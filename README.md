# CF Tunnel (Android)

一个使用 **纯 Java + Material You (Material 3)** 编写的 Android 客户端，通过
`cloudflared access tcp` 将 Cloudflare Tunnel 暴露的远端 TCP 服务映射到本机端口。

## 功能

- **多隧道支持**：添加任意数量的隧道（远端主机名 → `127.0.0.1:本地端口`），逐个启停、查看日志。
- **cloudflared 版本管理**
  - 首次启动自动下载最新版 cloudflared 到应用私有目录（显示进度 / 速度）。
  - 每次启动静默检查 GitHub Releases，有新版本时弹窗提示（可稍后 / 忽略此版本）。
  - 版本管理页可下载任意历史版本、切换当前使用版本、删除本地版本。
- **前台服务保活**：隧道进程由前台服务托管，常驻通知 + 部分唤醒锁，支持一键全部停止。
- **Material You**：Android 12+ 自动跟随系统动态取色，支持深色模式。
- **自适应矢量图标**（含 Android 13 单色主题图标）。

## 重要说明：targetSdkVersion = 28

Android 10 及以上对 `targetSdk >= 29` 的应用施加了 W^X 限制，禁止执行应用数据目录中的
可执行文件（`cloudflared` 二进制）。因此本项目 **必须保持 `targetSdk 28`**，请勿升级。

## 构建

```bash
gradle assembleDebug      # 或使用 Android Studio 打开项目
```

GitHub Actions（`.github/workflows/build.yml`）会在 push / PR / 手动触发时构建 Debug 与
Release APK 并上传为 Artifacts；推送 `v*` 标签时自动发布 Release。

如需对 Release 签名，在仓库 Secrets 中配置：
`KEYSTORE_BASE64`（keystore 文件 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

## 目录结构

```
app/src/main/java/org/lyi/cha/
  App.java                 Application，启用动态取色
  MainActivity.java        隧道列表 / 添加编辑 / 首次下载 / 更新检查
  VersionActivity.java     cloudflared 版本管理
  TunnelService.java       前台服务，托管 cloudflared 子进程
  CloudflaredManager.java  下载、版本存储、GitHub API
  DownloadDialog.java      带进度和速度的下载对话框
  Tunnel.java / TunnelStore.java   隧道模型与持久化
  TunnelAdapter.java / VersionAdapter.java
```
