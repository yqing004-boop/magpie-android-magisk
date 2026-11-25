package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 缓存清理：把 magpie 攒下来但已经不用的东西删掉。
 *
 * <p>为什么需要：magpie 自己的 cache 目录从不清理，实测在手机上能长到 350 MB+。
 * 里面主要是：
 * <ul>
 *   <li>{@code bun/<旧版本>/} —— Bun 升级后旧版本整个目录留着，每个约 108 MB</li>
 *   <li>{@code update/<hash>/} —— 内核更新包下载完的残留，几十 MB</li>
 *   <li>{@code astrlink-guard/<hash>/} —— 脱敏模型文件，关掉脱敏后仍在</li>
 *   <li>{@code models.json}、{@code plugin-github.json} —— 可重新下载的索引</li>
 * </ul>
 *
 * <p>这些都是**可再生**的：删了 magpie 下次用到会重新下载/重建。
 * 不碰 {@code data/} 下的配置、日志、使用记录。
 *
 * <p>面板分三块：当前占用、可清理项（带大小）、清理按钮。
 */
final class CacheCleaner {

    private static final String TAG = "MagpieCache";
    private static final String PREF = "cache_cleaner";
    private static final String KEY_INTERVAL_DAYS = "interval_days";   // 0=关
    private static final String KEY_LAST_RUN = "last_run";

    private CacheCleaner() {
    }

    // ==================================================================
    // 定时清理
    // ==================================================================

