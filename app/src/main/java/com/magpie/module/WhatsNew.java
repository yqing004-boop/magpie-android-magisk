package com.magpie.module;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.graphics.drawable.GradientDrawable;

/**
 * 「版本更新后弹窗说明更新内容」。
 *
 * <p>实现：本版更新说明写死在 {@link #NOTES}（按 versionCode 索引）；
 * 首次启动（NativeHome onCreate）时比较当前 versionCode 和 prefs 里记的
 * 「上次弹过的 versionCode」，升级过（更大）就弹一次，弹完记下。
 * 降级/重装不弹（code 更小或相等）。用户点「不再显示」也记下，本版不再弹。
 *
 * <p>发新版时只要往 {@link #NOTES} 里加一条新 versionCode 的说明即可。
 */
final class WhatsNew {

    private static final String PREF = "whatsnew";
    private static final String KEY_SHOWN = "shownCode";

    private WhatsNew() {
    }

    /**
     * 每版的更新说明。key = versionCode。
     * <p>发版纪律：新版本的说明要在构建前就加进来（改这里的同事改
     * app/build.gradle 的 versionCode）。
     */
    private static final android.util.SparseArray<String[]> NOTES = new android.util.SparseArray<>();

    static {
        // 1 / 1.0.0 —— 模块版首发
        NOTES.put(1, new String[]{
                "模块版",
                "• 内核不再打包进 APK：改由 Magisk 模块（magpie_native）提供，开机自启、watchdog 保活，App 被杀也不影响网关",
                "• APK 从 99MB 降到几百 KB",
                "• 界面直连本机 3425，网页后台 key 从模块的 webkey.txt 读，重启不再变",
                "• ⋮ → 模块信息与自检：一眼看出「模块没装 / 没启动 / 读不到」是哪一环",
                "• 与老版 com.magpie.android 完全独立，两个包可以同时装，互不干扰",
        });
        // 198 / 1.8.18
        NOTES.put(198, new String[]{
                "修复",
                "• 文件管理器的关闭/返回按钮改成界面内自绘卡片按钮：不再依赖系统对话框按钮（MIUI 上两次出现消失问题），这次一定显示",
        });
        // 197 / 1.8.17
        NOTES.put(197, new String[]{
                "修复",
                "• 文件管理器的「关闭/返回上级」按钮消失了——上一版去掉占位按钮导致按钮区空白，已修",
                "• 更新弹窗改为跨版本聚合：如果你从更早的版本直接升上来（中间几版没装过），会把中间所有版本的更新说明一起列出",
        });
        // 196 / 1.8.16
        NOTES.put(196, new String[]{
                "新功能",
                "• 缓存清理改勾选式：想清哪些打勾，不清的保留",
                "• 缓存清理支持定时自动清理：默认关，间隔自定义（1-90 天），只清可再生项不碰生成的图",
                "• 缓存扫描范围扩大：AI 生成的图片/视频、识图缓存、插件工作目录、图标缓存都纳入",
                "• 文件管理器「关闭」改两态：根目录显示关闭，子目录显示「返回上级」且真正逐级返回（之前层级会叠着）",
        });
        // 195 / 1.8.15
        NOTES.put(195, new String[]{
                "修复",
                "• 壳读签到开关改为「有效值」：插件层显式值优先（对齐 magpie 自己的 PluginCheckinOn），网页关掉下面那行，壳这边立刻跟着变",
                "• 弹层补齐插件型供应商的签到开关（之前只显示内置四家）",
                "• 供应商卡签到按钮不再因为账号清单读空而消失",
        });
        // 194 / 1.8.14
        NOTES.put(194, new String[]{
                "修复",
                "• 壳的自动签到开关与 magpie 网页后台双向同步：内置四家（WorkBuddy/Trae CN/MiniMax/Qoder）有供应商级和插件层两层开关，现在壳切换时两层一起写，网页那边立刻看到一致状态；网页改任何一层，壳这边 40 秒内同步",
        });
        // 193 / 1.8.13
        NOTES.put(193, new String[]{
                "调整",
                "• 取消页面读取时的「正在读取」浮条：下拉刷新保留它自己的转圈，页面不再叠指示",
                "• 自动签到判定再收紧：账号级开关（网页里把某账号设为「不参与签到」）也算——有一个没开就是未开启",
        });
        // 192 / 1.8.12
        NOTES.put(192, new String[]{
                "调整",
                "• 读取浮条去掉灰色挡条，只剩转圈和「正在读取…」",
                "• 自动签到判定改口径：全部供应商都开启才算「已开启自动签到」，任何一个没开都显示未开启",
                "• 弹层里切换开关后，头部状态点立即同步",
        });
        // 191 / 1.8.11
        NOTES.put(191, new String[]{
                "新功能",
                "• AI 排查弹窗新增「AI排查复制」：一键复制排查话术+链接，粘给 AI 就能自动排查",
                "• 签到状态两态化：头部「自动签到 / 已开启自动签到」、供应商卡「签到 / 已签到」，带红绿状态点",
                "修复",
                "• 读取数据不再整页下拉：改为顶部「转圈+正在读取」浮条，页面内容原地不动",
                "• 修复 WorkBuddy 供应商卡签到按钮不显示（上游 by 字段为空串未匹配）",
        });
        // 190 / 1.8.10：修第四格零宽（签到入口看不见的真因）
        NOTES.put(190, new String[]{
                "修复",
                "• 账号页「签到」入口看不见的 bug：竖排容器里误用了权重布局，宽度被压成 0",
                "• 现在头部卡片右侧能看到主色的「签到」和灰色的「自动签到 ›」了",
        });
        // 189 / 1.8.9
        NOTES.put(189, new String[]{
                "新功能",
                "• 账号页签到：头部「自动签到」入口 + 每家供应商卡片右侧「签到」按钮",
                "• 脱敏页「已开启」胶囊变成一键总开关：点击直接切换密钥脱敏",
                "• 脱敏筛选、文件操作、更新镜像的选择弹窗改成卡片式（带边框底色）",
                "• 版本更新弹窗（就是你现在看到的这个）",
                "• 关于页版本号后面带 code 数字，方便确认装的是哪个版本",
        });
        // 188 / 1.8.8
        NOTES.put(188, new String[]{
                "新功能",
                "• 账号页新增「自动签到」：每家供应商一个开关，打开后 magpie 每天自动签到领积分",
                "• 供应商卡片右侧新增「签到」按钮：今天没签的一键签到，签完变「已签到」",
                "• 版本更新弹窗（就是你现在看到的这个）",
                "内核",
                "• 内核升级到上游 v0.1.4738（官方 Android 构建，落后一年多的更新一次补齐）",
                "样式",
                "• 全部弹窗统一为卡片样式：玻璃白底 + 主色发光描边，按钮改主色描边胶囊",
        });
        // 186 / 1.8.6
        NOTES.put(186, new String[]{
                "新功能",
                "• AI 排查端口：菜单 → 控制台 → AI 排查端口，把地址粘给 AI 软件即可自动排查问题",
                "• 弹窗与按钮全面卡片化",
        });
    }

