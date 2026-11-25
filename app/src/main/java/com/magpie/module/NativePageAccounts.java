package com.magpie.module;

import android.content.ClipData;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号页：按供应商分组，两级折叠。
 *
 * <pre>
 * ▼ 天枢                    5 个账号 · 余额 $4.18
 *     ▸ 账号1                          $4.18
 *     ▸ 账号2                          $4.18
 * ▼ WorkBuddy               2 个账号 · Total credits 692 / 5342
 *     ▾ 个人1                Credits 0 / 500 · 最紧 61.6% 剩余
 *         [展开后的额度窗口、重置倒计时、读取时间]
 * </pre>
 *
 * <p>阶梯靠左内边距做：一级卡片左右各 14dp，二级缩进到 34dp，视觉上像树。
 * 供应商那一行写这家**合计**（余额去重相加、额度窗口按名字累加），
 * 不用点开就大致知道这家还有多少 —— 算法在 {@link QuotaAgg}。
 */
final class NativePageAccounts extends NativePage {

    /** 一级（供应商）缩进。 */
    private static final int INDENT_L1 = 14;
    /** 二级（账号）缩进。 */
    private static final int INDENT_L2 = 30;

    private final Map<String, String> offProviders = new LinkedHashMap<>();

    /** 签到的 /api/state 快照（开关+今日状态），签到动作后刷新。 */
    private volatile org.json.JSONObject checkinState;
    /** 正在签到中的供应商（防止重复点）。 */
    private final java.util.Set<String> checkinBusy = new java.util.HashSet<>();

    /** 最近一次渲染时的全部 provider key 和账号 key，用于「再点一次 tab 全部收起」。 */
    private final List<String> lastProviderKeys = new ArrayList<>();
    private final List<String> lastAccountKeys = new ArrayList<>();

    NativePageAccounts(NativeHome host) {
        super(host);
    }

    /**
     * 再点一次底栏「账号」：收起所有展开的供应商/账号卡片，并回到页面顶部。
     */
    @Override
    void collapseAndScrollTop() {
        boolean changed = false;
        for (String k : lastProviderKeys) {
            if (Collapse.expanded(host, k, false)) {
                Collapse.setExpanded(host, k, false);
                changed = true;
            }
        }
        for (String k : lastAccountKeys) {
            if (Collapse.expanded(host, k, false)) {
                Collapse.setExpanded(host, k, false);
                changed = true;
            }
        }
        if (changed) {
            // 收起后重画一次，保持回顶部的意图（refresh 里已经会重建列表）
            refresh(true);
        } else {
            scroll.post(() -> scroll.smoothScrollTo(0, 0));
        }
    }

    @Override
    String name() {
        return "账号";
    }

