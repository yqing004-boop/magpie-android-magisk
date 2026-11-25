#!/system/bin/sh
# Magpie Native watchdog：轮询进程/端口，挂了自动重启
# 用法：sh watchdog.sh <模块目录>
#
# 核心约束（每一条都是踩出来的）：
#   1. 单实例 —— Magisk/KSU 在部分 ROM 上会重跑 service.sh，
#      多个 watchdog 会互相 kill 进程、互相覆盖 pid 文件。
#   2. HOME 必须设 —— 内核启动第一件事是 CheckEnv()，缺 HOME 直接 exit 1。
#   3. 端口探测不用 /dev/tcp —— Android 的 /system/bin/sh 是 mksh，没该伪设备。
#   4. **必须读 disable 文件** —— 模块页开关只写/删 $MODDIR/disable，
#      不会重启任何东西。watchdog 不读它就等于开关失效：
#      用户关了，10 秒后 watchdog 又把内核拉起来。
#   5. 探测要便宜 —— 原来每轮 fork 3~4 个进程（netstat×2 + grep + tr），
#      内核高负载时这套自己也在抢 CPU，火上浇油。现在优先用内核自己
#      提供的 healthcheck（一次 exec，不做全表扫描），失败才退回 netstat。

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)
BIN="$MODDIR/magpie"
PIDFILE="$MODDIR/magpie.pid"
LOGDIR="$MODDIR/logs"
WEBKEYFILE="$MODDIR/webkey.txt"
DISABLE="$MODDIR/disable"
STOPFLAG="$MODDIR/stop.flag"

export PATH="/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH"
export HOME="$MODDIR/home"
export MAGPIE_NO_STATS=1
unset MAGPIE_ADDR

mkdir -p "$LOGDIR" "$HOME"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $1"; }

# ---------- 单实例检查 ----------
SELF=$$
COUNT=0
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  [ "$p" = "$SELF" ] && continue
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *"$MODDIR/scripts/watchdog.sh"*) COUNT=$((COUNT + 1)) ;;
  esac
done
if [ "$COUNT" -gt 0 ]; then
  log "已有 $COUNT 个 watchdog 在跑，本实例退出"
  exit 0
fi

# ---------- 降优先级（watchdog 自己也是后台进程）----------
if command -v renice >/dev/null 2>&1; then
  renice 19 -p $$ >/dev/null 2>&1
fi

# ---------- 探测 ----------
# 只读 /proc/net/tcp，不 fork 任何进程。
#
# 为什么不用原来的 netstat：netstat 要扫全部 socket 再 grep，每轮 3~4 个
# 进程；内核高负载时这套自己也在抢 CPU。而 /proc/net/tcp 是内核直接暴露的
# 表，一次 cat + 一次模式匹配就够。
#
# 3425 = 0x0D61，127.0.0.1 在 /proc/net/tcp 里是 0100007F（小端）。
# 状态 0A = LISTEN。所以一行的样子是：
#     0: 0100007F:0D61 00000000:0000 0A ...
# 只认「本地地址 0100007F:0D61 且状态 0A」，不关心对端。
#
# 注意：开了局域网共享的内核会绑 0.0.0.0:3425，那行是 00000000:0D61。
# 两种都算活。
port_ok() {
  [ -r /proc/net/tcp ] || return 1
  while read -r _sl _local _remote _st _rest; do
    case "$_st" in
      0A) ;;
      *) continue ;;
    esac
    case "$_local" in
      0100007F:0D61|00000000:0D61) return 0 ;;
    esac
  done < /proc/net/tcp
  return 1
}

proc_ok() {
  if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$PID" ] && [ -d "/proc/$PID" ] && return 0
  fi
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in
      *"$MODDIR/magpie"*serve*) return 0 ;;
    esac
  done
  return 1
}

# ---------- 后台启动器 ----------
bg() {
  if command -v nohup >/dev/null 2>&1; then
    nohup "$@" >> "$LOGDIR/magpie.log" 2>&1 &
  elif command -v setsid >/dev/null 2>&1; then
    setsid "$@" >> "$LOGDIR/magpie.log" 2>&1 &
  else
    "$@" >> "$LOGDIR/magpie.log" 2>&1 &
  fi
  echo $!
}

