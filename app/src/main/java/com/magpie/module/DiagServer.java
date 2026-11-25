package com.magpie.module;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 诊断端口 —— 没有电脑、没有 root 的用户把现场交给 AI 用的。
 *
 * <p>灵感来自「无线调试」：本机起一个 HTTP 端口，把壳手里的排查数据
 * （内核日志、崩溃计数、插件状态、数据目录结构……）按 JSON 吐出去。
 * 用户在 magpie 的菜单里点「AI 排查端口」看用法；不会用的，把地址
 * 整条发给手机上的 AI 软件（或电脑上的 AI），AI 自己 curl 就能拿到现场。
 *
 * <p>安全边界：
 * <ul>
 *   <li>默认只绑 127.0.0.1（外部碰不到）；设置页开「局域网开放」才绑 0.0.0.0</li>
 *   <li>每个请求都要带 key（menu 弹窗里展示的那串），不带或不对一律 403</li>
 *   <li>只读：所有端点都是 GET，没有任何写操作</li>
 *   <li>数据走 {@link DiagnosticsPack} 同一套脱敏（safe()/maskUser()）</li>
 * </ul>
 */
public final class DiagServer {

    private static final String TAG = "MagpieDiag";
    /** 固定端口：弹窗里的地址写死它，用户/AI 都不用猜。 */
    static final int PORT = 18790;

    private static volatile DiagServer current;

    private final ServerSocket server;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final String key;
    private final boolean lan;
    /** 请求处理池：有界（4 并发），plugins-state 这类 60 秒慢端点也堆不出千线程。 */
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    private DiagServer(ServerSocket server, int port, String key, boolean lan) {
        this.server = server;
        this.port = port;
        this.key = key;
        this.lan = lan;
    }

