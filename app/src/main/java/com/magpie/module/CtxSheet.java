package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 「上下文窗口」详情卡：一条请求的 prompt 按组成部分摊开。
 *
 * <p>照 magpie 网页点开一条请求后那张卡：
 * <pre>
 * 上下文窗口                    group/xyng  ● 已计数
 * 已用上下文 [健康]
 * 21.1K / 1M token
 * 已用 2% · 剩余 979K
 *
 * 提示词缓存
 * 0% 命中
 * ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
 * 0 来自缓存 · 21.1K 新增
 *
 * [条形图：系统提示词 15.4K | 剩余空间 979K]
 * ■ 系统提示词 15.4K  ■ 工具 5.6K  ■ 会话内容 37
 * ■ 剩余空间 979K
 *
 * 内容      [全部] [系统提示词] [工具] [会话内容]
 * ● agent 提示词   15.4K
 * ● browser         776
 * ● terminal        534
 * </pre>
 *
 * <p>数据来自 {@code route.prompt}：`window` / `tokens` / `counted` /
 * `parts[{kind, tokens, items[{name, tag, n, tokens}]}]`。
 */
final class CtxSheet {

    /** prompt 的 kind 到显示名。 */
    static String kindNameOf(String kind) {
        switch (kind) {
            case "system":
                return "系统提示词";
            case "tools":
                return "工具";
            case "skills":
                return "技能";
            case "memory":
                return "记忆";
            case "files":
                return "文件";
            case "results":
                return "工具输出";
            case "chat":
                return "会话内容";
            default:
                return kind;
        }
    }

    /** 各 kind 的颜色（跟网页那几颗小圆点对应）。 */
    static int kindColorOf(Ui ui, String kind) {
        switch (kind) {
            case "system":
                return 0xFF8B5CF6;   // 紫
            case "tools":
                return 0xFF38BDF8;   // 蓝
            case "skills":
                return 0xFF34D399;   // 绿
            case "memory":
                return 0xFFFBBF24;   // 黄
            case "files":
                return 0xFFFB7185;   // 粉
            case "results":
                return 0xFFF97316;   // 橙
            case "chat":
                return 0xFF6366F1;   // 靛
            default:
                return ui.col(M3.OUTLINE);
        }
    }

    private CtxSheet() {
    }

