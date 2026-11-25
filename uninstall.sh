#!/system/bin/sh
# Magpie Native 卸载清理脚本
#
# 坑：Android toybox 的 pgrep 在部分版本不支持 -f（匹配的是进程名 sh
# 而不是完整命令行），按命令行匹配会一个都杀不到，watchdog 残留继续
# 拉 magpie。所以这里一律扫 /proc/*/cmdline，兼容性最稳。

# 模块目录解析（同 customize.sh，兼容 KernelSU 的相对路径调用）
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
[ -n "$MODDIR" ] || exit 0
PIDFILE="$MODDIR/magpie.pid"

kill_matching() {
  SIG="$1"
  PAT="$2"
  for d in /proc/[0-9]*; do
    p=${d#/proc/}
    [ "$p" = "$$" ] && continue
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in
      *$PAT*) kill $SIG "$p" 2>/dev/null ;;
    esac
  done
}

# 1. 先杀 watchdog，防止它把主进程拉活
kill_matching "" "$MODDIR/scripts/watchdog.sh"
kill_matching "-9" "$MODDIR/scripts/watchdog.sh"

# 2. 再杀主进程
if [ -f "$PIDFILE" ]; then
  PID=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$PID" ] && kill "$PID" 2>/dev/null
fi
kill_matching "" "$MODDIR/magpie"
sleep 1
kill_matching "-9" "$MODDIR/magpie"
rm -f "$PIDFILE" "$MODDIR/stop.flag" "$MODDIR/want-running"

# 3. 解除 DNS bind mount（如果是我们挂的）
umount /system/etc/resolv.conf 2>/dev/null
umount /etc/resolv.conf 2>/dev/null

# 3.5 解除只读桥的 bind mount
umount /data/local/tmp/magpie-native/logs 2>/dev/null
rm -rf /data/local/tmp/magpie-native 2>/dev/null

# 4. 数据目录：默认保留
# Magisk 卸载模块只删模块目录，会连带删掉 data/。
# 想保留数据（usage.jsonl 历史账本等），卸载前先手动备份：
#   cp -a /data/adb/modules/magpie_native/data /sdcard/magpie-data-backup
# 想彻底清除（连账号密钥一起删），卸载前手动执行：
#   rm -rf /data/adb/modules/magpie_native/data
# 这里不做自动删除，避免误删历史账本。

exit 0
