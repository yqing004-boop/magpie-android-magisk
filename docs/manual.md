# Magpie Native — 完整手册

> 核心上游来自 **[yetone/magpie](https://github.com/yetone/magpie)**（MIT）。
> 本项目只是把它的 Android arm64 内核包成 Magisk 模块，内核一行没改。

把 magpie（AI 网关）的 Android 原生内核做成 Magisk 模块：开机自启、崩溃自动重启、数据便携化，不依赖 APK。

- 内核版本：**v0.1.4740**（上游 `yetone/magpie` 的 `magpie-cli-android-arm64`）
- 模块 id：`magpie_native`
- 网关端口：**3425**（API）
- 网页后台：**3430**（浏览器访问 `http://127.0.0.1:3430`）
- 配套 App：`com.magpie.module`（「Magpie 模块版」，见下文第十节）

---

## 一、前提

- 手机已 root 并安装 **Magisk**（或 KernelSU / APatch，本包按 Magisk 结构制作）
- 机型要求：arm64
- 不需要装 APK 版 magpie；装了也可以共存，但**两者抢 3425 端口，必须停一个**

## 二、安装

1. 打开 Magisk App → 模块 → 从存储安装 → 选 `magpie-native-v0.1.4740-r4.zip`
2. 安装过程会显示：
   - 便携模式标记创建
   - 旧数据检测与迁移结果（如有）
   - 网页后台固定 key（**记下来**）
   - 端口与日志路径说明
3. 重启手机
4. 等 10–20 秒（`service.sh` 会等系统起来），然后验证：

```
su -c "sh /data/adb/modules/magpie_native/scripts/selfcheck.sh"
```

自检会逐项打 `[OK]` / `[FAIL]`，一次性定位问题。

安装时想跳过自动迁移数据，可在 Magisk 安装命令行环境设置 `SKIP_MIGRATION=1`。

## 二点二、模块页开关：即时启停，不用重启

模块管理器（KernelSU / Magisk / APatch）模块详情页有一颗按钮，点一下就能启停网关。

```
KernelSU:  模块 → Magpie Native → 右下角那颗播放键
Magisk:    模块 → Magpie Native → 操作按钮
```

**它会做什么**：内核在跑就停掉，内核停了就起来，输出直接显示在页面上。

**为什么 r2 及之前不行**：模块页那个开关（右上角 ✓ / ✗）**只是写/删一个 `disable` 文件**，不会重启任何东西，页面上还会弹「重启生效」。而 r2 的 `service.sh` 和 `watchdog.sh` 都不读这个文件 —— 拨关后内核纹丝不动，只能重启手机。

r3 补了三处：

| 位置 | 行为 |
|---|---|
| `service.sh` | 开头检查 `disable`，存在就退出，不启动内核 |
| `watchdog.sh` | 每轮检查 `disable`，存在就把内核停掉（不是不管） |
| `action.sh` | 模块页按钮：立即启停，不用等 watchdog 那一轮 |

**两种「停」的区别**：

- **模块页开关拨到关** → 写 `disable` → 内核停，且**重启手机也不会起来**（模块整个禁用）
- **模块页按钮点一下** → 写 `stop.flag` → 内核停，但 `disable` 没动，**重启手机后会自己起来**

命令行也能用：

```sh
M=/data/adb/modules/magpie_native
su -c "sh $M/action.sh"              # 一键启停（自动判断方向）
su -c "sh $M/scripts/stop.sh $M"     # 明确停（写 stop.flag）
su -c "sh $M/scripts/start.sh $M"    # 明确起（清 stop.flag）
```

## 二点三、请求暴增导致系统卡死（r3 修）

**症状**：并发请求一多，整个手机卡住，弹「进程 system 没有响应」（system_server ANR）。

**根因**：上游 magpie 的 HTTP 服务**没有任何并发上限**。

```go
// internal/gateway/gateway.go:713
srv := &http.Server{
    Handler:           s.closesWhenHandedOff(s.Front()),
    ReadHeaderTimeout: 30 * time.Second,
    IdleTimeout:       5 * time.Minute,
    ConnState:         s.unread.note,
}
```

只有超时，没有 `MaxConns` / 限流 / 队列。`net/http` 默认每个连接一个 goroutine，Go runtime 默认 `GOMAXPROCS` = 核数。手机上 4~8 个核，几十个并发流同时跑就全占满，system_server 抢不到 CPU 就 ANR。

上游也没有提供任何环境变量或设置项来控制这件事（我查了 `internal/` 里所有 `MAGPIE_*` 变量，没有跟并发相关的）。

**r3 的解法**：不改内核，从进程层面压（`scripts/limit.sh`）：

| 手段 | 作用 |
|---|---|
| `nice 15` | 内核只在系统空闲时拿 CPU，system_server 永远优先 |
| `taskset` 绑小核 | 读 `/sys/.../cpufreq/cpuinfo_max_freq` 找主频最低的几个核，只在那儿跑，不占大核 |
| `ionice 3` | 日志和 `usage.jsonl` 的写入不抢前台 I/O |

`service.sh` 启动时、`restart.sh` 重启后、模块页按钮启动时都会调；
watchdog 每分钟复查一次，`nice` 掉了会补回来。

**效果**：网关吞吐几乎不变（瓶颈在网络和供应商 API，不在 CPU），系统卡死消失。

自检里能看到当前状态：

```
[OK] 内核已降优先级 nice=15（CPU=0-3）
```

## 二点五、模块目录是怎么解析的（KernelSU 必读）

`customize.sh` / `service.sh` / `post-fs-data.sh` / `uninstall.sh` 全部用同一个
`resolve_moddir()`：

```sh
1. $MODPATH     ← Magisk / KernelSU / APatch 都会设，最可靠
2. $MODDIR      ← 调用方已经设过
3. $0 的目录    ← 必须带斜杠、是目录、里面有 module.prop
4. pwd          ← 当前目录有 module.prop（metainstall 的 cd 场景）
```

**为什么不能只写 `MODDIR=${0%/*}`**：

KernelSU 的 meta-overlayfs `metainstall` 是 `cd` 进模块目录后用**相对路径**
执行 `sh customize.sh`。此时：

| | 值 |
|---|---|
| `$0` | `customize.sh`（没有斜杠） |
| `${0%/*}` | `customize.sh`（模式没匹配上，原样返回） |
| `MODDIR` | `customize.sh` ← **一个文件名，不是目录** |

于是 `mkdir -p customize.sh/data` 全部失败、`touch customize.sh/.portable` 失败、
`[ -f customize.sh/magpie ]` 为假 —— 报「magpie 内核不存在，zip 包可能损坏」，
但 **zip 其实是好的，`magpie` 就在里面 102760744 字节**。

Magisk 传的是绝对路径，所以这个坑只在 KernelSU / APatch 的相对路径调用下炸。

另外 r2 加了一道保险：`magpie` 找不到或大小不对（< 50MB / 不是 ELF）时，
会自己从 `$ZIPFILE` 解一次，再退到 `/sdcard/Download/magpie-native*.zip` 等位置。
都不行才 abort，并打印手动补救步骤（不用重刷）。

## 三、目录布局（重要）

```
/data/adb/modules/magpie_native/
├── module.prop
├── customize.sh
├── service.sh               # 开机启动 + 拉起 watchdog + 刷只读桥
├── post-fs-data.sh          # DNS 修复
├── uninstall.sh             # 卸载清理
├── magpie                   # ← 内核二进制，在模块根
├── .portable                # ← 便携标记，与 magpie 同级
├── webkey.txt               # ← 网页后台固定 key（600）
├── magpie.pid               # 运行时生成
├── home/                    # ← agent 的 $HOME（~/.claude、~/.codex…）
├── data/                    # ← 便携数据（settings.json / providers.json / usage.jsonl）
├── logs/
│   ├── magpie.log
│   └── watchdog.log
├── action.sh                # ← 模块页那颗按钮（一键启停）
├── scripts/
│   ├── watchdog.sh          # 10 秒轮询守护（单实例，读 disable）
│   ├── limit.sh             # 降 nice + 绑小核 + I/O 低优先级
│   ├── restart.sh           # 重启内核（App 也调这个）
│   ├── start.sh             # 明确启动
│   ├── stop.sh              # 停内核 + watchdog（写 stop.flag）
│   ├── bridge.sh            # 只读桥：把 webkey/日志暴露给 App
│   ├── selfcheck.sh         # 自检
│   ├── migrate-from-apk.sh  # APK 版数据迁移
│   └── pack.sh              # 打 zip（不进 zip）
└── system/etc/resolv.conf   # DNS
```

**为什么 `magpie` 必须在模块根、不能放 `bin/`**：

上游 `internal/appdir/appdir.go` 的便携判定是「二进制旁边的 `data/`」：

```go
base := Beside(exe)                    // = 二进制所在目录
data := filepath.Join(base, DataName)  // = base/data
if fi, err := os.Stat(filepath.Join(base, MarkerName)); err == nil && !fi.IsDir() {
    return absolute(data)              // → base/data
}
```

二进制在 `bin/magpie`、标记在 `bin/.portable` 时，内核读写的是 `bin/data`。而 `customize.sh` 建的是模块根 `data/` —— **迁移校验会通过，但内核读的是空目录**，账号和网关配置全丢。这个 bug 不报错，只表现为「网页后台打开是空的」。

v0.1.4740 就是这个布局，v0.1.4740-r4 已修正：二进制上移到模块根，`customize.sh` 还会自动把旧布局的 `bin/magpie`、`bin/.portable`、`bin/data` 合并回正确位置。

## 四、数据迁移（从 APK 版）

- 旧 APK 数据位置：`/data/data/com.magpie.android/files/magpie/data/`
- **`usage.jsonl` 是历史用量账本，迁移脚本逐文件复制后做行数校验，前后不一致会报错退出，不会丢**
- `settings.json` / `providers.json` / `caller-keys.json`（网关 key 存储）做 md5 校验
- **从 APK 版迁移的话，lan / gateway-mode 设置会原样保留，无需重设**
- 也可以之后手动跑：

```
su -c "sh /data/adb/modules/magpie_native/scripts/migrate-from-apk.sh /data/adb/modules/magpie_native"
```

- 迁移完成后**记得停用或卸载旧 APK 版**，否则它继续占着 3425
- 旧目录不会被删除；确认新目录正常后可手动删
- `data/` 权限 700/600（`settings.json` 有网关密钥和账号 token）

## 五、端口

| 端口 | 用途 | 访问方式 |
|---|---|---|
| 3425 | AI 网关（OpenAI 兼容 API） | `http://127.0.0.1:3425/v1/...` |
| 3430 | 网页后台 | 浏览器打开 `http://127.0.0.1:3430` |

- 局域网访问：网页后台「设置 → Network and sharing」把 gateway-mode 改成 `lan`，之后局域网设备访问 `http://<手机IP>:3430`（API 走 `:3425`，需要网关 key）
- **不要设置 `MAGPIE_ADDR`**：模块不设它，让内核按自己的设置决定监听地址（开共享 = `0.0.0.0:3425`，关 = `127.0.0.1:3425`）

## 六、网页后台的 key

`service.sh` 首次启动时生成 32 位 hex 写进 `webkey.txt`（600），之后每次启动都用它。所以**重启不会换 key**。

拿 key：

```
su -c "cat /data/adb/modules/magpie_native/webkey.txt"
```

完整链接：`http://127.0.0.1:3430/?k=<key>`

老版本模块（v0.1.4740）每次启动随机生成 key 打在日志里，要从日志里刮 —— v0.1.4740-r2 已修。

## 七、日志与 watchdog

- 内核日志：`/data/adb/modules/magpie_native/logs/magpie.log`（超 5MB 自动切一份 `.1`）
- watchdog 日志：`.../logs/watchdog.log`
- watchdog 每 10 秒检查进程和 3425 端口：
  - 进程在但端口 3 轮（30 秒）没起来 → 强制重启
  - 进程不在但 3425 被别人占着（旧 APK？）→ 只记日志不白重启，每 5 分钟提醒一次
  - **单实例**：启动时扫 `/proc/*/cmdline`，已有同名实例就直接退出

手动操作：

```
su
M=/data/adb/modules/magpie_native
sh $M/scripts/restart.sh $M     # 重启内核（连 watchdog 一起重排）
sh $M/scripts/stop.sh $M        # 停内核 + watchdog
sh $M/scripts/selfcheck.sh      # 自检
sh $M/service.sh                # 手动跑一遍开机流程
```

## 八、换内核版本

1. 下载新版 `magpie-cli-android-arm64`（GitHub releases：`yetone/magpie`）
2. 校验是不是 ELF aarch64、大小是否约 98MB
3. 替换：

```
su
cp /sdcard/Download/magpie-cli-android-arm64 /data/adb/modules/magpie_native/magpie
chmod 755 /data/adb/modules/magpie_native/magpie
sh /data/adb/modules/magpie_native/scripts/restart.sh /data/adb/modules/magpie_native
```

4. 同步改 `module.prop` 的 `version` / `versionCode`

`data/` 与内核版本无关，换版本不用动。

**重新打 zip 用脚本**（就在模块源码根目录）：

```
sh scripts/pack.sh <新内核路径> v0.1.xxxx vvvv
```

脚本会校验 ELF、写版本号、控制时间戳（可复现）、把 `magpie` 放在模块根。

## 九、DNS 修复

Go 内核读不到 `/etc/resolv.conf` 时 DNS 全挂（回退 `[::1]:53` 被拒）。模块通过两条路提供：

1. **Magisk overlay**（首选）：模块的 `system/etc/resolv.conf` 由 Magisk 自动 bind 到 `/system/etc/resolv.conf`
2. **手动 bind 兜底**：`post-fs-data.sh` 只在目标缺失时才做，且先 `touch` 目标文件 —— Android 的 `/etc` 是指向 `/system/etc` 的符号链接，目标不存在时 `mount` 必失败

内容：

```
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
```

## 十、配套 App（Magpie 模块版）

模块版 App 包名 **`com.magpie.module`**，与老版 `com.magpie.android` 完全独立，可以同时装。

| | 老版 com.magpie.android | 模块版 com.magpie.module |
|---|---|---|
| 内核在哪 | APK 的 assets，释放到 `filesDir/magpie/magpie` | Magisk 模块 `/data/adb/modules/magpie_native/magpie` |
| 谁启动它 | App 的 `ProcessBuilder` | 模块的 `service.sh` + `watchdog.sh` |
| 谁保活 | 前台服务 + WakeLock | 模块 watchdog，不依赖 App |
| App 被杀 | 网关跟着死 | 网关继续跑 |
| APK 大小 | 99MB | **716KB** |

模块版 App 做三件事：

1. 探测 `127.0.0.1:3425` / `:3430` 是否活着（不启动、不重启）
2. 通过**只读桥** `/data/local/tmp/magpie-native/` 读 `webkey.txt`、日志、`module.prop`
3. 原生页直连 3425 的 HTTP 接口画界面

只读桥由模块的 `scripts/bridge.sh` 挂（`service.sh` 启动时 + watchdog 每 5 分钟刷一次）：

- 暴露：`webkey.txt`、`logs/`、`module.prop`、`magpie.pid`
- **不暴露 `data/`** —— 里面有网关密钥、供应商 token、网关 key
- 要读 `data/` 的操作（插件补丁、缓存清理、文件管理器）走 Shizuku 或 root

App 里的「⋮ → 模块信息与自检」一眼看出是哪一环没通：

```
壳版本      1.0.0 (code 1)
包名        com.magpie.module
模块版本    v0.1.4740-r2
模块目录    /data/adb/modules/magpie_native
只读桥      已挂载 /data/local/tmp/magpie-native
网关 3425   在答话
网页 3430   在监听
内核 pid    12345
Shizuku     已授权（root 身份）
```

## 十一、卸载

1. Magisk App → 模块 → magpie_native → 卸载（自动执行 `uninstall.sh`：杀 watchdog、杀内核、解 DNS 挂载、删只读桥）
2. **数据默认保留**：`data/` 会随模块目录一起被 Magisk 删除 —— 想留数据先备份：

```
su
cp -a /data/adb/modules/magpie_native/data /sdcard/magpie-data-backup
```

3. 想彻底清除（连账号密钥一起删）：卸载前

```
su
rm -rf /data/adb/modules/magpie_native/data
```

## 十二、常见问题

**网页后台打开是空的**
→ 数据目录错位。检查 `ls /data/adb/modules/magpie_native/bin/` 有没有 `data/`；有就是旧布局，重刷 r1 或手动 `mv` 回模块根。

**3425 起不来，watchdog 一直刷 WARN**
→ 旧 APK 版还开着。`netstat -tlnp | grep 3425` 看是谁，停掉它。

**日志里 `magpie: HOME is not set`**
→ 旧版 `service.sh` 没设 HOME。r1 已修（`export HOME="$MODDIR/home"`）。

**KernelSU 报「magpie 内核不存在，zip 包可能损坏」，但 zip 是好的**
→ r1 的 `$0` 解析 bug。KernelSU 的 metainstall 用相对路径执行 `sh customize.sh`，
`${0%/*}` 返回文件名而不是目录。r2 已修（改用 `$MODPATH`）。
验证方法：装 r2 后看第一行 `- 模块目录：/data/adb/modules_update/magpie_native`，
应该是一个绝对路径而不是 `customize.sh`。

**网页后台每次重启 key 都变**
→ 旧版没设 `MAGPIE_WEB_KEY`。r1 已修（`webkey.txt` 固定）。

**模块页开关拨了没反应**
r3 之前不读 `disable` 文件。r3 之后：拨关 → 最多 10 秒内核停；拨开 → 需要点一下模块页那颗按钮（或重启）才会起来，因为 watchdog 被 `disable` 停住时自己也退出了。

**手机卡死 / 「进程 system 没有响应」**
r3 之前没有限流。检查 `nice`：
```
su -c "awk '{print \$19}' /proc/$(cat /data/adb/modules/magpie_native/magpie.pid)/stat"
```
应该是 15。是 0 就跑一次：`su -c "sh /data/adb/modules/magpie_native/scripts/limit.sh --all"`

**模块版 App 显示「只读桥未挂载」**
→ 模块没装，或 `service.sh` 没跑过。手动：`su -c "sh /data/adb/modules/magpie_native/scripts/bridge.sh /data/adb/modules/magpie_native"`

**模块版 App 的插件补丁/文件管理器说需要 Shizuku**
→ 模块目录普通 App 进不去。装 Shizuku 并授权，或用 root。

---

## 版本记录

- **v0.1.4740-r4**（versionCode 4743）
  - 新增：**模块页按钮即时启停**（`action.sh`），不用重启手机
  - 修：`service.sh` / `watchdog.sh` 现在读 `disable`，模块页开关立刻生效
  - 新增：`stop.flag` 机制 —— 手动停的内核不会被 watchdog 拉回来
  - 修：**请求暴增拖死系统**（system_server ANR）。内核没有并发上限，
    用 `scripts/limit.sh` 降 nice + 绑小核 + I/O 低优先级压住
  - 修：watchdog 端口探测改成只读 `/proc/net/tcp`，不再每轮 fork 3~4 个进程
  - 修：网页后台默认监听改回 `127.0.0.1:3430`（原 `0.0.0.0` 会让局域网可访问，
    在共享没开时属于多余暴露）
  - 新增：`scripts/start.sh` 明确启动
  - 自检加「模块页开关」与「限流状态」两项
- **v0.1.4740-r2**（versionCode 4742）
  - 修：**模块目录解析改用 `$MODPATH`**，兜底 `$MODDIR → $0 目录 → pwd`。
    KernelSU 的 meta-overlayfs `metainstall` 用相对路径执行 `sh customize.sh`，
    旧写法 `${0%/*}` 会返回文件名 `customize.sh`，导致所有 `[ -f "$MODDIR/magpie" ]`
    落空，误报「内核不存在，zip 包可能损坏」。
  - 修：`abort` 后补 `exit 1`（防止某些环境 `abort` 不退出）
  - 新增：`magpie` 缺失/不完整时自动从 `$ZIPFILE` 解一次，校验 ELF magic + ≥50MB
  - 新增：定位失败时打印 `MODPATH` / `MODDIR` / `$0` / `pwd`，方便诊断
- **v0.1.4740-r1**（versionCode 4741）
  - 修：`magpie` 上移到模块根，便携数据目录不再错位到 `bin/data`（旧布局自动迁移）
  - 修：`service.sh` 补 `HOME` / `PATH` / `MAGPIE_WEB_KEY`（缺 HOME 会 exit 1）
  - 修：`port_busy()` 真正被调用，旧 APK 占端口时不再死循环重启
  - 修：watchdog 单实例保护；`pgrep -f` 换成扫 `/proc/*/cmdline`（toybox 兼容）
  - 修：`/dev/tcp` 探测删掉（mksh 没有该伪设备）
  - 修：`post-fs-data.sh` bind 兜底先 `touch` 目标（符号链接下必失败）
  - 新增：`webkey.txt` 固定网页后台 key
  - 新增：`scripts/restart.sh` / `stop.sh`（App 通过 Shizuku 调）
  - 新增：`scripts/bridge.sh` 只读桥（给模块版 App 读 key 和日志）
  - 新增：`scripts/selfcheck.sh` 自检（10 项，逐条 [OK]/[FAIL]）
  - 新增：`scripts/pack.sh` 打包脚本（校验 ELF + 可复现）
  - 日志超 5MB 自动轮转
- **v0.1.4740**（versionCode 4740）— 首个版本
