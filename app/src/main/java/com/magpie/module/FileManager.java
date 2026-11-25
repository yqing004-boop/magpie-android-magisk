package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 壳内的「文件管理器」：浏览、导入、删除、复制 magpie 私有目录里的文件。
 *
 * <p>根是 magpie 的 HOME（{@link GatewayService#homeDir}），也就是子进程里
 * 的 {@code $HOME}。插件（比如 magpie-purge）读的人设/技能目录
 * {@code ~/.magpie/purge/imports}、以及各种配置/缓存都在这一棵树下。
 * 壳直接读写这个目录，不需要 root、不需要 SAF 持久授权。
 *
 * <p>所以「把外部文件导入给插件用」= 把文件复制进这棵树的对应子目录。
 */
final class FileManager {

    private static final String TAG = "MagpieFiles";

    /** SAF 选文件的请求码。 */
    static final int REQ_IMPORT_FILE = 41;

    private FileManager() {
    }

    /** 根目录：magpie 的 $HOME（filesDir/home）。 */
    static File root(Context ctx) {
        return GatewayService.homeDir(ctx);
    }

    // ==================================================================
    // 打开管理器
    // ==================================================================

    /** 打开文件管理器，初始停在哪一级。 */
    static void open(final Activity act, final Ui ui, final File dir) {
        try {
            show(act, ui, dir);
        } catch (Exception e) {
            Log.w(TAG, "open file manager failed", e);
            Toast.makeText(act, "打不开文件管理器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 当前打开着的目录弹窗（浏览层级切换时 dismiss 上一层，防止层层叠着）。 */
    private static AlertDialog current;

    private static void show(final Activity act, final Ui ui, final File dir) {
        AlertDialog dlg = build(act, ui, dir, false);
        if (dlg != null) {
            AlertDialog old = current;
            current = dlg;
            // 旧的等新的起来再收，避免闪一下空白
            dlg.setOnShowListener(d -> {
                if (old != null && old.isShowing()) {
                    old.dismiss();
                }
            });
            dlg.show();
        }
    }

    private static AlertDialog build(final Activity act, final Ui ui, final File dir,
                                     final boolean reuse) {
        final File cur = canonical(dir);
        final String rootPath = root(act).getAbsolutePath();

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(16);
        box.setPadding(pad, ui.dp(4), pad, pad);
        scroll.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 当前路径
        TextView path = ui.mono(displayPath(cur), 11.5f, M3.ON_SURFACE_VARIANT);
        path.setSingleLine(false);
        path.setPadding(0, 0, 0, ui.dp(10));
        box.addView(path);

        // 工具行：导入文件 / 新建文件夹
        LinearLayout tools = ui.row();
        tools.setPadding(0, 0, 0, ui.dp(10));

        TextView imp = ui.pill("导入文件", M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER);
        imp.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7));
        imp.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                act.startActivityForResult(i, REQ_IMPORT_FILE);
                pendingImport = cur;
            } catch (Exception e) {
                Toast.makeText(act, "打不开文件选择器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        tools.addView(imp);

        TextView mk = ui.pill("新建文件夹", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        mk.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7));
        LinearLayout.LayoutParams mkLp = ui.wrap();
        mkLp.leftMargin = ui.dp(8);
        mk.setOnClickListener(v -> {
            final EditText input = new EditText(act);
            input.setHint("文件夹名");
            input.setTextSize(13f);
            input.setBackground(ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 12,
                    ui.col(M3.OUTLINE_VARIANT), 1));
            input.setTextColor(ui.col(M3.ON_SURFACE));
            input.setHintTextColor(ui.col(M3.ON_SURFACE_VARIANT));
            int p = ui.dp(12);
            input.setPadding(p, p, p, p);
            AlertDialog mkDlg = new AlertDialog.Builder(act)
                    .setTitle("新建文件夹")
                    .setView(input)
                    .setPositiveButton("创建", (d, w) -> {
                        String name = PluginTools.sanitize(input.getText().toString().trim());
                        if (name.isEmpty()) {
                            return;
                        }
                        File f = new File(cur, name);
                        if (f.exists()) {
                            Toast.makeText(act, "已经存在：" + name, Toast.LENGTH_SHORT).show();
                        } else if (f.mkdirs()) {
                            Toast.makeText(act, "已创建 " + name, Toast.LENGTH_SHORT).show();
                            reopen(act, ui, cur);
                        } else {
                            Toast.makeText(act, "创建失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            styleDialog(act, ui, mkDlg);
            mkDlg.show();
        });
        tools.addView(mk, mkLp);
        box.addView(tools);

        // 上级目录（不越过 HOME 根）
        File parent = cur.getParentFile();
        if (parent != null && cur.getAbsolutePath().startsWith(rootPath)
                && parent.getAbsolutePath().startsWith(rootPath)) {
            box.addView(row(act, ui, "⬆ 上级目录", null, () -> reopen(act, ui, parent), true));
        }

        // 子项
        File[] children = cur.listFiles();
        List<File> list = new ArrayList<>();
        if (children != null) {
            Collections.addAll(list, children);
        }
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        if (list.isEmpty()) {
            TextView empty = ui.text("（空目录）", 12, M3.ON_SURFACE_VARIANT, false);
            empty.setPadding(0, ui.dp(20), 0, 0);
            box.addView(empty);
        } else {
            for (final File f : list) {
                box.addView(row(act, ui, f.isDirectory() ? "📁 " + f.getName() : "📄 " + f.getName(),
                        f.isDirectory() ? null : humanSize(f.length()),
                        () -> onItem(act, ui, f), false));
            }
        }

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("文件管理器")
                .setView(scroll)
                .create();
        // 「关闭/返回上级」做成 view 内自绘底栏（不用 AlertDialog 按钮体系：
        // MIUI 上占位按钮反复出幺蛾子——1.8.16 消失、1.8.17 空白，自绘的想怎么画怎么画）。
        // 两态：根目录=真关闭；子目录=返回上一级，文案跟着变。
        boolean atRoot = cur.getAbsolutePath().equals(rootPath);
        TextView bottomBtn = ui.pill(atRoot ? "关闭" : "↑ 返回上级",
                M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER);
        bottomBtn.setPadding(ui.dp(22), ui.dp(11), ui.dp(22), ui.dp(11));
        bottomBtn.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = ui.dp(10);
        bottomBtn.setOnClickListener(v -> {
            if (atRoot) {
                dlg.dismiss();
                if (current == dlg) {
                    current = null;
                }
            } else {
                File up = cur.getParentFile();
                if (up != null) {
                    dlg.dismiss();
                    show(act, ui, up);
                } else {
                    dlg.dismiss();
                }
            }
        });
        box.addView(bottomBtn, blp);
        styleDialog(act, ui, dlg);
        return dlg;
    }

    private static void reopen(final Activity act, final Ui ui, final File dir) {
        // 直接重开一个对话框，比在原对话框里重建视图简单可靠
        show(act, ui, dir);
    }

    /** 点一个目录或文件。 */
    private static void onItem(final Activity act, final Ui ui, final File f) {
        if (f.isDirectory()) {
            reopen(act, ui, f);
            return;
        }
        // 文件：复制路径 / 重命名 / 删除
        final String[] actions = {"复制绝对路径", "重命名", "删除"};
        // 卡片式底部弹层（与全 App 弹窗样式统一）
        android.app.Dialog dlg = new android.app.Dialog(act, R.style.Theme_Magpie_Sheet);
        LinearLayout root = ui.column();
        GradientDrawable bg = ui.round(ui.col(M3.SURFACE_CONTAINER_LOW), 28);
        bg.setCornerRadii(new float[]{
                ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(act, "navigation_bar_height"));

        root.addView(ui.text(f.getName(), 19, M3.ON_SURFACE, true));

        Runnable[] runs = {
                () -> copyToClipboard(act, f.getAbsolutePath()),
                () -> rename(act, ui, f),
                () -> confirmDelete(act, ui, f),
        };
        for (int i = 0; i < actions.length; i++) {
            final Runnable run = runs[i];
            TextView row = ui.text(actions[i], 15, M3.ON_SURFACE, false);
            row.setPadding(ui.dp(12), ui.dp(13), ui.dp(12), ui.dp(13));
            GradientDrawable rbg = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_LOW),
                    14, ui.col(M3.OUTLINE_VARIANT), 1);
            ui.clickable(row, rbg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, ui.dp(6));
            row.setOnClickListener(v -> {
                dlg.dismiss();
                run.run();
            });
            root.addView(row, rlp);
        }
        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(android.view.Gravity.BOTTOM);
        }
        dlg.show();
    }

    private static View row(final Activity act, final Ui ui, String name, String size,
                            Runnable action, boolean isParent) {
        LinearLayout r = ui.row();
        GradientDrawable bg = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER), 12,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.clickable(r, bg);
        r.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(6);
        r.setLayoutParams(lp);

        TextView t = ui.text(name, 13, isParent ? M3.PRIMARY : M3.ON_SURFACE, isParent);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        r.addView(t, ui.weight(1));

        if (size != null) {
            TextView s = ui.mono(size, 11, M3.ON_SURFACE_VARIANT);
            s.setPadding(ui.dp(8), 0, 0, 0);
            r.addView(s);
        }
        r.setOnClickListener(v -> action.run());
        return r;
    }

    // ==================================================================
    // 操作
    // ==================================================================

    /** SAF 选文件后的导入目标目录（一次只记一个，选完就用）。 */
    private static File pendingImport;

    /** 处理 SAF 选中的文件：复制到当前导入目标目录。 */
    static void onFilePicked(final Activity act, final Ui ui, final Uri uri) {
        final File destDir = pendingImport != null ? pendingImport : root(act);
        pendingImport = null;
        if (uri == null) {
            return;
        }
        String name = displayNameOf(act, uri);
        if (name == null || name.isEmpty()) {
            name = "imported-" + System.currentTimeMillis();
        }
        final String fname = PluginTools.sanitize(name);
        final File dst = new File(destDir, fname);
        new Thread(() -> {
            String err = null;
            try {
                if (!destDir.isDirectory() && !destDir.mkdirs()) {
                    throw new IOException("无法进入目录 " + destDir.getAbsolutePath());
                }
                try (InputStream in = act.getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w(TAG, "import file failed", e);
            }
            final String ferr = err;
            final String path = dst.getAbsolutePath();
            act.runOnUiThread(() -> {
                if (ferr != null) {
                    Toast.makeText(act, "导入失败：" + ferr, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(act, "已导入到\n" + path, Toast.LENGTH_LONG).show();
                    reopen(act, ui, destDir);
                }
            });
        }, "magpie-file-import").start();
    }

    private static void rename(final Activity act, final Ui ui, final File f) {
        final EditText input = new EditText(act);
        input.setText(f.getName());
        input.setSelectAllOnFocus(true);
        input.setTextSize(13f);
        input.setTextColor(ui.col(M3.ON_SURFACE));
        int p = ui.dp(12);
        input.setPadding(p, p, p, p);
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String name = PluginTools.sanitize(input.getText().toString().trim());
                    if (name.isEmpty()) {
                        return;
                    }
                    File dst = new File(f.getParentFile(), name);
                    if (dst.exists()) {
                        Toast.makeText(act, "已经存在：" + name, Toast.LENGTH_SHORT).show();
                    } else if (f.renameTo(dst)) {
                        reopen(act, ui, f.getParentFile());
                    } else {
                        Toast.makeText(act, "重命名失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        styleDialog(act, ui, dlg);
        dlg.show();
    }

    private static void confirmDelete(final Activity act, final Ui ui, final File f) {
        new AlertDialog.Builder(act)
                .setTitle("删除")
                .setMessage("确定删除 " + f.getName() + " 吗？")
                .setPositiveButton("删除", (d, w) -> {
                    PluginTools.deleteRecursive(f);
                    reopen(act, ui, f.getParentFile());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void copyToClipboard(final Activity act, String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("magpie", s));
        }
        Toast.makeText(act, "已复制路径", Toast.LENGTH_SHORT).show();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String displayNameOf(Context ctx, Uri uri) {
        String name = null;
        try {
            try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.getString(0);
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f;
        }
    }

    private static String displayPath(File f) {
        // 绝对路径直接展示；HOME 是 filesDir/home，路径本来就不长
        return f.getAbsolutePath();
    }

    private static String humanSize(long bytes) {
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
        return String.format(Locale.US, "%.1f %s", v, u[i]);
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
