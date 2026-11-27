# magpie-android

把 [yetone/magpie](https://github.com/yetone/magpie)（把你的所有 AI 订阅计划汇成一个池子的本地网关）打包成**安卓 App**：界面用 [Miuix](https://github.com/compose-miuix-ui/miuix) 做成 HyperOS/MIUI 观感，守护进程用 root 常驻，避免被国产 ROM 杀后台。

## 下载

APK 在 [Releases](https://github.com/ciyy9484-ship-it/magpie-android/releases) 里：`magpie-android-arm64.apk`（**arm64**，Android 8.0+）。

## 它做什么

- APK 把官方 `magpie-cli-android-arm64` 放进 `jniLibs/arm64-v8a/libmagpie-core.so`，`extractNativeLibs=true` 才能在 Android 10+ 执行
- 启动后跑 `magpie serve --web=127.0.0.1:3430`，网关在 `3425`
- **root 模式**：二进制拷到 `/data/adb/magpie/magpie`，用 `su` 起独立进程，`oom_score_adj=-1000`，脱离 App 的 cgroup —— 强杀 App 不影响它
- **普通模式**（没有 root）：前台服务 + 通知，能用但会被 ROM 杀
- 守护脚本 `while true`，进程挂了 3 秒自愈；App 端还有看门狗
- 开机自启：root 写 `/data/adb/service.d/99-magpie.sh`，否则走系统开机广播
- 界面是 WebView 打开 magpie 自己的网页（供应商 / 路由组 / 用量 / 插件 / MCP 全在里面）

## 构建

```bash
./scripts/fetch-payload.sh        # 下载 104MB 二进制 + 图标（不进仓库）
./gradlew :app:assembleRelease \
  -PMAGPIE_VERSION=0.1.4868 -PREV=0
# 产物：app/build/outputs/apk/release/app-release.apk
```

要求 JDK 17+、Android SDK（`local.properties` 里写 `sdk.dir=...`，需要 platform 37 + build-tools 36）。
版本号规则：`versionCode = major*1e6 + minor*1e4 + patch*10 + REV`，跟随 magpie 官方 release，保证覆盖安装是升级。

**签名**：密钥不进仓库。想复用自己的签名，把 keystore 放 `keystore/magpie.keystore`，密码/别名用 `-PMAGPIE_KEYSTORE_PASS=` 等属性覆盖；没有 keystore 时 release 构建不签名。

技术栈：Kotlin 2.4 + Jetpack Compose + AGP 9 + Miuix 0.9.2（`compileSdk 37`，`minSdk 24`，`targetSdk 34`）。

## 更新

- 有 root：App 里「检查更新」→「更新 magpie」，走 `magpie update` 原位替换守护二进制
- 没有 root：重新构建 APK 覆盖安装（配置在 App 私有目录，覆盖安装保留）
- 更新后不会被 APK 内置的旧二进制回滚：`installRootBinary` 先比版本号

## 插件 / MCP

不用额外装运行时：magpie 会自己下载 Bun，官方有 `bun-linux-aarch64-android` 构建。首次使用约 90MB，走 GitHub。

## 踩过的坑（都在代码注释里）

- **`kill -9 -1` 会把整台手机打重启**：早期按 pid 文件杀进程，列表为空时拼出了 `kill -9 -1`，root 下等于杀光所有进程。现在只杀 `/proc/<pid>/cmdline` 里验证过是 magpie 的进程，pkill 用 `libmagpie[-]core.so` 这种写法避免匹配到自己
- **Android 11+ 要求 `resources.arsc` 不压缩且 4 字节对齐**
- **主线程连 localhost socket 会抛 `NetworkOnMainThreadException`**：状态探测必须放后台线程，否则看门狗会把活着的守护反复杀掉
- **AGP 9 内置 Kotlin**：不能再 apply `org.jetbrains.kotlin.android`
- **不要给"以 root 执行的脚本"所在目录开 0777**：`umask 077` + 只 chown 属主
- 用户输入（代理）拼进 shell 命令前要白名单校验 + 单引号转义

## 许可

壳是 MIT（见 [LICENSE](LICENSE)）。`magpie` 本体是 [MIT](https://github.com/yetone/magpie/blob/main/LICENSE)，版权归 yetone；APK 里打包的是它的官方 release 产物。界面组件来自 [Miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）。
