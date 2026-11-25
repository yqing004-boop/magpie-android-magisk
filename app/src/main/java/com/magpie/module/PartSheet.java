package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 上下文明细里点某一项（比如「工具 · terminal」）弹出的原文。
 *
 * <p>照 magpie 网页那张：标题 `工具 · terminal` + 右侧 `533 token`，
 * 下面一小段说明，然后是折叠的原文块（JSON 工具定义 / 系统提示词 / 会话内容）。
 *
 * <p>原文来自 {@code GET /api/gateway/call?route=<id>} 的 `requestBody`：
 * 整包能到 48 KB，`tools[]` 里就是每个工具的 `function` 定义。这里按 kind
 * 去请求体里捞出对应那一段，而不是把 48 KB 全塞进弹窗。
 */
final class PartSheet {

    private PartSheet() {
    }

    /**
     * @param kind  成分（system / tools / chat …）
     * @param item  条目名（agent 提示词 / terminal …）；空表示整个成分
     * @param tokens 这一项的 token 数
     */
    static void show(final Activity act, final Ui ui, final JSONObject route,
                     final String kind, final String item, final int tokens) {
        String title = CtxSheet.kindNameOf(kind);
        if (item != null && !item.isEmpty()) {
            title += " · " + item;
        }

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(18);
        root.setPadding(pad, ui.dp(6), pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题 + token 数
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(act);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(CtxSheet.kindColorOf(ui, kind));
        dot.setBackground(dd);
        head.addView(dot, new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)));

        TextView t = ui.text(title, 17, M3.ON_SURFACE, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        head.addView(t, tlp);

        head.addView(ui.text(tokens + " token", 12.5f, M3.ON_SURFACE_VARIANT, false));
        root.addView(head);

        TextView note = ui.text("即 magpie 发出的内容，开启脱敏时为脱敏后的内容。"
                + "仅在内存中保留最近 10 个请求。", 12, M3.ON_SURFACE_VARIANT, false);
        note.setLineSpacing(ui.dp(2), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = ui.dp(10);
        note.setLayoutParams(nlp);
        root.addView(note);

        // 原文容器（异步填）
        final LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = ui.dp(12);
        body.setLayoutParams(blp);

        TextView loading = ui.text("正在读取原文…", 12, M3.ON_SURFACE_VARIANT, false);
        loading.setPadding(0, ui.dp(16), 0, 0);
        body.addView(loading);
        root.addView(body);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
            }
        } catch (Exception ignored) {
        }
        dlg.show();

        // 后台去捞请求体
        final long routeId = route.optLong("id", 0);
        final String blockTitle = (item == null || item.isEmpty()) ? title : item;
        new Thread(() -> {
            String text = fetch(act, routeId, kind, item);
            act.runOnUiThread(() -> {
                body.removeAllViews();
                if (text == null) {
                    TextView t2 = ui.text("原文已经不在了：magpie 只在内存里保留最近 10 个请求。",
                            12, M3.ON_SURFACE_VARIANT, false);
                    t2.setLineSpacing(ui.dp(2), 1f);
                    body.addView(t2, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    return;
                }
                body.addView(codeBlock(act, ui, blockTitle, text));
            });
        }, "magpie-part").start();
    }

    /** 从 requestBody 里把对应那一段捞出来。 */
    private static String fetch(Activity act, long routeId, String kind, String item) {
        if (routeId <= 0) {
            return null;
        }
        MagpieApi.Result r = MagpieApi.gatewayCall(act, routeId);
        if (!r.ok()) {
            return null;
        }
        JSONObject o = r.json();
        if (o == null) {
            return null;
        }
        String request = NativeData.str(o, "requestBody", "");
        String response = NativeData.str(o, "responseBody", "");

        if (NativeData.flag(o, "requestTruncated") && request.isEmpty()) {
            return null;
        }
        if (request.isEmpty() && response.isEmpty()) {
            return null;
        }

        switch (kind) {
            case "system":
            case "chat":
            case "memory":
            case "files":
            case "results":
            case "skills": {
                // 这些都在 messages[] 里；整包 prettify 后按行过滤太脆，
                // 直接把 messages 段落摆出来更实在
                String messages = extractMessages(request);
                return messages != null ? messages : request;
            }
            case "tools": {
                String tools = extractTools(request, item);
                return tools != null ? tools : request;
            }
            default:
                return request;
        }
    }

    /** 请求体里 `messages`（或 `input`）那一段，pretty 后的原文。 */
    private static String extractMessages(String request) {
        try {
            JSONObject o = new JSONObject(request);
            JSONArray msgs = o.optJSONArray("messages");
            if (msgs == null) {
                msgs = o.optJSONArray("input");
            }
            if (msgs == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) {
                    sb.append(msgs.opt(i)).append("\n\n");
                    continue;
                }
                String role = NativeData.str(m, "role", "?");
                String content = contentOf(m);
                sb.append("── ").append(role).append(" ──\n");
                sb.append(content).append("\n\n");
            }
            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String contentOf(JSONObject m) {
        Object c = m.opt("content");
        if (c instanceof String) {
            return (String) c;
        }
        if (c instanceof JSONArray) {
            JSONArray arr = (JSONArray) c;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part != null) {
                    String t = NativeData.str(part, "text", "");
                    if (!t.isEmpty()) {
                        sb.append(t).append('\n');
                    }
                } else {
                    sb.append(arr.opt(i)).append('\n');
                }
            }
            return sb.toString();
        }
        return String.valueOf(c);
    }

    /** 请求体里 `tools[]` 中 name 匹配的那一个的 function 定义。 */
    private static String extractTools(String request, String item) {
        try {
            JSONObject o = new JSONObject(request);
            JSONArray tools = o.optJSONArray("tools");
            if (tools == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) {
                    continue;
                }
                String name = toolName(t);
                if (item != null && !item.isEmpty() && !item.equals(name)) {
                    continue;
                }
                JSONObject fn = t.optJSONObject("function");
                JSONObject show = fn != null ? fn : t;
                sb.append("工具定义 ").append(name).append("\n\n");
                sb.append(show.toString(2)).append("\n\n");
            }
            if (sb.length() == 0 && item != null && !item.isEmpty()) {
                // 名字对不上（MCP 常带前缀），退回把全部工具列出来
                return extractTools(request, "");
            }
            return sb.length() > 0 ? sb.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String toolName(JSONObject t) {
        JSONObject fn = t.optJSONObject("function");
        if (fn != null) {
            String n = NativeData.str(fn, "name", "");
            if (!n.isEmpty()) {
                return n;
            }
        }
        return NativeData.str(t, "name", "?");
    }

    /** 一块等宽的原文框。 */
    private static View codeBlock(Activity act, Ui ui, String title, String text) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER), 12,
                ui.col(M3.OUTLINE_VARIANT), 1);
        box.setBackground(bg);
        box.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));

        TextView h = ui.text(title, 12, M3.ON_SURFACE, true);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(h);

        TextView code = ui.mono(text, 10.5f, M3.ON_SURFACE_VARIANT);
        code.setLineSpacing(ui.dp(1), 1f);
        code.setTextIsSelectable(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = ui.dp(6);
        box.addView(code, clp);
        return box;
    }
}
