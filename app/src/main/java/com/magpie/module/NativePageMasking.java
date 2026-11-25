package com.magpie.module;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脱敏页（原生 UI，底栏第 4 个 tab）：照 magpie 网页「脱敏」页 1:1 复刻。
 *
 * <p>上半是汇总卡（没让厂商看到的：总数 / 不同值数 / 请求数 / 按天柱图 /
 * top agents 分流摘要），下半是「按请求」列表。数据来自：
 * <ul>
 *   <li>{@code GET /api/masking/summary?days=} —— 汇总</li>
 *   <li>{@code GET /api/masking/records?limit=40} —— 请求列表</li>
 *   <li>{@code GET /api/state?last=1} —— 脱敏开关状态</li>
 *   <li>{@code GET /api/masking/sent?key=} —— 单条详情（厂商看到的样子）</li>
 * </ul>
 */
final class NativePageMasking extends NativePage {

    private static final int[] DAYS = {7, 30};

    private LinearLayout summaryBody;
    private LinearLayout listBody;
    private final TextView[] dayBtns = new TextView[2];
    private int days = 7;
    /** 筛选状态：空串 = 不过滤。 */
    private String filterAgent = "";
    private String filterProvider = "";
    private String filterFacet = ""; // ""=全部 / secret / rule / model / blocked
    /** 列表模式：req=按请求，val=按值。 */
    private String mode = "req";
    /** 汇总缓存（筛选项和流向图共用）。 */
    private JSONObject lastSummary;
    /** 记录分页：more 为 true 时可继续拉。 */
    private String lastKey = "";
    private boolean more;

    /**
     * agent → 模型 → 次数。summary 只给「按 agent 排行」和「按模型排行」两列，
     * 不知道谁连了谁；records 每条都同时带 agent 和 provider，聚合一次就有真实配对。
     *
     * <p>key 同时收 id 和 name（小写），画图时两边都试，避免 id/显示名不一致导致查不到。
     */
    private final java.util.Map<String, java.util.Map<String, Long>> pairs =
            new java.util.LinkedHashMap<>();

    NativePageMasking(NativeHome host) {
        super(host);
    }

