package com.magpie.module;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 实时路由面板的数据源：轮询 {@code /api/gateway/trace}。
 *
 * <p>这个接口本身就是长轮询：带上 {@code after=<seq>} 和 {@code wait=} 之后，
 * 网关会一直挂住请求，直到有新的路由事件才回。所以这里不需要高频轮询，
 * 一次请求最多挂 25 秒，回来了立刻再接一次。
 *
 * <p>返回的 `Route` 里我们要的是：
 * <ul>
 *   <li>{@code order[]} —— 这次请求的候选账号，按优先级排好（就是网页上那排）</li>
 *   <li>{@code tries[]} —— 已经尝试过的，带 status / 耗时</li>
 *   <li>{@code group} —— 走的是哪个路由组，名字 + 路由方式 + 成员</li>
 *   <li>{@code done}/{@code status} —— 请求结束了没有</li>
 *   <li>{@code totals} —— 请求数 / 改道数 / 错误数，顶部那三个数字</li>
 * </ul>
 */
final class TraceSource {


    private volatile boolean running;
    private long seq;
    private Thread worker;

    interface Sink {
        /** 一批路由到了（可能为空），外加累计数字。 */
        void onTrace(JSONArray routes, JSONObject totals);

        /** 读不到（网关没起来、密钥不对…）。 */
        void onError(String why);
    }

    void start(NativeHome host, Sink sink) {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(() -> loop(host, sink), "magpie-trace");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 停下。**seq 一并归零**：下次 start 时从「当前」重新读。
     *
     * <p>不归零的话，切回路由页时带着上次的 seq 去问，网关认为「这些你都
     * 看过了」，中间这段时间发生的请求就永远收不到。
     */
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        seq = 0;
    }

    private void loop(NativeHome host, Sink sink) {
        while (running) {
            String path = "/api/gateway/trace?after=" + seq + "&wait=1";
            MagpieApi.Result r = MagpieApi.get(host, path, MagpieApi.CONNECT_MS, MagpieApi.READ_POLL_MS);
            if (!running) {
                return;
            }
            if (!r.ok()) {
                sink.onError(r.why());
                // 失败别打转，歇一会儿再来
                sleep(3000);
                continue;
            }
            JSONObject o = r.json();
            if (o == null) {
                sleep(1000);
                continue;
            }
            long nextSeq = o.optLong("seq", seq);
            JSONArray routes = NativeData.arr(o, "routes");
            JSONObject totals = NativeData.obj(o, "totals");
            sink.onTrace(routes, totals);
            // seq 没往前走说明只是超时回来了，立刻再接一次即可
            if (nextSeq > 0) {
                seq = nextSeq;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
