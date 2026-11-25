package com.magpie.module;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * 通过 Shizuku 以 shell / root 身份执行命令，不需要 App 自己有 root。
 *
 * <p>为什么用这个：MIUI 把每应用的省电策略存在
 * {@code /data/data/com.miui.powerkeeper/databases/user_configure.db}，
 * 普通 App 权限读不到、也改不了。root 能改，但要求手机已 root；
 * Shizuku 能让「ADB 授权」或「Magisk 的 Sui」也给同样的身份，
 * 覆盖面大得多。
 *
 * <p>注意权限差异（Shizuku 官方文档写得很清楚）：
 * <ul>
 *   <li>{@code Shizuku.getUid() == 0}：root 身份，能读写别的 App 数据目录</li>
 *   <li>{@code Shizuku.getUid() == 2000}：shell 身份，<b>读不到</b>
 *       {@code /data/data/} 下的东西（SELinux + 目录权限）</li>
 * </ul>
 * 所以「改 MIUI 省电策略」只有 root 后端能做；shell 后端只能跑
 * {@code settings get/put} 这类命令。
 */
final class ShizukuShell {

    private static final String TAG = "MagpieShizuku";

    /** 请求 Shizuku 授权的请求码。 */
    static final int REQ_PERMISSION = 30;

    /** 监听器只挂一次。 */
    private static boolean listenersAdded;

    /** 等待授权结果的回调。 */
    interface OnPermission {
        void result(boolean granted);
    }

    private ShizukuShell() {
    }

    // ==================================================================
    // 状态
    // ==================================================================

    /** Shizuku 服务是否连上了（App 装了并且正在运行）。 */
    static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 是否已经拿到授权。 */
    static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 当前身份：0 = root，2000 = shell，-1 = 不可用。 */
    static int uid() {
        try {
            return granted() ? Shizuku.getUid() : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 一句话描述当前状态，给界面显示。 */
    static String describe() {
        if (!available()) {
            return "Shizuku 未运行";
        }
        if (!granted()) {
            return "Shizuku 已连接，未授权";
        }
        int uid = uid();
        if (uid == 0) {
            return "Shizuku 已授权（root 身份）";
        }
        if (uid == 2000) {
            return "Shizuku 已授权（shell 身份，不能读写别的 App 数据）";
        }
        return "Shizuku 已授权（uid=" + uid + "）";
    }

    // ==================================================================
    // 授权
    // ==================================================================

    /**
     * 请求授权。Shizuku 会自己弹一个系统级的授权对话框
     * （「允许 Magpie 使用 Shizuku？」），用户点允许即可。
     *
     * @param cb 结果回调，可为 null
     */
    static void request(final Activity act, final OnPermission cb) {
        if (!available()) {
            if (cb != null) {
                cb.result(false);
            }
            return;
        }
        if (granted()) {
            if (cb != null) {
                cb.result(true);
            }
            return;
        }
        addListenersOnce();
        // 用 WeakReference 存回调：用户不点授权框直接离开页面时，静态字段
        // 不把 Activity 钉住。监听器侧拿弱引用，Activity 已回收就静默放弃。
        pendingRef = cb == null ? null
                : new java.lang.ref.WeakReference<>(cb);
        try {
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable e) {
            Log.w(TAG, "requestPermission 失败: " + e);
            pendingRef = null;
            if (cb != null) {
                cb.result(false);
            }
        }
        // 结果由 OnRequestPermissionResultListener 收到，见 addListenersOnce
    }

    /** 授权结果回调（WeakReference 持有，防 Activity 泄漏）。 */
    private static volatile java.lang.ref.WeakReference<OnPermission> pendingRef;

    private static void addListenersOnce() {
        if (listenersAdded) {
            return;
        }
        listenersAdded = true;
        try {
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (requestCode != REQ_PERMISSION) {
                    return;
                }
                boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "Shizuku 授权结果: " + ok);
                java.lang.ref.WeakReference<OnPermission> ref = pendingRef;
                pendingRef = null;
                OnPermission cb = ref == null ? null : ref.get();
                if (cb != null) {
                    cb.result(ok);
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "注册授权监听失败: " + e);
        }
    }

    // ==================================================================
    // 执行命令
    // ==================================================================

    /** 一次命令的结果。 */
    static final class Result {
        final int code;
        final String out;
        final String error;

        Result(int code, String out, String error) {
            this.code = code;
            this.out = out;
            this.error = error;
        }

        boolean ok() {
            return error == null && code == 0;
        }
    }

    /**
     * 以 Shizuku 身份执行一条命令。
     *
     * @param cmd 命令字符串，走 {@code sh -c}，所以可以用管道和重定向
     */
    static Result exec(String cmd) {
        if (!available()) {
            return new Result(-1, "", "Shizuku 未运行");
        }
        if (!granted()) {
            return new Result(-1, "", "尚未获得 Shizuku 授权");
        }
        Process p = null;
        try {
            final Process proc = Shizuku.newProcess(
                    new String[]{"/system/bin/sh", "-c", cmd},
                    null, null);
            p = proc;
            // stderr 并行读：以前 stdout 读到 EOF 才轮到 stderr，命令往 stderr
            // 写满 pipe 缓冲时子进程卡在 write、stdout 永远到不了 EOF，这里就
            // 永久阻塞（没有 soTimeout）。
            final StringBuilder eb = new StringBuilder();
            Thread errT = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        eb.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }, "shizuku-exec-err");
            errT.setDaemon(true);
            errT.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            errT.join(2000);
            String err = null;
            if (code != 0) {
                err = eb.length() > 0 ? eb.toString().trim() : ("退出码 " + code);
            }
            return new Result(code, sb.toString().trim(), err);
        } catch (Throwable e) {
            Log.w(TAG, "Shizuku exec 失败: " + cmd + " → " + e);
            return new Result(-1, "", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 打开 Shizuku App（没装就跳商店页）。 */
    static void openApp(Context ctx) {
        try {
            android.content.Intent i = ctx.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (i == null) {
                i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                                "https://shizuku.rikka.app/download/"));
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable e) {
            Log.w(TAG, "打不开 Shizuku: " + e);
        }
    }
}