    /** 自动清理间隔（天），0=关。 */
    static int intervalDays(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL_DAYS, 0);
    }

    static void setIntervalDays(Context ctx, int days) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INTERVAL_DAYS, days).apply();
    }

    /** 上次自动清理时间（毫秒），0=从没跑过。 */
    static long lastRun(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_RUN, 0);
    }

    /**
     * 网关起来后调一次：到点了就静默清一轮「可再生」项（索引/残留/旧 Bun），
     * 不动生成的图/视频（可能唯一副本）。清完记时间。
     *
     * @return 实际清理的项数；-1=没到时间或功能关着
     */
    static int autoCleanIfDue(Context ctx) {
        int days = intervalDays(ctx);
        if (days <= 0) {
            return -1;
        }
        long last = lastRun(ctx);
        long now = System.currentTimeMillis();
        if (last != 0 && now - last < days * 86_400_000L) {
            return -1;
        }
        List<Target> ts = targets(ctx);
        int n = 0;
        long freed = 0;
        for (Target t : ts) {
            // 只清可再生类：自动清理不碰生成的图/视频和识图缓存（损失不可逆）
            if (t.label.startsWith("生成图") || t.label.startsWith("识图缓存")) {
                continue;
            }
            long sz = sizeOf(t.path);
            delete(t.path);
            if (!t.path.exists()) {
                freed += sz;
                n++;
            }
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_RUN, now).apply();
        android.util.Log.i(TAG, "定时清理: " + n + " 项, 释放 " + human(freed));
        return n;
    }

    // ==================================================================
    // 清什么
    // ==================================================================

    /** 一个可清理的目标。 */
    private static final class Target {
        final String label;
        final String note;
        final File path;
        /** 是否「整目录删」，false 表示删单个文件。 */
        final boolean dir;
        /** 算出来之前是 -1。 */
        long size = -1;
        /** 勾选状态（UI 用，默认全选）。 */
        boolean selected = true;

        Target(String label, String note, File path, boolean dir) {
            this.label = label;
            this.note = note;
            this.path = path;
            this.dir = dir;
        }
    }

    /** magpie 的 cache 根：filesDir/magpie/data/cache。 */
    private static File cacheRoot(Context ctx) {
        return new File(GatewayService.dataDir(ctx), "cache");
    }

    /**
     * 列出所有可清理项。每次都重新扫，因为 Bun 版本号会变。
     */
    private static List<Target> targets(Context ctx) {
        File root = cacheRoot(ctx);
        List<Target> out = new ArrayList<>();

        // 1. Bun：只留 state.json 里 current 那个版本，其余全算垃圾
        File bunRoot = new File(root, "bun");
        String current = readBunCurrent(bunRoot);
        File[] buns = bunRoot.listFiles();
        if (buns != null) {
            for (File d : buns) {
                if (!d.isDirectory()) {
                    continue;
                }
                if (d.getName().equals(current) || d.getName().equals("install-cache")) {
                    continue;
                }
                out.add(new Target("旧版 Bun " + d.getName(),
                        "升级后留下的旧运行时，当前用的是 " + current, d, true));
            }
        }

        // 2. update：内核更新包残留
        File update = new File(root, "update");
        File[] ups = update.listFiles();
        if (ups != null) {
            for (File d : ups) {
                out.add(new Target("更新包残留 " + d.getName(),
                        "内核/壳更新下载后没删的临时文件", d, true));
            }
        }

        // 3. astrlink-guard：脱敏模型（关掉脱敏就没用了）
        File guard = new File(root, "astrlink-guard");
        File[] gs = guard.listFiles();
        if (gs != null) {
            for (File d : gs) {
                if (d.getName().startsWith("found-")) {
                    continue; // 这个很小，留着
                }
                out.add(new Target("脱敏模型 " + d.getName(),
                        "关闭脱敏后不再使用；再开会在设置里重新下载", d, d.isDirectory()));
            }
        }

        // 4. 可重建的索引/缓存文件
        String[][] files = {
                {"models.json", "模型目录", "magpie 会重新拉一次"},
                {"plugin-github.json", "插件 GitHub 索引", "magpie 会重新拉一次"},
                {"plugin-npm.json", "插件 npm 索引", "magpie 会重新拉一次"},
                {"labs.json", "实验室数据", "magpie 会重新拉一次"},
                {"page-v1.json", "用量页快照", "打开用量页时重建"},
                {"log-v1.bin", "用量日志索引", "从 usage.jsonl 重建，可能慢几秒"},
        };
        for (String[] f : files) {
            File p = new File(root, f[0]);
            if (p.exists() && p.isFile()) {
                out.add(new Target(f[1], f[0] + " · " + f[2], p, false));
            }
        }

        // 5. 生成的图片/视频（AI 画的，永久保存没有任何自动清理）
        File home = GatewayService.homeDir(ctx);
        String[][] genDirs = {
                {"generated-images", "AI 生成的图片 · Agent 工作目录下"},
                {"generated-videos", "AI 生成的视频 · Agent 工作目录下"},
        };
        for (String[] g : genDirs) {
            File d = new File(home, g[0]);
            if (d.isDirectory()) {
                out.add(new Target("生成图 " + g[0].replace("generated-", ""),
                        g[1] + " · 厂商 URL 过期后这些是唯一副本，删前想清楚", d, true));
            }
        }
        // ~/Pictures/Magpie（Agent 在 HOME 根跑时的兜底目录）
        File pics = new File(home, "Pictures/Magpie");
        if (pics.isDirectory()) {
            out.add(new Target("生成图 Pictures/Magpie",
                    "Agent 在 HOME 根目录跑时的图片兜底目录", pics, true));
        }

        // 6. 插件工作目录（plugin-work，一般很小但可能残留）
        File pw = new File(root.getParentFile(), "plugin-work");
        if (pw.isDirectory()) {
            long sz = sizeOf(pw);
            if (sz > 1024 * 1024) { // 超过 1MB 才列
                out.add(new Target("插件工作目录",
                        "插件运行时的临时工作区 · 超过 1MB 才列出", pw, true));
            }
        }

        // 7. 图标缓存（icons 目录会随供应商数量增长）
        File icons = new File(root.getParentFile(), "icons");
        if (icons.isDirectory()) {
            long sz = sizeOf(icons);
            if (sz > 5 * 1024 * 1024) { // 超过 5MB 才列
                out.add(new Target("图标缓存",
                        "供应商图标 · 清了会重新拉，超过 5MB 才列出", icons, true));
            }
        }

        // 8. vision 缓存（识图缓存，会一直增长）
        File vc = new File(root.getParentFile(), "vision-cache.jsonl");
        if (vc.isFile()) {
            long sz = vc.length();
            if (sz > 1024 * 1024) { // 超过 1MB 才列
                out.add(new Target("识图缓存",
                        "图片识别结果缓存 · 清了下次识图重新算，超过 1MB 才列出", vc, false));
            }
        }

        return out;
    }

    /** 从 bun/state.json 读当前版本，读不到返回 ""。 */
    private static String readBunCurrent(File bunRoot) {
        File st = new File(bunRoot, "state.json");
        if (!st.isFile()) {
            return "";
        }
        try {
            String s = readAll(st);
            int i = s.indexOf("\"current\"");
            if (i < 0) {
                return "";
            }
            int q1 = s.indexOf('"', s.indexOf(':', i));
            if (q1 < 0) {
                return "";
            }
            int q2 = s.indexOf('"', q1 + 1);
            if (q2 < 0) {
                return "";
            }
            return s.substring(q1 + 1, q2);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(File f) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
            }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 递归算目录大小。 */
    private static long sizeOf(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        long total = 0;
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        for (File k : kids) {
            total += sizeOf(k);
        }
        return total;
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    delete(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] u = {"KB", "MB", "GB"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < u.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
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
        root.addView(ui.text("缓存清理", 17, M3.ON_SURFACE, true));

        TextView intro = ui.text(
                "magpie 的缓存从不自动清理，实测能长到 350 MB 以上。"
                        + "下面这些都可以安全删掉——都是能重新下载或重建的东西。\n"
                        + "不会动你的配置、日志、使用记录。",
                12, M3.ON_SURFACE_VARIANT, false);
        intro.setLineSpacing(ui.dp(2), 1f);
        intro.setLayoutParams(ui.matchWrap(4));
        root.addView(intro);

        // ---- 一、当前占用 ----
        root.addView(sectionTitle(act, ui, "当前占用"));
        final LinearLayout statusBox = ui.column();
        statusBox.setLayoutParams(ui.matchWrap(4));
        root.addView(statusBox);

        TextView refresh = ui.pill("重新扫描", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        refresh.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        refresh.setLayoutParams(ui.matchWrap(8));
        root.addView(refresh);

        // ---- 二、可清理项 ----
        root.addView(sectionTitle(act, ui, "可清理项"));
        final LinearLayout listBox = ui.column();
        listBox.setLayoutParams(ui.matchWrap(4));
        root.addView(listBox);

        // ---- 定时清理 ----
        root.addView(sectionTitle(act, ui, "定时清理"));
        final android.widget.TextView autoHint = ui.text("", 11.5f,
                M3.ON_SURFACE_VARIANT, false);
        autoHint.setLineSpacing(ui.dp(2), 1f);
        autoHint.setLayoutParams(ui.matchWrap(6));
        root.addView(autoHint);

        Runnable updateAutoHint = () -> {
            int days = intervalDays(act);
            if (days <= 0) {
                autoHint.setText("当前：关闭。开启后每隔设定天数自动清一轮可再生项"
                        + "（索引/残留/旧 Bun），不碰生成的图和识图缓存。");
            } else {
                long last = lastRun(act);
                String lastS = last == 0 ? "还没跑过"
                        : new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                                .format(new java.util.Date(last));
                autoHint.setText("当前：每 " + days + " 天自动清一轮（上次 " + lastS + "）。");
            }
        };
        updateAutoHint.run();

        LinearLayout autoRow = ui.row();
        final android.widget.Switch autoSw = new android.widget.Switch(act);
        autoSw.setText("开启定时清理");
        autoSw.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        autoSw.setTextColor(ui.col(M3.ON_SURFACE));
        autoSw.setChecked(intervalDays(act) > 0);
        autoRow.addView(autoSw, ui.weight(1));

        final android.widget.TextView daysBtn = ui.pill(
                intervalDays(act) > 0 ? intervalDays(act) + " 天" : "间隔",
                M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        daysBtn.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7));
        daysBtn.setOnClickListener(v -> {
            final android.widget.EditText input = new android.widget.EditText(act);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setHint("天数（1-90）");
            input.setText(String.valueOf(Math.max(1, intervalDays(act))));
            input.setTextSize(13f);
            AlertDialog dd = new AlertDialog.Builder(act)
                    .setTitle("自动清理间隔（天）")
                    .setView(input)
                    .setPositiveButton("保存", (d, w) -> {
                        try {
                            int days = Integer.parseInt(input.getText().toString().trim());
                            days = Math.max(1, Math.min(90, days));
                            setIntervalDays(act, days);
                            autoSw.setChecked(true);
                            updateAutoHint.run();
                        } catch (Exception ignored) {
                        }
                    })
                    .setNegativeButton("取消", null)
                    .create();
            styleDialog(act, ui, dd);
            dd.show();
        });
        autoRow.addView(daysBtn);
        root.addView(autoRow);
        autoSw.setOnCheckedChangeListener((b, checked) -> {
            setIntervalDays(act, checked ? 7 : 0);
            daysBtn.setText(checked ? "7 天" : "间隔");
            updateAutoHint.run();
        });

        // ---- 三、清理（勾选式）----
        root.addView(sectionTitle(act, ui, "清理"));
        TextView hint = ui.text("打勾选择要清的项，取消勾选的会保留。"
                + "清理过程中界面会卡一下，属正常。",
                12, M3.ON_SURFACE_VARIANT, false);
        hint.setLineSpacing(ui.dp(2), 1f);
        hint.setLayoutParams(ui.matchWrap(4));
        root.addView(hint);

        final TextView clean = ui.pill("清理勾选项", M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER);
        clean.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        clean.setLayoutParams(ui.matchWrap(10));
        root.addView(clean);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        styleDialog(act, ui, dlg);
        dlg.show();

        // ---- 行为 ----
        final List<Target> liveTargets = new ArrayList<>();
        final Runnable[] loadRef = new Runnable[1];
        loadRef[0] = () -> {
            statusBox.removeAllViews();
            listBox.removeAllViews();
            statusBox.addView(ui.text("正在扫描…", 11.5f, M3.ON_SURFACE_VARIANT, false));

            new Thread(() -> {
                File cacheDir = cacheRoot(act);
                long total = sizeOf(cacheDir);
                List<Target> ts = targets(act);
                long junk = 0;
                for (Target t : ts) {
                    t.size = sizeOf(t.path);
                    junk += t.size;
                }
                Collections.sort(ts, (a, b) -> Long.compare(b.size, a.size));
                synchronized (liveTargets) {
                    liveTargets.clear();
                    liveTargets.addAll(ts);
                }

                final long fTotal = total;
                final long fJunk = junk;
                final List<Target> fTs = ts;
                act.runOnUiThread(() -> {
                    statusBox.removeAllViews();
                    statusBox.addView(row(act, ui, "缓存目录总计", human(fTotal)));
                    statusBox.addView(row(act, ui, "可清理", human(fJunk)));
                    statusBox.addView(row(act, ui, "清理后剩余", human(Math.max(0, fTotal - fJunk))));

                    listBox.removeAllViews();
                    if (fTs.isEmpty()) {
                        listBox.addView(ui.text("没有可清理的东西。", 11.5f,
                                M3.ON_SURFACE_VARIANT, false));
                        clean.setEnabled(false);
                        return;
                    }
                    clean.setEnabled(true);
                    for (Target t : fTs) {
                        listBox.addView(checkableItem(act, ui, t));
                    }
                });
            }, "magpie-cache-scan").start();
        };

        refresh.setOnClickListener(v -> loadRef[0].run());
        loadRef[0].run();

        clean.setOnClickListener(v -> {
            List<Target> picked = new ArrayList<>();
            synchronized (liveTargets) {
                for (Target t : liveTargets) {
                    if (t.selected && t.size >= 0) {
                        picked.add(t);
                    }
                }
            }
            if (picked.isEmpty()) {
                android.widget.Toast.makeText(act, "先勾选要清理的项",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            confirmClean(act, ui, dlg, loadRef[0], picked);
        });
    }

    private static void confirmClean(final Activity act, final Ui ui,
                                     final AlertDialog parent, final Runnable after,
                                     final List<Target> picked) {
        long willFree = 0;
        int n = 0;
        for (Target t : picked) {
            willFree += t.size;
            n++;
        }
        AlertDialog c = new AlertDialog.Builder(act)
                .setTitle("清理缓存")
                .setMessage("清掉勾选的 " + n + " 项（约 " + human(willFree) + "）？\n\n"
                        + "这些都是可再生数据。配置、日志、使用记录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理", (d, w) -> {
                    new Thread(() -> {
                        long freed = 0;
                        int m = 0;
                        for (Target t : picked) {
                            long sz = sizeOf(t.path);
                            delete(t.path);
                            if (!t.path.exists()) {
                                freed += sz;
                                m++;
                            }
                        }
                        final long fFreed = freed;
                        final int fN = m;
                        act.runOnUiThread(() -> {
                            android.widget.Toast.makeText(act,
                                    "已清理 " + fN + " 项，释放 " + human(fFreed),
                                    android.widget.Toast.LENGTH_LONG).show();
                            if (after != null) {
                                after.run();
                            }
                        });
                    }, "magpie-cache-clean").start();
                })
                .create();
        styleDialog(act, ui, c);
        c.show();
    }

    /** 一个可清理项（带勾选框）：勾选状态写回 target.selected。 */
    private static LinearLayout checkableItem(Activity act, Ui ui, Target t) {
        LinearLayout box = item(act, ui, t.label, t.note + " · " + human(t.size));
        android.widget.CheckBox cb = new android.widget.CheckBox(act);
        cb.setChecked(t.selected);
        cb.setOnCheckedChangeListener((b, checked) -> t.selected = checked);
        // 整卡可点切换勾选
        box.setOnClickListener(v -> cb.toggle());
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setLayoutParams(ui.matchWrap(6));
        cb.setLayoutParams(new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        box.setLayoutParams(clp);
        wrap.addView(box);
        return wrap;
    }

    // ==================================================================
    // 小部件
    // ==================================================================

    private static TextView sectionTitle(Activity act, Ui ui, String s) {
        TextView t = ui.text(s, 12.5f, M3.PRIMARY, true);
        t.setLayoutParams(ui.matchWrap(14));
        return t;
    }

    /** 一行「标签 —— 值」。 */
    private static LinearLayout row(Activity act, Ui ui, String label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(ui.matchWrap(4));

        TextView l = ui.text(label, 11.5f, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(l, lp);

        TextView v = ui.text(value, 12, M3.ON_SURFACE, true);
        r.addView(v);
        return r;
    }

    /** 一个可清理项：标题 + 说明。 */
    private static LinearLayout item(Activity act, Ui ui, String title, String sub) {
        LinearLayout box = ui.column();
        box.setBackground(ui.roundStroke(ui.col(M3.SURFACE_CONTAINER), 12,
                ui.col(M3.OUTLINE_VARIANT), 1));
        int p = ui.dp(10);
        box.setPadding(p, p, p, p);
        box.setLayoutParams(ui.matchWrap(6));

        box.addView(ui.text(title, 12, M3.ON_SURFACE, true));
        TextView s = ui.text(sub, 10.5f, M3.ON_SURFACE_VARIANT, false);
        s.setPadding(0, ui.dp(2), 0, 0);
        box.addView(s);
        return box;
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