    @Override
    void load() {
        MagpieApi.Result quota = MagpieApi.quotas(host);
        if (!quota.ok()) {
            host.post(() -> failed(quota.why()));
            host.post(this::done);
            return;
        }
        // 供应商列表只是用来标「关掉的」，读不到不影响这一页
        MagpieApi.Result prov = MagpieApi.providersLast(host);
        Map<String, String> off = new LinkedHashMap<>();
        if (prov.ok()) {
            JSONObject o = prov.json();
            JSONArray arr = NativeData.arr(o, "providers");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = NativeData.at(arr, i);
                if (p == null) {
                    continue;
                }
                if (NativeData.flag(p, "off")) {
                    off.put(NativeData.str(p, "id", ""),
                            NativeData.str(p, "name", ""));
                }
            }
        }
        JSONArray q = QuotaCache.merge(quota.array());
        // 开关状态在 /api/providers 里；顺手读回来，跟额度一起画
        Map<String, Boolean> state = new LinkedHashMap<>();
        if (prov.ok()) {
            JSONObject o = prov.json();
            JSONArray arr = NativeData.arr(o, "providers");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = NativeData.at(arr, i);
                if (p == null) {
                    continue;
                }
                String id = NativeData.str(p, "id", "");
                JSONObject acct = NativeData.obj(p, "account");
                if (acct != null) {
                    JSONArray logins = NativeData.arr(acct, "logins");
                    for (int j = 0; j < logins.length(); j++) {
                        JSONObject l = NativeData.at(logins, j);
                        if (l != null) {
                            state.put(id + "|" + NativeData.str(l, "user", ""),
                                    NativeData.flag(l, "on"));
                        }
                    }
                }
                JSONArray keys = NativeData.arr(p, "keyList");
                for (int j = 0; j < keys.length(); j++) {
                    JSONObject k = NativeData.at(keys, j);
                    if (k != null) {
                        // key 型卡片的 user 可能是 key 的 name（用户自己起的名，
                        // 如「国模」「福利」），也可能是 masked key。两个都当键存，
                        // 否则按 name 来的卡片会查不到开关状态（误显示「停用」）。
                        boolean on = NativeData.flag(k, "on");
                        String masked = NativeData.str(k, "masked", "");
                        String keyName = NativeData.str(k, "name", "");
                        if (!masked.isEmpty()) {
                            state.put(id + "|key|" + masked, on);
                        }
                        if (!keyName.isEmpty() && !keyName.equals(masked)) {
                            state.put(id + "|key|" + keyName, on);
                        }
                    }
                }
            }
        }

        // 签到状态：读不到不影响本页（额度照画，签到按钮不出现）
        MagpieApi.Result cs = MagpieApi.stateLast(host);
        if (cs.ok() && cs.json() != null) {
            checkinState = cs.json();
        }

        host.post(() -> {
            // 开关状态先落地，accountRow 建卡片时要用
            onState.clear();
            onState.putAll(state);
            offProviders.clear();
            offProviders.putAll(off);
            if (quota.reading) {
                Log.i("MagpieNative", "额度还在读，稍后再问");
            }
            render(q);
            done();
            if (quota.reading) {
                recheckLater();
            } else {
                rechecksDone();
            }
        });
    }

    // ------------------------------------------------------------------

    private void render(JSONArray quotas) {
        rebuildKeepingScroll(() -> {
        content.removeAllViews();

        if (quotas == null || quotas.length() == 0) {
            content.addView(emptyState());
            content.addView(howToAdd());
            return;
        }

        // 按 provider 分组，但保持 magpie 自己的顺序
        Map<String, List<JSONObject>> byProvider = new LinkedHashMap<>();
        for (int i = 0; i < quotas.length(); i++) {
            JSONObject q = NativeData.at(quotas, i);
            if (q == null) {
                continue;
            }
            String p = NativeData.str(q, "provider", "?");
            List<JSONObject> list = byProvider.get(p);
            if (list == null) {
                list = new ArrayList<>();
                byProvider.put(p, list);
            }
            list.add(q);
        }

        content.addView(summaryBar(quotas.length(), byProvider.size()));
        content.addView(expandAllBar(byProvider));

        for (Map.Entry<String, List<JSONObject>> e : byProvider.entrySet()) {
            content.addView(providerGroup(e.getKey(), e.getValue()));
        }

        // 记下这批 key，供「再点一次 tab 收起全部」用
        lastProviderKeys.clear();
        lastAccountKeys.clear();
        for (Map.Entry<String, List<JSONObject>> e : byProvider.entrySet()) {
            lastProviderKeys.add("prov:" + e.getKey());
            for (JSONObject q : e.getValue()) {
                lastAccountKeys.add(accountKey(q));
            }
        }

        TextView foot = ui.text("额度由各家供应商接口读取，可能滞后几分钟；"
                        + "点账号那一行展开明细，右边「刷新」重读单个账号。",
                11, M3.ON_SURFACE_VARIANT, false);
        foot.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), 0);
        foot.setLineSpacing(ui.dp(2), 1f);
        content.addView(foot);
        });
    }

    private View summaryBar(int accounts, int providers) {
        LinearLayout card = ui.cardWithMargin();
        LinearLayout row = ui.row();
        row.addView(ui.stat(String.valueOf(accounts), "个账号", M3.ON_SURFACE, 1f));
        row.addView(ui.stat(String.valueOf(providers), "家供应商", M3.ON_SURFACE, 1f));
        row.addView(ui.stat(String.valueOf(offProviders.size()), "家已关闭", M3.ON_SURFACE, 1f));

        // 第四格：自动签到入口，两态 + 状态点。
        // 自动签到 = 没开任何一家的开关（灰点）；已开启自动签到 = 至少一家开着（绿点）。
        // half 是竖排容器：里面的子项千万别带 weight（竖排的 weight 分配的是
        // 高度，width=0 会真·零宽把文字吞掉——1.8.9 第一次构建就栽在这）。
        boolean anyOn = checkinState != null && anyCheckinOn(checkinState);
        TextView checkin = ui.text("自动签到", 17, M3.ON_SURFACE, true);
        checkin.setGravity(Gravity.CENTER);
        checkin.setOnClickListener(v -> showCheckinSheet());
        LinearLayout subRow = new LinearLayout(host);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER);
        android.view.View dot = new android.view.View(host);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(ui.col(anyOn ? M3.TERTIARY : M3.OUTLINE_VARIANT));
        dot.setBackground(dotBg);
        dot.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)));
        LinearLayout.LayoutParams dlp2 = (LinearLayout.LayoutParams) dot.getLayoutParams();
        dlp2.rightMargin = ui.dp(4);
        dlp2.gravity = android.view.Gravity.CENTER_VERTICAL;
        subRow.addView(dot);
        TextView sub = new TextView(host);
        sub.setText(anyOn ? "已开启自动签到 ›" : "自动签到 ›");
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(ui.col(anyOn ? M3.ON_SURFACE : M3.ON_SURFACE_VARIANT));
        sub.setGravity(Gravity.CENTER);
        subRow.addView(sub);
        LinearLayout half = new LinearLayout(host);
        half.setOrientation(LinearLayout.VERTICAL);
        half.setGravity(Gravity.CENTER);
        half.addView(checkin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        half.addView(subRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        half.setOnClickListener(v -> showCheckinSheet());
        row.addView(half,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);
        return card;
    }

    /**
     * 自动签到弹层：每家供应商一个开关行。
     *
     * <p>上游没有总开关，每家各自一个（内置四家 + 插件型）。这里列出的
     * 是「有账号可签」的：内置四家恒列（开关本来就要有地方放），插件型
     * 只列 checkinPlugins 里报上来的。
     */
    private void showCheckinSheet() {
        if (checkinState == null) {
            host.toastPublic("读不到签到状态，稍后再试");
            return;
        }
        LinearLayout box = ui.column();
        int pad = ui.dp(18);
        box.setPadding(pad, ui.dp(8), pad, ui.dp(6));

        TextView tip = ui.text("打开后 magpie 每天自动帮这家账号签到领积分；"
                        + "也可以在下面的供应商卡片上手动点「签到」。",
                12, M3.ON_SURFACE_VARIANT, false);
        tip.setLineSpacing(ui.dp(2), 1f);
        tip.setLayoutParams(ui.matchWrap(10));
        box.addView(tip);

        Map<String, Boolean> sw = Checkins.switches(checkinState);
        android.widget.ScrollView sc = new android.widget.ScrollView(host);
        LinearLayout list = ui.column();
        // 顺序：内置四家恒列，然后是 pluginCheckins / checkinPlugins 里出现的插件型
        java.util.List<String> order = new java.util.ArrayList<>();
        for (int i = 0; i < Checkins.BUILTIN.length; i++) {
            order.add(Checkins.BUILTIN[i][0]);
        }
        java.util.List<String> pluginOnly = new java.util.ArrayList<>();
        JSONObject st = checkinState.optJSONObject("settings");
        JSONObject pcMap = st == null ? null : st.optJSONObject("pluginCheckins");
        if (pcMap != null) {
            java.util.Iterator<String> it = pcMap.keys();
            while (it.hasNext()) {
                String id = it.next();
                String key = "plugin:" + id;
                // 内置四家对应的插件层 id 不重复列（它们已由供应商级那行代表）
                if (!isInheritedPluginId(id) && !order.contains(key)) {
                    pluginOnly.add(key);
                }
            }
        }
        for (String k : sw.keySet()) {
            if (k.startsWith("plugin:") && !order.contains(k) && !pluginOnly.contains(k)) {
                pluginOnly.add(k);
            }
        }
        order.addAll(pluginOnly);
        final Map<String, android.widget.Switch> live = new LinkedHashMap<>();
        for (String vendor : order) {
            Boolean on = sw.get(vendor);
            if (on == null) {
                // 内置四家从 switches 一定有值；插件型没值就当关
                on = false;
            }
            String name = vendor.startsWith("plugin:")
                    ? NativeData.pretty(vendor.substring("plugin:".length()))
                    : vendorTitle(vendor);
            android.widget.Switch swv = new android.widget.Switch(host);
            swv.setText(name);
            swv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
            swv.setTextColor(ui.col(M3.ON_SURFACE));
            swv.setChecked(on);
            swv.setPadding(0, ui.dp(10), 0, ui.dp(10));
            live.put(vendor, swv);
            swv.setOnCheckedChangeListener((b, checked) -> {
                swv.setEnabled(false);
                host.bg(() -> {
                    JSONObject[] out = {checkinState};
                    String err = Checkins.setSwitch(host, vendor, checked, out);
                    host.post(() -> {
                        swv.setEnabled(true);
                        if (err != null) {
                            swv.setChecked(!checked);
                            host.toastPublic("设不上：" + err);
                        } else {
                            checkinState = out[0];
                            host.toastPublic(name + " 自动签到已" + (checked ? "开启" : "关闭"));
                            // 头部状态点跟这口开关联动：立即重读本页
                            refresh(true);
                        }
                    });
                });
            });
            list.addView(swv);
        }
        sc.addView(list);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)));

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(host)
                .setTitle("自动签到")
                .setView(box)
                .setPositiveButton("关闭", null)
                .setCancelable(true)
                .create();
        host.styleDialogPublic(dlg);
        dlg.show();
    }

    /**
     * 自动签到是否「已开启」：**全部**都开着才算（爸爸定的口径）——
     * 供应商级开关（内置四家 + 插件型）和账号级开关（checkinAccounts）
     * 任何一个没开，就是「未开启自动签到」。
     * <p>为什么账号级也要算：网页后台能单独把某个账号设为「不参与每日
     * 自动签到」（settings/checkin-account），那也是一种"没开"。
     */
    private boolean anyCheckinOn(JSONObject state) {
        java.util.Map<String, Boolean> sw = Checkins.switches(state);
        if (sw.isEmpty()) {
            return false;
        }
        for (Boolean b : sw.values()) {
            if (!b) {
                return false;
            }
        }
        // 账号级：任何一个在册账号 on=false 就是没开全
        org.json.JSONArray arr = state.optJSONArray("checkinAccounts");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = NativeData.at(arr, i);
                if (a != null && !NativeData.flag(a, "on")) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 这个插件 provider id 是不是内置四家某个变体（弹层里不重复列）。 */
    private static boolean isInheritedPluginId(String id) {
        for (String[] m : new String[][]{
                {"workbuddy", "workbuddy"}, {"trae", "trae-cn"},
                {"minimax", "minimax-code"}, {"minimax", "minimax-code-global"},
                {"qoder", "qoder"}, {"qoder", "qoder-cn"}}) {
            if (m[1].equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String vendorTitle(String vendor) {
        for (int i = 0; i < Checkins.BUILTIN.length; i++) {
            if (Checkins.BUILTIN[i][0].equals(vendor)) {
                return Checkins.VENDOR_TITLES[i];
            }
        }
        return vendor;
    }

    /** 「全部展开 / 全部收起」两个小胶囊，省得一家家点。 */
    private View expandAllBar(Map<String, List<JSONObject>> byProvider) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(2));
        // 小屏放不下三个胶囊时让它们自己缩，不要把「添加供应商」挤出屏幕
        row.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(2));

        TextView all = ui.pill("全部展开", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        all.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        all.setOnClickListener(v -> {
            // 只展开到「账号列表」这一级；每张账号卡的明细保持用户自己点
            for (String p : byProvider.keySet()) {
                Collapse.setExpanded(host, "prov:" + p, true);
            }
            refresh(true);
        });
        row.addView(all);

        TextView none = ui.pill("全部收起", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        none.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        LinearLayout.LayoutParams nlp = ui.wrap();
        nlp.leftMargin = ui.dp(8);
        none.setOnClickListener(v -> {
            for (Map.Entry<String, List<JSONObject>> e : byProvider.entrySet()) {
                Collapse.setExpanded(host, "prov:" + e.getKey(), false);
                for (JSONObject q : e.getValue()) {
                    Collapse.setExpanded(host, accountKey(q), false);
                }
            }
            refresh(true);
        });
        row.addView(none, nlp);

        // 手动拉取刷新模型 + 查看全部模型（替代原来的「添加供应商」）
        TextView refreshModels = ui.pill("刷新模型", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        refreshModels.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        LinearLayout.LayoutParams rlp = ui.wrap();
        rlp.leftMargin = ui.dp(8);
        refreshModels.setOnClickListener(v -> refreshModels());
        row.addView(refreshModels, rlp);

        TextView allModels = ui.pill("全部模型", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        allModels.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        LinearLayout.LayoutParams mlp = ui.wrap();
        mlp.leftMargin = ui.dp(8);
        allModels.setOnClickListener(v -> showAllModels());
        row.addView(allModels, mlp);
        return row;
    }

    /**
     * 手动拉取一次各家供应商的模型表。
     *
     * <p>走 {@code /api/providers?wait}：magpie 会真去问供应商（最多 8 秒），
     * 而不是只给上次缓存。完事后重读本页。
     */
    private void refreshModels() {
        host.bg(() -> {
            MagpieApi.Result r = MagpieApi.providersWait(host);
            host.post(() -> {
                if (!r.ok()) {
                    toast("刷新模型失败：" + r.why());
                    return;
                }
                toast("模型表已刷新，正在重读…");
                QuotaCache.reset();
                refresh(true);
            });
        });
    }

    /**
     * 弹出「全部模型」：按供应商分组，列出当前每个模型的显示名、
     * provider id、模型 id，以及该供应商的全收写法 {@code id/*}。
     */
    private void showAllModels() {
        host.bg(() -> {
            MagpieApi.Result r = MagpieApi.providersFull(host);
            final MagpieApi.Result res = r;
            host.post(() -> {
                if (!res.ok()) {
                    toast("读不到模型列表：" + res.why());
                    return;
                }
                JSONObject o = res.json();
                if (o == null) {
                    toast("模型列表解析失败");
                    return;
                }
                buildAllModelsSheet(o);
            });
        });
    }

    /** 全部模型弹窗（数据已在 res 里，直接建 UI）。 */
    private void buildAllModelsSheet(JSONObject o) {
        android.app.Dialog dialog = new android.app.Dialog(host, R.style.Theme_Magpie_Sheet);
        LinearLayout root = ui.column();
        GradientDrawable bg = ui.round(ui.col(M3.SURFACE_CONTAINER_LOW), 28);
        bg.setCornerRadii(new float[]{
                ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(host, "navigation_bar_height"));

        TextView title = ui.text("全部模型", 21, M3.ON_SURFACE, true);
        title.setPadding(ui.dp(4), 0, 0, ui.dp(4));
        root.addView(title);

        TextView sub = ui.text("显示名 / provider id / 模型 id · 全收写法",
                12, M3.ON_SURFACE_VARIANT, false);
        sub.setPadding(ui.dp(4), 0, 0, ui.dp(10));
        root.addView(sub);

        ScrollView scroll = new ScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = ui.column();
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        JSONArray providers = NativeData.arr(o, "providers");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = NativeData.at(providers, i);
            if (p == null) {
                continue;
            }
            String pid = NativeData.str(p, "id", "");
            String pname = NativeData.str(p, "name", pid);
            JSONArray models = NativeData.arr(p, "models");
            list.addView(providerBlock(pid, pname, models));
        }

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int screenH = host.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * 0.9f));
        }
        dialog.show();
    }

    /** 一家供应商的模型块：标题行（显示名 + provider id + 全收写法）+ 模型行。 */
    private View providerBlock(String pid, String pname, JSONArray models) {
        LinearLayout card = ui.column();
        ui.glowCard(card, ui.glowStroke(ui.col(M3.SURFACE_CONTAINER), 16,
                ui.col(M3.OUTLINE_VARIANT), 1));
        int pad = ui.dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = ui.matchWrap(8);
        lp.leftMargin = ui.dp(2);
        lp.rightMargin = ui.dp(2);
        card.setLayoutParams(lp);

        LinearLayout head = ui.row();
        TextView t = ui.text(pname, 14.5f, M3.ON_SURFACE, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(t, ui.weight(1));

        TextView wild = ui.mono(pid + "/*", 10.5f, M3.PRIMARY);
        wild.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    host.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("magpie", pid + "/*"));
            }
            toast("已复制 " + pid + "/*");
        });
        head.addView(wild);
        card.addView(head);

        TextView pidLine = ui.mono("provider id: " + pid, 10.5f, M3.ON_SURFACE_VARIANT);
        pidLine.setLayoutParams(ui.matchWrap(3));
        card.addView(pidLine);

        if (models.length() == 0) {
            TextView none = ui.text("（没有模型）", 11.5f, M3.ON_SURFACE_VARIANT, false);
            none.setLayoutParams(ui.matchWrap(6));
            card.addView(none);
            return card;
        }

        for (int i = 0; i < models.length(); i++) {
            JSONObject m = NativeData.at(models, i);
            if (m == null) {
                continue;
            }
            String mid = NativeData.str(m, "id", "?");
            String mname = NativeData.str(m, "name", "");
            if (mname.isEmpty()) {
                mname = mid;
            }
            card.addView(modelRow(pid, mname, mid, m));
        }
        return card;
    }

    /**
     * 一个模型行：上行 显示名 + 倍率 + 能力胶囊，下行 模型 id。
     * 长按复制完整引用 {@code pid/mid}。
     */
    private View modelRow(final String pid, String mname, final String mid, JSONObject m) {
        LinearLayout row = ui.column();
        row.setPadding(ui.dp(4), ui.dp(5), ui.dp(4), ui.dp(5));
        row.setBackground(ui.round(ui.col(M3.SURFACE_CONTAINER_HIGH), 10));
        LinearLayout.LayoutParams lp = ui.matchWrap(3);
        row.setLayoutParams(lp);

        LinearLayout top = ui.row();

        TextView mn = ui.text(mname, 12.5f, M3.ON_SURFACE, true);
        mn.setSingleLine(true);
        mn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(mn, ui.weight(1));

        // 倍率：rate 是「一个请求消耗套餐多少倍」；0 = 没给
        double rate = NativeData.dec(m, "rate");
        if (rate > 0) {
            TextView r = ui.mono(rateText(rate) + "×", 10.5f, M3.PRIMARY);
            LinearLayout.LayoutParams rlp = ui.wrap();
            rlp.leftMargin = ui.dp(6);
            top.addView(r, rlp);
        }

        // 能力胶囊：免费 / 视觉 / 推理 / 生图 / 生视频
        if (NativeData.flag(m, "free")) {
            top.addView(capPill("免费", M3.TERTIARY_CONTAINER, M3.ON_TERTIARY_CONTAINER));
        }
        if (NativeData.flag(m, "images")) {
            top.addView(capPill("视觉", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER));
        }
        JSONArray efforts = NativeData.arr(m, "efforts");
        if (efforts.length() > 0) {
            top.addView(capPill("推理", M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER));
        }
        String kind = NativeData.str(m, "kind", "");
        if ("image".equalsIgnoreCase(kind)) {
            top.addView(capPill("生图", M3.ERROR_CONTAINER, M3.ON_ERROR_CONTAINER));
        } else if ("video".equalsIgnoreCase(kind)) {
            top.addView(capPill("生视频", M3.ERROR_CONTAINER, M3.ON_ERROR_CONTAINER));
        }

        row.addView(top);

        TextView mi = ui.mono(mid, 10.5f, M3.ON_SURFACE_VARIANT);
        mi.setSingleLine(true);
        mi.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mi.setLayoutParams(ui.matchWrap(2));
        row.addView(mi);

        row.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    host.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("magpie", pid + "/" + mid));
            }
            toast("已复制 " + pid + "/" + mid);
            return true;
        });
        return row;
    }

    private View capPill(String s, int bg, int fg) {
        TextView t = ui.text(s, 9.5f, fg, true);
        t.setBackground(ui.round(ui.col(bg), 999));
        int px = ui.dp(6), py = ui.dp(2);
        t.setPadding(px, py, px, py);
        LinearLayout.LayoutParams lp = ui.wrap();
        lp.leftMargin = ui.dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private String rateText(double rate) {
        if (rate >= 100) {
            return String.valueOf(Math.round(rate));
        }
        String s = String.format(java.util.Locale.US, "%.2f", rate);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(host, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private String accountKey(JSONObject q) {
        return "acct:" + NativeData.str(q, "provider", "") + "|"
                + NativeData.str(q, "user", "") + "|"
                + NativeData.str(q, "alias", "");
    }

    // ------------------------------------------------------------------
    // 一级：供应商
    // ------------------------------------------------------------------

    private View providerGroup(String providerId, List<JSONObject> list) {
        LinearLayout wrap = ui.column();
        String key = "prov:" + providerId;
        boolean expanded = Collapse.expanded(host, key, false);

        String name = NativeData.pretty(providerId);
        for (JSONObject q : list) {
            String n = NativeData.str(q, "name", "");
            if (!n.isEmpty()) {
                name = n;
                break;
            }
        }

        // ---- 供应商标题行（一级卡片，带合计）----
        LinearLayout header = ui.row();
        GradientDrawable hbg = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 18,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.clickable(header, hbg);
        if (ui.design) {
            header.setElevation(ui.dp(6));
        }
        header.setPadding(ui.dp(14), ui.dp(13), ui.dp(12), ui.dp(13));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(ui.dp(INDENT_L1), ui.dp(5), ui.dp(INDENT_L1), ui.dp(3));
        header.setLayoutParams(hlp);

        TextView arrow = ui.text("", 13, M3.ON_SURFACE_VARIANT, false);
        arrow.setGravity(Gravity.CENTER);
        header.addView(arrow, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));

        // 供应商图标：magpie 那套 logo（打包成 PNG），没有就画首字母圆底
        header.addView(providerIcon(providerId, list, 34), iconLp(34, 6, 0));

        LinearLayout titleBox = ui.column();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(10);
        header.addView(titleBox, tlp);

        TextView title = ui.text(name, 15, M3.ON_SURFACE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(title);

        // 这一家合计：余额（共享账户去重）/ 额度总量 / 最紧剩余
        TextView total = ui.text(QuotaAgg.totalLine(list), 11.5f,
                M3.ON_SURFACE_VARIANT, false);
        total.setSingleLine(true);
        total.setEllipsize(android.text.TextUtils.TruncateAt.END);
        total.setLayoutParams(ui.matchWrap(3));
        titleBox.addView(total);

        if (offProviders.containsKey(providerId)) {
            LinearLayout.LayoutParams olp = ui.wrap();
            olp.rightMargin = ui.dp(4);
            header.addView(ui.pill("已关闭", M3.ERROR_CONTAINER, M3.ON_ERROR_CONTAINER), olp);
        }

        // 签到按钮：这家有可签账号时出现。今天未签=主色「签到」，已签=灰「已签到」。
        // 点了立即调上游手动签到，完成（含结果提示）后刷新本页。
        addCheckinButton(header, providerId);

        // ---- 二级：账号列表（缩进，一眼能看出是这家的子项）----
        LinearLayout body = ui.column();
        for (JSONObject q : list) {
            body.addView(accountRow(q));
        }

        Collapse.wire(host, header, new Collapse.Unit(arrow, body, null, expanded), key);
        wrap.addView(header);
        wrap.addView(body);
        return wrap;
    }

    /**
     * 供应商卡右侧的签到按钮。
     *
     * <p>判定链：没读到签到状态 / 这家没有可签账号（checkinAccounts 无记录）→
     * 不出现；今天已签（outcome claimed/done 且 day=北京时间今天）→ 灰胶囊
     * 「已签到」；否则主色胶囊「签到」，点了即调 POST /api/usage/&lt;vendor&gt;-checkin
     * （插件型 /api/usage/plugin-checkin）。结果 toast：成功带签到结果条数，
     * 失败带原因（captcha/own-app 这些特殊情况上游在 msg 里说）。
     */
    private void addCheckinButton(LinearLayout header, String providerId) {
        if (checkinState == null) {
            return;
        }
        if (!Checkins.hasAccounts(checkinState, providerId)) {
            return;
        }
        String status = Checkins.statusOf(checkinState, providerId);
        if ("none".equals(status)) {
            return;
        }
        if (checkinBusy.contains(providerId)) {
            TextView busy = ui.pill("签到中…", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE_VARIANT);
            LinearLayout.LayoutParams blp = ui.wrap();
            blp.rightMargin = ui.dp(4);
            header.addView(busy, blp);
            return;
        }
        if ("done".equals(status)) {
            // 已签到：灰胶囊 + 绿点
            TextView done = ui.pill("已签到", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE_VARIANT);
            done.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable dotOk = new GradientDrawable();
            dotOk.setShape(GradientDrawable.OVAL);
            dotOk.setColor(ui.col(M3.TERTIARY));
            // 文字前塞一个绿点：用 span 不方便，直接在胶囊左侧放点视图
            LinearLayout doneRow = new LinearLayout(host);
            doneRow.setGravity(Gravity.CENTER_VERTICAL);
            android.view.View dv = new android.view.View(host);
            dv.setBackground(dotOk);
            dv.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)));
            LinearLayout.LayoutParams dvlp = (LinearLayout.LayoutParams) dv.getLayoutParams();
            dvlp.rightMargin = ui.dp(5);
            doneRow.addView(dv);
            doneRow.addView(done);
            LinearLayout.LayoutParams dlp = ui.wrap();
            dlp.rightMargin = ui.dp(4);
            header.addView(doneRow, dlp);
            return;
        }
        TextView btn = ui.pill("签到", M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER);
        LinearLayout.LayoutParams lp = ui.wrap();
        lp.rightMargin = ui.dp(4);
        btn.setOnClickListener(v -> {
            if (checkinBusy.contains(providerId)) {
                return;
            }
            checkinBusy.add(providerId);
            host.toastPublic("正在签到 " + providerId + " …");
            host.bg(() -> {
                JSONObject[] out = {checkinState};
                String err = Checkins.checkinNow(host, providerId, out);
                host.post(() -> {
                    checkinBusy.remove(providerId);
                    if (err != null) {
                        host.toastPublic("签到失败：" + err);
                    } else {
                        checkinState = out[0];
                        host.toastPublic("签到完成");
                        refresh(true);
                    }
                });
            });
        });
        header.addView(btn, lp);
    }

    /** 图标在标题行里的布局参数。 */
    private LinearLayout.LayoutParams iconLp(int sizeDp, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp));
        lp.leftMargin = ui.dp(leftDp);
        lp.rightMargin = ui.dp(rightDp);
        return lp;
    }

    /**
     * 一个供应商的 logo：优先用打包的 PNG，没有就画首字母圆底。
     *
     * <p>图标名来自 providers 的 {@code icon} 字段（{@code workbuddy-color} 这种），
     * 映射表和 PNG 都在 {@link ProviderIcons} 里。
     */
    private View providerIcon(String providerId, List<JSONObject> list, int sizeDp) {
        String icon = "";
        String preset = "";
        for (JSONObject q : list) {
            String i = NativeData.str(q, "icon", "");
            if (!i.isEmpty()) {
                icon = i;
                break;
            }
        }
        // 额度卡里没有 preset，靠 provider id 命中映射表就够
        int res = ProviderIcons.of(host, icon, providerId, preset);
        if (res != 0) {
            ImageView iv = new ImageView(host);
            iv.setImageResource(res);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return iv;
        }
        String name = NativeData.pretty(providerId);
        for (JSONObject q : list) {
            String n = NativeData.str(q, "name", "");
            if (!n.isEmpty()) {
                name = n;
                break;
            }
        }
        TextView t = ui.text(ProviderIcons.initial(name), sizeDp * 0.42f,
                M3.ON_PRIMARY_CONTAINER, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ui.round(ui.col(M3.PRIMARY_CONTAINER), sizeDp / 2f));
        return t;
    }

    // ------------------------------------------------------------------
    // 二级：账号
    // ------------------------------------------------------------------

    /**
     * 一个账号一行（缩进 + 更瘦的卡片）。
     *
     * <p>收起时两行：账号名 + 关键数字；展开后在里面摆额度窗口、重置倒计时。
     */
    private View accountRow(JSONObject q) {
        String key = accountKey(q);
        boolean expanded = Collapse.expanded(host, key, false);

        LinearLayout card = ui.column();
        // 二级卡片：左边缩进更多，右边留短一点，视觉上是「台阶」
        GradientDrawable bg = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER), 14,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.glowCard(card, bg);
        card.setPadding(ui.dp(12), ui.dp(10), ui.dp(10), ui.dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(ui.dp(INDENT_L2), ui.dp(2), ui.dp(INDENT_L1), ui.dp(2));
        card.setLayoutParams(clp);

        String providerId = NativeData.str(q, "provider", "");
        String user = NativeData.str(q, "user", "");
        String alias = NativeData.str(q, "alias", "");
        String seat = NativeData.str(q, "seat", "");
        String kind = NativeData.str(q, "kind", "");
        String title = !alias.isEmpty() ? alias : !user.isEmpty() ? user
                : !seat.isEmpty() ? seat : NativeData.pretty(providerId);
        boolean providerOff = offProviders.containsKey(providerId);

        // 关闭的账号整体置灰：卡片降不透明度，名字用次要色
        if (providerOff || !accountIsOn(q)) {
            card.setAlpha(0.45f);
        }

        // ---- 标题行：箭头 + 名字 + 摘要 + 刷新 ----
        LinearLayout head = ui.row();

        TextView arrow = ui.text("", 11, M3.ON_SURFACE_VARIANT, false);
        arrow.setGravity(Gravity.CENTER);
        head.addView(arrow, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));

        LinearLayout titleBox = ui.column();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(2);
        head.addView(titleBox, tlp);

        TextView name = ui.text(title, 13.5f,
                providerOff ? M3.ON_SURFACE_VARIANT : M3.ON_SURFACE, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleBox.addView(name);

        TextView teaser = ui.text(cardTeaser(q), 11, M3.ON_SURFACE_VARIANT, false);
        teaser.setSingleLine(true);
        teaser.setEllipsize(android.text.TextUtils.TruncateAt.END);
        teaser.setLayoutParams(ui.matchWrap(2));
        titleBox.addView(teaser);

        if (!kind.isEmpty()) {
            head.addView(ui.pill(kindName(kind), kindBg(kind), kindFg(kind)));
        }

        // 启停：订阅账号走 POST /api/login/{on|off}，key 走 POST /api/keys/{on|off}。
        // 关掉之后网关不会再往它发请求，额度也就不用再读。
        boolean on = accountIsOn(q);
        TextView toggle = ui.pill(on ? "停用" : "启用",
                on ? M3.ERROR_CONTAINER : M3.TERTIARY_CONTAINER,
                on ? M3.ON_ERROR_CONTAINER : M3.ON_TERTIARY_CONTAINER);
        toggle.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        LinearLayout.LayoutParams glp = ui.wrap();
        glp.leftMargin = ui.dp(6);
        toggle.setOnClickListener(v -> toggleAccount(providerId, user, q, toggle));
        head.addView(toggle, glp);

        TextView refresh = ui.pill("刷新", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        refresh.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        LinearLayout.LayoutParams rlp = ui.wrap();
        rlp.leftMargin = ui.dp(6);
        refresh.setOnClickListener(v -> refreshOne(providerId, user, refresh));
        head.addView(refresh, rlp);
        card.addView(head);

        // ---- 展开的明细 ----
        LinearLayout detail = ui.column();
        buildDetail(q, detail);

        Collapse.wire(host, head, new Collapse.Unit(arrow, detail, teaser, expanded), key);
        card.addView(detail);
        return card;
    }

    /** 收起时那一行：余额优先，没有余额就报最紧的额度窗口。 */
    private String cardTeaser(JSONObject q) {
        String err = NativeData.str(q, "error", "");
        if (!err.isEmpty()) {
            return "读不到：" + (err.length() > 34 ? err.substring(0, 34) + "…" : err);
        }
        String balance = NativeData.str(q, "balance", "");
        QuotaAgg.Money money = QuotaAgg.parseBalance(balance);
        StringBuilder sb = new StringBuilder();
        if (money.ok) {
            sb.append(balance);
        }
        JSONArray windows = NativeData.arr(q, "windows");
        double worst = 101;
        String worstName = "";
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = NativeData.at(windows, i);
            if (w == null || NativeData.flag(w, "unlimited")) {
                continue;
            }
            double left = 100 - Math.max(0, Math.min(100, NativeData.dec(w, "used")));
            if (left < worst) {
                worst = left;
                worstName = Zh.of(NativeData.str(w, "name", ""));
            }
        }
        if (worst <= 100) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            if (!worstName.isEmpty()) {
                sb.append(worstName).append(' ');
            }
            sb.append(NativeData.percent(worst)).append(" 剩余");
        }
        // 已用/总额：额度窗口给了 amount/limit 就直接展示
        String usedTotal = usedTotalLine(windows);
        if (!usedTotal.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("已用 ").append(usedTotal);
        }
        if (sb.length() == 0) {
            String plan = Zh.of(NativeData.str(q, "plan", ""));
            sb.append(plan.isEmpty() ? "点开看详情" : plan);
        }
        return sb.toString();
    }

    /** 已用/总额：取第一个给 amount/limit 的额度窗口，拼 "x / y 单位"。 */
    private String usedTotalLine(JSONArray windows) {
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = NativeData.at(windows, i);
            if (w == null || NativeData.flag(w, "unlimited")) {
                continue;
            }
            double amount = NativeData.dec(w, "amount");
            double limit = NativeData.dec(w, "limit");
            if (limit <= 0) {
                continue;
            }
            String unit = Zh.of(NativeData.str(w, "unit", ""));
            boolean showUnit = !unit.isEmpty();
            return shortCount(Math.max(0, amount)) + " / " + shortCount(limit)
                    + (showUnit ? " " + unit : "");
        }
        return "";
    }

    private void buildDetail(JSONObject q, LinearLayout body) {
        body.setPadding(ui.dp(22), ui.dp(8), 0, 0);

        // 副标题：套餐名 / 到期 / 来自
        StringBuilder sub = new StringBuilder();
        String plan = Zh.of(NativeData.str(q, "plan", ""));
        if (!plan.isEmpty()) {
            sub.append(plan);
        }
        String until = NativeData.str(q, "until", "");
        if (!until.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append("到期 ").append(NativeData.stamp(until));
        }
        String from = NativeData.str(q, "from", "");
        if (!from.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append("来自 ").append(from);
        }
        String shared = NativeData.str(q, "sharedBalance", "");
        if (!shared.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append("共享余额 ").append(shared);
        }
        if (sub.length() > 0) {
            TextView s = ui.text(sub.toString(), 10.5f, M3.ON_SURFACE_VARIANT, false);
            s.setLayoutParams(ui.matchWrap(4));
            s.setLineSpacing(ui.dp(1), 1f);
            body.addView(s);
        }

        String err = NativeData.str(q, "error", "");
        if (!err.isEmpty()) {
            TextView e = ui.text(err, 11.5f, M3.ERROR, false);
            e.setLayoutParams(ui.matchWrap(8));
            e.setLineSpacing(ui.dp(2), 1f);
            body.addView(e);
            if (NativeData.flag(q, "readDeferred")) {
                TextView d = ui.text("这次没有去请求供应商，用的是上次读到的值。",
                        10.5f, M3.ON_SURFACE_VARIANT, false);
                d.setLayoutParams(ui.matchWrap(4));
                body.addView(d);
            }
            body.addView(readAtLine(q));
            return;
        }

        JSONArray windows = NativeData.arr(q, "windows");

        // 额度区的小标题：先说清下面每行那两个数字是什么。
        // 用户要的是「余额 / 总额度」，所以把余额和总量先摆一行，
        // 下面的窗口行再写「剩余 / 总额度」。
        String balance = NativeData.str(q, "balance", "");
        double totalLimit = 0;
        String totalUnit = "";
        for (int i = 0; i < windows.length(); i++) {
            JSONObject w = NativeData.at(windows, i);
            if (w == null || NativeData.flag(w, "unlimited") || NativeData.flag(w, "text")) {
                continue;
            }
            double lim = NativeData.dec(w, "limit");
            if (lim > 0) {
                totalLimit += lim;
                if (totalUnit.isEmpty()) {
                    totalUnit = Zh.of(NativeData.str(w, "unit", ""));
                }
            }
        }
        if (!balance.isEmpty() || totalLimit > 0) {
            StringBuilder cap = new StringBuilder("余额 / 总额度　");
            cap.append(balance.isEmpty() ? "—" : balance);
            if (totalLimit > 0) {
                cap.append(" / ").append(shortCount(totalLimit));
                if (!totalUnit.isEmpty()) {
                    cap.append(' ').append(totalUnit);
                }
            }
            TextView t = ui.text(cap.toString(), 10.5f, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(6));
            body.addView(t);
        }

        for (int i = 0; i < windows.length(); i++) {
            JSONObject win = NativeData.at(windows, i);
            if (win == null) {
                continue;
            }
            body.addView(windowRow(win, i == 0));
        }

        if (windows.length() == 0) {
            TextView t = ui.text("这个账号没有可读的额度窗口（可能只是登录态，也可能是"
                    + "供应商暂时不给数据）。", 11, M3.ON_SURFACE_VARIANT, false);
            t.setLayoutParams(ui.matchWrap(8));
            t.setLineSpacing(ui.dp(2), 1f);
            body.addView(t);
        }

        // Codex 的重置次数：resets.count 是还剩几次，until 是它们什么时候作废
        JSONObject resets = NativeData.obj(q, "resets");
        if (resets != null) {
            int left = NativeData.num(resets, "count");
            if (left > 0) {
                String untilR = NativeData.str(resets, "until", "");
                String line = "还有 " + left + " 次手动重置可用";
                String u2 = NativeData.until(untilR);
                if (!u2.isEmpty()) {
                    line += "（" + u2 + "过期）";
                }
                TextView t = ui.text(line, 10.5f, M3.ON_TERTIARY_CONTAINER, false);
                t.setBackground(ui.round(ui.col(M3.TERTIARY_CONTAINER), 10));
                int p = ui.dp(8);
                t.setPadding(p, p / 2, p, p / 2);
                t.setLayoutParams(ui.matchWrap(8));
                body.addView(t);
            }
        }

        body.addView(readAtLine(q));
    }

    // ------------------------------------------------------------------
    // 账号启停
    // ------------------------------------------------------------------

    /**
     * 这个账号现在是开着的吗。
     *
     * <p>magpie 的额度卡本身不带「开/关」字段，状态在 {@code /api/providers}
     * 里：订阅账号是 {@code account.logins[].on}，key 是 {@code keyList[].on}。
     * 这两处都没读到（比如 providers 读失败）就当作开着 —— 宁可让用户点一下
     * 「停用」，也不要凭空把账号标成关闭。
     */
    private boolean accountIsOn(JSONObject q) {
        String provider = NativeData.str(q, "provider", "");
        String user = NativeData.str(q, "user", "");
        for (Map.Entry<String, Boolean> e : onState.entrySet()) {
            if (e.getKey().equals(provider + "|" + user)) {
                return e.getValue();
            }
        }
        // key 型：user 是掩码过的 key（sk-1…6199），按 provider 找同名的
        for (Map.Entry<String, Boolean> e : onState.entrySet()) {
            if (e.getKey().equals(provider + "|key|" + user)) {
                return e.getValue();
            }
        }
        return true;
    }

    /** provider|user → 开关状态，从 /api/providers 读回来。 */
    private final Map<String, Boolean> onState = new LinkedHashMap<>();

    /**
     * 打开 / 关掉一个账号。
     *
     * <p>看这张卡是什么类型：
     * <ul>
     *   <li>{@code user} 出现在某个 provider 的 {@code account.logins[].user}
     *       里 → 订阅账号，{@code POST /api/login/on|off} body {agent,user}</li>
     *   <li>否则当成 key，{@code POST /api/keys/on|off} body {id,ref}</li>
     * </ul>
     */
    private void toggleAccount(final String provider, final String user,
                               JSONObject card, final TextView button) {
        final boolean nowOn = accountIsOn(card);
        final boolean wantOn = !nowOn;
        button.setEnabled(false);
        button.setText("…");

        host.bg(() -> {
            // 先判断这是订阅账号还是 key
            boolean isLogin = false;
            String refFound = null;

            // 第一轮：?last=1（秒回，但可能是 null —— 从没打开过网页的供应商页时）
            MagpieApi.Result pr = MagpieApi.providersLast(host);
            if (pr.ok()) {
                String[] hit = resolveRef(pr.json(), provider, user);
                isLogin = "login".equals(hit[0]);
                refFound = hit[1];
            }
            // 第二轮：还找不到就拉全量。否则「?last=1 返回 null」会让所有 key 型
            // 供应商都启停不了（点了只弹「没在供应商列表里找到」）。
            if (!isLogin && (refFound == null || refFound.isEmpty())) {
                MagpieApi.Result full = MagpieApi.providersFull(host);
                if (full.ok()) {
                    String[] hit = resolveRef(full.json(), provider, user);
                    if ("login".equals(hit[0])) {
                        isLogin = true;
                    }
                    refFound = hit[1];
                }
            }

            String path;
            String body;
            if (isLogin) {
                path = "/api/login/" + (wantOn ? "on" : "off");
                body = "{\"agent\":\"" + esc(provider) + "\",\"user\":\""
                        + esc(user) + "\"}";
            } else {
                if (refFound == null || refFound.isEmpty()) {
                    host.post(() -> {
                        button.setEnabled(true);
                        button.setText(nowOn ? "停用" : "启用");
                        toast("这个账号没在供应商列表里找到，先去网页界面看看");
                    });
                    return;
                }
                path = "/api/keys/" + (wantOn ? "on" : "off");
                body = "{\"id\":\"" + esc(provider) + "\",\"ref\":\""
                        + esc(refFound) + "\"}";
            }
            final boolean fLogin = isLogin;
            final String fUser = user;
            final String fProvider = provider;
            MagpieApi.Result r = MagpieApi.post(host, path, body, 30_000);
            host.post(() -> {
                button.setEnabled(true);
                button.setText(wantOn ? "停用" : "启用");
                if (!r.ok()) {
                    toast("操作失败：" + r.why());
                    return;
                }
                // 本地状态先改：/api/providers 是缓存版，刷新时可能还没转过来，
                // 会让按钮看起来「点了没反应」。账本一定要立刻反映刚做的操作。
                onState.put(fLogin ? fProvider + "|" + fUser
                                : fProvider + "|key|" + fUser,
                        wantOn);
                button.setText(wantOn ? "停用" : "启用");
                button.setBackground(ui.round(
                        ui.col(wantOn ? M3.ERROR_CONTAINER : M3.TERTIARY_CONTAINER), 999));
                button.setTextColor(ui.col(wantOn
                        ? M3.ON_ERROR_CONTAINER : M3.ON_TERTIARY_CONTAINER));
                toast(wantOn ? "已启用" : "已停用，网关不再往它发请求");
                QuotaCache.reset();
                refresh(true);
            });
        });
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 在一份 {@code /api/providers} 响应里认出这张额度卡对应哪个账号。
     *
     * <p>返回 {@code [类型, ref]}：类型是 {@code login}（订阅账号，传 agent+user）
     * 或 {@code key}（API key，传 id+ref），ref 是 key 的指纹 id；认不出来就是
     * {@code ["", ""]}。
     *
     * <p>为什么不能只按 {@code masked} 匹配：key 型额度卡的 {@code user} 字段
     * 在 magpie 里取的是 {@code KeyName}（用户自己起的名，如「国模」「福利」），
     * 没有名字才是 {@code Mask(key)}。只认 masked 的话，带名字的 key 永远找不到，
     * 表现就是点了「停用」弹「这个账号没在供应商列表里找到」。
     */
    private static String[] resolveRef(JSONObject o, String provider, String user) {
        JSONArray arr = NativeData.arr(o, "providers");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = NativeData.at(arr, i);
            if (p == null || !provider.equals(NativeData.str(p, "id", ""))) {
                continue;
            }
            // 1) 订阅账号：account.logins[].user
            JSONObject acct = NativeData.obj(p, "account");
            if (acct != null) {
                JSONArray logins = NativeData.arr(acct, "logins");
                for (int j = 0; j < logins.length(); j++) {
                    JSONObject l = NativeData.at(logins, j);
                    if (l != null && user.equals(NativeData.str(l, "user", ""))) {
                        return new String[]{"login", ""};
                    }
                }
            }
            // 2) key：先按 name / masked 精确匹配
            JSONArray keys = NativeData.arr(p, "keyList");
            for (int j = 0; j < keys.length(); j++) {
                JSONObject k = NativeData.at(keys, j);
                if (k == null) {
                    continue;
                }
                String masked = NativeData.str(k, "masked", "");
                String kName = NativeData.str(k, "name", "");
                if (user.equals(masked) || user.equals(kName)) {
                    return new String[]{"key", NativeData.str(k, "id", "")};
                }
            }
            // 3) 兜底：比末尾四位（掩码是「前4…后4」，名字可能被别名/seat 覆盖）
            if (user.length() >= 4) {
                String tail = user.substring(user.length() - 4);
                for (int j = 0; j < keys.length(); j++) {
                    JSONObject k = NativeData.at(keys, j);
                    if (k == null) {
                        continue;
                    }
                    String masked = NativeData.str(k, "masked", "");
                    if (masked.length() >= 4 && masked.endsWith(tail)) {
                        return new String[]{"key", NativeData.str(k, "id", "")};
                    }
                }
            }
            // 4) 只有一个 key 的供应商：卡片没名字时直接认它
            if (keys.length() == 1) {
                JSONObject k = NativeData.at(keys, 0);
                String id = k == null ? "" : NativeData.str(k, "id", "");
                if (!id.isEmpty()) {
                    return new String[]{"key", id};
                }
            }
            break;
        }
        return new String[]{"", ""};
    }

    /** 一个额度窗口：名字 + 剩余条 + 重置倒计时。 */
    private View windowRow(JSONObject win, boolean first) {
        LinearLayout box = ui.column();
        box.setLayoutParams(ui.matchWrap(first ? 8 : 10));

        String name = Zh.of(NativeData.str(win, "name", "额度"));
        boolean unlimited = NativeData.flag(win, "unlimited");
        boolean textOnly = NativeData.flag(win, "text");
        String display = NativeData.str(win, "display", "");
        double used = NativeData.dec(win, "used");
        // remaining 字段多数卡片不填，真正的口径是 used 百分比
        double remaining = 100 - Math.max(0, Math.min(100, used));
        String reset = NativeData.str(win, "resetsAt", "");
        String runsOut = NativeData.str(win, "runsOutAt", "");
        double amount = NativeData.dec(win, "amount");
        double limit = NativeData.dec(win, "limit");
        String unit = Zh.of(NativeData.str(win, "unit", ""));

        LinearLayout head = ui.row();
        head.addView(ui.text(name, 12, M3.ON_SURFACE, false), ui.weight(1));

        String right;
        int color;
        if (unlimited) {
            right = "无限";
            color = ui.col(M3.TERTIARY);
        } else if (textOnly && !display.isEmpty()) {
            right = display;
            color = ui.col(M3.ON_SURFACE);
        } else if (limit > 0) {
            // 写「剩余 / 总额度」：用户要看的是还剩多少，不是已经花了多少。
            // 单位在窗口名里已经写过了（"积分" 配 "积分"）就别再挂一遍。
            boolean showUnit = !unit.isEmpty() && !name.contains(unit);
            right = shortCount(Math.max(0, limit - amount)) + " / " + shortCount(limit)
                    + (showUnit ? " " + unit : "");
            color = ui.quotaColor(remaining);
        } else {
            right = NativeData.percent(remaining) + " 剩余";
            color = ui.quotaColor(remaining);
        }
        TextView rightView = ui.textColor(right, 11.5f, color, true);
        rightView.setSingleLine(true);
        rightView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(rightView);
        box.addView(head);

        if (!unlimited) {
            // 条走「已用」：字上写剩余，条上画已用（越满越危险），
            // 颜色仍按剩余判定（快完了变红）。
            double usedPct = limit > 0
                    ? Math.max(0, Math.min(100, amount * 100.0 / limit))
                    : Math.max(0, Math.min(100, used));
            box.addView(ui.quotaBar(usedPct, remaining));
        }

        StringBuilder sub = new StringBuilder();
        String u = NativeData.until(reset);
        if (!u.isEmpty()) {
            sub.append(u).append("重置");
        }
        if (!runsOut.isEmpty()) {
            String ru = NativeData.until(runsOut);
            if (!ru.isEmpty()) {
                if (sub.length() > 0) {
                    sub.append(" · ");
                }
                sub.append("额度 ").append(ru).append("过期");
            }
        }
        if (sub.length() > 0) {
            TextView s = ui.text(sub.toString(), 10, M3.ON_SURFACE_VARIANT, false);
            s.setLayoutParams(ui.matchWrap(4));
            box.addView(s);
        }
        return box;
    }

    /** 大数缩写：200000000 → 200M（窄卡里必须缩，否则数字把标题顶没）。 */
    private String shortCount(double v) {
        long n = Math.round(v);
        if (n < 100_000) {
            if (v == Math.rint(v)) {
                return String.valueOf((long) v);
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

    private View readAtLine(JSONObject q) {
        String readAt = NativeData.str(q, "readAt", "");
        String asOf = NativeData.str(q, "asOf", "");
        StringBuilder sb = new StringBuilder();
        boolean last = NativeData.flag(q, "last");
        if (!readAt.isEmpty()) {
            sb.append("读取于 ").append(NativeData.stamp(readAt));
        } else if (!asOf.isEmpty()) {
            sb.append("缓存于 ").append(NativeData.stamp(asOf));
        }
        if (sb.length() == 0) {
            sb.append("供应商没有给出读取时间");
        }
        if (last) {
            sb.append(" · 最近一次被调用");
        }
        TextView t = ui.text(sb.toString(), 10, M3.ON_SURFACE_VARIANT, false);
        t.setLayoutParams(ui.matchWrap(8));
        return t;
    }

    // ------------------------------------------------------------------
    // 操作：刷新单个账号
    // ------------------------------------------------------------------

    private void refreshOne(String provider, String user, TextView button) {
        button.setText("刷新中…");
        button.setEnabled(false);
        host.bg(() -> {
            String path = "/api/usage/quotas/refresh?provider="
                    + android.net.Uri.encode(provider);
            if (user != null && !user.isEmpty()) {
                path += "&user=" + android.net.Uri.encode(user);
            }
            MagpieApi.Result r = MagpieApi.post(host, path, "{}", 30000);
            host.post(() -> {
                button.setText("刷新");
                button.setEnabled(true);
                if (!r.ok()) {
                    android.widget.Toast.makeText(host, "刷新失败：" + r.why(),
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                android.widget.Toast.makeText(host, "已重新读取，正在重画…",
                        android.widget.Toast.LENGTH_SHORT).show();
                refresh(true);
            });
        });
    }

    // ------------------------------------------------------------------
    // 空态
    // ------------------------------------------------------------------

    private View emptyState() {
        LinearLayout card = ui.cardWithMargin();
        card.addView(ui.text("还没有账号", 16, M3.ON_SURFACE, true));
        TextView t = ui.text("magpie 的账号是「登录态」：Codex、Claude Code 这些客户端登录过，"
                        + "magpie 会接手它们已经登好的那份，所以这里通常直接就有东西。"
                        + "如果真的是空的，先去网页界面里加一个供应商或登录一个客户端。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        t.setLayoutParams(ui.matchWrap(10));
        t.setLineSpacing(ui.dp(3), 1f);
        card.addView(t);

        TextView go = ui.pill("打开网页界面", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        go.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        go.setOnClickListener(v -> host.startActivity(
                new android.content.Intent(host, MainActivity.class)));
        LinearLayout row = ui.row();
        row.setLayoutParams(ui.matchWrap(14));
        row.addView(go);
        card.addView(row);
        return card;
    }

    private View howToAdd() {
        LinearLayout card = ui.cardWithMargin();
        card.addView(ui.text("原生界面的边界", 13, M3.ON_SURFACE, true));
        TextView t = ui.text("这一页只负责「看」和「重读额度」。加供应商、登录账号、改额度上限"
                        + "这些动作在网页界面里更完整，也更好操作。",
                12, M3.ON_SURFACE_VARIANT, false);
        t.setLayoutParams(ui.matchWrap(8));
        t.setLineSpacing(ui.dp(2), 1f);
        card.addView(t);
        return card;
    }

    private String kindName(String kind) {
        switch (kind) {
            case "plan":
                return "套餐";
            case "balance":
                return "余额";
            default:
                return "订阅";
        }
    }

    private int kindBg(String kind) {
        switch (kind) {
            case "plan":
                return M3.SECONDARY_CONTAINER;
            case "balance":
                return M3.TERTIARY_CONTAINER;
            default:
                return M3.PRIMARY_CONTAINER;
        }
    }

    private int kindFg(String kind) {
        switch (kind) {
            case "plan":
                return M3.ON_SECONDARY_CONTAINER;
            case "balance":
                return M3.ON_TERTIARY_CONTAINER;
            default:
                return M3.ON_PRIMARY_CONTAINER;
        }
    }
}
