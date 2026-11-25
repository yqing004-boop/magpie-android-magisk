#!/system/bin/sh
# Magpie Native watchdog：每 10 秒检查进程/端口，挂了自动重启
# 用法：sh watchdog.sh <模块目录>
#
# 三条关键约束：
#   1. 单实例 —— Magisk 在部分 ROM 上会重跑 service.sh，
#      多个 watchdog 会互相 kill 进程、互相覆盖 pid 文件。
#   2. HOME 必须设 —— 内核启动第一件事是 CheckEnv()，缺 HOME 直接 exit 1。
#   3. 端口探测不用 /dev/tcp —— Android 的 /system/bin/sh 是 mksh，没有该伪设备。

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)
BIN="$MODDIR/magpie"
PIDFILE="$MODDIR/magpie.pid"
LOGDIR="$MODDIR/logs"
WEBKEYFILE="$MODDIR/webkey.txt"

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
    *watchdog.sh*magpie_native*) COUNT=$((COUNT + 1)) ;;
  esac
done
if [ "$COUNT" -gt 0 ]; then
  log "已有 $COUNT 个 watchdog 在跑，本实例退出"
  exit 0
fi

# ---------- 探测 ----------
port_ok() {
  netstat -tlnp 2>/dev/null | grep -q ":3425 " && return 0
  busybox netstat -tlnp 2>/dev/null | grep -q ":3425 " && return 0
  if command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 3425 >/dev/null 2>&1 && return 0
  fi
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
      *magpie_native/magpie*serve*) return 0 ;;
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

start_magpie() {
  cd "$MODDIR" || return 1
  rm -f "$PIDFILE"
  # 日志轮转
  if [ -f "$LOGDIR/magpie.log" ]; then
    SZ=$(stat -c %s "$LOGDIR/magpie.log" 2>/dev/null || echo 0)
    [ "$SZ" -gt 5242880 ] && mv -f "$LOGDIR/magpie.log" "$LOGDIR/magpie.log.1"
  fi
  P=$(bg "$BIN" serve --web=0.0.0.0:3430)
  echo "$P" > "$PIDFILE"
  log "RESTART: magpie 已重新拉起 pid=$P"
}

log "watchdog 启动，监视 $MODDIR"

# 每 30 轮（5 分钟）刷一次只读桥：restart 后 webkey.txt / pid 会变，
# App 那边要能读到新值。bind 挂载本身不用重做，主要是那几个 cp 的文件。
BRIDGE_TICK=0

GRACE=0
FAILS=0
while true; do
  sleep 10
  BRIDGE_TICK=$((BRIDGE_TICK + 1))
  if [ "$BRIDGE_TICK" -ge 30 ]; then
    BRIDGE_TICK=0
    sh "$MODDIR/scripts/bridge.sh" "$MODDIR" >/dev/null 2>&1
  fi
  if proc_ok && port_ok; then
    GRACE=0
    FAILS=0
    continue
  fi
  if proc_ok && ! port_ok; then
    GRACE=$((GRACE + 1))
    if [ "$GRACE" -ge 3 ]; then
      log "WARN: 进程在但 3425 未监听（宽限 $GRACE 次），强制重启"
      PID=$(cat "$PIDFILE" 2>/dev/null)
      [ -n "$PID" ] && kill "$PID" 2>/dev/null
      sleep 2
      for d in /proc/[0-9]*; do
        c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
        case "$c" in
          *magpie_native/magpie*serve*) kill -9 "${d#/proc/}" 2>/dev/null ;;
        esac
      done
      start_magpie
      GRACE=0
    fi
  else
    # 进程不在：先确认端口不是被旧 APK 占着（占着就是白重启）
    if port_ok; then
      FAILS=$((FAILS + 1))
      if [ "$FAILS" = 1 ] || [ $((FAILS % 30)) -eq 0 ]; then
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
