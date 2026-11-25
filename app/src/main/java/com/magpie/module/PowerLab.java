package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * 省电策略实验室：只读诊断 + 可选的 root 一键修复。
 *
 * <p>为什么需要这个：MIUI 的 GreezeManager 会在 App 退到后台约 35 秒后
 * 冻结整个 uid（`cgroup.freeze=1`、`reason: greeze`），网关端口还在监听
 * 但一行代码都不执行，AI 智能体就卡在「正在思考」。实测把 App 设成
 * 「无限制」后不再冻结。
 *
 * <p>MIUI 把每应用的省电策略存在它自己的数据库里：
 * <pre>
 * /data/data/com.miui.powerkeeper/databases/user_configure.db
 *   userTable(pkgName, bgControl)
 *   bgControl = 'noRestrict'  → 界面上的「无限制」
 *   bgControl = 'miuiAuto'    → 「智能限制」（默认，会被 greeze 冻）
 * </pre>
 * 这是 AOSP 白名单（{@code isIgnoringBatteryOptimizations}）之外的另一层，
 * 标准 API 碰不到，所以只能靠 root 写库，或者用户手点设置页。
 *
 * <p>面板分三块：
 * <ol>
 *   <li>只读诊断：当前策略、AOSP 白名单、MILLET 名单</li>
 *   <li>可复制的 adb / Shizuku 命令（没 root 也能用）</li>
 *   <li>root 一键设「无限制」（改系统数据库，会先弹确认）</li>
 * </ol>
 */
final class PowerLab {

    private static final String TAG = "MagpiePowerLab";

    /** MIUI 省电策略数据库。 */
    private static final String PKG = "com.miui.powerkeeper";
    private static final String DB_PATH =
            "/data/data/" + PKG + "/databases/user_configure.db";

    /** 界面上的「无限制」。 */
    private static final String NO_RESTRICT = "noRestrict";

    private PowerLab() {
    }

    // ==================================================================
    // 面板
    // ==================================================================

