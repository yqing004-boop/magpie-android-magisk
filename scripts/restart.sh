#!/system/bin/sh
# 重启模块内核：停掉旧进程，等端口释放，再拉起来
# 用法：sh restart.sh <模块目录>
#
# 被两个地方调用：
#   1. App 的「重启内核」（通过 Shizuku/root）
#   2. 手动排查时
#
# 注意：这个脚本不重启 watchdog —— watchdog 一直活着，进程一没就会自己拉。

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)

BIN="$MODDIR/magpie"
PIDFILE="$MODDIR/magpie.pid"
LOGDIR="$MODDIR/logs"

export PATH="/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH"
export HOME="$MODDIR/home"
export MAGPIE_NO_STATS=1
unset MAGPIE_ADDR

mkdir -p "$LOGDIR" "$HOME"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [restart] $1" >> "$LOGDIR/watchdog.log"; }

# 1. 停 watchdog（否则它会和这个脚本抢着启动，两个进程撞端口）
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *watchdog.sh*magpie_native*) kill "$p" 2>/dev/null ;;
  esac
done
sleep 1

# 2. 杀内核
if [ -f "$PIDFILE" ]; then
  OLD=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$OLD" ] && kill "$OLD" 2>/dev/null
fi
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *magpie_native/magpie*serve*) kill "$p" 2>/dev/null ;;
  esac
done
rm -f "$PIDFILE"

# 3. 等端口真的放开（最多 5 秒）
i=0
while [ $i -lt 25 ]; do
  if ! netstat -tlnp 2>/dev/null | grep -q ":3425 "; then
    break
  fi
  sleep 0.2
  i=$((i + 1))
done

# 4. 拉起内核
cd "$MODDIR" || exit 1
if command -v nohup >/dev/null 2>&1; then
  nohup "$BIN" serve --web=0.0.0.0:3430 >> "$LOGDIR/magpie.log" 2>&1 &
elif command -v setsid >/dev/null 2>&1; then
  setsid "$BIN" serve --web=0.0.0.0:3430 >> "$LOGDIR/magpie.log" 2>&1 &
else
  "$BIN" serve --web=0.0.0.0:3430 >> "$LOGDIR/magpie.log" 2>&1 &
fi
NEWPID=$!
echo "$NEWPID" > "$PIDFILE"
log "内核已重启 pid=$NEWPID"

# 5. 重新拉起 watchdog
if command -v nohup >/dev/null 2>&1; then
  nohup sh "$MODDIR/scripts/watchdog.sh" "$MODDIR" >> "$LOGDIR/watchdog.log" 2>&1 &
else
  sh "$MODDIR/scripts/watchdog.sh" "$MODDIR" >> "$LOGDIR/watchdog.log" 2>&1 &
fi
log "watchdog 已重新拉起"

echo "restarted pid=$NEWPID"
exit 0
