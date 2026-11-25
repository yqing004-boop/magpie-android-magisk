package com.magpie.module;

import android.content.Context;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商图标：把 magpie 的 icon 名对到打包进 APK 的 PNG 上。
 *
 * <p>magpie 的图标是 {@code /icons/<name>.svg} 这类静态资源（<b>不是</b>
 * {@code /api/icons/}，那个只服务用户上传的图），Android 原生 ImageView
 * 又解析不了 SVG。所以常用厂商的 SVG 在构建期用 cairosvg 转成 144×144 的
 * PNG，放在 {@code res/drawable-nodpi/pv_*.png}，这里只做名字映射。
 *
 * <p>映射两级：
 * <ol>
 *   <li>先按 icon 名（{@code workbuddy-color}）直接查</li>
 *   <li>查不到就退回 provider id（{@code workbuddy}），再退回 preset id</li>
 * </ol>
 * 都没有就返回 0，调用方画一个首字母圆底。
 */
final class ProviderIcons {

    /** icon 名 → drawable 名。key 一律小写。 */
    private static final Map<String, String> BY_ICON = new HashMap<>();
    /** provider id / preset id → drawable 名。 */
    private static final Map<String, String> BY_ID = new HashMap<>();

    static {
        BY_ICON.put("workbuddy-color", "pv_workbuddy_color");
        BY_ICON.put("workbuddy", "pv_workbuddy_color");
        BY_ICON.put("antigravity-color", "pv_antigravity_color");
        BY_ICON.put("antigravity", "pv_antigravity_color");
        BY_ICON.put("trae", "pv_trae");
        BY_ICON.put("qwen-color", "pv_qwen_color");
        BY_ICON.put("qwen", "pv_qwen_color");
        BY_ICON.put("openai", "pv_openai");
        BY_ICON.put("anthropic", "pv_anthropic");
        BY_ICON.put("claude-color", "pv_claude_color");
        BY_ICON.put("claude", "pv_claude_color");
        BY_ICON.put("codex-color", "pv_codex_color");
        BY_ICON.put("gemini-color", "pv_gemini_color");
        BY_ICON.put("gemini", "pv_gemini_color");
        BY_ICON.put("deepseek-color", "pv_deepseek_color");
        BY_ICON.put("deepseek", "pv_deepseek_color");
        BY_ICON.put("zhipu-color", "pv_zhipu_color");
        BY_ICON.put("chatglm-color", "pv_chatglm_color");
        BY_ICON.put("moonshot", "pv_moonshot");
        BY_ICON.put("kimi", "pv_kimi");
        BY_ICON.put("openrouter", "pv_openrouter");
        BY_ICON.put("siliconcloud-color", "pv_siliconcloud_color");
        BY_ICON.put("volcengine-color", "pv_volcengine_color");
        BY_ICON.put("baidu-color", "pv_baidu_color");
        BY_ICON.put("tencent-color", "pv_tencent_color");
        BY_ICON.put("xai", "pv_xai");
        BY_ICON.put("mistral-color", "pv_mistral_color");
        BY_ICON.put("groq", "pv_groq");
        BY_ICON.put("together-color", "pv_together_color");
        BY_ICON.put("fireworks-color", "pv_fireworks_color");
        BY_ICON.put("cerebras", "pv_cerebras");
        BY_ICON.put("perplexity-color", "pv_perplexity_color");
        BY_ICON.put("copilot-color", "pv_copilot_color");
        BY_ICON.put("cursor", "pv_cursor");
        BY_ICON.put("windsurf", "pv_windsurf");
        BY_ICON.put("cline", "pv_cline");
        BY_ICON.put("opencode", "pv_opencode");
        BY_ICON.put("zed", "pv_zed");
        BY_ICON.put("ollama", "pv_ollama");
        BY_ICON.put("lmstudio", "pv_lmstudio");

        BY_ID.put("workbuddy", "pv_workbuddy_color");
        BY_ID.put("antigravity", "pv_antigravity_color");
        BY_ID.put("trae-cn", "pv_trae");
        BY_ID.put("trae", "pv_trae");
        BY_ID.put("qwenwork", "pv_qwen_color");
        BY_ID.put("qwen", "pv_qwen_color");
        BY_ID.put("marvis", "pv_tencent_color");
        BY_ID.put("raccoon", "pv_siliconcloud_color");
        BY_ID.put("tianshuapi", "pv_openai");
        BY_ID.put("kimi", "pv_kimi");
        BY_ID.put("moonshot", "pv_moonshot");
        BY_ID.put("deepseek", "pv_deepseek_color");
        BY_ID.put("zhipu", "pv_zhipu_color");
        BY_ID.put("zai", "pv_zhipu_color");
        BY_ID.put("openrouter", "pv_openrouter");
        BY_ID.put("siliconcloud", "pv_siliconcloud_color");
        BY_ID.put("volcengine", "pv_volcengine_color");
        BY_ID.put("openai", "pv_openai");
        BY_ID.put("anthropic", "pv_anthropic");
        BY_ID.put("claude", "pv_claude_color");
        BY_ID.put("claude-code", "pv_claude_color");
        BY_ID.put("codex", "pv_codex_color");
        BY_ID.put("gemini", "pv_gemini_color");
        BY_ID.put("cursor", "pv_cursor");
        BY_ID.put("windsurf", "pv_windsurf");
        BY_ID.put("cline", "pv_cline");
        BY_ID.put("opencode", "pv_opencode");
        BY_ID.put("zed", "pv_zed");
        BY_ID.put("ollama", "pv_ollama");
        BY_ID.put("lmstudio", "pv_lmstudio");
        BY_ID.put("xai", "pv_xai");
        BY_ID.put("mistral", "pv_mistral_color");
        BY_ID.put("groq", "pv_groq");
        BY_ID.put("together", "pv_together_color");
        BY_ID.put("fireworks", "pv_fireworks_color");
        BY_ID.put("cerebras", "pv_cerebras");
        BY_ID.put("perplexity", "pv_perplexity_color");
        BY_ID.put("copilot", "pv_copilot_color");
        BY_ID.put("baidu", "pv_baidu_color");
        BY_ID.put("tencent", "pv_tencent_color");
    }

