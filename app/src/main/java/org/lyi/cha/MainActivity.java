package org.lyi.cha;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements TunnelAdapter.Callbacks {

    private static final int MENU_EDIT = 1, MENU_LOGS = 2, MENU_COPY = 3, MENU_DELETE = 4;

    private TunnelStore store;
    private CloudflaredManager mgr;
    private List<Tunnel> tunnels;
    private TunnelAdapter adapter;
    private View root, emptyView;
    private ExtendedFloatingActionButton fab;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean updateChecked = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new TunnelStore(this);
        mgr = new CloudflaredManager(this);
        tunnels = store.load();

        root = findViewById(R.id.root);
        emptyView = findViewById(R.id.emptyView);
        fab = findViewById(R.id.fab);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);

        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TunnelAdapter(tunnels, this);
        rv.setAdapter(adapter);

        fab.setOnClickListener(v -> showTunnelDialog(null));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        refreshEmpty();
        ensureBinary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TunnelService.setListener(id -> main.post(() -> adapter.notifyDataSetChanged()));
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onPause() {
        TunnelService.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ cloudflared 下载 / 更新

    /** 首次启动：没有二进制则引导下载；否则静默检查更新 */
    private void ensureBinary() {
        if (!mgr.isReady()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("需要下载 cloudflared")
                    .setMessage("首次使用需要下载 cloudflared 二进制文件（架构: "
                            + CloudflaredManager.archName() + "），文件将保存在应用私有目录中。")
                    .setCancelable(false)
                    .setPositiveButton("开始下载", (d, w) -> startDownload(null))
                    .setNegativeButton("退出", (d, w) -> finish())
                    .show();
        } else if (!updateChecked) {
            updateChecked = true;
            checkUpdateSilently();
        }
    }

    private void startDownload(@Nullable String version) {
        DownloadDialog.start(this, mgr, version, new DownloadDialog.Callback() {
            @Override
            public void onSuccess(String v) {
                mgr.setActiveVersion(v);
                String msg = "cloudflared " + v + " 已就绪";
                if (TunnelService.runningCount() > 0) msg += "，重新启动隧道后生效";
                snack(msg);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onFailure(Exception e) {
                boolean ready = mgr.isReady();
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("下载失败")
                        .setMessage(String.valueOf(e.getMessage()) + "\n\n请检查网络（需要能访问 GitHub）后重试。")
                        .setCancelable(ready)
                        .setPositiveButton("重试", (d, w) -> startDownload(version))
                        .setNegativeButton(ready ? "取消" : "退出", (d, w) -> {
                            if (!ready) finish();
                        })
                        .show();
            }
        });
    }

    private void checkUpdateSilently() {
        io.execute(() -> {
            try {
                String latest = mgr.fetchLatestVersion();
                String active = mgr.getActiveVersion();
                if (active == null) return;
                if (CloudflaredManager.compareVersions(latest, active) > 0
                        && !latest.equals(mgr.getSkippedVersion())) {
                    main.post(() -> showUpdateDialog(latest, active));
                }
            } catch (Exception ignored) {
                // 静默失败：无网络等情况不打扰用户
            }
        });
    }

    private void showUpdateDialog(String latest, String current) {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("发现 cloudflared 新版本")
                .setMessage("当前版本：" + current + "\n最新版本：" + latest + "\n\n是否现在下载更新？"
                        + (TunnelService.runningCount() > 0 ? "\n（正在运行的隧道需重启后使用新版本）" : ""))
                .setPositiveButton("立即更新", (d, w) -> startDownload(latest))
                .setNegativeButton("稍后", null)
                .setNeutralButton("忽略此版本", (d, w) -> mgr.setSkippedVersion(latest))
                .show();
    }

    // ------------------------------------------------------------ 隧道增删改

    private void showTunnelDialog(@Nullable Tunnel existing) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_tunnel, null);
        TextInputLayout tilHost = v.findViewById(R.id.tilHost);
        TextInputLayout tilPort = v.findViewById(R.id.tilPort);
        TextInputEditText etName = v.findViewById(R.id.etName);
        TextInputEditText etHost = v.findViewById(R.id.etHost);
        TextInputEditText etPort = v.findViewById(R.id.etPort);

        if (existing != null) {
            etName.setText(existing.name);
            etHost.setText(existing.hostname);
            etPort.setText(String.valueOf(existing.port));
        }

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "添加隧道" : "编辑隧道")
                .setView(v)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String name = text(etName);
            String host = text(etHost);
            String portStr = text(etPort);
            tilHost.setError(null);
            tilPort.setError(null);

            if (host.startsWith("https://")) host = host.substring(8);
            if (host.startsWith("http://")) host = host.substring(7);
            while (host.endsWith("/")) host = host.substring(0, host.length() - 1);

            boolean ok = true;
            if (host.isEmpty() || host.contains(" ") || host.contains("/") || !host.contains(".")) {
                tilHost.setError("请输入有效的主机名，例如 ssh.example.com");
                ok = false;
            }
            int port = -1;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ignored) {
            }
            if (port < 1 || port > 65535) {
                tilPort.setError("端口范围 1 - 65535");
                ok = false;
            } else {
                for (Tunnel o : tunnels) {
                    if (o != existing && o.port == port) {
                        tilPort.setError("端口已被隧道「" + o.name + "」使用");
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) return;
            if (name.isEmpty()) name = host;

            if (existing == null) {
                Tunnel t = new Tunnel();
                t.name = name;
                t.hostname = host;
                t.port = port;
                tunnels.add(t);
                adapter.notifyItemInserted(tunnels.size() - 1);
            } else {
                existing.name = name;
                existing.hostname = host;
                existing.port = port;
                adapter.notifyDataSetChanged();
                if (TunnelService.isRunning(existing.id)) snack("修改将在重新启动该隧道后生效");
            }
            store.save(tunnels);
            refreshEmpty();
            dlg.dismiss();
        }));
        dlg.show();
    }

    private void confirmDelete(Tunnel t) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除隧道")
                .setMessage("确定删除「" + t.name + "」吗？" + (TunnelService.isRunning(t.id) ? "该隧道正在运行，将被停止。" : ""))
                .setPositiveButton("删除", (d, w) -> {
                    if (TunnelService.isRunning(t.id)) TunnelService.stop(this, t.id);
                    int idx = tunnels.indexOf(t);
                    if (idx >= 0) {
                        tunnels.remove(idx);
                        adapter.notifyItemRemoved(idx);
                    }
                    store.save(tunnels);
                    refreshEmpty();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLogs(Tunnel t) {
        String log = TunnelService.getLog(t.id);
        if (log.isEmpty()) log = "暂无日志。\n启动隧道后可在此查看 cloudflared 的输出。";
        final String content = log;

        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        new MaterialAlertDialogBuilder(this)
                .setTitle(t.name + " · 日志")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (d, w) -> copy("log", content))
                .show();
    }

    // ------------------------------------------------------------ Adapter 回调

    @Override
    public void onToggle(Tunnel t, boolean on) {
        if (on) {
            if (!mgr.isReady()) {
                adapter.notifyDataSetChanged();
                ensureBinary();
                return;
            }
            TunnelService.start(this, t);
        } else {
            TunnelService.stop(this, t.id);
        }
    }

    @Override
    public void onMore(Tunnel t, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, MENU_EDIT, 0, "编辑");
        m.add(0, MENU_LOGS, 1, "查看日志");
        m.add(0, MENU_COPY, 2, "复制本地地址");
        m.add(0, MENU_DELETE, 3, "删除");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_EDIT:
                    showTunnelDialog(t);
                    return true;
                case MENU_LOGS:
                    showLogs(t);
                    return true;
                case MENU_COPY:
                    copy("address", t.localAddress());
                    snack("已复制 " + t.localAddress());
                    return true;
                case MENU_DELETE:
                    confirmDelete(t);
                    return true;
            }
            return false;
        });
        pm.show();
    }

    // ------------------------------------------------------------ 菜单

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_versions) {
            startActivity(new Intent(this, VersionActivity.class));
            return true;
        } else if (id == R.id.action_battery) {
            requestIgnoreBatteryOptimizations();
            return true;
        } else if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return false;
    }

    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            snack("已忽略电池优化，后台保活效果最佳");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                snack("无法打开电池优化设置");
            }
        }
    }

    private void showAbout() {
        String active = mgr.getActiveVersion();
        String msg = "通过 cloudflared access tcp 将 Cloudflare Tunnel 暴露的 TCP 服务映射到本机端口。\n\n"
                + "cloudflared 版本：" + (active == null ? "未安装" : active) + "\n"
                + "设备架构：" + CloudflaredManager.archName() + "\n"
                + "已安装版本数：" + mgr.installedVersions().size() + "\n"
                + "版本安装目录：" + mgr.baseDir().getAbsolutePath();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setIcon(R.drawable.ic_app_logo)
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .setNeutralButton("检查更新", (d, w) -> {
                    updateChecked = false;
                    io.execute(() -> {
                        try {
                            String latest = mgr.fetchLatestVersion();
                            String cur = mgr.getActiveVersion();
                            main.post(() -> {
                                if (cur != null && CloudflaredManager.compareVersions(latest, cur) > 0) {
                                    showUpdateDialog(latest, cur);
                                } else {
                                    snack("已是最新版本 " + latest);
                                }
                            });
                        } catch (Exception e) {
                            main.post(() -> snack("检查更新失败: " + e.getMessage()));
                        }
                    });
                })
                .show();
    }

    // ------------------------------------------------------------ 工具

    private void refreshEmpty() {
        emptyView.setVisibility(tunnels.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void snack(String msg) {
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).setAnchorView(fab).show();
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
