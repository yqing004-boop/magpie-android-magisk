package com.magpie.module;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「实时 · 网关正在做的决定」：magpie 网页路由页最上面那块。
 *
 * <p>数据来自 {@code GET /api/gateway/trace?after=<seq>&wait=1} 长轮询，
 * 见 {@link TraceSource}。
 */
final class LivePanel {

    private final NativeHome host;
    private final Ui ui;

    private final LinearLayout root;
    private final TextView stats;
    private final LinearLayout body;

    private final TraceSource source = new TraceSource();

    /** 每次 trace 回来顺手转一份给别人（请求记录区要用同一批 routes）。 */
    private java.util.function.Consumer<JSONArray> forward;

    LivePanel(NativeHome host, Ui ui) {
        this.host = host;
        this.ui = ui;

        root = ui.column();
        // 自己在该方向上必须 wrap：父级是竖直 LinearLayout 时，
        // MATCH_PARENT 会让几个子项平分整屏高度，中间凭空多出空白
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = ui.row();
        head.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(2));
        head.addView(ui.text("实时 · 网关正在做的决定", 13, M3.ON_SURFACE, true),
                ui.weight(1));
        stats = ui.text("请求 0 · 改道 0 · 错误 0", 10.5f, M3.ON_SURFACE_VARIANT, false);
        stats.setSingleLine(true);
        head.addView(stats);
        root.addView(head, wrapLp());

