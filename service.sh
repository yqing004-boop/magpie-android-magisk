#!/system/bin/sh
# Magpie Native 开机启动脚本（Magisk / KernelSU / APatch service 阶段，root 运行）
#
# 六条不能删的要点，每一条都是踩出来的：
#   1. HOME 必须显式设 —— magpie 启动第一件事是 appdir.CheckEnv()，
#      HOME 未设或相对路径 → "magpie: HOME is not set" + exit 1，模块直接哑掉。
#   2. PATH 必须补全 —— service 阶段 PATH 常常只有 /sbin:/system/bin，
#      nohup / pgrep / nc 都可能找不到。
#   3. 二进制在模块根 —— 便携数据目录 = 二进制旁边的 data/（appdir.Beside），
#      所以 magpie、.portable、data/ 必须同级，不能把二进制塞进 bin/。
#   4. MAGPIE_WEB_KEY 固定 —— 不设的话上游每次启动随机生成一个 32 位 hex，
#      重启一次就得去日志里翻一次新链接。
#   5. 不设 MAGPIE_ADDR —— 便携模式自己决定监听地址。
#   6. **模块页面开关必须生效** —— KSU/Magisk 模块页的开关只写/删
#      $MODDIR/disable，不会重启任何东西。这里和 watchdog 都要读它，
#      否则用户关了开关、内核照跑，只能重启手机才能停。

# 模块目录解析：优先 $MODPATH，兜底 $MODDIR、$0 的目录、含 module.prop 的 pwd
resolve_moddir() {
  if [ -n "$MODPATH" ] && [ -d "$MODPATH" ]; then echo "$MODPATH"; return 0; fi
  case "$0" in
    */*) _d="${0%/*}"; [ -n "$_d" ] && [ -d "$_d" ] && [ -f "$_d/module.prop" ] && { echo "$_d"; return 0; } ;;
  esac
  [ -f "./module.prop" ] && { pwd; return 0; }
  echo ""
  return 1
}
MODDIR="$(resolve_moddir)"
[ -n "$MODDIR" ] || exit 1

LOGDIR="$MODDIR/logs"
PIDFILE="$MODDIR/magpie.pid"
BIN="$MODDIR/magpie"
WEBKEYFILE="$MODDIR/webkey.txt"
DISABLE="$MODDIR/disable"
WANTFILE="$MODDIR/want-running"

export PATH="/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH"
export HOME="$MODDIR/home"
export MAGPIE_NO_STATS=1
unset MAGPIE_ADDR

mkdir -p "$LOGDIR" "$MODDIR/data" "$HOME"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [service] $1" >> "$LOGDIR/watchdog.log"; }

# ---------- 模块页面开关 ----------
# disable 存在 = 用户在模块页关掉了这个模块。
# 内核不启动，watchdog 也不启动，什么都不做。
if [ -f "$DISABLE" ]; then
  log "模块被用户在模块页停用（disable 存在），不启动内核"
  exit 0
fi
# 用户手动 stop 过（want-running 不存在且 stop.flag 存在）也尊重。
if [ -f "$MODDIR/stop.flag" ]; then
  log "检测到 stop.flag（用户手动停止），不自动启动"
  exit 0
fi

# ---------- 日志轮转：超 5MB 切一份 ----------
if [ -f "$LOGDIR/magpie.log" ]; then
  SZ=$(stat -c %s "$LOGDIR/magpie.log" 2>/dev/null || echo 0)
  [ "$SZ" -gt 5242880 ] && mv -f "$LOGDIR/magpie.log" "$LOGDIR/magpie.log.1"
fi

# 等系统起来（boot 完成前网络/DNS 可能没就绪）
sleep 8

# 再查一次：这 8 秒里用户可能刚把开关拨了
if [ -f "$DISABLE" ] || [ -f "$MODDIR/stop.flag" ]; then
  log "等待期间被停用，放弃启动"
  exit 0
fi

# ---------- 防重复启动 ----------
if [ -f "$PIDFILE" ]; then
  OLDPID=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$OLDPID" ] && [ -d "/proc/$OLDPID" ]; then
    log "已有 magpie 在跑 pid=$OLDPID，跳过启动"
    exit 0
  fi
fi

# ---------- 后台启动器：nohup → setsid → 裸 & ----------
BG_PID=""
BG_LOG="$LOGDIR/magpie.log"
bg() {
  if command -v nohup >/dev/null 2>&1; then
    nohup "$@" >> "$BG_LOG" 2>&1 &
  elif command -v setsid >/dev/null 2>&1; then
    setsid "$@" >> "$BG_LOG" 2>&1 &
  else
    "$@" >> "$BG_LOG" 2>&1 &
  fi
  BG_PID=$!
}

port_busy() {
  netstat -tlnp 2>/dev/null | grep -q ":$1 " && return 0
  busybox netstat -tlnp 2>/dev/null | grep -q ":$1 " && return 0
  return 1
}

# 给内核降优先级。
#
# 为什么需要：内核每个请求都起 goroutine 真并发，几十个流同时跑就能
# 把 8 核吃满。system_server 抢不到 CPU → 整个系统 ANR（「进程 system
# 没有响应」）。模块是后台服务，没有任何理由跟系统抢。
#
# nice 15：内核只在系统空闲时拿 CPU。前台 App、system_server 永远优先。
# 实测网关吞吐几乎不变（瓶颈在网络和供应商 API，不在 CPU），
# 但系统卡死彻底消失。
#
# taskset 绑到小核（如果有）：手机通常是 4 小核 + 4 大核，
# 让内核只用能干活的核，别占着大核。有些 ROM 没有 taskset，找不到就跳过。
apply_limit() {
  _pid="$1"
  [ -z "$_pid" ] && return 1
  sh "$MODDIR/scripts/limit.sh" "$_pid" "$MODDIR" >/dev/null 2>&1
  return 0
}

cd "$MODDIR" || exit 1

# ---------- 只读桥：给模块版 App 读 webkey/日志用 ----------
sh "$MODDIR/scripts/bridge.sh" "$MODDIR" >/dev/null 2>&1

# ---------- 首次启动 ----------
if port_busy 3425; then
  log "3425 已被占用（旧 APK 版还开着？），本轮不启动，交给 watchdog 重试"
else
  bg "$BIN" serve --web=127.0.0.1:3430
  echo "$BG_PID" > "$PIDFILE"
  # 降优先级：内核是后台服务，不能跟 system_server 抢 CPU。
  # 请求暴增时（几十个并发流），内核会吃满所有核 —— 手机上就是整个系统卡死。
  # nice 15 让内核只在系统空闲时用 CPU，前台界面立刻响应。
  apply_limit "$BG_PID"
  log "已启动 magpie pid=$BG_PID"
fi

# ---------- 拉起 watchdog（幂等：watchdog 自己有单实例检查）----------
BG_LOG="$LOGDIR/watchdog.log"
bg sh "$MODDIR/scripts/watchdog.sh" "$MODDIR"

if [ -s "$WEBKEYFILE" ]; then
  log "网页后台：http://127.0.0.1:3430/?k=$(cat "$WEBKEYFILE" 2>/dev/null)"
else
  log "网页后台：http://127.0.0.1:3430"
fi
log "网关：http://127.0.0.1:3425/v1"
