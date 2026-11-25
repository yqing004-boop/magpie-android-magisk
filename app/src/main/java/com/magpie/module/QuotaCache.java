package com.magpie.module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 额度卡的合并缓存。
 *
 * <p>{@code /api/usage/quotas} 的读法是「问供应商，谁先回来先给谁」：第一次
 * 请求往往只回来一两家（我们实测第一次只回了 5 张天枢的卡，14 家里其余 9 家
 * 还在路上），同一次里 {@code X-Magpie-Reading: 1} 只是说「还没读完」。
 *
 * <p>所以界面上不能拿「最新一次的响应」直接替换整页 —— 会看到供应商从 6 家
 * 掉到 1 家。这里按 {@code provider|user|alias} 做键，把历次结果合并起来：
 * 后来读到的覆盖同键的旧值，这次没回来的先保留（多半是还在读）。
 *
 * <p>缓存是进程内的，不落盘：重开 App 重新读一遍，不至于把删掉的账号永远留着。
 */
final class QuotaCache {

    private static final Map<String, JSONObject> CARDS = new LinkedHashMap<>();
    private static final Map<String, Long> SEEN = new LinkedHashMap<>();

    /** 超过这个时间没再出现在响应里的卡就不留下了（账号被删、或一直读失败）。 */
    private static final long TTL = 30L * 60 * 1000;

    /**
     * 版本号：reset() 时 +1。
     *
     * <p>页面自己那份「刚拉到的结果」也要跟着失效，否则加完供应商回概览页，
     * 额度区还是旧的那几家。
     */
    private static int version;

    private QuotaCache() {
    }

    private static String keyOf(JSONObject q) {
        return NativeData.str(q, "provider", "?") + "|"
                + NativeData.str(q, "user", "") + "|"
                + NativeData.str(q, "alias", "");
    }

    /** 把一次响应并进缓存，返回合并后的完整列表。 */
    static synchronized JSONArray merge(JSONArray incoming) {
        long now = System.currentTimeMillis();
        if (incoming != null) {
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject q = incoming.optJSONObject(i);
                if (q == null) {
                    continue;
                }
                String k = keyOf(q);
                CARDS.put(k, q);
                SEEN.put(k, now);
            }
        }
        // 太久没出现过的清掉，免得删掉的账号一直挂在页面上
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Long> e : SEEN.entrySet()) {
            if (now - e.getValue() > TTL) {
                dead.add(e.getKey());
            }
        }
        for (String k : dead) {
            CARDS.remove(k);
            SEEN.remove(k);
        }
        return snapshot();
    }

    /** 当前合并结果，按 magpie 给过的顺序（LinkedHashMap 保持插入序）。 */
    static synchronized JSONArray snapshot() {
        JSONArray out = new JSONArray();
        for (JSONObject q : CARDS.values()) {
            out.put(q);
        }
        return out;
    }

    /** 只保留这一次响应里有的卡（用户手动「全部重读」时用）。 */
    static synchronized void reset() {
        CARDS.clear();
        SEEN.clear();
        version++;
    }

    /** 当前的版本号：页面记下它，下次发现变了就重新拉。 */
    static synchronized int version() {
        return version;
    }
}