    /** 从 records 聚合 agent → 模型 的真实配对。 */
    private void buildPairs(MagpieApi.Result src) {
        pairs.clear();
        try {
            if (src == null || !src.ok() || src.json() == null) {
                return;
            }
            JSONArray rs = NativeData.arr(src.json(), "records");
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = NativeData.at(rs, i);
                if (r == null) {
                    continue;
                }
                String ag = NativeData.str(r, "agent", "");
                String pv = NativeData.str(r, "provider", "");
                if (ag.isEmpty() || pv.isEmpty()) {
                    continue;
                }
                long n = Math.max(1, NativeData.lng(r, "n"));
                // agent 和 provider 都按原样 + 小写各记一份，查表时更稳
                addPair(ag, pv, n);
                addPair(ag.toLowerCase(java.util.Locale.US), pv.toLowerCase(java.util.Locale.US), n);
            }
        } catch (Exception ignored) {
        }
    }

    /** 查 agent 用过的模型表；id / name / 小写 都试一遍。 */
    private java.util.Map<String, Long> lookupPair(String id, String name) {
        if (id != null && !id.isEmpty()) {
            java.util.Map<String, Long> m = pairs.get(id);
            if (m != null) {
                return m;
            }
            m = pairs.get(id.toLowerCase(java.util.Locale.US));
            if (m != null) {
                return m;
            }
        }
        if (name != null && !name.isEmpty()) {
            java.util.Map<String, Long> m = pairs.get(name);
            if (m != null) {
                return m;
            }
            m = pairs.get(name.toLowerCase(java.util.Locale.US));
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** used 表里有没有这个 provider（id / name / 小写 都试）。 */
    private boolean pairHas(java.util.Map<String, Long> used, String id, String name) {
        if (used.containsKey(id) || used.containsKey(name)) {
            return true;
        }
        if (id != null && used.containsKey(id.toLowerCase(java.util.Locale.US))) {
            return true;
        }
        return name != null && used.containsKey(name.toLowerCase(java.util.Locale.US));
    }

    private void addPair(String ag, String pv, long n) {
        java.util.Map<String, Long> m = pairs.get(ag);
        if (m == null) {
            m = new java.util.LinkedHashMap<>();
            pairs.put(ag, m);
        }
        Long was = m.get(pv);
        m.put(pv, (was == null ? 0L : was) + n);
    }

    @Override
    String name() {
        return "脱敏";
    }

    @Override
    void load() {
        host.bg(() -> {
            MagpieApi.Result sum = MagpieApi.get(host,
                    "/api/masking/summary?days=" + days, 6000, 15000);
            MagpieApi.Result recs = MagpieApi.get(host,
                    recordsQuery(40), 6000, 15000);
            // 真实配对（agent → 模型）从记录里聚合，summary 只给两列独立排行
            MagpieApi.Result pairSrc = MagpieApi.get(host,
                    "/api/masking/records?limit=200", 6000, 20000);
            MagpieApi.Result st = MagpieApi.stateLast(host);
            // 本地模型状态（privacy-model）：现状、可选模型、下载进度
            MagpieApi.Result pm = MagpieApi.get(host, "/api/privacy-model", 6000, 15000);
            JSONObject sumJson = sum.ok() ? sum.json() : null;
            JSONObject recJson = recs.ok() ? recs.json() : null;
            // 脱敏开关的真实判定（照内核 maskOn()）：redact 开 / 个人信息开 /
            // 有自定义脱敏词，任一即视为开启；不是 settings.mask
            boolean on = false;
            JSONObject s = st.ok() ? st.json() : null;
            if (s != null) {
                JSONObject settings = s.optJSONObject("settings");
                if (settings != null) {
                    on = NativeData.flag(settings, "redact")
                            || NativeData.flag(settings, "redactPersonal")
                            || settings.optJSONArray("redactWords") != null
                            && settings.optJSONArray("redactWords").length() > 0;
                }
            }
            lastSummary = sumJson;
            buildPairs(pairSrc);
            final JSONObject fSum = sumJson;
            final JSONArray fRecs = recJson == null ? null
                    : NativeData.arr(recJson, "records");
            if (fRecs != null && fRecs.length() > 0) {
                lastKey = NativeData.str(NativeData.at(fRecs, fRecs.length() - 1), "key", "");
            } else {
                lastKey = "";
            }
            more = recJson != null && recJson.optBoolean("more", false);
            final boolean fOn = on;
            final JSONObject fSettings = s == null ? null : s.optJSONObject("settings");
            final String sumWhy = sum.ok() ? null : sum.why();
            final String recWhy = recs.ok() ? null : recs.why();
            host.post(() -> {
                rebuildKeepingScroll(() -> {
                    content.removeAllViews();
                    content.addView(summaryCard(fSum, fOn, sumWhy, fSettings));
                    content.addView(modelCard(pm));
                    content.addView(recordsCard(fRecs, recWhy));
                });
                done();
            });
        });
    }

    // ------------------------------------------------------------------
    // 本地模型卡（脱敏的 model 层）：现状 + 模型切换 + 开关
    // ------------------------------------------------------------------

    /**
     * 本地脱敏模型。上游网页这块有 UI 死锁 bug：一旦选了 Android 不支持的
     * Privacy Filter 并关闭，redactModelName 锁死在前者，前端
     * draw(m) 的 if (m.supported || m.installed) 不成立，整个选择器
     * 都不画，用户没有任何 UI 手段切回来（重装也会再锁死）。
     *
     * <p>壳这块直接读 /api/privacy-model 的 models 数组画完整选择器，
     * 不看顶层 Chosen 的 supported——这样永远能切回 AstrLink Guard
     * （Android 上唯一支持的模型，37MB，纯 Go 推理）。
     */
    private View modelCard(MagpieApi.Result pm) {
        LinearLayout card = ui.cardWithMargin();
        LinearLayout body = ui.column();
        card.addView(body);

        LinearLayout head = ui.row();
        LinearLayout titles = ui.column();
        titles.addView(ui.text("用本地模型查找", 17, M3.ON_SURFACE, true));
        TextView sub = ui.text("规则认不出的名字、地址和密码，由跑在本机的模型找出来",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        sub.setLayoutParams(ui.matchWrap(4));
        titles.addView(sub);
        head.addView(titles, ui.weight(1));
        body.addView(head);

        if (pm == null || !pm.ok()) {
            TextView e = ui.text("读不到本地模型状态："
                    + (pm == null ? "" : pm.why()), 12, M3.ERROR, false);
            e.setPadding(0, ui.dp(8), 0, 0);
            body.addView(e);
            return card;
        }
        JSONObject st = pm.json();
        boolean on = st.optBoolean("on", false);
        boolean chosen = st.optBoolean("chosen", false);
        String curId = st.optString("model", "");

        // 当前模型名 + 支持状态
        JSONArray models = NativeData.arr(st, "models");
        JSONObject cur = null;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = NativeData.at(models, i);
            if (m != null && curId.equals(m.optString("model", ""))) {
                cur = m;
                break;
            }
        }
        if (cur == null && models.length() > 0) {
            cur = NativeData.at(models, 0);
            curId = cur == null ? "" : cur.optString("model", "");
        }
        boolean curSupported = cur != null && cur.optBoolean("supported", false);
        boolean curInstalled = cur != null && cur.optBoolean("installed", false);
        boolean downloading = cur != null && cur.optBoolean("downloading", false);
        long done = cur == null ? 0 : NativeData.lng(cur, "done");
        long total = cur == null ? 0 : NativeData.lng(cur, "size");

        String modelName = modelNameOf(curId);
        String stateLine;
        if (downloading) {
            stateLine = "正在下载模型：" + NativeData.bytes(done)
                    + " / " + NativeData.bytes(total);
        } else if (!curSupported && !curInstalled) {
            stateLine = modelName + " 在此系统不可用（Android 没有它的本地推理组件），"
                    + "换下面的 AstrLink Guard 即可使用";
        } else if (curInstalled) {
            stateLine = modelName + " 已下载" + (on ? "，正在参与脱敏" : "，未开启");
        } else {
            stateLine = modelName + " 未下载（开启时自动下载 "
                    + NativeData.bytes(total) + "）";
        }
        TextView state = ui.text(stateLine, 12.5f, M3.ON_SURFACE_VARIANT, false);
        state.setLineSpacing(ui.dp(1), 1f);
        state.setLayoutParams(ui.matchWrap(6));
        body.addView(state);

        // 锁死提示：当前选中不支持的模型时，明确告诉用户这里是解药
        if (!curSupported && !curInstalled) {
            TextView fix = ui.text("如果网页上切不了模型（弹「此系统暂不支持」后" +
                            "选择器消失），在这里切换即可解开。",
                    11.5f, M3.ON_SURFACE_VARIANT, false);
            fix.setLineSpacing(ui.dp(1), 1f);
            fix.setLayoutParams(ui.matchWrap(2));
            body.addView(fix);
        }

        // 模型选择行：每个模型一张行卡，点击切换（pick）
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = NativeData.at(models, i);
            if (m == null) {
                continue;
            }
            body.addView(modelRow(m, curId.equals(m.optString("model", ""))));
        }

        // 开关行：on/off（对当前选中模型）
        final boolean fOn = on;
        final String fCurId = curId;
        TextView toggle = ui.pill(fOn ? "关闭本地模型" : "开启本地模型",
                fOn ? M3.SURFACE_CONTAINER_HIGH : M3.PRIMARY_CONTAINER,
                fOn ? M3.ON_SURFACE : M3.ON_PRIMARY_CONTAINER);
        toggle.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10));
        toggle.setLayoutParams(ui.matchWrap(10));
        toggle.setOnClickListener(v -> actModel(fOn ? "off" : "on", fCurId));
        body.addView(toggle);

        // chosen 提示：只有用户亲自开启的模型才会拦请求等它
        if (on && !chosen) {
            TextView note = ui.text("开启的是默认模型（规则之外的补充）；"
                    + "只有在这里亲自开启后，请求才会等待本地模型。",
                    11.5f, M3.ON_SURFACE_VARIANT, false);
            note.setLineSpacing(ui.dp(1), 1f);
            note.setLayoutParams(ui.matchWrap(6));
            body.addView(note);
        }
        return card;
    }

    private String modelNameOf(String id) {
        if ("astrlink-guard".equals(id)) {
            return "AstrLink Guard（中文英文，误报少，37MB）";
        }
        if ("privacy-filter".equals(id)) {
            return "Privacy Filter（OpenAI，遮代码里的普通值，1.6GB）";
        }
        return id.isEmpty() ? "未知" : id;
    }

    /** 一行模型：名字 + 状态 + 选中标记，点击 = pick。 */
    private View modelRow(JSONObject m, boolean selected) {
        String id = m.optString("model", "");
        boolean supported = m.optBoolean("supported", false);
        boolean installed = m.optBoolean("installed", false);
        boolean dl = m.optBoolean("downloading", false);

        LinearLayout row = ui.row();
        GradientDrawable rb = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 16,
                ui.col(selected ? M3.PRIMARY : M3.OUTLINE_VARIANT), 1);
        ui.glowCard(row, rb);
        row.setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(11));
        row.setLayoutParams(ui.matchWrap(6));

        LinearLayout texts = ui.column();
        texts.addView(ui.text(modelNameOf(id) + (selected ? "　✓ 当前" : ""),
                13.5f, M3.ON_SURFACE, true));
        String note;
        if (dl) {
            note = "下载中 " + NativeData.bytes(NativeData.lng(m, "done"))
                    + " / " + NativeData.bytes(NativeData.lng(m, "size"));
        } else if (!supported && !installed) {
            note = "此系统不可用（Android 无本地推理组件）";
        } else if (installed) {
            note = "已下载，可用";
        } else {
            note = "未下载 · " + NativeData.bytes(NativeData.lng(m, "size"))
                    + " · 开启时自动下载";
        }
        TextView s = ui.text(note, 11, !supported && !installed
                ? M3.ON_SURFACE_VARIANT : M3.ON_SURFACE_VARIANT, false);
        s.setLineSpacing(ui.dp(1), 1f);
        s.setLayoutParams(ui.matchWrap(2));
        texts.addView(s);
        row.addView(texts, ui.weight(1));

        TextView pick = ui.text(selected ? "当前" : "切换",
                12.5f, selected ? M3.ON_SURFACE_VARIANT : M3.PRIMARY, true);
        pick.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        pick.setOnClickListener(v -> {
            if (!selected) {
                actModel("pick", id);
            }
        });
        row.addView(pick);
        return row;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(host, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** POST /api/privacy-model：pick / on / off，完成后刷新整页。 */
    private void actModel(String doing, String model) {
        toast("正在" + ("pick".equals(doing) ? "切换模型" : doing.equals("on") ? "开启" : "关闭") + "…");
        host.bg(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("do", doing);
                if (model != null && !model.isEmpty()) {
                    body.put("model", model);
                }
                MagpieApi.Result r = MagpieApi.post(host, "/api/privacy-model",
                        body.toString(), 30000);
                host.post(() -> {
                    if (r.ok()) {
                        toast("已" + ("pick".equals(doing) ? "切换，开启时用新模型"
                                : doing.equals("on") ? "开启（未下载会先下载）" : "关闭"));
                        refresh(true);
                    } else {
                        toast("失败：" + r.why());
                    }
                });
            } catch (Exception e) {
                host.post(() -> toast("请求失败：" + e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------
    // 汇总卡
    // ------------------------------------------------------------------

    private View summaryCard(JSONObject sum, boolean on, String why,
                             JSONObject maskSettings) {
        LinearLayout card = ui.cardWithMargin();
        LinearLayout body = ui.column();
        card.addView(body);
        summaryBody = body;

        if (why != null && sum == null) {
            TextView e = ui.text("读不到脱敏汇总：" + why, 12, M3.ERROR, false);
            e.setPadding(0, ui.dp(8), 0, 0);
            body.addView(e);
            return card;
        }

        long n = sum == null ? 0 : NativeData.lng(sum, "n");
        long distinct = sum == null ? 0 : NativeData.lng(sum, "distinct");
        long requests = sum == null ? 0 : NativeData.lng(sum, "requests");

        LinearLayout head = ui.row();
        LinearLayout titles = ui.column();
        titles.addView(ui.text("没让厂商看到的", 17, M3.ON_SURFACE, true));
        TextView sub = ui.text(on
                        ? "每个请求离开这台手机前在本机脱敏，回复里再换回原样"
                        : "脱敏现在是关的：请求原样发往厂商",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        sub.setLayoutParams(ui.matchWrap(4));
        titles.addView(sub);
        head.addView(titles, ui.weight(1));
        // 「已开启/已关闭」胶囊 = 一键总开关：点击切换 settings.redact（密钥脱敏主开关），
        // 整份 settings 原样回传只改这一个字段（与内核网页 savePrefs 一致）
        TextView toggle = ui.pill(on ? "已开启" : "已关闭",
                on ? M3.TERTIARY_CONTAINER : M3.SURFACE_CONTAINER_HIGH,
                on ? M3.ON_TERTIARY_CONTAINER : M3.ON_SURFACE);
        if (maskSettings != null) {
            toggle.setOnClickListener(v -> {
                toggle.setEnabled(false);
                toggle.setText("保存中…");
                boolean next = !NativeData.flag(maskSettings, "redact");
                saveMaskPrefs(maskSettings, "redact", next, toggle);
                toggle.setEnabled(true);
            });
        }
        head.addView(toggle);
        body.addView(head);

        // 标题旁的「脱敏设置」入口：点开设置面板（开关 + 脱敏词）
        TextView settingsBtn = ui.text("脱敏设置 >", 12.5f, M3.PRIMARY, true);
        settingsBtn.setPadding(0, ui.dp(2), 0, 0);
        settingsBtn.setOnClickListener(v -> showMaskSettings());
        // 放在 head 下面一行右对齐，紧贴标题区
        LinearLayout settingsRow = ui.row();
        LinearLayout.LayoutParams srlp = ui.matchWrap(2);
        settingsRow.setLayoutParams(srlp);
        settingsRow.addView(ui.text("", 1, M3.ON_SURFACE, false), ui.weight(1));
        settingsRow.addView(settingsBtn);
        body.addView(settingsRow);

        // 周期切换：7 天 / 30 天
        LinearLayout dayRow = ui.row();
        dayRow.setLayoutParams(ui.matchWrap(10));
        dayRow.addView(ui.text("统计范围", 12, M3.ON_SURFACE_VARIANT, false), ui.weight(1));
        for (int i = 0; i < DAYS.length; i++) {
            final int idx = i;
            TextView b = ui.text(DAYS[i] + " 天", 12, M3.ON_SURFACE_VARIANT, false);
            b.setGravity(Gravity.CENTER);
            b.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
            if (days == DAYS[idx]) {
                b.setBackground(ui.roundStroke(ui.col(M3.PRIMARY_CONTAINER), 999,
                        ui.col(M3.PRIMARY), 1));
                b.setTextColor(ui.col(M3.PRIMARY));
                b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                b.setBackground(ui.round(ui.col(M3.SURFACE_CONTAINER_HIGH), 999));
            }
            b.setOnClickListener(v -> {
                days = DAYS[idx];
                refresh(true);
            });
            dayBtns[idx] = b;
            LinearLayout.LayoutParams blp = ui.wrap();
            if (i > 0) {
                blp.leftMargin = ui.dp(6);
            }
            dayRow.addView(b, blp);
        }
        body.addView(dayRow);

        // 大数字
        TextView big = ui.text(NativeData.count(n), 40, M3.ON_SURFACE, true);
        big.setTypeface(Typeface.MONOSPACE);
        body.addView(big);
        TextView cap = ui.text("处已脱敏", 13, M3.ON_SURFACE_VARIANT, false);
        cap.setPadding(0, 0, 0, ui.dp(2));
        body.addView(cap);
        LinearLayout meta = ui.row();
        meta.addView(ui.text(NativeData.count(distinct) + " 不同的值", 12.5f,
                M3.ON_SURFACE_VARIANT, false), ui.weight(1));
        meta.addView(ui.text(NativeData.count(requests) + " 请求", 12.5f,
                M3.ON_SURFACE_VARIANT, false));
        body.addView(meta);

        // 按天柱图
        JSONArray dayArr = sum == null ? null : NativeData.arr(sum, "days");
        if (dayArr != null && dayArr.length() > 0) {
            body.addView(barRow(dayArr));
            LinearLayout lbl = ui.row();
            TextView a = ui.text(firstDayLabel(dayArr), 10.5f, M3.ON_SURFACE_VARIANT, false);
            a.setPadding(0, ui.dp(3), 0, 0);
            lbl.addView(a, ui.weight(1));
            TextView b = ui.text("今天", 10.5f, M3.PRIMARY, true);
            b.setPadding(0, ui.dp(3), 0, 0);
            lbl.addView(b);
            body.addView(lbl);
        }

        // 流向图（简化桑基）：左 agent → 右 provider，连线粗细按量比例
        JSONArray agents = sum == null ? null : NativeData.arr(sum, "agents");
        JSONArray providers = sum == null ? null : NativeData.arr(sum, "providers");
        if (agents != null && agents.length() > 0 && providers != null
                && providers.length() > 0) {
            body.addView(ui.divider());
            body.addView(flowGraph(agents, providers));
            TextView flowCap = ui.text("magpie 在这台手机上脱敏", 10.5f,
                    M3.ON_SURFACE_VARIANT, false);
            flowCap.setGravity(Gravity.CENTER);
            flowCap.setPadding(0, ui.dp(6), 0, ui.dp(2));
            body.addView(flowCap);
        }

        // 各 agent 检查统计
        JSONObject by = sum == null ? null : sum.optJSONObject("by");
        if (by != null) {
            LinearLayout checks = ui.column();
            for (int i = 0; i < agents.length() && i < 4; i++) {
                JSONObject a = NativeData.at(agents, i);
                if (a == null) {
                    continue;
                }
                String name = NativeData.str(a, "name",
                        NativeData.str(a, "id", "?"));
                LinearLayout row = ui.row();
                row.addView(ui.text(NativeData.pretty(name), 12.5f,
                        M3.ON_SURFACE, false), ui.weight(1));
                row.addView(ui.text("检查了 " + NativeData.count(NativeData.lng(a, "requests"))
                                + " 个 · " + NativeData.count(NativeData.lng(a, "n"))
                                + " 个有脱敏", 11, M3.ON_SURFACE_VARIANT, false));
                checks.addView(row);
            }
            if (checks.getChildCount() > 0) {
                body.addView(ui.divider());
                body.addView(checks);
            }
        }
        return card;
    }

    /**
     * 连线调色板：每个 agent/provider 一种颜色，按索引取，循环使用。
     *
     * <p>配色取自 Material 常用色系，明暗模式都能看清；alpha 由调用方
     * 叠加（这里返回不透明 RGB）。
     */
    private int paletteColor(int i) {
        int[] pal = {
                0xFF2E7D32, // 绿
                0xFF1565C0, // 蓝
                0xFFC62828, // 红
                0xFF6A1B9A, // 紫
                0xFFEF6C00, // 橙
                0xFF00838F, // 青
                0xFFAD1457, // 品红
                0xFF4E342E, // 棕
        };
        return pal[Math.abs(i) % pal.length];
    }

    /**
     * 简化流向图：左列 agent 节点，右列 provider 节点，中间连线的粗细
     * 按该 agent 的脱敏量占比分配。自绘 Canvas，只画直线（不做曲线束）。
     *
     * <p>每个 agent 用不同颜色的连线，左侧标签前有色点对应；
     * 右侧 provider 同理，一眼能看出每条线属于谁。
     */
    private View flowGraph(JSONArray agents, JSONArray providers) {
        return new android.view.View(ui.ctx) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                android.graphics.Paint line = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                line.setStyle(android.graphics.Paint.Style.STROKE);
                android.graphics.Paint text = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                text.setColor(ui.col(M3.ON_SURFACE));
                text.setTextSize(ui.dp(12));
                android.graphics.Paint dim = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                dim.setColor(ui.col(M3.PRIMARY));
                dim.setTextSize(ui.dp(11));
                dim.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

                int w = getWidth();
                int h = getHeight();
                if (w == 0 || h == 0) {
                    return;
                }
                int padX = ui.dp(10);
                int midX = w / 2;

                // 节点纵坐标：均匀分布
                int an = Math.min(agents.length(), 5);
                int pn = Math.min(providers.length(), 5);
                // totalA/totalP 只用来判空（没有数据就不画图），
                // 线宽不再按占比变化——所有 agent 一样粗
                long totalA = 0;
                long totalP = 0;
                for (int i = 0; i < an; i++) {
                    totalA += NativeData.lng(NativeData.at(agents, i), "n");
                }
                for (int i = 0; i < pn; i++) {
                    totalP += NativeData.lng(NativeData.at(providers, i), "n");
                }
                if (totalA <= 0 || totalP <= 0) {
                    return;
                }
                float[] ay = new float[an];
                float[] py = new float[pn];
                for (int i = 0; i < an; i++) {
                    ay[i] = h * (i + 1) / (an + 1f);
                }
                for (int i = 0; i < pn; i++) {
                    py[i] = h * (i + 1) / (pn + 1f);
                }

                // 连线：一个 AI 软件 = 一种颜色，贯穿 agent → 脱敏 → 它用过的所有模型。
                // 用真实配对（pairs，从 records 聚合）决定连哪几个模型；
                // 拿不到配对就退化成「连到同名/同索引」，至少不断线。
                int magY = h / 2;
                float stroke = ui.dp(1.6f);
                line.setStrokeWidth(stroke);
                // 先算每条线的起止 x：贴着名字画（左边在 agent 名字右侧，右边在模型名字左侧）
                float aLineX = padX + ui.dp(4);
                float pLineX = w - padX - ui.dp(4);
                // 最多 5 个，每条线单独判断，不按索引强行配对
                for (int i = 0; i < an; i++) {
                    JSONObject a = NativeData.at(agents, i);
                    String agId = NativeData.str(a, "id", "");
                    String agName = NativeData.str(a, "name", agId);
                    int color = (paletteColor(i) & 0x00FFFFFF) | 0x99000000;
                    line.setColor(color);

                    // 查这个 agent 用过的模型（id 与 name 都试，忽略大小写）
                    java.util.Map<String, Long> used = lookupPair(agId, agName);

                    // 左段：agent → 脱敏（每个 agent 只画一次）
                    canvas.drawLine(aLineX, ay[i], midX, magY, line);

                    boolean drew = false;
                    if (used != null && !used.isEmpty()) {
                        for (int j = 0; j < pn; j++) {
                            JSONObject p = NativeData.at(providers, j);
                            String pId = NativeData.str(p, "id", "");
                            String pName = NativeData.str(p, "name", pId);
                            if (!pairHas(used, pId, pName)) {
                                continue;
                            }
                            // 右段：脱敏 → 该模型，同色同宽
                            canvas.drawLine(midX, magY, pLineX, py[j], line);
                            drew = true;
                        }
                    }
                    if (!drew) {
                        // 没有配对数据：退回「同索引」画法，保证图不空
                        int j = Math.min(i, pn - 1);
                        canvas.drawLine(midX, magY, pLineX, py[j], line);
                    }
                }

                // 中间 magpie 圆点
                android.graphics.Paint bird = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                bird.setColor(ui.col(M3.PRIMARY_CONTAINER));
                canvas.drawCircle(midX, magY, ui.dp(16), bird);
                bird.setColor(ui.col(M3.PRIMARY));
                bird.setStyle(android.graphics.Paint.Style.STROKE);
                bird.setStrokeWidth(ui.dp(1.5f));
                canvas.drawCircle(midX, magY, ui.dp(16), bird);

                // 标签：左 agent 名+量，右 provider 名+量
                text.setTextAlign(android.graphics.Paint.Align.LEFT);
                dim.setTextAlign(android.graphics.Paint.Align.LEFT);
                android.graphics.Paint dot = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                for (int i = 0; i < an; i++) {
                    JSONObject a = NativeData.at(agents, i);
                    String name = NativeData.str(a, "name", NativeData.str(a, "id", "?"));
                    // 色点：和连线同色，标出这条线是哪个 agent
                    dot.setColor(paletteColor(i));
                    canvas.drawCircle(padX + ui.dp(3), ay[i] - ui.dp(4), ui.dp(3), dot);
                    canvas.drawText(NativeData.pretty(name), padX + ui.dp(10),
                            ay[i] - ui.dp(2), text);
                    canvas.drawText(NativeData.count(NativeData.lng(a, "n")),
                            padX + ui.dp(10), ay[i] + ui.dp(12), dim);
                }
                for (int i = 0; i < pn; i++) {
                    JSONObject p = NativeData.at(providers, i);
                    String name = NativeData.str(p, "name", NativeData.str(p, "id", "?"));
                    float right = w - padX - ui.dp(10);
                    text.setTextAlign(android.graphics.Paint.Align.RIGHT);
                    dim.setTextAlign(android.graphics.Paint.Align.RIGHT);
                    canvas.drawText(NativeData.pretty(name), right,
                            py[i] - ui.dp(2), text);
                    canvas.drawText(NativeData.count(NativeData.lng(p, "n")),
                            right, py[i] + ui.dp(12), dim);
                    // 色点画在文字右边，颜色跟随配对的 agent（同一条线同色）
                    dot.setColor(paletteColor(i));
                    canvas.drawCircle(right + ui.dp(4), py[i] - ui.dp(4), ui.dp(3), dot);
                }
                text.setTextAlign(android.graphics.Paint.Align.CENTER);
                dim.setTextAlign(android.graphics.Paint.Align.CENTER);
                canvas.drawText("脱敏", midX, magY + ui.dp(4), dim);
            }

            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int an = Math.min(agents.length(), 5);
                int pn = Math.min(providers.length(), 5);
                int rows = Math.max(an, pn);
                setMeasuredDimension(android.view.View.MeasureSpec
                                .getSize(widthSpec),
                        Math.max(ui.dp(120), rows * ui.dp(52)));
            }
        };
    }

    /** 按天小柱图：末根（今天）高亮。 */
    private View barRow(JSONArray daysArr) {
        LinearLayout row = new LinearLayout(ui.ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56));
        lp.topMargin = ui.dp(14);
        row.setLayoutParams(lp);

        long max = 1;
        float[] vals = new float[daysArr.length()];
        for (int i = 0; i < daysArr.length(); i++) {
            JSONObject d = NativeData.at(daysArr, i);
            long v = d == null ? 0 : NativeData.lng(d, "n");
            vals[i] = v;
            max = Math.max(max, v);
        }
        for (int i = 0; i < daysArr.length(); i++) {
            View bar = new View(ui.ctx);
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(ui.dp(2));
            gd.setColor(i == daysArr.length() - 1
                    ? ui.col(M3.PRIMARY) : ui.col(M3.SURFACE_CONTAINER_HIGHEST));
            bar.setBackground(gd);
            int h = Math.max(ui.dp(2), Math.round(ui.dp(52) * vals[i] / max));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, h, 1f);
            blp.rightMargin = ui.dp(2);
            row.addView(bar, blp);
        }
        return row;
    }

    private String firstDayLabel(JSONArray daysArr) {
        JSONObject d = NativeData.at(daysArr, 0);
        if (d == null) {
            return "";
        }
        String day = NativeData.str(d, "day", NativeData.str(d, "t", ""));
        if (day.length() >= 10) {
            String[] p = day.substring(5, 10).split("-");
            return p[0] + "月" + p[1] + "日";
        }
        return "最早";
    }

    // ------------------------------------------------------------------
    // 记录列表
    // ------------------------------------------------------------------

    /** 拼接记录列表查询：limit + 筛选参数（agent/provider/facet/before）。 */
    private String recordsQuery(int limit) {
        StringBuilder q = new StringBuilder("/api/masking/records?limit=" + limit);
        if (filterAgent != null && !filterAgent.isEmpty()) {
            q.append("&agent=").append(android.net.Uri.encode(filterAgent));
        }
        if (filterProvider != null && !filterProvider.isEmpty()) {
            q.append("&provider=").append(android.net.Uri.encode(filterProvider));
        }
        if ("secret".equals(filterFacet)) {
            q.append("&kind=API_KEY,PRIVATE_KEY,TOKEN,PASSWORD,SECRET");
        } else if ("rule".equals(filterFacet) || "model".equals(filterFacet)) {
            q.append("&by=").append(filterFacet);
        } else if ("blocked".equals(filterFacet)) {
            q.append("&blocked=1");
        }
        return q.toString();
    }

    /** 筛选栏：按请求/按值 切换 + 类型筛选 + 智能体/提供方下拉。 */
    private View filterBar() {
        LinearLayout bar = new LinearLayout(ui.ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = ui.matchWrap(8);
        bar.setLayoutParams(lp);

        // 第一行：按请求 / 按值 + 类型筛选胶囊（全部/机密/规则/本地模型/拦截）。
        // 放进 HorizontalScrollView：胶囊多时能滑出来，不被右边「最近 40 条」遮挡。
        android.widget.HorizontalScrollView hs1 = new android.widget.HorizontalScrollView(ui.ctx);
        hs1.setHorizontalScrollBarEnabled(false);
        hs1.setFillViewport(true);
        LinearLayout row1 = ui.row();
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView req = modePill("按请求", "req".equals(mode));
        req.setOnClickListener(v -> { mode = "req"; refresh(true); });
        row1.addView(req);
        LinearLayout.LayoutParams rlp1 = (LinearLayout.LayoutParams) req.getLayoutParams();
        rlp1.rightMargin = ui.dp(6);
        TextView val = modePill("按值", "val".equals(mode));
        val.setOnClickListener(v -> { mode = "val"; refresh(true); });
        row1.addView(val);

        // 类型筛选：跟随网页 facet 定义
        String[][] facets = {{"", "全部"}, {"secret", "机密"},
                {"rule", "规则"}, {"model", "本地模型"}, {"blocked", "拦截"}};
        for (String[] f : facets) {
            long n = facetCount(f[0]);
            if (!f[0].isEmpty() && n == 0 && !filterFacet.equals(f[0])) {
                continue;
            }
            TextView b = modePill(f[1] + (f[0].isEmpty() || n == 0 ? "" : " " + n),
                    filterFacet.equals(f[0]));
            b.setOnClickListener(v -> {
                filterFacet = filterFacet.equals(f[0]) ? "" : f[0];
                refresh(true);
            });
            LinearLayout.LayoutParams blp = ui.wrap();
            blp.leftMargin = ui.dp(10);
            row1.addView(b, blp);
        }
        hs1.addView(row1, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(hs1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第二行：智能体 / 提供方 下拉（同样可滑）
        android.widget.HorizontalScrollView hs2 = new android.widget.HorizontalScrollView(ui.ctx);
        hs2.setHorizontalScrollBarEnabled(false);
        hs2.setFillViewport(true);
        LinearLayout row2 = ui.row();
        row2.setOrientation(LinearLayout.HORIZONTAL);
        TextView agentDrop = dropdown("智能体: " + displayName(filterAgent, "agent"),
                () -> pickFrom("agent"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = ui.dp(6);
        row2.addView(agentDrop, dlp);
        TextView provDrop = dropdown("提供方: " + displayName(filterProvider, "provider"),
                () -> pickFrom("provider"));
        row2.addView(provDrop);
        hs2.addView(row2, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(hs2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    /** 筛选胶囊样式。 */
    /**
     * 筛选胶囊（爸爸 2026-11-23 定稿样式）：
     * 选中 = 主色实心胶囊白字；未选中 = 白底描边胶囊深色字。字号加大更醒目。
     */
    private TextView modePill(String label, boolean on) {
        TextView t = ui.text(label, 13, on ? M3.ON_PRIMARY : M3.ON_SURFACE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        if (on) {
            t.setBackground(ui.round(ui.col(M3.PRIMARY), 999));
        } else {
            t.setBackground(ui.roundStroke(ui.col(M3.SURFACE), 999,
                    ui.col(M3.OUTLINE), 1));
        }
        return t;
    }

    /** 筛选下拉按钮样式。 */
    private TextView dropdown(String label, Runnable onClick) {
        TextView t = ui.text(label + " ▾", 13, M3.ON_SURFACE, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        GradientDrawable bg = ui.roundStroke(ui.col(M3.SURFACE), 999,
                ui.col(M3.OUTLINE), 1);
        t.setBackground(bg);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    /** 筛选显示名：空 = 所有。 */
    private String displayName(String id, String kind) {
        if (id == null || id.isEmpty()) {
            return "所有";
        }
        return NativeData.pretty(id);
    }

    /** 弹列表选择：智能体 / 提供方。 */
    private void pickFrom(String kind) {
        JSONArray opts = summaryOptions(kind);
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<String> vals = new java.util.ArrayList<>();
        names.add("所有");
        vals.add("");
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject o = NativeData.at(opts, i);
                if (o == null) {
                    continue;
                }
                String name = NativeData.str(o, "name", NativeData.str(o, "id", "?"));
                names.add(NativeData.pretty(name));
                vals.add(name);
            }
        }
        // 卡片式底部弹层（统一走 Ui.dialogBg 同款样式，不用系统 setItems 白板）
        android.app.Dialog dlg = new android.app.Dialog(host, R.style.Theme_Magpie_Sheet);
        LinearLayout root = ui.column();
        GradientDrawable bg = ui.round(ui.col(M3.SURFACE_CONTAINER_LOW), 28);
        bg.setCornerRadii(new float[]{
                ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(host, "navigation_bar_height"));

        root.addView(ui.text(kind.equals("agent") ? "选择智能体" : "选择提供方",
                21, M3.ON_SURFACE, true));

        android.widget.ScrollView sc = new android.widget.ScrollView(host);
        LinearLayout list = ui.column();
        for (int i = 0; i < names.size(); i++) {
            final String v = i < vals.size() ? vals.get(i) : "";
            String label = names.get(i);
            boolean selected = (kind.equals("agent") ? filterAgent : filterProvider).equals(v);
            TextView row = ui.text(label, 15,
                    selected ? M3.PRIMARY : M3.ON_SURFACE, selected);
            row.setPadding(ui.dp(12), ui.dp(13), ui.dp(12), ui.dp(13));
            GradientDrawable rbg = ui.roundStroke(
                    selected ? ui.col(M3.PRIMARY_CONTAINER) : ui.col(M3.SURFACE_CONTAINER_LOW),
                    14, ui.col(M3.OUTLINE_VARIANT), 1);
            ui.clickable(row, rbg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, ui.dp(6));
            row.setOnClickListener(vv -> {
                dlg.dismiss();
                if (kind.equals("agent")) {
                    filterAgent = v;
                } else {
                    filterProvider = v;
                }
                refresh(true);
            });
            list.addView(row, rlp);
        }
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.ui.dp(360)));

        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int maxH = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.82f);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(maxH, host.getResources().getDisplayMetrics().heightPixels));
        }
        dlg.show();
    }

    /** summary 里 agents/providers 数组。 */
    private JSONArray summaryOptions(String kind) {
        if (lastSummary == null) {
            return null;
        }
        return NativeData.arr(lastSummary, kind.equals("agent") ? "agents" : "providers");
    }

    /** facet 计数（跟网页 MASK_FACETS 一致）。 */
    private long facetCount(String id) {
        if (lastSummary == null) {
            return 0;
        }
        if (id.isEmpty()) {
            return NativeData.lng(lastSummary, "n");
        }
        if ("secret".equals(id)) {
            JSONArray kinds = NativeData.arr(lastSummary, "kinds");
            java.util.Set<String> secret = new java.util.HashSet<>(java.util.Arrays.asList(
                    "API_KEY", "PRIVATE_KEY", "TOKEN", "PASSWORD", "SECRET"));
            long n = 0;
            for (int i = 0; i < kinds.length(); i++) {
                JSONObject k = NativeData.at(kinds, i);
                if (k != null && secret.contains(NativeData.str(k, "name", k.optString("k", "")))) {
                    n += NativeData.lng(k, "n");
                }
            }
            return n;
        }
        JSONObject by = lastSummary.optJSONObject("by");
        if ("rule".equals(id) || "model".equals(id)) {
            return by == null ? 0 : NativeData.lng(by, id);
        }
        if ("blocked".equals(id)) {
            return NativeData.lng(lastSummary, "blocked");
        }
        return 0;
    }

    /** 按值视图：脱敏最多的值排行（横条 + 次数）。 */
    private View valueList() {
        LinearLayout list = ui.column();
        JSONArray values = lastSummary == null ? null
                : NativeData.arr(lastSummary, "values");
        if (values == null || values.length() == 0) {
            TextView e = ui.text("还没有脱敏值。", 12, M3.ON_SURFACE_VARIANT, false);
            e.setPadding(0, ui.dp(8), 0, 0);
            list.addView(e);
            return list;
        }
        long max = 1;
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = NativeData.at(values, i);
            if (v != null) {
                max = Math.max(max, NativeData.lng(v, "n"));
            }
        }
        int shown = 0;
        for (int i = 0; i < values.length() && shown < 20; i++) {
            JSONObject v = NativeData.at(values, i);
            if (v == null) {
                continue;
            }
            // facet 过滤：机密只显示机密类；rule/model 按 by 字段
            String kind = NativeData.str(v, "kind", "");
            String by = NativeData.str(v, "by", "");
            if ("secret".equals(filterFacet) && !isSecretKind(kind)) {
                continue;
            }
            if (("rule".equals(filterFacet) || "model".equals(filterFacet))
                    && !filterFacet.equals(by)) {
                continue;
            }
            if ("blocked".equals(filterFacet)) {
                continue;
            }
            shown++;
            list.addView(valueRow(v, max, i + 1));
        }
        if (shown == 0) {
            TextView e = ui.text("没有匹配当前筛选的值。", 12, M3.ON_SURFACE_VARIANT, false);
            e.setPadding(0, ui.dp(8), 0, 0);
            list.addView(e);
        }
        return list;
    }

    private boolean isSecretKind(String kind) {
        return java.util.Arrays.asList("API_KEY", "PRIVATE_KEY", "TOKEN",
                "PASSWORD", "SECRET").contains(kind);
    }

    /** 一行值排行：序号 + 形状摘要 + 类型 + 条 + 次数。 */
    private View valueRow(JSONObject v, long max, int index) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(2), ui.dp(7), ui.dp(2), ui.dp(7));

        TextView idx = ui.mono(String.format(java.util.Locale.US, "%02d", index),
                10.5f, M3.ON_SURFACE_VARIANT);
        idx.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(26),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(idx);

        // 形状摘要（首尾字符 + 长度），不显示原文
        String shape = maskShape(NativeData.str(v, "h", ""),
                NativeData.lng(v, "len"));
        TextView sh = ui.text(shape, 12, M3.ON_SURFACE, false);
        sh.setSingleLine(true);
        sh.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(sh, ui.weight(1));

        TextView kind = ui.text(kindName(NativeData.str(v, "kind", "")), 10.5f,
                M3.ON_SURFACE_VARIANT, false);
        kind.setPadding(ui.dp(6), 0, 0, 0);
        row.addView(kind);

        // 比例条
        View barWrap = barFill(NativeData.lng(v, "n"), max);
        row.addView(barWrap, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(6)));

        TextView n = ui.text(String.valueOf(NativeData.lng(v, "n")), 12,
                M3.PRIMARY, true);
        n.setPadding(ui.dp(8), 0, 0, 0);
        row.addView(n);
        return row;
    }

    private View barFill(long n, long max) {
        LinearLayout wrap = new LinearLayout(ui.ctx);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        View fill = new View(ui.ctx);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(ui.dp(3));
        gd.setColor(ui.col(M3.PRIMARY));
        fill.setBackground(gd);
        int w = Math.max(ui.dp(3), Math.round(ui.dp(58) * n / Math.max(1, max)));
        wrap.addView(fill, new LinearLayout.LayoutParams(w, ui.dp(6)));
        return wrap;
    }

    /** 值形状：首 2 字 + … + 末 2 字 + 长度（照内核 maskShape 语义）。 */
    private String maskShape(String head, long len) {
        String h = head == null ? "" : head;
        if (h.length() > 4) {
            h = h.substring(0, 2) + "…" + h.substring(h.length() - 2);
        }
        return h + " (" + len + ")";
    }

    private View recordsCard(JSONArray recs, String why) {
        LinearLayout card = ui.cardWithMargin();
        LinearLayout head = ui.row();
        head.addView(ui.text(mode.equals("val") ? "按值" : "按请求", 15,
                M3.ON_SURFACE, true), ui.weight(1));
        head.addView(ui.text("最近 40 条", 11, M3.ON_SURFACE_VARIANT, false));
        card.addView(head);

        // 筛选栏：按请求/按值 + 类型筛选 + 智能体/提供方下拉
        card.addView(filterBar());

        if (why != null && recs == null) {
            TextView e = ui.text("读不到脱敏记录：" + why + "（点我重试）", 12,
                    M3.ERROR, false);
            e.setPadding(0, ui.dp(8), 0, 0);
            e.setOnClickListener(v -> refresh(true));
            card.addView(e);
            return card;
        }
        if (mode.equals("val")) {
            card.addView(valueList());
            return card;
        }
        if (recs == null || recs.length() == 0) {
            TextView e = ui.text("还没有脱敏记录。", 12, M3.ON_SURFACE_VARIANT, false);
            e.setPadding(0, ui.dp(8), 0, 0);
            card.addView(e);
            return card;
        }

        String lastDay = "";
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = NativeData.at(recs, i);
            if (r == null) {
                continue;
            }
            String day = dayName(timeOf(r));
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView g = ui.text(day, 12, M3.ON_SURFACE_VARIANT, true);
                g.setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(4));
                card.addView(g);
            }
            card.addView(recordRow(r));
        }
        return card;
    }

    private String dayName(long t) {
        if (t <= 0) {
            return "";
        }
        return new java.text.SimpleDateFormat("M月d日 E",
                java.util.Locale.CHINESE).format(new java.util.Date(t));
    }

    /** 一条请求小卡：时间 · agent → 厂商 · 数量；模型 + 类型摘要。 */
    private View recordRow(JSONObject r) {
        LinearLayout card = ui.column();
        GradientDrawable bg = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 16,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.glowCard(card, bg);
        card.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(7);
        card.setLayoutParams(lp);

        // 第一行：时间 | agent → provider | 主类型胶囊 | 数量 >>
        LinearLayout l1 = ui.row();
        TextView time = ui.mono(clock(timeOf(r)), 10.5f,
                M3.ON_SURFACE_VARIANT);
        time.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        l1.addView(time);

        String agent = NativeData.str(r, "agent", "");
        String provider = NativeData.str(r, "provider", "");
        StringBuilder route = new StringBuilder();
        if (!agent.isEmpty()) {
            route.append(NativeData.pretty(agent));
        }
        route.append(" → ");
        route.append(provider.isEmpty() ? "厂商" : NativeData.pretty(provider));
        TextView rt = ui.text(route.toString(), 12, M3.ON_SURFACE, false);
        rt.setSingleLine(true);
        rt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        l1.addView(rt, ui.weight(1));

        // 类型胶囊：取量最大的那类，带色点（绿=机密，蓝=邮箱，灰=其它）
        // 若被拦截则显示红色「拦截」胶囊
        JSONArray vals = NativeData.arr(r, "values");
        String topKind = "";
        long topN = 0;
        for (int i = 0; i < vals.length(); i++) {
            JSONObject v = NativeData.at(vals, i);
            if (v == null) {
                continue;
            }
            long n = NativeData.lng(v, "n");
            if (n > topN) {
                topN = n;
                topKind = NativeData.str(v, "k", "other");
            }
        }
        boolean blocked = NativeData.flag(r, "blocked");
        TextView pill = ui.text(blocked ? "已拦截" : kindName(topKind) + " ×" + topN,
                10.5f, blocked ? M3.ON_ERROR_CONTAINER : M3.ON_SURFACE, false);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
        int dotColor = blocked ? ui.col(M3.ERROR)
                : "secret".equalsIgnoreCase(topKind) ? 0xFF2E7D32
                : "email".equalsIgnoreCase(topKind) ? 0xFF1565C0
                : 0xFF757575;
        // 用 drawableLayer 叠色点：用 「●」字符 + 着色 span 最简单
        pill.setText(("● " + pill.getText().toString()));
        android.text.SpannableString sp = new android.text.SpannableString(pill.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(dotColor), 0, 1, 0);
        sp.setSpan(new android.text.style.AbsoluteSizeSpan(9, true), 0, 1, 0);
        pill.setText(sp);
        pill.setBackground(ui.round(ui.col(M3.SURFACE_CONTAINER_HIGH), 999));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = ui.dp(6);
        l1.addView(pill, plp);

        l1.addView(ui.text(String.valueOf(NativeData.lng(r, "n")), 12.5f,
                M3.PRIMARY, true));
        card.addView(l1);

        // 第二行：模型名（网页版这行只有模型）
        String model = NativeData.str(r, "model", "");
        if (!model.isEmpty()) {
            TextView m = ui.text(model, 11, M3.ON_SURFACE_VARIANT, false);
            m.setSingleLine(true);
            m.setEllipsize(TextUtils.TruncateAt.END);
            m.setLayoutParams(ui.matchWrap(2));
            card.addView(m);
        } else if (blocked) {
            TextView m = ui.text("请求被拦下，没有发给厂商", 11,
                    M3.ON_SURFACE_VARIANT, false);
            m.setLayoutParams(ui.matchWrap(2));
            card.addView(m);
        }

        card.setOnClickListener(v -> showOne(r));
        return card;
    }

    /**
     * 解析记录时间：上游 t 是 RFC3339 字符串（"2026-11-23T18:05:12.3+08:00"），
     * 旧数据可能是毫秒数字。两种都接。
     */
    private long timeOf(JSONObject r) {
        if (r == null) {
            return 0;
        }
        Object v = r.opt("t");
        if (v instanceof Number) {
            long ms = ((Number) v).longValue();
            // 秒级时间戳（< 10^12）转毫秒
            if (ms > 0 && ms < 1000000000000L) {
                ms *= 1000;
            }
            return ms;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) {
                return 0;
            }
            java.text.SimpleDateFormat[] fmts = {
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                            java.util.Locale.US),
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
                            java.util.Locale.US),
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                            java.util.Locale.US),
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",
                            java.util.Locale.US),
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",
                            java.util.Locale.US),
            };
            for (java.text.SimpleDateFormat f : fmts) {
                try {
                    f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    java.util.Date d = f.parse(s);
                    if (d != null) {
                        return d.getTime();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private String clock(long t) {
        if (t <= 0) {
            return "--:--";
        }
        return new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.US).format(new java.util.Date(t));
    }

    private String kindName(String k) {
        if (k == null) {
            return "其它";
        }
        switch (k) {
            case "api-key":
            case "apikey":
                return "密钥";
            case "bearer":
            case "token":
                return "令牌";
            case "password":
            case "pwd":
                return "密码";
            case "email":
                return "邮箱";
            case "phone":
                return "手机号";
            case "id":
            case "idcard":
                return "证件号";
            case "card":
            case "bankcard":
                return "银行卡";
            case "url":
                return "网址";
            case "path":
                return "路径";
            case "command":
                return "命令";
            case "rule":
                return "规则词";
            case "model":
                return "本地模型";
            default:
                return k;
        }
    }

    /** 单条详情弹窗：厂商看到的样子（已脱敏全文）。 */
    private void showOne(JSONObject r) {
        final String key = NativeData.str(r, "key", "");
        if (key.isEmpty()) {
            return;
        }
        android.app.Dialog dlg = new android.app.Dialog(host, R.style.Theme_Magpie_Sheet);
        LinearLayout root = ui.column();
        GradientDrawable bg = ui.round(ui.col(M3.SURFACE_CONTAINER_LOW), 28);
        bg.setCornerRadii(new float[]{
                ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(host, "navigation_bar_height"));

        root.addView(ui.text("脱敏详情", 21, M3.ON_SURFACE, true));
        TextView sub = ui.text(
                NativeData.pretty(NativeData.str(r, "agent", "")) + " → "
                        + (NativeData.str(r, "provider", "").isEmpty()
                        ? "厂商" : NativeData.pretty(NativeData.str(r, "provider", ""))),
                12.5f, M3.ON_SURFACE_VARIANT, false);
        sub.setPadding(ui.dp(4), ui.dp(2), 0, ui.dp(10));
        root.addView(sub);

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout body = ui.column();
        sc.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView loading = ui.text("正在读取原文…", 12, M3.ON_SURFACE_VARIANT, false);
        body.addView(loading);

        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int maxH = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.88f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(maxH, host.getResources().getDisplayMetrics().heightPixels));
        }
        dlg.show();

        host.bg(() -> {
            MagpieApi.Result sent = MagpieApi.get(host,
                    "/api/masking/sent?key=" + android.net.Uri.encode(key),
                    8000, 15000);
            String shown = null;
            if (sent.ok() && sent.body != null) {
                try {
                    JSONObject o = new JSONObject(sent.body);
                    shown = o.has("body") ? o.getString("body") : sent.body;
                } catch (Exception ignored) {
                    shown = sent.body;
                }
            }
            final String fShown = shown == null ? null
                    : (shown.length() > 4000
                    ? shown.substring(0, 4000) + "\n…（已截断，网页里可看全文）" : shown);
            final String err = sent.ok() ? null : sent.why();
            host.post(() -> {
                body.removeAllViews();
                if (fShown == null) {
                    body.addView(ui.text("读不到厂商收到的内容：" + err, 12, M3.ERROR, false));
                    return;
                }
                body.addView(ui.text("厂商看到的样子（已脱敏）", 13.5f, M3.ON_SURFACE, true));
                TextView t = ui.mono(fShown, 10.5f, M3.ON_SURFACE_VARIANT);
                t.setTextIsSelectable(true);
                t.setLayoutParams(ui.matchWrap(8));
                body.addView(t);
            });
        });
    }

    // ------------------------------------------------------------------
    // 脱敏设置面板（开关 + 脱敏词）
    // ------------------------------------------------------------------

    /**
     * 设置面板：主开关 / 个人信息两个开关 + 脱敏词增删。
     *
     * <p>保存走 POST /api/settings（整份 prefs 原样回传、只改目标字段），
     * 与网页 savePrefs 的行为一致——这是内核唯一接受写设置的方式，
     * 不带全量会把其它设置清掉。
     */
    private void showMaskSettings() {
        android.app.Dialog dlg = new android.app.Dialog(host, R.style.Theme_Magpie_Sheet);
        LinearLayout root = ui.column();
        GradientDrawable bg = ui.round(ui.col(M3.SURFACE_CONTAINER_LOW), 28);
        bg.setCornerRadii(new float[]{
                ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(host, "navigation_bar_height"));

        root.addView(ui.text("脱敏设置", 21, M3.ON_SURFACE, true));
        TextView sub = ui.text("改完立刻保存到 magpie，下一个请求就生效", 12,
                M3.ON_SURFACE_VARIANT, false);
        sub.setPadding(ui.dp(4), ui.dp(2), 0, ui.dp(10));
        root.addView(sub);

        LinearLayout rows = ui.column();
        root.addView(rows);

        TextView status = ui.text("", 11.5f, M3.ON_SURFACE_VARIANT, false);
        status.setPadding(ui.dp(4), ui.dp(8), 0, 0);

        // 先拉当前 settings，再画
        TextView loading = ui.text("正在读取设置…", 12, M3.ON_SURFACE_VARIANT, false);
        rows.addView(loading);

        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int maxH = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.82f);
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(maxH, host.getResources().getDisplayMetrics().heightPixels));
        }
        dlg.show();

        host.bg(() -> {
            MagpieApi.Result st = MagpieApi.stateLast(host);
            JSONObject settings = null;
            if (st.ok() && st.json() != null) {
                settings = st.json().optJSONObject("settings");
            }
            final JSONObject fSettings = settings;
            final String err = st.ok() ? null : st.why();
            host.post(() -> {
                rows.removeAllViews();
                if (fSettings == null) {
                    TextView e = ui.text("读不到设置：" + err, 12, M3.ERROR, false);
                    e.setPadding(0, ui.dp(8), 0, 0);
                    rows.addView(e);
                    return;
                }
                drawMaskSettings(dlg, rows, status, fSettings);
            });
        });
    }

    /** 设置面板内容：两个开关行 + 脱敏词列表 + 新增输入。 */
    private void drawMaskSettings(android.app.Dialog dlg, LinearLayout rows,
                                  TextView status, JSONObject settings) {
        // ---- 开关行：主开关 ----
        rows.addView(toggleRow("密钥脱敏",
                "API Key、私钥、Token 和密码以占位符发给厂商，回复里再还原",
                NativeData.flag(settings, "redact"),
                on -> saveMaskPrefs(settings, "redact", on, status)));

        // ---- 开关行：个人信息 ----
        rows.addView(toggleRow("脱敏个人信息",
                "邮箱、手机号、身份证号和银行卡号也一并脱敏",
                NativeData.flag(settings, "redactPersonal"),
                on -> saveMaskPrefs(settings, "redactPersonal", on, status)));

        // ---- 脱敏词 ----
        LinearLayout wordsHead = ui.row();
        LinearLayout.LayoutParams hlp = ui.matchWrap(10);
        hlp.topMargin = ui.dp(10);
        wordsHead.setLayoutParams(hlp);
        LinearLayout wt = ui.column();
        wt.addView(ui.text("脱敏词", 13.5f, M3.ON_SURFACE, true));
        TextView wh = ui.text("不想让厂商看到的词，发出去前换掉，回复里还原", 11,
                M3.ON_SURFACE_VARIANT, false);
        wt.addView(wh);
        wordsHead.addView(wt, ui.weight(1));
        TextView add = ui.pill("添加", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        add.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        wordsHead.addView(add);
        rows.addView(wordsHead);

        LinearLayout wordList = ui.column();
        rows.addView(wordList);
        android.widget.EditText input = new android.widget.EditText(host);
        input.setSingleLine(true);
        input.setHint("输入一个词");
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        input.setTextColor(ui.col(M3.ON_SURFACE));
        input.setHintTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(ui.col(M3.SURFACE_CONTAINER_HIGHEST));
        ibg.setCornerRadius(ui.dp(12));
        ibg.setStroke(ui.dp(1), ui.col(M3.OUTLINE_VARIANT));
        input.setBackground(ibg);
        input.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams ilp = ui.matchWrap(6);
        input.setLayoutParams(ilp);
        input.setVisibility(android.view.View.GONE);
        rows.addView(input);

        // 画现有脱敏词
        Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            wordList.removeAllViews();
            org.json.JSONArray words = settings.optJSONArray("redactWords");
            if (words == null || words.length() == 0) {
                TextView none = ui.text("还没有脱敏词", 12, M3.ON_SURFACE_VARIANT, false);
                none.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(6));
                wordList.addView(none);
                return;
            }
            for (int i = 0; i < words.length(); i++) {
                final String word = words.optString(i, "");
                if (word.isEmpty()) {
                    continue;
                }
                LinearLayout row = ui.row();
                GradientDrawable rb = ui.round(ui.col(M3.SURFACE_CONTAINER), 12);
                row.setBackground(rb);
                row.setPadding(ui.dp(12), ui.dp(8), ui.dp(8), ui.dp(8));
                LinearLayout.LayoutParams rlp = ui.matchWrap(4);
                row.setLayoutParams(rlp);
                TextView tv = ui.text(word, 12.5f, M3.ON_SURFACE, false);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                row.addView(tv, ui.weight(1));
                TextView del = ui.text("删除", 11.5f, M3.ERROR, true);
                del.setPadding(ui.dp(8), ui.dp(4), ui.dp(4), ui.dp(4));
                del.setOnClickListener(v -> {
                    org.json.JSONArray next = new org.json.JSONArray();
                    for (int j = 0; j < words.length(); j++) {
                        String w = words.optString(j, "");
                        if (!w.isEmpty() && !w.equals(word)) {
                            next.put(w);
                        }
                    }
                    try {
                        settings.put("redactWords", next);
                    } catch (Exception ignored) {
                    }
                    saveMaskPrefs(settings, null, false, status);
                    redraw[0].run();
                });
                row.addView(del);
                wordList.addView(row);
            }
        };
        redraw[0].run();

        add.setOnClickListener(v -> {
            boolean showing = input.getVisibility() == android.view.View.VISIBLE;
            input.setVisibility(showing ? android.view.View.GONE : android.view.View.VISIBLE);
            if (!showing) {
                input.requestFocus();
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            String word = input.getText().toString().trim();
            if (word.isEmpty()) {
                return false;
            }
            org.json.JSONArray words = settings.optJSONArray("redactWords");
            org.json.JSONArray next = new org.json.JSONArray();
            if (words != null) {
                for (int j = 0; j < words.length(); j++) {
                    String w = words.optString(j, "");
                    if (!w.isEmpty()) {
                        next.put(w);
                    }
                }
            }
            next.put(word);
            try {
                settings.put("redactWords", next);
            } catch (Exception ignored) {
            }
            input.setText("");
            input.setVisibility(android.view.View.GONE);
            saveMaskPrefs(settings, null, false, status);
            redraw[0].run();
            return true;
        });
    }

    /** 一个开关行：标题 + 说明 + 右侧开关胶囊。 */
    private View toggleRow(String title, String sub, boolean on,
                           java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = ui.row();
        GradientDrawable rb = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER_LOW), 16,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.glowCard(row, rb);
        row.setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(11));
        LinearLayout.LayoutParams lp = ui.matchWrap(8);
        row.setLayoutParams(lp);

        LinearLayout texts = ui.column();
        texts.addView(ui.text(title, 14, M3.ON_SURFACE, true));
        TextView s = ui.text(sub, 11, M3.ON_SURFACE_VARIANT, false);
        s.setLineSpacing(ui.dp(1), 1f);
        s.setLayoutParams(ui.matchWrap(2));
        texts.addView(s);
        row.addView(texts, ui.weight(1));

        TextView t = ui.pill(on ? "开" : "关",
                on ? M3.TERTIARY_CONTAINER : M3.SURFACE_CONTAINER_HIGH,
                on ? M3.ON_TERTIARY_CONTAINER : M3.ON_SURFACE);
        t.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        t.setOnClickListener(v -> onChange.accept(!on));
        row.addView(t);
        return row;
    }

    /**
     * 保存脱敏设置：POST /api/settings，整份 settings 原样回传、只改目标字段。
     * 与内核网页的 savePrefs 行为一致。
     */
    private void saveMaskPrefs(JSONObject settings, String key, boolean on,
                               TextView status) {
        if (key != null) {
            try {
                settings.put(key, on);
            } catch (Exception ignored) {
            }
        }
        status.setText("保存中…");
        host.bg(() -> {
            MagpieApi.Result r = MagpieApi.post(host, "/api/settings",
                    settings.toString(), 20000);
            host.post(() -> {
                if (r.ok()) {
                    status.setText("已保存，下一个请求生效");
                    refresh(true); // 刷新开关状态胶囊
                } else {
                    status.setText("保存失败：" + r.why());
                }
            });
        });
    }
}
