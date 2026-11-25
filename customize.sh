#!/system/bin/sh
# Magpie Native 模块安装脚本
#
# ============================================================
# 最重要的一件事：模块目录怎么拿
# ------------------------------------------------------------
# 旧版写的是 MODDIR=${0%/*}。这在 Magisk 上碰巧能work（Magisk 用绝对
# 路径调 customize.sh），但 KernelSU 的 meta-overlayfs metainstall 是
# cd 进模块目录后以相对路径执行 sh customize.sh —— 此时
#   $0            = "customize.sh"（没有斜杠）
#   ${0%/*}       = "customize.sh"（模式没匹配上，原样返回）
#   MODDIR        = "customize.sh"  ← 一个文件名，不是目录
# 后续 mkdir / touch / [ -f "$MODDIR/magpie" ] 全部落空，
# 报「magpie 内核不存在，zip 包可能损坏」——其实 zip 是好的。
#
# 正确做法：Magisk 和 KernelSU 都会设 $MODPATH（模块根绝对路径）。
# 解析顺序：$MODPATH → $MODDIR → $0 的目录（须绝对且存在）→ pwd（有 module.prop）
# ============================================================
SKIPUNZIP=0

resolve_moddir() {
  # 1. 安装器给的标准变量（Magisk / KernelSU / APatch 都设）
  if [ -n "$MODPATH" ] && [ -d "$MODPATH" ]; then
    echo "$MODPATH"
    return 0
  fi
  # 2. 已经有人设过 MODDIR
  if [ -n "$MODDIR" ] && [ -d "$MODDIR" ]; then
    echo "$MODDIR"
    return 0
  fi
  # 3. $0 带斜杠且指向真实目录
  case "$0" in
    */*)
      _d="${0%/*}"
      if [ -n "$_d" ] && [ -d "$_d" ]; then
        echo "$_d"
        return 0
      fi
      ;;
  esac
  # 4. 当前目录有 module.prop 就是模块根（metainstall 的 cd 场景）
  if [ -f "./module.prop" ]; then
    pwd
    return 0
  fi
  echo ""
  return 1
}

MODDIR="$(resolve_moddir)"

UI_PRINT() { ui_print "$1"; }

if [ -z "$MODDIR" ] || [ ! -d "$MODDIR" ]; then
  UI_PRINT "! 错误：无法定位模块目录"
  UI_PRINT "  MODPATH=$MODPATH"
  UI_PRINT "  MODDIR=$MODDIR"
  UI_PRINT "  \$0=$0"
  UI_PRINT "  pwd=$(pwd)"
  abort "! 安装中止：模块目录定位失败"
  exit 1
fi

UI_PRINT "*******************************"
UI_PRINT "  Magpie Native (AI Gateway)   "
UI_PRINT "  v0.1.4740-r4  android-arm64  "
UI_PRINT "  酷安@图图没耳朵          "
UI_PRINT "*******************************"
UI_PRINT "- 模块目录：$MODDIR"

# ============================================================
# 目录结构说明
# ------------------------------------------------------------
# 上游 internal/appdir/appdir.go 决定便携数据目录：
#     base := Beside(exe)          // 二进制所在目录
#     data := base + "/data"       // 便携数据 = 二进制旁边的 data/
# 所以 magpie、.portable、data/ 三者必须同级。
# 旧包（v0.1.4740）把二进制放在 bin/ 下、数据放在模块根 data/，
# 内核实际读写的是 bin/data（空目录），迁移校验通过但配置读不到。
# ============================================================

UI_PRINT "- 创建目录结构"
# 清掉上一版留下的「手动停止」标记：刷模块 = 用户要它跑
rm -f "$MODDIR/stop.flag" "$MODDIR/want-running" 2>/dev/null
mkdir -p "$MODDIR/data" "$MODDIR/logs" "$MODDIR/scripts" "$MODDIR/home" 2>/dev/null

# ---------- 二进制位置修正：bin/magpie -> 模块根 magpie ----------
if [ -f "$MODDIR/bin/magpie" ] && [ ! -f "$MODDIR/magpie" ]; then
  UI_PRINT "- 旧布局：把 bin/magpie 上移到模块根"
  mv -f "$MODDIR/bin/magpie" "$MODDIR/magpie" 2>/dev/null
fi
if [ -f "$MODDIR/bin/.portable" ]; then
  UI_PRINT "- 旧布局：把 bin/.portable 上移到模块根"
  mv -f "$MODDIR/bin/.portable" "$MODDIR/.portable" 2>/dev/null
fi
if [ -d "$MODDIR/bin/data" ]; then
  UI_PRINT "- 旧布局：把 bin/data 合并回 data/"
  for f in "$MODDIR/bin/data"/*; do
    [ -e "$f" ] || continue
    B=$(basename "$f")
    [ ! -e "$MODDIR/data/$B" ] && mv -f "$f" "$MODDIR/data/$B" 2>/dev/null
  done
  rmdir "$MODDIR/bin/data" 2>/dev/null
fi
rmdir "$MODDIR/bin" 2>/dev/null

# ---------- 便携模式标记 ----------
if [ ! -f "$MODDIR/.portable" ]; then
  : > "$MODDIR/.portable" 2>/dev/null
  if [ -f "$MODDIR/.portable" ]; then
    UI_PRINT "- 已创建便携模式标记 .portable"
  else
    UI_PRINT "! 无法创建 .portable（模块目录不可写？）"
  fi
fi

# ============================================================
# 内核二进制：找不到就自己从 zip 里解
# ------------------------------------------------------------
# 可能的情况：
#   a) 安装器没解出 magpie（大文件被跳过 / /data 空间不足）
#   b) 解出来了但被截断
# 这里主动补一次：优先用 $ZIPFILE（KernelSU/Magisk 都设），
# 再试几个常见路径。校验 ELF magic + 大小 >= 50MB。
# ============================================================
MINSIZE=50000000

bin_ok() {
  [ -f "$1" ] || return 1
  SZ=$(stat -c %s "$1" 2>/dev/null || echo 0)
  [ "$SZ" -ge "$MINSIZE" ] || return 1
  head -c 4 "$1" 2>/dev/null | od -An -tx1 | tr -d ' \n' | grep -q "7f454c46" || return 1
  return 0
}

extract_bin() {
  # $1 = zip 路径
  [ -f "$1" ] || return 1
  UI_PRINT "- 从 zip 里解出 magpie：$1"
  if command -v unzip >/dev/null 2>&1; then
    unzip -o "$1" magpie -d "$MODDIR" >/dev/null 2>&1 && bin_ok "$MODDIR/magpie" && return 0
  fi
  if command -v busybox >/dev/null 2>&1; then
    busybox unzip -o "$1" magpie -d "$MODDIR" >/dev/null 2>&1 && bin_ok "$MODDIR/magpie" && return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$1" "$MODDIR/magpie" <<'PY' >/dev/null 2>&1
import sys, zipfile, shutil
z = zipfile.ZipFile(sys.argv[1])
with z.open('magpie') as src, open(sys.argv[2], 'wb') as dst:
    shutil.copyfileobj(src, dst, 1 << 20)
PY
    bin_ok "$MODDIR/magpie" && return 0
  fi
  return 1
}

if ! bin_ok "$MODDIR/magpie"; then
  if [ -f "$MODDIR/magpie" ]; then
    SZ=$(stat -c %s "$MODDIR/magpie" 2>/dev/null || echo 0)
    UI_PRINT "! magpie 存在但不完整（$SZ 字节，应为 102760744）"
    rm -f "$MODDIR/magpie"
  fi

  GOT=0
  if [ -n "$ZIPFILE" ] && extract_bin "$ZIPFILE"; then
    GOT=1
  fi
  if [ "$GOT" = "0" ]; then
    for z in /sdcard/Download/magpie-native*.zip \
             /storage/emulated/0/Download/magpie-native*.zip \
             /data/adb/modules_update/magpie_native/*.zip; do
      [ -f "$z" ] || continue
      extract_bin "$z" && { GOT=1; break; }
    done
  fi

  if [ "$GOT" = "0" ]; then
    UI_PRINT "! 错误：拿不到 magpie 内核"
    UI_PRINT "  zip 里应该有 102760744 字节的 magpie"
    UI_PRINT "  常见原因：/data 空间不足，或安装器跳过了大文件"
    UI_PRINT "  手动补救（不用重刷）："
    UI_PRINT "    1. 把 magpie-native zip 里的 magpie 拷到 /sdcard/Download/"
    UI_PRINT "    2. su -c \"cp /sdcard/Download/magpie $MODDIR/magpie\""
    UI_PRINT "    3. su -c \"chmod 755 $MODDIR/magpie\""
    UI_PRINT "    4. su -c \"sh $MODDIR/scripts/restart.sh $MODDIR\""
    abort "! 安装中止：内核缺失"
    exit 1
  fi
fi

chmod 755 "$MODDIR/magpie" 2>/dev/null
chmod 755 "$MODDIR"
chmod 755 "$MODDIR/service.sh" "$MODDIR/post-fs-data.sh" "$MODDIR/uninstall.sh" 2>/dev/null
chmod 755 "$MODDIR/scripts/"*.sh 2>/dev/null
chmod 755 "$MODDIR/action.sh" 2>/dev/null
UI_PRINT "- 内核 magpie 就位 ($(du -k "$MODDIR/magpie" 2>/dev/null | cut -d'	' -f1) KB)"

# ---------- 网页后台固定 key ----------
WEBKEYFILE="$MODDIR/webkey.txt"
if [ ! -s "$WEBKEYFILE" ]; then
  K=$(head -c 16 /dev/urandom 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
  [ -z "$K" ] && K=$(date +%s%N 2>/dev/null | md5sum 2>/dev/null | cut -c1-32)
  if [ -n "$K" ]; then
    echo "$K" > "$WEBKEYFILE"
    chmod 600 "$WEBKEYFILE" 2>/dev/null
    UI_PRINT "- 已生成固定网页后台 key：$K"
    UI_PRINT "  后台地址 http://127.0.0.1:3430/?k=$K"
  fi
else
  UI_PRINT "- 沿用已有网页后台 key：$(cat "$WEBKEYFILE" 2>/dev/null)"
fi

# ---------- 从 APK 版迁移旧数据 ----------
# 旧 APK 数据目录：/data/data/com.magpie.android/files/magpie/data/
# 里面 usage.jsonl 是历史用量账本，绝不能丢。
#
# 交互方式（音量键选择）：
#   检测到旧数据时会问「要不要迁移」，音量上=迁移 / 音量下=跳过，
#   10 秒不按默认迁移。已迁移过（data/usage.jsonl 已存在）则不再问，
#   直接跳过 —— 每次更新刷入都快。
#
# 也可以刷入前设环境变量 SKIP_MIGRATION=1 一票否决（用 KSU 的 adb 命令刷）。

OLD_DATA="/data/data/com.magpie.android/files/magpie/data"

# ask_migration <提示语>
# 返回 0 = 迁移，1 = 跳过。没有 getevent（极少数 ROM）时默认迁移。
ask_migration() {
  UI_PRINT ""
  UI_PRINT "  音量上 = 迁移（默认）"
  UI_PRINT "  音量下 = 跳过（10 秒不按按迁移算）"
  UI_PRINT ""
  # KSU/Magisk 的安装环境里 getevent 可用（/dev/input 由 root 可读）
  if ! command -v getevent >/dev/null 2>&1; then
    UI_PRINT "- 此环境不支持按键选择，默认迁移"
    return 0
  fi
  # 找一个输入设备：优先 gpio-keys（实体按键），否则第一个 event 节点
  DEV=""
  for d in /dev/input/event*; do
    [ -r "$d" ] || continue
    case "$(getevent -pl 2>/dev/null | grep -A1 "$d" | head -1)" in
      *gpio-keys*) DEV="$d"; break ;;
    esac
  done
  [ -z "$DEV" ] && DEV=$(ls /dev/input/event* 2>/dev/null | head -1)
  [ -z "$DEV" ] && { UI_PRINT "- 找不到输入设备，默认迁移"; return 0; }

  # getevent -ql 输出形如：/dev/input/event0 EV_KEY KEY_VOLUMEUP DOWN
  # 用 timeout 限 10 秒；toybox 的 timeout 用法：timeout <秒> <cmd>
  RESULT=$(timeout 10 getevent -ql "$DEV" 2>/dev/null | while read -r _t _type _code _val; do
    case "$_type" in
      EV_KEY) ;;
      *) continue ;;
    esac
    case "$_code" in
      KEY_VOLUMEUP)   echo up;   break ;;
      KEY_VOLUMEDOWN) echo down; break ;;
    esac
  done)
  case "$RESULT" in
    down)
      UI_PRINT "- 选择：跳过迁移"
      return 1
      ;;
    *)
      UI_PRINT "- 选择：迁移（或超时默认）"
      return 0
      ;;
  esac
}

if [ "$SKIP_MIGRATION" = "1" ]; then
  UI_PRINT "- SKIP_MIGRATION=1，跳过迁移"
elif [ ! -d "$OLD_DATA" ]; then
  UI_PRINT "- 未检测到旧 APK 数据目录，跳过迁移"
elif [ -s "$MODDIR/data/usage.jsonl" ]; then
  # 模块自己的 data/ 里已经有账本：说明之前迁移过或模块版已写过账本。
  # 再问一遍只会让每次更新刷入都慢几十秒，直接跳过。
  UI_PRINT "- 模块 data/usage.jsonl 已存在（之前迁移过或已在使用），跳过"
  UI_PRINT "  如需重新迁移，手动跑："
  UI_PRINT "  sh $MODDIR/scripts/migrate-from-apk.sh $MODDIR"
else
  UI_PRINT "- 检测到旧 APK 版数据目录"
  UI_PRINT "  $OLD_DATA"
  if [ -f "$OLD_DATA/usage.jsonl" ]; then
    UI_PRINT "- usage.jsonl 存在（历史账本，$(wc -l < "$OLD_DATA/usage.jsonl" 2>/dev/null | tr -d ' ') 行）"
  else
    UI_PRINT "! 警告：旧目录里没有 usage.jsonl"
  fi
  if ask_migration; then
    sh "$MODDIR/scripts/migrate-from-apk.sh" "$MODDIR"
    if [ $? -eq 0 ]; then
      UI_PRINT "- 数据迁移完成，usage.jsonl 行数校验通过"
      UI_PRINT "- 提示：lan / gateway-mode 设置原样保留，无需重设"
      UI_PRINT "! 记得停用或卸载旧 APK 版，否则两者抢 3425 端口"
    else
      UI_PRINT "! 迁移脚本返回失败，旧数据未删除，可稍后手动跑："
      UI_PRINT "  sh $MODDIR/scripts/migrate-from-apk.sh $MODDIR"
    fi
  else
    UI_PRINT "- 已跳过迁移。以后想迁随时手动跑："
    UI_PRINT "  sh $MODDIR/scripts/migrate-from-apk.sh $MODDIR"
  fi
fi

# ---------- 权限收紧（settings.json 含网关密钥与账号 token）----------
chmod 700 "$MODDIR/data" "$MODDIR/home" 2>/dev/null
chmod 600 "$MODDIR/data"/*.json "$MODDIR/data"/*.jsonl "$MODDIR/data"/*.btcm 2>/dev/null
find "$MODDIR/data" -type d -exec chmod 700 {} \; 2>/dev/null
find "$MODDIR/data" -type f -exec chmod 600 {} \; 2>/dev/null
UI_PRINT "- data/ 权限已收紧（700/600）"

UI_PRINT "*******************************"
UI_PRINT "- 安装完成，service.sh 会在开机后自动拉起 magpie"
UI_PRINT "  网关端口：3425（API）"
UI_PRINT "  网页后台：3430"
UI_PRINT "  日志：$MODDIR/logs/magpie.log"
UI_PRINT "  自检：sh $MODDIR/scripts/selfcheck.sh"
UI_PRINT "*******************************"
