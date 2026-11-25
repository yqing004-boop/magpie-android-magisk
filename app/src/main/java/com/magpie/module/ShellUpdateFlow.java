package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 壳（APK）自更新流程：查最新 tag → 确认 → 下载 APK → 拉起系统安装器。
 *
 * <p>抽出来是为了让两个入口共用：原生主界面（{@link NativeHome}）和旧的
 * 网页壳（{@link MainActivity}）。两边逻辑一样，只有对话框配色不一样。
 */
final class ShellUpdateFlow {

    private static final String TAG = "MagpieShellUpdate";

    private ShellUpdateFlow() {
    }

    /**
     * 静默检查：24 小时一次，看到新版就问一句要不要装。
     *
     * @param onStyle 给对话框上色的回调，可以是 null
     */
    static void autoCheck(final Activity act, final Style onStyle) {
        if (!Prefs.shellAutoCheck(act)) {
            return;
        }
        if (System.currentTimeMillis() - Prefs.shellLastCheck(act)
                < 24L * 60 * 60 * 1000) {
            return;
        }
        ShellUpdater.check(act, r -> {
            if (!r.ok()) {
                return; // 网络失败这次不算，下次再试
            }
            Prefs.noteShellChecked(act, r.latestTag);
            if (!ShellUpdater.newer(r.latestTag, ShellUpdater.localVersion(act))) {
                return;
            }
            final String mirror = Prefs.shellMirror(act);
            AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(R.string.shell_update_title)
                    .setMessage(act.getString(R.string.shell_update_auto_found, r.latestTag))
                    .setPositiveButton(R.string.shell_download_install, (d, w) -> {
                        if (r.hasApk()) {
                            downloadAndInstall(act, ShellUpdater.mirrored(mirror, r.downloadUrl),
                                    onStyle);
                        } else {
                            try {
                                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.page)));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            if (onStyle != null) {
                onStyle.apply(dlg);
            }
            dlg.show();
        });
    }

    /** 手动检查壳更新（菜单里的「检查 App 更新」）。 */
    static void checkNow(final Activity act, final Style onStyle) {
        final String local = ShellUpdater.localVersion(act);
        ShellUpdater.check(act, r -> {
            if (!r.ok()) {
                AlertDialog dlg = new AlertDialog.Builder(act)
                        .setTitle(R.string.shell_update_title)
                        .setMessage(act.getString(R.string.shell_update_failed, r.error))
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                if (onStyle != null) {
                    onStyle.apply(dlg);
                }
                dlg.show();
                return;
            }
            if (!ShellUpdater.newer(r.latestTag, local)) {
                AlertDialog dlg = new AlertDialog.Builder(act)
                        .setTitle(R.string.shell_update_title)
                        .setMessage(act.getString(R.string.shell_update_latest, local))
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                if (onStyle != null) {
                    onStyle.apply(dlg);
                }
                dlg.show();
                return;
            }
            final String mirror = Prefs.shellMirror(act);
            if (!r.hasApk()) {
                openPage(act, r.page);
                return;
            }
            final String url = ShellUpdater.mirrored(mirror, r.downloadUrl);
            String mirrorName = mirror == null || mirror.isEmpty()
                    ? act.getString(R.string.shell_mirror_official) : mirror;
            AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(R.string.shell_update_title)
                    .setMessage(act.getString(R.string.shell_update_available_download,
                            r.latestTag, mirrorName))
                    .setPositiveButton(R.string.shell_download_install,
                            (d, w) -> downloadAndInstall(act, url, onStyle))
                    .setNeutralButton(R.string.shell_open_page,
                            (d, w) -> openPage(act, r.page))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            if (onStyle != null) {
                onStyle.apply(dlg);
            }
            dlg.show();
        });
    }

    private static void openPage(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    /** 下载到 cacheDir/apk/ 再拉起安装器。 */
    static void downloadAndInstall(final Activity act, final String url, final Style onStyle) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            AlertDialog dlg = new AlertDialog.Builder(act)
                    .setTitle(R.string.shell_update_title)
                    .setMessage(R.string.shell_need_unknown_source)
                    .setPositiveButton(R.string.shell_go_settings, (d, w) -> {
                        try {
                            act.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(act, R.string.shell_install_failed,
                                    Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            if (onStyle != null) {
                onStyle.apply(dlg);
            }
            dlg.show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage(act.getString(R.string.shell_downloading));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            File apk = null;
            String error = null;
            try {
                File dir = new File(act.getCacheDir(), "apk");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("无法创建下载目录");
                }
                apk = new File(dir, "magpie-update.apk");
                download(url, apk);
            } catch (IOException e) {
                error = e.getMessage();
                Log.w(TAG, "下载 APK 失败: " + e);
            }
            final File file = apk;
            final String why = error;
            act.runOnUiThread(() -> {
                if (pd.isShowing()) {
                    pd.dismiss();
                }
                if (why != null || file == null) {
                    AlertDialog dlg = new AlertDialog.Builder(act)
                            .setTitle(R.string.shell_update_title)
                            .setMessage(act.getString(R.string.shell_download_failed, why))
                            .setPositiveButton(android.R.string.ok, null)
                            .create();
                    if (onStyle != null) {
                        onStyle.apply(dlg);
                    }
                    dlg.show();
                    return;
                }
                install(act, file);
            });
        }, "magpie-apk-download").start();
    }

    private static void install(Activity act, File apk) {
        try {
            Uri uri = Uri.parse("content://" + ApkFileProvider.AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "拉起安装器失败: " + e);
            Toast.makeText(act, R.string.shell_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "magpie-android-shell");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("写不出目标文件");
            }
        } finally {
            c.disconnect();
        }
    }

    /** 对话框上色的回调（原生界面按 M3 令牌，旧壳也按令牌）。 */
    interface Style {
        void apply(AlertDialog dlg);
    }

    /** 给原生界面用的一份现成配色。 */
    static Style m3(final Context ctx, final Ui ui) {
        return dlg -> {
            try {
                if (dlg.getWindow() != null) {
                    dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
                }
                dlg.setOnShowListener(x -> {
                    for (int id : new int[]{AlertDialog.BUTTON_POSITIVE,
                            AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
                        android.widget.Button b = dlg.getButton(id);
                        if (b != null) {
                            b.setTextColor(ui.col(M3.PRIMARY));
                            b.setBackground(ui.dialogBtnBg());
                            android.view.ViewGroup.LayoutParams blp0 = b.getLayoutParams();
                            if (blp0 instanceof android.widget.LinearLayout.LayoutParams) {
                                android.widget.LinearLayout.LayoutParams blp =
                                        (android.widget.LinearLayout.LayoutParams) blp0;
                                blp.setMargins(ui.dp(6), 0, ui.dp(6), 0);
                                b.setLayoutParams(blp);
                            }
                        }
                    }
                });
            } catch (Exception ignored) {
            }
        };
    }
}
