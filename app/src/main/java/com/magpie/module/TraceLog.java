package com.magpie.module;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「请求」区：实时 / 今天 / 昨天 / 更早的日期 标签栏 + 请求列表。
 *
 * <p>照网页来：
 * <pre>
 * 请求
 * [实时] [今天 686] [昨天 562] [11-12 804] [11-11 1209] …
 * ─────────────────────────────────
 * 15:57  minimax-m3.1-flash    4.4s
 *        天枢 · AstrBot · 1.2k token · $0.0004
 * </pre>
 *
 * <p>数据两路：
 * <ul>
 *   <li>「实时」——{@code /api/gateway/trace}（跟 {@link LivePanel} 同一个源，
 *       这里只用它的 routes 列成流水）</li>
 *   <li>「今天/昨天/更早」——{@code /api/gateway/history?day=YYYY-MM-DD}，
 *       带 {@code days[]} 给出每一天的请求数（就是标签上那些数字）</li>
 * </ul>
 */
final class TraceLog {

    private final NativeHome host;
    private final Ui ui;

    private final LinearLayout root;
    private final LinearLayout tabs;
    private final LinearLayout list;

    /** 每天的请求数（来自 history 的 days[]），按日期从新到旧。 */
    private final List<String[]> days = new ArrayList<>();  // [day, requests]
    /** 当前选中的标签：null=实时，否则是日期。 */
    private String selected;

    /** 实时的最近若干条。 */
    private JSONArray liveRoutes = new JSONArray();

    /**
     * 已合并过的实时条目，按 route id 去重。
     *
     * <p>{@link LivePanel} 的长轮询每 26 秒最多回一次，每次只带「变化的」
     * 路由；只靠最新一批的话，前一条会从列表里消失。这里按 id 攒着，
     * 最多留 10 条。
     */
    private final java.util.LinkedHashMap<Long, JSONObject> liveBuffer =
            new java.util.LinkedHashMap<>();

    /** 已经自动从「实时」切到最近有记录的一天，只切一次。 */
    private boolean fallbackDone;

    TraceLog(NativeHome host, Ui ui) {
        this.host = host;
        this.ui = ui;

        root = ui.column();
        // 自己在该方向上必须 wrap：父级是竖直 LinearLayout 时，
        // MATCH_PARENT 会让几个子项平分整屏高度，中间凭空多出空白
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = ui.row();
        head.setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(2));
        head.addView(ui.text("请求", 13, M3.ON_SURFACE, true), ui.weight(1));
        root.addView(head, wrapLp());

