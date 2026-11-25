package com.magpie.module;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键错误诊断：收集排障要用的现场信息，打成 zip 放到用户可见的位置。
 *
 * <p>收集内容（全部只读，不改任何状态）：
 * <ol>
 *   <li>info.txt —— 壳版本 / Android / 机型 / 端口 / 开关 / 网关进程信息</li>
 *   <li>magpie.log —— 网关二进制最后 3000 行 stdout（web key 打码）</li>
 *   <li>crash.txt —— logcat -b crash 里本包的记录</li>
 *   <li>state.json —— /api/state 白名单字段（无 keys/tokens）</li>
 *   <li>usage-tail.json —— 最近 100 条请求摘要 + 当日/本周聚合（含上下行字节）</li>
 *   <li>netstats.json —— 系统口径本 App 流量 + 已知代理/VPN App 对比</li>
 *   <li>vpn.json —— VPN 是否激活、接口、已装代理类应用</li>
 *   <li>connections.txt —— 本 App 进程树的外部 TCP 连接（含 pid）</li>
 *   <li>plugins.json —— 已装插件 spec/版本/开关（不含 options 里的人设与密钥）</li>
 *   <li>plugins-state.json —— <b>GET /api/plugins</b> 的脱敏快照：每个插件的
 *       加载错误、provider 名、工具数、Bun 版本 —— 定位「哪个插件坏了」</li>
 *   <li>providers.json —— provider 列表（id/名称/模型数/signedIn），不含密钥</li>
 *   <li>quotas.json —— 额度卡（provider/窗口/用量），不含账号明文</li>
 *   <li>groups.json —— 路由组配置（pattern 与成员名）</li>
 *   <li>files.txt —— data 目录结构与大小（找异常膨胀的缓存/日志）</li>
 *   <li>env.txt —— magpie 子进程的环境变量（代理、MAGPIE_ADDR，值打码）</li>
 *   <li>threads.txt —— magpie / bun 的线程数、CPU 时间、fd 数、内存</li>
 *   <li>battery.txt —— dumpsys 里的 wake lock 与冻结状态（若有权限）</li>
 * </ol>
 *
 * <p>隐私红线：不打包 settings.json / providers 的密钥 / caller-keys.json /
 * login 凭据 / 任何 token；日志与 env 里的 key 一律打码；插件只取包名不取配置。
 */
final class DiagnosticsPack {

    private static final String TAG = "MagpieDiag";

    /** 作者邮箱：发送至作者时收件人用，也复制到剪贴板（防止邮箱 App 带不动收件人）。 */
    static final String AUTHOR_EMAIL = "lkjhg131456@qq.com";

    /** QQ 交流群加群链接：关于页可点，也是 README 里的入口。 */
    static final String QQ_GROUP_URL =
            "http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=2anVAHj2T9a4GPPPNtL2iIHqctjgYGbQ"
                    + "&authKey=0lMP2nrT%2BvB9ZKIiyDx%2BUPzWA6MhRhSNRtGPIbmmjff0nnVLxHKEObduHhLWFDph"
                    + "&noverify=0&group_code=1053848850";

    /** 进度回调：0..100 与当前步骤名。 */
    interface Progress {
        void step(int percent, String what);
    }

    /**
     * 导出结果：实际文件 + 给用户看的位置说明。
     */
    static final class DiagResult {
        /** 实际文件（发邮箱附件用 FileProvider 从这里读）。 */
        public final File file;
        /** 人话位置："公共下载目录" / "应用目录 Download"。 */
        public final String location;
        /** 完整绝对路径（弹窗里显示，方便手动找）。 */
        public final String absolutePath;

        DiagResult(File file, String location) {
            this.file = file;
            this.location = location;
            this.absolutePath = file.getAbsolutePath();
        }
    }

    private DiagnosticsPack() {
    }

    /** 生成并写入 zip，返回目标文件；失败抛异常带原因。 */

