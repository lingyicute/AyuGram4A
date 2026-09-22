package org.lyi.cha;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前台服务：托管所有正在运行的 cloudflared 子进程，
 * 通过常驻通知 + 部分唤醒锁保活。
 */
public class TunnelService extends Service {
    public static final String ACTION_START = "org.lyi.cha.action.START";
    public static final String ACTION_STOP = "org.lyi.cha.action.STOP";
    public static final String ACTION_STOP_ALL = "org.lyi.cha.action.STOP_ALL";
    public static final String EXTRA_ID = "tunnel_id";

    private static final String CHANNEL_ID = "tunnels";
    private static final int NOTIF_ID = 1;
    private static final int MAX_LOG_LINES = 300;

    public interface Listener {
        void onStateChanged(String tunnelId);
    }

    private static final Map<String, Process> processes = new ConcurrentHashMap<>();
    private static final Map<String, ArrayDeque<String>> logs = new ConcurrentHashMap<>();
    private static final Map<String, String> descriptions = new ConcurrentHashMap<>();
    private static volatile Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    // ------------------------------------------------------------ 静态 API（供 Activity 调用）

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean isRunning(String id) {
        Process p = processes.get(id);
        return p != null && p.isAlive();
    }

    public static int runningCount() {
        int n = 0;
        for (Process p : processes.values()) if (p.isAlive()) n++;
        return n;
    }

    public static String getLog(String id) {
        ArrayDeque<String> q = logs.get(id);
        if (q == null) return "";
        synchronized (q) {
            return TextUtils.join("\n", q);
        }
    }

    public static void start(Context c, Tunnel t) {
        Intent i = new Intent(c, TunnelService.class).setAction(ACTION_START).putExtra(EXTRA_ID, t.id);
        ContextCompat.startForegroundService(c, i);
    }

    public static void stop(Context c, String id) {
        Intent i = new Intent(c, TunnelService.class).setAction(ACTION_STOP).putExtra(EXTRA_ID, id);
        ContextCompat.startForegroundService(c, i);
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cftunnel:tunnels");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action != null) {
            String id = intent.getStringExtra(EXTRA_ID);
            switch (action) {
                case ACTION_START: {
                    Tunnel t = new TunnelStore(this).find(id);
                    if (t != null) startTunnel(t);
                    break;
                }
                case ACTION_STOP:
                    stopTunnel(id);
                    break;
                case ACTION_STOP_ALL:
                    stopAll();
                    break;
            }
        }
        updateNotification();
        stopIfIdle();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        for (Map.Entry<String, Process> e : processes.entrySet()) {
            e.getValue().destroy();
            appendLog(e.getKey(), "[服务销毁] 隧道已停止");
        }
        processes.clear();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 进程管理

    private void startTunnel(Tunnel t) {
        if (isRunning(t.id)) return;
        logs.put(t.id, new ArrayDeque<>());
        descriptions.put(t.id, t.name + "  →  " + t.localAddress());

        File bin = new CloudflaredManager(this).getActiveBinary();
        if (bin == null) {
            appendLog(t.id, "[错误] 尚未安装 cloudflared，请先在应用内下载");
            notifyChanged(t.id);
            return;
        }

        List<String> cmd = Arrays.asList(
                bin.getAbsolutePath(), "access", "tcp",
                "--hostname", t.hostname,
                "--url", t.localAddress());
        appendLog(t.id, "$ " + TextUtils.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.directory(getFilesDir());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            pb.environment().put("NO_AUTOUPDATE", "true");
            final Process p = pb.start();
            processes.put(t.id, p);

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) appendLog(t.id, line);
                } catch (IOException ignored) {
                }
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                appendLog(t.id, "[退出] cloudflared 进程已结束，退出码 " + code);
                processes.remove(t.id, p);
                notifyChanged(t.id);
                main.post(() -> {
                    updateNotification();
                    stopIfIdle();
                });
            }, "cf-reader-" + t.port);
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            appendLog(t.id, "[错误] 启动失败: " + e.getMessage());
            processes.remove(t.id);
        }
        notifyChanged(t.id);
    }

    private void stopTunnel(String id) {
        if (id == null) return;
        Process p = processes.remove(id);
        if (p != null) {
            p.destroy();
            appendLog(id, "[停止] 用户已停止隧道");
        }
        notifyChanged(id);
    }

    private void stopAll() {
        List<String> ids = new ArrayList<>(processes.keySet());
        for (String id : ids) stopTunnel(id);
    }

    private void stopIfIdle() {
        if (runningCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private static void appendLog(String id, String line) {
        ArrayDeque<String> q = logs.get(id);
        if (q == null) {
            q = new ArrayDeque<>();
            logs.put(id, q);
        }
        synchronized (q) {
            q.addLast(line);
            while (q.size() > MAX_LOG_LINES) q.pollFirst();
        }
    }

    private static void notifyChanged(String id) {
        Listener l = listener;
        if (l != null) l.onStateChanged(id);
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("显示正在运行的 Cloudflare TCP 隧道");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        int n = runningCount();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Process> e : processes.entrySet()) {
            if (!e.getValue().isAlive()) continue;
            if (sb.length() > 0) sb.append('\n');
            String d = descriptions.get(e.getKey());
            sb.append(d != null ? d : e.getKey());
        }
        String text = n == 0 ? "没有正在运行的隧道" : sb.toString();

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stopAll = PendingIntent.getService(this, 1,
                new Intent(this, TunnelService.class).setAction(ACTION_STOP_ALL), flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tunnel)
                .setContentTitle(n == 0 ? getString(R.string.app_name) : n + " 个隧道运行中")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, "全部停止", stopAll)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }
}
