package com.magpie.module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一家供应商下多个账号的额度汇总。
 *
 * <p>账号页和概览页都要在供应商那一行写「这家一共多少」：余额加总、积分加总、
 * 或者所有额度窗口里最紧的那个还剩多少。算法统一放这里，两个页面只负责画。
 *
 * <p><b>余额不能无脑相加</b>：天枢 5 个账号的 {@code balance} 都是 {@code $4.18}，
 * 它们是同一个账户的 5 把 key —— JSON 里的 {@code sharedBalance} 就写着共享的那个
 * 账户名。按它去重，5 个账号才只算一次 $4.18。
 */
final class QuotaAgg {

    private QuotaAgg() {
    }

    // ------------------------------------------------------------------
    // 余额
    // ------------------------------------------------------------------

    /** 一条余额：数值 + 单位（"$" / "¥" / "credits"）。 */
    static final class Money {
        String unit = "";
        double value;
        boolean ok;
    }

    /**
     * 解析 magpie 的 balance 字符串。
     *
     * <p>见过的写法：
     * <ul>
     *   <li>{@code "$4.18"}、{@code "¥30.00"} —— 货币符号在前</li>
     *   <li>{@code "366.08 credits (monthly 66.08 · long-term 300)"} —— 数字在前，
     *       单位是词，括号里是拆分（不进汇总，免得重复计算）</li>
     * </ul>
     */
    static Money parseBalance(String s) {
        Money m = new Money();
        if (s == null) {
            return m;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return m;
        }
        // 货币符号在前
        if (t.startsWith("$") || t.startsWith("¥") || t.startsWith("￥") || t.startsWith("€")) {
            m.unit = t.substring(0, 1);
            int end = 1;
            while (end < t.length() && (Character.isDigit(t.charAt(end))
                    || t.charAt(end) == '.' || t.charAt(end) == ',')) {
                end++;
            }
            m.value = num(t.substring(1, end));
            m.ok = end > 1;
            return m;
        }
        // 数字在前，单位是后面的第一个词
        int i = 0;
        while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.')) {
            i++;
        }
        if (i == 0) {
            return m;
        }
        m.value = num(t.substring(0, i));
        String rest = t.substring(i).trim();
        int j = 0;
        while (j < rest.length() && !Character.isWhitespace(rest.charAt(j))
                && rest.charAt(j) != '(') {
            j++;
        }
        m.unit = rest.substring(0, j);
        m.ok = true;
        return m;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 余额汇总。同一个 {@code sharedBalance} 只算一次。
     *
     * @return "余额 $4.18" / "余额 $12.30 · 366 credits"，没有余额时返回 ""
     */
    static String balanceLine(List<JSONObject> accounts) {
        // unit → (共享键 → 金额)。共享键空表示各算各的。
        Map<String, Map<String, Double>> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (JSONObject q : accounts) {
            String text = NativeData.str(q, "balance", "");
            Money m = parseBalance(text);
            if (!m.ok) {
                continue;
            }
            String shared = NativeData.str(q, "sharedBalance", "");
            String dedupeKey = shared.isEmpty()
                    ? "self:" + NativeData.str(q, "user", "") + "|"
                    + NativeData.str(q, "alias", "")
                    : "shared:" + shared;
            Map<String, Double> bucket = sums.get(m.unit);
            if (bucket == null) {
                bucket = new LinkedHashMap<>();
                sums.put(m.unit, bucket);
            }
            if (!bucket.containsKey(dedupeKey)) {
                bucket.put(dedupeKey, m.value);
                counts.put(m.unit, (counts.containsKey(m.unit) ? counts.get(m.unit) : 0) + 1);
            }
        }

        if (sums.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("余额 ");
        boolean first = true;
        for (Map.Entry<String, Map<String, Double>> e : sums.entrySet()) {
            double total = 0;
            for (double v : e.getValue().values()) {
                total += v;
            }
            if (!first) {
                sb.append(" · ");
            }
            first = false;
            String unit = e.getKey();
            if ("$".equals(unit) || "¥".equals(unit) || "￥".equals(unit) || "€".equals(unit)) {
                sb.append(unit).append(brief(total));
            } else {
                sb.append(brief(total));
                if (!unit.isEmpty()) {
                    sb.append(' ').append(Zh.of(unit));
                }
            }
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
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

    /** 大数缩写：10000000 → 10M。供应商行只有一行，不缩就会被截断。 */
    private static String brief(double v) {
        if (Math.abs(v) < 100_000) {
            return fmt(v);
        }
        return NativeData.tokens(Math.round(v));
    }

    // ------------------------------------------------------------------
    // 额度窗口
    // ------------------------------------------------------------------

    /** 一个窗口跨账号累加后的结果。 */
    private static final class Window {
        String name = "";
        double amount;
        double limit;
        String unit = "";
        int accounts;
        boolean unlimited;
    }

    /**
     * 按窗口名把各账号的窗口累加起来。
     *
     * <p>WorkBuddy 两个账号各有「Credits 500/500」「Total credits 1587.96/2942」，
     * 累加后是 5342 的总量、已用 1896 —— 这才是「这家一共多少」。
     */
    private static List<Window> windows(List<JSONObject> accounts) {
        Map<String, Window> byName = new LinkedHashMap<>();
        for (JSONObject q : accounts) {
            if (!NativeData.str(q, "error", "").isEmpty()) {
                continue;
            }
            JSONArray arr = NativeData.arr(q, "windows");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = NativeData.at(arr, i);
                if (w == null) {
                    continue;
                }
                String name = NativeData.str(w, "name", "");
                if (name.isEmpty()) {
                    continue;
                }
                Window agg = byName.get(name);
                if (agg == null) {
                    agg = new Window();
                    agg.name = name;
                    byName.put(name, agg);
                }
                agg.accounts++;
                agg.unit = NativeData.str(w, "unit", agg.unit);
                if (NativeData.flag(w, "unlimited")) {
                    agg.unlimited = true;
                }
                if (NativeData.flag(w, "text")) {
                    // 纯文本窗口（供应商自己给的计数）累加没有意义，只留一个
                    agg.accounts = Math.max(agg.accounts, 1);
                    continue;
                }
                double limit = NativeData.dec(w, "limit");
                double amount = NativeData.dec(w, "amount");
                if (limit <= 0) {
                    // 百分比窗口：用 used 折出一个假的总量，100 份
                    limit = 100;
                    amount = Math.max(0, Math.min(100, NativeData.dec(w, "used")));
                }
                agg.limit += limit;
                agg.amount += amount;
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** 挑一个窗口当「这家一共多少」的代表：优先叫 Total 的，其次总量最大的。 */
    private static Window headline(List<Window> windows) {
        if (windows == null || windows.isEmpty()) {
            return null;
        }
        for (Window w : windows) {
            if (w.limit > 0 && w.name.toLowerCase(Locale.US).contains("total")) {
                return w;
            }
        }
        Window best = null;
        for (Window w : windows) {
            if (w.limit <= 0) {
                continue;
            }
            if (best == null || w.limit > best.limit) {
                best = w;
            }
        }
        return best;
    }

    /** 所有窗口里最紧的那个还剩多少（0..100）。 */
    static double tightest(List<JSONObject> accounts) {
        double worst = -1;
        for (JSONObject q : accounts) {
            if (!NativeData.str(q, "error", "").isEmpty()) {
                continue;
            }
            JSONArray arr = NativeData.arr(q, "windows");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = NativeData.at(arr, i);
                if (w == null || NativeData.flag(w, "unlimited")) {
                    continue;
                }
                double left = 100 - Math.max(0, Math.min(100, NativeData.dec(w, "used")));
                if (worst < 0 || left < worst) {
                    worst = left;
                }
            }
        }
        return worst;
    }

    /**
     * 这家聚合后的总额度，已用占比（0..100）。
     *
     * <p>进度条要的是「总额度用了多少」，不是「单个账号剩多少」。WorkBuddy
     * 两个账号各自 500/500 和 3031/6142，聚合后是 3031/6142（约 49% 已用），
     * 进度条就该画 49%，而不是拿某个用光的账号画成 100% 全红。
     *
     * @return 已用百分比；没有可聚合的额度窗口时返回 -1
     */
    static double headlineUsed(List<JSONObject> accounts) {
        Window h = headline(windows(accounts));
        if (h == null || h.limit <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(100, h.amount * 100.0 / h.limit));
    }

    /** 聚合后的总额度，剩余占比（0..100）；没有时返回 -1。 */
    static double headlineLeft(List<JSONObject> accounts) {
        double used = headlineUsed(accounts);
        return used < 0 ? -1 : 100 - used;
    }

    /**
     * 供应商那一行的一句话汇总。
     *
     * <p>顺序：余额 → 额度窗口总量 → 最紧的那个百分比 → 读不到。
     */
    static String totalLine(List<JSONObject> accounts) {
        StringBuilder sb = new StringBuilder();
        sb.append(accounts.size()).append(" 个账号");

        String balance = balanceLine(accounts);
        if (!balance.isEmpty()) {
            sb.append(" · ").append(balance);
        } else {
            Window h = headline(windows(accounts));
            if (h != null && h.limit > 0) {
                sb.append(" · ").append(Zh.of(h.name)).append(' ')
                        .append(brief(h.limit - h.amount)).append(" / ")
                        .append(brief(h.limit));
                if (!h.unit.isEmpty()) {
                    sb.append(' ').append(Zh.of(h.unit));
                }
            } else {
                double tight = tightest(accounts);
                if (tight >= 0) {
                    sb.append(" · 最紧 ").append(NativeData.percent(tight)).append(" 剩余");
                }
            }
        }

        int errors = 0;
        for (JSONObject q : accounts) {
            if (!NativeData.str(q, "error", "").isEmpty()) {
                errors++;
            }
        }
        if (errors > 0) {
            sb.append(" · ").append(errors).append(" 个读不到");
        }
        return sb.toString();
    }
}