    /**
     * 生成并写入 zip，带进度回调。
     *
     * <p>写入位置三层回退（爸爸 2026-11-23 定）：
     * <ol>
     *   <li><b>MediaStore.Downloads</b>（API 29+）：零权限，写完文件管理器
     *       直接可见，绝大多数设备走这条。</li>
     *   <li><b>/sdcard/Download 直写</b>：Android 9 及以下（targetSdk 28
     *       的写权限还有效），老设备走这条。</li>
     *   <li><b>应用私有目录</b> getExternalFilesDir/Download：永远成功，
     *       兜底；弹窗显示绝对路径 + 发邮箱走 FileProvider 自动带附件。</li>
     * </ol>
     *
     * @param p 可为 null；每完成一步回调一次
     */
    static DiagResult export(Activity act, Progress p) throws Exception {
        String ver = version(act);
        String stamp = new java.text.SimpleDateFormat("MMdd", java.util.Locale.US)
                .format(new java.util.Date());
        // 文件名短一些：太长经某些邮件/IM 客户端会被截断甚至拒收。
        String name = "magpie-diag-" + ver + "-" + stamp + ".zip";

        // ---- 先在私有目录生成（私有目录永远可写，生成过程不受公共存储权限影响）----
        File privDir = new File(act.getExternalFilesDir(null), "Download");
        if (!privDir.isDirectory() && !privDir.mkdirs()) {
            privDir = act.getCacheDir();
        }
        File priv = new File(privDir, name);
        writeZip(priv, act, ver, p);

        // ---- 三层回退：把 zip 挪到用户能看见的地方 ----
        // ① API 29+：MediaStore.Downloads，零权限，文件管理器直接可见
        if (Build.VERSION.SDK_INT >= 29) {
            File pub = mediaStoreSave(act, priv, name);
            if (pub != null) {
                priv.delete(); // 公共目录已有，私有副本删掉
                return new DiagResult(pub, "公共下载目录（文件管理器 → Download）");
            }
        }
        // ② 老系统直写 /sdcard/Download（targetSdk 28 + Android 9- 有权限）
        File sd = new File("/sdcard/Download", name);
        if (canWrite(sd.getParentFile())) {
            try {
                java.nio.file.Files.copy(priv.toPath(), sd.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                priv.delete();
                return new DiagResult(sd, "公共下载目录（文件管理器 → Download）");
            } catch (Exception ignored) {
            }
        }
        // ③ 兜底：留在私有目录，弹窗显示绝对路径
        return new DiagResult(priv, "应用私有目录（Android/data/com.magpie.module/files/Download）");
    }

    /** 目录是否可写（实际创建一个探测文件再删掉）。 */
    private static boolean canWrite(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        try {
            File probe = new File(dir, ".magpie-probe");
            if (probe.createNewFile()) {
                probe.delete();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * MediaStore.Downloads 保存（API 29+）：零权限，写完文件管理器直接可见。
     * 成功返回公共目录里的文件路径副本，失败返回 null（调用方走下一层回退）。
     */
    private static File mediaStoreSave(Activity act, File priv, String name) {
        try {
            android.content.ContentResolver cr = act.getContentResolver();
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
            android.net.Uri uri = cr.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return null;
            }
            try (InputStream in = new FileInputStream(priv);
                 java.io.OutputStream os = cr.openOutputStream(uri)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    os.write(b, 0, n);
                }
            }
            // 拿回实际路径（用于返回值；拿不到也无妨，用 uri 描述）
            android.database.Cursor c = cr.query(uri,
                    new String[]{android.provider.MediaStore.MediaColumns.DATA},
                    null, null, null);
            String path = null;
            if (c != null) {
                if (c.moveToFirst()) {
                    path = c.getString(0);
                }
                c.close();
            }
            return path != null ? new File(path) : new File("/sdcard/Download/" + name);
        } catch (Exception e) {
            return null;
        }
    }

    /** 实际写 zip 的过程（收集 + 打包），独立出来供三层回退共用。 */
    private static void writeZip(File out, Activity act, String ver, Progress p)
            throws Exception {

        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
            int i = 0;
            report(p, i++, "读取设备与版本信息");
            put(z, "info.txt", buildInfo(act, ver));

            report(p, i++, "收集网关日志（3000 行）");
            put(z, "magpie.log", safe(GatewayService.lastLog(3000)));

            report(p, i++, "读取崩溃日志");
            put(z, "crash.txt", crashLog());

            report(p, i++, "读取 magpie 状态与设置");
            put(z, "state.json", stateSafe(act));

            report(p, i++, "收集请求明细与流量聚合");
            put(z, "usage-tail.json", usageTail(act));

            report(p, i++, "统计系统口径流量");
            put(z, "netstats.json", netStats(act));

            report(p, i++, "检测 VPN 与代理环境");
            put(z, "vpn.json", vpnInfo(act));

            report(p, i++, "扫描外部连接");
            put(z, "connections.txt", connections());

            report(p, i++, "读取已装插件清单");
            put(z, "plugins.json", pluginsSafe(act));

            report(p, i++, "读取插件加载状态（关键）");
            put(z, "plugins-state.json", pluginsStateSafe(act));

            report(p, i++, "读取供应商列表");
            put(z, "providers.json", providersSafe(act));

            report(p, i++, "读取额度卡");
            put(z, "quotas.json", quotasSafe(act));

            report(p, i++, "读取路由组配置");
            put(z, "groups.json", groupsSafe(act));

            report(p, i++, "扫描数据目录结构");
            put(z, "files.txt", filesTree(act));

            report(p, i++, "读取进程环境变量");
            put(z, "env.txt", envSafe());

            report(p, i++, "读取进程资源占用");
            put(z, "threads.txt", procStats());

            report(p, i++, "读取电源与冻结状态");
            put(z, "battery.txt", batteryState());

            report(p, 100, "打包完成");
        }
    }

    private static void report(Progress p, int percent, String what) {
        if (p != null) {
            try {
                p.step(percent, what);
            } catch (Exception ignored) {
            }
        }
    }

    /** 给用户看的产出位置说明。 */

    // ------------------------------------------------------------------

    static String version(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static String buildInfo(Context ctx, String ver) {
        StringBuilder sb = new StringBuilder();
        sb.append("magpie-android 错误诊断包\n");
        // 收集时间只在这里显示（文件名为了兼容邮件/IM 截掉了），精确到秒
        sb.append("生成时间: ").append(
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).format(new java.util.Date())).append('\n');
        sb.append("壳版本: ").append(ver).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append("网关端口: ").append(GatewayService.gatewayPort(ctx)).append('\n');
        sb.append("网页端口: ").append(GatewayService.webPort(ctx)).append('\n');
        sb.append("局域网共享: ").append(Prefs.lan(ctx) ? "开" : "关").append('\n');
        sb.append("保活: ").append(Prefs.autostart(ctx) ? "开机自启开" : "关").append('\n');
        sb.append("网关就绪: ").append(GatewayService.isReady() ? "是" : "否").append('\n');
        sb.append("模块版本: ").append(GatewayService.moduleVersion()).append('\n');
        sb.append("模块目录可读: ").append(ModuleBridge.bridgeReady() ? "是" : "否（只读桥未挂载）").append('\n');
        sb.append("UID: ").append(android.os.Process.myUid()).append('\n');
        return sb.toString();
    }

    /** 本包最近的 crash 日志；没有 root/adb 权限时返回提示。 */
    static String crashLog() {
        try {
            Process p = new ProcessBuilder("logcat", "-b", "crash", "-d", "-t", "400")
                    .redirectErrorStream(true).start();
            String all = readAll(p.getInputStream());
            if (!p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            StringBuilder sb = new StringBuilder();
            for (String line : all.split("\n")) {
                if (line.contains("com.magpie.module") || line.contains("FATAL")) {
                    sb.append(safe(line)).append('\n');
                }
            }
            return sb.length() == 0 ? "（没有本包的崩溃记录）\n" : sb.toString();
        } catch (Exception e) {
            return "读不到 crash 日志：" + e.getMessage() + "\n";
        }
    }

    private static String stateSafe(Context ctx) {
        try {
            MagpieApi.Result r = MagpieApi.stateLast(ctx);
            if (!r.ok()) {
                return "{\"error\": \"" + safe(r.why()) + "\"}";
            }
            JSONObject o = r.json();
            JSONObject out = new JSONObject();
            out.put("version", o.optString("version", ""));
            JSONObject settings = o.optJSONObject("settings");
            if (settings != null) {
                JSONObject s = new JSONObject();
                // 白名单：脱敏相关 + 语言 + 代理（无凭据）+ 模型选择
                for (String k : new String[]{"redact", "redactPersonal", "redactModel",
                        "redactModelName", "redactModelOff", "redactShape",
                        "redactWordsAnyCase", "lang", "proxy", "pluginProxy",
                        "lan", "gatewayMode", "theme", "noAutoUpdate",
                        "updateChannel", "vision", "trayUsageEvery", "dayStart"}) {
                    if (settings.has(k)) {
                        s.put(k, settings.get(k));
                    }
                }
                JSONArray words = settings.optJSONArray("redactWords");
                if (words != null) {
                    // 只留数量，不留词内容
                    s.put("redactWordsCount", words.length());
                }
                out.put("settings", s);
            }
            return out.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    /** 最近 100 条请求摘要 + 当日/本周聚合（含上下行字节）。 */
    private static String usageTail(Context ctx) {
        try {
            MagpieApi.Result r = MagpieApi.requests(ctx, "today", 100);
            if (!r.ok()) {
                return "{\"error\": \"" + safe(r.why()) + "\"}";
            }
            JSONObject o = r.json();
            JSONArray rows = NativeData.arr(o, "rows");
            JSONArray out = new JSONArray();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = NativeData.at(rows, i);
                if (row == null) {
                    continue;
                }
                JSONObject slim = new JSONObject();
                slim.put("t", row.optString("t", ""));
                slim.put("model", row.optString("model", ""));
                slim.put("provider", row.optString("providerName",
                        row.optString("provider", "")));
                slim.put("agent", row.optString("agentName", row.optString("agent", "")));
                slim.put("status", row.optInt("status", 0));
                slim.put("ms", row.optDouble("ms", 0));
                slim.put("in", row.optInt("in", 0));
                slim.put("out", row.optInt("out", 0));
                String err = row.optString("err", "");
                if (!err.isEmpty()) {
                    slim.put("err", err.length() > 200
                            ? err.substring(0, 200) + "…" : err);
                }
                out.put(slim);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("total", o.optInt("total", 0));
            wrap.put("rows", out);
            // 聚合：today + 7d，网关口径的字节数 —— 与系统统计对比可判断
            // 「流量是不是 AI 请求产生的」
            JSONObject agg = new JSONObject();
            for (String period : new String[]{"today", "7d"}) {
                try {
                    MagpieApi.Result u = MagpieApi.usage(ctx, period);
                    if (u.ok()) {
                        JSONObject uo = u.json();
                        JSONObject d = new JSONObject();
                        d.put("calls", NativeData.num(uo, "calls"));
                        d.put("errors", NativeData.num(uo, "errors"));
                        d.put("retried", NativeData.num(uo, "retried"));
                        d.put("canceled", NativeData.num(uo, "canceled"));
                        d.put("up_bytes", NativeData.lng(uo, "up_bytes"));
                        d.put("down_bytes", NativeData.lng(uo, "down_bytes"));
                        d.put("tokens", NativeData.num(uo, "in")
                                + NativeData.num(uo, "out"));
                        agg.put(period, d);
                    }
                } catch (Exception ignored) {
                }
            }
            wrap.put("aggregate", agg);
            return wrap.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    private static void put(ZipOutputStream z, String name, String content)
            throws Exception {
        if (content == null) {
            content = "";
        }
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bos.write(b, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static String safe(String s) {
        if (s == null) {
            return "";
        }
        // web key 与常见凭据一律打码
        return s.replaceAll("k=[0-9a-f]{8,}", "k=***")
                .replaceAll("(?i)(key|token|secret|password|passwd|apikey|api_key)=[^\\s\"&,}]+",
                        "$1=***")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{10,}", "sk-***")
                .replaceAll("\\bgh[pousr]_[A-Za-z0-9]{20,}", "gh*_***")
                .replaceAll("\\bgithub_pat_[A-Za-z0-9_]{20,}", "github_pat_***");
    }

    // ==================================================================
    // 网络诊断
    // ==================================================================

    /** 系统口径的本 App 流量（TrafficStats uid 计数，开机以来累计）+ 代理类 App 对比。 */
    private static String netStats(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            int uid = android.os.Process.myUid();
            long rx = TrafficStats.getUidRxBytes(uid);
            long tx = TrafficStats.getUidTxBytes(uid);
            out.put("myUid", uid);
            out.put("rx_bytes", rx);
            out.put("tx_bytes", tx);
            out.put("rx_human", NativeData.bytes(rx));
            out.put("tx_human", NativeData.bytes(tx));
            long allRx = TrafficStats.getTotalRxBytes();
            long allTx = TrafficStats.getTotalTxBytes();
            out.put("total_rx_bytes", allRx);
            out.put("total_tx_bytes", allTx);
            long my = rx + tx;
            long all = allRx + allTx;
            out.put("my_share_pct", all > 0 ? Math.round(my * 10000.0 / all) / 100.0 : 0);
            out.put("note", "开机以来累计，与系统流量排行同源");

            // 常见代理/VPN 的 uid 流量对比
            JSONArray apps = new JSONArray();
            String[] candidates = {"flclash", "com.github.kr328.clash",
                    "com.v2ray.ang", "com.v2ray.ang.fdroid", "moe.matsuri.lite",
                    "eu.nevila.v2ray", "io.github.dogedog.doge",
                    "com.github.metacubex.clash.meta"};
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            for (String pkg : candidates) {
                try {
                    int u = pm.getPackageUid(pkg, 0);
                    if (u < 0) {
                        continue;
                    }
                    JSONObject a = new JSONObject();
                    a.put("pkg", pkg);
                    a.put("rx", TrafficStats.getUidRxBytes(u));
                    a.put("tx", TrafficStats.getUidTxBytes(u));
                    apps.put(a);
                } catch (Exception ignored) {
                }
            }
            out.put("proxy_apps", apps);
            return out.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    /** VPN 状态：是否激活、接口、地址，以及已装的代理类应用。 */
    private static String vpnInfo(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "{\"error\": \"no connectivity service\"}";
            }
            Network[] nets = cm.getAllNetworks();
            JSONArray list = new JSONArray();
            boolean vpnActive = false;
            for (Network n : nets) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                if (cap == null) {
                    continue;
                }
                JSONObject o = new JSONObject();
                boolean vpn = cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                o.put("vpn", vpn);
                o.put("wifi", cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                o.put("cellular", cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                if (vpn) {
                    vpnActive = true;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        o.put("interface", lp.getInterfaceName());
                        JSONArray addrs = new JSONArray();
                        if (Build.VERSION.SDK_INT >= 30) {
                            for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                                addrs.put(la.getAddress().getHostAddress());
                            }
                        }
                        o.put("addresses", addrs);
                    }
                }
                list.put(o);
            }
            out.put("vpn_active", vpnActive);
            out.put("networks", list);

            JSONArray installed = new JSONArray();
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            String[] known = {"flclash", "com.github.kr328.clash", "com.v2ray.ang",
                    "com.v2ray.ang.fdroid", "moe.matsuri.lite", "eu.nevila.v2ray",
                    "com.github.metacubex.clash.meta"};
            for (String pkg : known) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    installed.put(pkg);
                } catch (Exception ignored) {
                }
            }
            out.put("known_proxy_apps_installed", installed);
            return out.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    /** 本 App 进程树的外部 TCP 连接（不含 loopback），带 pid。 */
    private static String connections() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Map<String, String> inodes = new java.util.HashMap<>();
            File proc = new File("/proc");
            File[] dirs = proc.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (!d.isDirectory()) {
                        continue;
                    }
                    String name = d.getName();
                    boolean num = true;
                    for (char c : name.toCharArray()) {
                        if (c < '0' || c > '9') {
                            num = false;
                            break;
                        }
                    }
                    if (!num) {
                        continue;
                    }
                    File[] fds = new File(d, "fd").listFiles();
                    if (fds == null) {
                        continue;
                    }
                    for (File f : fds) {
                        String target = readlink(f.getAbsolutePath());
                        if (target.startsWith("socket:")) {
                            String ino = target.substring(8, target.length() - 1);
                            inodes.put(ino, name);
                        }
                    }
                }
            }
            sb.append("# 本 uid 的外部 TCP 连接（不含 loopback）\n");
            sb.append("# 若 magpie 在跑流量，这里能看到连到哪个 IP:端口、属于哪个 pid\n");
            sb.append(String.format("%-24s %-24s %-12s %s%n",
                    "local", "remote", "state", "pid"));
            int shown = 0;
            shown += dumpTcp(sb, "/proc/net/tcp", false, inodes);
            shown += dumpTcp(sb, "/proc/net/tcp6", true, inodes);
            if (shown == 0) {
                sb.append("（当前无外部连接）\n");
            }
            sb.append("\n# 进程名对照（pid -> name）\n");
            for (String pid : new java.util.TreeSet<>(inodes.values())) {
                sb.append(pid).append(" -> ").append(procName(pid)).append('\n');
            }
        } catch (Exception e) {
            sb.append("读取失败：").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String procName(String pid) {
        try {
            String s = readAll(new FileInputStream("/proc/" + pid + "/comm"));
            return s.trim();
        } catch (Exception e) {
            return "?";
        }
    }

    private static int dumpTcp(StringBuilder sb, String path, boolean ipv6,
                               java.util.Map<String, String> inodes) {
        int n = 0;
        try {
            String all = readAll(new FileInputStream(path));
            String[] lines = all.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String[] cols = lines[i].trim().split("\\s+");
                if (cols.length < 10) {
                    continue;
                }
                String local = cols[1], remote = cols[2], st = cols[3], ino = cols[9];
                String pid = inodes.get(ino);
                if (pid == null) {
                    continue;
                }
                String rIp = ipOf(remote, ipv6);
                if (rIp.startsWith("127.") || rIp.equals("::1") || rIp.startsWith("0.")) {
                    continue;
                }
                sb.append(String.format("%-24s %-24s %-12s %s%n",
                        addrOf(local, ipv6), addrOf(remote, ipv6), tcpState(st), pid));
                n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    private static String addrOf(String hex, boolean ipv6) {
        try {
            String[] p = hex.split(":");
            long port = Long.parseLong(p[1], 16);
            if (!ipv6) {
                long v = Long.parseLong(p[0], 16);
                return String.format("%d.%d.%d.%d:%d",
                        v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff, port);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                long v = Long.parseLong(p[0].substring(i * 8, i * 8 + 8), 16);
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format("%02x%02x:%02x%02x",
                        v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff));
            }
            return sb.append(':').append(port).toString();
        } catch (Exception e) {
            return hex;
        }
    }

    private static String ipOf(String hexAddr, boolean ipv6) {
        String a = addrOf(hexAddr, ipv6);
        int i = a.lastIndexOf(':');
        return i > 0 ? a.substring(0, i) : a;
    }

    private static String tcpState(String hex) {
        switch (hex) {
            case "01": return "ESTABLISHED";
            case "02": return "SYN_SENT";
            case "03": return "SYN_RECV";
            case "06": return "TIME_WAIT";
            case "08": return "CLOSE_WAIT";
            default: return hex;
        }
    }

    private static String readlink(String path) {
        try {
            Process p = new ProcessBuilder("readlink", path)
                    .redirectErrorStream(true).start();
            String s = readAll(p.getInputStream()).trim();
            p.waitFor();
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    // ==================================================================
    // 插件
    // ==================================================================

    /** 已装插件的 spec / 版本 / 开关（不含 options 里的人设文本与密钥）。 */
    static String pluginsSafe(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            File f = new File(GatewayService.dataDir(ctx), "plugins.json");
            if (!f.isFile()) {
                return "{\"note\": \"没有 plugins.json\"}";
            }
            JSONObject o = new JSONObject(readAll(new FileInputStream(f)));
            JSONArray ps = o.optJSONArray("plugins");
            JSONArray out2 = new JSONArray();
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = NativeData.at(ps, i);
                    if (p == null) {
                        continue;
                    }
                    JSONObject slim = new JSONObject();
                    slim.put("spec", p.optString("spec", ""));
                    if (p.has("off")) {
                        slim.put("off", p.optBoolean("off", false));
                    }
                    JSONObject opts = p.optJSONObject("options");
                    if (opts != null) {
                        // 只记键名，不记值（人设文本、密钥都在值里）
                        JSONArray keys = new JSONArray();
                        java.util.Iterator<String> it = opts.keys();
                        while (it.hasNext()) {
                            keys.put(it.next());
                        }
                        slim.put("optionKeys", keys);
                    }
                    out2.put(slim);
                }
            }
            out.put("plugins", out2);
            return out.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    /**
     * GET /api/plugins 的脱敏快照 —— 定位「哪个插件坏了」的关键。
     *
     * <p>上游这个接口返回每个插件的加载错误（errs）、声明的 provider 名、
     * 注册的工具、Bun 版本。这里只取这几项，剥掉模型列表详情（体积大）
     * 与任何凭据。
     */
    static String pluginsStateSafe(Context ctx) {
        try {
            MagpieApi.Result r = MagpieApi.get(ctx, "/api/plugins", 8000, 60000);
            if (!r.ok()) {
                return "{\"error\": \"" + safe(r.why()) + "\"}";
            }
            JSONObject o = r.json();
            JSONObject out = new JSONObject();
            out.put("bun", o.optBoolean("bun", false));
            out.put("bunVer", o.optString("bunVer", ""));
            String err = o.optString("error", "");
            if (!err.isEmpty()) {
                out.put("error", safe(err));
            }
            JSONArray plugins = NativeData.arr(o, "plugins");
            JSONArray list = new JSONArray();
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject p = NativeData.at(plugins, i);
                if (p == null) {
                    continue;
                }
                JSONObject slim = new JSONObject();
                slim.put("spec", p.optString("spec", ""));
                slim.put("version", p.optString("version", ""));
                String perr = p.optString("error", "");
                if (!perr.isEmpty()) {
                    // ★ 这就是「哪个插件坏了」的直接答案
                    slim.put("error", safe(perr.length() > 400
                            ? perr.substring(0, 400) + "…" : perr));
                }
                JSONArray provs = p.optJSONArray("providers");
                if (provs != null) {
                    JSONArray pn = new JSONArray();
                    for (int j = 0; j < provs.length(); j++) {
                        Object v = provs.opt(j);
                        if (v instanceof JSONObject) {
                            JSONObject po = (JSONObject) v;
                            pn.put(po.optString("name", po.optString("id", "")));
                        } else if (v != null) {
                            pn.put(String.valueOf(v));
                        }
                    }
                    slim.put("providers", pn);
                    slim.put("providerCount", pn.length());
                }
                JSONArray tools = p.optJSONArray("tools");
                if (tools != null) {
                    slim.put("toolCount", tools.length());
                }
                list.put(slim);
            }
            out.put("plugins", list);
            out.put("pluginCount", list.length());
            return out.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    // ==================================================================
    // 供应商 / 额度 / 路由组
    // ==================================================================

    /** provider 列表：id / 名称 / 模型数 / 是否登录（不含任何密钥）。 */
    private static String providersSafe(Context ctx) {
        try {
            MagpieApi.Result r = MagpieApi.providersLast(ctx);
            if (!r.ok()) {
                return "{\"error\": \"" + safe(r.why()) + "\"}";
            }
            JSONObject o = r.json();
            JSONArray arr = NativeData.arr(o, "providers");
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = NativeData.at(arr, i);
                if (p == null) {
                    continue;
                }
                JSONObject slim = new JSONObject();
                slim.put("id", p.optString("id", ""));
                slim.put("name", p.optString("name", ""));
                JSONArray models = p.optJSONArray("models");
                slim.put("modelCount", models == null ? 0 : models.length());
                slim.put("signedIn", p.optBoolean("signedIn", false));
                slim.put("authType", p.optString("authType", ""));
                JSONArray accounts = p.optJSONArray("accounts");
                slim.put("accountCount", accounts == null ? 0 : accounts.length());
                out.put(slim);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("providers", out);
            wrap.put("count", out.length());
            return wrap.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    /** 额度卡：provider / 窗口 / 用量百分比（账号名打码）。 */
    private static String quotasSafe(Context ctx) {
        try {
            MagpieApi.Result r = MagpieApi.quotas(ctx);
            if (!r.ok()) {
                return "{\"error\": \"" + safe(r.why()) + "\"}";
            }
            JSONArray arr = r.json() == null ? null : new JSONArray(r.json().toString());
            JSONArray out = new JSONArray();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject q = NativeData.at(arr, i);
                    if (q == null) {
                        continue;
                    }
                    JSONObject slim = new JSONObject();
                    slim.put("provider", q.optString("provider", ""));
                    slim.put("kind", q.optString("kind", ""));
                    // 账号标识只留掩码（前 3 + 后 2）
                    String user = q.optString("user", "");
                    if (!user.isEmpty()) {
                        slim.put("user", maskUser(user));
                    }
                    JSONArray windows = q.optJSONArray("windows");
                    if (windows != null) {
                        JSONArray ws = new JSONArray();
                        for (int j = 0; j < windows.length(); j++) {
                            JSONObject w = NativeData.at(windows, j);
                            if (w == null) {
                                continue;
                            }
                            JSONObject sw = new JSONObject();
                            sw.put("label", w.optString("label", ""));
                            sw.put("used", w.optDouble("used", 0));
                            sw.put("limit", w.optDouble("limit", 0));
                            sw.put("unit", w.optString("unit", ""));
                            sw.put("resets", w.optString("resets", ""));
                            ws.put(sw);
                        }
                        slim.put("windows", ws);
                    }
                    out.put(slim);
                }
            }
            JSONObject wrap = new JSONObject();
            wrap.put("quotas", out);
            wrap.put("count", out.length());
            return wrap.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    /** 账号名掩码：保留前 3 后 2，中间星号。 */
    private static String maskUser(String u) {
        if (u == null || u.length() <= 5) {
            return "***";
        }
        return u.substring(0, 3) + "***" + u.substring(u.length() - 2);
    }

    /** 路由组：名字 / pattern / 成员数。 */
    private static String groupsSafe(Context ctx) {
        try {
            MagpieApi.Result r = MagpieApi.groups(ctx);
            if (!r.ok()) {
                return "{\"error\": \"" + safe(r.why()) + "\"}";
            }
            JSONObject o = r.json();
            JSONArray arr = NativeData.arr(o, "groups");
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = NativeData.at(arr, i);
                if (g == null) {
                    continue;
                }
                JSONObject slim = new JSONObject();
                slim.put("name", g.optString("name", ""));
                JSONArray pats = g.optJSONArray("patterns");
                if (pats == null) {
                    pats = g.optJSONArray("models");
                }
                if (pats != null) {
                    slim.put("patterns", pats);
                }
                JSONArray members = g.optJSONArray("members");
                slim.put("memberCount", members == null ? 0 : members.length());
                out.put(slim);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("groups", out);
            wrap.put("count", out.length());
            return wrap.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + safe(e.getMessage()) + "\"}";
        }
    }

    // ==================================================================
    // 文件 / 进程 / 电源
    // ==================================================================

    /** data 目录结构与大小：找异常膨胀的缓存、日志、会话记录。 */
    static String filesTree(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# magpie data 目录（两层，> 1MB 的才列，按大小排序）\n\n");
        try {
            File data = GatewayService.dataDir(ctx);
            sb.append("data/ 总大小: ").append(human(dirSize(data))).append("\n\n");
            // 第一层目录各自大小
            File[] kids = data.listFiles();
            java.util.List<File> dirs = new java.util.ArrayList<>();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isDirectory()) {
                        dirs.add(k);
                    }
                }
            }
            dirs.sort((a, b) -> Long.compare(dirSize(b), dirSize(a)));
            sb.append("== 目录（按大小）==\n");
            for (File d : dirs) {
                long sz = dirSize(d);
                sb.append(String.format("%10s  %s%n", human(sz), d.getName()));
            }
            sb.append("\n== data 根下的文件 ==\n");
            if (kids != null) {
                java.util.List<File> files = new java.util.ArrayList<>();
                for (File k : kids) {
                    if (k.isFile()) {
                        files.add(k);
                    }
                }
                files.sort((a, b) -> Long.compare(b.length(), a.length()));
                for (File f : files) {
                    sb.append(String.format("%10s  %s%n", human(f.length()), f.getName()));
                }
            }
            // cache 下两层，只列 > 1MB
            File cache = new File(data, "cache");
            if (cache.isDirectory()) {
                sb.append("\n== cache/（> 1MB 的条目）==\n");
                walkBig(sb, cache, 0, 2, 1024 * 1024);
            }
            // routing 目录（每天的路由记录）
            File routing = new File(data, "routing");
            if (routing.isDirectory()) {
                sb.append("\n== routing/（每日路由记录）==\n");
                File[] rs = routing.listFiles();
                if (rs != null) {
                    java.util.Arrays.sort(rs);
                    for (File f : rs) {
                        sb.append(String.format("%10s  %s%n", human(f.length()), f.getName()));
                    }
                }
            }
            // logs
            File logs = new File(ctx.getFilesDir(), "logs");
            if (logs.isDirectory()) {
                sb.append("\n== logs/ ==\n");
                File[] ls = logs.listFiles();
                if (ls != null) {
                    for (File f : ls) {
                        sb.append(String.format("%10s  %s%n", human(f.length()), f.getName()));
                    }
                }
            }
        } catch (Exception e) {
            sb.append("扫描失败：").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static void walkBig(StringBuilder sb, File dir, int depth, int maxDepth, long min) {
        if (depth > maxDepth) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        java.util.Arrays.sort(kids, (a, b) -> {
            long sa = a.isDirectory() ? dirSize(a) : a.length();
            long sb2 = b.isDirectory() ? dirSize(b) : b.length();
            return Long.compare(sb2, sa);
        });
        for (File k : kids) {
            long sz = k.isDirectory() ? dirSize(k) : k.length();
            if (sz < min) {
                continue;
            }
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                indent.append("  ");
            }
            sb.append(String.format("%10s  %s%s%n", human(sz), indent, k.getName()));
            if (k.isDirectory()) {
                walkBig(sb, k, depth + 1, maxDepth, min);
            }
        }
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        long t = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                t += dirSize(k);
            }
        }
        return t;
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

    /** magpie 子进程的环境变量（代理/地址/时区），值里的凭据打码。 */
    static String envSafe() {
        StringBuilder sb = new StringBuilder();
        sb.append("# magpie 内核进程的环境变量（凭据打码）\n\n");
        try {
            String pid = findPid("magpie");
            if (pid == null) {
                return sb.append("找不到 magpie 进程\n").toString();
            }
            sb.append("pid: ").append(pid).append('\n');
            sb.append("cmdline: ").append(
                    readAll(new FileInputStream("/proc/" + pid + "/cmdline"))
                            .replace('\0', ' ').trim()).append("\n\n");
            String env = readAll(new FileInputStream("/proc/" + pid + "/environ"));
            for (String e : env.split("\0")) {
                if (e.isEmpty()) {
                    continue;
                }
                // 只留排障相关的，其余略
                String upper = e.toUpperCase();
                if (upper.startsWith("PATH=") || upper.startsWith("BOOTCLASSPATH=")
                        || upper.startsWith("ANDROID_") || upper.startsWith("DEX2OAT")
                        || upper.startsWith("SYSTEMSERVER") || upper.startsWith("LC_")
                        || upper.startsWith("LANG=") || upper.startsWith("TMPDIR=")) {
                    continue;
                }
                sb.append(safe(e)).append('\n');
            }
        } catch (Exception e) {
            sb.append("读取失败：").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** magpie / bun 的资源占用：线程数、CPU 时间、fd 数、内存。 */
    static String procStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 进程资源占用\n\n");
        try {
            String pid = findPid("magpie");
            if (pid == null) {
                return sb.append("找不到 magpie 进程\n").toString();
            }
            sb.append("== magpie (pid ").append(pid).append(") ==\n");
            appendProcStats(sb, pid);
            String bun = findPid("magpie-bun");
            if (bun != null) {
                sb.append("\n== magpie-bun (pid ").append(bun).append(") ==\n");
                appendProcStats(sb, bun);
            } else {
                sb.append("\n（没有 magpie-bun 进程）\n");
            }
            // 宿主的子进程数
            sb.append("\n== 本 uid 的进程列表 ==\n");
            File proc = new File("/proc");
            File[] dirs = proc.listFiles();
            int uid = android.os.Process.myUid();
            if (dirs != null) {
                for (File d : dirs) {
                    String name = d.getName();
                    boolean num = true;
                    for (char c : name.toCharArray()) {
                        if (c < '0' || c > '9') {
                            num = false;
                            break;
                        }
                    }
                    if (!num) {
                        continue;
                    }
                    try {
                        String status = readAll(new FileInputStream(
                                new File(d, "status")));
                        if (!status.contains("Uid:\t" + uid + "\t")) {
                            continue;
                        }
                        String comm = procName(name);
                        String state = "";
                        for (String line : status.split("\n")) {
                            if (line.startsWith("State:")) {
                                state = line.substring(6).trim();
                                break;
                            }
                        }
                        sb.append("  pid=").append(name)
                                .append("  ").append(comm)
                                .append("  [").append(state).append("]\n");
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            sb.append("读取失败：").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static void appendProcStats(StringBuilder sb, String pid) {
        try {
            // /proc/<pid>/stat: utime=14 stime=15 threads=20
            String stat = readAll(new FileInputStream("/proc/" + pid + "/stat"));
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            long utime = Long.parseLong(f[11]);   // 字段 14 - 3
            long stime = Long.parseLong(f[12]);   // 字段 15 - 3
            long threads = Long.parseLong(f[17]); // 字段 20 - 3
            sb.append("  CPU 累计: ").append((utime + stime) * 10).append(" ms");
            sb.append("（用户 ").append(utime * 10).append(" / 内核 ").append(stime * 10).append("）\n");
            sb.append("  线程数: ").append(threads).append('\n');

            String status = readAll(new FileInputStream("/proc/" + pid + "/status"));
            for (String line : status.split("\n")) {
                if (line.startsWith("VmRSS:") || line.startsWith("VmSize:")
                        || line.startsWith("Threads:")) {
                    sb.append("  ").append(line.trim()).append('\n');
                }
            }
            // fd 数
            File[] fds = new File("/proc/" + pid + "/fd").listFiles();
            sb.append("  fd 数: ").append(fds == null ? "无权限" : fds.length).append('\n');
            // 运行时长
            String uptime = readAll(new FileInputStream("/proc/uptime")).trim().split(" ")[0];
            sb.append("  系统 uptime: ").append(uptime).append(" 秒\n");
        } catch (Exception e) {
            sb.append("  读取失败：").append(e.getMessage()).append('\n');
        }
    }

    private static String findPid(String comm) {
        try {
            File proc = new File("/proc");
            File[] dirs = proc.listFiles();
            if (dirs == null) {
                return null;
            }
            for (File d : dirs) {
                String name = d.getName();
                boolean num = true;
                for (char c : name.toCharArray()) {
                    if (c < '0' || c > '9') {
                        num = false;
                        break;
                    }
                }
                if (!num) {
                    continue;
                }
                try {
                    String c = readAll(new FileInputStream(
                            new File(d, "comm"))).trim();
                    if (comm.equals(c)) {
                        return name;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 电源与冻结状态：wake lock、cgroup 冻结、进程优先级。 */
    static String batteryState() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 电源与冻结状态\n\n");
        sb.append("== dumpsys power（wake lock 摘要）==\n");
        sb.append(runCmd("dumpsys power", "Wake Locks", 40));
        sb.append("\n== dumpsys deviceidle ==\n");
        sb.append(runCmd("dumpsys deviceidle", "mState", 10));
        sb.append("\n== 本进程 cgroup（freezer 是否被冻）==\n");
        try {
            String pid = findPid("magpie");
            if (pid != null) {
                sb.append(readAll(new FileInputStream("/proc/" + pid + "/cgroup")));
            }
        } catch (Exception e) {
            sb.append("读不到 cgroup：").append(e.getMessage()).append('\n');
        }
        sb.append("\n== 进程 oom_adj / priority ==\n");
        try {
            String pid = findPid("magpie");
            if (pid != null) {
                sb.append("oom_score_adj: ")
                        .append(readAll(new FileInputStream(
                                "/proc/" + pid + "/oom_score_adj")).trim())
                        .append('\n');
            }
        } catch (Exception e) {
            sb.append("读不到：").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** 跑一条命令，只保留含 key 的若干行（避免整份 dumpsys 太大）。 */
    private static String runCmd(String cmd, String key, int maxLines) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder(cmd.split(" "))
                    .redirectErrorStream(true).start();
            String all = readAll(p.getInputStream());
            // 权限被拒/SELinux 拦截时子进程可能挂住不退出，别让诊断卡死
            if (!p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                sb.append("（命令 6 秒没退出，已被强杀——多半是无权限）\n");
            }
            int n = 0;
            for (String line : all.split("\n")) {
                if (line.contains(key) || line.contains("magpie")
                        || line.contains("Wake Locks")) {
                    sb.append(safe(line)).append('\n');
                    if (++n >= maxLines) {
                        sb.append("…（已截断）\n");
                        break;
                    }
                }
            }
            if (sb.length() == 0) {
                sb.append("（没有匹配行，可能无权限）\n");
            }
        } catch (Exception e) {
            sb.append("执行失败：").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
