package com.magpie.module;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

/**
 * Holds an application context for the static helpers in {@link GatewayService}
 * (the log ring, the log file) that have no Activity to ask for one.
 *
 * <p>另外维持一个「本 App 当前有几个 Activity 在启动态」的计数：
 * {@link FreezeGuard} 靠它区分「真的退到后台」和「只是在 App 内部跳了个页面」。
 * 后者进程仍在前台，系统不会冻结，不能当后台计时。
 */
public class App extends Application {

    /** 本版「后台释放页面内存」的默认值（分钟）。 */
    public static final int SHELL_DEFAULT_WEBC_RELEASE = 5;

    /** 处于 started（可见）状态的 Activity 个数。 */
    private static int started;

    private static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
                started++;
            }

            @Override
            public void onActivityResumed(Activity a) {
            }

            @Override
            public void onActivityPaused(Activity a) {
            }

            @Override
            public void onActivityStopped(Activity a) {
                started--;
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
            }
        });
        // 网页后台的 key 由模块 service.sh 生成（模块根 webkey.txt），
        // 不再由 App 生成 —— 两边必须一致，否则所有 /api/* 都 401。
        // 这里只在模块还没装、只读桥读不到时给个本地占位值。
        if (ModuleBridge.webKey().isEmpty()) {
            Prefs.webKey(this);
        }
        // 大数字的单位（英文 K/M/B 还是中文 万/亿）在这里读一次，
        // 之后全 App 的数字格式化都按它走
        NativeData.setUnitMode(Prefs.unitMode(this));
        // 本版把「后台释放页面内存」的默认改成 5 分钟，并对老用户强制覆盖一次
        // （只做一次，之后用户在设置里改的值照样保留）
        Prefs.migrateWebReleaseOnce(this, SHELL_DEFAULT_WEBC_RELEASE);
    }

    /**
     * 当前有几个 Activity 在 started 态。
     *
     * <p>在某个 Activity 的 {@code onStop} 里调用时，它自己也还没被减掉 ——
     * 所以返回 1 意味着「只剩我要退了，整个 App 真的要离开了」。
     */
    static int startedActivities() {
        return started;
    }

    public static Context context() {
        return ctx;
    }
}
