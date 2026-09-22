package org.lyi.cha;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** cloudflared 版本管理：查看远端发布列表、下载任意版本、切换/删除本地版本 */
public class VersionActivity extends AppCompatActivity implements VersionAdapter.Callbacks {

    private CloudflaredManager mgr;
    private VersionAdapter adapter;
    private final List<VersionAdapter.Item> items = new ArrayList<>();
    private List<CloudflaredManager.Release> remote = new ArrayList<>();
    private LinearProgressIndicator progress;
    private TextView header;
    private View root;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_versions);
        mgr = new CloudflaredManager(this);

        root = findViewById(R.id.root);
        header = findViewById(R.id.tvHeader);
        progress = findViewById(R.id.progress);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) {
                fetchRemote();
                return true;
            }
            return false;
        });

        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VersionAdapter(items, this);
        rv.setAdapter(adapter);

        rebuild();
        fetchRemote();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void rebuild() {
        items.clear();
        String active = mgr.getActiveVersion();
        List<String> installed = mgr.installedVersions();
        Set<String> seen = new HashSet<>();

        for (CloudflaredManager.Release r : remote) {
            seen.add(r.tag);
            items.add(new VersionAdapter.Item(r.tag, r.date, r.prerelease,
                    installed.contains(r.tag), r.tag.equals(active)));
        }
        for (String v : installed) {
            if (!seen.contains(v)) {
                items.add(new VersionAdapter.Item(v, "", false, true, v.equals(active)));
            }
        }
        Collections.sort(items, (a, b) -> CloudflaredManager.compareVersions(b.tag, a.tag));
        adapter.notifyDataSetChanged();

        header.setText("架构: " + CloudflaredManager.archName()
                + "  ·  当前使用: " + (active == null ? "未安装" : active)
                + "  ·  本地已安装 " + installed.size() + " 个版本"
                + (remote.isEmpty() ? "\n下拉菜单点击刷新以获取在线版本列表" : ""));
    }

    private void fetchRemote() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                List<CloudflaredManager.Release> list = mgr.fetchReleases();
                main.post(() -> {
                    remote = list;
                    progress.setVisibility(View.GONE);
                    rebuild();
                });
            } catch (Exception e) {
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    snack("获取在线版本列表失败: " + e.getMessage());
                });
            }
        });
    }

    // ------------------------------------------------------------ 回调

    @Override
    public void onDownload(VersionAdapter.Item item) {
        DownloadDialog.start(this, mgr, item.tag, new DownloadDialog.Callback() {
            @Override
            public void onSuccess(String version) {
                rebuild();
                if (version.equals(mgr.getActiveVersion())) {
                    snack("已下载并启用 " + version);
                    return;
                }
                new MaterialAlertDialogBuilder(VersionActivity.this)
                        .setTitle("下载完成")
                        .setMessage("cloudflared " + version + " 已下载，是否设为当前使用版本？")
                        .setPositiveButton("设为当前", (d, w) -> useVersion(version))
                        .setNegativeButton("暂不", null)
                        .show();
            }

            @Override
            public void onFailure(Exception e) {
                new MaterialAlertDialogBuilder(VersionActivity.this)
                        .setTitle("下载失败")
                        .setMessage(String.valueOf(e.getMessage()))
                        .setPositiveButton("重试", (d, w) -> onDownload(item))
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    @Override
    public void onUse(VersionAdapter.Item item) {
        useVersion(item.tag);
    }

    private void useVersion(String version) {
        mgr.setActiveVersion(version);
        rebuild();
        snack("已切换到 " + version + (TunnelService.runningCount() > 0 ? "，重启隧道后生效" : ""));
    }

    @Override
    public void onDelete(VersionAdapter.Item item) {
        String msg = "确定删除本地的 cloudflared " + item.tag + " 吗？";
        if (item.active) msg += "\n\n这是当前使用的版本，删除后将自动切换到其他已安装版本（若无则需重新下载）。";
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除版本")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> {
                    if (mgr.deleteVersion(item.tag)) snack("已删除 " + item.tag);
                    else snack("删除失败");
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void snack(String msg) {
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
    }
}