    static void show(final Activity act, final Ui ui) {
        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(18);
        root.setPadding(pad, ui.dp(6), pad, pad);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 标题 ----
        root.addView(ui.text("省电策略实验室", 17, M3.ON_SURFACE, true));

        TextView intro = ui.text("小米 / 澎湃OS 会在后台约 35 秒后冻结整个 App，"
                        + "网关端口还在但不执行代码。设成「无限制」后不再冻结。"
                        + "这里能看到系统里到底记的是什么。",
                12, M3.ON_SURFACE_VARIANT, false);
        intro.setLineSpacing(ui.dp(2), 1f);
        intro.setLayoutParams(ui.matchWrap(8));
        root.addView(intro);

        // ---- 一、只读诊断 ----
        root.addView(sectionTitle(act, ui, "当前状态"));

        final LinearLayout statusBox = ui.column();
        statusBox.setLayoutParams(ui.matchWrap(4));
        root.addView(statusBox);

        TextView refresh = ui.pill("重新读取", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        refresh.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        LinearLayout.LayoutParams rlp = ui.matchWrap(10);
        refresh.setLayoutParams(rlp);
        root.addView(refresh);

        // ---- 二、adb / Shizuku 命令 ----
        root.addView(sectionTitle(act, ui, "adb / Shizuku 命令"));

        TextView cmdHint = ui.text("没有 root 时，用 adb 或 Shizuku 执行下面这些。"
                        + "Shizuku 的「终端」里直接粘贴即可。",
                12, M3.ON_SURFACE_VARIANT, false);
        cmdHint.setLineSpacing(ui.dp(2), 1f);
        cmdHint.setLayoutParams(ui.matchWrap(4));
        root.addView(cmdHint);

        final String diagCmd =
                "settings get system MILLET_NO_RESTRICT_APP\n"
                        + "dumpsys deviceidle whitelist | grep com.magpie.module\n"
                        + "dumpsys power | grep -i \"magpie\"";
        root.addView(cmdBlock(act, ui, "诊断（只读）", diagCmd));

        final String fixCmd =
                "settings put system MILLET_NO_RESTRICT_APP \"$(settings get system "
                        + "MILLET_NO_RESTRICT_APP),com.magpie.module\"";
        root.addView(cmdBlock(act, ui, "把包名加进 MILLET 名单（需 ADB/Shizuku）", fixCmd));

        TextView fixNote = ui.text("注意：MILLET 名单只是镜像，真正的开关是 MIUI 省电策略里的"
                        + "「无限制」。上面这条命令不一定生效，只作为没 root 时的尝试。",
                11, M3.ON_SURFACE_VARIANT, false);
        fixNote.setLineSpacing(ui.dp(2), 1f);
        fixNote.setLayoutParams(ui.matchWrap(4));
        root.addView(fixNote);

        // ---- 三、Shizuku ----
        root.addView(sectionTitle(act, ui, "Shizuku（免 root 授权）"));

        TextView szHint = ui.text("装了 Shizuku 并授权后，壳能以 shell 或 root 身份执行命令。"
                        + "如果 Shizuku 是用 root 启动的，就能直接改 MIUI 省电策略。",
                12, M3.ON_SURFACE_VARIANT, false);
        szHint.setLineSpacing(ui.dp(2), 1f);
        szHint.setLayoutParams(ui.matchWrap(4));
        root.addView(szHint);

        final LinearLayout szBox = ui.column();
        szBox.setLayoutParams(ui.matchWrap(6));
        root.addView(szBox);

        LinearLayout szBtns = ui.row();
        szBtns.setLayoutParams(ui.matchWrap(8));
        TextView szAuth = ui.pill("Shizuku 授权", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        szAuth.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        szBtns.addView(szAuth);
        TextView szOpen = ui.pill("打开 Shizuku", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        szOpen.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        LinearLayout.LayoutParams solp = ui.wrap();
        solp.leftMargin = ui.dp(8);
        szBtns.addView(szOpen, solp);
        root.addView(szBtns);

        // ---- 四、root 一键修复 ----
        root.addView(sectionTitle(act, ui, "一键设为「无限制」"));

        TextView rootHint = ui.text("直接改 MIUI 的省电策略数据库，等于替你在设置里点了「无限制」。"
                        + "改完会尝试重启 PowerKeeper 让它重读。",
                12, M3.ON_SURFACE_VARIANT, false);
        rootHint.setLineSpacing(ui.dp(2), 1f);
        rootHint.setLayoutParams(ui.matchWrap(4));
        root.addView(rootHint);

        TextView fix = ui.pill("设为无限制", M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER);
        fix.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        fix.setLayoutParams(ui.matchWrap(10));
        root.addView(fix);

        // ---- 四、打开系统设置 ----
        TextView openSettings = ui.pill("打开系统省电设置", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        openSettings.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        openSettings.setLayoutParams(ui.matchWrap(8));
        root.addView(openSettings);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        styleDialog(act, ui, dlg);
        dlg.show();

        // ---- 行为 ----
        Runnable load = () -> {
            loadStatus(act, ui, statusBox);
            loadShizuku(act, ui, szBox);
        };
        refresh.setOnClickListener(v -> load.run());
        load.run();

        szAuth.setOnClickListener(v -> {
            if (!ShizukuShell.available()) {
                toast(act, "Shizuku 未运行，请先安装并启动 Shizuku");
                ShizukuShell.openApp(act);
                return;
            }
            toast(act, "正在请求 Shizuku 授权…");
            ShizukuShell.request(act, granted -> act.runOnUiThread(() -> {
                toast(act, granted ? "Shizuku 已授权" : "授权被拒绝");
                load.run();
            }));
        });

        szOpen.setOnClickListener(v -> ShizukuShell.openApp(act));

        openSettings.setOnClickListener(v -> {
            try {
                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                toast(act, "打不开系统设置：" + e.getMessage());
            }
        });

        fix.setOnClickListener(v -> confirmAndFix(act, ui, dlg, statusBox));
    }

    private static TextView sectionTitle(Activity act, Ui ui, String s) {
        TextView t = ui.text(s, 12.5f, M3.PRIMARY, true);
        t.setLayoutParams(ui.matchWrap(14));
        return t;
    }

    /** 一段可复制的等宽命令块。 */
    private static LinearLayout cmdBlock(final Activity act, final Ui ui,
                                         String label, final String cmd) {
        LinearLayout box = ui.column();
        box.setBackground(ui.roundStroke(ui.col(M3.SURFACE_CONTAINER), 12,
                ui.col(M3.OUTLINE_VARIANT), 1));
        int p = ui.dp(10);
        box.setPadding(p, p, p, p);
        box.setLayoutParams(ui.matchWrap(6));

        TextView l = ui.text(label, 11, M3.ON_SURFACE_VARIANT, false);
        box.addView(l);

        TextView c = ui.mono(cmd, 10.5f, M3.ON_SURFACE);
        c.setPadding(0, ui.dp(4), 0, 0);
        c.setTextIsSelectable(true);
        box.addView(c);

        TextView copy = ui.text("点这里复制", 11, M3.PRIMARY, true);
        copy.setPadding(0, ui.dp(6), 0, 0);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("magpie", cmd));
            }
            toast(act, "已复制命令");
        });
        box.addView(copy);
        return box;
    }

