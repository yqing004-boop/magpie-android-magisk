package com.magpie.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生主界面：不再有 WebView，直接调 magpie 的 HTTP 接口画出来。
 *
 * <pre>
 * ┌──────────────────────────────┐
 * │ ○ Magpie            [地址] ⋮ │  顶栏：状态 + 网关地址 + 菜单
 * ├──────────────────────────────┤
 * │                              │
 * │        页面内容（可滚动）      │  概览 / 用量 / 账号 / 路由
 * │                              │
 * ├──────────────────────────────┤
 * │  概览    用量    账号    路由  │  底部切换
 * └──────────────────────────────┘
 * </pre>
 *
 * <p>WebView 那版整个留着（{@link MainActivity}），从 ⋮ 菜单进得去：
 * 插件安装、隐私脱敏、同步备份这些深度功能网页才全。
 */
public class NativeHome extends Activity {

    private static final String TAG = "MagpieNative";

    static final int TAB_OVERVIEW = 0;
    static final int TAB_USAGE = 1;
    static final int TAB_ACCOUNTS = 2;
    static final int TAB_MASKING = 3;
    static final int TAB_ROUTES = 4;

    /** 前台可见时多久自己刷一次页面数据。 */
    private static final long AUTO_REFRESH = 40_000L;

    Ui ui;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private ImageView avatar;
    private TextView stateText;
    private View stateDot;
    private TextView gwPill;
    private TextView lanPill;
    /** 顶栏中间的「插件」胶囊：点一下直接进网页的插件页。 */
    private TextView pluginPill;

    private FrameLayout pageHost;
    private LinearLayout tabBar;

    private NativePage[] pages = new NativePage[5];
    private int tab = TAB_OVERVIEW;

    volatile boolean gatewayReady;
    private boolean polling;
    private boolean resumed;