    /** 已经在跑就复用（改过开关/重启后调它换绑定），否则按当前设置起一个。 */
    public static synchronized void ensure(Context ctx) {
        String key = Prefs.webKey(ctx);
        boolean lan = Prefs.lan(ctx);
        DiagServer s = current;
        if (s != null && s.running.get() && s.key.equals(key) && s.lan == lan) {
            return;
        }
        stop();
        IOException last = null;
        for (int p = PORT; p <= PORT + 10; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName(
                        lan ? "0.0.0.0" : "127.0.0.1"), p));
                current = new DiagServer(ss, p, key, lan);
                current.begin();
                Log.i(TAG, "诊断端口起来了: " + (lan ? "0.0.0.0" : "127.0.0.1") + ":" + p);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        Log.w(TAG, "诊断端口起不来: " + (last == null ? "?" : last.getMessage()));
    }

    public static synchronized void stop() {
        DiagServer s = current;
        current = null;
        if (s != null) {
            s.running.set(false);
            try {
                s.server.close();
            } catch (IOException ignored) {
            }
            s.pool.shutdownNow();
        }
    }

    /** 当前端口（起失败时返回 0）。设置页/弹窗显示地址用。 */
    public static synchronized int runningPort() {
        DiagServer s = current;
        return s != null && s.running.get() ? s.port : 0;
    }

    private void begin() {
        Thread t = new Thread(() -> {
            while (running.get()) {
                try {
                    final Socket c = server.accept();
                    pool.execute(() -> serve(c));
                } catch (IOException e) {
                    if (running.get()) {
                        Log.w(TAG, "accept 断了: " + e.getMessage());
                    }
                }
            }
        }, "magpie-diag");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private void serve(Socket c) {
        try {
            c.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null || !line.startsWith("GET ")) {
                send(c, 405, "{\"error\":\"only GET\"}");
                return;
            }
            String path = line.split(" ")[1];
            // 吃掉剩余 header，顺手抓 Authorization（AI 客户端可能用它带 key）
            long body = 0;
            String auth = null;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                String low = line.toLowerCase(Locale.US);
                if (low.startsWith("content-length:")) {
                    body = parseLong(line.substring(15).trim());
                } else if (low.startsWith("authorization:")) {
                    auth = line.substring(14).trim();
                }
            }
            // 没有 POST 端点，body 不消费——直接 Connection: close 丢弃即可

            // 鉴权：?key= 优先，其次 Authorization: Bearer <key>
            String given = query(path, "key");
            if (given == null && auth != null
                    && auth.toLowerCase(Locale.US).startsWith("bearer ")) {
                given = auth.substring(7).trim();
            }
            if (!key.equals(given)) {
                send(c, 403, "{\"error\":\"bad key\",\"hint\":\"地址里带上弹窗显示的 ?key=…\"}");
                return;
            }

            String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            route = DiagnosticsPack.safe(route);
            switch (route) {
                case "/health":
                    send(c, 200, health());
                    break;
                case "/log":
                    send(c, 200, logJson(queryInt(path, "n", 200)));
                    break;
                case "/crash":
                    send(c, 200, crashJson());
                    break;
                case "/files":
                    send(c, 200, wrap("files", DiagnosticsPack.filesTree(App.context())));
                    break;
                case "/plugins":
                    send(c, 200, wrap("plugins", DiagnosticsPack.pluginsSafe(App.context())));
                    break;
                case "/plugins-state":
                    send(c, 200, wrap("plugins_state",
                            DiagnosticsPack.pluginsStateSafe(App.context())));
                    break;
                case "/system":
                    send(c, 200, systemJson());
                    break;
                case "/pack":
                    send(c, 200, packJson());
                    break;
                default:
                    send(c, 404, indexJson());
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "serve: " + e.getMessage());
        } finally {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String query(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String kv : path.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && name.equals(kv.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(eq + 1),
                            StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return kv.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static int queryInt(String path, String name, int def) {
        String v = query(path, name);
        if (v == null) {
            return def;
        }
        try {
            int n = Integer.parseInt(v);
            return n < 1 ? def : Math.min(n, 5000);
        } catch (Exception e) {
            return def;
        }
    }

    private static void send(Socket c, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        String text = code == 200 ? "OK" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "OK";
        out.write(("HTTP/1.1 " + code + " " + text + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String wrap(String name, String raw) {
        // raw 多半已是 JSON；不是就当字符串塞进去
        String t = raw == null ? "" : raw.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            return "{\"" + name + "\":" + t + "}";
        }
        try {
            return new JSONObject().put(name, raw == null ? "" : raw).toString();
        } catch (Exception e) {
            return "{\"" + name + "\":\"\"}";
        }
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    /** 内核活没活、网关端口通不通、跑了多久、退出码。一眼判断崩溃循环。 */
    private String health() {
        JSONObject o = new JSONObject();
        try {
            Context ctx = App.context();
            o.put("time", System.currentTimeMillis() / 1000);
            boolean alive = GatewayService.isAlive();
            o.put("kernel_alive", alive);
            o.put("gateway_ready", GatewayService.isReady());
            o.put("starting", GatewayService.isStarting());
            o.put("exit_code", GatewayService.exitCode());
            o.put("gateway_port", GatewayService.gatewayPort(ctx));
            o.put("web_port", GatewayService.webPort(ctx));
            o.put("gateway_port_open", GatewayService.portOpen(
                    Prefs.browserHost(ctx), GatewayService.gatewayPort(ctx)));
            o.put("web_port_open", GatewayService.portOpen(
                    Prefs.browserHost(ctx), GatewayService.webPort(ctx)));
        } catch (Exception e) {
            try {
                o.put("error", DiagnosticsPack.safe(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** 脱敏后的日志尾部（logJson 与 packJson 共用，别绕开）。 */
    private static String maskedLog(int n) {
        String log = GatewayService.lastLog(n);
        StringBuilder sb = new StringBuilder();
        for (String l : (log == null ? "" : log).split("\n")) {
            sb.append(DiagnosticsPack.safe(l)).append('\n');
        }
        return sb.toString();
    }

    /** 内核日志尾部（appendLog 落盘的那份，跟「查看日志」同源）。 */
    private String logJson(int n) {
        try {
            String log = maskedLog(n);
            JSONArray lines = new JSONArray();
            for (String l : log.split("\n")) {
                lines.put(l);
            }
            return new JSONObject().put("lines", lines.length())
                    .put("log", lines).toString();
        } catch (Exception e) {
            return "{\"error\":\"" + DiagnosticsPack.safe(e.getMessage()) + "\"}";
        }
    }

    /**
     * 崩溃画像：退出码、崩溃文本里的关键行。
     *
     * <p>为什么要有这个：Mi 10 那种「内核 5 秒一崩」的循环，/health 只能看到
     * exit_code，这里把日志里含「退出码 / Bad system call / fatal error /
     * rename」的行单独捞出来，AI 不用翻 500 行日志。
     */
    private String crashJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("exit_code", GatewayService.exitCode());
            o.put("kernel_alive", GatewayService.isAlive());
            String log = GatewayService.lastLog(1000);
            JSONArray hits = new JSONArray();
            if (log != null) {
                for (String l : log.split("\n")) {
                    String low = l.toLowerCase(Locale.US);
                    if (low.contains("退出码") || low.contains("fatal")
                            || low.contains("bad system call")
                            || low.contains("panic") || low.contains("no such file")
                            || low.contains("stack corruption")
                            || low.contains("address already in use")
                            || low.contains("permission denied")
                            || low.contains("自动重启")) {
                        hits.put(DiagnosticsPack.safe(l));
                        if (hits.length() >= 40) {
                            break;
                        }
                    }
                }
            }
            o.put("suspicious_lines", hits);
            o.put("note", "退出码 159=Bad system call(SIGSYS)，多见于系统 seccomp 拦静态二进制；"
                    + "139=SIGSEGV 段错误；143=SIGTERM 被正常杀掉");
        } catch (Exception e) {
            try {
                o.put("error", DiagnosticsPack.safe(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** 系统侧：Android 版本、机型、电池、进程资源。 */
    private String systemJson() {
        JSONObject o = new JSONObject();
        try {
            Context ctx = App.context();
            o.put("android", android.os.Build.VERSION.RELEASE);
            o.put("sdk", android.os.Build.VERSION.SDK_INT);
            o.put("model", android.os.Build.MODEL);
            o.put("shell_version", DiagnosticsPack.version(ctx));
            o.put("proc", DiagnosticsPack.procStats());
            o.put("battery", DiagnosticsPack.batteryState());
        } catch (Exception e) {
            try {
                o.put("error", DiagnosticsPack.safe(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** 完整包：把诊断包的主要文本项合一个 JSON（等价手动导出，AI 一次拿全）。 */
    private String packJson() {
        JSONObject o = new JSONObject();
        try {
            Context ctx = App.context();
            o.put("info", DiagnosticsPack.buildInfo(ctx,
                    DiagnosticsPack.version(ctx)));
            o.put("log", maskedLog(1000));
            o.put("crash", DiagnosticsPack.crashLog());
            o.put("plugins", DiagnosticsPack.pluginsSafe(ctx));
            o.put("plugins_state", DiagnosticsPack.pluginsStateSafe(ctx));
            o.put("files", DiagnosticsPack.filesTree(ctx));
            o.put("env", DiagnosticsPack.envSafe());
            o.put("threads", DiagnosticsPack.procStats());
            o.put("battery", DiagnosticsPack.batteryState());
        } catch (Exception e) {
            try {
                o.put("error", DiagnosticsPack.safe(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    /** 404 时返回端点索引，AI 一看就知道有什么可查。 */
    private String indexJson() {
        return "{\"endpoints\":{"
                + "\"/health\":\"内核与端口状态\","
                + "\"/log?n=200\":\"内核日志尾部\","
                + "\"/crash\":\"崩溃画像（退出码+可疑行）\","
                + "\"/files\":\"数据目录结构\","
                + "\"/plugins\":\"插件清单\","
                + "\"/plugins-state\":\"插件加载状态\","
                + "\"/system\":\"系统与资源\","
                + "\"/pack\":\"以上全要（AI 一次拿全）\""
                + "}}";
    }
}
