#!/system/bin/sh
# 模块页的「操作」按钮（KernelSU / Magisk 模块详情页那颗播放键）
#
# 用户在模块页面点一下就能启停网关，不用重启手机。
#
# ============================================================
# 为什么需要这个脚本
# ------------------------------------------------------------
# 模块页那个开关（右上角的 ✓ / ✗）只是**写/删一个 disable 文件**，
# 它不会重启任何东西，页面上还会弹一句「重启生效」。
#
# 所以在 r2 及之前：
#   - 拨关 → 写 disable 文件 → 内核纹丝不动 → 必须重启手机
#   - 拨开 → 删 disable 文件 → 内核还是没起来 → 又得重启
#
# 现在两件事都补上了：
#   1. service.sh 和 watchdog.sh 都读 disable，开关立刻生效（最多 10 秒）
#   2. 这个 action.sh 提供「立即启停」，不用等 watchdog 那一轮
#
# ============================================================
# 行为
# ------------------------------------------------------------
# 内核在跑 → 停掉（并写 stop.flag，防止 watchdog 拉回来）
# 内核没跑 → 起来（并删 stop.flag，恢复 watchdog 守护）
#
# 输出走 stdout，KSU/Magisk 会在页面上直接显示。

MODDIR=$(cd "$(dirname "$0")" && pwd)
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

say() { echo "$1"; }
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [action] $1" >> "$LOGDIR/watchdog.log"; }

# ---------- 探测（只读 /proc/net/tcp，不 fork）----------
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

proc_ok() {
  if [ -f "$PIDFILE" ]; then
    P=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$P" ] && [ -d "/proc/$P" ] && return 0
  fi
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in *"$MODDIR/magpie"*serve*) return 0 ;; esac
  done
  return 1
}

stop_core() {
  if [ -f "$PIDFILE" ]; then
    P=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$P" ] && kill "$P" 2>/dev/null
  fi
  sleep 1
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in *"$MODDIR/magpie"*serve*) kill "${d#/proc/}" 2>/dev/null ;; esac
  done
  sleep 1
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in *"$MODDIR/magpie"*serve*) kill -9 "${d#/proc/}" 2>/dev/null ;; esac
  done
  rm -f "$PIDFILE"
}

start_core() {
  cd "$MODDIR" || return 1
  rm -f "$PIDFILE"
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
  # 限流：nice 15 + 绑小核 + I/O 低优先级。
  # 内核没有并发上限，请求暴增会吃满所有核导致 system_server ANR，
  # 详见 scripts/limit.sh 的注释。
  sh "$MODDIR/scripts/limit.sh" "$P" "$MODDIR" >/dev/null 2>&1
  return 0
}

ensure_watchdog() {
  # 已有就不重复起
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in *"$MODDIR/scripts/watchdog.sh"*) return 0 ;; esac
  done
  if command -v nohup >/dev/null 2>&1; then
    nohup sh "$MODDIR/scripts/watchdog.sh" "$MODDIR" >> "$LOGDIR/watchdog.log" 2>&1 &
  else
    sh "$MODDIR/scripts/watchdog.sh" "$MODDIR" >> "$LOGDIR/watchdog.log" 2>&1 &
  fi
}

# ============================================================
# 判断当前状态
# ============================================================
if proc_ok || port_ok; then
  # ---------- 在跑 → 停 ----------
  say "Magpie 状态：运行中"
  say ""

  # 如果模块页开关是「开」，用户可能只是想临时停一下。
  # 写 stop.flag 是「我手动停的，别自动拉回来」，它比 disable 更轻 ——
  # 不动模块页那个开关。
  : > "$STOPFLAG"

  say "→ 正在停止内核…"
  stop_core

  if proc_ok || port_ok; then
    say ""
    say "！停止失败：进程还在"
    say "  手动排查："
    say "    ps -A | grep magpie"
    say "    netstat -tlnp | grep 3425"
    exit 1
  fi

  log "用户从模块页停止了内核"
  say ""
  say "已停止。"
  say "  网关 3425 不再监听，AI 客户端会连不上。"
  say "  再点一次这个按钮即可恢复；或直接重启手机，"
  say "  手机重启后 service.sh 会自动起来（除非模块页开关是关的）。"
  exit 0
fi

# ---------- 没在跑 → 起 ----------
say "Magpie 状态：已停止"
say ""

# 模块页开关关着时，直接起会被 watchdog 立刻停掉，先说清楚
if [ -f "$DISABLE" ]; then
  say "！注意：模块页的开关现在是「关」的状态（disable 文件存在）。"
  say "  内核能起来，但模块页下次刷新会显示关；建议先在模块页把开关打开。"
  say ""
fi

rm -f "$STOPFLAG"

say "→ 正在启动内核…"
start_core

# 等端口起来（最多 15 秒）
i=0
while [ $i -lt 30 ]; do
  if port_ok; then
    break
  fi
  sleep 0.5
  i=$((i + 1))
done

if ! port_ok; then
  say ""
  say "！启动失败：15 秒内 3425 没监听"
  say "  看日志："
  say "    tail -50 $LOGDIR/magpie.log"
  say "  常见原因："
  say "    · 旧 APK 版还开着，占着 3425"
  say "    · data/settings.json 里的 port 不是 3425"
  say "    · 内核崩了（日志末尾有堆栈）"
  exit 1
fi

ensure_watchdog
log "用户从模块页启动了内核"

KEY=""
[ -s "$MODDIR/webkey.txt" ] && KEY=$(cat "$MODDIR/webkey.txt" 2>/dev/null)

say ""
say "已启动。"
say "  网关    http://127.0.0.1:3425/v1"
if [ -n "$KEY" ]; then
  say "  网页后台 http://127.0.0.1:3430/?k=$KEY"
else
  say "  网页后台 http://127.0.0.1:3430"
fi
say "  watchdog 已接管，挂掉会自动拉起。"
exit 0
