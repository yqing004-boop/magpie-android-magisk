package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 把日志和诊断信息上报到 GitHub 仓库。
 *
 * <p>为什么不是直接调 API 建 issue：建 issue 需要 token，而本项目是公开仓库，
 * token 写进 APK 就等于公开——之前查过那个 token 是 admin 全仓库权限，绝不能内置。
 *
 * <p>所以走 GitHub 官方支持的「预填 Issue」链接：
 * <pre>
 * https://github.com/{owner}/{repo}/issues/new?title=…&body=…&labels=bug
 * </pre>
 * 用户在浏览器里看到已经填好的内容，自己点「Submit new issue」即可。
 * 全程不需要任何凭据，也不会泄露任何 token。
 *
 * <p>日志会截断（URL 有长度上限），完整日志同时复制到剪贴板，用户可自行粘贴补充。
 */
final class ReportIssue {

    /** 日志最多带多少字符进 URL（浏览器 URL 上限约 8000，留足余量）。 */
    private static final int MAX_LOG_CHARS = 2500;

    private ReportIssue() {
    }

    /** 弹出上报面板：让用户写一句话描述，然后跳到 GitHub。 */
    static void show(final Activity act, final Ui ui) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0);

        TextView hint = ui.text("写一句发生了什么，会自动附上版本、机型和日志末尾。"
                        + "点「去 GitHub 提交」后，在浏览器里点一下 Submit new issue 就行。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        hint.setLineSpacing(ui.dp(2), 1f);
        box.addView(hint);

        final EditText input = new EditText(act);
        input.setHint("例如：全面屏上账号编辑弹窗文字被截断");
        input.setTextSize(13f);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setBackground(ui.roundStroke(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 12,
                ui.col(M3.OUTLINE_VARIANT), 1));
        input.setTextColor(ui.col(M3.ON_SURFACE));
        input.setHintTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        int p = ui.dp(12);
        input.setPadding(p, p, p, p);
        LinearLayout.LayoutParams ilp = ui.matchWrap(12);
        box.addView(input, ilp);

        TextView info = ui.text(deviceLine(act), 11, M3.ON_SURFACE_VARIANT, false);
        info.setLayoutParams(ui.matchWrap(8));
        box.addView(info);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("上报问题到 GitHub")
                .setView(box)
                .setPositiveButton("去 GitHub 提交", (d, w) ->
                        submit(act, input.getText().toString().trim()))
                .setNeutralButton("只复制内容", (d, w) ->
                        copyOnly(act, input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        style(act, ui, dlg);
        dlg.show();
    }

    /** 组装 issue 正文：用户描述 + 诊断 + 日志末尾。 */
    private static String buildBody(Activity act, String userNote) {
        StringBuilder sb = new StringBuilder();
        if (userNote != null && !userNote.isEmpty()) {
            sb.append("### 描述\n\n").append(userNote).append("\n\n");
        }
        sb.append("### 环境\n\n");
        sb.append("- App 版本：").append(ShellUpdater.localVersion(act)).append('\n');
        sb.append("- Android：").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("- 机型：").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n');
        sb.append("- 模块内核：").append(GatewayService.moduleVersion()).append("\n");
        sb.append("- 网关端口：").append(GatewayService.gatewayPort(act))
                .append(" / 网页 ").append(GatewayService.webPort(act)).append('\n');
        sb.append("- 局域网共享：").append(Prefs.lan(act) ? "开" : "关").append('\n');
        sb.append("- 数字单位：").append(Prefs.unitMode(act) == 1 ? "中文 万/亿" : "英文 K/M/B")
                .append("\n\n");

        String log = GatewayService.lastLog(200);
        if (log != null && !log.trim().isEmpty()) {
            String tail = log;
            if (tail.length() > MAX_LOG_CHARS) {
                tail = "…（前面省略）\n" + tail.substring(tail.length() - MAX_LOG_CHARS);
            }
            sb.append("### 日志末尾\n\n```\n").append(tail).append("\n```\n");
        }
        return sb.toString();
    }

    private static void submit(Activity act, String userNote) {
        String title = userNote.isEmpty() ? "问题反馈" : firstLine(userNote);
        String body = buildBody(act, userNote);

        // 完整日志先复制一份，URL 里装不下的部分用户能自己补
        copyToClipboard(act, body);

        String url = "https://github.com/" + ShellUpdater.owner() + "/" + ShellUpdater.repo()
                + "/issues/new?labels=bug"
                + "&title=" + enc(title)
                + "&body=" + enc(body);
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            Toast.makeText(act, "已在浏览器打开；完整内容也已复制到剪贴板",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(act, "打不开浏览器，内容已复制，请手动到 GitHub 提 issue",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void copyOnly(Activity act, String userNote) {
        copyToClipboard(act, buildBody(act, userNote));
        Toast.makeText(act, "已复制，可粘贴到 GitHub issue", Toast.LENGTH_SHORT).show();
    }

    private static String deviceLine(Activity act) {
        return "将附带：v" + ShellUpdater.localVersion(act)
                + " · Android " + Build.VERSION.RELEASE
                + " · " + Build.BRAND + " " + Build.MODEL
                + " · 日志末尾 " + MAX_LOG_CHARS + " 字符";
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String t = i > 0 ? s.substring(0, i) : s;
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    private static String enc(String s) {
        try {
            // URLEncoder 把空格编成 +，URL 里要用 %20
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void copyToClipboard(Context ctx, String s) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("magpie", s));
        }
    }

    private static void style(Activity act, Ui ui, AlertDialog dlg) {
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
            }
        } catch (Exception ignored) {
        }
    }
}
