package com.magpie.module;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 模块版的前台服务：<b>不启动内核，只探测它</b>。
 *
 * <h3>和老版的根本区别</h3>
 * <table>
 *   <tr><th></th><th>老版 com.magpie.android</th><th>模块版 com.magpie.module</th></tr>
 *   <tr><td>内核在哪</td><td>APK 的 assets，首次运行释放到
 *       {@code filesDir/magpie/magpie}</td>
 *       <td>Magisk 模块 {@code /data/adb/modules/magpie_native/magpie}</td></tr>
 *   <tr><td>谁启动它</td><td>这个 Service 的 {@code ProcessBuilder}</td>
 *       <td>模块的 {@code service.sh}（开机）+ {@code watchdog.sh}（10 秒轮询）</td></tr>
 *   <tr><td>谁保活</td><td>前台服务 + WakeLock + 自动重试</td>
 *       <td>Magisk 模块的 watchdog，不依赖 App 活着</td></tr>
 *   <tr><td>App 被杀</td><td>网关跟着死</td><td>网关继续跑，App 只是观众</td></tr>
 *   <tr><td>APK 大小</td><td>99MB（98MB 是内核）</td><td>几百 KB</td></tr>
 * </table>
 *
 * <p>所以这个 Service 的职责只剩：探测 3425 是否活着、把状态反映到通知、
 * 顺手确认模块装的版本。它连 WakeLock 都不需要 —— 内核在模块里跑，
 * 由 Magisk 的 service 阶段保证开机自启。
 *
 * <h3>为什么还留前台服务</h3>
 * 用户点开 App 时系统会给它更高的进程优先级，探测线程活着才能持续刷新界面。
 * 不用 WakeLock 是因为不需要在息屏时做任何事 —— 网关不归 App 管。
 */
public class GatewayService extends Service {

    private static final String TAG = "MagpieNativeGateway";
    private static final int NOTIF_ID = 9101;
    private static final String CHANNEL_ID = "magpie_native_gateway";
    public static final String ACTION_RESTART = "com.magpie.module.action.RESTART";
    public static final String ACTION_PROBE = "com.magpie.module.action.PROBE";

    private static final Object LOCK = new Object();

    /** 网关（模块内核）是否在答话。 */
    private static volatile boolean ready;
    /** 正在探测。 */
    private static volatile boolean probing;
    /** 上一次探测的错误说明（连不上时给界面看）。 */
    private static volatile String lastError = "";
    /** 模块版本缓存，读一次省一次 shell。 */
    private static volatile String moduleVersion = "";
    /** 通知最后一次展示的状态，避免每 2 秒重发。 */
    private static volatile Boolean lastNotifAlive;

    private volatile boolean stopped;

    // ------------------------------------------------------------------
    // 路径：全部指向 Magisk 模块
    // ------------------------------------------------------------------

    /** 模块目录（root 才能进；普通 App 只用来拼路径给 Shizuku）。 */
    static File rootDir(Context ctx) {
        return new File(ModuleBridge.MODULE_DIR);
    }

    /** 模块里的内核二进制。模块版<b>不释放、不替换</b>它。 */
    static File binary(Context ctx) {
        return new File(ModuleBridge.MODULE_DIR, "magpie");
    }

    /** 模块的数据目录（settings.json / providers.json / usage.jsonl）。 */
    static File dataDir(Context ctx) {
        return new File(ModuleBridge.MODULE_DIR, "data");
    }

    /** 模块给 agent 的 HOME（~/.claude、~/.codex 都在这）。 */
    static File homeDir(Context ctx) {
        return new File(ModuleBridge.MODULE_DIR, "home");
    }

    static File logDir(Context ctx) {
        return new File(ModuleBridge.MODULE_DIR, "logs");
    }

    static File logFile(Context ctx) {
        return new File(logDir(ctx), "magpie.log");
    }

    static File callerKeysFile(Context ctx) {
        return new File(dataDir(ctx), "caller-keys.json");
    }

    // ------------------------------------------------------------------
    // 端口
    // ------------------------------------------------------------------

    static int gatewayPort(Context ctx) {
        return ModuleBridge.GATEWAY_PORT;
    }

    static int webPort(Context ctx) {
        return ModuleBridge.WEB_PORT;
    }

