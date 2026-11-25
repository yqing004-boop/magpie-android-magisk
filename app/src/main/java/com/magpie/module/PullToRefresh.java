package com.magpie.module;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 下拉刷新容器（自写版）。
 *
 * <p>项目里没有 AndroidX，用不了 {@code SwipeRefreshLayout}，所以自己搭一个
 * 最小实现：内容滚到顶部之后再往下拽，顶部露出一个转圈；松手过阈值就触发
 * 回调，圈转起来，**至少转 0.5 秒**再收（不然网络快的时候一闪而过，看不出
 * 到底刷没刷）。
 *
 * <p>为什么不用 {@code NestedScrollView}：一样是没有 AndroidX。这里直接拦
 * {@link MotionEvent}，条件很窄（只有 {@code scrollY == 0} 且手势向下）才
 * 接管，其他情况全部交还给 ScrollView，滚动本身不受影响。
 */
final class PullToRefresh extends FrameLayout {

    /** 触发刷新需要的下拉距离。 */
    private static final int THRESHOLD_DP = 60;
    /** 圈最多拉到多远（再拉也不动了）。 */
    private static final int MAX_DP = 100;
    /** 圈至少转这么久才收，太快会让人以为没刷新。 */
    private static final long MIN_SPIN_MS = 500;

    private final ScrollView scroll;
    private final Ui ui;
    private final Spinner spinner;
    private final TextView label;
    private final LinearLayoutBox holder;

    private Runnable onRefresh;
    private boolean refreshing;
    private boolean dragging;
    private float downY;
    private int offset;
    private long refreshStartedAt;

    private final Handler ui2 = new Handler(Looper.getMainLooper());

    PullToRefresh(Context ctx, ScrollView scroll, Ui ui) {
        super(ctx);
        this.scroll = scroll;
        this.ui = ui;

        addView(scroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶上的小圈 + 一行字，平时藏在屏幕外
        holder = new LinearLayoutBox(ctx);
        holder.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        holder.setGravity(android.view.Gravity.CENTER);

        spinner = new Spinner(ctx, ui);
        holder.addView(spinner, new android.widget.LinearLayout.LayoutParams(
                ui.dp(18), ui.dp(18)));

        label = ui.text("下拉刷新", 11.5f, M3.ON_SURFACE_VARIANT, false);
        label.setPadding(ui.dp(8), 0, 0, 0);
        holder.addView(label);

        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        holder.setLayoutParams(lp);
        addView(holder);
        holder.setTranslationY(-ui.dp(48));
    }

    void setOnRefresh(Runnable r) {
        this.onRefresh = r;
    }

    /**
     * 数据到了：收起圈。
     *
     * <p>但至少让圈转满 {@link #MIN_SPIN_MS} —— 本地接口几十毫秒就回来了，
     * 不兜住的话用户只看到闪一下，分不清到底刷没刷。
     */
    void finishRefresh() {
        if (!refreshing) {
            return;
        }
        long spent = System.currentTimeMillis() - refreshStartedAt;
        long wait = Math.max(0, MIN_SPIN_MS - spent);
        ui2.postDelayed(this::collapse, wait);
    }

    private void collapse() {
        refreshing = false;
        offset = 0;
        spinner.stop();
        animate().setDuration(0).withEndAction(() -> {
            holder.setTranslationY(-ui.dp(48));
            label.setText("下拉刷新");
        }).start();
        scroll.animate().translationY(0).setDuration(140).start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                dragging = scroll.getScrollY() <= 0;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging || refreshing) {
                    break;
                }
                float dy = ev.getY() - downY;
                if (dy > 0 && scroll.getScrollY() <= 0) {
                    offset = (int) Math.min(dy * 0.55f, ui.dp(MAX_DP));
                    applyOffset(offset);
                    return true;
                }
                if (offset > 0) {
                    offset = 0;
                    applyOffset(0);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    break;
                }
                dragging = false;
                if (offset >= ui.dp(THRESHOLD_DP) && !refreshing) {
                    startRefresh();
                    offset = 0;
                    return true;
                }
                if (offset > 0) {
                    offset = 0;
                    applyOffset(0);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void applyOffset(int px) {
        scroll.setTranslationY(px * 0.6f);
        holder.setTranslationY(-ui.dp(48) + px);
        spinner.setProgress(Math.min(1f, px / (float) ui.dp(THRESHOLD_DP)));
        label.setText(px >= ui.dp(THRESHOLD_DP) ? "松手刷新" : "下拉刷新");
    }

    private void startRefresh() {
        refreshing = true;
        refreshStartedAt = System.currentTimeMillis();
        label.setText("正在刷新…");
        spinner.setProgress(1f);
        spinner.start();
        scroll.animate().translationY(ui.dp(30)).setDuration(120).start();
        holder.animate().translationY(ui.dp(14)).setDuration(120).start();
        if (onRefresh != null) {
            onRefresh.run();
        }
    }

    // ------------------------------------------------------------------
    // 手绘的小转圈：一圈轨道 + 一段跑动的弧
    // ------------------------------------------------------------------

    static final class Spinner extends View {

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float angle;
        private float progress = 1f;
        private boolean spinning;
        private final float stroke;

        Spinner(Context ctx, Ui ui) {
            super(ctx);
            stroke = ui.dp(2);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(ui.col(M3.SURFACE_CONTAINER_HIGHEST));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(ui.col(M3.PRIMARY));
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            if (!spinning) {
                invalidate();
            }
        }

        void start() {
            spinning = true;
            postOnAnimation(tick);
        }

        void stop() {
            spinning = false;
            removeCallbacks(tick);
            progress = 0f;
            invalidate();
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!spinning) {
                    return;
                }
                angle = (angle + 12) % 360;
                invalidate();
                postOnAnimationDelayed(this, 16);
            }
        };

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = stroke;
            rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawArc(rect, 0, 360, false, track);
            if (progress <= 0) {
                return;
            }
            // 旋转中：一段会跑的弧；未触发时：按拉下比例画一段弧
            float sweep = spinning ? 90f : 300f * progress;
            canvas.drawArc(rect, angle, sweep, false, arc);
        }
    }

    /** 只是给 holder 一个短名字，省得满屏 LinearLayout。 */
    private static final class LinearLayoutBox extends android.widget.LinearLayout {
        LinearLayoutBox(Context ctx) {
            super(ctx);
        }
    }
}
