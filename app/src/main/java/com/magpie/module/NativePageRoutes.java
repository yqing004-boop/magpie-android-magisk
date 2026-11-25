package com.magpie.module;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.json.JSONArray;

/**
 * 路由页。
 *
 * <p>跟 magpie 网页 Routing 页一致，只有两块：
 * <ol>
 *   <li>「实时 · 网关正在做的决定」—— {@link LivePanel}</li>
 *   <li>「请求」—— 实时 / 今天 / 昨天 / 更早 的流水，{@link TraceLog}</li>
 * </ol>
 *
 * <p>路由组的树不在这一页：网页上那是「路由」页下面另一块，而原生界面里
 * 「每个组怎么配、成员是谁」在账号页看得更清楚，所以这里不放。
 */
final class NativePageRoutes extends NativePage {

    private LivePanel live;
    private TraceLog log;

    NativePageRoutes(NativeHome host) {
        super(host);
    }

    @Override
    String name() {
        return "路由";
    }

    @Override
    void load() {
        // 这一页没有要一次性拉的列表：两个子块各自读自己的接口。
        //
        // 注意：每次 load 都要**保证内容已经挂上**。只判断「第一次」会在
        // 开关页面之后留下一个空壳（Activity 重建、refresh 重入都算），
        // 表现出来就是一直停在「正在读取 magpie…」。
        host.post(() -> {
            // 下拉刷新（PullToRefresh → refresh(true)）也要真的重来一遍：
            // 重建容器等于把两个子块的数据全部重取一次
            boolean rebuild = pageBox == null || pageBox.getParent() == null
                    || System.currentTimeMillis() - lastBuildAt > 2000;
            if (rebuild) {
                stopLive();
                content.removeAllViews();
                pageBox = build();
                content.addView(pageBox, wrapLp());
                lastBuildAt = System.currentTimeMillis();
            }
            done();
        });
    }

    /** 装两块内容的容器，重复 load 时不再重建。 */
    private LinearLayout pageBox;
    /** 上次重建的时间：短时间内的重复 load 不重建（避免闪烁）。 */
    private long lastBuildAt;

    private void stopLive() {
        if (live != null) {
            live.stop();
            live = null;
        }
        log = null;
    }

    private LinearLayout build() {
        LinearLayout box = ui.column();
        box.setOrientation(LinearLayout.VERTICAL);

        live = new LivePanel(host, ui);
        box.addView(live.view(), wrapLp());

        log = new TraceLog(host, ui);
        box.addView(log.view(), wrapLp());
        log.loadDays();

        // 实时面板每收到一批 routes，顺手给记录区，省一路轮询
        live.setForward(routes -> {
            if (log != null) {
                log.setLive(routes);
            }
        });
        live.start();

        // 底部留一点，别贴到悬浮胶囊
        box.setPadding(0, 0, 0, ui.dp(8));
        return box;
    }

    @Override
    void onDetach() {
        super.onDetach();
        if (live != null) {
            live.stop();
        }
    }

    @Override
    void onAttach() {
        super.onAttach();
        // 页已经建好就重启轮询：新的 seq 从 0 开始，把这段时间的路由也取回来
        if (live != null && live.view().getParent() != null) {
            live.start();
        }
    }

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
