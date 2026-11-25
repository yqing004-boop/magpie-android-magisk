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

DISABLE="$MODDIR/disable"
STOPFLAG="$MODDIR/stop.flag"

mkdir -p "$LOGDIR" "$HOME"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [restart] $1" >> "$LOGDIR/watchdog.log"; }

# 模块页开关是关的：重启没有意义，watchdog 会立刻停掉
if [ -f "$DISABLE" ]; then
  log "模块页开关是关的，拒绝重启"
  echo "模块页开关是关的（disable 存在）。要跑请先在模块页打开开关。"
  exit 1
fi

# 重启 = 用户要它跑，清掉「手动停止」标记
rm -f "$STOPFLAG"

# 1. 停 watchdog（否则它会和这个脚本抢着启动，两个进程撞端口）
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in
    *"$MODDIR/scripts/watchdog.sh"*) kill "$p" 2>/dev/null ;;
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
    *"$MODDIR/magpie"*serve*) kill "$p" 2>/dev/null ;;
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
  nohup "$BIN" serve --web=127.0.0.1:3430 >> "$LOGDIR/magpie.log" 2>&1 &
elif command -v setsid >/dev/null 2>&1; then
  setsid "$BIN" serve --web=127.0.0.1:3430 >> "$LOGDIR/magpie.log" 2>&1 &
else
  "$BIN" serve --web=127.0.0.1:3430 >> "$LOGDIR/magpie.log" 2>&1 &
fi
NEWPID=$!
echo "$NEWPID" > "$PIDFILE"
# 限流：nice 15 + 绑小核，避免请求暴增时拖垮系统
sh "$MODDIR/scripts/limit.sh" "$NEWPID" "$MODDIR" >/dev/null 2>&1
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