    /**
     * 弹出一张上下文窗口详情。
     *
     * @param route 一条 trace/history 的 Route；没有 {@code prompt} 就提示「没有记录」
     */
    static void show(final Activity act, final Ui ui, final JSONObject route) {
        JSONObject prompt = NativeData.obj(route, "prompt");
        if (prompt == null) {
            AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle("上下文窗口")
                    .setMessage("这条请求没有留下上下文记录（magpie 只在能读到请求体时才统计）。")
                    .setPositiveButton("知道了", null)
                    .create();
                    styleDialog(act, ui, dlg);
            dlg.show();
            return;
        }

        final int window = NativeData.num(prompt, "window");
        final int tokens = NativeData.num(prompt, "tokens");
        final boolean counted = NativeData.flag(prompt, "counted");
        JSONArray parts = NativeData.arr(prompt, "parts");

        // ---- 外壳：可滚的竖布局 ----
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(18);
        root.setPadding(pad, ui.dp(4), pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 标题行：上下文窗口 + 组 + 已计数 ----
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = ui.text("上下文窗口", 16, M3.ON_SURFACE, true);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        JSONObject group = NativeData.obj(route, "group");
        String ref = group == null ? "" : NativeData.str(group, "ref",
                NativeData.str(group, "name", ""));
        if (!ref.isEmpty()) {
            TextView g = ui.mono(ref, 10, M3.ON_SURFACE_VARIANT);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.rightMargin = ui.dp(6);
            head.addView(g, glp);
        }
        if (counted) {
            TextView ok = ui.pill("已计数", M3.TERTIARY_CONTAINER, M3.ON_TERTIARY_CONTAINER);
            head.addView(ok);
        }
        root.addView(head);

        // ---- 已用上下文 ----
        LinearLayout usedRow = new LinearLayout(act);
        usedRow.setOrientation(LinearLayout.HORIZONTAL);
        usedRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = ui.dp(14);
        usedRow.setLayoutParams(ulp);
        usedRow.addView(ui.text("已用上下文", 12, M3.ON_SURFACE_VARIANT, false));

        double share = window > 0 ? tokens * 100.0 / window : -1;
        String health;
        int healthRole;
        if (share < 0) {
            health = "未知";
            healthRole = M3.SURFACE_CONTAINER_HIGHEST;
        } else if (share < 50) {
            health = "健康";
            healthRole = M3.TERTIARY_CONTAINER;
        } else if (share < 80) {
            health = "偏多";
            healthRole = M3.SECONDARY_CONTAINER;
        } else {
            health = "快满";
            healthRole = M3.ERROR_CONTAINER;
        }
        TextView h = ui.pill(health, healthRole,
                healthRole == M3.ERROR_CONTAINER ? M3.ON_ERROR_CONTAINER
                        : healthRole == M3.SECONDARY_CONTAINER ? M3.ON_SECONDARY_CONTAINER
                        : M3.ON_TERTIARY_CONTAINER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = ui.dp(8);
        usedRow.addView(h, hlp);
        root.addView(usedRow);

        // 大数字 21.1K / 1M token
        LinearLayout big = new LinearLayout(act);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.BOTTOM);
        big.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView num = ui.text(NativeData.tokens(tokens), 30, M3.ON_SURFACE, true);
        big.addView(num);
        TextView of = ui.text(window > 0 ? " / " + NativeData.tokens(window) + " token"
                : " token", 13, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.bottomMargin = ui.dp(5);
        big.addView(of, olp);
        root.addView(big);

        if (share >= 0) {
            String sub = "已用 " + NativeData.percent(share) + " · 剩余 "
                    + NativeData.tokens(Math.max(0, window - tokens));
            TextView s = ui.text(sub, 12, M3.ON_SURFACE_VARIANT, false);
            s.setLayoutParams(mlp(ui, 4));
            root.addView(s);
        }

        // ---- 提示词缓存 ----
        int cacheRead = 0;
        JSONArray usage = NativeData.arr(route, "usage");
        if (usage.length() > 0) {
            JSONObject u = usage.optJSONObject(0);
            cacheRead = NativeData.num(u, "cache_read");
        }
        long promptTokens = tokens > 0 ? tokens : 1;
        double hit = Math.max(0, Math.min(100, cacheRead * 100.0 / promptTokens));

        TextView cacheTitle = ui.text("提示词缓存", 12, M3.ON_SURFACE_VARIANT, false);
        cacheTitle.setLayoutParams(mlp(ui, 18));
        root.addView(cacheTitle);

        LinearLayout cacheBig = new LinearLayout(act);
        cacheBig.setOrientation(LinearLayout.HORIZONTAL);
        cacheBig.setGravity(Gravity.BOTTOM);
        cacheBig.addView(ui.text(NativeData.percent(hit), 30, M3.ON_SURFACE, true));
        TextView hitLabel = ui.text(" 命中", 13, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = ui.dp(5);
        cacheBig.addView(hitLabel, clp);
        root.addView(cacheBig);

        root.addView(ui.quotaBar(hit));

        TextView cacheSub = ui.text(NativeData.tokens(cacheRead) + " 来自缓存 · "
                        + NativeData.tokens(Math.max(0, tokens - cacheRead)) + " 新增",
                12, M3.ON_SURFACE_VARIANT, false);
        cacheSub.setLayoutParams(mlp(ui, 6));
        root.addView(cacheSub);

        // ---- 条形图：各 kind 的占比 + 剩余 ----
        List<JSONObject> kinds = new ArrayList<>();
        long sumParts = 0;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p == null || NativeData.num(p, "tokens") <= 0) {
                continue;
            }
            kinds.add(p);
            sumParts += NativeData.num(p, "tokens");
        }

        if (!kinds.isEmpty()) {
            long total = window > 0 ? window : Math.max(1, sumParts);

            // ---- 细条（占比一眼看出）----
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10));
            blp.topMargin = ui.dp(14);
            bar.setLayoutParams(blp);
            bar.setBackground(ui.round(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 999));
            bar.setClipToOutline(true);
            for (JSONObject p : kinds) {
                long n = NativeData.num(p, "tokens");
                View seg = new View(act);
                GradientDrawable d = new GradientDrawable();
                d.setColor(kindColorOf(ui, NativeData.str(p, "kind", "")));
                seg.setBackground(d);
                bar.addView(seg, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(0.004f, (float) n / total)));
            }
            long rest0 = Math.max(0, total - sumParts);
            if (rest0 > 0) {
                View seg = new View(act);
                bar.addView(seg, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, (float) rest0 / total));
            }
            root.addView(bar);

            // ---- 方块占比图（网页那张：左两块 + 右侧大片「剩余空间」斜线）----
            root.addView(treemap(act, ui, route, kinds, Math.max(total, tokens), tokens));

            // 图例：每 kind 一颗点 + 名字 + 数
            LinearLayout legend = new LinearLayout(act);
            legend.setOrientation(LinearLayout.VERTICAL);
            legend.setLayoutParams(mlp(ui, 10));
            for (JSONObject p : kinds) {
                legend.addView(legendRow(ui, kindColorOf(ui, NativeData.str(p, "kind", "")),
                        kindNameOf(NativeData.str(p, "kind", "")),
                        NativeData.tokens(NativeData.num(p, "tokens"))));
            }
            if (window > 0) {
                legend.addView(legendRow(ui, ui.col(M3.SURFACE_CONTAINER_HIGHEST),
                        "剩余空间", NativeData.tokens(Math.max(0, window - tokens))));
            }
            root.addView(legend);
        }

        // ---- 内容：按 kind 分组的明细 ----
        if (!kinds.isEmpty()) {
            TextView contentTitle = ui.text("内容", 12, M3.ON_SURFACE_VARIANT, false);
            contentTitle.setLayoutParams(mlp(ui, 16));
            root.addView(contentTitle);

            for (JSONObject p : kinds) {
                String kind = NativeData.str(p, "kind", "");
                JSONArray items = NativeData.arr(p, "items");

                LinearLayout group2 = new LinearLayout(act);
                group2.setOrientation(LinearLayout.VERTICAL);
                group2.setLayoutParams(mlp(ui, 10));

                LinearLayout gh = new LinearLayout(act);
                gh.setOrientation(LinearLayout.HORIZONTAL);
                gh.setGravity(Gravity.CENTER_VERTICAL);
                View dot = new View(act);
                GradientDrawable dd = new GradientDrawable();
                dd.setShape(GradientDrawable.OVAL);
                dd.setColor(kindColorOf(ui, kind));
                dot.setBackground(dd);
                gh.addView(dot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
                TextView gt = ui.text(kindNameOf(kind), 13, M3.ON_SURFACE, true);
                LinearLayout.LayoutParams gtlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                gtlp.leftMargin = ui.dp(7);
                gh.addView(gt, gtlp);
                gh.addView(ui.text(NativeData.tokens(NativeData.num(p, "tokens")),
                        12.5f, M3.ON_SURFACE, true));
                group2.addView(gh);

                // prompt.go 对 chat 的轮次按编号升序给出（oldest first）；
                // 列表需要最新一轮在最上面。其他类型按 token 多少排名，
                // 没有时间语义，保持上游原顺序，避免把工具等误标成新旧。
                boolean newestFirst = "chat".equals(kind);
                for (int index = 0; index < items.length(); index++) {
                    int i = newestFirst ? items.length() - 1 - index : index;
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    String name = NativeData.str(it, "name", "?");
                    int n = NativeData.num(it, "n");
                    if (n > 1) {
                        name += "（" + n + "）";
                    }
                    final String fName = name;
                    final String fKind = kind;
                    final int fTokens = NativeData.num(it, "tokens");
                    View row = itemRow(ui, kindColorOf(ui, kind), name, fTokens);
                    row.setClickable(true);
                    row.setFocusable(true);
                    row.setOnClickListener(v -> PartSheet.show(act, ui, route, fKind, fName, fTokens));
                    group2.addView(row);
                }
                root.addView(group2);
            }
        }

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        styleDialog(act, ui, dlg);
        dlg.show();
    }

    /**
     * 上下文地图：各类 token 分区，块内进一步按具体条目划分，剩余空间画斜纹。
     * 与网页 context.js 的 tilesOf/squarify 一样，用面积表达占比；
     * 点有内容的块打开原文，点剩余空间则无动作。
     */
    private static View treemap(Activity act, Ui ui, JSONObject route,
                                List<JSONObject> kinds, long window, long used) {
        ContextMap map = new ContextMap(act, ui, route, kinds, window, used);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(172));
        lp.topMargin = ui.dp(10);
        map.setLayoutParams(lp);
        return map;
    }

    private static final class MapTile {
        final String kind;
        final JSONObject item;
        final String title;
        final long tokens;
        final boolean free;
        float x, y, w, h;

        MapTile(String kind, JSONObject item, String title, long tokens, boolean free) {
            this.kind = kind;
            this.item = item;
            this.title = title;
            this.tokens = tokens;
            this.free = free;
        }
    }

    /**
     * 一个 View 的 Canvas 上画所有地图块，避免用数十个布局子项堆卡片。
     * 先对 kind 做 squarify，再在每个 kind 的矩形内对 items 做第二层 squarify。
     */
    private static final class ContextMap extends View {
        private static final int TILE_MIN = 1500;
        private static final int TILE_MAX = 12;
        private final Activity act;
        private final Ui ui;
        private final JSONObject route;
        private final List<JSONObject> kinds;
        private final long window;
        private final long used;
        private final List<MapTile> tiles = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int layoutW, layoutH;

        ContextMap(Activity act, Ui ui, JSONObject route, List<JSONObject> kinds,
                   long window, long used) {
            super(act);
            this.act = act;
            this.ui = ui;
            this.route = route;
            this.kinds = kinds;
            this.window = window;
            this.used = used;
            setClickable(true);
            setContentDescription("上下文地图，点不同颜色的块查看原文");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            if (w != layoutW || h != layoutH) {
                layoutW = w;
                layoutH = h;
                layoutTiles(w, h);
            }
            float gap = ui.dp(2);
            for (MapTile t : tiles) {
                float l = t.x + gap / 2, top = t.y + gap / 2;
                float r = t.x + t.w - gap / 2, b = t.y + t.h - gap / 2;
                if (r <= l || b <= top) {
                    continue;
                }
                rect.set(l, top, r, b);
                int color = t.free ? ui.col(M3.OUTLINE_VARIANT) : kindColorOf(ui, t.kind);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(t.free ? ui.col(M3.SURFACE_CONTAINER_HIGH)
                        : mix(color, ui.col(M3.SURFACE_CONTAINER_HIGH), 0.18f));
                canvas.drawRoundRect(rect, ui.dp(7), ui.dp(7), paint);
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(rect, ui.dp(7), ui.dp(7), android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                if (t.free) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(ui.dp(1));
                    paint.setColor(mix(ui.col(M3.ON_SURFACE_VARIANT),
                            ui.col(M3.SURFACE_CONTAINER_HIGH), 0.12f));
                    float d = ui.dp(7);
                    for (float x = l - (b - top); x < r; x += d) {
                        canvas.drawLine(x, b, x + (b - top), top, paint);
                    }
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, ui.dp(1)));
                paint.setColor(mix(color, ui.col(M3.SURFACE_CONTAINER_HIGH),
                        t.free ? 0.22f : 0.38f));
                canvas.drawRoundRect(rect, ui.dp(7), ui.dp(7), paint);
                canvas.restore();

                if (t.w < ui.dp(66) || t.h < ui.dp(29)) {
                    continue;
                }
                float inset = ui.dp(8);
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                paint.setTextSize(ui.dp(11));
                paint.setColor(t.free ? ui.col(M3.ON_SURFACE_VARIANT)
                        : mix(color, ui.col(M3.ON_SURFACE), 0.62f));
                canvas.save();
                canvas.clipRect(l + inset, top + ui.dp(4), r - inset, b - ui.dp(2));
                String name = shorten(t.title, paint, r - l - inset * 2);
                canvas.drawText(name, l + inset, top + ui.dp(16), paint);
                if (t.h >= ui.dp(50)) {
                    paint.setTypeface(android.graphics.Typeface.DEFAULT);
                    paint.setTextSize(ui.dp(10));
                    paint.setColor(ui.col(M3.ON_SURFACE_VARIANT));
                    String value = NativeData.tokens(t.tokens);
                    if (t.free && window > 0) {
                        value += " · " + NativeData.percent(t.tokens * 100.0 / window);
                    }
                    canvas.drawText(shorten(value, paint, r - l - inset * 2),
                            l + inset, top + ui.dp(33), paint);
                }
                canvas.restore();
            }
        }

        /** 先划分“已用/剩余”，再按上游类别及具体条目划分已用区。 */
        private void layoutTiles(int w, int h) {
            tiles.clear();
            long scale = Math.max(window, Math.max(used, 1));
            float longSide = Math.max(w, h);
            float ratio = Math.min(1f, Math.max(0f, (float) used / scale));
            float usedLen = used >= scale ? longSide
                    : Math.min(longSide, Math.max(longSide * (float) Math.pow(ratio, 0.35), ui.dp(36)));
            boolean across = w >= h;
            float usedW = across ? usedLen : w, usedH = across ? h : usedLen;

            List<MapTile> parts = new ArrayList<>();
            for (JSONObject p : kinds) {
                int n = NativeData.num(p, "tokens");
                if (n > 0) {
                    parts.add(new MapTile(NativeData.str(p, "kind", ""), null,
                            kindNameOf(NativeData.str(p, "kind", "")), n, false));
                }
            }
            squarify(parts, 0, 0, usedW, usedH);
            for (MapTile p : parts) {
                JSONObject part = null;
                for (JSONObject k : kinds) {
                    if (p.kind.equals(NativeData.str(k, "kind", ""))) {
                        part = k;
                        break;
                    }
                }
                JSONArray arr = NativeData.arr(part, "items");
                float area = p.w * p.h;
                if (arr.length() == 0 || area < TILE_MIN * 2) {
                    tiles.add(p);
                    continue;
                }
                List<JSONObject> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it != null && NativeData.num(it, "tokens") > 0) {
                        items.add(it);
                    }
                }
                items.sort((a, b) -> Integer.compare(NativeData.num(b, "tokens"),
                        NativeData.num(a, "tokens")));
                long sum = 0;
                for (JSONObject it : items) {
                    sum += NativeData.num(it, "tokens");
                }
                List<MapTile> inner = new ArrayList<>();
                long rest = 0;
                int kept = 0;
                for (JSONObject it : items) {
                    int n = NativeData.num(it, "tokens");
                    if (kept < TILE_MAX && (double) n / Math.max(1, sum) * area >= TILE_MIN) {
                        inner.add(new MapTile(p.kind, it,
                                NativeData.str(it, "name", "?"), n, false));
                        kept++;
                    } else {
                        rest += n;
                    }
                }
                if (rest > 0) {
                    inner.add(new MapTile(p.kind, null, "另外 " + (items.size() - kept) + " 项",
                            rest, false));
                }
                if (inner.size() == 0) {
                    tiles.add(p);
                    continue;
                }
                squarify(inner, p.x, p.y, p.w, p.h);
                boolean tooThin = false;
                for (MapTile t : inner) {
                    if (Math.min(t.w, t.h) < ui.dp(14)) {
                        tooThin = true;
                        break;
                    }
                }
                if (tooThin) {
                    // 防止一条窄到点不到的细线；保留分区，同时仍能点大块看整个类别。
                    tiles.add(p);
                } else {
                    tiles.addAll(inner);
                }
            }
            if (used < scale) {
                MapTile free = new MapTile("", null, "剩余空间", scale - used, true);
                free.x = across ? usedLen : 0;
                free.y = across ? 0 : usedLen;
                free.w = across ? w - usedLen : w;
                free.h = across ? h : h - usedLen;
                tiles.add(free);
            }
        }

        /** 保持数组顺序的 squarified treemap，块面积近似正比于 token。 */
        private void squarify(List<MapTile> vals, float x, float y, float w, float h) {
            if (vals.isEmpty() || w <= 0 || h <= 0) {
                return;
            }
            double sum = 0;
            for (MapTile v : vals) {
                sum += Math.max(0, v.tokens);
            }
            if (sum <= 0) {
                return;
            }
            double k = (w * h) / sum;
            List<MapTile> row = new ArrayList<>();
            for (MapTile v : vals) {
                float side = Math.min(w, h);
                if (row.isEmpty() || worst(row, v, side, k) <= worst(row, null, side, k)) {
                    row.add(v);
                } else {
                    float[] next = placeRow(row, x, y, w, h, k);
                    x = next[0]; y = next[1]; w = next[2]; h = next[3];
                    row.clear();
                    row.add(v);
                }
            }
            if (!row.isEmpty()) {
                placeRow(row, x, y, w, h, k);
            }
        }

        private double worst(List<MapTile> row, MapTile add, float side, double k) {
            double sum = 0, hi = 0, lo = Double.MAX_VALUE;
            for (MapTile v : row) {
                double area = Math.max(1, v.tokens * k);
                sum += area;
                hi = Math.max(hi, area);
                lo = Math.min(lo, area);
            }
            if (add != null) {
                double area = Math.max(1, add.tokens * k);
                sum += area;
                hi = Math.max(hi, area);
                lo = Math.min(lo, area);
            }
            if (sum <= 0 || side <= 0 || lo <= 0) {
                return Double.MAX_VALUE;
            }
            return Math.max(side * side * hi / (sum * sum),
                    (sum * sum) / (side * side * lo));
        }

        private float[] placeRow(List<MapTile> row, float x, float y,
                                 float w, float h, double k) {
            double area = 0;
            for (MapTile v : row) {
                area += v.tokens * k;
            }
            if (w >= h) {
                float cw = Math.min(w, (float) (area / Math.max(1, h)));
                float cy = y;
                for (MapTile v : row) {
                    float ch = (float) (v.tokens * k / Math.max(1, cw));
                    v.x = x; v.y = cy; v.w = cw; v.h = ch;
                    cy += ch;
                }
                return new float[]{x + cw, y, w - cw, h};
            }
            float rh = Math.min(h, (float) (area / Math.max(1, w)));
            float cx = x;
            for (MapTile v : row) {
                float cw = (float) (v.tokens * k / Math.max(1, rh));
                v.x = cx; v.y = y; v.w = cw; v.h = rh;
                cx += cw;
            }
            return new float[]{x, y + rh, w, h - rh};
        }

        private static String shorten(String value, Paint p, float width) {
            if (value == null || width <= 0) {
                return "";
            }
            if (p.measureText(value) <= width) {
                return value;
            }
            String dots = "…";
            int end = value.length();
            while (end > 0 && p.measureText(value.substring(0, end) + dots) > width) {
                end--;
            }
            return end == 0 ? dots : value.substring(0, end) + dots;
        }

        private static int mix(int color, int background, float strength) {
            float a = Math.max(0, Math.min(1, strength));
            int r = Math.round(((color >> 16) & 255) * a + ((background >> 16) & 255) * (1 - a));
            int g = Math.round(((color >> 8) & 255) * a + ((background >> 8) & 255) * (1 - a));
            int b = Math.round((color & 255) * a + (background & 255) * (1 - a));
            return 0xff000000 | (r << 16) | (g << 8) | b;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float x = event.getX(), y = event.getY();
                for (MapTile tile : tiles) {
                    if (tile.free || x < tile.x || x > tile.x + tile.w
                            || y < tile.y || y > tile.y + tile.h) {
                        continue;
                    }
                    performClick();
                    String name = tile.item == null ? "" : NativeData.str(tile.item, "name", "");
                    PartSheet.show(act, ui, route, tile.kind, name,
                            (int) Math.min(Integer.MAX_VALUE, tile.tokens));
                    return true;
                }
            }
            return true;
        }
    }

    private static LinearLayout.LayoutParams mlp(Ui ui, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(topDp);
        return lp;
    }

    private static View legendRow(Ui ui, int color, String name, String value) {
        LinearLayout row = new LinearLayout(ui.ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        row.setLayoutParams(lp);

        View dot = new View(ui.ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));

        TextView t = ui.text(name, 11.5f, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(7);
        row.addView(t, tlp);

        row.addView(ui.mono(value, 11.5f, M3.ON_SURFACE_VARIANT));
        return row;
    }

    private static View itemRow(Ui ui, int color, String name, int tokens) {
        LinearLayout row = new LinearLayout(ui.ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(5);
        lp.leftMargin = ui.dp(2);
        row.setLayoutParams(lp);

        View dot = new View(ui.ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        row.addView(dot, new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));

        TextView t = ui.text(name, 12, M3.ON_SURFACE, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(8);
        row.addView(t, tlp);

        row.addView(ui.mono(NativeData.tokens(tokens), 11.5f, M3.ON_SURFACE_VARIANT));
        return row;
    }

    private static void styleDialog(Activity act, Ui ui, AlertDialog dlg) {
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
            }
        } catch (Exception ignored) {
        }
    }
}