    /** 模块内核自己决定绑定地址，壳只连回环。 */
    static String bindHost(Context ctx) {
        return "127.0.0.1";
    }

    static String webBind(Context ctx) {
        return "127.0.0.1";
    }

    /**
     * 网页后台的完整链接（带 key）。
     *
     * <p>key 来自模块的 {@code webkey.txt}（service.sh 首次生成后固定）。
     * 读不到就返回不带 k= 的地址 —— 上游会显示登录页，用户可以从
     * 「复制网页登录链接」重新取。
     */
    static String webUrl(Context ctx) {
        String key = ModuleBridge.webKey();
        String base = "http://127.0.0.1:" + webPort(ctx);
        return key.isEmpty() ? base : base + "/?k=" + key;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastNotifAlive = null;
        startForeground(NOTIF_ID, notification(this, "Magpie 模块", "正在探测网关…",
                Notification.FLAG_ONGOING_EVENT));

        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            restart(this);
            return START_STICKY;
        }

        synchronized (LOCK) {
            if (probing) {
                return START_STICKY;
            }
            probing = true;
        }
        Thread t = new Thread(this::probeLoop, "magpie-probe");
        t.setDaemon(true);
        t.start();
        return START_STICKY;
    }

    /**
     * 探测循环：每 5 秒问一次 3425，状态变化时更新通知。
     *
     * <p>这里<b>不重启内核</b> —— 那是模块 watchdog 的活。App 只管看。
     */
    private void probeLoop() {
        try {
            // 模块版本只读一次
            if (moduleVersion.isEmpty()) {
                moduleVersion = ModuleBridge.version();
            }
            while (!stopped) {
                boolean alive = GatewayService.portOpen("127.0.0.1", ModuleBridge.GATEWAY_PORT);
                boolean webAlive = GatewayService.portOpen("127.0.0.1", ModuleBridge.WEB_PORT);
                synchronized (LOCK) {
                    ready = alive;
                    lastError = alive ? "" : explainNotAlive(webAlive);
                }
                syncNotification(this, alive);
                sleep(5000);
            }
        } catch (Exception e) {
            Log.w(TAG, "探测循环退出: " + e);
        } finally {
            synchronized (LOCK) {
                probing = false;
            }
        }
    }

    /**
     * 连不上时给一句人话。
     *
     * <p>三种典型现场，界面上要能一眼区分：
     * 模块没装 / 模块装了但内核没起来 / 内核起来了但端口被别的占了。
     */
    private static String explainNotAlive(boolean webAlive) {
        if (webAlive) {
            return "网页后台在监听但网关没起来，多半是内核启动后立刻崩了，看日志";
        }
        if (!ModuleBridge.installed()) {
            return "没检测到 Magpie Native 模块。先去 Magisk 里刷 magpie-native zip 并重启。";
        }
        return "模块装着，但网关没在答话。可能是开机后还没起来，或内核崩了（看日志）";
    }

    /** 停：只停 App 的探测，<b>不动模块内核</b>。 */
    public static void stop(Context ctx) {
        synchronized (LOCK) {
            ready = false;
        }
        try {
            ctx.stopService(new Intent(ctx, GatewayService.class));
        } catch (Exception ignored) {
        }
    }

    /**
     * 重启模块内核。
     *
     * <p>模块版不能像老版那样 kill 自己的子进程 —— 内核不归 App 管。
     * 走 {@link ModuleBridge#restart()}，它调模块的 restart.sh；
     * 脚本不存在就杀 pid 让 watchdog 拉起来。
     */
    public static void restart(Context ctx) {
        new Thread(() -> {
            synchronized (LOCK) {
                ready = false;
                lastError = "正在重启模块内核…";
            }
            syncNotification(ctx, false);
            ModuleBridge.restart();
            // 等它起来（最多 30 秒）
            for (int i = 0; i < 60; i++) {
                if (GatewayService.portOpen("127.0.0.1", ModuleBridge.GATEWAY_PORT)) {
                    synchronized (LOCK) {
                        ready = true;
                        lastError = "";
                    }
                    syncNotification(ctx, true);
                    return;
                }
                sleep(500);
            }
            synchronized (LOCK) {
                lastError = "重启后 30 秒仍未答话，看日志";
            }
            syncNotification(ctx, false);
        }, "magpie-restart").start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopped = true;
        synchronized (LOCK) {
            ready = false;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 划掉最近任务不影响模块内核，模块 watchdog 会继续守
        appendLog("最近任务被移除，模块内核继续运行");
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    static boolean isReady() {
        return ready;
    }

    static boolean isStarting() {
        return probing && !ready;
    }

    /** 老版语义是「App 起的子进程活着」；模块版改成「网关在答话」。 */
    static boolean isAlive() {
        return ready || GatewayService.portOpen("127.0.0.1", ModuleBridge.GATEWAY_PORT);
    }

    static String bootError() {
        return lastError == null ? "" : lastError;
    }

    static int exitCode() {
        // 模块版拿不到内核退出码（进程不归 App 管），返回 -1 表示未知
        return -1;
    }

    static void resetState() {
        synchronized (LOCK) {
            ready = false;
            lastError = "";
        }
    }

    /** 模块版本（读一次缓存）。 */
    static String moduleVersion() {
        if (moduleVersion.isEmpty()) {
            moduleVersion = ModuleBridge.version();
        }
        return moduleVersion;
    }

    static void invalidateModuleVersion() {
        moduleVersion = "";
    }

    public static boolean portOpen(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 日志：模块内核的日志在模块里，App 通过只读桥读
    // ------------------------------------------------------------------

    /** App 自己的事件日志（不是内核日志）。 */
    private static final Object TAIL_LOCK = new Object();
    private static final StringBuilder tail = new StringBuilder();
    private static final long LOG_MAX = 5L * 1024 * 1024;
    private static volatile long logSize = -1;
    private static final Object LOG_LOCK = new Object();

    static void appendLog(String line) {
        synchronized (TAIL_LOCK) {
            if (tail.length() > 0) {
                tail.append('\n');
            }
            tail.append(line);
            if (tail.length() > 64 * 1024) {
                String all = tail.toString();
                int cut = all.indexOf('\n', all.length() / 2);
                if (cut >= 0) {
                    tail.setLength(0);
                    tail.append(all, cut + 1, all.length());
                } else {
                    tail.setLength(0);
                }
            }
        }
        try {
            File f = appLogFile(App.context());
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (LOG_LOCK) {
                if (logSize < 0) {
                    logSize = f.length();
                }
                if (logSize + bytes.length > LOG_MAX) {
                    rotateLog(f);
                    logSize = 0;
                }
                try (OutputStream out = new FileOutputStream(f, true)) {
                    out.write(bytes);
                }
                logSize += bytes.length;
            }
        } catch (Exception ignored) {
        }
    }

    /** App 自己的日志文件（不是内核日志）。 */
    static File appLogFile(Context ctx) {
        return new File(ctx.getFilesDir(), "shell.log");
    }

    private static void rotateLog(File f) {
        try {
            File old = new File(f.getParentFile(), f.getName() + ".1");
            if (old.exists()) {
                old.delete();
            }
            f.renameTo(old);
        } catch (Exception ignored) {
        }
    }

    /**
     * 界面要看的日志：优先模块内核的（只读桥），拼上 App 自己的事件。
     *
     * @param lines 要最后多少行；&lt;=0 全要
     */
    static String lastLog(int lines) {
        StringBuilder sb = new StringBuilder();
        String core = ModuleBridge.log(lines);
        if (core != null && !core.isEmpty()) {
            sb.append("=== 模块内核日志 ===\n").append(core);
            if (!core.endsWith("\n")) {
                sb.append('\n');
            }
        } else if (!ModuleBridge.bridgeReady()) {
            sb.append("=== 模块内核日志 ===\n")
                    .append("（只读桥没挂上：模块没装，或 service.sh 没跑过）\n")
                    .append("挂上后这里会显示 /data/adb/modules/magpie_native/logs/magpie.log\n");
        }
        String wd = ModuleBridge.watchdogLog(20);
        if (wd != null && !wd.isEmpty()) {
            sb.append("\n=== watchdog 日志 ===\n").append(wd);
        }
        synchronized (TAIL_LOCK) {
            String mine = tail.toString();
            if (!mine.isEmpty()) {
                sb.append("\n=== App 事件 ===\n");
                if (lines > 0) {
                    String[] all = mine.split("\n");
                    int from = Math.max(0, all.length - lines);
                    for (int i = from; i < all.length; i++) {
                        sb.append(all[i]).append('\n');
                    }
                } else {
                    sb.append(mine);
                }
            }
        }
        return sb.toString();
    }

    static void clearLog() {
        synchronized (TAIL_LOCK) {
            tail.setLength(0);
        }
        synchronized (LOG_LOCK) {
            File f = appLogFile(App.context());
            if (f.exists()) {
                f.delete();
            }
            logSize = 0;
        }
    }

    // ------------------------------------------------------------------
    // 插件补丁：模块版需要 Shizuku 才能写进模块目录
    // ------------------------------------------------------------------

    private static final class Patch {
        final String id;
        final String file;
        final String mark;
        final String helperMark;
        final String anchor;
        final String replacement;
        final String helper;

        Patch(String id, String file, String mark, String helperMark,
              String anchor, String replacement, String helper) {
            this.id = id;
            this.file = file;
            this.mark = mark;
            this.helperMark = helperMark;
            this.anchor = anchor;
            this.replacement = replacement;
            this.helper = helper;
        }
    }

    private static final String MARVIS_FILE =
            "data/plugins/node_modules/magpie-marvis/src/hooks/methods.mjs";
    private static final String MARVIS_MARK = "MAGPIE_ANDROID_PASTE";
    private static final String MARVIS_HELPER_MARK = "MAGPIE_ANDROID_PASTE_HELPER";

    private static final String MARVIS_ANCHOR =
            "        async callback(_url, _input, signal) {\n"
                    + "          const browser = findBrowser()";

    private static final String MARVIS_REPLACEMENT =
            "        async callback(_url, _input, signal) {\n"
                    + "          /* MAGPIE_ANDROID_PASTE */\n"
                    + "          // Android 上没有可 spawn 的 Chromium：等壳把用户粘贴的回调地址写进文件。\n"
                    + "          if (!findBrowser()) {\n"
                    + "            const pasted = await waitPastedCallback(signal)\n"
                    + "            const st = stateOfUrl(pasted)\n"
                    + "            const vg = pendingDevices.take(st) || (loginType === \"WX\" ? st : \"\")\n"
                    + "            if (!vg) {\n"
                    + "              throw new Error(\"登录流程已失效（找不到这次登录的设备标识）。请重新点一次登录。\")\n"
                    + "            }\n"
                    + "            let acct = await parseCallback(pasted, \"\", { loginType, guid: vg })\n"
                    + "            acct = await withProfile({ ...acct, guid: vg }, loginType)\n"
                    + "            return authOf({ ...acct, guid: vg, loginType: acct.loginType ?? LOGIN_TYPE[loginType] ?? \"1\" })\n"
                    + "          }\n"
                    + "          const browser = findBrowser()";

    private static final String MARVIS_HELPER = "\n\n/* MAGPIE_ANDROID_PASTE_HELPER */\n"
            + "// 壳把粘贴的回调地址写在 HOME/marvis-login-url.txt，读完即删。\n"
            + "async function waitPastedCallback(signal) {\n"
            + "  const fs = await import(\"node:fs/promises\")\n"
            + "  const os = await import(\"node:os\")\n"
            + "  const path = await import(\"node:path\")\n"
            + "  const file = path.join(os.homedir(), \"marvis-login-url.txt\")\n"
            + "  const started = Date.now()\n"
            + "  while (Date.now() - started < 10 * 60_000) {\n"
            + "    if (signal && signal.aborted) throw new Error(\"登录已取消\")\n"
            + "    try {\n"
            + "      const raw = (await fs.readFile(file, \"utf8\")).trim()\n"
            + "      if (raw) {\n"
            + "        await fs.unlink(file).catch(() => {})\n"
            + "        return raw\n"
            + "      }\n"
            + "    } catch {\n"
            + "      /* 还没写进来 */\n"
            + "    }\n"
            + "    await new Promise((r) => setTimeout(r, 500))\n"
            + "  }\n"
            + "  throw new Error(\"等待粘贴 Marvis 授权回调超时（10 分钟）。请重新点一次登录。\")\n"
            + "}\n";

    private static final Patch[] PATCHES = {
            new Patch("magpie-marvis", MARVIS_FILE, MARVIS_MARK, MARVIS_HELPER_MARK,
                    MARVIS_ANCHOR, MARVIS_REPLACEMENT, MARVIS_HELPER),
    };

    private static volatile String patchNote = "";

    static String takePatchNote() {
        String n = patchNote;
        patchNote = "";
        return n;
    }

    /**
     * 给插件打 Android 兼容补丁。
     *
     * <p>模块版必须走 Shizuku/root：文件在 {@code /data/adb/modules/} 下，
     * 普通 App 的 File API 碰不到。没有提权时只记一条日志，不报错。
     */
    static void patchPlugins(Context ctx) {
        if (!ModuleBridge.canWrite()) {
            appendLog("跳过插件补丁：没有 Shizuku/root 权限，改不了模块目录");
            return;
        }
        for (Patch p : PATCHES) {
            applyPatch(ctx, p);
        }
    }

    private static void applyPatch(Context ctx, Patch p) {
        String path = ModuleBridge.MODULE_DIR + "/" + p.file;
        String src = ModuleBridge.shellOut("cat " + ModuleBridge.quote(path) + " 2>/dev/null");
        if (src == null || src.isEmpty()) {
            return; // 插件没装
        }
        if (src.contains(p.mark) && src.contains(p.helperMark)) {
            return; // 已经补过
        }
        if (!src.contains(p.anchor)) {
            patchNote = p.id + " 补丁不适用（插件已改版）";
            appendLog(patchNote);
            return;
        }
        String out = src.replace(p.anchor, p.replacement);
        if (!out.contains(p.helperMark)) {
            out = out + p.helper;
        }
        // 写回：先写临时文件再 mv，避免写一半崩了把插件搞坏
        String tmp = path + ".patch.tmp";
        String b64 = android.util.Base64.encodeToString(
                out.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        ShizukuShell.Result r = ModuleBridge.shell(
                "echo " + ModuleBridge.quote(b64) + " | base64 -d > " + ModuleBridge.quote(tmp)
                        + " && mv -f " + ModuleBridge.quote(tmp) + " " + ModuleBridge.quote(path));
        if (r != null && r.ok()) {
            patchNote = p.id + " 兼容补丁已写入";
            appendLog(patchNote);
        } else {
            patchNote = p.id + " 补丁写入失败";
            appendLog(patchNote);
        }
    }

    static String patchStatus(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Patch p : PATCHES) {
            String path = ModuleBridge.MODULE_DIR + "/" + p.file;
            sb.append(p.id).append("：");
            if (!ModuleBridge.canWrite()) {
                sb.append("需要 Shizuku/root 才能查看（模块目录普通 App 读不到）");
            } else {
                String src = ModuleBridge.shellOut("cat " + ModuleBridge.quote(path) + " 2>/dev/null");
                if (src == null || src.isEmpty()) {
                    sb.append("没装这个插件");
                } else if (src.contains(p.mark) && src.contains(p.helperMark)) {
                    sb.append("兼容补丁已生效");
                } else if (src.contains(p.anchor)) {
                    sb.append("补丁待写入（重启 App 或点一下写入）");
                } else {
                    sb.append("补丁不适用（插件已改版，需要更新这个 App）");
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Magpie 模块",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("模块内核的探测状态（网关由 Magisk 模块启动，不归 App 管）");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private static Notification notification(Context ctx, String title, String body, int flags) {
        Intent open = new Intent(ctx, NativeHome.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setOngoing((flags & Notification.FLAG_ONGOING_EVENT) != 0)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    static void syncNotification(Context ctx, boolean alive) {
        if (lastNotifAlive != null && lastNotifAlive == alive) {
            return;
        }
        lastNotifAlive = alive;
        String ver = moduleVersion();
        String body = alive
                ? (ver.isEmpty() ? "网关 3425 运行中（模块内核）"
                : "网关 3425 运行中 · 模块 " + ver)
                : bootError();
        if (body == null || body.isEmpty()) {
            body = "网关没在答话";
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        nm.notify(NOTIF_ID, notification(ctx, "Magpie 模块", body,
                Notification.FLAG_ONGOING_EVENT));
    }

    static void postNotice(Context ctx, String title, String body) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            nm.notify((int) (System.currentTimeMillis() & 0xffff),
                    notification(ctx, title, body, 0));
        } catch (Exception ignored) {
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeFile(File f, String content) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
