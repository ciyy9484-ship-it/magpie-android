# magpie-android

把 [yetone/magpie](https://github.com/yetone/magpie)（把你的所有 AI 订阅计划汇成一个池子的本地网关）打包成一个**安卓 APK**，用 root 守护进程常驻，避免被国产 ROM 杀掉后台。

The official `magpie-cli-android-arm64` build, wrapped in a tiny Android app that runs it as a persistent daemon behind a WebView.

## 它做什么

- APK 内置官方 `magpie-cli-android-arm64`（放进 `lib/arm64-v8a/libmagpie-core.so`，`extractNativeLibs=true` 才能在 Android 10+ 执行）
- 启动时拉起 `magpie serve --web=127.0.0.1:3430`，网关在 `3425`
- **root 模式**：二进制拷到 `/data/adb/magpie/magpie`（root 管理器目录，不会被系统清），用 `su` 起独立进程，`oom_score_adj=-1000`，脱离 App 的 cgroup —— 强杀 App 也不影响它
- **普通模式**（没有 root）：前台服务 + 通知，能用但会被 ROM 杀
- 守护脚本 `while true` 循环，进程挂了 3 秒自愈；App 端另有看门狗
- 开机自启：root 写 `/data/adb/service.d/99-magpie.sh`，否则走系统开机广播
- 界面是 WebView 打开 magpie 自己的网页，配置齐全（供应商 / 路由组 / 用量 / 插件 / MCP）

## 需求

- Android 8.0+（`minSdk 24`）、**arm64** 设备
- root（Magisk / KernelSU / APatch）可选但要"杀不死"就必须有

## 构建

```bash
cd work/android
VERSION=0.1.4936 bash build.sh     # 自动下载该 release 的 android 二进制
REV=2 VERSION=0.1.4936 bash build.sh   # 同一版本重打包时递增 REV
```

产物在 `outputs/magpie-android-arm64.apk`。不需要 Gradle，脚本直接走 `aapt2 → javac → d8 → zipalign → apksigner`。

依赖：
- JDK（`D:/Java` 或改 `build.sh` 里的 `KEYTOOL`）
- Android build-tools + platform，默认找 `/tmp/sdk/android-16`（build-tools 36.1）和 `/tmp/sdk/android-35/android.jar`

> 签名密钥 `work/android/magpie.keystore` **不在仓库里**。自己构建会用新密钥，所以无法覆盖安装别人构建的同包名 APK（要先卸载，配置会丢）。

## 更新

- 有 root：App 里「检查更新」→「更新 magpie」，走 `magpie update` 原位替换守护二进制
- 没有 root：重新构建 APK 覆盖安装（配置在 App 私有目录，覆盖安装保留）
- 更新后不会被 APK 内置的旧版二进制回滚：`installRootBinary` 先比较版本号

## 踩过的坑（值得记下来）

- **Android 11+ 要求 `resources.arsc` 不压缩且 4 字节对齐**（targetSdk≥30），否则装不上，报"解析包时出现问题"
- **主线程连 localhost socket 会抛 `NetworkOnMainThreadException`**：`127.0.0.1` 也不例外。状态探测必须放后台线程，否则会误判"没起来"，看门狗把活着的守护反复杀掉
- **Android 14 禁止后台广播接收器 `startForegroundService`**（`code:DENIED`），只能从 Activity 启；`BOOT_COMPLETED` 是豁免的
- 锁屏（PIN）时 `screencap` 全黑是安全锁屏挡的，不是 bug
- `adb forward` 转发大响应体会得到空 body（headers 正常），别用它判断服务端
- build-tools 34 的 d8 8.2 对 javac 21 生成的类文件会 NPE，用 36.x

## 插件 / MCP

不用额外装运行时：magpie 会自己下载 Bun，官方有 `bun-linux-aarch64-android` 构建。首次使用约 90MB，走 GitHub。

## 许可

本项目只是壳。`magpie` 本体是 [MIT](https://github.com/yetone/magpie/blob/main/LICENSE)，版权归 yetone；APK 里打包的是它的官方 release 产物。