apply_limit() {
  _pid="$1"
  [ -z "$_pid" ] && return 1
  [ -d "/proc/$_pid" ] || return 1
  sh "$MODDIR/scripts/limit.sh" "$_pid" "$MODDIR" >/dev/null 2>&1
  return 0
}

start_magpie() {
  cd "$MODDIR" || return 1
  rm -f "$PIDFILE"
  if [ -f "$LOGDIR/magpie.log" ]; then
    SZ=$(stat -c %s "$LOGDIR/magpie.log" 2>/dev/null || echo 0)
    [ "$SZ" -gt 5242880 ] && mv -f "$LOGDIR/magpie.log" "$LOGDIR/magpie.log.1"
  fi
  P=$(bg "$BIN" serve --web=127.0.0.1:3430)
  echo "$P" > "$PIDFILE"
  apply_limit "$P"
  log "RESTART: magpie 已重新拉起 pid=$P"
}

# ---------- 停内核 ----------
kill_magpie() {
  if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$PID" ] && kill "$PID" 2>/dev/null
  fi
  sleep 1
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in
      *"$MODDIR/magpie"*serve*) kill "${d#/proc/}" 2>/dev/null ;;
    esac
  done
  sleep 1
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in
      *"$MODDIR/magpie"*serve*) kill -9 "${d#/proc/}" 2>/dev/null ;;
    esac
  done
  rm -f "$PIDFILE"
}

log "watchdog 启动，监视 $MODDIR"

BRIDGE_TICK=0
LIMIT_TICK=0
GRACE=0
FAILS=0
DISABLED_LOGGED=0

while true; do
  sleep 10

  # ---------- 模块页开关：最高优先级 ----------
  if [ -f "$DISABLE" ] || [ -f "$STOPFLAG" ]; then
    # 用户关了：确保内核真的停了，然后空转等开关回来
    if proc_ok || port_ok; then
      kill_magpie
      [ "$DISABLED_LOGGED" = "0" ] && log "用户在模块页停用了模块（或手动停止），已停内核"
      DISABLED_LOGGED=1
    fi
    # 期间不刷 bridge、不重启
    GRACE=0
    FAILS=0
    continue
  fi
  if [ "$DISABLED_LOGGED" = "1" ]; then
    log "模块已重新启用，恢复守护"
    DISABLED_LOGGED=0
  fi

  BRIDGE_TICK=$((BRIDGE_TICK + 1))
  if [ "$BRIDGE_TICK" -ge 30 ]; then
    BRIDGE_TICK=0
    sh "$MODDIR/scripts/bridge.sh" "$MODDIR" >/dev/null 2>&1
  fi

  if proc_ok && port_ok; then
    GRACE=0
    FAILS=0
    # 每 6 轮（1 分钟）复查一次限流：内核自己重启、或用户手动起过，
    # nice 和 CPU 亲和性都会丢，这里补回来。
    LIMIT_TICK=$((LIMIT_TICK + 1))
    if [ "$LIMIT_TICK" -ge 6 ]; then
      LIMIT_TICK=0
      _p=$(cat "$PIDFILE" 2>/dev/null)
      if [ -n "$_p" ] && [ -d "/proc/$_p" ]; then
        _n=$(awk '{print $19}' "/proc/$_p/stat" 2>/dev/null)
        if [ "${_n:-0}" -lt 10 ]; then
          apply_limit "$_p"
          log "复查：内核 nice=$_n 偏低，已重新限流"
        fi
      fi
    fi
    continue
  fi

  if proc_ok && ! port_ok; then
    GRACE=$((GRACE + 1))
    if [ "$GRACE" -ge 3 ]; then
      log "WARN: 进程在但 3425 未监听（宽限 $GRACE 次），强制重启"
      kill_magpie
      start_magpie
      GRACE=0
    fi
  else
    if port_ok; then
      FAILS=$((FAILS + 1))
      if [ "$FAILS" = "1" ] || [ $((FAILS % 30)) -eq 0 ]; then
        log "WARN: 3425 被其它进程占用（旧 APK 版？），magpie 无法启动。第 $FAILS 次"
      fi
    else
      log "WARN: magpie 进程不在，重启"
      start_magpie
      FAILS=0
    fi
    GRACE=0
  fi
done
