#!/system/bin/sh
# 只读桥：把模块里「App 需要看但不能改」的文件暴露到 /data/local/tmp/magpie-native
#
# 为什么需要：
#   模块在 /data/adb/modules/magpie_native/，父目录 0700 root:root，
#   普通 App（uid 10xxx）连 stat 都做不到。而模块版 App 要显示
#   「网关在不在跑」「模块什么版本」「日志尾巴」—— 这些必须能读。
#
# 设计原则：
#   1. 只挂「读也无害」的东西：webkey.txt、logs/、module.prop、magpie.pid
#   2. **绝不暴露 data/** —— 里面有 settings.json（网关密钥）、
#      providers.json（各供应商 token）、caller-keys.json（网关 key）。
#      需要读 data/ 的操作一律走 Shizuku/root。
#   3. 桥目录 0755，文件 0644 —— /data/local/tmp 本身是 0771，
#      其它 uid 可遍历，所以这样就能读。
#
# 什么时候跑：
#   service.sh 启动时（每次开机），以及 restart.sh 之后。
#   幂等：重复跑只会重新 bind。

MODDIR="$1"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)

BRIDGE=/data/local/tmp/magpie-native

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [bridge] $1" >> "$MODDIR/logs/watchdog.log"; }

mkdir -p "$BRIDGE" 2>/dev/null
chmod 755 "$BRIDGE" 2>/dev/null

# ---------- 1. 直接把可读文件复制/链接进桥 ----------
# 用「复制」而不是 symlink：symlink 的目标权限还是 0600，
# 普通 uid 跟过去一样读不到。复制出来改 0644 才行。

# module.prop（版本号）
if [ -f "$MODDIR/module.prop" ]; then
  cp -f "$MODDIR/module.prop" "$BRIDGE/module.prop" 2>/dev/null
  chmod 644 "$BRIDGE/module.prop" 2>/dev/null
fi

# webkey.txt（网页后台 key，32 位 hex；泄露只影响本机网页登录，
# 而本机网页本来就在 127.0.0.1，且网关 key 是另一个文件）
if [ -f "$MODDIR/webkey.txt" ]; then
  cp -f "$MODDIR/webkey.txt" "$BRIDGE/webkey.txt" 2>/dev/null
  chmod 644 "$BRIDGE/webkey.txt" 2>/dev/null
fi

# magpie.pid（进程号，用于界面显示）
if [ -f "$MODDIR/magpie.pid" ]; then
  cp -f "$MODDIR/magpie.pid" "$BRIDGE/magpie.pid" 2>/dev/null
  chmod 644 "$BRIDGE/magpie.pid" 2>/dev/null
fi

# ---------- 2. 日志：整个 logs/ 目录 bind 挂载 ----------
# 日志里可能带请求 URL 和错误堆栈，但不带密钥（magpie 自己会脱敏）。
# 用户排查问题就是要看这个。
mkdir -p "$BRIDGE/logs" 2>/dev/null
mkdir -p "$MODDIR/logs" 2>/dev/null
# 先把已有的日志文件复制一份（bind 挂载失败时的兜底）
for f in magpie.log watchdog.log magpie.log.1; do
  if [ -f "$MODDIR/logs/$f" ]; then
    cp -f "$MODDIR/logs/$f" "$BRIDGE/logs/$f" 2>/dev/null
  fi
done
chmod 755 "$BRIDGE/logs" 2>/dev/null
chmod 644 "$BRIDGE/logs"/*.log* 2>/dev/null

# 尝试 bind 挂载（成功的话日志实时可见，不用反复 cp）
# 注意：bind 后目标目录的权限等于源目录的权限，所以源目录必须先 chmod 755，
# 否则 App 仍然读不到（源目录默认是 0700）。
chmod 755 "$MODDIR/logs" 2>/dev/null
mountpoint -q "$BRIDGE/logs" 2>/dev/null
if [ $? -ne 0 ]; then
  mount -o bind "$MODDIR/logs" "$BRIDGE/logs" 2>/dev/null
fi
# bind 挂载后重新放开文件权限
chmod 755 "$BRIDGE/logs" 2>/dev/null
chmod 644 "$BRIDGE/logs"/* 2>/dev/null

# ---------- 3. 标记文件：告诉 App「桥挂好了」 ----------
echo "$(date '+%Y-%m-%d %H:%M:%S')" > "$BRIDGE/.ready" 2>/dev/null
chmod 644 "$BRIDGE/.ready" 2>/dev/null

log "只读桥已就绪：$BRIDGE"
exit 0
