package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 插件与登录相关的壳侧动作，从 MainActivity 抽出来给两个界面共用。
 *
 * <p>这里放的都是「必须由 Android 侧做」的事 —— 选文件夹、解压、写沙箱文件、
 * 调 magpie 的 HTTP 接口。magpie 自己在网页里做不了的才在这里做。
 *
 * <ul>
 *   <li>{@link #pickFolder} / {@link #onFolderPicked}：SAF 选文件夹导入本地插件</li>
 *   <li>{@link #promptMarvisCallback}：粘贴腾讯 Marvis 的登录回调地址</li>
 * </ul>
 */
final class PluginTools {

    private static final String TAG = "MagpiePlugins";

    /** SAF 选文件夹的请求码，Activity 侧 onActivityResult 用它认领。 */
    static final int REQ_PLUGIN_DIR = 40;

    private PluginTools() {
    }

    // ==================================================================
    // 导入本地插件
    // ==================================================================

    /** 弹系统文件夹选择器。 */
    static void pickFolder(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivityForResult(i, REQ_PLUGIN_DIR);
        } catch (Exception e) {
            Toast.makeText(act, act.getString(R.string.plugin_import_failed,
                    "打不开文件夹选择器"), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 选好文件夹之后：列目录 → 勾选要导入的项 → 拷进沙箱 → 交给 magpie。
     *
     * @param style 对话框配色回调，可为 null
     */
    static void onFolderPicked(final Activity act, final Uri tree, final ShellUpdateFlow.Style style) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        final List<String> names = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        final List<Boolean> dirs = new ArrayList<>();
        Cursor c = act.getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    names.add(c.getString(1));
                    ids.add(c.getString(0));
                    dirs.add(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)));
                }
            } finally {
                c.close();
            }
        }
        if (names.isEmpty()) {
            // 空文件夹：直接确认导入文件夹本身
            confirmSingle(act, tree, rootId, folderNameOf(act, tree, rootId), true, style);
            return;
        }
        final String[] items = names.toArray(new String[0]);
        final boolean[] checked = new boolean[items.length];
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(R.string.plugin_pick_title)
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.plugin_import_selected, (d, w) -> {
                    List<String> sn = new ArrayList<>();
                    List<String> si = new ArrayList<>();
                    List<Boolean> sd = new ArrayList<>();
                    for (int i = 0; i < items.length; i++) {
                        if (checked[i]) {
                            sn.add(names.get(i));
                            si.add(ids.get(i));
                            sd.add(dirs.get(i));
                        }
                    }
                    if (sn.isEmpty()) {
                        Toast.makeText(act, R.string.plugin_none_selected, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    importSelected(act, tree, si, sn, sd);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (style != null) {
            style.apply(dlg);
        }
        dlg.show();
    }

    private static void confirmSingle(final Activity act, final Uri tree, final String childId,
                                      final String name, final boolean dir,
                                      final ShellUpdateFlow.Style style) {
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(R.string.plugin_confirm_title)
                .setMessage(act.getString(R.string.plugin_confirm_msg, name))
                .setPositiveButton(R.string.plugin_confirm_ok, (d, w) -> importSelected(act, tree,
                        new ArrayList<>(java.util.Collections.singletonList(childId)),
                        new ArrayList<>(java.util.Collections.singletonList(name)),
                        new ArrayList<>(java.util.Collections.singletonList(dir))))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (style != null) {
            style.apply(dlg);
        }
        dlg.show();
    }

    /** 批量导入勾选的插件（文件或目录），后台线程跑。 */
    private static void importSelected(final Activity act, final Uri tree,
                                       final List<String> ids, final List<String> names,
                                       final List<Boolean> dirs) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage(act.getString(R.string.plugin_importing));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                cleanRemovedPlugins(act);
                File pluginsDir = new File(GatewayService.dataDir(act), "plugins-local");
                if (!pluginsDir.isDirectory() && !pluginsDir.mkdirs()) {
                    throw new IOException("无法创建插件目录");
                }
                int ok = 0;
                final StringBuilder errs = new StringBuilder();
                for (int i = 0; i < ids.size(); i++) {
                    try {
                        importOne(act, tree, ids.get(i), names.get(i), dirs.get(i), pluginsDir);
                        ok++;
                    } catch (Exception e) {
                        errs.append(names.get(i)).append(": ")
                                .append(e.getMessage() == null ? e.toString() : e.getMessage())
                                .append('\n');
                    }
                }
                final int fok = ok;
                final String ferrs = errs.toString().trim();
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    if (ferrs.isEmpty()) {
                        Toast.makeText(act, act.getString(R.string.plugin_import_ok_count, fok),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(act,
                                act.getString(R.string.plugin_import_partial, fok, ferrs),
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "导入插件失败: " + e);
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(act, act.getString(R.string.plugin_import_failed, msg),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "magpie-plugin-import").start();
    }

    /** 导入单个插件：目录递归拷贝，文件直接拷；tar.gz/tgz/zip 先解压再交给 magpie。 */
    private static void importOne(Activity act, Uri tree, String childId, String rawName,
                                  boolean dir, File pluginsDir) throws IOException {
        String name = sanitize(rawName);
        String spec;
        if (dir) {
            File dst = new File(pluginsDir, name);
            if (dst.exists()) {
                deleteRecursive(dst);
            }
            if (!dst.mkdirs()) {
                throw new IOException("无法创建 " + name);
            }
            copyTree(act, tree, childId, dst);
            spec = dst.getAbsolutePath();
        } else {
            File dst = new File(pluginsDir, name);
            if (dst.exists()) {
                dst.delete();
            }
            copyFile(act, DocumentsContract.buildDocumentUriUsingTree(tree, childId), dst);
            if (name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".zip")) {
                String dirName = name.replaceFirst("\\.(tar\\.gz|tgz|zip)$", "");
                File exDir = new File(pluginsDir, dirName);
                if (exDir.exists()) {
                    deleteRecursive(exDir);
                }
                if (!exDir.mkdirs()) {
                    throw new IOException("无法创建 " + dirName);
                }
                if (name.endsWith(".zip")) {
                    unzip(dst, exDir);
                } else {
                    untar(dst, exDir);
                }
                dst.delete();
                spec = exDir.getAbsolutePath();
            } else {
                spec = dst.getAbsolutePath();
            }
        }
        String err = postPluginAdd(act, spec);
        if (err != null) {
            throw new IOException(err);
        }
        Prefs.addImportedPlugin(act, name);
    }

    // ==================================================================
    // 与 magpie 说话
    // ==================================================================

    /** 调 magpie 的 POST /api/plugins/add，spec=沙箱绝对路径。返回 null 成功，否则错误文案。 */
    private static String postPluginAdd(Context ctx, String spec) {
        String body = "{\"spec\":\"" + jsonEscape(spec) + "\"}";
        MagpieApi.Result r = MagpieApi.post(ctx, "/api/plugins/add", body, MagpieApi.WRITE_MS);
        if (!r.ok()) {
            return "magpie 返回 " + (r.code != 0 ? r.code : r.why());
        }
        return null;
    }

    /** 清残留：magpie 已移除的导入插件，把沙箱副本删掉。 */
    private static void cleanRemovedPlugins(Context ctx) {
        File dir = new File(GatewayService.dataDir(ctx), "plugins-local");
        if (!dir.isDirectory()) {
            return;
        }
        Set<String> imported = Prefs.importedPlugins(ctx);
        if (imported.isEmpty()) {
            return;
        }
        MagpieApi.Result r = MagpieApi.get(ctx, "/api/plugins",
                MagpieApi.CONNECT_MS, MagpieApi.READ_MS);
        if (!r.ok()) {
            return; // 查不到就别清，避免误删
        }
        for (String name : new HashSet<>(imported)) {
            if (!r.body.contains("plugins-local/" + name)) {
                deleteRecursive(new File(dir, name));
                Prefs.removeImportedPlugin(ctx, name);
            }
        }
    }

    // ==================================================================
    // Marvis 登录回调
    // ==================================================================

    /** 粘贴腾讯 Marvis 插件的登录回调地址（写进沙箱文件等插件来取）。 */
    static void promptMarvisCallback(final Activity act, final Ui ui,
                                     final ShellUpdateFlow.Style style) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);

        // 补丁状态摆在输入框上面：插件在不在、版本、补丁有没有打上
        TextView status = new TextView(act);
        status.setTextSize(11.5f);
        status.setText(GatewayService.patchStatus(act));
        status.setTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        box.addView(status);

        TextView help = new TextView(act);
        help.setText(R.string.marvis_paste_help);
        help.setTextSize(12.5f);
        help.setTextColor(ui.col(M3.ON_SURFACE));
        help.setPadding(0, ui.dp(10), 0, 0);
        box.addView(help);

        final EditText input = new EditText(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(10);
        input.setLayoutParams(lp);
        input.setHint(R.string.marvis_paste_hint);
        input.setTextSize(12.5f);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        GradientDrawable field = ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 12,
                ui.col(M3.OUTLINE_VARIANT), 1);
        input.setBackground(field);
        input.setTextColor(ui.col(M3.ON_SURFACE));
        input.setHintTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        int inPad = ui.dp(12);
        input.setPadding(inPad, inPad, inPad, inPad);
        box.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(R.string.act_marvis_paste)
                .setView(box)
                .setPositiveButton(R.string.marvis_paste_ok, (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) {
                        return;
                    }
                    if (writeMarvisCallback(act, url)) {
                        Toast.makeText(act, R.string.marvis_paste_done, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(act, R.string.marvis_paste_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (style != null) {
            style.apply(dlg);
        }
        dlg.show();
    }

    private static boolean writeMarvisCallback(Context ctx, String url) {
        try {
            File dir = GatewayService.homeDir(ctx);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            File f = new File(dir, "marvis-login-url.txt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "marvis callback write failed: " + e);
            return false;
        }
    }

    // ==================================================================
    // 文件与解压工具
    // ==================================================================

    private static String folderNameOf(Context ctx, Uri tree, String docId) {
        String name = null;
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Cursor nc = ctx.getContentResolver().query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (nc != null) {
            try {
                if (nc.moveToFirst()) {
                    name = nc.getString(0);
                }
            } finally {
                nc.close();
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = docId.substring(docId.lastIndexOf('/') + 1);
        }
        return name;
    }

    private static void copyTree(Context ctx, Uri treeUri, String docId, File dstDir)
            throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Cursor c = ctx.getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null);
        if (c == null) {
            return;
        }
        try {
            while (c.moveToNext()) {
                String childId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    File sub = new File(dstDir, sanitize(name));
                    if (!sub.isDirectory() && !sub.mkdirs()) {
                        throw new IOException("mkdir fail: " + name);
                    }
                    copyTree(ctx, treeUri, childId, sub);
                } else {
                    copyFile(ctx, childUri, new File(dstDir, sanitize(name)));
                }
            }
        } finally {
            c.close();
        }
    }

    private static void copyFile(Context ctx, Uri src, File dst) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    /** 解压 zip 到 destDir（流式，防路径穿越）。 */
    private static void unzip(File zip, File destDir) throws IOException {
        try (InputStream fin = new FileInputStream(zip);
             java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(fin)) {
            byte[] buf = new byte[8192];
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                if (name.isEmpty() || name.contains("..")) {
                    zin.closeEntry();
                    continue;
                }
                File out = new File(destDir, name);
                if (e.isDirectory()) {
                    if (!out.isDirectory()) {
                        out.mkdirs();
                    }
                    zin.closeEntry();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    parent.mkdirs();
                }
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        fo.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    /** 解压 tar.gz 到 destDir（仅普通文件和目录，支持长路径名）。 */
    private static void untar(File tarGz, File destDir) throws IOException {
        try (InputStream fin = new FileInputStream(tarGz);
             java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(fin)) {
            byte[] buf = new byte[8192];
            byte[] header = new byte[512];
            while (true) {
                int off = 0;
                int n;
                while (off < 512) {
                    n = gz.read(header, off, 512 - off);
                    if (n < 0) {
                        return;
                    }
                    off += n;
                }
                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    return;
                }
                String name = new String(header, 0, 100,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                long size = 0;
                String sizeStr = new String(header, 124, 12,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                try {
                    size = Long.parseLong(sizeStr, 8);
                } catch (Exception ignored) {
                    size = 0;
                }
                char type = (char) header[156];
                if (name.isEmpty()) {
                    continue;
                }
                name = name.replaceFirst("^\\./", "");
                if (name.startsWith("/")) {
                    name = name.substring(1);
                }
                File out = new File(destDir, name);
                if (type == '5') {
                    if (!out.isDirectory()) {
                        out.mkdirs();
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        long remaining = size;
                        while (remaining > 0) {
                            int want = (int) Math.min((long) buf.length, remaining);
                            n = gz.read(buf, 0, want);
                            if (n < 0) {
                                break;
                            }
                            fo.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                }
                long pad = (512 - (size % 512)) % 512;
                while (pad > 0) {
                    int want = (int) Math.min((long) buf.length, pad);
                    n = gz.read(buf, 0, want);
                    if (n < 0) {
                        return;
                    }
                    pad -= n;
                }
            }
        }
    }

    static String sanitize(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            s = "plugin";
        }
        return s;
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' || ch == '"') {
                sb.append('\\').append(ch);
            } else if (ch < 0x20) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
