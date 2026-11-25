# Magpie Native — Magisk 模块

> **核心上游来自 [yetone/magpie](https://github.com/yetone/magpie)**（MIT）。
> 本项目只是把它的 Android arm64 内核包成 Magisk 模块，内核一行没改。
> 所有 AI 网关能力、供应商适配、网页后台，都是上游的功劳。

把 magpie 的 Android 原生内核做成 Magisk 模块：开机自启、崩溃自动重启、数据便携化，不依赖 APK。

- 内核 **v0.1.4740**（上游 `magpie-cli-android-arm64`，arm64，sha256 与官方 SHA256SUMS 一致）
- 模块 id `magpie_native` ｜ 网关 **3425** ｜ 网页后台 **3430**
- 支持 Magisk / KernelSU / APatch
- 模块与移植：酷安 [@图图没耳朵](https://www.coolapk.com/u/1642567)

## 安装

1. 下载 `magpie-native-v0.1.4740-r2.zip`
2. 模块管理器 → 从存储安装 → 重启
3. 自检：

```sh
su -c "sh /data/adb/modules/magpie_native/scripts/selfcheck.sh"
```

10 项逐条打 `[OK]` / `[FAIL]`，哪项挂了看下面「常见问题」。

## 端口

| 端口 | 用途 |
|---|---|
| 3425 | AI 网关（OpenAI 兼容 API） |
| 3430 | 网页后台（浏览器打开） |

网页后台的 key 在 `/data/adb/modules/magpie_native/webkey.txt`，首次启动生成后固定不变。

## 目录布局

```
/data/adb/modules/magpie_native/
├── magpie              # 内核，必须在模块根
├── .portable           # 便携标记，与 magpie 同级
├── data/               # 配置与用量账本（700/600）
├── home/               # agent 的 $HOME
├── webkey.txt          # 网页后台固定 key
├── logs/               # magpie.log / watchdog.log
└── scripts/            # watchdog / restart / stop / bridge / selfcheck
```

`magpie`、`.portable`、`data/` 三者必须同级 —— 内核按「二进制旁边的 `data/`」判便携目录，放错位置会读到一个空目录，表现为网页后台一片空白且不报错。

## 常用命令

```sh
M=/data/adb/modules/magpie_native
su -c "sh $M/scripts/selfcheck.sh"   # 自检
su -c "sh $M/scripts/restart.sh $M"  # 重启内核
su -c "sh $M/scripts/stop.sh $M"     # 停内核 + watchdog
```

## 常见问题

**KernelSU 报「magpie 内核不存在，zip 包可能损坏」**
r1 及更早的 bug，KernelSU 的 metainstall 用相对路径执行脚本导致目录解析失败。r2 已修，装 r2 后第一行会打印 `- 模块目录：/data/adb/...` 绝对路径。

**网页后台打开是空的**
数据目录错位。检查 `ls /data/adb/modules/magpie_native/bin/` 有没有 `data/`，有就是旧布局。

**3425 起不来，watchdog 一直刷 WARN**
旧 APK 版还开着。`netstat -tlnp | grep 3425` 看是谁。

**日志里 `magpie: HOME is not set`**
r1 及更早的 bug，r2 已修。

更多细节见 **[完整手册](docs/manual.md)**（换内核、DNS、数据迁移、watchdog 机制、卸载）。

## 从 APK 版迁移

装模块时自动检测 `/data/data/com.magpie.android/files/magpie/data/` 并迁移，`usage.jsonl` 做行数校验，`settings.json` / `providers.json` 做 md5 校验。迁移后记得停用旧 APK，两者抢 3425。

## 相关

- **核心上游：[yetone/magpie](https://github.com/yetone/magpie)**（MIT）—— 内核、网关、网页后台全部来自这里
- 模块版 App 源码：[app-source 分支](../../tree/app-source)（`com.magpie.module`，704KB，只探测不启动内核）

## 许可

MIT。magpie 内核版权归 [yetone](https://github.com/yetone)，本仓库只做模块封装与 Android 移植，见 [LICENSE](LICENSE)。
