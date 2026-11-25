#!/system/bin/sh
# 从 APK 版迁移数据到模块目录
# 用法：sh migrate-from-apk.sh <模块目录>
#   模块目录缺省为脚本上两级（即 /data/adb/modules/magpie_native）
#
# 行为：
#   1. 把 /data/data/com.magpie.android/files/magpie/data/ 逐文件 cp 到 <模块>/data/
#   2. 迁移前校验 usage.jsonl 行数，迁移后再次校验，前后必须一致，否则报错退出
#   3. settings.json / providers.json / caller-keys.json 做 md5 校验
#   4. 不删除旧目录（保险起见，确认新目录可用后可手动删）
#
# 注意：模块根 data/ 就是内核实际读写的目录（二进制旁边的 data/），
# 不是 bin/data —— 后者是 v0.1.4740 旧布局的错位位置。

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)
# 第二个参数可指定旧数据源（默认老 APK 版）。
# customize.sh 的音量键流程不需要它，这里只是让手动排查时能指向任意目录。
SRC="$2"
[ -z "$SRC" ] && SRC="/data/data/com.magpie.android/files/magpie/data"
DST="$MODDIR/data"

fail() {
  echo "[migrate] 失败：$1" >&2
  exit 1
}

[ -d "$SRC" ] || fail "旧数据目录不存在：$SRC"
mkdir -p "$DST" || fail "无法创建目标目录：$DST"

# usage.jsonl 行数校验
SRC_LINES=""
if [ -f "$SRC/usage.jsonl" ]; then
  SRC_LINES=$(wc -l < "$SRC/usage.jsonl" 2>/dev/null | tr -d ' ')
fi

echo "[migrate] 开始迁移 $SRC -> $DST"

# 逐文件复制（用 find 逐条处理，避免 cp -a 在个别 Android 上对特殊文件的怪异行为）
find "$SRC" -type f 2>/dev/null | while read f; do
  rel=${f#"$SRC"/}
  dir=$(dirname "$rel")
  mkdir -p "$DST/$dir" 2>/dev/null
  cp -f "$f" "$DST/$rel" 2>/dev/null || echo "[migrate] 跳过复制失败的文件：$rel"
done
# 空目录也补上（cache/ 等）
find "$SRC" -type d 2>/dev/null | while read d; do
  rel=${d#"$SRC"/}
  [ -n "$rel" ] && mkdir -p "$DST/$rel" 2>/dev/null
done

# 校验 usage.jsonl
if [ -n "$SRC_LINES" ]; then
  [ -f "$DST/usage.jsonl" ] || fail "usage.jsonl 未复制成功"
  DST_LINES=$(wc -l < "$DST/usage.jsonl" 2>/dev/null | tr -d ' ')
  if [ "$SRC_LINES" != "$DST_LINES" ]; then
    fail "usage.jsonl 行数不一致（迁移前 $SRC_LINES，迁移后 $DST_LINES），请重跑本脚本"
  fi
  echo "[migrate] usage.jsonl 行数校验通过：$DST_LINES 行"
else
  echo "[migrate] 旧目录无 usage.jsonl，跳过行数校验"
fi

# 校验关键配置（lan / gateway-mode 会原样保留）
# caller-keys.json 是网关 key 存储（internal/access/access.go:Path()）
for f in settings.json providers.json caller-keys.json; do
  if [ -f "$SRC/$f" ]; then
    if [ ! -f "$DST/$f" ]; then
      fail "$f 未复制成功"
    fi
    SRC_MD5=$(md5sum "$SRC/$f" 2>/dev/null | cut -d' ' -f1)
    DST_MD5=$(md5sum "$DST/$f" 2>/dev/null | cut -d' ' -f1)
    [ "$SRC_MD5" != "$DST_MD5" ] && fail "$f 内容校验不一致"
    echo "[migrate] $f 校验通过（md5 一致）"
  fi
done

# 收紧权限：settings.json / btcm 里有网关密钥、账号 token
chmod 700 "$DST" 2>/dev/null
find "$DST" -type d -exec chmod 700 {} \; 2>/dev/null
find "$DST" -type f -exec chmod 600 {} \; 2>/dev/null

echo "[migrate] 迁移完成，旧目录 $SRC 未删除（确认新目录可用后可手动删）"
exit 0
