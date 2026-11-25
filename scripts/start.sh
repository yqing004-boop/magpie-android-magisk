#!/system/bin/sh
# 启动模块内核（清 stop.flag，拉起内核 + watchdog）
# 用法：sh start.sh <模块目录>
#
# 和 action.sh 的区别：action.sh 是模块页那颗按钮（自动判断启停），
# 这个是明确「我要启动」，不看当前状态。

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)

BIN="$MODDIR/magpie"
PIDFILE="$MODDIR/magpie.pid"
LOGDIR="$MODDIR/logs"
DISABLE="$MODDIR/disable"
STOPFLAG="$MODDIR/stop.flag"

export PATH="/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH"
export HOME="$MODDIR/home"
export MAGPIE_NO_STATS=1
unset MAGPIE_ADDR

mkdir -p "$LOGDIR" "$HOME"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [start] $1" >> "$LOGDIR/watchdog.log"; }

# 清掉「手动停止」标记
rm -f "$STOPFLAG"

if [ -f "$DISABLE" ]; then
  echo "！注意：模块页开关是关的（disable 存在），内核会起来但模块页显示关"
fi

# 已经在跑就不重复起
if [ -f "$PIDFILE" ]; then
  P=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$P" ] && [ -d "/proc/$P" ]; then
    echo "内核已在运行 pid=$P"
    exit 0
  fi
fi

port_ok() {
  [ -r /proc/net/tcp ] || return 1
  while read -r _sl _local _remote _st _rest; do
    case "$_st" in 0A) ;; *) continue ;; esac
    case "$_local" in
      0100007F:0D61|00000000:0D61) return 0 ;;
    esac
  done < /proc/net/tcp
  return 1
}

if port_ok; then
  echo "3425 已被占用，可能内核已在跑（或旧 APK 版开着）。不重复启动。"
  exit 1
fi

cd "$MODDIR" || exit 1

# 日志轮转
if [ -f "$LOGDIR/magpie.log" ]; then
  SZ=$(stat -c %s "$LOGDIR/magpie.log" 2>/dev/null || echo 0)
  [ "$SZ" -gt 5242880 ] && mv -f "$LOGDIR/magpie.log" "$LOGDIR/magpie.log.1"
fi

if command -v nohup >/dev/null 2>&1; then
  nohup "$BIN" serve --web=127.0.0.1:3430 >> "$LOGDIR/magpie.log" 2>&1 &
elif command -v setsid >/dev/null 2>&1; then
  setsid "$BIN" serve --web=127.0.0.1:3430 >> "$LOGDIR/magpie.log" 2>&1 &
else
  "$BIN" serve --web=127.0.0.1:3430 >> "$LOGDIR/magpie.log" 2>&1 &
fi
P=$!
echo "$P" > "$PIDFILE"

# 限流：nice 15 + 绑小核，避免请求暴增时拖垮系统
sh "$MODDIR/scripts/limit.sh" "$P" "$MODDIR" >/dev/null 2>&1

log "内核已启动 pid=$P"

# 拉起 watchdog（幂等）
for d in /proc/[0-9]*; do
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in *"$MODDIR/scripts/watchdog.sh"*) echo "watchdog 已在跑"; exit 0 ;; esac
done
if command -v nohup >/dev/null 2>&1; then
  nohup sh "$MODDIR/scripts/watchdog.sh" "$MODDIR" >> "$LOGDIR/watchdog.log" 2>&1 &
else
  sh "$MODDIR/scripts/watchdog.sh" "$MODDIR" >> "$LOGDIR/watchdog.log" 2>&1 &
fi

echo "已启动 pid=$P（watchdog 已接管）"
exit 0
