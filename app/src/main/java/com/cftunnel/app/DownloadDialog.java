package com.cftunnel.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Locale;

/** 带进度条、已下载大小、百分比和实时速度的 cloudflared 下载对话框 */
public class DownloadDialog {

    public interface Callback {
        void onSuccess(String version);

        void onFailure(Exception e);
    }

    /**
     * @param version 指定版本 tag；为 null 时自动获取最新版本
     */
    public static void start(Activity act, CloudflaredManager mgr, @Nullable String version, Callback cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_download, null);
        TextView tvStatus = v.findViewById(R.id.tvStatus);
        TextView tvProgress = v.findViewById(R.id.tvProgress);
        TextView tvSpeed = v.findViewById(R.id.tvSpeed);
        LinearProgressIndicator bar = v.findViewById(R.id.progress);
        bar.setMax(1000);
        bar.setProgress(0);

        tvStatus.setText(version == null ? "正在获取最新版本信息…" : "准备下载 cloudflared " + version + "…");
        tvProgress.setText("0 B");
        tvSpeed.setText("");

        AlertDialog dlg = new MaterialAlertDialogBuilder(act)
                .setTitle("下载 cloudflared")
                .setView(v)
                .setCancelable(false)
                .show();

        Handler main = new Handler(Looper.getMainLooper());
        Thread th = new Thread(() -> {
            try {
                final String ver = version != null ? version : mgr.fetchLatestVersion();
                main.post(() -> tvStatus.setText("正在下载 cloudflared " + ver + " (" + CloudflaredManager.archName() + ")…"));
                final long[] lastUi = {0};
                mgr.download(ver, (done, total, speed) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUi[0] < 150 && (total <= 0 || done < total)) return;
                    lastUi[0] = now;
                    main.post(() -> {
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            bar.setProgressCompat((int) (done * 1000 / total), true);
                            tvProgress.setText(fmt(done) + " / " + fmt(total) + "  (" + pct + "%)");
                        } else {
                            tvProgress.setText(fmt(done));
                        }
                        tvSpeed.setText(fmt((long) speed) + "/s");
                    });
                });
                main.post(() -> {
                    dismissSafely(act, dlg);
                    cb.onSuccess(ver);
                });
            } catch (Exception e) {
                main.post(() -> {
                    dismissSafely(act, dlg);
                    cb.onFailure(e);
                });
            }
        }, "cf-download");
        th.setDaemon(true);
        th.start();
    }

    private static void dismissSafely(Activity act, AlertDialog dlg) {
        if (!act.isFinishing() && !act.isDestroyed() && dlg.isShowing()) dlg.dismiss();
    }

    public static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.2f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }
}
