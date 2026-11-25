# Magpie 模块版 App（源码）

Magisk 模块 [magpie-android-magisk](https://github.com/yqing004-boop/magpie-android-magisk) 的配套 App。

包名 **`com.magpie.module`**，与老版 `com.magpie.android` 完全独立，可以同时装。

## 它和 APK 版有什么不同

| | APK 版 | 模块版（这个） |
|---|---|---|
| 内核在哪 | APK assets，释放到应用私有目录 | Magisk 模块 `/data/adb/modules/magpie_native/magpie` |
| 谁启动它 | App 自己 fork | 模块 `service.sh` + `watchdog.sh` |
| 谁保活 | 前台服务 + WakeLock | 模块 watchdog，不依赖 App |
| App 被杀 | 网关跟着死 | 网关继续跑 |
| APK 大小 | 99MB | **704KB** |

这个 App 只做三件事：探测 `127.0.0.1:3425` 是否活着、通过只读桥读模块的 `webkey.txt` 和日志、原生页直连 HTTP 接口画界面。

## 构建

不依赖 Gradle，直接 aapt2 → javac → d8 → zipalign → apksigner：

```sh
ANDROID_JAR=/path/to/android.jar \
ANDROID_SDK=/path/to/android-sdk \
VERSION_NAME=1.0.0 VERSION_CODE=1 \
sh scripts/build-apk-local.sh
```

需要 JDK 17、Android SDK build-tools（`lib/d8.jar`、`lib/apksigner.jar`）、
一份带 `resources.arsc` 的 `android.jar`（API ≤ 30）。
arm64 机器上仓库自带 `scripts/tools/aarch64/` 的 aapt2 与 zipalign。

产物：`dist/magpie-native-<version>.apk`

## 结构

```
app/src/main/java/com/magpie/module/
├── GatewayService.java     # 前台服务：只探测 3425，不 fork 内核
├── ModuleBridge.java       # 只读桥 + Shizuku/root 三条通路
├── NativeHome.java         # 原生主界面（四页）
├── MainActivity.java       # WebView 壳（插件/脱敏/备份等深度功能）
├── SettingsActivity.java   # 设置
├── LanSync.java            # 局域网开关同步
├── ShizukuShell.java       # Shizuku 执行器
├── DiagServer.java         # AI 排查端口
└── ...                     # 界面组件、数据层、缓存清理等
```

## 模块侧依赖

需要模块提供：

- `webkey.txt` — 网页后台 key（模块首次启动生成后固定）
- `scripts/restart.sh` / `stop.sh` — 启停内核
- `scripts/bridge.sh` — 只读桥，把 `webkey.txt` / `logs/` / `module.prop` 暴露到
  `/data/local/tmp/magpie-native/`（**不暴露 `data/`**）

模块源码见 `main` 分支。

## 许可

MIT。magpie 内核版权归 yetone。
