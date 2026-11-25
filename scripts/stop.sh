#!/system/bin/sh
# 停掉模块内核（连 watchdog 一起停，否则 10 秒后又起来）
# 用法：sh stop.sh <模块目录>

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)

PIDFILE="$MODDIR/magpie.pid"
LOGDIR="$MODDIR/logs"
mkdir -p "$LOGDIR"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [stop] $1" >> "$LOGDIR/watchdog.log"; }

# 先杀 watchdog
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *"$MODDIR/scripts/watchdog.sh"*) kill "$p" 2>/dev/null ;;
  esac
done
sleep 1
# 顽固的强杀
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *"$MODDIR/scripts/watchdog.sh"*) kill -9 "$p" 2>/dev/null ;;
  esac
done

# 再杀内核
if [ -f "$PIDFILE" ]; then
  OLD=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$OLD" ] && kill "$OLD" 2>/dev/null
fi
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *"$MODDIR/magpie"*serve*) kill "$p" 2>/dev/null ;;
  esac
done
sleep 1
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *"$MODDIR/magpie"*serve*) kill -9 "$p" 2>/dev/null ;;
  esac
done
rm -f "$PIDFILE"

# 写 stop.flag：告诉 service.sh 和 watchdog「这是用户主动停的，别自动拉回来」。
# 否则 watchdog 被杀之后 service.sh 下次跑还会起，或者别的路径又拉起来。
: > "$MODDIR/stop.flag"

log "内核与 watchdog 已停止（写 stop.flag，不会自动重启）"
echo "stopped"
echo "要恢复：sh $MODDIR/scripts/start.sh（或删掉 stop.flag 后重启手机）"
exit 0
