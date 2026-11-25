#!/system/bin/sh
# Magpie Native 自检脚本：刷完 Magisk 后跑一遍，逐项给出通过/失败
# 用法：su -c "sh /data/adb/modules/magpie_native/scripts/selfcheck.sh"

resolve_moddir() {
  if [ -n "$MODPATH" ] && [ -d "$MODPATH" ]; then echo "$MODPATH"; return 0; fi
  case "$0" in
    */*) _d="$(cd "${0%/*}" 2>/dev/null && pwd)"; [ -n "$_d" ] && [ -f "$_d/../module.prop" ] && { cd "$_d/.." && pwd; return 0; } ;;
  esac
  [ -f "./module.prop" ] && { pwd; return 0; }
  [ -d "/data/adb/modules/magpie_native" ] && { echo "/data/adb/modules/magpie_native"; return 0; }
  echo ""
  return 1
}
MODDIR="$(resolve_moddir)"
[ -n "$MODDIR" ] || { echo "找不到模块目录"; exit 1; }
BIN="$MODDIR/magpie"
OK=0
BAD=0

export PATH="/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH"
export HOME="$MODDIR/home"

pass() { echo "  [OK]   $1"; OK=$((OK + 1)); }
fail() { echo "  [FAIL] $1"; BAD=$((BAD + 1)); }
warn() { echo "  [WARN] $1"; }

echo "=== Magpie Native 自检 ==="
echo "模块目录：$MODDIR"
echo

echo "1. 布局（magpie / .portable / data 必须同级）"
[ -f "$MODDIR/magpie" ] && pass "magpie 在模块根" || fail "magpie 不在模块根（旧布局？）"
[ -f "$MODDIR/.portable" ] && pass ".portable 在模块根" || fail ".portable 缺失（会写到 ~/.config/magpie）"
[ -d "$MODDIR/data" ] && pass "data/ 在模块根" || fail "data/ 缺失"
[ -d "$MODDIR/bin" ] && warn "bin/ 目录还在（旧布局残留，可删）"
echo

echo "2. 内核"
if [ -x "$BIN" ]; then
  pass "magpie 可执行"
  V=$("$BIN" --version 2>&1 | head -1)
  case "$V" in
    magpie*) pass "版本：$V" ;;
    *HOME*) fail "启动报 HOME 错误：$V" ;;
    *) warn "版本输出异常：$V" ;;
  esac
else
  fail "magpie 不可执行"
fi
echo

echo "3. 进程"
MPID=$(cat "$MODDIR/magpie.pid" 2>/dev/null)
if [ -n "$MPID" ] && [ -d "/proc/$MPID" ]; then
  pass "magpie 在跑 pid=$MPID"
else
  fail "magpie 没在跑"
fi
WD=0
for d in /proc/[0-9]*; do
  c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
  case "$c" in *watchdog.sh*) WD=$((WD + 1)) ;; esac
done
if [ "$WD" -eq 1 ]; then pass "watchdog 单实例正常"
elif [ "$WD" -eq 0 ]; then fail "watchdog 没在跑"
else fail "watchdog 有 $WD 个实例（应只有 1 个）"; fi
echo

echo "3.5 模块页开关与限流"
if [ -f "$MODDIR/disable" ]; then
  warn "模块页开关是「关」（disable 存在）—— 内核本该是停的"
else
  pass "模块页开关是「开」（无 disable）"
fi
if [ -f "$MODDIR/stop.flag" ]; then
  warn "stop.flag 存在（用户手动停过）—— 内核不会自动起，用 action.sh 或 start.sh 恢复"
else
  pass "无 stop.flag"
fi
if [ -n "$MPID" ] && [ -d "/proc/$MPID" ]; then
  NICE=$(awk '{print $19}' "/proc/$MPID/stat" 2>/dev/null)
  AFF=$(grep Cpus_allowed_list "/proc/$MPID/status" 2>/dev/null | awk '{print $2}')
  if [ -n "$NICE" ] && [ "$NICE" -ge 10 ] 2>/dev/null; then
    pass "内核已降优先级 nice=$NICE（CPU=${AFF:-?}）"
  else
    fail "内核 nice=${NICE:-?} 偏低 —— 请求暴增可能拖死系统，跑 scripts/limit.sh $MPID"
  fi
fi
echo

echo "4. 端口"
if netstat -tlnp 2>/dev/null | grep -q ":3425 "; then pass "3425 在监听"
elif busybox netstat -tlnp 2>/dev/null | grep -q ":3425 "; then pass "3425 在监听（busybox）"
else fail "3425 没在监听"; fi
if netstat -tlnp 2>/dev/null | grep -q ":3430 "; then pass "3430 在监听"
else warn "3430 没在监听（网页后台）"; fi
echo

echo "5. 网关存活"
if "$BIN" healthcheck >/dev/null 2>&1; then pass "healthcheck 通过"
else fail "healthcheck 失败"; fi
echo

echo "6. 网页后台链接"
if [ -s "$MODDIR/webkey.txt" ]; then
  pass "固定 key：$(cat "$MODDIR/webkey.txt")"
  echo "         地址：http://127.0.0.1:3430/?k=$(cat "$MODDIR/webkey.txt")"
else
  warn "没有固定 key（每次重启 key 会变）"
  L=$(grep -o "http://[^ ]*k=[A-Za-z0-9._~-]*" "$MODDIR/logs/magpie.log" 2>/dev/null | tail -1)
  [ -n "$L" ] && echo "         本次链接：$L"
fi
echo

echo "7. 数据"
if [ -f "$MODDIR/data/usage.jsonl" ]; then
  pass "usage.jsonl 存在，$(wc -l < "$MODDIR/data/usage.jsonl" | tr -d ' ') 行"
else
  warn "usage.jsonl 不存在（新装或未迁移）"
fi
for f in settings.json providers.json; do
  [ -f "$MODDIR/data/$f" ] && pass "data/$f 存在" || warn "data/$f 不存在"
done
OLD="/data/data/com.magpie.android/files/magpie/data/usage.jsonl"
if [ -f "$OLD" ] && [ -f "$MODDIR/data/usage.jsonl" ]; then
  A=$(wc -l < "$OLD" | tr -d ' ')
  B=$(wc -l < "$MODDIR/data/usage.jsonl" | tr -d ' ')
  [ "$A" = "$B" ] && pass "账本行数与 APK 版一致（$A）" || fail "账本行数不一致（APK $A / 模块 $B）"
fi
echo

echo "8. DNS"
if [ -f /etc/resolv.conf ] && grep -q nameserver /etc/resolv.conf 2>/dev/null; then
  pass "/etc/resolv.conf 有 nameserver"
else
  fail "/etc/resolv.conf 缺失或无 nameserver（DNS 会全挂）"
fi
echo

echo "9. 日志尾部"
echo "--- magpie.log 最后 15 行 ---"
tail -15 "$MODDIR/logs/magpie.log" 2>/dev/null | sed 's/^/  /'
echo "--- watchdog.log 最后 10 行 ---"
tail -10 "$MODDIR/logs/watchdog.log" 2>/dev/null | sed 's/^/  /'
echo

echo "=== 结果：通过 $OK 项，失败 $BAD 项 ==="
[ "$BAD" -eq 0 ] && echo "模块状态正常" || echo "有失败项，按上面 [FAIL] 逐条处理"
exit 0