        TextView hint = ui.text("等待请求；经 agent 发出请求后，这里实时播放："
                        + "路由把谁排第一及原因、每次尝试和各自的回应。",
                11, M3.ON_SURFACE_VARIANT, false);
        hint.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);
        hint.setLineSpacing(ui.dp(2), 1f);
        root.addView(hint, wrapLp());

        body = ui.column();
        body.setPadding(0, ui.dp(10), 0, 0);
        root.addView(body, wrapLp());

        renderEmpty();
    }

    View view() {
        return root;
    }

    void setForward(java.util.function.Consumer<JSONArray> f) {
        this.forward = f;
    }

    void start() {
        source.start(host, new TraceSource.Sink() {
            @Override
            public void onTrace(JSONArray routes, JSONObject totals) {
                host.post(() -> {
                    apply(routes, totals);
                    if (forward != null && routes != null && routes.length() > 0) {
                        forward.accept(routes);
                    }
                });
            }

            @Override
            public void onError(String why) {
                // 网关没起来就保留上一帧，不刷屏
            }
        });
    }

    void stop() {
        source.stop();
    }

    private void apply(JSONArray routes, JSONObject totals) {
        if (totals != null) {
            stats.setText("请求 " + NativeData.num(totals, "requests")
                    + " · 改道 " + NativeData.num(totals, "rerouted")
                    + " · 错误 " + NativeData.num(totals, "errors"));
        }

        JSONObject newest = null;
        if (routes != null) {
            long best = -1;
            for (int i = 0; i < routes.length(); i++) {
                JSONObject r = routes.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                long id = r.optLong("id", 0);
                if (id >= best) {
                    best = id;
                    newest = r;
                }
            }
        }
        if (newest == null) {
            renderEmpty();
            return;
        }
        renderRoute(newest);
    }

    private void renderEmpty() {
        body.removeAllViews();
        body.addView(nodes(null, null, null));
        TextView t = ui.text("还没有请求", 12, M3.ON_SURFACE_VARIANT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(16), 0, ui.dp(10));
        body.addView(t);
    }

    private void renderRoute(JSONObject route) {
        body.removeAllViews();

        String agent = NativeData.str(route, "agent", "");
        String model = NativeData.str(route, "model", "");
        JSONObject group = NativeData.obj(route, "group");

        String answered = null;
        JSONArray tries = NativeData.arr(route, "tries");
        if (tries.length() > 0) {
            JSONObject last = tries.optJSONObject(tries.length() - 1);
            if (last != null) {
                answered = NativeData.str(last, "id", null);
            }
        }

        boolean done = NativeData.flag(route, "done");
        int status = NativeData.num(route, "status");

        body.addView(nodes(agent, model, group));
        body.addView(connector(answered != null, done, status));
        body.addView(orderList(route, answered, tries, done, status));
        body.addView(excluded(route));
    }

    private View nodes(String agent, String model, JSONObject group) {
        LinearLayout row = ui.row();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(16), 0, ui.dp(16), 0);
        row.setLayoutParams(lp);

        String who = (agent == null || agent.isEmpty()) ? "等待请求" : NativeData.pretty(agent);
        String sub = model == null || model.isEmpty() ? null : model;
        row.addView(box(who, "你的 Agent", sub, false, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = ui.text("\u2192", 14, M3.OUTLINE, false);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ui.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(arrow, alp);

        String grpName = group == null ? "" : NativeData.str(group, "ref",
                NativeData.str(group, "name", ""));
        row.addView(box(Prefs.browserHost(host) + ":" + GatewayService.gatewayPort(host),
                        "magpie", grpName.isEmpty() ? null : grpName, true, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout box(String main, String header, String sub,
                             boolean live, boolean idle) {
        LinearLayout box = ui.column();
        GradientDrawable bg = ui.roundStroke(
                ui.col(live ? M3.SURFACE_CONTAINER : M3.SURFACE_CONTAINER_LOW), 14,
                ui.col(live ? M3.ON_SURFACE : M3.OUTLINE_VARIANT), live ? 1.8f : 1f);
        box.setBackground(bg);
        box.setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9));
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout head = ui.row();
        if (live) {
            head.addView(dot(M3.TERTIARY, 7));
        } else if (idle) {
            head.addView(dot(M3.ERROR, 7));
        }
        TextView h = ui.text(header, 10, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams hlp = ui.wrap();
        if (live || idle) {
            hlp.leftMargin = ui.dp(5);
        }
        head.addView(h, hlp);
        box.addView(head);

        TextView m = ui.text(main, 12, M3.ON_SURFACE, true);
        m.setGravity(Gravity.CENTER);
        m.setSingleLine(true);
        m.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        m.setLayoutParams(ui.matchWrap(2));
        box.addView(m);

        if (sub != null && !sub.isEmpty()) {
            TextView s = ui.mono(sub, 9, M3.ON_SURFACE_VARIANT);
            s.setGravity(Gravity.CENTER);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            box.addView(s);
        }
        return box;
    }

    private View dot(int role, int sizeDp) {
        View v = new View(host);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ui.col(role));
        v.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp));
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    private View connector(boolean live, boolean done, int status) {
        RouteLines v = new RouteLines(host, ui);
        v.setLive(live, done && status >= 400);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(24));
        lp.setMargins(ui.dp(16), 0, ui.dp(16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View orderList(JSONObject route, String answeredId,
                           JSONArray tries, boolean done, int status) {
        LinearLayout list = ui.column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(14), 0, ui.dp(14), 0);
        list.setLayoutParams(lp);

        JSONArray order = NativeData.arr(route, "order");
        if (order.length() == 0) {
            return list;
        }

        // 同一个账号会在 order 里出现多次（它服务的每个模型各一条）。网页上
        // 是「一个账号一张卡、右边的胶囊列它这次能服务的模型」，所以这里按
        // id 合并：同一个 id 只画一张卡，模型名拼在右边。
        java.util.LinkedHashMap<String, java.util.List<JSONObject>> byId =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < order.length(); i++) {
            JSONObject w = order.optJSONObject(i);
            if (w == null) {
                continue;
            }
            String id = NativeData.str(w, "id", "");
            java.util.List<JSONObject> group = byId.get(id);
            if (group == null) {
                group = new java.util.ArrayList<>();
                byId.put(id, group);
            }
            group.add(w);
        }

        boolean marked = false;
        int shown = 0;
        for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : byId.entrySet()) {
            if (shown >= 12) {
                break;
            }
            boolean picked = !marked && e.getKey().equals(answeredId);
            if (picked) {
                marked = true;
            }
            list.addView(card(e.getValue(), picked, tries, done, status, route));
            shown++;
        }
        int rest = byId.size() - shown;
        if (rest > 0) {
            TextView more = ui.text("还有 " + rest + " 个候选账号",
                    10.5f, M3.ON_SURFACE_VARIANT, false);
            more.setPadding(0, ui.dp(2), 0, ui.dp(6));
            list.addView(more);
        }
        return list;
    }

    /**
     * 一个账号一张卡。
     *
     * @param same 同一个账号在 order 里的若干条（它服务的每个模型一条），
     *             第一条当主，其余模型名拼在右边
     */
    private View card(java.util.List<JSONObject> same, boolean picked, JSONArray tries,
                      boolean done, int status, JSONObject routeOfCard) {
        JSONObject w = same.get(0);
        LinearLayout card = ui.column();
        GradientDrawable bg = ui.roundStroke(
                ui.col(picked ? M3.SURFACE : M3.SURFACE_CONTAINER_LOW), 12,
                ui.col(picked ? M3.ON_SURFACE : M3.OUTLINE_VARIANT), picked ? 2f : 1f);
        card.setBackground(bg);
        card.setPadding(ui.dp(11), ui.dp(8), ui.dp(11), ui.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(6);
        card.setLayoutParams(lp);

        LinearLayout row = ui.row();

        View circle = new View(host);
        GradientDrawable cd = new GradientDrawable();
        cd.setShape(GradientDrawable.OVAL);
        cd.setColor(ui.col(picked ? M3.ON_SURFACE : M3.SURFACE_CONTAINER_HIGHEST));
        circle.setBackground(cd);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        clp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(circle, clp);

        int icon = ProviderIcons.of(host, NativeData.str(w, "icon", ""),
                NativeData.str(w, "provider", ""), NativeData.str(w, "preset", ""));
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(host);
            iv.setImageResource(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
            ilp.leftMargin = ui.dp(6);
            row.addView(iv, ilp);
        }

        String who = NativeData.str(w, "who", NativeData.str(w, "name", "?"));
        if (who.length() > 20) {
            who = who.substring(0, 20) + "…";
        }
        // 名字按内容宽（最多 130dp），模型串吃剩下的并省略 —— 反过来的话
        // 一长串模型名会把账号名挤成 0 宽，玩家直接看不见是谁
        TextView name = ui.text(who, 12, M3.ON_SURFACE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = ui.dp(6);
        name.setMaxWidth(ui.dp(130));
        row.addView(name, nlp);

        String plan = Zh.of(NativeData.str(w, "plan", ""));
        if (plan.isEmpty() && "account".equals(NativeData.str(w, "kind", ""))) {
            plan = "Free";
        }
        if (!plan.isEmpty()) {
            TextView p = ui.text(plan, 9, M3.ON_SURFACE_VARIANT, false);
            LinearLayout.LayoutParams plp = ui.wrap();
            plp.leftMargin = ui.dp(4);
            row.addView(p, plp);
        }

        // 模型：同一账号的多个模型拼成一行（网页上也是这么紧凑）
        StringBuilder models = new StringBuilder();
        for (JSONObject x : same) {
            String mm = NativeData.str(x, "model", "");
            if (mm.isEmpty()) {
                continue;
            }
            if (models.length() > 0) {
                models.append(" · ");
            }
            models.append(shortModel(mm));
        }
        if (models.length() > 0) {
            TextView m = ui.mono(models.toString(), 9, M3.ON_SURFACE_VARIANT);
            m.setSingleLine(true);
            m.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = ui.dp(6);
            row.addView(m, mlp);
        }
        card.addView(row);

        double used = NativeData.dec(w, "used");
        double amount = NativeData.dec(w, "amount");
        double limit = NativeData.dec(w, "limit");
        String unit = Zh.of(NativeData.str(w, "unit", ""));
        StringBuilder sb = new StringBuilder();
        if (limit > 0) {
            sb.append(brief(amount)).append(" / ").append(brief(limit));
            if (!unit.isEmpty()) {
                sb.append(' ').append(unit);
            }
            sb.append(" · ");
        }
        sb.append("已用 ").append(NativeData.percent(used));

        if (used >= 90) {
            sb.append(" · 留到最后");
        } else if (isPaced(w)) {
            sb.append(" · 先用积分更早到期的账号");
        }
        JSONArray renews = NativeData.arr(w, "renews");
        if (renews.length() > 0) {
            String u = NativeData.until(renews.optString(0, ""));
            if (!u.isEmpty()) {
                sb.append(" · ").append(u).append("重置");
            }
        }
        JSONObject rest = NativeData.obj(w, "rest");
        if (rest != null) {
            String u = NativeData.until(NativeData.str(rest, "until", ""));
            sb.append(" · 暂停使用");
            if (!u.isEmpty()) {
                sb.append(' ').append(u).append("恢复");
            }
        }
        if (NativeData.flag(w, "fallback")) {
            sb.append(" · 备用");
        }

        TextView info = ui.text(sb.toString(), 10.5f,
                used >= 90 ? M3.ERROR : M3.ON_SURFACE_VARIANT, false);
        info.setLineSpacing(ui.dp(1), 1f);
        info.setLayoutParams(ui.matchWrap(4));
        card.addView(info);

        card.addView(ui.quotaBar(Math.max(0, Math.min(100, used)),
                100 - Math.max(0, Math.min(100, used))));

        if (picked) {
            card.addView(stateLine(w, tries, done, status));
        }

        // 点开看这条请求的上下文窗口（跟网页上点一行一样）。
        // 用**这张卡自己**那条 route，而不是全局的「最新一条」——
        // 两个请求连着来时，点旧卡会打开新请求的上下文。
        final JSONObject mine = routeOfCard;
        ui.clickable(card, bg);
        card.setOnClickListener(v -> {
            if (mine != null) {
                CtxSheet.show((android.app.Activity) host, ui, mine);
            }
        });
        return card;
    }

    private View stateLine(JSONObject w, JSONArray tries, boolean done, int status) {
        String text;
        int role = M3.PRIMARY;

        JSONObject t = null;
        for (int i = tries.length() - 1; i >= 0; i--) {
            JSONObject x = tries.optJSONObject(i);
            if (x != null && NativeData.str(x, "id", "").equals(NativeData.str(w, "id", ""))) {
                t = x;
                break;
            }
        }

        if (t == null) {
            text = "这个请求没有经过它";
            role = M3.ON_SURFACE_VARIANT;
        } else if (!NativeData.flag(t, "done")) {
            long ms = System.currentTimeMillis()
                    - NativeData.parseTime(NativeData.str(t, "start", ""));
            text = "回答中… " + Math.max(1, ms / 1000) + "s";
        } else {
            int st = NativeData.num(t, "status");
            long ms = (long) NativeData.dec(t, "ms");
            if (st >= 400) {
                text = "失败（" + st + "）";
                role = M3.ERROR;
            } else {
                text = "回答了这个请求" + (ms > 0 ? " · " + NativeData.ms(ms) : "");
            }
        }

        TextView v = ui.textColor(text, 11, ui.col(role), true);
        v.setLayoutParams(ui.matchWrap(5));
        return v;
    }

    private View excluded(JSONObject route) {
        JSONArray left = NativeData.arr(route, "left");
        LinearLayout box = ui.column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(ui.dp(16), ui.dp(2), ui.dp(16), 0);
        box.setLayoutParams(lp);
        if (left.length() == 0) {
            return box;
        }

        final LinearLayout detail = ui.column();
        final TextView arrow = ui.text("\u25B8", 10, M3.ON_SURFACE_VARIANT, false);
        LinearLayout head = ui.row();
        TextView label = ui.text(left.length() + " 个被排除", 10.5f,
                M3.ON_SURFACE_VARIANT, false);
        head.addView(label, ui.weight(1));
        head.addView(arrow);
        head.setClickable(true);
        head.setOnClickListener(v -> {
            boolean open = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "\u25BE" : "\u25B8");
        });
        box.addView(head);

        for (int i = 0; i < left.length(); i++) {
            JSONObject w = left.optJSONObject(i);
            if (w == null) {
                continue;
            }
            String who = NativeData.str(w, "who", NativeData.str(w, "name", "?"));
            StringBuilder why = new StringBuilder();
            if (NativeData.flag(w, "barred")) {
                why.append("不服务这个模型");
            }
            if (NativeData.flag(w, "unlisted")) {
                if (why.length() > 0) {
                    why.append(" · ");
                }
                why.append("仅供路由组");
            }
            JSONObject rest = NativeData.obj(w, "rest");
            if (rest != null) {
                if (why.length() > 0) {
                    why.append(" · ");
                }
                why.append("暂停使用");
            }
            if (why.length() == 0) {
                why.append("不在候选里");
            }
            TextView t = ui.text("· " + who + "：" + why, 10,
                    M3.ON_SURFACE_VARIANT, false);
            t.setPadding(ui.dp(6), ui.dp(2), 0, 0);
            detail.addView(t);
        }
        detail.setVisibility(View.GONE);
        box.addView(detail);
        return box;
    }

    private boolean isPaced(JSONObject w) {
        return "pace".equals(NativeData.str(w, "routing", ""));
    }

    private String shortModel(String m) {
        int slash = m.indexOf('/');
        return slash > 0 && slash < m.length() - 1 ? m.substring(slash + 1) : m;
    }

    private String brief(double v) {
        long n = Math.round(v);
        if (n < 100_000) {
            if (v == Math.rint(v)) {
                return String.valueOf(n);
            }
            String s = String.format(java.util.Locale.US, "%.2f", v);
            while (s.endsWith("0")) {
                s = s.substring(0, s.length() - 1);
            }
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        }
        return NativeData.tokens(n);
    }

    static final class RouteLines extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Ui ui;

        RouteLines(android.content.Context ctx, Ui ui) {
            super(ctx);
            this.ui = ui;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.6f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.col(M3.OUTLINE_VARIANT));
        }

        void setLive(boolean live, boolean failed) {
            if (!live) {
                paint.setColor(ui.col(M3.OUTLINE_VARIANT));
                paint.setPathEffect(null);
            } else {
                paint.setColor(failed ? ui.col(M3.ERROR) : 0xFF34A853);
                float dash = ui.dp(5);
                paint.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{dash, dash * 0.9f}, 0));
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float fromX = w * 0.72f;
            float toX = w * 0.10f;
            path.reset();
            path.moveTo(fromX, 0);
            path.lineTo(fromX, h * 0.5f);
            path.quadTo(fromX, h * 0.82f, toX, h * 0.82f);
            path.lineTo(toX, h);
            canvas.drawPath(path, paint);
        }
    }
    /** 垂直 LinearLayout 的子项必须显式给 WRAP_CONTENT，否则会和别的子项平分整屏高度。 */
    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

}
