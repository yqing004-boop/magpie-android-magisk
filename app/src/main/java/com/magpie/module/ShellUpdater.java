package com.magpie.module;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 检查这个 App（壳）自己在 GitHub 开源仓库里的新版本。
 *
 * <p>壳更新和内核更新是两回事：内核是 {@code filesDir/magpie/magpie} 那个 ELF，
 * 走 {@link Updater}；壳是安装的这个 APK，走这里。只做「提示 + 跳到 Release 页」，
 * 下载安装交给系统浏览器和安装器，壳不碰 APK 文件、不请求安装权限。
 *
 * <p>检查源有两级：先问 GitHub Releases API（结构好解析）；API 对未认证请求
 * 返回 403（2026-11 起 GitHub 收紧了匿名访问）时，回退到 {@code releases.atom}
 * —— 那是普通网页端点，匿名可读、不限流。两条都不带 token，避免把凭据写进 APK。
 */
public final class ShellUpdater {

    private static final String TAG = "MagpieShellUpdater";

    /** 开源仓库 owner。 */
    private static final String OWNER = "yqing004-boop";

    /** 开源仓库名。 */
    private static final String REPO = "magpie-android";

    /** 给「上报问题」用的仓库坐标。 */
    static String owner() {
        return OWNER;
    }

    static String repo() {
        return REPO;
    }

    /** 内置镜像前缀（空串 = GitHub 官方直连）。用户可在设置页手动添加更多。 */
    public static final String[] MIRROR_PREFIXES = {
            "",
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/",
            "https://ghproxy.net/",
            "https://gh.ddlc.top/",
    };

    public static final String[] MIRROR_NAMES = {
            "GitHub 官方",
            "gh-proxy.com",
            "mirror.ghproxy.com",
            "ghproxy.net",
            "gh.ddlc.top",
    };

    public interface Callback {
        void result(Result result);
    }

    /** 一次检查的结果。 */
    public static final class Result {
        public final String latestTag;
        public final String page;
        /** APK 的 GitHub 原始直链（未拼镜像）。 */
        public final String downloadUrl;
        public final String error;

        Result(String tag, String page, String downloadUrl, String error) {
            this.latestTag = tag;
            this.page = page;
            this.downloadUrl = downloadUrl;
            this.error = error;
        }

        public boolean ok() {
            return error == null && latestTag != null && !latestTag.isEmpty();
        }

        /** 是否有可下载的 APK。 */
        public boolean hasApk() {
            return downloadUrl != null && !downloadUrl.isEmpty();
        }
    }

    private ShellUpdater() {
    }

    public static String releasesPage() {
        return "https://github.com/" + OWNER + "/" + REPO + "/releases";
    }

    /** 本 App 的版本号（versionName），例如 "1.0.25"。 */
    public static String localVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 后台查一次最新版本，结果回主线程。 */
    public static void check(Context ctx, Callback cb) {
        new Thread(() -> {
            Result result;
            try {
                result = fetch(ctx);
            } catch (IOException e) {
                Log.w(TAG, "检查壳更新失败: " + e);
                result = new Result("", "", "", e.getMessage());
            }
            final Result r = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.result(r));
        }, "magpie-shell-update").start();
    }

    private static Result fetch(Context ctx) throws IOException {
        Result r = fetchApi(ctx);
        if (r == null) {
            r = fetchAtom();
        }
        if (r == null) {
            return new Result("", "", "", "GitHub 不可达（API 与 atom 都没读到）");
        }
        return r;
    }

    /**
     * 第一级：GitHub Releases API。匿名访问可能 403，失败返回 null 交给
     * atom 兜底，不在这里报错。
     *
     * <p>稳定版只拿 {@code /releases/latest}（不含 prerelease）；测试版拿
     * {@code /releases?per_page=1}（列表第一项，含 prerelease）。
     */
    private static Result fetchApi(Context ctx) {
        String url = Prefs.shellStableChannel(ctx)
                ? "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest"
                : "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=1";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "magpie-android-shell");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            int code = c.getResponseCode();
            if (code == 404) {
                return new Result("", "", "", "仓库还没有 Release");
            }
            if (code != 200) {
                return null; // 403 等，交给 atom
            }
            String body = readAll(c.getInputStream());
            JSONObject j;
            if (!Prefs.shellStableChannel(ctx)) {
                // 测试版：返回的是数组，取第一个
                JSONArray arr = new JSONArray(body);
                if (arr.length() == 0) {
                    return new Result("", "", "", "仓库还没有 Release");
                }
                j = arr.optJSONObject(0);
                if (j == null) {
                    return null;
                }
            } else {
                j = new JSONObject(body);
            }
            String tag = j.optString("tag_name", "");
            String page = j.optString("html_url", releasesPage());
            String dl = "";
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        dl = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            return new Result(stripV(tag), page, dl, null);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * 第二级：从 releases.atom 读最新 tag。
     *
     * <p>atom 是普通网页端点，匿名可读。里面没有 APK 直链，但下载直链是
     * 固定格式：{@code https://github.com/<owner>/<repo>/releases/download/<tag>/<repo>-<ver>.apk}
     * （发布脚本传的 asset 名就是 {@code magpie-android-<version>.apk}）。
     * 按 tag 拼出来；万一拼错下载会 404，但不影响「检查」本身。
     */
    private static Result fetchAtom() {
        String url = "https://github.com/" + OWNER + "/" + REPO + "/releases.atom";
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "magpie-android-shell");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            int code = c.getResponseCode();
            if (code != 200) {
                return null;
            }
            String body = readAll(c.getInputStream());
            String marker = "Repository/";
            int i = body.indexOf(marker);
            if (i < 0) {
                return null;
            }
            int start = i + marker.length();
            int end = body.indexOf('<', start);
            if (end < 0) {
                return null;
            }
            String tag = body.substring(start, end).trim();
            if (!tag.startsWith("v")) {
                return null;
            }
            String ver = stripV(tag);
            String page = "https://github.com/" + OWNER + "/" + REPO
                    + "/releases/tag/" + tag;
            String dl = "https://github.com/" + OWNER + "/" + REPO
                    + "/releases/download/" + tag + "/" + REPO + "-" + ver + ".apk";
            return new Result(ver, page, dl, null);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /** tag 里的 "v" 前缀去掉，好和 versionName 比较。 */
    static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V')
                && Character.isDigit(t.charAt(1)) ? t.substring(1) : t;
    }

    /** 把镜像前缀拼到下载直链前面；prefix 空则原样返回。 */
    public static String mirrored(String prefix, String url) {
        if (prefix == null || prefix.isEmpty()) {
            return url;
        }
        return prefix + url;
    }

    /** 按 "1.0.25" 这种点分数字比较；latest > local 返回 true。 */
    public static boolean newer(String latest, String local) {
        if (latest == null || latest.isEmpty()) {
            return false;
        }
        if (local == null || local.isEmpty()) {
            return true;
        }
        String[] a = latest.split("\\.");
        String[] b = local.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim().replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
