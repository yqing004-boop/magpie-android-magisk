#!/system/bin/sh
# Magpie Native DNS 修复（post-fs-data 阶段，root 运行）
#
# 原理：
#   Go 程序（CGO_ENABLED=0 的 magpie）读 /etc/resolv.conf 拿 DNS 服务器。
#   Android 原生没有这个文件，Go 会回退到 [::1]:53，被系统拒绝，DNS 全挂。
#   根治办法：让 /etc/resolv.conf 存在并指向公共 DNS。
#
# 实现方式（两条路，Magisk 优先，bind 兜底）：
#   1. Magisk overlay：模块的 system/etc/resolv.conf 由 Magisk 自动
#      bind-mount 到 /system/etc/resolv.conf。这是首选，重启自动生效。
#   2. bind mount 兜底：只有当 /etc/resolv.conf 不存在或没有 nameserver
#      行时才做，且必须先确保目标文件存在——Android 的 /etc 是指向
#      /system/etc 的符号链接，目标不存在时 mount 必失败。
#
# 注意：本脚本只做「确保文件存在 + 必要时挂载」，不写 /system 分区。

# 模块目录：post-fs-data 阶段 Magisk 用绝对路径调，但为了兼容
# KernelSU/APatch 的相对路径调用，同样先看 $MODPATH。
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
SRC="$MODDIR/system/etc/resolv.conf"

# 生成源文件（Magisk 挂载用）
mkdir -p "$MODDIR/system/etc"
if [ ! -s "$SRC" ]; then
  printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 1.1.1.1\n' > "$SRC"
  chmod 644 "$SRC"
fi

# 目标已由 Magisk overlay 生效 → 什么都不用做
if [ -f /system/etc/resolv.conf ] && grep -q "nameserver" /system/etc/resolv.conf 2>/dev/null; then
  exit 0
fi

# 兜底：先建空目标文件再挂载（否则 mount 报 No such file or directory）
TARGET=/system/etc/resolv.conf
if [ ! -e "$TARGET" ]; then
  touch "$TARGET" 2>/dev/null
fi
if [ -e "$TARGET" ]; then
  mount -o bind "$SRC" "$TARGET" 2>/dev/null
fi

# 再兜底一次：/etc/resolv.conf 独立存在的情况（少数 ROM）
if [ ! -f /etc/resolv.conf ] || ! grep -q "nameserver" /etc/resolv.conf 2>/dev/null; then
  mkdir -p /etc 2>/dev/null
  touch /etc/resolv.conf 2>/dev/null
  mount -o bind "$SRC" /etc/resolv.conf 2>/dev/null
fi

exit 0