    private final List<TextView> tabViews = new ArrayList<>();
    /** 底栏每个 tab 的图标字，选中时一起变色。 */
    private final List<TextView> tabGlyphs = new ArrayList<>();
    /** 底栏每个 tab 的整块视图，选中时给它挂胶囊底。 */
    private final List<android.widget.LinearLayout> tabItems = new ArrayList<>();
    /** 系统导航条高度：底栏要抬到它上面，内容则铺到它下面（真沉浸）。 */
    private int navHeight;
    /** 同一个值给 NativePage 用（页面算底部留白）。 */
    private static int sNavHeight;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshHeader();
            uiHandler.postDelayed(this, 2000);
        }
    };

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (resumed && gatewayReady) {
                currentPage().refresh(false);
            }
            uiHandler.postDelayed(this, AUTO_REFRESH);
        }
    };

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        Prefs.webKey(this);
        buildShell();
        setupImmersive();
        startGateway();
        pollGateway();
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab", TAB_OVERVIEW);
        } else {
            // 允许外部指定一上来停在哪个 tab（调式与快捷方式用）
            tab = getIntent() == null ? TAB_OVERVIEW
                    : getIntent().getIntExtra("tab", TAB_OVERVIEW);
        }
        selectTab(tab, true);

        // 版本更新弹窗：升级过来就弹一次（排在保活引导之前，用户最先看到）
        uiHandler.postDelayed(() -> WhatsNew.maybeShow(this), 1200);

        // 后台保活引导（通知权限 / 电池优化 / 厂商自启动）和两个自动检查
        // 跟旧壳一致，只是延后一点，别和首屏渲染抢主线程
        uiHandler.postDelayed(() -> BackgroundGuide.maybeShowOnLaunch(this), 2500);
        // 小米系且没设「无限制」：发一条通知引导（不弹框、不挡用户看数据）
        uiHandler.postDelayed(() -> FreezeGuard.nudgeOnOpen(this), 3200);
        uiHandler.postDelayed(this::autoCheckUpdates, 4000);
        uiHandler.postDelayed(() -> ShellUpdateFlow.autoCheck(this, ShellUpdateFlow.m3(this, ui)),
                6000);
    }

    /**
     * 静默查模块内核的版本。
     *
     * <p>模块版的「内核」在 Magisk 模块里，不归 App 管、也不该由 App 替换：
     * 内核升级 = 换模块 zip = 重刷 Magisk。所以这里不做联网比对、不做下载，
     * 只把模块里实际装的版本号读出来，跟上次记下的比一眼，变了就提示一句
     * 「模块内核已更新」。
     */
    private void autoCheckUpdates() {
        bg(() -> {
            try {
                String ver = GatewayService.moduleVersion();
                if (ver == null || ver.isEmpty()) {
                    return;
                }
                String known = Prefs.installedVersion(this);
                if (!ver.equals(known)) {
                    Prefs.noteInstalled(this, ver, 0);
                    final String v = ver;
                    post(() -> toast("模块内核 " + v));
                }
            } catch (Exception e) {
                Log.i(TAG, "读模块版本没成功: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // 从后台回来：如果刚才后台待得够久（小米 greeze 约 40 秒就冻），
        // 补一条「为什么刚才卡」的提醒；时间戳为 0 时什么都不做。
        FreezeGuard.checkAfterReturn(this, Prefs.freezeLeftAt(this));
        // 前台才起这两条定时器：顶栏状态点每 2 秒、页面数据每 40 秒。
        // 放在 onResume 里成对启停，后台就一次唤醒都没有。
        uiHandler.removeCallbacks(ticker);
        uiHandler.removeCallbacks(autoRefresh);
        uiHandler.postDelayed(ticker, 400);
        uiHandler.postDelayed(autoRefresh, AUTO_REFRESH);
        // 从设置页回来可能换了种子色/莫奈，令牌变了就整页重来
        int[] now = M3.current(this);
        if (now[M3.SURFACE] != ui.col(M3.SURFACE)) {
            recreate();
            return;
        }
        refreshHeader(true);
        NativePage p = currentPage();
        p.onAttach();
        if (gatewayReady) {
            p.refresh(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // 后台不再唤醒 CPU：顶栏点不需要实时刷，页面数据等回到前台再说
        uiHandler.removeCallbacks(ticker);
        uiHandler.removeCallbacks(autoRefresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 记下退后台的时间。只在「真的离开 App」时记：
        // 跳到本 App 里的设置页/网页页时 onStop 也会响，但进程仍在前台，
        // 系统不会冻结，不该算成后台。此刻自己的 onActivityStopped 还没跑，
        // 所以 startedActivities()==1 就是「只剩我这一扇窗口了」。
        if (!isChangingConfigurations() && App.startedActivities() <= 1) {
            FreezeGuard.markBackgrounded(this);
        }
        // 后台把页面上的常驻任务全停掉（路由页的长轮询、额度重试），
        // 回到前台时 onResume 会把当前这个页重新挂上。
        for (NativePage p : pages) {
            if (p != null) {
                p.onDetach();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        pool.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FileManager.REQ_IMPORT_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                FileManager.onFilePicked(this, ui, data.getData());
            }
            return;
        }
        if (requestCode == PluginTools.REQ_PLUGIN_DIR) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                PluginTools.onFolderPicked(this, data.getData(), this::styleDialog);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (tab != TAB_OVERVIEW) {
            selectTab(TAB_OVERVIEW, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // 线程
    // ------------------------------------------------------------------

    void bg(Runnable r) {
        try {
            pool.execute(r);
        } catch (Exception e) {
            Log.w(TAG, "后台任务没排上: " + e);
        }
    }

    void post(Runnable r) {
        uiHandler.post(r);
    }

    void postDelayed(Runnable r, long ms) {
        uiHandler.postDelayed(r, ms);
    }

    /** 撤掉一个挂起的任务（页面切走时要撤重试）。 */
    void cancel(Runnable r) {
        uiHandler.removeCallbacks(r);
    }

    // ------------------------------------------------------------------
    // 骨架
    // ------------------------------------------------------------------

    private void buildShell() {
        FrameLayout root = new FrameLayout(this);
        // 根布局不铺实色：页面自己在 shell 里铺底色，底栏悬浮在外面，
        // 它那圈空隙就透出系统壁纸（酷安那种观感）。
        root.setBackgroundColor(ui.col(M3.SURFACE));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        // 页面铺满整屏：胶囊是浮在它上面的半透明层，透出来的是下面滚动的
        // 内容（酷安那种），不是壁纸 —— 所以这里不能留空。
        shell.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(buildHeader());

        pageHost = new FrameLayout(this);
        shell.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(shell);

        // 底栏是浮在内容上面的胶囊：不占布局高度，页面在它下面继续铺。
        // 宽度铺满（左右各留 16dp），像酷安那样一条横贯的胶囊，而不是中间
        // 一小块 —— 四个 tab 平分宽度，手指更好按。
        View bar = buildTabBar();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        // 照酷安那条的比例：左右只留一点点（8dp），离屏幕底边也近（10dp），
        // 整条细长横贯；上下留白靠胶囊自己的 padding，不靠外边距。
        blp.leftMargin = ui.dp(8);
        blp.rightMargin = ui.dp(8);
        blp.bottomMargin = ui.dp(6) + navHeight;
        root.addView(bar, blp);

        // 注意：这里**不**给 pageHost 留底部空白。内容要一直铺到屏幕底边，
        // 这样滚动时卡片从半透明胶囊下面经过，才有「浮在上面」的观感。
        // 最后一张卡会不会被挡住由页面自己的底部 padding 负责（见 NativePage）。
        setContentView(root);
    }

    /**
     * 悬浮底栏的高度（含外边距），页面用它算底部留白。
     *
     * <p>页面内容铺到屏幕底边（这样胶囊下面才有东西透出来），但最后一屏
     * 要能滚出胶囊，所以滚动内容自己再加这么多底部空白。
     */
    static int bottomInset(Ui ui) {
        // 底栏实际约 54dp（比之前低），外面上下各 14dp，再算上导航条
        return ui.dp(58) + ui.dp(6) * 2 + sNavHeight;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = ui.dp(14), padV = ui.dp(6);
        header.setPadding(padH, padV, ui.dp(6), padV);
        header.setMinimumHeight(ui.dp(60));

        avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_magpie_bird);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable av = new GradientDrawable();
        av.setShape(GradientDrawable.OVAL);
        av.setColor(ui.col(M3.PRIMARY_CONTAINER));
        avatar.setBackground(av);
        header.addView(avatar, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(10);
        header.addView(titles, tlp);

        TextView title = ui.text("Magpie", 18, M3.ON_SURFACE, true);
        titles.addView(title);

        LinearLayout stateRow = ui.row();
        stateRow.setLayoutParams(ui.matchWrap(2));
        stateDot = new View(this);
        stateDot.setBackgroundResource(R.drawable.dot_off);
        stateRow.addView(stateDot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        stateText = ui.text(getString(R.string.header_starting), 12, M3.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams slp = ui.wrap();
        slp.leftMargin = ui.dp(6);
        stateRow.addView(stateText, slp);
        titles.addView(stateRow);

        gwPill = ui.text("", 11.5f, M3.ON_SURFACE_VARIANT, false);
        gwPill.setBackground(ui.glowStroke(ui.col(M3.SURFACE),
                999, ui.col(M3.OUTLINE_VARIANT), 1));
        gwPill.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5));
        gwPill.setGravity(Gravity.CENTER);
        gwPill.setOnClickListener(v -> showGatewayDialog());
        gwPill.setOnLongClickListener(v -> {
            openWebUi();
            return true;
        });
        LinearLayout.LayoutParams glp = ui.wrap();
        glp.rightMargin = ui.dp(4);
        header.addView(gwPill, glp);

        // 中间的「插件」胶囊：和「局域网」同一套样式，点一下直接开网页的
        // 插件页（插件安装、登录、开关都在那里，原生做不完）。
        pluginPill = ui.text("插件", 11.5f, M3.ON_SECONDARY_CONTAINER, false);
        pluginPill.setBackground(ui.glowStroke(ui.col(M3.SECONDARY_CONTAINER),
                999, ui.col(M3.SECONDARY_CONTAINER), 0));
        pluginPill.setPadding(ui.dp(9), ui.dp(5), ui.dp(9), ui.dp(5));
        pluginPill.setGravity(Gravity.CENTER);
        pluginPill.setOnClickListener(v -> openWebView("plugins"));
        LinearLayout.LayoutParams plp = ui.wrap();
        plp.rightMargin = ui.dp(4);
        header.addView(pluginPill, plp);

        lanPill = ui.text("", 11.5f, M3.ON_SECONDARY_CONTAINER, false);
        lanPill.setBackground(ui.glowStroke(ui.col(M3.SECONDARY_CONTAINER),
                999, ui.col(M3.SECONDARY_CONTAINER), 0));
        lanPill.setPadding(ui.dp(9), ui.dp(5), ui.dp(9), ui.dp(5));
        lanPill.setGravity(Gravity.CENTER);
        lanPill.setOnClickListener(v -> showLanDialog());
        LinearLayout.LayoutParams llp = ui.wrap();
        llp.rightMargin = ui.dp(2);
        header.addView(lanPill, llp);

        TextView menu = ui.text("\u22EE", 20, M3.ON_SURFACE_VARIANT, true);
        menu.setGravity(Gravity.CENTER);
        menu.setBackgroundResource(selectableItemBackground());
        menu.setOnClickListener(v -> showMenu());
        header.addView(menu, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        excludeFromGestures(menu);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(header);
        return wrap;
    }

    private int selectableItemBackground() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }

    /**
     * 悬浮胶囊底栏。
     *
     * <p>观感照酷安那条：整条是一颗横贯屏幕的胶囊，四周留空隙、透出下面的
     * 内容；四个 tab 平分宽度，选中的那颗带一块更亮的胶囊底。
     * 胶囊本身是一层半透明的亮色玻璃（{@link M3#SURFACE_BRIGHT} + alpha）。
     */
    private View buildTabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);

        // 玻璃胶囊。
        //
        // 关键：底色不能取 surfaceContainerHigh —— 它和页面背景本来就只差
        // 几个色阶，铺上去就是「白底上画了个白胶囊」，看不出透明。
        // 换成 SURFACE_BRIGHT（浅色主题是纯白，深色是很深的棕黑）配 78% 的
        // alpha：浅底上明显提亮发白、深底上明显压暗，一眼就是一层玻璃。
        int bright = ui.col(M3.SURFACE_BRIGHT);
        // 不要太透：94% 不透明度，接近磨砂玻璃的那种「糊但看得见」；
        // 真要毛玻璃效果要靠下面的 RenderEffect 模糊。
        int glass = (bright & 0x00FFFFFF) | 0xF0000000;
        GradientDrawable pill = ui.round(glass, 999);
        // 描边用同一个亮色再淡一档，边缘才有玻璃的高光感
        pill.setStroke(Math.max(1, ui.dp(1)), (bright & 0x00FFFFFF) | 0x55000000);
        tabBar.setBackground(pill);
        // 阴影让胶囊从内容里浮起来；elevation 要有背景才画得出来
        tabBar.setElevation(ui.dp(10));
        tabBar.setClipToOutline(false);
        int padH = ui.dp(8), padV = ui.dp(8);
        tabBar.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabBar.setLayoutParams(blp);

        String[] names = {"概览", "用量", "账号", "脱敏", "路由"};
        String[] glyphs = {"\u25C9", "\u2261", "\u25D1", "\u25C7", "\u21C4"};
        tabViews.clear();
        tabGlyphs.clear();
        for (int i = 0; i < names.length; i++) {
            final int index = i;

            LinearLayout item = ui.column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, ui.dp(5), 0, ui.dp(5));

            TextView glyph = ui.text(glyphs[i], 15, M3.ON_SURFACE_VARIANT, false);
            glyph.setGravity(Gravity.CENTER);
            glyph.setIncludeFontPadding(false);
            item.addView(glyph);

            TextView t = ui.text(names[i], 11.5f, M3.ON_SURFACE_VARIANT, true);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            LinearLayout.LayoutParams tlp = ui.wrap();
            tlp.topMargin = ui.dp(3);
            item.addView(t, tlp);

            item.setOnClickListener(v -> {
                // 已在这个 tab 上时再点：收起展开的卡片并回到顶部初始位置
                if (index == tab) {
                    currentPage().collapseAndScrollTop();
                    return;
                }
                selectTab(index, true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(ui.dp(2), 0, ui.dp(2), 0);
            tabBar.addView(item, lp);
            tabViews.add(t);
            tabGlyphs.add(glyph);
            tabItems.add(item);
        }
        return tabBar;
    }

    // ------------------------------------------------------------------
    // tab
    // ------------------------------------------------------------------

    NativePage currentPage() {
        if (pages[tab] == null) {
            pages[tab] = createPage(tab);
        }
        return pages[tab];
    }

    private NativePage createPage(int which) {
        switch (which) {
            case TAB_USAGE:
                return new NativePageUsage(this);
            case TAB_ACCOUNTS:
                return new NativePageAccounts(this);
            case TAB_MASKING:
                return new NativePageMasking(this);
            case TAB_ROUTES:
                return new NativePageRoutes(this);
            default:
                return new NativePageOverview(this);
        }
    }

    void selectTab(int which, boolean refresh) {
        tab = Math.max(0, Math.min(4, which));
        // 设计稿：选中态用青色文字 + 青色图标，无胶囊背景，更克制
        int onFg = ui.design ? M3.PRIMARY : M3.ON_SECONDARY_CONTAINER;
        for (int i = 0; i < tabViews.size(); i++) {
            boolean on = i == tab;
            TextView t = tabViews.get(i);
            t.setTextColor(on ? ui.col(onFg) : ui.col(M3.ON_SURFACE_VARIANT));
            if (i < tabGlyphs.size()) {
                tabGlyphs.get(i).setTextColor(on
                        ? ui.col(onFg) : ui.col(M3.ON_SURFACE_VARIANT));
                tabGlyphs.get(i).setAlpha(on ? 1f : 0.75f);
            }
            if (i < tabItems.size()) {
                if (ui.design) {
                    // 设计稿：无胶囊底，保持透明
                    tabItems.get(i).setBackground(null);
                } else {
                    // 选中那颗带一层很淡的底：玻璃胶囊本身已经是亮的，再叠一块
                    // 实心的 secondaryContainer 会糊成一坨。用 22% 的主题色。
                    int tint = ui.col(M3.SECONDARY_CONTAINER);
                    int soft = (tint & 0x00FFFFFF) | 0x38000000;
                    tabItems.get(i).setBackground(on ? ui.round(soft, 999) : null);
                }
            }
        }
        // 上一个页如果挂着常驻轮询，先收掉，别在后台空转；
        // 当前这个页重新挂上（切回来时要恢复它的定时器）
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] == null) {
                continue;
            }
            if (i == tab) {
                pages[i].onAttach();
            } else {
                pages[i].onDetach();
            }
        }

        NativePage p = currentPage();
        p.onAttach();
        pageHost.removeAllViews();
        pageHost.addView(p.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 切页要从头看：上一个页的滚动位置留着没意义（而且点进来看到一半的内容很奇怪）
        p.view().post(() -> p.view().scrollTo(0, 0));
        if (gatewayReady) {
            if (refresh) {
                p.refresh(false);
            }
        } else {
            p.showWaiting();
            if (!polling) {
                pollGateway();
            }
        }
    }

    // ------------------------------------------------------------------
    // 网关
    // ------------------------------------------------------------------

    private void startGateway() {
        try {
            Intent i = new Intent(this, GatewayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "网关服务起不来", e);
        }
    }

    private void pollGateway() {
        if (polling) {
            return;
        }
        polling = true;
        bg(() -> {
            String host = Prefs.browserHost(this);
            int port = GatewayService.webPort(this);
            for (int i = 0; i < 200; i++) {
                if (GatewayService.portOpen(host, port)) {
                    post(() -> {
                        polling = false;
                        gatewayReady = true;
                        refreshHeader();
                        // 网关就绪后把壳里的局域网开关推给 magpie（幂等）
                        LanSync.pushIfNeeded(this);
                        currentPage().refresh(true);
                    });
                    return;
                }
                GatewayService.sleep(250);
            }
            post(() -> {
                polling = false;
                gatewayReady = false;
                refreshHeader();
                // 一直没等到端口：页面上给「启动中」而不是「读不到」，
                // 因为这时候接口本来就还没起来，不是读失败。
                currentPage().showWaiting();
            });
        });
    }

    /**
     * 顶栏状态点、地址胶囊、局域网胶囊。
     *
     * <p>每 2 秒调一次，所以**值没变就别碰 View**：{@code setText} 会触发布局
     * 测量和重绘，两年下来是白白几百次无用的 measure/layout。
     *
     * @param force 忽略缓存强制刷一遍（onResume、改设置后要用）
     */
    void refreshHeader() {
        refreshHeader(false);
    }

    private int headerState = -1;
    private String headerGw, headerLan;

    private void refreshHeader(boolean force) {
        boolean alive = GatewayService.isAlive() || gatewayReady;
        int state = gatewayReady ? 2 : alive ? 1 : 0;

        if (force || state != headerState) {
            headerState = state;
            stateDot.setBackgroundResource(gatewayReady
                    ? R.drawable.dot_ok : R.drawable.dot_off);
            stateText.setText(state == 2 ? R.string.header_running
                    : state == 1 ? R.string.header_starting
                    : R.string.header_stopped);
        }
        if (state == 0 && !polling) {
            pollGateway();
        }

        String gw = Prefs.browserHost(this) + ":" + GatewayService.gatewayPort(this);
        if (force || !gw.equals(headerGw)) {
            headerGw = gw;
            gwPill.setText(gw);
        }
        String lan = Prefs.lan(this) ? "局域网" : "本机";
        if (force || !lan.equals(headerLan)) {
            headerLan = lan;
            lanPill.setText(lan);
        }

        // syncNotification 内部已按状态翻转去重（lastNotifAlive），
        // ticker 每 2 秒调它不会重发通知。
        if (alive && gatewayReady) {
            GatewayService.syncNotification(this, true);
        }
    }

    // ------------------------------------------------------------------
    // 菜单
    // ------------------------------------------------------------------

    /**
     * 顶栏 ⋮：外壳能做的事全在这儿。
     *
     * <p>不用 AlertDialog 的纯文字列表（那样就是一排字，没有边界），改成
     * 自己搭的底部面板：每个动作都是一张带圆角底、描边、涟漪的卡片，
     * 左边一个图标底块，右边标题 + 一行说明。分组标题是浅色小字。
     */
    private void showMenu() {
        // 第一组就是「打开控制后台」——最常用的一条，压在最上面，不用翻。
        final String[] groups = {"控制台", "网关", "插件", "模块", "其他"};
        final String[][] titles = {
                {"赞赏作者", "打开控制后台", "错误诊断", "AI 排查端口"},
                {"重启网关", "查看日志", "复制网关地址", "忽略电池优化"},
                {"导入本地插件", "粘贴 Marvis 登录回调", "文件管理器", "省电策略实验室", "缓存清理"},
                {"模块信息与自检", "检查 App 更新", "模块刷入与换内核说明"},
                {"复制网页登录链接", "用浏览器打开", "设置", "关于"},
        };
        final String[][] subs = {
                {"微信赞赏码 + 支付宝收款码；纯属自愿，不构成任何服务承诺",
                        "magpie 自己的完整网页界面：插件、脱敏、同步备份都在这里",
                        "收集全局现场：日志、插件加载错误、流量归属、外部连接、进程资源，发作者排查",
                        "本机开一个诊断端口，手机上的 AI 软件或电脑都能直接连上来查"},
                {"先停再起，改过密钥或配置后用得上",
                        "网关最后写下的三百行输出", "把 …:3425/v1 复制到剪贴板",
                        "让系统不要在熄屏后冻结网关"},
                {"从手机存储选文件夹，拷进沙箱并交给 magpie",
                        "浏览器里登录完，把地址栏整条 URL 贴进来",
                        "浏览、导入、删除、复制 magpie HOME 目录里的文件",
                        "诊断后台被冻结的原因，可一键设无限制（需 root）",
                        "删旧版 Bun、更新包残留、可重建的索引，实测能腾出几百 MB"},
                {"版本号、路径、端口、只读桥与 Shizuku 状态；一键重启内核",
                        "这个壳（APK）自己的新版本，只提示不自动装",
                        "内核升级 = 换模块 zip 重刷；怎么刷、怎么回滚都在这里"},
                {"带 k= 的完整网页地址，粘到浏览器里直接登进去（绕过登录页）",
                        "交给系统浏览器，方便用电脑上的工具看",
                        "端口、局域网访问、外观与开机启动",
                        "官方仓库、移植作者与许可证"},
        };
        final String[][] icons = {
                {"\u2661", "\u2699", "\u2691", "\u2318"},
                {"\u27f3", "\u25a4", "\u29c9", "\u26a1"},
                {"\u2913", "\u270e", "\u25a4", "\u26a1", "\u267b"},
                {"\u21e9", "\u2b07", "\u2699"},
                {"\u29c9", "\u2197", "\u2699", "\u24d8"},
        };

        final Dialog dialog = new Dialog(this, R.style.Theme_Magpie_Sheet);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.col(M3.SURFACE_CONTAINER_LOW));
        float r = ui.dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(ui.dp(16), ui.dp(10), ui.dp(16),
                ui.dp(16) + MainActivity.barHeightOf(this, "navigation_bar_height"));

        // 拖拽条
        View handle = new View(this);
        GradientDrawable hb = ui.round(ui.col(M3.OUTLINE_VARIANT), 2);
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = ui.dp(14);
        root.addView(handle, hlp);

        TextView title = ui.text("菜单", 21, M3.ON_SURFACE, true);
        title.setPadding(ui.dp(4), 0, 0, ui.dp(4));
        root.addView(title);

        TextView sub = ui.text("网关 " + Prefs.browserHost(this) + ":"
                        + GatewayService.gatewayPort(this) + " · 网页 "
                        + GatewayService.webPort(this),
                12.5f, M3.ON_SURFACE_VARIANT, false);
        sub.setPadding(ui.dp(4), 0, 0, ui.dp(10));
        root.addView(sub);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int g = 0; g < titles.length; g++) {
            TextView gh = ui.text(groups[g], 11.5f, M3.ON_SURFACE_VARIANT, true);
            gh.setLetterSpacing(0.06f);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.setMargins(ui.dp(6), ui.dp(12), 0, ui.dp(6));
            list.addView(gh, glp);

            for (int i = 0; i < titles[g].length; i++) {
                final int gg = g, ii = i;
                list.addView(menuCard(icons[g][i], titles[g][i], subs[g][i], () -> {
                    dialog.dismiss();
                    runMenuAction(gg, ii, titles[gg][ii]);
                }));
            }
        }

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(maxH, getResources().getDisplayMetrics().heightPixels));
        }
        dialog.show();
    }

    /** 菜单里一张可点的卡片：图标底块 + 标题 + 说明，带描边和涟漪。 */
    private View menuCard(String icon, String title, String sub, Runnable action) {
        LinearLayout row = ui.row();
        GradientDrawable cardBg = ui.glowStroke(ui.col(M3.SURFACE_CONTAINER), 16,
                ui.col(M3.OUTLINE_VARIANT), 1);
        ui.clickable(row, cardBg);
        if (ui.design) {
            row.setElevation(ui.dp(6));
        }
        row.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = ui.dp(7);
        row.setLayoutParams(rlp);

        TextView ic = ui.text(icon, 15, M3.ON_SECONDARY_CONTAINER, false);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(ui.round(ui.col(M3.SECONDARY_CONTAINER), 12));
        row.addView(ic, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));

        LinearLayout texts = ui.column();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ui.dp(12);
        row.addView(texts, tlp);

        TextView t = ui.text(title, 14.5f, M3.ON_SURFACE, true);
        t.setSingleLine(true);
        texts.addView(t);

        TextView st = ui.text(sub, 11.5f, M3.ON_SURFACE_VARIANT, false);
        st.setLineSpacing(ui.dp(1), 1f);
        st.setLayoutParams(ui.matchWrap(2));
        texts.addView(st);

        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** 菜单动作表：和 {@link #showMenu()} 里的 titles 一一对应。 */
    private void runMenuAction(int group, int index, String title) {
        switch (group) {
            case 0: // 控制台
                if (index == 0) {
                    showDonate();
                } else if (index == 1) {
                    openWebUi();
                } else if (index == 2) {
                    confirmAndDiagnose();
                } else {
                    showDiagPort();
                }
                break;
            case 1: // 网关
                switch (index) {
                    case 0:
                        GatewayService.restart(this);
                        gatewayReady = false;
                        toast("正在重启网关…");
                        pollGateway();
                        break;
                    case 1:
                        showLog();
                        break;
                    case 2:
                        copyGateway();
                        break;
                    default:
                        ignoreBattery();
                        break;
                }
                break;
            case 2: // 插件
                if (index == 0) {
                    PluginTools.pickFolder(this);
                } else if (index == 1) {
                    PluginTools.promptMarvisCallback(this, ui, this::styleDialog);
                } else if (index == 2) {
                    FileManager.open(this, ui, FileManager.root(this));
                } else if (index == 3) {
                    PowerLab.show(this, ui);
                } else {
                    CacheCleaner.show(this, ui);
                }
                break;
            case 3: // 模块
                if (index == 0) {
                    showModuleInfo();
                } else if (index == 1) {
                    ShellUpdateFlow.checkNow(this, this::styleDialog);
                } else {
                    showModuleHelp();
                }
                break;
            default: // 其他
                switch (index) {
                    case 0:
                        copyWebLoginLink();
                        break;
                    case 1:
                        openInBrowser();
                        break;
                    case 2:
                        openSettings();
                        break;
                    default:
                        showAbout();
                        break;
                }
                break;
        }
    }

    /**
     * 复制带 key 的网页登录链接。
     *
     * <p>为什么要有这条：magpie 网页对没带 key 的请求会回一张「magpie web」
     * 登录页，要你粘 MAGPIE_WEB_KEY。用户手上通常没有那串东西，就卡在登录页。
     * 而壳自己就知道 key（{@link GatewayService#webUrl} 拼的就是它）。
     *
     * <p><b>只复制本机回环这一条</b>：用户是在自己手机上复制、粘到本机浏览器，
     * 127.0.0.1 就够。绝不能把局域网地址用换行拼进去 —— 粘进地址栏后换行会
     * 污染 ?k= 参数，magpie 就报「this link's key is not this magpie web's」。
     */
    private void copyWebLoginLink() {
        String link = GatewayService.webUrl(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("magpie", link));
        }
        toast(Prefs.lan(this)
                ? "已复制本机登录链接，粘到浏览器即可"
                : "已复制登录链接，粘到浏览器即可（带 key，不用再输）");
    }

    /** 用系统浏览器打开 magpie 网页（带 key，直接登进去）。 */
    private void openInBrowser() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GatewayService.webUrl(this))));
        } catch (Exception e) {
            toast("打不开浏览器：" + e.getMessage());
        }
    }

    /** 给 NativePage 系用的弹窗样式入口（同 {@link #styleDialog}）。 */
    void styleDialogPublic(android.app.AlertDialog dlg) {
        styleDialog(dlg);
    }

    private void styleDialog(AlertDialog dlg) {
        try {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(ui.dialogBg());
            }
        } catch (Exception ignored) {
        }
        dlg.setOnShowListener(x -> {
            for (int id : new int[]{AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
                android.widget.Button b = dlg.getButton(id);
                if (b != null) {
                    b.setTextColor(ui.col(M3.PRIMARY));
                    b.setBackground(ui.dialogBtnBg());
                    android.view.ViewGroup.LayoutParams blp0 = b.getLayoutParams();
                    if (blp0 instanceof android.widget.LinearLayout.LayoutParams) {
                        android.widget.LinearLayout.LayoutParams blp =
                                (android.widget.LinearLayout.LayoutParams) blp0;
                        blp.setMargins(ui.dp(6), 0, ui.dp(6), 0);
                        b.setLayoutParams(blp);
                    }
                }
            }
        });
    }

    private void openWebUi() {
        openWebView(null);
    }

    /**
     * 打开网页界面，并停在指定的那一页。
     *
     * <p>magpie 的网页是单页应用，页面靠 URL 的 hash 切换（`#view=plugins`）。
     * 传 {@code view} 就直接落到那一页，省得用户自己再点一遍。
     *
     * @param view 要落的页（"plugins" / "providers" / "usage" …），null 就是首页
     */
    private void openWebView(String view) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            if (view != null && !view.isEmpty()) {
                i.putExtra(MainActivity.EXTRA_VIEW, view);
            }
            startActivity(i);
        } catch (Exception e) {
            toast("打不开网页界面：" + e.getMessage());
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void copyGateway() {
        String url = "http://" + Prefs.browserHost(this) + ":"
                + GatewayService.gatewayPort(this) + "/v1";
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("magpie", url));
        }
        toast("已复制 " + url);
    }

    /**
     * 点顶栏地址胶囊弹出的网关信息卡：本机地址 + 密钥掩码，
     * 三个按钮：复制密钥 / 关闭 / 复制网关。
     */
    private void showGatewayDialog() {
        String url = "http://" + Prefs.browserHost(this) + ":"
                + GatewayService.gatewayPort(this) + "/v1";
        final String secret = LanInfo.lanSecret(this);

        StringBuilder sb = new StringBuilder();
        sb.append("网关地址\n  ").append(url);
        if (secret != null && !secret.isEmpty()) {
            sb.append("\n\n密钥\n  ").append(LanInfo.mask(secret));
        } else {
            sb.append("\n\n密钥\n  还没有（共享没开或密钥已停用）");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("网关信息")
                .setMessage(sb.toString())
                .setPositiveButton("复制网关", (d, w) -> copyGateway());
        if (secret != null && !secret.isEmpty()) {
            b.setNeutralButton("复制密钥", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("magpie", secret));
                }
                toast("已复制完整密钥");
            });
        }
        b.setNegativeButton("关闭", null);
        AlertDialog dlg = b.create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 给 NativePage 系用的 toast 入口。 */
    void toastPublic(String s) {
        toast(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void showLanDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("网关地址\n");
        List<String> ips = LanInfo.localIps();
        if (ips == null || ips.isEmpty()) {
            sb.append("  没找到局域网地址\n");
        } else {
            for (String ip : ips) {
                sb.append("  http://").append(ip).append(':')
                        .append(GatewayService.gatewayPort(this)).append("/v1\n");
            }
        }
        final String secret = LanInfo.lanSecret(this);
        if (secret != null && !secret.isEmpty()) {
            sb.append("\n密钥\n  ").append(LanInfo.mask(secret)).append('\n');
        } else {
            sb.append("\n密钥\n  还没有（共享没开或密钥已停用）\n");
        }
        if (!Prefs.lan(this)) {
            sb.append("\n局域网访问现在是关的，去设置里打开才能被别的设备连上。");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("局域网访问")
                .setMessage(sb.toString())
                .setPositiveButton("复制地址", (d, w) -> {
                    String url = "http://" + (ips == null || ips.isEmpty()
                            ? "127.0.0.1" : ips.get(0)) + ":" + GatewayService.gatewayPort(this) + "/v1";
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("magpie", url));
                    }
                    toast("已复制 " + url);
                });
        // 密钥能拿到时给一个「复制密钥」按钮（明文进剪贴板）
        if (secret != null && !secret.isEmpty()) {
            b.setNeutralButton("复制密钥", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("magpie", secret));
                }
                toast("已复制完整密钥");
            });
        }
        b.setNegativeButton("关闭", null);
        AlertDialog dlg = b.create();
        styleDialog(dlg);
        dlg.show();
    }

    private void showLog() {
        String log = GatewayService.lastLog(200);
        if (log == null || log.trim().isEmpty()) {
            log = "（还没有输出）";
        }
        ScrollView sc = new ScrollView(this);
        TextView t = new TextView(this);
        t.setText(log);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextColor(ui.col(M3.ON_SURFACE_VARIANT));
        int p = ui.dp(16);
        t.setPadding(p, p, p, p);
        t.setTextIsSelectable(true);
        sc.addView(t);

        // 自建内容：日志 + 底部按钮行（四个动作，含「导出诊断包」）
        LinearLayout box = ui.column();
        LinearLayout head = ui.row();
        head.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(6));
        head.addView(ui.text("magpie 日志（最后 200 行）", 18, M3.ON_SURFACE, true), ui.weight(1));
        box.addView(head);
        LinearLayout scWrap = ui.column();
        scWrap.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(420)));
        box.addView(scWrap);

        LinearLayout actions = ui.row();
        actions.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10));
        TextView copy = ui.text("复制全部", 13.5f, M3.PRIMARY, true);
        copy.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("magpie", t.getText().toString()));
            }
            toast("已复制日志");
        });
        actions.addView(copy, ui.weight(1));

        TextView diag = ui.text("导出诊断包", 13.5f, M3.PRIMARY, true);
        diag.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        diag.setOnClickListener(v -> confirmAndDiagnose());
        actions.addView(diag, ui.weight(1));

        TextView report = ui.text("上报问题", 13.5f, M3.PRIMARY, true);
        report.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        report.setOnClickListener(v -> ReportIssue.show(this, ui));
        actions.addView(report, ui.weight(1));

        TextView close = ui.text("关闭", 13.5f, M3.PRIMARY, true);
        close.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        close.setOnClickListener(v -> {
            if (v.getTag() instanceof android.app.Dialog) {
                ((android.app.Dialog) v.getTag()).dismiss();
            }
        });
        actions.addView(close, ui.weight(1));
        box.addView(actions);

        android.app.Dialog dlg = new android.app.Dialog(this, R.style.Theme_Magpie_Sheet);
        close.setTag(dlg);
        GradientDrawable bg = ui.round(ui.col(M3.SURFACE_CONTAINER_LOW), 28);
        bg.setCornerRadii(new float[]{
                ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        box.setBackground(bg);
        dlg.setContentView(box);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.min(maxH, getResources().getDisplayMetrics().heightPixels));
        }
        dlg.show();
    }

    // ==================================================================
    // 错误诊断（菜单 → 控制台 → 错误诊断）
    // ==================================================================

    /**
     * 赞赏作者：微信赞赏码 + 支付宝收款码，两张图纵向排列。
     *
     * <p>纯属自愿，不构成任何服务承诺。magpie 本体由 yetone 开发，
     * 本项目只是非官方 Android 移植。
     */
    private void showDonate() {
        LinearLayout box = ui.column();
        int pad = ui.dp(18);
        box.setPadding(pad, ui.dp(6), pad, ui.dp(10));

        TextView tip = ui.text("如果这个移植对你有用，可以请我喝杯咖啡。\n"
                        + "赞赏纯属自愿，不构成任何服务承诺；magpie 本体由 yetone 开发，"
                        + "本项目只是非官方 Android 移植。",
                12, M3.ON_SURFACE_VARIANT, false);
        tip.setLineSpacing(ui.dp(2), 1f);
        tip.setLayoutParams(ui.matchWrap(8));
        box.addView(tip);

        // 支付宝一键跳转：收款码的二维码内容就是一个 https 链接，
        // 点按钮直接拉起支付宝到付款页，不用扫码
        TextView alipayBtn = ui.pill("用支付宝赞赏（不用扫码）",
                M3.PRIMARY_CONTAINER, M3.ON_PRIMARY_CONTAINER);
        alipayBtn.setPadding(ui.dp(18), ui.dp(11), ui.dp(18), ui.dp(11));
        alipayBtn.setLayoutParams(ui.matchWrap(10));
        alipayBtn.setOnClickListener(v -> openAlipayDonate());
        box.addView(alipayBtn);

        // 微信赞赏码
        box.addView(ui.text("微信", 13, M3.ON_SURFACE, true));
        android.widget.ImageView wx = new android.widget.ImageView(this);
        wx.setImageResource(R.drawable.donate_wechat);
        wx.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        wx.setAdjustViewBounds(true);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(260));
        wlp.topMargin = ui.dp(6);
        wlp.bottomMargin = ui.dp(14);
        box.addView(wx, wlp);
        wx.setOnClickListener(v -> zoomDonate(R.drawable.donate_wechat, "微信赞赏码"));

        // 支付宝收款码
        box.addView(ui.text("支付宝", 13, M3.ON_SURFACE, true));
        android.widget.ImageView ali = new android.widget.ImageView(this);
        ali.setImageResource(R.drawable.donate_alipay);
        ali.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        ali.setAdjustViewBounds(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(260));
        alp.topMargin = ui.dp(6);
        box.addView(ali, alp);
        ali.setOnClickListener(v -> zoomDonate(R.drawable.donate_alipay, "支付宝收款码"));

        TextView hint = ui.text("点图片可放大，长按保存或直接扫码", 11,
                M3.ON_SURFACE_VARIANT, false);
        hint.setLayoutParams(ui.matchWrap(8));
        box.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(box);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("赞赏作者")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 支付宝收款码解出的 URL：点开直接跳支付宝付款页。 */
    private static final String ALIPAY_DONATE_URL = "https://qr.alipay.com/fkx14569btoofplf4ketx74";

    /** 一键跳支付宝赞赏：ACTION_VIEW 打开收款链接；没装支付宝则提示。 */
    private void openAlipayDonate() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(ALIPAY_DONATE_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // 没装支付宝/浏览器：退回复制链接
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("magpie", ALIPAY_DONATE_URL));
            }
            toast("打不开支付宝，链接已复制：" + ALIPAY_DONATE_URL);
        }
    }

    /** 点赞赏码放大看，方便对方扫码。 */
    private void zoomDonate(int resId, String title) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(resId);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        iv.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(iv)
                .setPositiveButton("关闭", null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /**
     * 先弹确认（耗时 + 隐私脱敏说明），确认后带百分比进度条收集，
     * 完成后弹三选项（取消 / 保存本地 / 发送至作者邮箱）。
     */
    /**
     * AI 排查端口：用法弹窗。
     *
     * <p>为什么做成弹窗而不是直接开端口：端口的 key 用户不知道的话
     * 外部也连不上，所以第一步永远是把「地址 + key」展示/复制给用户；
     * 顺手把用法写给不会用的人 —— 或者整条地址丢给 AI，让 AI 自己连。
     */
    private void showDiagPort() {
        DiagServer.ensure(this); // 没起就先起（内核活着的话）
        int port = DiagServer.runningPort();
        String key = Prefs.webKey(this);
        String host = Prefs.lan(this) ? lanIp() : "127.0.0.1";
        String base = port > 0
                ? "http://" + host + ":" + port
                : "http://127.0.0.1:" + DiagServer.PORT;
        String addr = base + "/pack?key=" + key;

        LinearLayout box = ui.column();
        int pad = ui.dp(18);
        box.setPadding(pad, ui.dp(6), pad, ui.dp(4));

        TextView t1 = ui.text("这是给 AI 软件看的现场端口：手机上的 AI 助手"
                        + "（或电脑上的 AI）连上来，就能自己读 magpie 的日志、"
                        + "崩溃记录、插件状态，不用你截图描述。",
                13, M3.ON_SURFACE, false);
        t1.setLineSpacing(ui.dp(2), 1f);
        t1.setLayoutParams(ui.matchWrap(8));
        box.addView(t1);

        TextView t2 = ui.text("会用的（AI 软件支持自定义地址）：", 13,
                M3.ON_SURFACE, true);
        t2.setPadding(0, ui.dp(10), 0, ui.dp(2));
        box.addView(t2);

        TextView steps = ui.text("1. 在 AI 软件里让它「访问下面这个地址」\n"
                        + "2. 把地址发给它，AI 自己 curl 读取\n"
                        + "3. 然后 AI 会直接告诉你 magpie 哪里出了问题",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        steps.setLineSpacing(ui.dp(2), 1f);
        steps.setLayoutParams(ui.matchWrap(8));
        box.addView(steps);

        TextView t3 = ui.text("不会用的（最简单）：", 13, M3.ON_SURFACE, true);
        t3.setPadding(0, ui.dp(10), 0, ui.dp(2));
        box.addView(t3);

        TextView t4 = ui.text("点下面的「复制地址」，把整条粘给 AI 软件对话里，"
                        + "加一句「帮我排查 magpie 的问题」就行。剩下的 AI 自己来。",
                12.5f, M3.ON_SURFACE_VARIANT, false);
        t4.setLineSpacing(ui.dp(2), 1f);
        t4.setLayoutParams(ui.matchWrap(10));
        box.addView(t4);

        // 地址卡片
        TextView addrBox = new TextView(this);
        addrBox.setText(addr);
        addrBox.setTextSize(11);
        addrBox.setTypeface(android.graphics.Typeface.MONOSPACE);
        addrBox.setTextColor(ui.col(M3.ON_SURFACE));
        GradientDrawable ab = ui.round(ui.col(M3.SURFACE_CONTAINER_HIGHEST), 12);
        addrBox.setBackground(ab);
        addrBox.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        addrBox.setTextIsSelectable(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(0, ui.dp(10), 0, 0);
        box.addView(addrBox, alp);

        // 局域网提示
        if (Prefs.lan(this)) {
            TextView lan = ui.text("已开放局域网：同一 Wi-Fi 下的设备都能连这个端口"
                            + "（地址里的 key 就是密码，别发到公开群）。",
                    11.5f, M3.ON_SURFACE_VARIANT, false);
            lan.setPadding(0, ui.dp(8), 0, 0);
            box.addView(lan);
        }

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("AI 排查端口")
                .setView(box)
                .setPositiveButton("复制地址", (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("magpie", addr));
                    }
                    toast("已复制，粘给 AI 软件就能开始排查");
                })
                .setNeutralButton("AI排查复制", (d, w) -> {
                    // 一段现成的排查话术 + 排查链接：粘给 AI 后它自己知道该干嘛
                    String text = "我是 magpie（AI 网关）Android 版的用户，手机上开了一个诊断端口。"
                            + "请访问下面这个地址读取现场信息（日志、崩溃记录、插件状态），"
                            + "然后直接告诉我 magpie 有什么问题、怎么解决：\n" + addr;
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("magpie", text));
                    }
                    toast("已复制排查文案，直接粘给 AI 即可");
                })
                .setNegativeButton("关闭", null)
                .setCancelable(true)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 局域网里本机的 IPv4（开「允许局域网访问」时弹窗地址用它）。 */
    private String lanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.util.Enumeration<java.net.InetAddress> addrs =
                        nis.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private void confirmAndDiagnose() {
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("错误诊断")
                .setMessage("将收集一份完整的现场信息，用于定位问题：\n\n"
                        + "• 网关日志、崩溃记录、进程与资源占用\n"
                        + "• 插件清单与每个插件的加载错误\n"
                        + "• 供应商/额度/路由组配置（不含密钥）\n"
                        + "• 系统口径流量、VPN 状态、外部连接\n"
                        + "• 数据目录结构与各文件大小\n\n"
                        + "诊断可能耗时较久（几十秒）。\n"
                        + "包里的密钥、Token、密码、账号都会自动脱敏或打码，"
                        + "但其余信息仍是你的真实环境数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始诊断", (d, w) -> runDiagnose())
                .setCancelable(true)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 带百分比进度条的诊断流程。 */
    private void runDiagnose() {
        // 自建对话框：标题 + 百分比大字 + 进度条 + 当前步骤
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("正在诊断…")
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .create();

        LinearLayout box = ui.column();
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(8), pad, ui.dp(4));

        final TextView pct = ui.text("0%", 30, M3.PRIMARY, true);
        box.addView(pct);

        final ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ui.col(M3.PRIMARY)));
        pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ui.col(M3.SURFACE_CONTAINER_HIGHEST)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8));
        plp.topMargin = ui.dp(8);
        box.addView(pb, plp);

        final TextView step = ui.text("准备中…", 12, M3.ON_SURFACE_VARIANT, false);
        step.setLayoutParams(ui.matchWrap(10));
        box.addView(step);

        dlg.setView(box);
        styleDialog(dlg);
        dlg.show();

        // 取消标志：用户点取消就不再往下走
        final boolean[] canceled = {false};
        dlg.setOnDismissListener(d -> canceled[0] = true);

        bg(() -> {
            try {
                final DiagnosticsPack.DiagResult r = DiagnosticsPack.export(this,
                        (percent, what) -> post(() -> {
                            if (canceled[0]) {
                                return;
                            }
                            pct.setText(percent + "%");
                            pb.setProgress(percent);
                            step.setText(what);
                        }));
                post(() -> {
                    if (canceled[0]) {
                        return;
                    }
                    dlg.dismiss();
                    showDiagDoneDialog(r);
                });
            } catch (Exception e) {
                final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> {
                    if (canceled[0]) {
                        return;
                    }
                    dlg.dismiss();
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("诊断失败")
                            .setMessage(why)
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    /**
     * 诊断包收集完后的三个选项。
     *
     * <p>保存本地：文件已在 Download（或应用目录），toast 提示位置。
     * 发送至作者邮箱：先确保文件已保存，再拉起系统邮箱
     * （ACTION_SENDTO mailto:，带主题不带附件——附件走邮箱 App 自带
     * 的添加流程；作者邮箱同时复制到剪贴板，用户粘一下就能发）。
     */
    private void showDiagDoneDialog(final DiagnosticsPack.DiagResult r) {
        final java.io.File f = r.file;
        // 完整路径直接显示（爸爸 2026-11-23 要求）：私有目录时用户能照着找
        String msg = f.getName() + "\n大小 " + NativeData.bytes(f.length())
                + "\n位置：" + r.location
                + "\n完整路径：\n" + r.absolutePath
                + "\n\n保存到本地，或发送给作者排查。";
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("诊断包已生成")
                .setMessage(msg)
                .setPositiveButton("发送至作者邮箱",
                        (d, w) -> sendDiagByEmail(f))
                .setNeutralButton("保存本地",
                        (d, w) -> toast("已保存：" + r.location))
                .setNegativeButton("取消", null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /**
     * 拉起系统邮箱：ACTION_SEND 直接带 zip 附件（FileProvider 授权），
     * 用户不用再手动添加。作者邮箱同时进剪贴板兜底。
     */
    private void sendDiagByEmail(final java.io.File f) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("magpie",
                    DiagnosticsPack.AUTHOR_EMAIL));
        }
        try {
            String ver = "?";
            try {
                ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {
            }
            android.net.Uri stream = ApkFileProvider.shareUri(this, f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_EMAIL, new String[]{DiagnosticsPack.AUTHOR_EMAIL});
            i.putExtra(Intent.EXTRA_SUBJECT, "Magpie for Android " + ver + " 诊断反馈");
            i.putExtra(Intent.EXTRA_TEXT, "问题描述：\n\n\n（附件为错误诊断包，"
                    + "含日志/插件状态/流量归属等现场信息，密钥已脱敏）");
            i.putExtra(Intent.EXTRA_STREAM, stream);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "发送诊断包"));
        } catch (Exception e) {
            // 没有 zip 分享能力的邮箱：退化成 mailto 纯文字，附件手动
            try {
                String ver = "?";
                try {
                    ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                Uri uri = Uri.parse("mailto:" + DiagnosticsPack.AUTHOR_EMAIL
                        + "?subject=" + java.net.URLEncoder.encode(
                                "Magpie for Android " + ver + " 诊断反馈", "UTF-8")
                        + "&body=" + java.net.URLEncoder.encode(
                                "问题描述：\n\n诊断包位置：" + f.getAbsolutePath(), "UTF-8"));
                startActivity(new Intent(Intent.ACTION_SENDTO, uri));
            } catch (Exception ignored) {
            }
            toast("邮箱不支持带附件，作者邮箱已复制：" + DiagnosticsPack.AUTHOR_EMAIL
                    + "\n诊断包位置：" + f.getAbsolutePath());
        }
    }

    private void ignoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                toast("系统没有这个设置页");
            }
        }
    }

    /**
     * 模块信息与自检：一眼看完整套状态。
     *
     * <p>模块版最容易出问题的就是「到底哪一环没通」：模块没装 / 装了没启动 /
     * 启动了但 App 读不到。这页把四个环节分别列出来，一眼定位。
     */
    private void showModuleInfo() {
        LinearLayout box = ui.column();
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(4), pad, 0);

        // ---- 壳自己 ----
        String shellVer = "?";
        int shellCode = 0;
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            shellVer = pi.versionName == null ? "?" : pi.versionName;
            shellCode = pi.versionCode;
        } catch (Exception ignored) {
        }
        box.addView(kv("壳版本", shellVer + " (code " + shellCode + ")"));
        box.addView(kv("包名", getPackageName()));

        // ---- 模块 ----
        boolean bridge = ModuleBridge.bridgeReady();
        String modVer = GatewayService.moduleVersion();
        box.addView(kv("模块版本", modVer.isEmpty() ? "读不到（模块没装？）" : modVer));
        box.addView(kv("模块目录", ModuleBridge.MODULE_DIR));
        box.addView(kv("只读桥", bridge
                ? "已挂载 " + ModuleBridge.BRIDGE_DIR
                : "未挂载（模块没装，或 service.sh 没跑过）"));

        // ---- 网关 ----
        boolean gw = GatewayService.portOpen("127.0.0.1", ModuleBridge.GATEWAY_PORT);
        boolean web = GatewayService.portOpen("127.0.0.1", ModuleBridge.WEB_PORT);
        box.addView(kv("网关 3425", gw ? "在答话" : "没在答话"));
        box.addView(kv("网页 3430", web ? "在监听" : "没在监听"));
        String pid = ModuleBridge.pid() > 0 ? String.valueOf(ModuleBridge.pid()) : "未知";
        box.addView(kv("内核 pid", pid));

        // ---- 提权 ----
        box.addView(kv("Shizuku", ShizukuShell.describe()));
        String blocker = ModuleBridge.writeBlocker();
        if (!blocker.isEmpty()) {
            TextView w = ui.text(blocker, 11.5f, M3.ON_SURFACE_VARIANT, false);
            w.setLayoutParams(ui.matchWrap(10));
            w.setLineSpacing(ui.dp(2), 1f);
            box.addView(w);
        }

        TextView note = ui.text("网关由 Magisk 模块的 service.sh 开机启动、watchdog.sh 每 10 秒守一次。\n"
                        + "App 被杀、划掉最近任务都不影响它；这里只是观众。",
                11.5f, M3.ON_SURFACE_VARIANT, false);
        note.setLayoutParams(ui.matchWrap(14));
        note.setLineSpacing(ui.dp(2), 1f);
        box.addView(note);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("模块信息与自检")
                .setView(sc)
                .setPositiveButton("重启内核", (d, w) -> {
                    GatewayService.restart(this);
                    toast("正在重启模块内核…");
                    gatewayReady = false;
                    pollGateway();
                })
                .setNeutralButton("刷新", (d, w) -> showModuleInfo())
                .setNegativeButton("关闭", null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 模块刷入 / 换内核的说明。 */
    private void showModuleHelp() {
        LinearLayout box = ui.column();
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(4), pad, 0);

        TextView t = ui.text(
                "内核不在 APK 里，在 Magisk 模块里。\n\n"
                        + "【换内核】\n"
                        + "1. 下载新版 magpie-cli-android-arm64\n"
                        + "2. 用新二进制替掉模块里的 magpie\n"
                        + "3. 改 module.prop 的 version / versionCode\n"
                        + "4. 重启手机，或在这里点「重启内核」\n\n"
                        + "【刷模块】\n"
                        + "Magisk → 模块 → 从存储安装 → 选 magpie-native zip → 重启。\n"
                        + "刷完先跑模块自检：\n"
                        + "  su -c \"sh /data/adb/modules/magpie_native/scripts/selfcheck.sh\"\n\n"
                        + "【回滚】\n"
                        + "Magisk → 模块 → magpie_native → 卸载，重刷旧 zip。\n"
                        + "数据在模块的 data/ 里，卸载会一起删；先备份：\n"
                        + "  cp -a /data/adb/modules/magpie_native/data /sdcard/magpie-data-backup",
                12f, M3.ON_SURFACE, false);
        t.setLineSpacing(ui.dp(3), 1f);
        t.setLayoutParams(ui.matchWrap(0));
        box.addView(t);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("模块刷入与换内核")
                .setView(sc)
                .setPositiveButton("知道了", null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 一行「键：值」，键固定宽度对齐。 */
    private View kv(String k, String v) {
        LinearLayout row = ui.row();
        row.setLayoutParams(ui.matchWrap(5));
        TextView kk = ui.text(k, 12f, M3.ON_SURFACE_VARIANT, false);
        kk.setWidth(ui.dp(86));
        row.addView(kk);
        TextView vv = ui.text(v == null || v.isEmpty() ? "—" : v,
                12f, M3.ON_SURFACE, false);
        vv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(vv);
        return row;
    }

    private void showAbout() {
        String ver = "?";
        int code = 0;
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            ver = pi.versionName == null ? "?" : pi.versionName;
            code = pi.versionCode;
        } catch (Exception ignored) {
        }

        LinearLayout box = ui.column();
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(4), pad, 0);

        TextView verView = ui.mono("Magpie for Android " + ver
                + " (code " + code + ")", 12, M3.ON_SURFACE_VARIANT);
        verView.setLayoutParams(ui.matchWrap(2));
        box.addView(verView);

        // ---- 核心：magpie 官方项目（可点）----
        box.addView(linkRow("magpie 官方项目", "github.com/yetone/magpie",
                "https://github.com/yetone/magpie"));

        // ---- 外壳：这个壳自己的仓库（用户要的那条）----
        box.addView(linkRow("本外壳源码", "github.com/yqing004-boop/magpie-android",
                "https://github.com/yqing004-boop/magpie-android"));

        // ---- 作者（酷安，可点）----
        box.addView(linkRow("APK 移植制作者", "酷安 @图图没耳朵",
                "https://www.coolapk.com/u/1642567"));

        // ---- 交流群（QQ 群，可点加入）----
        box.addView(linkRow("交流群", "QQ 群：magpie手机移植版（1053848850）",
                DiagnosticsPack.QQ_GROUP_URL));

        TextView lic = ui.text("magpie 核心：Copyright © 2026 yetone · MIT License",
                11.5f, M3.ON_SURFACE_VARIANT, false);
        lic.setLayoutParams(ui.matchWrap(14));
        box.addView(lic);

        TextView note = ui.text("非官方移植，与 yetone 无关。\n"
                        + "四页界面由外壳直接调本机 magpie 的 HTTP 接口绘制；"
                        + "插件安装、隐私脱敏、同步备份等深度功能在「⋮ → 打开控制后台」的网页里。\n"
                        + "所有数据都来自本机 127.0.0.1，不出手机。",
                11.5f, M3.ON_SURFACE_VARIANT, false);
        note.setLayoutParams(ui.matchWrap(8));
        note.setLineSpacing(ui.dp(2), 1f);
        box.addView(note);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(sc)
                .setPositiveButton("知道了", null)
                .create();
        styleDialog(dlg);
        dlg.show();
    }

    /** 关于里的一行：左边标签，下面一行可点的地址。 */
    private View linkRow(String label, String shown, final String url) {
        LinearLayout row = ui.column();
        row.setLayoutParams(ui.matchWrap(10));

        TextView l = ui.text(label, 12, M3.ON_SURFACE_VARIANT, false);
        row.addView(l);

        TextView a = ui.text(shown, 13.5f, M3.PRIMARY, true);
        a.setPadding(0, ui.dp(2), 0, 0);
        a.setBackgroundResource(selectableItemBackground());
        a.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                // 没浏览器就退回复制，总不能点了没反应
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("magpie", url));
                }
                toast("已复制 " + url);
            }
        });
        row.addView(a);
        return row;
    }

    // ------------------------------------------------------------------
    // 外观
    // ------------------------------------------------------------------

    /**
     * 真沉浸：状态栏和底部导航条都透明，页面铺满整个屏幕。
     *
     * <p>关键是三个 flag 缺一不可：
     * <ul>
     *   <li>{@code LAYOUT_STABLE} —— 布局不随后续系统栏变化跳动</li>
     *   <li>{@code LAYOUT_FULLSCREEN} —— 内容铺到状态栏下面</li>
     *   <li>{@code LAYOUT_HIDE_NAVIGATION} —— <b>内容铺到导航条下面</b>；
     *       少了这一条，底部会留一条系统栏高度的空白，就是那条「白边」</li>
     * </ul>
     * 再配 {@code LIGHT_*} 让系统栏图标在浅色背景上变深。
     */
    private void setupImmersive() {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 显式关掉导航条的对比度遮罩，不然系统会在
            // 透明导航条底下垫一层半透明的深色/白色，白边就是这么来的
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 手势导航条（小白条）模式下也让内容铺到它下面
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        int surface = ui.col(M3.SURFACE);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        boolean light = !MainActivity.darkColor(surface);
        if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (light && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(flags);
        // Android 11+ 还有一层「系统栏插件不出让空间」的开关，手势导航条下
        // 只靠 flag 有时仍会留缝；显式声明内容自己铺到系统栏后面。
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        // 顶部让出状态栏；底部**不让** —— 内容一路铺到屏幕最底边，
        // 导航条透明浮在上面，这才是沉浸。底栏自己会抬到导航条之上。
        int sb = barHeight("status_bar_height");
        navHeight = barHeight("navigation_bar_height");
        sNavHeight = navHeight;
        View content = ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
        if (content != null) {
            content.setPadding(0, sb, 0, 0);
        }
    }

    private int barHeight(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ui.dp(24);
    }

    private void excludeFromGestures(View v) {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        v.post(() -> {
            List<android.graphics.Rect> rects = new ArrayList<>(1);
            rects.add(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()));
            v.setSystemGestureExclusionRects(rects);
        });
    }
}
