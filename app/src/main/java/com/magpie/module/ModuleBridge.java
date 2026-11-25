package com.magpie.module;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块内核的桥：所有「读模块文件 / 写模块文件」都从这里过。
 *
 * <h3>为什么需要这一层</h3>
 * 模块版的内核跑在 Magisk 模块里，路径是
 * {@code /data/adb/modules/magpie_native/}，属主 root、父目录 0700，
 * 普通 App 连遍历都做不到。而壳（这个 APK）是普通 uid。
 *
 * <h3>三条通路</h3>
 * <ol>
 *   <li><b>只读桥</b>（不需要 root）：模块的 service.sh 在 root 下做
 *       {@code mount -o bind} 把模块目录挂到 {@code /data/local/tmp/magpie-native}，
 *       并把 logs / home / webkey.txt 放开成 0755/0644。
 *       {@code /data/local/tmp} 是 0771（其它 uid 可遍历），所以壳能直接
 *       {@code new File()} 读这些文件。<b>data/ 目录保持 0700，密钥不外泄。</b></li>
 *   <li><b>Shizuku</b>（uid 2000 或 0）：读 {@code data/} 里的配置、启停模块服务、
 *       清理缓存、写插件目录。shell 身份能读写 {@code /data/adb/modules/}
 *       （Magisk 的模块目录对 shell 放开）。</li>
 *   <li><b>root 兜底</b>：Shizuku 不可用时，用 {@code su -c} 走一遍同样的命令。</li>
 * </ol>
 *
 * <h3>降级原则</h3>
 * 任何一条通路失败都只让「某一项功能不可用」，绝不让整个界面白屏。
 * 所以这里每个方法都返回 null / 空列表 / false，由调用方决定怎么显示。
 */
final class ModuleBridge {

    private static final String TAG = "MagpieModuleBridge";

    /** 模块 id，与 module.prop 的 id= 一致。 */
    static final String MODULE_ID = "magpie_native";

    /** 模块目录（root 才进得去）。 */
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;

    /** 只读桥（普通 App 可读，模块 service.sh 挂的）。 */
    static final String BRIDGE_DIR = "/data/local/tmp/magpie-native";

    /** 网关端口。模块内核固定监听这个。 */
    static final int GATEWAY_PORT = 3425;

    /** 网页后台端口。 */
    static final int WEB_PORT = 3430;

    private ModuleBridge() {
    }

    // ==================================================================
    // 只读桥
    // ==================================================================

    /**
     * 只读桥里的路径。
     *
     * <p>桥没挂上（模块没装 / 没启动）时这个 File 依然可以构造，
     * 只是 {@code exists()} 为 false —— 调用方据此判断。
     */
    static File bridge() {
        return new File(BRIDGE_DIR);
    }

    static File bridgeFile(String name) {
        return new File(BRIDGE_DIR, name);
    }

    /** 只读桥是否可用（模块装好并跑过 service.sh）。 */
    static boolean bridgeReady() {
        return bridge().isDirectory();
    }

    /**
     * 网页后台的 key。
     *
     * <p>模块的 service.sh 生成一次并固定写在模块根的 {@code webkey.txt}，
     * 同时镜像到只读桥。这样重启模块不会换 key，壳也不用去日志里刮。
     *
     * @return 32 位 hex；读不到返回 ""（调用方退回「从日志里刮」或提示）
     */
    static String webKey() {
        String k = readSmall(bridgeFile("webkey.txt"));
        if (k == null || k.isEmpty()) {
            // 兜底：老版本模块把 key 只打在日志里，刮最后一条 ?k= 链接
            k = scrapeKeyFromLog();
        }
        if (k == null) {
            return "";
        }
        k = k.trim();
        return k.length() >= 16 ? k : "";
    }

    /** 从 magpie.log 最后一条带 k= 的链接里刮 key。 */
    private static String scrapeKeyFromLog() {
        String log = readSmall(bridgeFile("logs/magpie.log"));
        if (log == null) {
            return null;
        }
        int idx = log.lastIndexOf("k=");
        if (idx < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = idx + 2; i < log.length(); i++) {
            char c = log.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '~' || c == '-') {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 模块内核的日志（只读桥里的 {@code logs/magpie.log}）。
     *
     * @param tailLines 只要最后这么多行；&lt;=0 表示全要
     */
    static String log(int tailLines) {
        File f = bridgeFile("logs/magpie.log");
        if (!f.isFile()) {
            return "";
        }
        // 大文件只读尾部：从末尾倒着读 256KB，够 3000 行了
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - 256 * 1024);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String all = new String(buf, StandardCharsets.UTF_8);
            if (from > 0) {
                int nl = all.indexOf('\n');
                if (nl >= 0) {
                    all = all.substring(nl + 1);
                }
            }
            if (tailLines <= 0) {
                return all;
            }
            int count = 0;
            for (int i = all.length() - 1; i >= 0; i--) {
                if (all.charAt(i) == '\n') {
                    count++;
                    if (count > tailLines) {
                        return all.substring(i + 1);
                    }
                }
            }
            return all;
        } catch (Exception e) {
            Log.w(TAG, "读日志失败: " + e);
            return "";
        }
    }

