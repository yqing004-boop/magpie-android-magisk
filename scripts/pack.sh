#!/bin/sh
# 打 Magpie Native 模块 zip
#
# 用法：
#   sh scripts/pack.sh <内核二进制路径> <版本名> <versionCode> [--update]
# 例：
#   sh scripts/pack.sh ~/x/magpie v0.1.4740-r5 4745            # 完整包（带内核）
#   sh scripts/pack.sh ~/x/magpie v0.1.4740-r5 4745 --update   # 更新包（约 1MB，不带内核）
#
# 完整包：内含 102MB 内核，首次安装 / 内核升级用。
# 更新包：只含脚本 + ksha.txt（内核 sha256），刷入十几秒；
#         customize.sh 校验旧模块内核 sha256 一致才接管，不一致拒绝。
#
# 产物：dist-magisk/magpie-native-<版本名>.zip
#
# 关键点：内核 magpie、便携标记 .portable、数据目录 data/ 三者必须同级
# （都在模块根），因为上游 internal/appdir/appdir.go 的便携判定是
# 「二进制旁边的 data/」，把二进制塞进 bin/ 会让内核读写 bin/data。
set -eu

SRC="$(cd "$(dirname "$0")/.." && pwd)"        # magisk-module/
REPO="$(cd "$SRC/.." && pwd)"                   # magpie-bake/

BIN="${1:-}"
VER="${2:-}"
CODE="${3:-}"
UPDATE_MODE="${4:-}"

# 更新包也要给 BIN（用来算 sha256 写进 ksha.txt），但文件本身不进包
[ -n "$BIN" ] || die "缺内核路径。用法：sh scripts/pack.sh <magpie> <版本名> <versionCode> [--update]"
[ -f "$BIN" ] || die "内核不存在：$BIN"

die() { echo "错误：$*" >&2; exit 1; }

[ -n "$BIN" ] || die "缺内核路径。用法：sh scripts/pack.sh <magpie> <版本名> <versionCode>"
[ -f "$BIN" ] || die "内核不存在：$BIN"
[ -n "$VER" ] || die "缺版本名"
[ -n "$CODE" ] || die "缺 versionCode"

# 内核必须是 aarch64 ELF
head -c 4 "$BIN" | od -An -tx1 | tr -d ' \n' | grep -q "7f454c46" \
  || die "$BIN 不是 ELF 文件"
SIZE=$(stat -c %s "$BIN")
[ "$SIZE" -gt 50000000 ] || die "内核只有 $SIZE 字节，不像完整的 magpie（应约 98MB）"

STAGE="$REPO/.pack-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/scripts" "$STAGE/system/etc" "$STAGE/logs"

# ---------- 根文件 ----------
cp -f "$SRC/module.prop"      "$STAGE/module.prop"
cp -f "$SRC/customize.sh"     "$STAGE/customize.sh"
cp -f "$SRC/service.sh"       "$STAGE/service.sh"
cp -f "$SRC/post-fs-data.sh"  "$STAGE/post-fs-data.sh"
cp -f "$SRC/uninstall.sh"     "$STAGE/uninstall.sh"
cp -f "$SRC/action.sh"        "$STAGE/action.sh"
cp -f "$SRC/README.md"        "$STAGE/README.md"

# ---------- 脚本 ----------
# 排除 pack.sh 本身（打 zip 的工具，不进 zip）
for f in "$SRC"/scripts/*.sh; do
  [ -f "$f" ] || continue
  case "$f" in *pack.sh) continue ;; esac
  cp -f "$f" "$STAGE/scripts/"
done

# ---------- DNS ----------
cp -f "$SRC/system/etc/resolv.conf" "$STAGE/system/etc/resolv.conf"

# ---------- 便携标记（必须在模块根，与 data/ 同级） ----------
: > "$STAGE/.portable"
chmod 644 "$STAGE/.portable"

# ---------- 内核 / 内核校验值 ----------
KSHA=$(sha256sum "$BIN" | cut -d' ' -f1)
if [ "$UPDATE_MODE" = "--update" ]; then
  # 更新包：只写内核 sha256，customize.sh 校验旧模块内核一致才接管
  echo "$KSHA" > "$STAGE/ksha.txt"
  chmod 644 "$STAGE/ksha.txt"
  echo "（更新包模式：内核不进包，sha256 写入 ksha.txt）"
else
  cp -f "$BIN" "$STAGE/magpie"
  chmod 755 "$STAGE/magpie"
  # 完整包也带 ksha.txt： customize.sh 据此判断内核对不对
  echo "$KSHA" > "$STAGE/ksha.txt"
  chmod 644 "$STAGE/ksha.txt"
fi

# ---------- 权限 ----------
chmod 755 "$STAGE" "$STAGE/scripts"
chmod 755 "$STAGE/customize.sh" "$STAGE/service.sh" "$STAGE/post-fs-data.sh" "$STAGE/uninstall.sh" "$STAGE/action.sh"
chmod 755 "$STAGE/scripts/"*.sh
chmod 644 "$STAGE/module.prop" "$STAGE/README.md" "$STAGE/system/etc/resolv.conf"

# ---------- 版本号写进 module.prop ----------
sed -i "s|^version=.*|version=$VER|" "$STAGE/module.prop"
sed -i "s|^versionCode=.*|versionCode=$CODE|" "$STAGE/module.prop"

# ---------- 打 zip ----------
mkdir -p "$REPO/dist-magisk"
# 更新包加 -update 后缀，两种包共存
if [ "$UPDATE_MODE" = "--update" ]; then
  ZIP="$REPO/dist-magisk/magpie-native-$VER-update.zip"
else
  ZIP="$REPO/dist-magisk/magpie-native-$VER.zip"
fi
rm -f "$ZIP"

# 用 python 打：控制时间戳（可复现）
# 内核也用 deflate：102MB 能压到 ~40MB，下载量差一倍多。
# 解压是首启一次的事，CPU 开销可忽略。
python3 - "$STAGE" "$ZIP" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
FIXED = (2026, 1, 1, 0, 0, 0)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(stage):
        dirs.sort()
        for name in sorted(files):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, stage).replace(os.sep, '/')
            st = os.stat(full)
            zi = zipfile.ZipInfo(rel, date_time=FIXED)
            zi.external_attr = (st.st_mode & 0xFFFF) << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            with open(full, 'rb') as f:
                z.writestr(zi, f.read())
print('  ', out, os.path.getsize(out), 'bytes')
PY

rm -rf "$STAGE"

echo
echo "zip: $ZIP"
echo "大小: $(du -h "$ZIP" | cut -f1)"
echo "sha256: $(sha256sum "$ZIP" | cut -d' ' -f1)"
echo "内核 sha256: $KSHA"
[ "$UPDATE_MODE" = "--update" ] && echo "（更新包：刷入时从旧模块接管内核与数据）"
echo
echo "内容："
unzip -l "$ZIP"