    // ==================================================================
    // 只读诊断
    // ==================================================================

    private static void loadStatus(final Activity act, final Ui ui, final LinearLayout box) {
        box.removeAllViews();
        TextView loading = ui.text("正在读取…", 11.5f, M3.ON_SURFACE_VARIANT, false);
        box.addView(loading);

        new Thread(() -> {
            // 1) AOSP 白名单
            boolean aosp = false;
            try {
                PowerManager pm = (PowerManager) act.getSystemService(Context.POWER_SERVICE);
                aosp = pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
            } catch (Throwable ignored) {
            }

            // 2) MIUI 省电策略（需要 root 读另一个 App 的数据库）
            String bgControl = readBgControl(act);

            // 3) MILLET 名单
            String millet = execSu("settings get system MILLET_NO_RESTRICT_APP");

            // 4) greeze 相关系统键
            String freezeKey = execSu("settings get system perf_power_freeze_enable");

            final boolean fAosp = aosp;
            final String fBg = bgControl;
            final String fMillet = millet == null ? "" : millet.trim();
            final String fFreeze = freezeKey == null ? "" : freezeKey.trim();

            act.runOnUiThread(() -> {
                box.removeAllViews();
                addRow(act, ui, box, "本机品牌",
                        Build.BRAND + " / " + Build.MANUFACTURER);
                addRow(act, ui, box, "AOSP 白名单（忽略电池优化）",
                        fAosp ? "已在名单里" : "不在");
                addRow(act, ui, box, "MIUI 省电策略（bgControl）",
                        bgText(fBg));
                addRow(act, ui, box, "MILLET 名单里有本 App 吗",
                        fMillet.isEmpty() ? "读不到（需要 root）"
                                : (fMillet.contains(act.getPackageName()) ? "在" : "不在"));
                addRow(act, ui, box, "perf_power_freeze_enable",
                        fFreeze.isEmpty() ? "读不到" : fFreeze);
            });
        }, "magpie-powerlab").start();
    }

    private static String bgText(String bg) {
        if (bg == null) {
            return "读不到（需要 root）";
        }
        switch (bg) {
            case NO_RESTRICT:
                return "noRestrict（无限制 ✅ 不会被冻结）";
            case "miuiAuto":
                return "miuiAuto（智能限制 ⚠️ 会被 greeze 冻）";
            case "restrictAll":
                return "restrictAll（限制全部 ⚠️）";
            case "restrictBackground":
                return "restrictBackground（限制后台 ⚠️）";
            default:
                return bg;
        }
    }

    /** 读 Shizuku 状态并显示。 */
    private static void loadShizuku(final Activity act, final Ui ui, final LinearLayout box) {
        box.removeAllViews();
        new Thread(() -> {
            final String desc = ShizukuShell.describe();
            final int uid = ShizukuShell.uid();
            act.runOnUiThread(() -> {
                box.removeAllViews();
                addRow(act, ui, box, "Shizuku 状态", desc);
                if (uid >= 0) {
                    addRow(act, ui, box, "当前身份",
                            uid == 0 ? "root（可改 MIUI 省电策略）"
                                    : uid == 2000 ? "shell（不能读写别的 App 数据）"
                                    : "uid=" + uid);
                }
            });
        }, "magpie-shizuku-status").start();
    }

    private static void addRow(Activity act, Ui ui, LinearLayout box, String k, String v) {
        LinearLayout row = ui.column();
        row.setLayoutParams(ui.matchWrap(6));
        TextView key = ui.text(k, 11, M3.ON_SURFACE_VARIANT, false);
        row.addView(key);
        TextView val = ui.text(v, 12.5f, M3.ON_SURFACE, true);
        val.setTextIsSelectable(true);
        val.setPadding(0, ui.dp(2), 0, 0);
        row.addView(val);
        box.addView(row);
    }

    // ==================================================================
    // root：读 / 写 MIUI 省电策略
    // ==================================================================

