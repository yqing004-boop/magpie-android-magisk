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

1. 下载 `magpie-native-v0.1.4740-r3.zip`
2. 模块管理器 → 从存储安装 → 重启
3. 自检：

```sh
su -c "sh /data/adb/modules/magpie_native/scripts/selfcheck.sh"
```

10 项逐条打 `[OK]` / `[FAIL]`，哪项挂了看下面「常见问题」。

## 模块页开关：即时启停，不用重启

模块管理器（KernelSU / Magisk / APatch）详情页有一颗按钮，点一下启停网关。

```
KernelSU:  模块 → Magpie Native → 右下角播放键
Magisk:    模块 → Magpie Native → 操作按钮
```

两种「停」不一样：

| 操作 | 效果 |
|---|---|
| 右上角开关拨到关 | 写 `disable`，内核停，**重启手机也不会起**（模块整个禁用） |
| 点那颗按钮 | 写 `stop.flag`，内核停，**重启手机后会自己恢复** |

命令行：

```sh
M=/data/adb/modules/magpie_native
su -c "sh $M/action.sh"              # 一键启停（自动判断方向）
su -c "sh $M/scripts/stop.sh $M"     # 明确停
su -c "sh $M/scripts/start.sh $M"    # 明确起
```

## 请求多了会把手机拖死？

r3 之前会。上游的 HTTP 服务没有并发上限，几十个流同时跑就吃满所有核，
system_server 抢不到 CPU 就弹「进程 system 没有响应」。

r3 用 `scripts/limit.sh` 从进程层压住：`nice 15` + 绑到小核 + I/O 最低优先级。
网关吞吐几乎不变（瓶颈在网络和供应商 API），但系统卡死消失。

自检里能看到：`[OK] 内核已降优先级 nice=15（CPU=0-3）`

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
├── action.sh           # 模块页那颗按钮（一键启停）
└── scripts/            # watchdog / limit / restart / start / stop / bridge / selfcheck
```

`magpie`、`.portable`、`data/` 三者必须同级 —— 内核按「二进制旁边的 `data/`」判便携目录，放错位置会读到一个空目录，表现为网页后台一片空白且不报错。

## 常用命令

```sh
M=/data/adb/modules/magpie_native
su -c "sh $M/scripts/selfcheck.sh"   # 自检（含开关与限流状态）
su -c "sh $M/scripts/restart.sh $M"  # 重启内核
su -c "sh $M/scripts/stop.sh $M"     # 停内核 + watchdog
su -c "sh $M/scripts/start.sh $M"    # 启动
su -c "sh $M/scripts/limit.sh --all" # 手动补一次限流
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

**模块页开关拨了没反应**
r2 及更早不读 `disable` 文件。r3 已修：拨关最多 10 秒内核停；拨开要点一下模块页那颗按钮才会起（watchdog 在 disable 期间自己也会退出）。

**手机卡死 / 「进程 system 没有响应」**
r2 及更早没有限流。r3 已修。检查 nice：
```sh
su -c "awk '{print \$19}' /proc/$(cat /data/adb/modules/magpie_native/magpie.pid)/stat"
```
应该是 15。是 0 就跑 `su -c "sh /data/adb/modules/magpie_native/scripts/limit.sh --all"`。

更多细节见 **[完整手册](docs/manual.md)**（换内核、DNS、数据迁移、watchdog 机制、卸载）。

## 从 APK 版迁移

装模块时自动检测 `/data/data/com.magpie.android/files/magpie/data/` 并迁移，`usage.jsonl` 做行数校验，`settings.json` / `providers.json` 做 md5 校验。迁移后记得停用旧 APK，两者抢 3425。

## 相关

- **核心上游：[yetone/magpie](https://github.com/yetone/magpie)**（MIT）—— 内核、网关、网页后台全部来自这里
- 模块版 App 源码：[app-source 分支](../../tree/app-source)（`com.magpie.module`，704KB，只探测不启动内核）

## 许可

MIT。magpie 内核版权归 [yetone](https://github.com/yetone)，本仓库只做模块封装与 Android 移植，见 [LICENSE](LICENSE)。
