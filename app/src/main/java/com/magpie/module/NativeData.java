package com.magpie.module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据层的小工具：把 magpie 的 JSON 取成界面要的那几个数，以及人话格式化。
 *
 * <p>原则：字段一律当「可能没有」处理（{@code optInt} / {@code optDouble}），
 * magpie 改字段时界面最多少显示一块，不会整页崩。
 */
final class NativeData {

    private static final Pattern TZ = Pattern.compile("([+-]\\d{2}):(\\d{2})$");
    private static final SimpleDateFormat ISO;

    static {
        ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        ISO.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private NativeData() {
    }

    // ------------------------------------------------------------------
    // 数字单位：英文 K/M/B ↔ 中文 万/亿
    // ------------------------------------------------------------------

    /** 0 = 英文（K/M/B），1 = 中文（万/亿）。 */
    private static int unitMode;

    static void setUnitMode(int mode) {
        unitMode = mode == 1 ? 1 : 0;
    }

    static int unitMode() {
        return unitMode;
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    static JSONObject obj(JSONObject o, String key) {
        return o == null ? null : o.optJSONObject(key);
    }

    static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }

    static JSONObject at(JSONArray a, int i) {
        return a == null ? null : a.optJSONObject(i);
    }

    static String str(JSONObject o, String key, String def) {
        if (o == null) {
            return def;
        }
        String s = o.optString(key, null);
        return s == null || s.isEmpty() ? def : s;
    }

    static int num(JSONObject o, String key) {
        return o == null ? 0 : (int) o.optLong(key, 0);
    }

    /** 取 long 字段：字节数这类累计值会超过 int 上限（int 溢出会变负数）。 */
    static long lng(JSONObject o, String key) {
        return o == null ? 0 : o.optLong(key, 0);
    }

    static double dec(JSONObject o, String key) {
        return o == null ? 0 : o.optDouble(key, 0);
    }

    static boolean flag(JSONObject o, String key) {
        return o != null && o.optBoolean(key, false);
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    /**
     * 大数字缩写，按当前单位模式。
     *
     * <p>英文：1234 → 1.23K，1234567 → 1.23M，1.2e9 → 1.2B
     * <p>中文：12345 → 1.23万，1.2e8 → 1.2亿
     */
    static String tokens(long n) {
        if (n < 0) {
            return "—";
        }
        if (n < 1000) {
            return String.valueOf(n);
        }
        if (unitMode == 1) {
            if (n < 10_000) {
                return String.valueOf(n);   // 不到一万就写原数
            }
            if (n < 100_000_000L) {
                return trim(n / 10_000.0) + "万";
            }
            return trim(n / 100_000_000.0) + "亿";
        }
        if (n < 999_950) { // 999.95K 会四舍五入成 1000K，不如直接进位
            return trim(n / 1000.0) + "K";
        }
        if (n < 999_950_000L) {
            return trim(n / 1_000_000.0) + "M";
        }
        return trim(n / 1_000_000_000.0) + "B";
    }

    /** 请求数：跟 token 用同一套单位，读起来才一致。 */
    static String count(long n) {
        return tokens(n);
    }

    private static String trim(double v) {
        if (v >= 100) {
            return String.valueOf(Math.round(v));
        }
        String s = String.format(Locale.US, "%.2f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 费用：美元。小于一分也标出来，别显示成 $0。 */
    static String cost(double usd) {
        if (usd <= 0) {
            return "$0";
        }
        if (usd < 0.01) {
            return String.format(Locale.US, "$%.4f", usd);
        }
        if (usd < 10) {
            return String.format(Locale.US, "$%.2f", usd);
        }
        return String.format(Locale.US, "$%.1f", usd);
    }

    /** 百分比：0 到 100 的数，一位小数。 */
    static String percent(double v) {
        if (v <= 0) {
            return "0%";
        }
        if (v >= 100) {
            return "100%";
        }
        return String.format(Locale.US, "%.1f%%", v);
    }

    /**
     * 缓存命中率：cache_read / (input + cache_read + cache_write)。
     *
     * <p>跟 magpie 网页同一算法：供应商明确说没统计缓存的那些调用
     * （cache_unknown）要从分母里扣掉，否则一律读成「没命中」。
     */
    static double cacheHit(JSONObject totals, JSONObject block) {
        int read = num(totals, "cache_read");
        int write = num(totals, "cache_write");
        int input = num(totals, "input");
        long denom = (long) read + write + input;
        if (block != null) {
            denom -= num(block, "cache_unknown");
        }
        if (denom <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, read * 100.0 / denom));
    }

    /** 字节：438 MB / 66 MB / 1.5 GB（1000 进制，跟网页一致）。 */
    static String bytes(long n) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double v = n;
        int i = 0;
        while (v >= 1000 && i < u.length - 1) {
            v /= 1000;
            i++;
        }
        if (i == 0) {
            return n + " B";
        }
        return (v < 10 ? String.format(Locale.US, "%.1f", v)
                : String.valueOf(Math.round(v))) + " " + u[i];
    }

    /** 毫秒：320ms / 1.4s / 42s / 3m。 */
    static String ms(long v) {
        if (v <= 0) {
            return "—";
        }
        if (v < 1000) {
            return v + "ms";
        }
        double s = v / 1000.0;
        if (s < 10) {
            return String.format(Locale.US, "%.1fs", s);
        }
        if (s < 60) {
            return Math.round(s) + "s";
        }
        return Math.round(s / 60) + "m";
    }

    // ------------------------------------------------------------------
    // 时间
    // ------------------------------------------------------------------

    /** 解析 Go 的 RFC3339Nano：2026-11-14T15:04:05.999+08:00 / …Z。 */
    static long parseTime(String s) {
        if (s == null || s.length() < 19) {
            return 0;
        }
        String t = s.trim();
        int offsetSec = 0;
        Matcher m = TZ.matcher(t);
        if (m.find()) {
            offsetSec = (Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2))) * 60;
            t = t.substring(0, m.start());
        } else if (t.endsWith("Z")) {
            t = t.substring(0, t.length() - 1);
        }
        int dot = t.indexOf('.');
        if (dot > 0) {
            t = t.substring(0, dot);
        }
        try {
            synchronized (ISO) {
                Date d = ISO.parse(t);
                if (d == null) {
                    return 0;
                }
                // Go 的零值时间（0001-01-01T00:00:00Z）在界面上等于「没有」
                long ms = d.getTime() - offsetSec * 1000L;
                return ms < 0 ? 0 : ms;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** 「3h20m 后」：离某个时刻还有多久。 */
    static String until(String isoTime) {
        long at = parseTime(isoTime);
        if (at <= 0) {
            return "";
        }
        long ms = at - System.currentTimeMillis();
        if (ms <= 0) {
            return "已重置";
        }
        long min = ms / 60000L;
        if (min < 1) {
            return "不到 1 分钟后";
        }
        if (min < 60) {
            return min + " 分钟后";
        }
        long h = min / 60, m2 = min % 60;
        if (h < 24) {
            return m2 == 0 ? h + " 小时后" : h + "h" + m2 + "m 后";
        }
        long d = h / 24;
        return "约 " + d + " 天后";
    }

    /** 「14:32」：某条请求的本地时刻。 */
    static String clock(String isoTime) {
        long at = parseTime(isoTime);
        if (at <= 0) {
            return "";
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return f.format(new Date(at));
    }

    /** 「11-14 14:32」：带日期的时刻。 */
    static String stamp(String isoTime) {
        long at = parseTime(isoTime);
        if (at <= 0) {
            return "";
        }
        if (System.currentTimeMillis() - at < 24L * 3600 * 1000) {
            return clock(isoTime);
        }
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return f.format(new Date(at));
    }

    /** 周期名字。magpie 的周期值是 "today" / "7d" / "30d" / "all"，不是 day/week。 */
    static String periodName(String p) {
        switch (p) {
            case "today":
                return "今天";
            case "7d":
                return "近 7 天";
            case "30d":
                return "近 30 天";
            case "all":
                return "全部";
            default:
                return p;
        }
    }

    /** 供应商 id → 界面用的名字（agent 的 id 也走这张表兜底）。 */
    static String pretty(String id) {
        if (id == null || id.isEmpty()) {
            return "未知";
        }
        switch (id) {
            case "claude":
            case "claude-desktop":
                return "Claude";
            case "codex":
                return "Codex";
            case "gemini":
                return "Gemini";
            case "cursor":
                return "Cursor";
            case "windsurf":
                return "Windsurf";
            case "zed":
                return "Zed";
            case "cline":
                return "Cline";
            case "kilo":
                return "Kilo";
            case "opencode":
                return "OpenCode";
            case "droid":
                return "Droid";
            case "qoder":
                return "Qoder";
            case "trae":
                return "Trae";
            default:
                return id;
        }
    }
}
