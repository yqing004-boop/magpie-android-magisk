package com.magpie.module;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

/**
 * 局域网共享开关：模块版只有一个真实来源 —— magpie 自己的 {@code settings.LAN}。
 *
 * <h3>和老版的区别</h3>
 * 老版（com.magpie.android）有「两层局域网」：
 * <ul>
 *   <li>壳这一侧：{@code Prefs.lan} 决定子进程的 {@code MAGPIE_ADDR}，
 *       也就是<b>监听绑定地址</b>（{@code 0.0.0.0} 还是 {@code 127.0.0.1}）</li>
 *   <li>magpie 那一侧：{@code settings.json} 的 {@code lan}，由
 *       {@code POST /api/settings/lan} 改，决定<b>要不要对外服务</b>，
 *       以及顺手生成那个默认的 {@code Magpie} 网关密钥</li>
 * </ul>
 * 所以老版要写两处。
 *
 * <p>模块版没这个问题：内核由模块的 {@code service.sh} 启动，
 * {@code MAGPIE_ADDR} 根本没有被设置，监听地址完全由 magpie 自己按
 * {@code settings.LAN} 算（{@code internal/gateway/lan.go:listenAddr}）——
 * 开共享就是 {@code 0.0.0.0:3425}，关就是 {@code 127.0.0.1:3425}。
 *
 * <p>因此这里只做一件事：把开关写给 magpie，并把结果同步进
 * {@link Prefs#lan} 当显示缓存（顶栏那个「局域网 / 本机」胶囊用）。
 */
final class LanSync {

    private static final String TAG = "MagpieLan";

    private LanSync() {
    }

    /**
     * 读 magpie 现在的 {@code settings.LAN}。
     *
     * @return true/false；网关没起来或读不到时返回 null（调用方别据此改状态）
     */
    static Boolean current(Context ctx) {
        MagpieApi.Result r = MagpieApi.get(ctx, "/api/settings?last=1",
                MagpieApi.CONNECT_MS, MagpieApi.READ_MS);
        if (!r.ok()) {
            return null;
        }
        JSONObject o = r.json();
        if (o == null) {
            return null;
        }
        // settings.LAN 带 omitempty：关着的时候字段根本不在 JSON 里
        return o.optBoolean("lan", false);
    }

    /**
     * 用户拨了开关：写给 magpie，立刻生效。
     *
     * <p>写失败也要把值记进 Prefs —— 那是显示用的，等网关起来后
     * {@link #pushIfNeeded} 会补一次。
     */
    static void set(final Context ctx, final boolean on) {
        Prefs.setLan(ctx, on);
        new Thread(() -> {
            MagpieApi.Result r = MagpieApi.post(ctx, "/api/settings/lan",
                    "{\"on\":" + on + ",\"newKey\":false}", MagpieApi.WRITE_MS);
            if (!r.ok()) {
                Log.w(TAG, "同步 magpie 的局域网开关失败: " + r.why());
            } else {
                Log.i(TAG, "已同步 magpie 局域网共享 = " + on);
            }
        }, "magpie-lan-set").start();
    }

    /**
     * 网关就绪后调一次：把壳里记的值推给 magpie（幂等，值一样就不发请求）。
     *
     * <p>为什么还要这一步：开关有可能是在网关没起来时拨的（比如开机前就设过），
     * 那时 POST 会失败；等网关起来了补一次，两边就一致了。
     */
    static void pushIfNeeded(final Context ctx) {
        final boolean want = Prefs.lan(ctx);
        new Thread(() -> {
            Boolean now = current(ctx);
            if (now == null) {
                // 读不到就按壳里记的推一次，让 magpie 跟上
                MagpieApi.Result r = MagpieApi.post(ctx, "/api/settings/lan",
                        "{\"on\":" + want + ",\"newKey\":false}", MagpieApi.WRITE_MS);
                Log.i(TAG, "网关就绪后推局域网开关 = " + want
                        + (r.ok() ? " 成功" : " 失败：" + r.why()));
                return;
            }
            if (now == want) {
                return;
            }
            MagpieApi.Result r = MagpieApi.post(ctx, "/api/settings/lan",
                    "{\"on\":" + want + ",\"newKey\":false}", MagpieApi.WRITE_MS);
            Log.i(TAG, "网关就绪后对齐局域网开关 " + now + " → " + want
                    + (r.ok() ? " 成功" : " 失败：" + r.why()));
        }, "magpie-lan-push").start();
    }
}
