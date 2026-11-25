package com.magpie.module;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * magpie 那些英文词的中文名。
 *
 * <p>额度窗口的名字（{@code Credits}、{@code Extra credits}、{@code Total credits}…）、
 * 单位（{@code credits}、{@code tokens}）都是 magpie 从供应商那边原样抄过来的
 * 英文串，接口不会翻译。网页版靠 {@code i18n.js} 查表译，这里抄同一套译法，
 * 保证同一个词在 App 里和网页里看起来一致。
 *
 * <p>查不到就原样返回 —— 供应商自定义的窗口名（{@code deepseek-v4.1-flash tokens (est.)}）
 * 本来就不该硬译。
 */
final class Zh {

    private static final Map<String, String> WORDS = new HashMap<>();
    /** 名字里带这些词的整句，按模式译（大小写不敏感、做小写匹配）。 */
    private static final Map<String, String> PATTERNS = new LinkedHashMap<>();

    static {
        // ---- 额度窗口名（对齐 magpie 的 i18n.js）----
        WORDS.put("credits", "积分");
        WORDS.put("credit", "积分");
        WORDS.put("extra credits", "额外积分");
        WORDS.put("total credits", "总积分");
        WORDS.put("balance", "余额");
        WORDS.put("tokens", "令牌用量");
        WORDS.put("requests", "请求数");
        WORDS.put("messages", "消息数");
        WORDS.put("prompts", "提问数");
        WORDS.put("5h limit", "5 小时额度");
        WORDS.put("5 hour limit", "5 小时额度");
        WORDS.put("weekly limit", "每周额度");
        WORDS.put("week limit", "每周额度");
        WORDS.put("daily limit", "每日额度");
        WORDS.put("day limit", "每日额度");
        WORDS.put("monthly limit", "每月额度");
        WORDS.put("month limit", "每月额度");
        WORDS.put("hourly limit", "每小时额度");
        WORDS.put("max", "上限");
        WORDS.put("used", "已用");
        WORDS.put("remaining", "剩余");
        WORDS.put("free plan", "免费套餐");
        WORDS.put("pro plan", "专业套餐");
        WORDS.put("team plan", "团队套餐");

        // ---- 单位 ----
        WORDS.put("credits", "积分");
        WORDS.put("credit", "积分");
        WORDS.put("token", "令牌");
        WORDS.put("tokens", "令牌用量");

        // ---- 常见供应商额度窗口里带修饰的 ----
        PATTERNS.put("total credits", "总积分");
        PATTERNS.put("extra credits", "额外积分");
        PATTERNS.put("tokens (est.)", "令牌（估）");
        PATTERNS.put("(est.)", "（估）");
    }

    private Zh() {
    }

    // ------------------------------------------------------------------
    // 查表
    // ------------------------------------------------------------------

    /**
     * 一个词（窗口名、单位、套餐名）的中文。
     *
     * <p>三档：整串命中 → 小写命中 → 括号里的 (est.) 之类做局部替换 →
     * 都没命中就原样返回。
     */
    static String of(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return t;
        }
        String hit = WORDS.get(t);
        if (hit != null) {
            return hit;
        }
        String lower = t.toLowerCase(Locale.US);
        hit = WORDS.get(lower);
        if (hit != null) {
            return hit;
        }
        // 部分替换：把能认的片段换掉，剩下的保留（"deepseek-v4.1-flash tokens (est.)"）
        String out = t;
        boolean changed = false;
        for (Map.Entry<String, String> e : PATTERNS.entrySet()) {
            if (out.toLowerCase(Locale.US).contains(e.getKey())) {
                out = replaceIgnoreCase(out, e.getKey(), e.getValue());
                changed = true;
            }
        }
        return changed ? out : t;
    }

    private static String replaceIgnoreCase(String src, String target, String with) {
        String lowerSrc = src.toLowerCase(Locale.US);
        String lowerTarget = target.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int at = lowerSrc.indexOf(lowerTarget, i);
            if (at < 0) {
                sb.append(src, i, src.length());
                break;
            }
            sb.append(src, i, at).append(with);
            i = at + lowerTarget.length();
        }
        return sb.toString();
    }
}
