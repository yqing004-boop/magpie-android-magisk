package com.magpie.module;

import android.view.View;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 一页 tab 的公共骨架：一个竖着滚的容器 + 「正在读取 / 读不到」的兜底。
 *
 * <p>刷新的节奏固定：主线程开个圈 → 后台线程取 JSON → 回主线程重画。
 * 后台失败一律走 {@link #failed}，页面上给出原因和「重试」，而不是白屏 ——
 * magpie 的内部接口改字段时，这是唯一能保证 App 不崩的地方。
 */
abstract class NativePage {

    final NativeHome host;
    final Ui ui;
    final PullToRefresh layout;
    final ScrollView scroll;
    final LinearLayout content;

    private final TextView refreshHint;

    volatile boolean loading;
    long lastLoadAt;

    /** 页面上现在挂的是「网关正在启动」而不是内容，刷 spinner 没意义。 */
    private boolean waiting;

    /** 已经为了「magpie 还在读」重取了几次。 */
    private int rechecks;

    /** 挂起还没跑的重试，页面被换掉时要撤掉，否则它会在后台一直请求。 */
    private Runnable pendingRecheck;

    /** 页面还挂在这个 tab 上吗；false 时不再排新的重试。 */
    private boolean attached = true;

    NativePage(NativeHome host) {
        this.host = host;
        this.ui = host.ui;

        scroll = new ScrollView(host);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        // 底部留白 = 悬浮胶囊的高度 + 一点余量：内容铺到屏幕底边（胶囊
        // 下面才有东西透出来），同时最后一张卡能滚到胶囊上方不被挡住。
        content.setPadding(0, ui.dp(4), 0,
                NativeHome.bottomInset(ui) + ui.dp(16));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshHint = ui.text("", 11.5f, M3.ON_SURFACE_VARIANT, false);
        refreshHint.setGravity(android.view.Gravity.CENTER);
        refreshHint.setPadding(0, ui.dp(2), 0, ui.dp(6));

        // 下拉刷新：滚到顶再往下拽就重读这一页（60 秒内节流，只转动画）
        layout = new PullToRefresh(host, scroll, ui);
        layout.setOnRefresh(() -> pullRefresh());
    }

    View view() {
        return layout;
    }

    /** 页面的标题名（给下拉刷新提示用）。 */
    abstract String name();

    /** 后台线程里做网络请求，拿到数据后自己 post 回主线程画。 */
    abstract void load();

    /**
     * 页面被换掉 / App 退到后台时收尾：撤掉挂起的额度重试，并标记为分离。
     *
     * <p>子类可以覆盖，但**必须先调 super**——否则 {@link #recheckLater()}
     * 排下的那几次「额度还在读，再问一次」会一直在后台打接口。
     */
    void onDetach() {
        attached = false;
        if (pendingRecheck != null) {
            host.post(() -> host.cancel(pendingRecheck));
            pendingRecheck = null;
        }
        loading = false;
        if (layout != null) {
            layout.finishRefresh();
        }
    }

    /** 重新挂上（切回这个 tab 时由 {@link NativeHome#selectTab} 调）。 */
    void onAttach() {
        attached = true;
    }

    /**
     * 再点一次当前 tab 时调：收起本页展开的卡片并回到顶部。
     *
     * <p>默认只回顶部；账号页覆盖它，把展开的供应商/账号卡片全部收起。
     */
    void collapseAndScrollTop() {
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
    }

    /**
     * 清空内容层并重建，**但保持滚动位置**。
     *
     * <p>直接 `removeAllViews()` 会让内容高度瞬间归零，ScrollView 就把
     * scrollY 收成 0；新内容填好后再滚不回原位。表现出来就是「自动刷新时
     * 页面突然往下跳一下」，跟手动下拉刷新一样。
     *
     * <p>做法：先记下 scrollY，重建完在下一帧恢复。
     *
     * @param rebuild 真正重建内容的动作（里面自己 addView）
     */
    void rebuildKeepingScroll(final Runnable rebuild) {
        final int y = scroll.getScrollY();
        rebuild.run();
        if (y > 0) {
            // 布局还没完成时 scrollTo 会被夹到 0，等一帧再滚
            scroll.post(() -> scroll.scrollTo(0, y));
        }
    }

    /**
     * 重画的目标容器。
     *
     * <p>默认就是整页 {@link #content}；固定表头的页面（比如用量页的周期条）
     * 覆盖成自己的内容层，这样「正在读取 / 读不到」只会换掉下面那块，表头还在。
     */
    LinearLayout renderTarget() {
        return content;
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    void refresh(boolean force) {
        if (loading) {
            return;
        }
        if (!force && System.currentTimeMillis() - lastLoadAt < 8000) {
            return;
        }
        loading = true;
        lastLoadAt = System.currentTimeMillis();
        host.post(() -> {
            // 空页面直接上「正在读取」大字，不要再叠一条小提示；
            // 等待网关启动时也不叠
            if (renderTarget().getChildCount() == 0) {
                showLoading();
            } else if (!waiting) {
                headerSpinner(true);
            }
        });
        host.bg(() -> {
            try {
                load();
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                host.post(() -> {
                    loading = false;
                    headerSpinner(false);
                    failed("读取失败：" + msg);
                });
            }
        });
    }

    /**
     * 下拉刷新专用：60 秒内重复下拉只转动画、不真正重新拉数据。
     *
     * <p>为什么单独一个方法：{@code refresh(true)} 还被切周期、重试、账号
     * 刷新这些按钮用，那些必须真正刷新。只有下拉这个入口要节流。
     */
    void pullRefresh() {
        if (loading) {
            layout.finishRefresh();
            return;
        }
        if (System.currentTimeMillis() - lastLoadAt < 60_000) {
            // 只转一下动画表示收到了，不发请求
            headerSpinner(true);
            host.postDelayed(() -> {
                headerSpinner(false);
                if (layout != null) {
                    layout.finishRefresh();
                }
            }, 600);
            return;
        }
        refresh(true);
    }

    /** 后台任务收尾：一定要调用，否则页面会一直卡在加载态。 */
    void done() {
        host.post(() -> {
            loading = false;
            headerSpinner(false);
            if (layout != null) {
                layout.finishRefresh();
            }
        });
    }

    /**
     * magpie 说「额度还在读」时，过一会儿自己再问一次。
     *
     * <p>供应商额度是异步读的：第一次请求秒回一份旧卡，同时回一个
     * {@code X-Magpie-Reading: 1}，网页看到这个头就隔一会儿再问。这里一样，
     * 但只重提三次，不追着网关打。
     */
    void recheckLater() {
        if (rechecks >= 5 || !attached) {
            return;
        }
        rechecks++;
        pendingRecheck = () -> {
            pendingRecheck = null;
            if (attached) {
                refresh(true);
            }
        };
        host.postDelayed(pendingRecheck, 3000L * rechecks);
    }

    /** 一次真正的刷新后，重提计数清零。 */
    void rechecksDone() {
        rechecks = 0;
    }

    private void headerSpinner(boolean on) {
        // 爸爸 2026-11-24 定：读取过程不给用户看任何指示——下拉刷新有它自己的
        // 转圈（PullToRefresh 自带），页面内不再叠「正在读取」浮条。
        // 空方法占位：保留调用点，将来要恢复往这里加。
    }

    /** 顶部读取条：转圈 + 文字，浮在页面内容上方，不被内容重建清掉。惰性建（要用 host）。 */
    private LinearLayout spinnerBar;
    private android.view.animation.Animation spinnerAnim;
    private android.widget.ProgressBar spinnerView;

    private void ensureSpinner() {
        if (spinnerBar != null) {
            return;
        }
        spinnerView = new android.widget.ProgressBar(host);
        spinnerBar = makeSpinnerBar();
        spinnerAnim = makeSpinnerAnim();
        spinnerView.setAnimation(spinnerAnim);
    }

    private LinearLayout makeSpinnerBar() {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER);
        bar.setVisibility(android.view.View.GONE);
        // 不要灰色挡条：就一个转圈 + 文字，浮在页面顶部
        int pad = ui.dp(8);
        bar.setPadding(pad, ui.dp(4), pad, ui.dp(4));
        spinnerView.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
        bar.addView(spinnerView);
        refreshHint.setPadding(ui.dp(8), 0, 0, 0);
        bar.addView(refreshHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private android.view.animation.Animation makeSpinnerAnim() {
        android.view.animation.RotateAnimation a = new android.view.animation.RotateAnimation(
                0, 360, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        a.setDuration(900);
        a.setRepeatCount(android.view.animation.Animation.INFINITE);
        a.setInterpolator(host, android.R.anim.linear_interpolator);
        return a;
    }

    // ------------------------------------------------------------------
    // 三种兜底状态
    // ------------------------------------------------------------------

    void showLoading() {
        LinearLayout target = renderTarget();
        if (target == null) {
            return;
        }
        waiting = false;
        target.removeAllViews();
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(24), ui.dp(60), ui.dp(24), ui.dp(24));
        TextView t = ui.text("正在读取 magpie…", 14, M3.ON_SURFACE_VARIANT, false);
        t.setGravity(android.view.Gravity.CENTER);
        box.addView(t);
        target.addView(box);
    }

    /** 网关还没起来时的等待态，跟「读不到」区分开。 */
    void showWaiting() {
        LinearLayout target = renderTarget();
        if (target == null) {
            return;
        }
        waiting = true;
        headerSpinner(false);
        target.removeAllViews();
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(24), ui.dp(56), ui.dp(24), ui.dp(24));
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        TextView t = ui.text("网关正在启动…", 16, M3.ON_SURFACE, true);
        t.setGravity(android.view.Gravity.CENTER);
        box.addView(t);

        TextView s = ui.text("magpie 的内核第一次启动要解压二进制，通常十几秒。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        s.setGravity(android.view.Gravity.CENTER);
        s.setLayoutParams(ui.matchWrap(8));
        box.addView(s);

        TextView log = ui.pill("看日志", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        log.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        StringBuilder sb = new StringBuilder();
        String tail = GatewayService.lastLog(6);
        if (tail != null && !tail.trim().isEmpty()) {
            sb.append(tail.trim());
        }
        String last = sb.length() > 240 ? sb.substring(sb.length() - 240) : sb.toString();
        if (!last.isEmpty()) {
            TextView l = ui.mono(last, 10.5f, M3.ON_SURFACE_VARIANT);
            l.setLayoutParams(ui.matchWrap(16));
            box.addView(l);
        }
        target.addView(box);
    }

    /** 读不到时：原因 + 重试 + 打开网页兜底。 */
    void failed(String why) {
        LinearLayout target = renderTarget();
        if (target == null) {
            return;
        }
        waiting = false;
        target.removeAllViews();

        LinearLayout box = ui.cardWithMargin();
        box.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(20));

        TextView title = ui.text("这一页现在读不出来", 16, M3.ON_SURFACE, true);
        box.addView(title);

        TextView body = ui.text(why, 12.5f, M3.ON_SURFACE_VARIANT, false);
        body.setLineSpacing(ui.dp(3), 1f);
        body.setLayoutParams(ui.matchWrap(8));
        box.addView(body);

        LinearLayout actions = ui.row();
        actions.setLayoutParams(ui.matchWrap(14));

        TextView retry = ui.pill("重试", M3.SECONDARY_CONTAINER, M3.ON_SECONDARY_CONTAINER);
        retry.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        retry.setOnClickListener(v -> refresh(true));
        actions.addView(retry);

        TextView web = ui.pill("打开网页", M3.SURFACE_CONTAINER_HIGH, M3.ON_SURFACE);
        web.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9));
        LinearLayout.LayoutParams wlp = ui.wrap();
        wlp.leftMargin = ui.dp(8);
        web.setOnClickListener(v -> host.startActivity(
                new android.content.Intent(host, MainActivity.class)));
        actions.addView(web, wlp);

        box.addView(actions);
        target.addView(box);

        TextView hint = ui.text("magpie 的接口是内部接口，升级后字段可能变。"
                + "网页界面永远是最全的，长按右上角地址栏也能直接进。",
                11.5f, M3.ON_SURFACE_VARIANT, false);
        hint.setPadding(ui.dp(24), ui.dp(14), ui.dp(24), 0);
        hint.setLineSpacing(ui.dp(2), 1f);
        target.addView(hint);
    }

    /** 空数据时的一句提示。 */
    View empty(String s) {
        LinearLayout box = ui.cardWithMargin();
        TextView t = ui.text(s, 13, M3.ON_SURFACE_VARIANT, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, ui.dp(24), 0, ui.dp(24));
        box.addView(t);
        return box;
    }
}