    /** watchdog 的日志。 */
    static String watchdogLog(int tailLines) {
        return tail(bridgeFile("logs/watchdog.log"), tailLines);
    }

    private static String tail(File f, int lines) {
        if (!f.isFile()) {
            return "";
        }
        List<String> all = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                all.add(line);
            }
        } catch (Exception e) {
            return "";
        }
        int from = lines <= 0 ? 0 : Math.max(0, all.size() - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.size(); i++) {
            sb.append(all.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static String readSmall(File f) {
        if (f == null || !f.isFile() || f.length() > 4 * 1024 * 1024) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================================================================
    // 模块状态
    // ==================================================================

    /** 模块是否装上（看 module.prop 在不在，走 Shizuku/root 判）。 */
    static boolean installed() {
        if (bridgeReady()) {
            return true;
        }
        ShizukuShell.Result r = shell("[ -f " + MODULE_DIR + "/module.prop ] && echo yes");
        return r != null && r.out != null && r.out.trim().equals("yes");
    }

    /** 模块版本（module.prop 的 version= 那行）。 */
    static String version() {
        String prop = shellOut("cat " + MODULE_DIR + "/module.prop 2>/dev/null");
        if (prop == null) {
            return "";
        }
        for (String line : prop.split("\n")) {
            if (line.startsWith("version=")) {
                return line.substring(8).trim();
            }
        }
        return "";
    }

    /** 模块内核的进程 pid，没在跑返回 -1。 */
    static int pid() {
        String out = shellOut("cat " + MODULE_DIR + "/magpie.pid 2>/dev/null");
        if (out == null) {
            return -1;
        }
        try {
            int p = Integer.parseInt(out.trim());
            if (p > 0 && new File("/proc/" + p).isDirectory()) {
                return p;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** 进程是否活着（不依赖 pid 文件，扫 /proc 更准）。 */
    static boolean running() {
        return GatewayService.portOpen("127.0.0.1", GATEWAY_PORT);
    }

    /** 重启模块服务：先杀进程让 watchdog 拉起来，watchdog 不在就直接调模块脚本。 */
    static boolean restart() {
        // 优先让模块自己的脚本干（它知道 HOME/PATH 怎么设）
        ShizukuShell.Result r = shell(
                "sh " + MODULE_DIR + "/scripts/restart.sh " + MODULE_DIR + " 2>&1");
        if (r != null && r.ok()) {
            return true;
        }
        // 兜底：杀进程，watchdog 10 秒内会拉回来
        shell("kill $(cat " + MODULE_DIR + "/magpie.pid 2>/dev/null) 2>/dev/null");
        return true;
    }

    /** 停掉模块内核（watchdog 也停，否则 10 秒后又起来）。 */
    static void stop() {
        shell("sh " + MODULE_DIR + "/scripts/stop.sh " + MODULE_DIR + " 2>&1");
    }

    /** 启动模块内核。 */
    static void start() {
        shell("sh " + MODULE_DIR + "/service.sh 2>&1");
    }

    // ==================================================================
    // 写操作（需要 Shizuku 或 root）
    // ==================================================================

    /** 当前有没有可用的提权通路。 */
    static boolean canWrite() {
        return ShizukuShell.available() && ShizukuShell.granted();
    }

    /** 一句话说明写操作为什么不可用。 */
    static String writeBlocker() {
        if (!ShizukuShell.available()) {
            return "需要 Shizuku（或 root）才能改模块文件。Shizuku 没在运行。";
        }
        if (!ShizukuShell.granted()) {
            return "需要 Shizuku 授权才能改模块文件。点「去授权」给一次权限。";
        }
        return "";
    }

    /** 模块 HOME（agent 的 ~/.claude 等都在这里）。 */
    static String moduleHome() {
        return MODULE_DIR + "/home";
    }

    /** 模块数据目录（settings.json / providers.json / usage.jsonl）。 */
    static String moduleData() {
        return MODULE_DIR + "/data";
    }

    // ==================================================================
    // shell
    // ==================================================================

    /**
     * 走 Shizuku 跑一条命令；Shizuku 不可用时用 su 兜底。
     *
     * @return 结果；两条路都不通返回 null
     */
    static ShizukuShell.Result shell(String cmd) {
        if (ShizukuShell.available() && ShizukuShell.granted()) {
            return ShizukuShell.exec(cmd);
        }
        // root 兜底：su -c
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c",
                    "su -c " + quote(cmd)).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            String out = sb.toString().trim();
            return new ShizukuShell.Result(code, out, code == 0 ? null : out);
        } catch (Exception e) {
            return null;
        }
    }

    /** 跑一条命令只要 stdout，失败返回 null。 */
    static String shellOut(String cmd) {
        ShizukuShell.Result r = shell(cmd);
        if (r == null || !r.ok()) {
            return null;
        }
        return r.out;
    }

    /** 把字符串安全地塞进 sh -c。 */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
