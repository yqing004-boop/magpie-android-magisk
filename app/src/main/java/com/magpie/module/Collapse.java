package com.magpie.module;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * 折叠状态的小仓库。
 *
 * <p>列表页每次刷新都会把 View 整个重建一遍（没有 RecyclerView 可复用），
 * 所以「这块是展开还是收起」不能只看 View，得落在 pref 里：刷完重建时
 * 用 {@link #expanded} 问一句，就还是用户上次看到的样子。
 *
 * <p>存法很土但够用：{@code "1#key"} 表示展开，{@code "0#key"} 表示收起。
 * pref 里没这个 key 就用调用方给的默认值。
 */
final class Collapse {

    private static final String PREF = "collapse_state";

    private Collapse() {
    }

    private static Set<String> raw(Context ctx) {
        Set<String> s = ctx.getApplicationContext()
                .getSharedPreferences("magpie_prefs", Context.MODE_PRIVATE)
                .getStringSet(PREF, new HashSet<String>());
        return s == null ? new HashSet<String>() : s;
    }

    /** 这个 key 现在是展开的吗？没记录过就返回 {@code byDefault}。 */
    static boolean expanded(Context ctx, String key, boolean byDefault) {
        for (String s : raw(ctx)) {
            if (s != null && s.length() > 2 && s.charAt(1) == '#' && s.substring(2).equals(key)) {
                return s.charAt(0) == '1';
            }
        }
        return byDefault;
    }

    static void setExpanded(Context ctx, String key, boolean on) {
        Set<String> next = new HashSet<>();
        for (String s : raw(ctx)) {
            if (s == null) {
                continue;
            }
            if (s.length() > 2 && s.charAt(1) == '#' && s.substring(2).equals(key)) {
                continue;
            }
            next.add(s);
        }
        next.add((on ? "1#" : "0#") + key);
        ctx.getApplicationContext()
                .getSharedPreferences("magpie_prefs", Context.MODE_PRIVATE)
                .edit().putStringSet(PREF, next).apply();
    }

    /**
     * 一个可折叠单元：箭头 + 内容块，外加一块「展开时藏起来」的摘要行。
     *
     * <p>账号卡用它：收起时那一行 teaser 显示余额/最紧额度，展开后正文里
     * 已经有同样的数字，再把 teaser 摆着就是重复。
     */
    static final class Unit {

        final TextView arrow;
        final View body;
        final View teaser;
        boolean expanded;

        Unit(TextView arrow, View body, View teaser, boolean expanded) {
            this.arrow = arrow;
            this.body = body;
            this.teaser = teaser;
            this.expanded = expanded;
            apply();
        }

        void set(boolean on) {
            expanded = on;
            apply();
        }

        void toggle() {
            set(!expanded);
        }

        private void apply() {
            if (arrow != null) {
                arrow.setText(expanded ? "\u25BE" : "\u25B8");
            }
            if (body != null) {
                body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            }
            if (teaser != null) {
                teaser.setVisibility(expanded ? View.GONE : View.VISIBLE);
            }
        }
    }

    /**
     * 给一行标题装上折叠能力，点了就地展开/收起（不重画页面，滚动位置不跳）。
     */
    static void wire(final Context ctx, View header, final Unit unit, final String key) {
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> {
            unit.toggle();
            setExpanded(ctx, key, unit.expanded);
        });
    }
}