        // 两个子块都要 wrap_content：不给 LayoutParams 的话，垂直 LinearLayout
        // 会把它们按 MATCH_PARENT 平分高度，中间就凭空多出一大段空白
        tabs = ui.row();
        tabs.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(2));
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        list = ui.column();
        list.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), 0);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        paintTabs();
        showEmpty();
    }

    View view() {
        return root;
    }

    // ------------------------------------------------------------------

    /** 拉日期列表（一次），然后画标签。只留今天、昨天两个。 */
    void loadDays() {
        host.bg(() -> {
            MagpieApi.Result r = MagpieApi.get(host, "/api/gateway/history?day=", 8000, 20000);
            if (!r.ok()) {
                return;
            }
            JSONObject o = r.json();
            JSONArray arr = NativeData.arr(o, "days");
            // 只保留今天和昨天，更早的历史不要了
            java.text.SimpleDateFormat todayFmt = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US);
            long now = System.currentTimeMillis();
            long dayMs = 24L * 3600 * 1000;
            String today = todayFmt.format(new java.util.Date(now));
            String yesterday = todayFmt.format(new java.util.Date(now - dayMs));
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) {
                    continue;
                }
                String day = NativeData.str(d, "day", "");
                if (!day.equals(today) && !day.equals(yesterday)) {
                    continue;
                }
                out.add(new String[]{
                        day,
                        String.valueOf(NativeData.num(d, "requests"))});
                if (out.size() >= 2) {
                    break;
                }
            }
            host.post(() -> {
                days.clear();
                days.addAll(out);
                paintTabs();
                // 日期齐了才知道「实时」空不空：实时没数据、但今天有记录，
                // 就直接切到今天，别让用户对着空列表猜是不是坏了
                if (selected == null && liveRoutes.length() == 0 && !days.isEmpty()) {
                    selected = days.get(0)[0];
                    paintTabs();
                    loadDay(selected);
                }
            });
        });
    }

    /** 实时那一栏的数据（由 LivePanel 的轮询顺手带过来）。 */
    void setLive(JSONArray routes) {
        if (routes == null) {
            return;
        }
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            if (r == null) {
                continue;
            }
            liveBuffer.put(r.optLong("id", 0), r);
        }
        // 只留最近 10 条
        while (liveBuffer.size() > 10) {
            java.util.Iterator<Long> it = liveBuffer.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else {
                break;
            }
        }
        liveRoutes = new JSONArray();
        for (JSONObject r : liveBuffer.values()) {
            liveRoutes.put(r);
        }
        if (selected == null) {
            render();
        }
    }

    // ------------------------------------------------------------------

    private void paintTabs() {
        tabs.removeAllViews();
        // 实时
        addTab("实时", null);
        for (String[] d : days) {
            String label = dayLabel(d[0]);
            if (d[1] != null && !d[1].isEmpty() && !"0".equals(d[1])) {
                label += " " + d[1];
            }
            addTab(label, d[0]);
        }
    }

    private void addTab(String label, final String day) {
        boolean on = (day == null && selected == null)
                || (day != null && day.equals(selected));
        TextView t = ui.text(label, 11.5f,
                on ? M3.ON_SECONDARY_CONTAINER : M3.ON_SURFACE_VARIANT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(11), ui.dp(6), ui.dp(11), ui.dp(6));
        t.setBackground(on ? ui.round(ui.col(M3.SECONDARY_CONTAINER), 999) : null);
        t.setOnClickListener(v -> {
            if (day == null ? selected == null : day.equals(selected)) {
                return;
            }
            selected = day;
            paintTabs();
            if (day == null) {
                render();
            } else {
                loadDay(day);
            }
        });
        LinearLayout.LayoutParams lp = ui.wrap();
        lp.rightMargin = ui.dp(6);
        tabs.addView(t, lp);
    }

    /** 标签上的日期：今天 / 昨天 / 11-12。 */
    private String dayLabel(String day) {
        if (day == null || day.isEmpty()) {
            return "";
        }
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.US);
        try {
            long at = f.parse(day).getTime();
            long now = System.currentTimeMillis();
            long dayMs = 24L * 3600 * 1000;
            java.text.SimpleDateFormat today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US);
            String t0 = today.format(new java.util.Date(now));
            String t1 = today.format(new java.util.Date(now - dayMs));
            if (day.equals(t0)) {
                return "今天";
            }
            if (day.equals(t1)) {
                return "昨天";
            }
            return day.substring(5); // MM-DD
        } catch (Exception e) {
            return day;
        }
    }

    // ------------------------------------------------------------------

    private void render() {
        list.removeAllViews();
        if (liveRoutes == null || liveRoutes.length() == 0) {
            showEmpty();
            return;
        }
        // 最新的在上
        int n = Math.min(liveRoutes.length(), 10);
        for (int i = liveRoutes.length() - 1; i >= liveRoutes.length() - n; i--) {
            JSONObject r = liveRoutes.optJSONObject(i);
            if (r != null) {
                list.addView(row(r));
            }
        }
    }

    private void showEmpty() {
        list.removeAllViews();
        // 「实时」是网关内存里那几条：网关刚重启过就是空的，但历史里明明有
        // 几百条。这种情况下直接把视图切到最近有记录的那天，否则用户会以为
        // 整个请求区坏了。

        TextView t = ui.text("magpie 启动以来还没有请求", 12, M3.ON_SURFACE_VARIANT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(20), 0, ui.dp(8));
        list.addView(t, wrapLp());

        TextView hint = ui.text("agent 经 magpie 发出的每个请求都列在这里：由谁应答、"
                        + "为何选它，以及每次尝试。更早的请求按天保存，在上面的日期里查看。",
                10.5f, M3.ON_SURFACE_VARIANT, false);
        hint.setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(10));
        hint.setLineSpacing(ui.dp(2), 1f);
        list.addView(hint, wrapLp());
    }

    private void loadDay(final String day) {
        list.removeAllViews();
        TextView loading = ui.text("正在读取 " + day + " 的记录…", 11.5f,
                M3.ON_SURFACE_VARIANT, false);
        loading.setPadding(0, ui.dp(16), 0, 0);
        list.addView(loading);

        host.bg(() -> {
            MagpieApi.Result r = MagpieApi.get(host,
                    "/api/gateway/history?day=" + android.net.Uri.encode(day), 8000, 25000);
            if (!r.ok()) {
                host.post(() -> {
                    list.removeAllViews();
                    TextView t = ui.text("读不到这一天的记录：" + r.why(), 11.5f,
                            M3.ERROR, false);
                    t.setPadding(0, ui.dp(16), 0, 0);
                    list.addView(t);
                });
                return;
            }
            // 关键：17MB 的 history JSON 绝不能在主线程 new JSONObject ——
            // 会把 256MB 堆打满直接 OOM。在后台线程解析，只把最后 10 条
            // 小对象带回去，主线程只负责画 View。
            final JSONArray last;
            try {
                JSONObject o = new JSONObject(r.body);
                JSONArray routes = NativeData.arr(o, "routes");
                int n = Math.min(routes.length(), 10);
                last = new JSONArray();
                // History 返回 oldest first；从末尾取，最新 10 条置顶。
                for (int i = routes.length() - 1; i >= routes.length() - n; i--) {
                    JSONObject x = routes.optJSONObject(i);
                    if (x != null) {
                        last.put(x);
                    }
                }
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                host.post(() -> {
                    list.removeAllViews();
                    TextView e = ui.text("记录解析失败：" + msg, 11.5f, M3.ERROR, false);
                    e.setPadding(0, ui.dp(16), 0, 0);
                    list.addView(e);
                });
                return;
            }
            host.post(() -> {
                list.removeAllViews();
                if (last.length() == 0) {
                    TextView t = ui.text("这一天没有记录", 11.5f,
                            M3.ON_SURFACE_VARIANT, false);
                    t.setPadding(0, ui.dp(16), 0, 0);
                    list.addView(t);
                    return;
                }
                for (int i = 0; i < last.length(); i++) {
                    JSONObject x = last.optJSONObject(i);
                    if (x != null) {
                        list.addView(row(x));
                    }
                }
            });
        });
    }

    /** 一条请求：时刻 + 模型 + 耗时 / 下面一行 供应商 · Agent · token · 费用。 */
    private View row(JSONObject r) {
        LinearLayout box = ui.column();
        // 每条请求一张小卡：发光描边 + 悬浮阴影，跟其它页面统一
        GradientDrawable bg = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 16,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.glowCard(box, bg);
        box.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = ui.dp(8);
        box.setLayoutParams(blp);

        LinearLayout line = ui.row();
        String time = NativeData.stamp(NativeData.str(r, "time", ""));
        TextView t = ui.mono(time.isEmpty() ? "--:--" : time, 10, M3.ON_SURFACE_VARIANT);
        t.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(62),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(t);

        String model = NativeData.str(r, "model", "?");
        TextView m = ui.text(model, 11.5f, M3.ON_SURFACE, false);
        m.setSingleLine(true);
        m.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        line.addView(m, ui.weight(1));

        int status = NativeData.num(r, "status");
        long ms = (long) NativeData.dec(r, "ms");
        if (status >= 400) {
            line.addView(ui.pill(String.valueOf(status), M3.ERROR_CONTAINER,
                    M3.ON_ERROR_CONTAINER));
        } else if (ms > 0) {
            line.addView(ui.text(NativeData.ms(ms), 10.5f, M3.ON_SURFACE_VARIANT, false));
        }
        box.addView(line);

        // 点一行看上下文窗口（网页上也是点开的）
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> {
            if (box.getTag() instanceof JSONObject) {
                CtxSheet.show((android.app.Activity) host, ui, (JSONObject) box.getTag());
            }
        });
        box.setTag(r);

        // 第二行：供应商 · agent · token · 费用
        StringBuilder sb = new StringBuilder();
        String provider = NativeData.str(r, "provider", "");
        if (!provider.isEmpty()) {
            sb.append(NativeData.pretty(provider));
        }
        String agent = NativeData.str(r, "agent", "");
        if (!agent.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(agent);
        }
        int tokens = NativeData.num(r, "tokens");
        if (tokens > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(NativeData.tokens(tokens)).append(" token");
        }
        double cost = NativeData.dec(r, "cost");
        if (cost > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(NativeData.cost(cost));
        }
        if (sb.length() > 0) {
            TextView s = ui.text(sb.toString(), 10, M3.ON_SURFACE_VARIANT, false);
            LinearLayout.LayoutParams slp = ui.matchWrap(2);
            slp.leftMargin = ui.dp(50);
            s.setLayoutParams(slp);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(s);
        }
        return box;
    }
    /** 垂直 LinearLayout 的子项必须显式给 WRAP_CONTENT，否则会和别的子项平分整屏高度。 */
    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

}