    /**
     * 首页 onCreate 时调：升级过来就弹一次。
     *
     * @return true 弹了（或正在弹），false 无需弹
     */
    static boolean maybeShow(NativeHome host) {
        int code = versionCode(host);
        if (code <= 0) {
            return false;
        }
        SharedPreferences sp = host.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int shown = sp.getInt(KEY_SHOWN, 0);
        // 只在「升级」时弹：首装（shown==0 且已有多版历史）不弹旧说明，
        // 降级不弹，重装同版不弹
        if (shown >= code) {
            return false;
        }
        // 首装用户（从未记过）不弹——他们没有「升级」的概念
        if (shown == 0) {
            sp.edit().putInt(KEY_SHOWN, code).apply();
            return false;
        }
        // 跨版本聚合：把「上一个已发布正式版 + 1」到当前 code 的所有说明合并。
        // 比如 GitHub 正式版只到 1.1（code 110），用户手机从 110 直接升到 160，
        // 就把 111~160 的说明全部列出——不然中间几版的更新用户永远看不到。
        StringBuilder merged = new StringBuilder();
        boolean any = false;
        for (int c = shown + 1; c <= code; c++) {
            String[] part = NOTES.get(c);
            if (part == null) {
                continue;
            }
            if (any) {
                merged.append("\n");
            }
            for (String line : part) {
                merged.append(line).append('\n');
            }
            any = true;
        }
        if (!any) {
            // 中间没有任何说明（跳版太远）也不弹
            sp.edit().putInt(KEY_SHOWN, code).apply();
            return false;
        }
        String[] notes = merged.toString().split("\n");
        {
            String[] trimmed = new String[notes.length - (notes.length > 0 && notes[notes.length-1].isEmpty() ? 1 : 0)];
            System.arraycopy(notes, 0, trimmed, 0, trimmed.length);
            notes = trimmed;
        }

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (host.getResources().getDisplayMetrics().density * 18);
        box.setPadding(pad, host.ui.dp(4), pad, host.ui.dp(4));

        TextView ver = host.ui.text("已更新到 " + versionName(host), 16,
                M3.ON_SURFACE, true);
        ver.setPadding(0, 0, 0, host.ui.dp(6));
        box.addView(ver);

        ScrollView sc = new ScrollView(host);
        LinearLayout list = host.ui.column();
        int sectionColor = host.ui.col(M3.PRIMARY);
        for (String line : notes) {
            boolean isSection = line.startsWith("•") == false;
            TextView t = new TextView(host);
            t.setText(line);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, isSection ? 13 : 13.5f);
            t.setTextColor(isSection ? sectionColor : host.ui.col(M3.ON_SURFACE));
            t.setTypeface(null, isSection ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            t.setLineSpacing(host.ui.dp(2), 1f);
            t.setPadding(0, host.ui.dp(isSection ? 8 : 2), 0, 0);
            if (!isSection) {
                t.setPadding(host.ui.dp(6), 0, 0, 0);
            }
            list.addView(t);
        }
        sc.addView(list);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, host.ui.dp(320)));

        AlertDialog dlg = new AlertDialog.Builder(host)
                .setTitle("更新说明")
                .setView(box)
                .setPositiveButton("开始使用", null)
                .setCancelable(true)
                .create();
        host.styleDialogPublic(dlg);
        dlg.show();
        sp.edit().putInt(KEY_SHOWN, code).apply();
        return true;
    }

    private static int versionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