    /** 读 magpie 的 bgControl。需要 root（数据库属于另一个 App）。 */
    private static String readBgControl(Context ctx) {
        File tmp = new File(ctx.getCacheDir(), "pl_user_configure.db");
        try {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            if (!execSuOk("cp " + DB_PATH + " " + tmp.getAbsolutePath())) {
                return null;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true);
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try {
                Cursor c = db.rawQuery(
                        "select bgControl from userTable where pkgName=?",
                        new String[]{ctx.getPackageName()});
                try {
                    return c.moveToFirst() ? c.getString(0) : null;
                } finally {
                    c.close();
                }
            } finally {
                db.close();
            }
        } catch (Throwable e) {
            Log.w(TAG, "读 bgControl 失败: " + e);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 弹确认，然后写库。 */
    private static void confirmAndFix(final Activity act, final Ui ui,
                                      final AlertDialog parent, final LinearLayout statusBox) {
        new AlertDialog.Builder(act)
                .setTitle("修改系统省电策略？")
                .setMessage("这会直接改写 MIUI 的省电策略数据库：\n\n"
                        + DB_PATH + "\n\n"
                        + "把 " + act.getPackageName() + " 的 bgControl 改成 noRestrict（无限制），"
                        + "等于替你在系统设置里点了「无限制」。\n\n"
                        + "改完会尝试重启 PowerKeeper 让它重读。需要 root 授权。")
                .setPositiveButton("确认修改", (d, w) -> doFix(act, ui, statusBox))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void doFix(final Activity act, final Ui ui, final LinearLayout statusBox) {
        toast(act, "正在修改，请留意 root 授权弹窗…");
        new Thread(() -> {
            String err = null;
            try {
                File tmp = new File(act.getCacheDir(), "pl_user_configure.db");
                if (tmp.exists() && !tmp.delete()) {
                    throw new java.io.IOException("无法清理临时文件");
                }
                if (!execSuOk("cp " + DB_PATH + " " + tmp.getAbsolutePath())) {
                    throw new java.io.IOException("复制数据库失败（root 未授权？）");
                }
                if (!tmp.setReadable(true) || !tmp.setWritable(true)) {
                    throw new java.io.IOException("无法给临时数据库授权");
                }

                SQLiteDatabase db = SQLiteDatabase.openDatabase(
                        tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
                try {
                    db.execSQL(
                            "insert or replace into userTable"
                                    + "(userId,pkgName,lastConfigured,bgControl)"
                                    + " values(0,?,?,?)",
                            new Object[]{act.getPackageName(),
                                    System.currentTimeMillis(), NO_RESTRICT});
                } finally {
                    db.close();
                }

                // 写回系统，恢复属主和权限（原本是 system:system 660）
                if (!execSuOk("cp " + tmp.getAbsolutePath() + " " + DB_PATH)) {
                    throw new java.io.IOException("写回数据库失败");
                }
                execSu("chown system:system " + DB_PATH);
                execSu("chmod 660 " + DB_PATH);
                // 让 PowerKeeper 重读
                execSu("am force-stop " + PKG);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w(TAG, "设为无限制失败: " + e, e);
            }
            final String ferr = err;
            act.runOnUiThread(() -> {
                if (ferr != null) {
                    new AlertDialog.Builder(act)
                            .setTitle("修改失败")
                            .setMessage(ferr + "\n\n可以改用「打开系统省电设置」手动设成无限制。")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                toast(act, "已设为无限制，正在重新读取…");
                loadStatus(act, ui, statusBox);
            });
        }, "magpie-powerlab-fix").start();
    }

    // ==================================================================
    // su 工具
    // ==================================================================

    /**
     * 跑一条特权命令：先用 Shizuku（免 root，需授权），不行再试 su。
     *
     * <p>顺序很重要：Shizuku 只要用户点一次授权就行，覆盖面比 root 大；
     * root 需要手机已 root 并弹 magisk 授权。
     */
    private static String execSu(String cmd) {
        // 1) Shizuku
        if (ShizukuShell.available() && ShizukuShell.granted()) {
            ShizukuShell.Result r = ShizukuShell.exec(cmd);
            if (r.ok() || r.code >= 0) {
                return r.out;
            }
        }
        // 2) su
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable e) {
            Log.w(TAG, "su 执行失败: " + cmd + " → " + e);
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /** 跑一条特权命令，只看退出码是否为 0。同样 Shizuku 优先。 */
    private static boolean execSuOk(String cmd) {
        if (ShizukuShell.available() && ShizukuShell.granted()) {
            ShizukuShell.Result r = ShizukuShell.exec(cmd);
            if (r.ok()) {
                return true;
            }
            // Shizuku 明确失败（非 -1 不可用）就不再试 su，避免重复弹窗
            if (r.code > 0) {
                return false;
            }
        }
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Throwable e) {
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    // ==================================================================

    private static void styleDialog(Activity act, Ui ui, AlertDialog dlg) {
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
            }
        } catch (Exception ignored) {
        }
    }

    private static void toast(Activity act, String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
