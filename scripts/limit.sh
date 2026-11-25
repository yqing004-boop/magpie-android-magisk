#!/system/bin/sh
# 给 magpie 内核限资源：CPU 亲和性 + 优先级 + I/O 优先级
#
# ============================================================
# 为什么需要
# ------------------------------------------------------------
# 症状：请求一多（几十个并发流），整个系统卡死，弹出
#       「进程 system 没有响应」（system_server ANR）。
#
# 根因：上游 magpie 的 HTTP 服务**没有任何并发上限** ——
#       net/http 默认每个连接一个 goroutine，Go runtime 默认
#       GOMAXPROCS = 核数，于是几十个流同时跑就把 4~8 个核全占满。
#       system_server 抢不到 CPU，就 ANR。
#
#       桌面端不在意这个（电脑有几十个核、还能降频），手机不行。
#       上游没有提供任何环境变量或设置来限并发（我查过
#       internal/gateway/gateway.go 的 NewServer，只有
#       ReadHeaderTimeout / IdleTimeout，没有 MaxConns / Limit），
#       所以只能从进程层面压。
#
# 三管齐下：
#   1. nice 15       —— 内核只在系统空闲时拿 CPU，system_server 永远优先
#   2. taskset 小核  —— 绑到主频最低的那几个核，不占大核
#                       （读 /sys/.../cpufreq/cpuinfo_max_freq 找最小的）
#   3. ionice 3      —— 日志和 usage.jsonl 的写入不抢前台 I/O
#
# 效果：网关吞吐几乎不变（瓶颈在网络和供应商 API，不在 CPU），
#       但「请求暴增 → 系统卡死」彻底消失。
#
# 用法：sh scripts/limit.sh <pid> [<MODDIR>]
#       或   sh scripts/limit.sh --all [<MODDIR>]   （找到 magpie 自己处理）

MODDIR="$2"
[ -z "$MODDIR" ] && MODDIR=$(cd "$(dirname "$0")/.." && pwd)

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [limit] $1" >> "$MODDIR/logs/watchdog.log"
}

# ---------- 找小核 ----------
#
# 手机 SoC 典型布局：cpu0-3 小核（1.8GHz）、cpu4-6 中核、cpu7 大核（2.8GHz）。
# 但不是所有 ROM 都暴露 cpufreq，读不到就退回「只绑 cpu0-3」。
pick_little_cores() {
  _freqs=""
  _min=999999999
  _max=0
  for c in /sys/devices/system/cpu/cpu[0-9]*; do
    [ -d "$c" ] || continue
    _n=${c##*/cpu}
    _f=$(cat "$c/cpufreq/cpuinfo_max_freq" 2>/dev/null)
    [ -z "$_f" ] && continue
    case "$_f" in *[!0-9]*) continue ;; esac
    _freqs="$_freqs $_n:$_f"
    [ "$_f" -lt "$_min" ] && _min=$_f
    [ "$_f" -gt "$_max" ] && _max=$_f
  done
  # cpufreq 读不到，或者所有核主频一样（没大小核 / 不可见）
  if [ -z "$_freqs" ] || [ "$_min" = "$_max" ]; then
    echo ""
    return
  fi
  # 主频 <= 最小值 1.15 倍的算小核
  _thr=$((_min + _min / 10))
  _out=""
  for item in $_freqs; do
    _n=${item%%:*}
    _f=${item##*:}
    [ "$_f" -le "$_thr" ] && _out="$_out,$_n"
  done
  echo "${_out#,}"
}

# ---------- 找 magpie 的 pid ----------
find_pid() {
  if [ -f "$MODDIR/magpie.pid" ]; then
    P=$(cat "$MODDIR/magpie.pid" 2>/dev/null)
    if [ -n "$P" ] && [ -d "/proc/$P" ]; then
      echo "$P"
      return 0
    fi
  fi
  for d in /proc/[0-9]*; do
    c=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
    case "$c" in
      *"$MODDIR/magpie"*serve*) echo "${d#/proc/}"; return 0 ;;
    esac
  done
  return 1
}

apply() {
  _pid="$1"
  [ -z "$_pid" ] && return 1
  [ -d "/proc/$_pid" ] || return 1

  # 1. 优先级
  if command -v renice >/dev/null 2>&1; then
    renice 15 -p "$_pid" >/dev/null 2>&1
  fi

  # 2. CPU 亲和性：绑小核
  _cores=$(pick_little_cores)
  if [ -n "$_cores" ] && command -v taskset >/dev/null 2>&1; then
    taskset -p "$_cores" "$_pid" >/dev/null 2>&1 \
      && log "pid=$_pid 绑定 CPU $_cores（小核）" \
      || log "pid=$_pid taskset 失败（无权限？）"
  elif command -v taskset >/dev/null 2>&1; then
    # 读不到主频：保守绑 cpu0-3（多数 arm64 手机的小核区）
    taskset -p 0-3 "$_pid" >/dev/null 2>&1 \
      && log "pid=$_pid 绑定 CPU 0-3（默认小核区）"
  fi

  # 3. I/O 优先级（best-effort，多数 ROM 没有 ionice）
  if command -v ionice >/dev/null 2>&1; then
    ionice -c 3 -p "$_pid" >/dev/null 2>&1
  fi

  _nice=$(awk '{print $19}' "/proc/$_pid/stat" 2>/dev/null)
  _aff=$(grep Cpus_allowed_list "/proc/$_pid/status" 2>/dev/null | awk '{print $2}')
  log "pid=$_pid 限流完成：nice=${_nice:-?} cpu=${_aff:-?}"
  return 0
}

case "$1" in
  --all|"")
    P=$(find_pid) || { echo "没找到 magpie 进程"; exit 1; }
    if apply "$P"; then
      _a=$(grep Cpus_allowed_list "/proc/$P/status" 2>/dev/null | awk '{print $2}')
      _n=$(awk '{print $19}' "/proc/$P/stat" 2>/dev/null)
      echo "已限流 pid=$P（nice=${_n:-?}，CPU=${_a:-?}）"
    else
      echo "限流失败 pid=$P"
      exit 1
    fi
    ;;
  *)
    if apply "$1"; then
      _a=$(grep Cpus_allowed_list "/proc/$1/status" 2>/dev/null | awk '{print $2}')
      _n=$(awk '{print $19}' "/proc/$1/stat" 2>/dev/null)
      echo "已限流 pid=$1（nice=${_n:-?}，CPU=${_a:-?}）"
    else
      echo "限流失败 pid=$1"
      exit 1
    fi
    ;;
esac

exit 0
