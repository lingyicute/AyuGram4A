package org.lyi.cha;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 负责 cloudflared 二进制的下载、存放、版本切换，以及通过 GitHub API 查询版本。
 * 目录结构：filesDir/cloudflared/<version>/cloudflared
 */
public class CloudflaredManager {
    private static final String PREFS = "cloudflared";
    private static final String KEY_ACTIVE = "active_version";
    private static final String KEY_SKIPPED = "skipped_version";

    private static final String API_LATEST = "https://api.github.com/repos/cloudflare/cloudflared/releases/latest";
    private static final String API_RELEASES = "https://api.github.com/repos/cloudflare/cloudflared/releases?per_page=30";
    private static final String DOWNLOAD_URL = "https://github.com/cloudflare/cloudflared/releases/download/%s/cloudflared-linux-%s";
    private static final String UA = "CFTunnel-Android/1.0 (+https://github.com/cloudflare/cloudflared)";

    public interface ProgressListener {
        void onProgress(long downloaded, long total, double bytesPerSecond);
    }

    public static class Release {
        public final String tag;
        public final String date;
        public final boolean prerelease;

        public Release(String tag, String date, boolean prerelease) {
            this.tag = tag;
            this.date = date;
            this.prerelease = prerelease;
        }
    }

    private final Context ctx;
    private final SharedPreferences prefs;

    public CloudflaredManager(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 架构 / 路径

    /** 将设备 ABI 映射为 cloudflared release 资产名后缀 */
    public static String archName() {
        for (String abi : Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a":
                    return "arm64";
                case "armeabi-v7a":
                    return "arm";
                case "x86_64":
                    return "amd64";
                case "x86":
                    return "386";
            }
        }
        return "arm64";
    }

    public File baseDir() {
        File f = new File(ctx.getFilesDir(), "cloudflared");
        if (!f.exists()) //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        return f;
    }

    public File versionDir(String version) {
        return new File(baseDir(), version);
    }

    public File binaryFor(String version) {
        return new File(versionDir(version), "cloudflared");
    }

    // ---------------------------------------------------------------- 已安装版本

    /** 已安装版本，按版本号从新到旧排序 */
    public List<String> installedVersions() {
        List<String> out = new ArrayList<>();
        File[] dirs = baseDir().listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && new File(d, "cloudflared").isFile()) out.add(d.getName());
            }
        }
        Collections.sort(out, (a, b) -> compareVersions(b, a));
        return out;
    }

    public synchronized String getActiveVersion() {
        String v = prefs.getString(KEY_ACTIVE, null);
        if (v != null && binaryFor(v).isFile()) return v;
        List<String> installed = installedVersions();
        if (installed.isEmpty()) {
            if (v != null) prefs.edit().remove(KEY_ACTIVE).apply();
            return null;
        }
        setActiveVersion(installed.get(0));
        return installed.get(0);
    }

    public void setActiveVersion(String version) {
        prefs.edit().putString(KEY_ACTIVE, version).apply();
    }

    public File getActiveBinary() {
        String v = getActiveVersion();
        if (v == null) return null;
        File f = binaryFor(v);
        if (!f.canExecute()) //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false);
        return f;
    }

    public boolean isReady() {
        return getActiveBinary() != null;
    }

    public String getSkippedVersion() {
        return prefs.getString(KEY_SKIPPED, null);
    }

    public void setSkippedVersion(String version) {
        prefs.edit().putString(KEY_SKIPPED, version).apply();
    }

    public boolean deleteVersion(String version) {
        boolean ok = deleteRecursive(versionDir(version));
        if (version.equals(prefs.getString(KEY_ACTIVE, null))) {
            prefs.edit().remove(KEY_ACTIVE).apply();
        }
        return ok;
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return !f.exists() || f.delete();
    }

    // ---------------------------------------------------------------- 网络

    private HttpURLConnection open(String url, boolean api) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        if (api) {
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        return c;
    }

    private String readString(String url) throws IOException {
        HttpURLConnection c = open(url, true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub API 返回 HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** 查询 GitHub 最新 release 的 tag（例如 2024.12.2） */
    public String fetchLatestVersion() throws IOException {
        try {
            return new JSONObject(readString(API_LATEST)).getString("tag_name");
        } catch (JSONException e) {
            throw new IOException("解析版本信息失败", e);
        }
    }

    /** 查询最近的 release 列表 */
    public List<Release> fetchReleases() throws IOException {
        try {
            JSONArray arr = new JSONArray(readString(API_RELEASES));
            List<Release> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optBoolean("draft", false)) continue;
                String tag = o.getString("tag_name");
                String date = o.optString("published_at", "");
                if (date.length() >= 10) date = date.substring(0, 10);
                out.add(new Release(tag, date, o.optBoolean("prerelease", false)));
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("解析版本列表失败", e);
        }
    }

    /**
     * 下载指定版本到私有目录并设置可执行权限。
     * 通过 listener 回调已下载字节数、总字节数（未知为 -1）与当前速度。
     */
    public void download(String version, ProgressListener listener) throws IOException {
        String url = String.format(DOWNLOAD_URL, version, archName());
        File dir = versionDir(version);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir);
        File tmp = new File(dir, "cloudflared.part");
        File dst = new File(dir, "cloudflared");

        HttpURLConnection c = open(url, false);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new IOException("未找到版本 " + version + " 的 " + archName() + " 构建 (HTTP 404)");
            if (code != 200) throw new IOException("下载失败: HTTP " + code);
            long total = c.getContentLengthLong();

            try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long downloaded = 0;
                long lastTime = System.currentTimeMillis();
                long lastBytes = 0;
                double speed = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastTime >= 300) {
                        speed = (downloaded - lastBytes) * 1000.0 / (now - lastTime);
                        lastTime = now;
                        lastBytes = downloaded;
                        if (listener != null) listener.onProgress(downloaded, total, speed);
                    }
                }
                out.flush();
                if (listener != null) listener.onProgress(downloaded, total, speed);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } finally {
            c.disconnect();
        }

        if (tmp.length() < 1024 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("下载的文件异常（大小过小），请重试");
        }
        if (dst.exists() && !dst.delete()) throw new IOException("无法替换旧文件");
        if (!tmp.renameTo(dst)) throw new IOException("无法保存文件");
        if (!dst.setExecutable(true, false)) throw new IOException("无法设置可执行权限");
        if (prefs.getString(KEY_ACTIVE, null) == null) setActiveVersion(version);
    }

    // ---------------------------------------------------------------- 版本比较

    /** 比较形如 2024.12.2 的版本号，a>b 返回正数 */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long x = i < pa.length ? num(pa[i]) : 0;
            long y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static long num(String s) {
        String d = s.replaceAll("[^0-9]", "");
        if (d.isEmpty()) return 0;
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
