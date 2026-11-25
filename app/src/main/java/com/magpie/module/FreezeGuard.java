package com.magpie.module;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 系统冻结（MIUI greeze）的自查与提醒。
 *
 * <p>为什么需要这个类：实测（同一台小米手机，1.0.48 与 1.4.2 都一样）
 * 退到后台约 35 秒后，系统会依次做三件事：
 * <pre>
 * SmartPower 把 com.magpie.module 判为 power idle
 *   → PowerManagerServiceImpl 禁用 magpie:gateway 唤醒锁，reason: greeze
 *   → GreezeManager iso uid:xxxxx 隔离整个 uid
 *   → 整个进程组 cgroup.freeze=1，线程全部 do_freezer_trap
 * </pre>
 * 冻结期间：进程还在、端口还 LISTEN、前台服务通知也还在，
 * 但用户态代码一行都不执行 —— 所以 AI 智能体会一直「正在思考」，
 * 直到用户手动点开 App 触发解冻。
 *
 * <p>把 App 设成「无限制」（不受省电策略限制）后实测不再冻结。
 * 这个类只负责**提醒**用户去设，不试图对抗系统 —— 一个被冻结的进程
 * 没法自己解冻自己。
 *
 * <p>两类提醒（用户要求的 A + B）：
 * <ul>
 *   <li><b>A（主动引导）</b>：打开 App 时如果是小米系且没设「无限制」，发一条通知，
 *       点通知直达系统设置页。只在必要时发，不刷屏。</li>
 *   <li><b>B（后台回来的补救）</b>：从后台回来时，如果这次后台时长超过冻结阈值，
 *       说明「刚才很可能已经被冻过了」，再提醒一次。</li>
 * </ul>
 */
final class FreezeGuard {

    private static final String TAG = "MagpieFreeze";

    /** 提醒用的独立通知 id，别和网关前台通知（9101）打架。 */
    private static final int NOTIF_ID = 9110;

    private static final String CHANNEL_ID = "magpie_freeze";

    /**
     * 后台超过这么久就算「可能被冻结过」。
     *
     * <p>实测 greeze 在退后台约 35 秒时下手（17:09:09 切走 → 17:09:44 iso），
     * 留点余量按 40 秒算。短于这个的后台切来切去不提醒，免得刷屏。
     */
    private static final long FROZEN_AFTER_MS = 40_000L;

    /** 同一类提醒最多几天一次（用户自己不去设，也别天天弹）。 */
    private static final long NUDGE_EVERY_MS = 7L * 24 * 60 * 60 * 1000;

    private FreezeGuard() {
    }

    // ------------------------------------------------------------------
    // 判断
    // ------------------------------------------------------------------

    /** 是不是小米系（MIUI / 澎湃OS）。greeze 是这套系统自己的机制。 */
    static boolean isMiui() {
        String brand = safe(Build.BRAND);
        String maker = safe(Build.MANUFACTURER);
        if (brand.contains("xiaomi") || maker.contains("xiaomi")
                || brand.contains("redmi") || maker.contains("redmi")
                || brand.contains("poco") || maker.contains("poco")) {
            return true;
        }
        // 有些机型 BRAND 是别的，但系统属性还是 MIUI
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, "ro.miui.ui.version.name");
            if (v instanceof String && !((String) v).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 这个 App 是不是已经「不受电池优化限制」了。 */
    static boolean isUnrestricted(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 需不需要提醒：小米系 + 没设无限制。 */
    static boolean needsNudge(Context ctx) {
        return isMiui() && !isUnrestricted(ctx);
    }

    // ------------------------------------------------------------------
    // A：打开 App 时主动引导
    // ------------------------------------------------------------------

    /**
     * 打开 App 时调用。需要提醒且距上次提醒够久，就发通知。
     *
     * <p>不弹对话框：用户是来看用量/账号的，不是来读说明的。
     * 一条通知挂在那儿，愿意处理就点，不愿意就划掉。
     */
    static void nudgeOnOpen(Context ctx) {
        if (!needsNudge(ctx)) {
            return;
        }
        long last = Prefs.freezeNudgeAt(ctx);
        if (System.currentTimeMillis() - last < NUDGE_EVERY_MS) {
            return;
        }
        Prefs.setFreezeNudgeAt(ctx, System.currentTimeMillis());
        post(ctx, "建议把 Magpie 设为「无限制」",
                "小米省电策略会在退后台约 40 秒后冻结网关，AI 智能体会卡在「正在思考」。点这里去设置。");
        Log.i(TAG, "已发「设为无限制」引导通知");
    }

    // ------------------------------------------------------------------
    // B：从后台回来的补救提醒
    // ------------------------------------------------------------------

    /**
     * 退到后台时调用，记下时间。
     *
     * <p>只记时间戳，不注册任何定时器/唤醒锁 —— 冻结的时候这些也一样停。
     */
    static void markBackgrounded(Context ctx) {
        Prefs.setFreezeLeftAt(ctx, System.currentTimeMillis());
    }

    /**
     * 回到前台时调用。
     *
     * <p>如果这次后台待了超过 {@link #FROZEN_AFTER_MS}，那几乎肯定已经被
     * greeze 冻过了（用户那边的症状就是智能体卡住）。这时补一条通知，
     * 把「为什么刚才卡」和「怎么一劳永逸」讲清楚。
     */
    static void checkAfterReturn(Context ctx, long leftAt) {
        if (leftAt <= 0 || !needsNudge(ctx)) {
            return;
        }
        long away = System.currentTimeMillis() - leftAt;
        if (away < FROZEN_AFTER_MS) {
            return; // 只是切了个窗口，不算
        }
        Prefs.setFreezeLeftAt(ctx, 0);
        // A 已经提醒过（7 天内）就不再补一条，避免同一件事两条通知
        long last = Prefs.freezeNudgeAt(ctx);
        if (System.currentTimeMillis() - last < NUDGE_EVERY_MS) {
            Log.i(TAG, "后台 " + (away / 1000) + " 秒，但近期已提醒过，跳过");
            return;
        }
        Prefs.setFreezeNudgeAt(ctx, System.currentTimeMillis());
        post(ctx, "网关刚才可能被系统冻结了",
                "后台待了 " + (away / 60_000 >= 1 ? (away / 60_000) + " 分钟" : (away / 1000) + " 秒")
                        + "，小米省电策略冻结了 magpie 进程，期间的请求会卡住。"
                        + "设成「无限制」可以避免。点这里去设置。");
        Log.i(TAG, "后台 " + (away / 1000) + " 秒后回来，已发补救通知");
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "后台冻结提醒", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("小米省电策略冻结 magpie 网关时提醒你把 App 设为无限制");
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /** 点通知直达「允许不受电池优化限制」的系统页；打不开就退到应用详情页。 */
    private static PendingIntent settingsIntent(Context ctx) {
        Intent i;
        try {
            i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
        } catch (Throwable e) {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(ctx, 2, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void post(Context ctx, String title, String body) {
        try {
            createChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            nm.notify(NOTIF_ID, b
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(settingsIntent(ctx))
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .getNotification());
        } catch (Throwable e) {
            // 通知权限没给（Android 13+）就静默跳过，别因此崩掉界面
            Log.w(TAG, "发提醒通知失败: " + e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }
}
