#!/system/bin/sh
# Magpie Native 开机启动脚本（Magisk service 阶段，root 运行）
#
# 五条不能删的环境设置，每一条都是踩出来的：
#   1. HOME 必须显式设 —— magpie 启动第一件事是 appdir.CheckEnv()，
#      HOME 未设或相对路径 → "magpie: HOME is not set" + exit 1，模块直接哑掉。
#   2. PATH 必须补全 —— Magisk service 阶段 PATH 常常只有 /sbin:/system/bin，
#      nohup / pgrep / nc 都可能找不到。
#   3. 二进制在模块根 —— 便携数据目录 = 二进制旁边的 data/（appdir.Beside），
#      所以 magpie、.portable、data/ 必须同级，不能把二进制塞进 bin/。
#   4. MAGPIE_WEB_KEY 固定 —— 不设的话上游每次启动随机生成一个 32 位 hex，
#      重启一次就得去日志里翻一次新链接。
#   5. 不设 MAGPIE_ADDR —— 便携模式自己决定监听地址（默认 127.0.0.1:3425）。

# 模块目录解析：Magisk 用绝对路径调（${0%/*} 可用），
# KernelSU 的 metainstall 可能用相对路径（${0%/*} 会返回文件名），
# 所以先用 $MODPATH，再用 module.prop 认目录。
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

export PATH="/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH"
export HOME="$MODDIR/home"
export MAGPIE_NO_STATS=1
unset MAGPIE_ADDR

mkdir -p "$LOGDIR" "$MODDIR/data" "$HOME"

# ---------- 网页后台固定 key ----------
# 32 位 hex，满足 gui.CheckWebKey（≥16 位，只允许 [A-Za-z0-9._~-]）
if [ ! -s "$WEBKEYFILE" ]; then
  K=$(head -c 16 /dev/urandom 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
  [ -z "$K" ] && K=$(date +%s%N 2>/dev/null | md5sum 2>/dev/null | cut -c1-32)
  if [ -n "$K" ]; then
    echo "$K" > "$WEBKEYFILE"
    chmod 600 "$WEBKEYFILE" 2>/dev/null
  fi
fi
[ -s "$WEBKEYFILE" ] && export MAGPIE_WEB_KEY=$(cat "$WEBKEYFILE" 2>/dev/null)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [service] $1"; }

# ---------- 日志轮转：超 5MB 切一份 ----------
if [ -f "$LOGDIR/magpie.log" ]; then
  SZ=$(stat -c %s "$LOGDIR/magpie.log" 2>/dev/null || echo 0)
  [ "$SZ" -gt 5242880 ] && mv -f "$LOGDIR/magpie.log" "$LOGDIR/magpie.log.1"
fi

# 等系统起来（boot 完成前网络/DNS 可能没就绪）
sleep 8

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

cd "$MODDIR" || exit 1

# ---------- 只读桥：给模块版 App 读 webkey/日志用 ----------
sh "$MODDIR/scripts/bridge.sh" "$MODDIR" >/dev/null 2>&1

# ---------- 首次启动 ----------
if port_busy 3425; then
  log "3425 已被占用（旧 APK 版还开着？），本轮不启动，交给 watchdog 重试"
  log "建议：停用或卸载旧 APK 版 magpie，两者抢同一个端口"
else
  bg "$BIN" serve --web=0.0.0.0:3430
  echo "$BG_PID" > "$PIDFILE"
  log "已启动 magpie pid=$BG_PID"
fi

# 启动后刷一次桥，把新的 pid 同步过去
sleep 2
sh "$MODDIR/scripts/bridge.sh" "$MODDIR" >/dev/null 2>&1

# ---------- 拉起 watchdog（幂等：watchdog 自己有单实例检查）----------
BG_LOG="$LOGDIR/watchdog.log"
bg sh "$MODDIR/scripts/watchdog.sh" "$MODDIR"

if [ -s "$WEBKEYFILE" ]; then
  log "网页后台：http://127.0.0.1:3430/?k=$(cat "$WEBKEYFILE" 2>/dev/null)"
else
  log "网页后台：http://127.0.0.1:3430 （key 见 magpie.log 里的链接）"
fi
log "网关：http://127.0.0.1:3425/v1"