    private ProviderIcons() {
    }

    /**
     * 一个供应商该用的图标资源 id；没有返回 0。
     *
     * @param icon   providerJSON.icon（magpie 的图标名）
     * @param id     provider id
     * @param preset preset id
     */
    static int of(Context ctx, String icon, String id, String preset) {
        String name = lookup(icon);
        if (name == null) {
            name = lookup(id);
        }
        if (name == null) {
            name = lookup(preset);
        }
        if (name == null) {
            return 0;
        }
        try {
            return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        } catch (Exception e) {
            return 0;
        }
    }

    static int ofProvider(Context ctx, JSONObject p) {
        return of(ctx,
                NativeData.str(p, "icon", ""),
                NativeData.str(p, "id", ""),
                NativeData.str(p, "preset", ""));
    }

    static int ofPreset(Context ctx, JSONObject pr) {
        return of(ctx,
                NativeData.str(pr, "icon", ""),
                NativeData.str(pr, "id", ""),
                NativeData.str(pr, "id", ""));
    }

    private static String lookup(String key) {
        if (key == null) {
            return null;
        }
        String k = key.trim().toLowerCase(Locale.US);
        if (k.isEmpty()) {
            return null;
        }
        String n = BY_ICON.get(k);
        if (n == null) {
            n = BY_ID.get(k);
        }
        return n;
    }

    /** 首字母兜底：没有图标时画一个字母圆底。 */
    static String initial(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        String t = name.trim();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return t.substring(0, 1).toUpperCase(Locale.US);
    }
}
