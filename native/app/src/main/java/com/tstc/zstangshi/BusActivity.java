package com.tstc.zstangshi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.bus.BusClient;
import com.tstc.zstangshi.bus.BusModels;
import com.tstc.zstangshi.bus.BusStore;
import com.tstc.zstangshi.ui.ConfirmDialog;
import com.tstc.zstangshi.ui.HeaderBar;
import com.tstc.zstangshi.ui.OptionSheet;
import com.tstc.zstangshi.ui.PageSlide;
import com.tstc.zstangshi.ui.PullRefreshLayout;
import com.tstc.zstangshi.ui.Sheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 实时公交（二级页）。
 *
 * 数据与协议移植自 {@code Trackr}（github.com/5151561/Trackr，MIT），见 {@link BusClient}；
 * 信息层级与交互参照车来了：首页「点站名进站点、点线路块进线路」，线路详情用
 * 横排可滑动的站线并标出「离我最近」的候车站。视觉仍沿用本 App 的既有规范
 * （{@link HeaderBar}、{@code card_bg} 卡片、品牌色与语义色、圆角卡片进出场），
 * 深浅色只跟随系统，不提供主题切换。
 *
 * 三种模式共用一个 Activity，靠 Intent extra 区分：
 *   · HOME —— 城市 / 搜索 / 收藏 / 附近站点
 *   · STOP —— 某个站点的全部线路与到站
 *   · LINE —— 线路详情（候车站预测 + 横排站线）
 */
public class BusActivity extends BaseActivity {

    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_PHYSICAL = "physical_id";
    private static final String EXTRA_NAMESAKE = "namesake_id";
    private static final String EXTRA_STATION = "station_id";
    private static final String EXTRA_LINE = "line_id";
    private static final String EXTRA_DIRECTION = "direction";
    private static final String EXTRA_ORDER = "order";
    private static final String EXTRA_CITY = "city_id";
    private static final String EXTRA_LAT = "lat";
    private static final String EXTRA_LNG = "lng";

    private static final String MODE_HOME = "home";
    private static final String MODE_STOP = "stop";
    private static final String MODE_LINE = "line";

    /** 从首页宫格进入。 */
    public static void open(Activity host) {
        host.startActivity(new Intent(host, BusActivity.class).putExtra(EXTRA_MODE, MODE_HOME));
    }

    private static void openStop(Activity host, BusModels.Station s, String cityId, double lat, double lng) {
        host.startActivity(new Intent(host, BusActivity.class)
                .putExtra(EXTRA_MODE, MODE_STOP)
                .putExtra(EXTRA_NAME, s.name)
                .putExtra(EXTRA_PHYSICAL, s.physicalId)
                .putExtra(EXTRA_NAMESAKE, s.namesakeId)
                .putExtra(EXTRA_STATION, s.id)
                .putExtra(EXTRA_CITY, cityId)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng));
    }

    private static void openLine(Activity host, String cityId, String lineId, String name,
                                 int direction, int order, String stopId, double lat, double lng) {
        host.startActivity(new Intent(host, BusActivity.class)
                .putExtra(EXTRA_MODE, MODE_LINE)
                .putExtra(EXTRA_LINE, lineId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_DIRECTION, direction)
                .putExtra(EXTRA_ORDER, order)
                .putExtra(EXTRA_STATION, stopId == null ? "" : stopId)
                .putExtra(EXTRA_CITY, cityId)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng));
    }

    private BusStore store;
    private PageSlide slide;
    private PullRefreshLayout pull;
    private LinearLayout body;
    private EditText searchBox;

    /**
     * 到站文案的即时更新器。补预测是一条条回来的，只换那一行的文字就行，
     * 整页重画会把用户正看着的位置和展开状态全打乱。
     */
    private final java.util.Map<BusModels.Arrival, Runnable> etaBinders =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** 线路详情当前这份数据 + 当前选中的候车站（原地切换用）。 */
    private BusModels.LineDetail detail;
    private int selectedOrder = -1;
    /** 候车站的 sId：虚拟站组共用 order=999，只按站序认站会一次高亮四五个站。 */
    private String selectedStopId = "";
    private ScrollView pageScroll;
    private HorizontalScrollView stripScroll;

    /** 实时位置定时刷新：20 秒一轮，静默更新，不动下拉动画。 */
    private static final long AUTO_MS = 20000;
    private final android.os.Handler ticker =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean fetching;
    private long lastTouchAt;
    /** 页面建起来的时刻。onResume 里补刷时要避开 onCreate 那一次请求。 */
    private long openedAt = System.currentTimeMillis();

    /**
     * 当前真正画在屏幕上的那些到站行。首页 / 站点详情每 20 秒只重算这一批，
     * 数字不会停在刚进页面时的老值，也不至于一次轰几十个请求被上游掐掉。
     * 线路详情整页重拉，用不到它。
     */
    private final java.util.LinkedHashSet<BusModels.Arrival> shownEta =
            new java.util.LinkedHashSet<>();
    /** 上一轮刷到哪儿了；每轮接着往下轮，整屏都能在一两分钟内轮完。 */
    private int etaCursor;
    private long etaRefreshAt;
    /** 一轮最多重算几条（3 并发 + 每条间隔 70ms，12 条约 3~4 秒）。 */
    private static final int ETA_PER_TICK = 12;
    /** 刚进页面 / 刚铺完一屏时先整体算一遍，多给一些名额。 */
    private static final int ETA_PER_PRIME = 36;
    /** 一轮还没走完就不再起新一轮，免得自己把自己限流。 */
    private static final long ETA_GAP_MS = 8000;

    private final Runnable autoTick = this::autoStep;

    private void autoStep() {
        quietRefresh();
        ticker.postDelayed(autoTick, AUTO_MS);
    }

    private String mode = MODE_HOME;
    private String title = "实时公交";
    private String cityId = BusClient.DEFAULT_CITY_ID;
    private double lat, lng;

    private String stationName = "";
    private String physicalId = "";
    private String namesakeId = "";
    private String stationId = "";
    private String lineId = "";
    private String lineName = "";
    private int direction;
    private int targetOrder;

    /** 线路详情里的首站发车时刻表；换线路/换向就作废。 */
    private BusModels.Timetable tt;
    /** tt 属于哪条线（lineId#direction），对不上就不显示，免得串台。 */
    private String ttKey = "";
    private long ttAt;
    /** 时刻表卡片的占位容器：数据晚到也不会把整页重排一遍。 */
    private LinearLayout ttSlot;
    private boolean ttLoading;
    /** 时刻表 5 分钟内不重复拉：它变化慢，没必要跟着 20 秒轮询走。 */
    private static final long TT_TTL_MS = 5 * 60 * 1000;

    private boolean showingSearch = false;
    /** 正在拉附近站点；和「等定位」用两个标志，不然一边在等定位会把列表请求挡住。 */
    private boolean loading = false;
    /** 正在等一帧新定位。 */
    private boolean locating;
    /** 最近一次真的拿到定位的时刻：连着两次进入页面不重复要。 */
    private long lastFixAt;
    /** 最近一次发起定位的时刻：首页停留时每分钟自动对一次位置。 */
    private long lastLocateAt;
    /** 手动下拉刷新：位置没动也要重新查一遍附近站点。 */
    private boolean forceReload;
    /** 附近站点请求代号：换了位置会重开一轮，上一轮的响应回来直接丢掉。 */
    private int nearGen;
    /** 首页「附近站点」标题，等定位时挂一句提示。 */
    private TextView nearTitle;
    /** 顶栏的数据源按钮：切换后要跟着改字，不然一直显示旧数据源的名字。 */
    private TextView sourceBtn;

    /** 首页里被「展开全部」的站点（默认每条线路只露三条，和车来了一致）。 */
    private final Set<String> expanded = new HashSet<>();

    /**
     * 本站的站台顺序。服务端会把「本次请求的那个站台」排到 stationList 首位，
     * 直接用返回顺序渲染会导致序号错位（点了站台2，高亮却还在站台1）。
     */
    private final List<BusModels.Platform> stopPlatforms = new ArrayList<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new BusStore(this);
        // BusStore 构造时已把用户选过的数据源读回 BusClient（没选过默认车来了），
        // 城市编号跟源走，各存一份
        cityId = store.cityId();
        lat = store.lastLat();
        lng = store.lastLng();

        Intent it = getIntent();
        mode = it.getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_HOME;
        stationName = safe(it.getStringExtra(EXTRA_NAME));
        physicalId = safe(it.getStringExtra(EXTRA_PHYSICAL));
        namesakeId = safe(it.getStringExtra(EXTRA_NAMESAKE));
        stationId = safe(it.getStringExtra(EXTRA_STATION));
        lineId = safe(it.getStringExtra(EXTRA_LINE));
        lineName = safe(it.getStringExtra(EXTRA_NAME));
        direction = it.getIntExtra(EXTRA_DIRECTION, 0);
        targetOrder = it.getIntExtra(EXTRA_ORDER, 0);
        String c = it.getStringExtra(EXTRA_CITY);
        if (c != null && !c.isEmpty()) cityId = c;
        if (it.hasExtra(EXTRA_LAT)) lat = it.getDoubleExtra(EXTRA_LAT, lat);
        if (it.hasExtra(EXTRA_LNG)) lng = it.getDoubleExtra(EXTRA_LNG, lng);

        if (MODE_STOP.equals(mode)) {
            title = stationName.isEmpty() ? "站点" : stationName;
        } else if (MODE_LINE.equals(mode)) {
            title = displayLine(lineName);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageColor());
        root.addView(header());

        pull = new PullRefreshLayout(this);
        pull.setRefreshText("正在获取实时公交…");
        pull.setOnRefresh(this::reload);
        View scroller = pull;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(28));
        pull.scrollView().addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageScroll = pull.scrollView();
        pageScroll.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN
                    || a == android.view.MotionEvent.ACTION_MOVE) {
                lastTouchAt = System.currentTimeMillis();
            }
            return false;
        });
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applyInsets(root);
        slide = new PageSlide(this, pageColor());
        slide.enter();
        slide.attachBack();

        if (MODE_HOME.equals(mode)) {
            // 先用手里的旧坐标铺一屏（不至于白屏），紧接着就要一帧「此刻的位置」：
            // 旧坐标可能是几小时前在别处留下的，不重新定位就会一直显示那个地方的站点。
            renderHome();
            ensureLocation(true);
        } else if (MODE_STOP.equals(mode)) {
            loadStop();
        } else {
            loadLine();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ──────────────────────────── 骨架 ────────────────────────────

    private LinearLayout header() {
        LinearLayout h = HeaderBar.create(this, title, v -> closePage());
        if (MODE_HOME.equals(mode)) {
            // 三角用几何绘制、与文字垂直居中；sourceBtn 只是其中的文字部分
            LinearLayout srcBox = HeaderBar.actionWithCaret(this, BusClient.sourceName(),
                    v -> pickSource());
            sourceBtn = (TextView) srcBox.getChildAt(0);
            h.addView(srcBox);
            h.addView(HeaderBar.action(this, "刷新", v -> reload()));
        } else if (MODE_LINE.equals(mode)) {
            boolean fav = store.isFavorite(cityId, lineId);
            h.addView(HeaderBar.action(this, fav ? "已收藏" : "收藏", v -> toggleFavorite((TextView) v)));
        }
        return h;
    }

    private void closePage() {
        if (slide != null) slide.finish();
        else finish();
    }

    private void applyInsets(View root) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        } else {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            root.setPadding(0, id > 0 ? getResources().getDimensionPixelSize(id) : 0, 0, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(autoTick);
        ticker.postDelayed(autoTick, AUTO_MS);
        ticker.post(this::onEnter);
    }

    /**
     * 每次回到这一页都要做一次刷新，但两层页面的口径不同：
     *
     * <ul>
     *   <li><b>首页</b>：位置和公交信息都要新 —— 人可能已经从宿舍走到校门口了，
     *       只刷到站时间会算出一堆「离你 900 米」的假距离。所以重新要一次定位，
     *       位置真的变了就整套重铺（顺序、距离都跟着变）。</li>
     *   <li><b>站点 / 线路详情</b>：位置没有意义（这一站、这一条线是固定的），
     *       只把到站时间和车辆位置重算一遍。</li>
     * </ul>
     */
    private void onEnter() {
        if (!MODE_HOME.equals(mode)) {
            quietRefresh();
            return;
        }
        if (showingSearch) return;              // 搜索页是静态列表，不用刷

        // 先要位置。以前这里会先补一轮到站（最多 36 条预测），把链路堵在后面，
        // 定位和 nearby 都得排队；位置没变时 keepList 那条路会补到站，变了则
        // renderNearby 会补，不用在这儿抢跑。
        ensureLocation(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(autoTick);
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacks(autoTick);
        super.onDestroy();
    }

    /**
     * 后台静默刷新：只更新到站/车辆位置，不显示下拉动画、不重置候车站和滚动位置。
     * 用户正在滑动或刚点过就先让一让，免得内容跳。
     */
    private void quietRefresh() {
        if (isFinishing() || isDestroyed() || fetching) return;
        // 刚进页面时 onCreate 已经在拉了，别在它落地前再补一次
        if (System.currentTimeMillis() - openedAt < 3000) return;
        if (System.currentTimeMillis() - lastTouchAt < 2500) return;
        if (MODE_LINE.equals(mode)) {
            if (detail != null) fetchLine(true, true);
        } else if (MODE_STOP.equals(mode)) {
            // 站点详情：整站重拉一次（上游会把每条线的到站一起重算），
            // 顺带把补不到的几条再补一遍
            if (!stopPlatforms.isEmpty() || !shownEta.isEmpty()) loadStop(true);
        } else if (MODE_HOME.equals(mode)) {
            // 搜索页是静态结果，别在这里重铺列表，也别把用户从搜索里踢回去
            if (showingSearch) return;
            // 首页：只重算屏幕上这一批到站，不重铺站点列表，滚动位置和展开状态都不动
            refreshShownEta(ETA_PER_TICK);
            // 人可能就在页面停留期间走了一段：每分钟对一次位置，真换了地方再整套重铺
            if (System.currentTimeMillis() - lastLocateAt > RELOCATE_EVERY_MS) {
                ensureLocation(true);
            }
        }
    }

    /**
     * 刚铺好一屏站点：立刻把这一屏的到站整体算一遍，不等到 20 秒后的那一轮。
     * 进页面、下拉刷新、往下翻出更远的站点都走这里。
     */
    private void primeShownEta() {
        primeShownEtaFrom(0);
    }

    /** 从第 from 行开始算（往下翻出新站点时，从新的那几条算起）。 */
    private void primeShownEtaFrom(int from) {
        etaRefreshAt = 0;
        etaCursor = Math.max(0, from);
        refreshShownEta(ETA_PER_PRIME);
    }

    /**
     * 首页 / 站点详情的静默刷新：把当前显示着的到站行按线路详情同一个口径重算。
     *
     * <p>每轮只算 limit 条、从上次停下的地方接着轮，一条回来就换一行文字，
     * 不重画页面。nearSts 给的那点站数只能当占位，真正的分钟数以这里为准 ——
     * 不然首页写「已到站 / 17 站外」，点进线路详情却是「1 分钟」。
     */
    private void refreshShownEta(int limit) {
        if (shownEta.isEmpty()) return;
        if (lat <= 0 || lng <= 0) {
            // 没定位就算不了预测，把「…」收掉，别让它一直转
            for (BusModels.Arrival a : shownEta) a.pending = false;
            for (BusModels.Arrival a : shownEta) {
                Runnable r = etaBinders.get(a);
                if (r != null) r.run();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - etaRefreshAt < ETA_GAP_MS) return;
        etaRefreshAt = now;

        List<BusModels.Arrival> all = new ArrayList<>(shownEta);
        int n = all.size();
        int take = Math.min(limit, n);
        int start = etaCursor % n;
        List<BusModels.Arrival> batch = new ArrayList<>(take);
        for (int i = 0; i < take; i++) batch.add(all.get((start + i) % n));
        etaCursor = (start + take) % n;
        BusClient.refreshEta(cityId, batch, lat, lng, take, true, this::onEtaFilled);
        // 一点数据都没有的几条先显示「…」——比先写「暂无实时」再跳成「6分钟」诚实
        for (BusModels.Arrival a : batch) {
            if (!a.pending) continue;
            Runnable r = etaBinders.get(a);
            if (r != null) r.run();
        }
    }

    private void reload() {
        if (MODE_HOME.equals(mode)) {
            // 搜索页下拉：按当前关键词重搜一遍，不把人踢回首页
            if (showingSearch) {
                if (!searchKey.isEmpty()) doSearch(searchKey);
                else if (pull != null) pull.setRefreshing(false);
                return;
            }
            if (searchBox != null) searchBox.setText("");
            // 首页的下拉刷新是「换个位置重新看」：先要一次新定位，拿到新坐标后
            // useLocation → renderHome 会按新位置把附近站点整套重铺（距离、顺序都变）。
            // 所以这里不清 list，等新坐标回来一次到位，避免先闪一屏旧位置的站点。
            nearDone = false;
            nearRing = 0;
            // 手动下拉是「重新查一遍」：就算位置没动也要重铺列表，不能只刷到站
            forceReload = true;
            if (lat <= 0 || lng <= 0) renderHome();
            ensureLocation(true);
        } else if (MODE_STOP.equals(mode)) {
            loadStop(true);
        } else {
            // 线路详情的刷新只换实时位置：不动已选的候车站、不重排站表、不回顶部
            fetchLine(true, true);
        }
    }

    // ──────────────────────────── 首页 ────────────────────────────

    private void renderHome() {
        renderHomeContent();
        if (lat > 0 && lng > 0) {
            // 重铺意味着位置可能变了：作废还在飞的上一轮请求，
            // 否则旧位置的响应晚一步回来，会把刚铺好的新列表又盖回旧站点
            nearGen++;
            loading = false;
            body.addView(loadingCard());
            loadNearby();
        } else {
            body.addView(infoCard("还没有定位", "点上方「重新定位」获取附近站点；也可以直接搜索线路"));
        }
    }

    /** 首页正文：搜索 + 城市 + 收藏 + 附近站点标题。 */
    private void renderHomeContent() {
        body.removeAllViews();
        body.addView(searchCard());
        body.addView(cityRow());
        body.addView(favoritesSection());
        nearTitle = sectionTitle("附近站点");
        body.addView(nearTitle);
        updateLocatingHint();
    }

    /** 正在等定位时，在「附近站点」后面挂一句提示：此刻列表还可能是上一次的位置。 */
    private void updateLocatingHint() {
        if (nearTitle == null) return;
        nearTitle.setText(locating ? "附近站点 · 正在定位…" : "附近站点");
    }

    private View searchCard() {
        LinearLayout card = card();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        searchBox = new EditText(this);
        searchBox.setBackgroundResource(R.drawable.input_bg);
        searchBox.setHint("搜索线路、站点");
        searchBox.setHintTextColor(getColor(R.color.text_tertiary));
        searchBox.setTextColor(getColor(R.color.text_primary));
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setPadding(dp(12), dp(9), dp(12), dp(9));
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(searchBox.getText().toString().trim());
                return true;
            }
            return false;
        });
        card.addView(searchBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(this);
        go.setText("搜索");
        go.setTextSize(13);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setTextColor(0xFFFFFFFF);
        go.setBackground(roundRect(getColor(R.color.brand), dp(10)));
        go.setPadding(dp(14), dp(9), dp(14), dp(9));
        go.setOnClickListener(v -> doSearch(searchBox.getText().toString().trim()));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(8);
        card.addView(go, glp);

        // 搜索页要给一条退回附近站点的路：不然后退键只能退出整个功能
        if (showingSearch) {
            TextView cancel = new TextView(this);
            cancel.setText("取消");
            cancel.setTextSize(13);
            cancel.setTextColor(getColor(R.color.text_secondary));
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(12), dp(9), dp(4), dp(9));
            cancel.setOnClickListener(v -> exitSearch());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(4);
            card.addView(cancel, clp);
        }
        return card;
    }

    /** 退出搜索，回到附近站点（不重新定位，位置没变）。 */
    private void exitSearch() {
        showingSearch = false;
        searchKey = "";
        searchAll.clear();
        searchShown = 0;
        searchLinesBox = null;
        searchFooter = null;
        if (searchBox != null) searchBox.setText("");
        renderHome();
    }

    private View cityRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(12), dp(6), dp(4));

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(9);
        dot.setTextColor(0xFF3FA37A);
        row.addView(dot);

        TextView city = new TextView(this);
        city.setText(store.cityName() + " · 实时公交");
        city.setTextSize(12.5f);
        city.setTextColor(getColor(R.color.text_secondary));
        city.setPadding(dp(6), 0, 0, 0);
        row.addView(city);

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView pick = new TextView(this);
        pick.setText("切换城市");
        pick.setTextSize(12.5f);
        pick.setTextColor(getColor(R.color.brand));
        pick.setPadding(dp(8), dp(4), dp(8), dp(4));
        pick.setOnClickListener(v -> pickCity());
        row.addView(pick);

        TextView reloc = new TextView(this);
        reloc.setText("重新定位");
        reloc.setTextSize(12.5f);
        reloc.setTextColor(getColor(R.color.text_tertiary));
        reloc.setPadding(dp(8), dp(4), dp(4), dp(4));
        reloc.setOnClickListener(v -> ensureLocation(true));
        row.addView(reloc);
        return row;
    }

    private View favoritesSection() {
        List<BusStore.Fav> favs = store.favoritesOf(cityId);
        if (favs.isEmpty()) return new LinearLayout(this);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(sectionTitle("收藏线路"));
        for (BusStore.Fav f : favs) {
            LinearLayout c = card();
            c.setPadding(dp(14), dp(13), dp(14), dp(13));
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setClickable(true);
            c.setBackground(rippleCard());
            c.setOnClickListener(v -> openLine(this, cityId, f.lineId, f.name, f.direction, 0, "", lat, lng));

            c.addView(badge(f.name));

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(displayLine(f.name));
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(getColor(R.color.text_primary));
            mid.addView(t);
            if (!f.terminus().isEmpty()) {
                TextView d = new TextView(this);
                d.setText(f.terminus());
                d.setTextSize(11.5f);
                d.setTextColor(getColor(R.color.text_tertiary));
                d.setPadding(0, dp(3), 0, 0);
                mid.addView(d);
            }
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = dp(10);
            c.addView(mid, mlp);

            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(20);
            arrow.setTextColor(getColor(R.color.text_tertiary));
            c.addView(arrow);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            c.setLayoutParams(lp);
            wrap.addView(c);
        }
        return wrap;
    }

    private void loadNearby() {
        if (loading) return;
        loading = true;
        final String cid = cityId;
        final double la = lat, ln = lng;
        // 换位置会重开一轮，上一轮晚到的响应不能拿来覆盖新位置的列表
        final int gen = ++nearGen;
        new Thread(() -> {
            List<BusModels.Station> list;
            try {
                list = BusClient.nearby(cid, la, ln, 8);
            } catch (Exception e) {
                final String error = friendly(e);
                runOnUiThread(() -> {
                    if (gen != nearGen) return;
                    loading = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (pull != null) pull.setRefreshing(false);
                    if (showingSearch) return;
                    renderNearby(null, error);
                });
                return;
            }
            // 第一屏只用 nearby 的结果，几百毫秒就能上屏；
            // 站点详情那批每个站一条请求、通常要一两秒，回来再补进已有卡片里。
            // 以前是等两批都齐了才画，换一次位置要多等两秒。
            final List<BusModels.Station> first = list;
            runOnUiThread(() -> {
                if (gen != nearGen) return;
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (pull != null) pull.setRefreshing(false);
                if (showingSearch) return;
                renderNearby(first, null);
            });
            // 附近接口只给最近两三站算实时，其余站补一次详情，避免一排「暂无车」
            final java.util.Map<String, BusModels.Station> details =
                    BusClient.stationDetails(cid, first, la, ln);
            if (details.isEmpty()) return;
            runOnUiThread(() -> {
                if (gen != nearGen) return;
                if (isFinishing() || isDestroyed()) return;
                if (showingSearch) return;
                boolean any = false;
                for (BusModels.Station s : nearList) {
                    BusModels.Station full = details.get(s.id);
                    if (full != null) {
                        BusClient.mergeDetail(s, full);
                        any = true;
                    }
                }
                if (!any) return;
                // 合并换了 Arrival 对象，旧的行绑定要一起换掉；重画之后必须补一次预测：
                // 详情里没实时数据的线路是带 pending 的，不补就一直挂着「…」，
                // 只能靠用户点「展开全部」或等 20 秒轮询。
                etaBinders.clear();
                shownEta.clear();
                etaCursor = 0;
                fillNearCards();
                primeArrivals(new ArrayList<>(shownEta));
            });
        }, "bus-nearby").start();
    }

    /** 附近站点列表：先给最近的一批，用户继续往上翻再按距离往外扩。 */
    private final List<BusModels.Station> nearList = new ArrayList<>();
    private LinearLayout nearBox;
    private TextView nearFooter;
    private int nearRing;
    private boolean nearDone;
    private boolean nearLoading;
    /** 连续几圈没探到新站；中间只要有一圈探到就清零。 */
    private int nearMiss;

    private void renderNearby(List<BusModels.Station> list, String error) {
        renderHomeContent();
        etaBinders.clear();
        shownEta.clear();
        etaCursor = 0;
        nearList.clear();
        nearRing = 0;
        nearDone = false;
        nearLoading = false;
        nearMiss = 0;

        nearBox = new LinearLayout(this);
        nearBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(nearBox);

        if (error != null) {
            body.addView(errorCard(error));
            return;
        }
        if (list == null || list.isEmpty()) {
            body.addView(infoCard("附近没有查到站点", "换个城市，或直接搜索线路"));
            return;
        }
        nearList.addAll(list);
        fillNearCards();

        nearFooter = footerHint("继续下滑，加载更远的站点");
        body.addView(nearFooter);
        attachScrollPager();
        // 这一屏的到站整体算一遍：nearSts 只给最近几站算实时，其余只有站数没有分钟，
        // 统一走线路详情那个接口，两处的数字才同一个口径
        primeShownEta();
    }

    private void fillNearCards() {
        nearBox.removeAllViews();
        for (BusModels.Station s : nearList) nearBox.addView(stationCard(s));
    }

    /** 补到一条预测：只重刷那一行，不整页重画。 */
    private void onEtaFilled(BusModels.Arrival a) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            Runnable r = etaBinders.get(a);
            if (r != null) r.run();
        });
    }

    private TextView footerHint(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(getColor(R.color.text_tertiary));
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(10), 0, dp(6));
        return v;
    }

    /**
     * 滚到接近底部：搜索页继续放线路，首页继续按距离往外扩站点。
     * 两处共用一个滚动监听 —— 换成各自 set 会互相覆盖。
     */
    private void attachScrollPager() {
        if (pageScroll == null) return;
        pageScroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            View child = ((ViewGroup) v).getChildAt(0);
            if (child == null) return;
            if (sy + v.getHeight() < child.getHeight() - dp(260)) return;
            if (showingSearch) loadMoreSearch();
            else loadMoreNear();
        });
    }

    private void loadMoreNear() {
        if (showingSearch) return;
        if (nearLoading || nearDone || nearList.isEmpty() || lat <= 0 || lng <= 0) return;
        nearLoading = true;
        nearRing++;
        if (nearFooter != null) nearFooter.setText("正在加载更远的站点…");

        final int ring = nearRing;
        final double la = lat, ln = lng;
        final String cid = cityId;
        final Set<String> have = new HashSet<>();
        for (BusModels.Station s : nearList) have.add(s.id);
        new Thread(() -> {
            List<BusModels.Station> more = new ArrayList<>();
            try {
                more = BusClient.fartherStations(cid, la, ln, ring, have);
            } catch (Exception ignored) {
            }
            final List<BusModels.Station> data = more;
            runOnUiThread(() -> {
                nearLoading = false;
                if (isFinishing() || isDestroyed()) return;
                int before = shownEta.size();
                int added = 0;
                for (BusModels.Station s : data) {
                    if (have.contains(s.id)) continue;
                    have.add(s.id);
                    // 按距离插进去，别让后翻出来的近站排到远站后头
                    int at = nearList.size();
                    for (int i = 0; i < nearList.size(); i++) {
                        if (s.distanceMeters < nearList.get(i).distanceMeters) {
                            at = i;
                            break;
                        }
                    }
                    nearList.add(at, s);
                    nearBox.addView(stationCard(s), at);
                    added++;
                }
                if (added == 0) {
                    // 某一圈探空了不一定是真没了：再往外一圈可能还有，连续两圈空才收尾
                    nearMiss++;
                    if (nearMiss >= 2 || nearRing >= 8) finishNear();
                    else if (nearFooter != null) {
                        nearFooter.setText("继续下滑，加载更远的站点");
                        // 这一圈空出来的位置继续往下探，用户不用再滑一次
                        pageScroll.post(this::loadMoreNear);
                    }
                } else {
                    nearMiss = 0;
                    // 新翻出来的这批也得算到站，从新增的那几行开始
                    primeShownEtaFrom(before);
                    if (nearRing >= 8) finishNear();
                    else if (nearFooter != null) nearFooter.setText("继续下滑，加载更远的站点");
                }
            });
        }, "bus-more").start();
    }

    private void finishNear() {
        nearDone = true;
        if (nearFooter != null) nearFooter.setText("附近能查到的站点就这些了");
    }

    /**
     * 附近站点卡，结构照车来了：
     * 顶部一整行是「站名 + 几个站台 + 距离」，点它进站点详情；
     * 下面每条线路是独立一行，点它直接进线路详情。
     */
    private View stationCard(BusModels.Station s) {
        LinearLayout c = card();
        c.setPadding(dp(14), 0, dp(14), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(13), 0, dp(11));
        head.setClickable(true);
        head.setOnClickListener(v -> openStop(this, s, cityId, lat, lng));

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_quick_bus);
        ic.setColorFilter(getColor(R.color.brand));
        ic.setBackground(roundRect(getColor(R.color.brand_soft), dp(9)));
        ic.setPadding(dp(6), dp(6), dp(6), dp(6));
        head.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text_primary));
        name.setPadding(dp(9), 0, 0, 0);
        head.addView(name);

        TextView meta = new TextView(this);
        meta.setTextSize(11.5f);
        meta.setTextColor(getColor(R.color.text_tertiary));
        StringBuilder m = new StringBuilder();
        if (s.platforms.size() > 1) m.append(" ").append(s.platforms.size()).append(" 个站台");
        if (!s.distanceLabel().isEmpty()) m.append(" ").append(s.distanceLabel());
        meta.setText(m.toString());
        head.addView(meta);

        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(19);
        arrow.setTextColor(getColor(R.color.text_tertiary));
        head.addView(arrow);
        c.addView(head);

        LinearLayout linesBox = new LinearLayout(this);
        linesBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(linesBox);
        fillStationLines(linesBox, s);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 站点卡里的线路列表。「展开全部」只重画这一块，不整页重排（否则会跳回顶部）。 */
    private void fillStationLines(LinearLayout box, BusModels.Station s) {
        box.removeAllViews();
        // 重铺之前先把这一站上一轮的行摘掉：不摘的话「展开/收起」会把同一批 Arrival
        // 一遍遍塞进 shownEta，收起后看不见的行还在被轮询。
        for (BusModels.Arrival a : s.arrivals) {
            etaBinders.remove(a);
            shownEta.remove(a);
        }
        if (s.arrivals.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("暂无实时数据");
            none.setTextSize(11.5f);
            none.setTextColor(getColor(R.color.text_tertiary));
            none.setPadding(dp(2), 0, 0, dp(12));
            box.addView(none);
            return;
        }
        boolean open = expanded.contains(s.id);
        int show = open ? s.arrivals.size() : Math.min(3, s.arrivals.size());
        List<BusModels.Arrival> visible = new ArrayList<>(show);
        for (int i = 0; i < show; i++) {
            BusModels.Arrival a = s.arrivals.get(i);
            visible.add(a);
            box.addView(divider());
            box.addView(lineBlock(a));
        }
        // 刚展开出来的行还挂着「…」（上游没给它们的 stnStates），得马上算一次，
        // 别等下一轮轮询——那时候用户早就盯着几个省略号在等了
        if (open) primeArrivals(visible);
        if (s.arrivals.size() > 3) {
            TextView more = new TextView(this);
            more.setText(open ? "收起" : "展开全部");
            more.setTextSize(12.5f);
            more.setTextColor(getColor(R.color.text_tertiary));
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(10), 0, dp(10));
            more.setClickable(true);
            more.setOnClickListener(v -> {
                if (open) expanded.remove(s.id);
                else expanded.add(s.id);
                fillStationLines(box, s);
            });
            box.addView(more);
        }
    }

    /**
     * 只给指定这几行算到站：展开更多线路、往下翻出新站点时用。
     * 和 {@link #refreshShownEta} 走同一个口径（{@link BusClient#refreshEta} 的 force 档），
     * 所以填出来的数字和点进线路详情一致。
     */
    private void primeArrivals(List<BusModels.Arrival> list) {
        if (list == null || list.isEmpty()) return;
        List<BusModels.Arrival> todo = new ArrayList<>();
        for (BusModels.Arrival a : list) {
            // 没有站序就算不出「还有几站」，别让「…」一直转
            if (a.orderInLine <= 0) {
                a.pending = false;
                continue;
            }
            // 已经有数的行交给定时刷新，这里只救那些空着的
            if (!a.pending && (a.arrived || a.minutes >= 0)) continue;
            todo.add(a);
        }
        if (todo.isEmpty() || lat <= 0 || lng <= 0) {
            for (BusModels.Arrival a : todo) a.pending = false;
            for (BusModels.Arrival a : list) {
                Runnable r = etaBinders.get(a);
                if (r != null) r.run();
            }
            return;
        }
        BusClient.refreshEta(cityId, todo, lat, lng, todo.size(), true, this::onEtaFilled);
        for (BusModels.Arrival a : todo) {
            if (!a.pending) continue;
            Runnable r = etaBinders.get(a);
            if (r != null) r.run();
        }
    }

    /** 一条线路：左边线路名 + 开往，右边「23分钟·14站」+「下辆…」。 */
    private View lineBlock(BusModels.Arrival a) {
        shownEta.add(a);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(11), dp(2), dp(11));
        row.setBackground(rippleRow());
        // 点哪个站的线路，就默认候车在那个站（a.orderInLine 是该站在该线的站序）；
        // 拿不到站序（=0）时 BusClient 会退回到「离我最近的那站」
        row.setOnClickListener(v -> openLine(this, cityId, a.lineId, a.lineName, a.direction,
                a.orderInLine, a.stopId, lat, lng));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(displayLine(a.lineName));
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text_primary));
        left.addView(name);

        TextView dest = new TextView(this);
        dest.setText("开往 " + (a.destination().isEmpty() ? "—" : a.destination()));
        dest.setTextSize(11.5f);
        dest.setTextColor(getColor(R.color.text_tertiary));
        dest.setPadding(0, dp(3), 0, 0);
        dest.setMaxLines(1);
        dest.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(dest);
        row.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextView eta = new TextView(this);
        eta.setTextSize(16);
        eta.setTypeface(Typeface.DEFAULT_BOLD);
        eta.setGravity(Gravity.END);
        right.addView(eta);

        // 「下辆」先占好位置再按需显示，补预测回来时只换字不插行，列表不会跳一下
        TextView next = new TextView(this);
        next.setTextSize(11);
        next.setTextColor(getColor(R.color.text_tertiary));
        next.setPadding(0, dp(3), 0, 0);
        next.setGravity(Gravity.END);
        right.addView(next);
        bindEta(a, eta, next);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(10);
        row.addView(right, rlp);
        return row;
    }

    /** 把一行的到站文案绑到 Arrival 上，补预测回来后直接改这几个 TextView。 */
    private void bindEta(BusModels.Arrival a, TextView eta, TextView next) {
        Runnable r = () -> {
            eta.setText(a.etaCompact());
            eta.setTextColor(etaColor(a));
            if (next != null) {
                String nx = a.nextLabel();
                next.setText(nx);
                next.setVisibility(nx.isEmpty() ? View.GONE : View.VISIBLE);
            }
        };
        r.run();
        etaBinders.put(a, r);
    }

    /** 到站时间的颜色：已到站 / 3 分钟内用暖色提醒，其余用品牌蓝。 */
    private int etaColor(BusModels.Arrival a) {
        if (a.arrived || (a.minutes >= 0 && a.minutes <= 3)) return 0xFFE8734A;
        if (a.minutes < 0 && a.stopsAway < 0) return getColor(R.color.text_tertiary);
        return getColor(R.color.brand);
    }

    // ──────────────────────────── 搜索 ────────────────────────────

    /** 一屏先给 12 条线路，滑到底再补 12 条。 */
    private static final int SEARCH_PAGE = 12;
    private String searchKey = "";
    /** 已拿到的线路（第一屏只是上游的前 10 条），按 lineId#direction 去重后追加 */
    private final List<BusModels.Line> searchAll = new ArrayList<>();
    private int searchShown;
    private boolean searchMoreLoading;
    /** 上游全量已经取过一次（不管有没有新东西），不再重复请求 */
    private boolean searchMoreDone;
    private LinearLayout searchLinesBox;
    private TextView searchFooter;

    private void doSearch(String key) {
        if (key.isEmpty()) return;
        showingSearch = true;
        searchKey = key;
        searchAll.clear();
        searchShown = 0;
        searchMoreLoading = false;
        searchMoreDone = false;
        searchLinesBox = null;
        searchFooter = null;
        shownEta.clear();
        etaBinders.clear();

        body.removeAllViews();
        body.addView(searchCard());
        if (searchBox != null) {
            searchBox.setText(key);
            searchBox.setSelection(searchBox.getText().length());
        }
        body.addView(sectionTitle("「" + key + "」的搜索结果"));
        body.addView(loadingCard());
        // 搜索页也有「滑到底继续加载」，和首页共用一套滚动判据
        attachScrollPager();

        final String kw = key;
        new Thread(() -> {
            BusModels.SearchHit hit = null;
            String err = null;
            try {
                hit = BusClient.search(cityId, kw, lat, lng);
            } catch (Exception e) {
                err = friendly(e);
            }
            final BusModels.SearchHit data = hit;
            final String error = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // 连按两次搜索：只认最后一次的关键词，别让先回来的旧结果覆盖新结果
                if (!showingSearch || !kw.equals(searchKey)) return;
                renderSearch(data, error);
            });
        }, "bus-search").start();
    }

    private void renderSearch(BusModels.SearchHit data, String error) {
        if (pull != null) pull.setRefreshing(false);
        body.removeAllViews();
        body.addView(searchCard());
        if (searchBox != null) {
            searchBox.setText(searchKey);
            searchBox.setSelection(searchBox.getText().length());
        }
        searchLinesBox = null;
        searchFooter = null;

        if (error != null) {
            body.addView(errorCard(error));
            return;
        }
        if (data == null || data.empty()) {
            body.addView(infoCard("没有找到相关站点或线路",
                    "站名要写成连着的词（如「大学公寓」），线路写「60」或「60路」都行"));
            return;
        }

        if (!data.stations.isEmpty()) {
            body.addView(sectionTitle("站点"));
            for (BusModels.Station s : data.stations) body.addView(searchStationRow(s));
            if (data.stationsTruncated()) {
                // 上游只回前 3 个站，剩下没有可翻的列表 —— 如实说明，别让人以为站点就这些
                body.addView(footerHint("共 " + data.stationCount + " 个站点命中，这里只列最接近的 "
                        + data.stations.size() + " 个 · 站名写全一些能找得更准"));
            }
        }

        if (!data.lines.isEmpty()) {
            String t = data.lineCount > 0 ? "线路 · 共 " + data.lineCount + " 条" : "线路";
            body.addView(sectionTitle(t));
            searchLinesBox = new LinearLayout(this);
            searchLinesBox.setOrientation(LinearLayout.VERTICAL);
            body.addView(searchLinesBox);
            searchAll.addAll(dedupe(data.lines));
            // 上游报的命中数已经全在这一屏里了，就不用再为「更多」多发一次请求
            searchMoreDone = !data.linesTruncated();
            appendSearchLines(SEARCH_PAGE);
            searchFooter = footerHint("");
            body.addView(searchFooter);
            updateSearchFooter();
            // 上游第一屏只给 10 条记录（合并方向后只剩 5 条线），不够一屏就立刻补全，
            // 别让人以为「就这么几条」
            if (searchShown < SEARCH_PAGE && !searchMoreDone) loadMoreSearch();
        }
    }

    /** 把还没铺出来的线路接着铺 count 条。 */
    private void appendSearchLines(int count) {
        if (searchLinesBox == null) return;
        int end = Math.min(searchAll.size(), searchShown + count);
        for (int i = searchShown; i < end; i++) {
            searchLinesBox.addView(searchRow(searchAll.get(i)));
        }
        searchShown = end;
    }

    private void updateSearchFooter() {
        if (searchFooter == null) return;
        if (searchMoreLoading) {
            searchFooter.setText("正在加载更多线路…");
        } else if (searchShown < searchAll.size()) {
            searchFooter.setText("继续下滑，加载更多线路");
        } else if (!searchMoreDone) {
            searchFooter.setText("继续下滑，加载更多线路");
        } else {
            searchFooter.setText("已显示全部 " + searchShown + " 条线路");
        }
    }

    /**
     * 搜索页滑到底：手上还有没铺完的先铺（不发请求），铺完了再按 {@code more=true}
     * 向上游要一次全量 —— 这个模式一次给全，之后都是本地翻页。
     */
    private void loadMoreSearch() {
        if (!showingSearch || searchLinesBox == null || searchMoreLoading) return;
        if (searchShown < searchAll.size()) {
            appendSearchLines(SEARCH_PAGE);
            updateSearchFooter();
            return;
        }
        if (searchMoreDone) return;

        searchMoreLoading = true;
        updateSearchFooter();
        final String kw = searchKey;
        new Thread(() -> {
            List<BusModels.Line> more = null;
            try {
                more = BusClient.searchMore(cityId, kw, lat, lng);
            } catch (Exception ignored) {
            }
            final List<BusModels.Line> got = more;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !showingSearch) return;
                if (!kw.equals(searchKey)) return;
                searchMoreLoading = false;
                searchMoreDone = true;
                if (got != null && !got.isEmpty()) searchAll.addAll(dedupe(got));
                appendSearchLines(SEARCH_PAGE);
                updateSearchFooter();
            });
        }, "bus-search-more").start();
    }

    /** 上游每条线的正反方向已在上层合并成一行，这里按 lineNo（没有就用名字）去重。 */
    private List<BusModels.Line> dedupe(List<BusModels.Line> in) {
        Set<String> seen = new HashSet<>();
        for (BusModels.Line l : searchAll) seen.add(lineKey(l));
        List<BusModels.Line> out = new ArrayList<>();
        for (BusModels.Line l : in) {
            if (seen.add(lineKey(l))) out.add(l);
        }
        return out;
    }

    private static String lineKey(BusModels.Line l) {
        return l.lineNo.isEmpty() ? l.name + "#" + l.lineId : l.lineNo;
    }

    private View searchStationRow(BusModels.Station s) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(rippleCard());
        c.setClickable(true);
        c.setOnClickListener(v -> openStop(this, s, cityId, lat, lng));

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_quick_bus);
        ic.setColorFilter(getColor(R.color.brand));
        ic.setBackground(roundRect(getColor(R.color.brand_soft), dp(9)));
        ic.setPadding(dp(6), dp(6), dp(6), dp(6));
        c.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(s.name);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColor(R.color.text_primary));
        mid.addView(t);
        TextView d = new TextView(this);
        d.setText(s.distanceLabel().isEmpty() ? "公交站点" : "公交站点 · 距你 " + s.distanceLabel());
        d.setTextSize(11.5f);
        d.setTextColor(getColor(R.color.text_tertiary));
        d.setPadding(0, dp(3), 0, 0);
        mid.addView(d);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(10);
        c.addView(mid, mlp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(getColor(R.color.text_tertiary));
        c.addView(arrow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private View searchRow(BusModels.Line l) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setClickable(true);
        c.setBackground(rippleCard());
        c.setOnClickListener(v -> openLine(this, cityId, l.lineId, l.name, l.direction, 0, "", lat, lng));

        c.addView(badge(l.name));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(displayLine(l.name));
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColor(R.color.text_primary));
        mid.addView(t);
        if (!l.terminusLabel().isEmpty()) {
            TextView d = new TextView(this);
            d.setText(l.terminusLabel());
            d.setTextSize(11.5f);
            d.setTextColor(getColor(R.color.text_tertiary));
            d.setPadding(0, dp(3), 0, 0);
            mid.addView(d);
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(10);
        c.addView(mid, mlp);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(getColor(R.color.text_tertiary));
        c.addView(arrow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    // ──────────────────────────── 站点详情 ────────────────────────────

    private void loadStop() {
        loadStop(false);
    }

    /** silent=true：不闪加载卡，只把到站时间换掉（定时刷新 / 下拉刷新都走这条）。 */
    private void loadStop(boolean silent) {
        if (fetching) return;
        fetching = true;
        final int keepY = silent && pageScroll != null ? pageScroll.getScrollY() : -1;
        if (!silent) {
            body.removeAllViews();
            body.addView(loadingCard());
        }
        new Thread(() -> {
            BusModels.Station s = null;
            String err = null;
            try {
                s = BusClient.stopDetail(cityId, physicalId, namesakeId, stationId, lat, lng);
            } catch (Exception e) {
                err = friendly(e);
            }
            final BusModels.Station data = s;
            final String error = err;
            runOnUiThread(() -> {
                fetching = false;
                if (isFinishing() || isDestroyed()) return;
                if (pull != null) pull.setRefreshing(false);
                body.removeAllViews();
                if (error != null) {
                    body.addView(errorCard(error));
                    return;
                }
                if (data == null || data.arrivals.isEmpty()) {
                    body.addView(infoCard("这个站点暂时没有实时数据", "可能已停用，或该线路还没接入实时调度"));
                    return;
                }
                mergePlatforms(data.platforms);
                if (stopPlatforms.size() > 1) body.addView(platformChips());
                body.addView(sectionTitle("途经 " + data.arrivals.size() + " 条线路"));
                etaBinders.clear();
                shownEta.clear();
                etaCursor = 0;
                for (BusModels.Arrival a : data.arrivals) {
                    body.addView(stopLineCard(a));
                }
                // 站点详情同样按线路详情那个口径算一遍，别和点进去之后的数字打架
                primeShownEta();
                if (keepY > 0 && pageScroll != null) {
                    pageScroll.post(() -> pageScroll.scrollTo(0, keepY));
                }
            });
        }, "bus-stop").start();
    }

    private void mergePlatforms(List<BusModels.Platform> incoming) {
        for (BusModels.Platform p : incoming) {
            boolean found = false;
            for (BusModels.Platform q : stopPlatforms) {
                if (q.id.equals(p.id)) {
                    found = true;
                    break;
                }
            }
            if (!found) stopPlatforms.add(p);
        }
    }

    /** 站台切换：同一站名下的多个物理站台各自有独立的到站数据。 */
    private View platformChips() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(10), dp(2), dp(2));

        for (int i = 0; i < stopPlatforms.size(); i++) {
            final BusModels.Platform p = stopPlatforms.get(i);
            boolean on = p.id.equals(physicalId);
            TextView chip = new TextView(this);
            chip.setText("站台 " + (i + 1));
            chip.setTextSize(12.5f);
            chip.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setGravity(Gravity.CENTER);
            chip.setTextColor(on ? 0xFFFFFFFF : getColor(R.color.text_secondary));
            chip.setBackground(roundRect(on ? getColor(R.color.brand)
                    : getColor(R.color.bg_sunken), dp(16)));
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            if (!on) {
                chip.setOnClickListener(v -> {
                    physicalId = p.id;
                    loadStop();
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            row.addView(chip, lp);
        }
        hs.addView(row);
        return hs;
    }

    private View stopLineCard(BusModels.Arrival a) {
        shownEta.add(a);
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setClickable(true);
        c.setBackground(rippleCard());
        // 从站点详情点进线路：候车站就是刚才看的这一站，而不是离我最近的那站
        c.setOnClickListener(v -> openLine(this, cityId, a.lineId, a.lineName, a.direction,
                a.orderInLine, a.stopId, lat, lng));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(a.lineName));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView dest = new TextView(this);
        dest.setText("开往 " + (a.destination().isEmpty() ? "—" : a.destination()));
        dest.setTextSize(13);
        dest.setTextColor(getColor(R.color.text_secondary));
        dest.setMaxLines(1);
        dest.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(dest);
        TextView next = new TextView(this);
        next.setTextSize(11);
        next.setTextColor(getColor(R.color.text_tertiary));
        next.setPadding(0, dp(3), 0, 0);
        mid.addView(next);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(10);
        top.addView(mid, mlp);

        TextView eta = new TextView(this);
        eta.setTextSize(15);
        eta.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.leftMargin = dp(8);
        top.addView(eta, elp);
        bindEta(a, eta, next);
        c.addView(top);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    // ──────────────────────────── 线路详情 ────────────────────────────

    private void loadLine() {
        fetchLine(false, false);
    }

    /**
     * 拉线路详情。silent=true 时不闪加载卡、保留当前滚动位置——
     * 换候车站、换向都在本页原地完成，不新开页面。
     */
    private void fetchLine(boolean silent, boolean keepScroll) {
        if (fetching) return;
        fetching = true;
        final int keepY = keepScroll && pageScroll != null ? pageScroll.getScrollY() : -1;
        final int keepX = keepScroll && stripScroll != null ? stripScroll.getScrollX() : -1;
        if (!silent) {
            body.removeAllViews();
            body.addView(loadingCard());
        }
        final String lid = lineId;
        final int order = targetOrder;
        new Thread(() -> {
            BusModels.LineDetail d = null;
            String err = null;
            try {
                d = BusClient.lineDetail(cityId, lid, order, stationId, lat, lng);
            } catch (Exception e) {
                err = friendly(e);
            }
            final BusModels.LineDetail data = d;
            final String error = err;
            runOnUiThread(() -> {
                fetching = false;
                if (isFinishing() || isDestroyed()) return;
                if (pull != null) pull.setRefreshing(false);
                if (error != null) {
                    body.removeAllViews();
                    body.addView(errorCard(error));
                    return;
                }
                if (data == null || data.stops.isEmpty()) {
                    body.removeAllViews();
                    body.addView(infoCard("这条线路没有站表数据", "可能是新开线路，或上游还没维护"));
                    return;
                }
                detail = data;
                selectedOrder = data.waitingOrder;
                selectedStopId = data.waitingStopId;
                renderLine(keepY, keepX);
                // 站表先上屏，时刻表晚一步补：它要多一次请求，别拖慢首屏
                loadTimetable(data);
            });
        }, "bus-line").start();
    }

    /**
     * 拉首站发车时刻表。5 分钟内的不重复拉（它变化慢），但每次都会重算「下一班」，
     * 因为那是拿本机时钟和班次表比的。
     */
    private void loadTimetable(BusModels.LineDetail d) {
        final String key = lineId + "#" + d.line.direction;
        if (key.equals(ttKey) && tt != null) {
            fillTimetableSlot();
            if (System.currentTimeMillis() - ttAt < TT_TTL_MS) return;
        }
        if (ttLoading) return;
        if (d.line.lineNo.isEmpty() && d.line.name.isEmpty()) return;
        ttLoading = true;
        final String cid = cityId;
        final String lid = lineId;
        final int dir = d.line.direction;
        final String no = d.line.lineNo;
        new Thread(() -> {
            BusModels.Timetable got = null;
            try {
                got = BusClient.timetable(cid, lid, dir, no);
            } catch (Exception ignored) {
            }
            final BusModels.Timetable data = got;
            runOnUiThread(() -> {
                ttLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (data == null || data.empty()) return;
                // 首末班优先用线路官方的：昨日实际的首班常常比公布的晚一点
                BusModels.LineDetail cur = detail;
                if (cur != null) {
                    if (!cur.line.firstTime.isEmpty()) data.firstDep = cur.line.firstTime;
                    if (!cur.line.lastTime.isEmpty()) data.lastDep = cur.line.lastTime;
                }
                tt = data;
                ttKey = key;
                ttAt = System.currentTimeMillis();
                fillTimetableSlot();
            });
        }, "bus-tt").start();
    }

    /** 往占位容器里填时刻表卡片（只动这一块，滚动位置不受影响）。 */
    private void fillTimetableSlot() {
        if (ttSlot == null) return;
        ttSlot.removeAllViews();
        if (tt == null || !ttKey.equals(lineId + "#" + (detail == null ? -1 : detail.line.direction))) return;
        ttSlot.addView(timetableCard(tt));
    }

    private void renderLine(int keepY, int keepX) {
        BusModels.LineDetail d = detail;
        if (d == null) return;
        body.removeAllViews();
        // 模块顺序学车来了：① 候车站 + 等车时间 → ② 线路图 → ③ 首站发车
        body.addView(lineHeadCard(d));
        body.addView(sectionTitle("全线 " + d.stops.size() + " 站 · 左右滑动查看"));
        // 站线只在首进页面时居中一次；之后不管换候车站还是例行刷新，
        // 一律恢复用户当前的滚动位置——用户能点到那站，说明它就在屏幕里，
        // 擅自居中只会产生跳动感
        body.addView(stopStrip(d, keepX <= 0 ? 1 : 0));
        // 时刻表占个位：数据回来只填这里，不重排整页
        ttSlot = new LinearLayout(this);
        ttSlot.setOrientation(LinearLayout.VERTICAL);
        body.addView(ttSlot);
        body.addView(sectionTitle("全部站点 · 点站名切换候车站"));
        body.addView(stationListCard(d));
        if (!ttKey.equals(lineId + "#" + d.line.direction)) tt = null;
        fillTimetableSlot();
        if (keepY > 0 && pageScroll != null) {
            pageScroll.post(() -> pageScroll.scrollTo(0, keepY));
        }
        if (keepX > 0 && stripScroll != null) {
            stripScroll.post(() -> stripScroll.scrollTo(keepX, 0));
        }
    }

    /** 点站名：没选中就切换候车站（本页原地换），已选中就问要不要去看站点详情。 */
    private void onStopTap(BusModels.Stop s) {
        if (isPicked(s)) {
            // 虚拟站没有物理站，跳过去也查不到东西，直接说明白
            if (s.physicalId.isEmpty()) {
                ConfirmDialog.alert(this, "这一站是附带小站",
                        "上游只给了站名，没有独立的站点详情；候车站已经切到它了。");
            } else {
                askOpenStop(s);
            }
            return;
        }
        targetOrder = s.order;
        selectedOrder = s.order;
        selectedStopId = s.id;
        stationId = s.id;
        fetchLine(true, true);
    }

    /** 当前候车站是不是这一站。站序可能重复（999 的虚拟站组），优先按 id 认。 */
    private boolean isPicked(BusModels.Stop s) {
        if (!selectedStopId.isEmpty()) return selectedStopId.equals(s.id);
        return s.order == selectedOrder;
    }

    private void askOpenStop(BusModels.Stop s) {
        ConfirmDialog.show(this,
                "跳转到「" + s.name + "」站点详情？",
                "可以看到途经这一站的全部线路，以及每趟车还有几站到。",
                "留在本页", "跳转",
                () -> openStop(this, s.toStation(), cityId, lat, lng));
    }

    /** 换向：反方向线路 id 上游已经给了，本页重新拉一次即可。 */
    private void swapDirection() {
        BusModels.LineDetail d = detail;
        if (d == null || !d.reversible()) return;
        lineId = d.reverseId;
        direction = 1 - direction;
        targetOrder = 0;
        selectedOrder = -1;
        selectedStopId = "";
        fetchLine(true, false);
    }

    /** 头部：候车站 + 还有几分钟 + 站数/距离，右侧换向。 */
    private View lineHeadCard(BusModels.LineDetail d) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView lab = new TextView(this);
        lab.setText("候车站");
        lab.setTextSize(11.5f);
        lab.setTextColor(getColor(R.color.text_tertiary));
        left.addView(lab);
        TextView st = new TextView(this);
        st.setText(d.waitingStopName.isEmpty() ? "—" : d.waitingStopName);
        st.setTextSize(15);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setTextColor(getColor(R.color.text_primary));
        st.setPadding(0, dp(3), 0, 0);
        left.addView(st);

        if (!d.line.endName.isEmpty()) {
            TextView dir = new TextView(this);
            dir.setText("开往 " + d.line.endName);
            dir.setTextSize(11.5f);
            dir.setTextColor(getColor(R.color.text_tertiary));
            dir.setPadding(0, dp(2), 0, 0);
            left.addView(dir);
        }
        top.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (d.reversible()) {
            TextView rev = new TextView(this);
            rev.setText("⇄ 换向");
            rev.setTextSize(12.5f);
            rev.setGravity(Gravity.CENTER);
            rev.setTextColor(getColor(R.color.brand));
            rev.setBackground(roundRect(getColor(R.color.brand_soft), dp(14)));
            rev.setPadding(dp(12), dp(7), dp(12), dp(7));
            rev.setOnClickListener(v -> swapDirection());
            top.addView(rev);
        }
        c.addView(top);

        // 大字：还有几分钟
        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.BOTTOM);
        big.setPadding(0, dp(12), 0, 0);

        TextView num = new TextView(this);
        if (d.etaArrived) {
            num.setText("已到站");
            num.setTextSize(26);
        } else if (d.etaMinutes >= 60) {
            // 长途线一小时开外，直接写「1小时44分」，别让大字撑成「104」
            num.setText(BusModels.minutesText(d.etaMinutes));
            num.setTextSize(30);
        } else if (d.etaMinutes > 0) {
            num.setText(String.valueOf(d.etaMinutes));
            num.setTextSize(34);
        } else if (d.etaMinutes == 0) {
            // 官方口径：小于 1 分钟叫「即将到站」，配「小于1分钟·X站·X米」
            num.setText("即将到站");
            num.setTextSize(26);
        } else if (!d.estArrival.isEmpty()) {
            // 没车在途：车来了的 preArrivalTime，官方估的到站时刻
            num.setText("预计" + d.estArrival + "到站");
            num.setTextSize(24);
        } else {
            num.setText(d.status.isEmpty() ? "暂无预测" : d.status);
            // 官方状态偶尔是长句（如「首班预计明日 06:00 发车」），字号跟着收一点
            num.setTextSize(d.status.length() > 8 ? 16 : 22);
        }
        num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextColor(d.etaMinutes >= 0 || d.etaArrived || !d.estArrival.isEmpty()
                ? getColor(R.color.brand) : getColor(R.color.text_secondary));
        big.addView(num);

        if (d.etaMinutes > 0 && d.etaMinutes < 60) {
            TextView unit = new TextView(this);
            unit.setText(" 分钟");
            unit.setTextSize(14);
            unit.setTextColor(getColor(R.color.text_secondary));
            unit.setPadding(0, 0, 0, dp(6));
            big.addView(unit);
        }
        c.addView(big);

        String detail = d.etaDetail();
        if (!detail.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(detail);
            sub.setTextSize(12.5f);
            sub.setTextColor(getColor(R.color.text_tertiary));
            sub.setPadding(0, dp(2), 0, 0);
            c.addView(sub);
        }

        TextView meta = new TextView(this);
        meta.setText(headMeta(d));
        meta.setTextSize(11.5f);
        meta.setTextColor(getColor(R.color.text_tertiary));
        meta.setPadding(0, dp(8), 0, 0);
        c.addView(meta);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private String headMeta(BusModels.LineDetail d) {
        List<String> bits = new ArrayList<>();
        if (!d.line.firstTime.isEmpty() || !d.line.lastTime.isEmpty()) {
            bits.add("首 " + d.line.firstTime + " · 末 " + d.line.lastTime);
        }
        if (!d.line.price.isEmpty()) {
            bits.add("票价 " + d.line.price);
        }
        if (d.busCount > 0) bits.add("在途 " + d.busCount + " 辆");
        return String.join(" · ", bits);
    }

    // ───────────────────── 首站发车时刻表 ─────────────────────

    /** 现在几点（今天的第几分钟）。 */
    private static int nowMinutes() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    /**
     * 时刻表卡片：下一班几点发、从哪儿发、多久一班，点「完整时刻表」看全天。
     * 放在候车站卡片下面，是因为「这站的车还有几分钟」和「首站下一班几点发」
     * 回答的是两个不同的问题——车还没发出来时，只有后者能看。
     */
    private View timetableCard(BusModels.Timetable t) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(13), dp(16), dp(13));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("首站发车");
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        head.addView(title);

        TextView from = new TextView(this);
        from.setText(" " + (t.startName.isEmpty() ? "" : t.startName));
        from.setTextSize(11.5f);
        from.setTextColor(getColor(R.color.text_tertiary));
        head.addView(from);

        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView more = new TextView(this);
        more.setText("完整时刻表 ›");
        more.setTextSize(12.5f);
        more.setTextColor(getColor(R.color.brand));
        more.setPadding(dp(8), dp(4), 0, dp(4));
        more.setClickable(true);
        more.setOnClickListener(v -> showTimetableSheet());
        head.addView(more);
        c.addView(head);

        LinearLayout big = new LinearLayout(this);
        big.setOrientation(LinearLayout.HORIZONTAL);
        big.setGravity(Gravity.BOTTOM);
        big.setPadding(0, dp(8), 0, 0);

        TextView num = new TextView(this);
        if (t.nextDep.isEmpty()) {
            num.setText("今日已收班");
            num.setTextSize(20);
            num.setTextColor(getColor(R.color.text_secondary));
        } else {
            num.setText(t.nextDep);
            num.setTextSize(26);
            num.setTextColor(getColor(R.color.brand));
        }
        num.setTypeface(Typeface.DEFAULT_BOLD);
        big.addView(num);

        if (!t.nextDep.isEmpty() && !t.nextFrom.isEmpty()) {
            TextView src = new TextView(this);
            String wait = "";
            int nowMin = nowMinutes();
            int dep = BusModels.minutesOf(t.nextDep);
            if (nowMin >= 0 && dep >= 0 && dep >= nowMin && dep - nowMin <= 120) {
                wait = "还有 " + (dep - nowMin) + " 分钟发车";
            }
            src.setText(wait.isEmpty() ? "  下一班 · " + t.nextFrom
                    : "  " + wait + " · " + t.nextFrom);
            src.setTextSize(11.5f);
            src.setTextColor(getColor(R.color.text_tertiary));
            src.setPadding(0, 0, 0, dp(4));
            big.addView(src);
        }
        c.addView(big);

        List<String> meta = new ArrayList<>();
        if (!t.firstDep.isEmpty() || !t.lastDep.isEmpty()) {
            meta.add("首班 " + t.firstDep + " · 末班 " + t.lastDep);
        }
        if (t.interval > 0) meta.add("约 " + t.interval + " 分钟一班");
        if (!meta.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(String.join(" · ", meta));
            sub.setTextSize(11.5f);
            sub.setTextColor(getColor(R.color.text_tertiary));
            sub.setPadding(0, dp(3), 0, 0);
            c.addView(sub);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    /** 完整时刻表：今日预测 + 昨日实际，从底部弹层里看。 */
    private void showTimetableSheet() {
        BusModels.Timetable t = tt;
        if (t == null) return;

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(2), dp(18), dp(20));
        sv.addView(box);

        TextView name = new TextView(this);
        BusModels.LineDetail d = detail;
        String head = d == null || d.line.name.isEmpty()
                ? displayLine(t.lineName) : displayLine(d.line.name);
        if (!t.startName.isEmpty()) head += " · 首站 " + t.startName;
        name.setText(head);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text_primary));
        box.addView(name);

        TextView sub = new TextView(this);
        List<String> bits = new ArrayList<>();
        if (!t.firstDep.isEmpty() || !t.lastDep.isEmpty()) {
            bits.add("首班 " + t.firstDep + " · 末班 " + t.lastDep);
        }
        if (t.interval > 0) bits.add("约 " + t.interval + " 分钟一班");
        sub.setText(String.join(" · ", bits));
        sub.setTextSize(11.5f);
        sub.setTextColor(getColor(R.color.text_tertiary));
        sub.setPadding(0, dp(4), 0, dp(2));
        box.addView(sub);

        if (!t.today.isEmpty()) {
            box.addView(ttSection("今日发车预测", "还没发的班次，按现在算的", t.today, t.nextDep));
        }
        if (!t.history.isEmpty()) {
            box.addView(ttSection("昨日实际班次", "昨天这一天的真实发车记录", t.history, ""));
        }
        if (!t.tip.isEmpty()) {
            TextView tip = new TextView(this);
            tip.setText(t.tip);
            tip.setTextSize(11);
            tip.setTextColor(getColor(R.color.text_tertiary));
            tip.setPadding(0, dp(14), 0, 0);
            box.addView(tip);
        }
        Sheet.show(this, sv, 0);
    }

    private View ttSection(String title, String hint, List<BusModels.TtSegment> segs, String highlight) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(16), 0, 0);

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(13.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(getColor(R.color.text_primary));
        box.addView(h);

        if (!hint.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(hint);
            t.setTextSize(11);
            t.setTextColor(getColor(R.color.text_tertiary));
            t.setPadding(0, dp(2), 0, 0);
            box.addView(t);
        }

        for (BusModels.TtSegment seg : segs) {
            String lab = seg.label();
            if (!lab.isEmpty()) {
                TextView s = new TextView(this);
                s.setText(lab);
                s.setTextSize(11.5f);
                s.setTextColor(getColor(R.color.text_secondary));
                s.setPadding(0, dp(12), 0, dp(6));
                box.addView(s);
            }
            box.addView(ttGrid(seg.times, highlight));
            if (!seg.tips.isEmpty()) {
                TextView tp = new TextView(this);
                tp.setText(seg.tips);
                tp.setTextSize(11);
                tp.setTextColor(getColor(R.color.text_tertiary));
                tp.setPadding(0, dp(4), 0, 0);
                box.addView(tp);
            }
        }
        return box;
    }

    /** 时间网格：一行四个，像时刻表那样对齐着看。 */
    private View ttGrid(List<String> times, String highlight) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < times.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row);
            }
            boolean hit = !highlight.isEmpty() && highlight.equals(times.get(i));
            TextView chip = new TextView(this);
            chip.setText(times.get(i));
            chip.setTextSize(12.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(hit ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setTextColor(hit ? 0xFFFFFFFF : getColor(R.color.text_primary));
            chip.setBackground(roundRect(hit ? getColor(R.color.brand)
                    : getColor(R.color.bg_sunken), dp(8)));
            chip.setPadding(0, dp(7), 0, dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(chip, lp);
        }
        // 最后一行不满四个，补空位，别让时间被拉开
        if (row != null) {
            int filled = times.size() % 4;
            if (filled != 0) {
                for (int i = filled; i < 4; i++) {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
        }
        return col;
    }

    /**
     * 横排站线（车来了那套）：整条线路横向铺开、可滑动，站名竖排，
     * 首进页面居中到「候车站」（没有就退回「离我最近」的站）并高亮，
     * 在途车辆标在它所在的那一站。之后重绘一律不自己滚动，滚动位置由外部恢复。
     */
    /** 站线里每一列的宽度，决定一屏能露出几个站。 */
    private static final int STRIP_COL = 54;

    /** centerMode：0 不动（滚动位置由外部恢复），1 瞬时居中（仅首进页面）。 */
    private View stopStrip(BusModels.LineDetail d, int centerMode) {
        LinearLayout wrap = card();
        wrap.setPadding(0, 0, 0, 0);

        final HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.TOP);
        strip.setClipChildren(false);
        strip.setPadding(dp(8), dp(12), dp(8), dp(14));

        final int n = d.stops.size();
        View pickedCol = null;
        View nearestCol = null;
        for (int i = 0; i < n; i++) {
            BusModels.Stop s = d.stops.get(i);
            View col = stripColumn(s, i == 0, i == n - 1);
            strip.addView(col, new LinearLayout.LayoutParams(dp(STRIP_COL),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (pickedCol == null && isPicked(s)) pickedCol = col;
            if (nearestCol == null && s.nearest) nearestCol = col;
        }
        final View anchor = pickedCol != null ? pickedCol : nearestCol;
        hs.addView(strip);
        wrap.addView(hs);
        stripScroll = hs;

        if (anchor != null && centerMode > 0) {
            // 等布局完成再居中，否则 getLeft() 还是 0
            hs.post(() -> {
                int x = anchor.getLeft() + anchor.getWidth() / 2 - hs.getWidth() / 2;
                hs.scrollTo(Math.max(0, x), 0);
            });
        }
        return wrap;
    }

    /**
     * 站线上的一列：车辆槽 → 横线 + 站点圆点 → 竖排站名 →（标签）。
     * 车辆槽和圆点行都是固定高度，所有列才能对齐成一条连续的线；
     * 站名一律排在线下面，和车来了那套一致。
     */
    private View stripColumn(BusModels.Stop s, boolean first, boolean last) {
        final boolean picked = isPicked(s);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClickable(true);
        col.setBackground(rippleRow());
        col.setOnClickListener(v -> onStopTap(s));

        FrameLayout slot = new FrameLayout(this);
        slot.setClipChildren(false);
        if (s.busRatio >= 0) {
            ImageView bus = new ImageView(this);
            bus.setImageResource(R.drawable.ic_quick_bus);
            bus.setColorFilter(0xFFE8734A);
            slot.addView(bus, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
            // 车在路上就按比例停在两站中间，只有真到站了才贴到圆点上
            bus.setTranslationX(-(float) ((1 - s.busRatio) * dp(STRIP_COL)));
        }
        col.addView(slot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(21)));

        FrameLayout rail = new FrameLayout(this);
        rail.setClipChildren(false);
        View line = new View(this);
        GradientDrawable lg = new GradientDrawable();
        lg.setColor(getColor(R.color.border_subtle));
        line.setBackground(lg);
        // 线条学车来了加粗一档（1.5dp 太细，横排看起来像条虚线）
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(5f)), Gravity.CENTER_VERTICAL);
        if (first) llp.leftMargin = dp(STRIP_COL / 2f);
        if (last) llp.rightMargin = dp(STRIP_COL / 2f);
        rail.addView(line, llp);

        if (picked) {
            // 车来了同款：候车站在线条上是一个蓝色圆底 + 白色右箭头，
            // 一眼能认出「车往这边开、你在这站等」
            FrameLayout badge = new FrameLayout(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(getColor(R.color.brand));
            badge.setBackground(bg);
            // 白箭头用几何路径画：字符「›」受字体基线影响，永远做不到真正居中
            badge.addView(new RailArrow(this, 0xFFFFFFFF), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            rail.addView(badge, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        } else {
            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            if (s.nearest) {
                // 家/公司那站：空心圈，永远保留「离我最近」
                dg.setColor(pageColor());
                dg.setStroke(Math.max(2, dp(2.2f)), getColor(R.color.brand));
            } else {
                dg.setColor(night() ? 0xFF39424F : 0xFFD7DCE5);
            }
            dot.setBackground(dg);
            int ds = s.nearest ? dp(13) : dp(9);
            rail.addView(dot, new FrameLayout.LayoutParams(ds, ds, Gravity.CENTER));
        }
        col.addView(rail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));

        TextView order = new TextView(this);
        order.setText(s.orderLabel);
        order.setTextSize(10.5f);
        order.setGravity(Gravity.CENTER);
        order.setTextColor(s.nearest || picked
                ? getColor(R.color.brand) : getColor(R.color.text_tertiary));
        order.setPadding(0, dp(4), 0, 0);
        col.addView(order);

        int nameColor = (s.nearest || picked) ? getColor(R.color.brand)
                : (s.busHere ? getColor(R.color.text_primary) : getColor(R.color.text_secondary));
        col.addView(stopName(s.name, nameColor, s.nearest || picked));

        // 两个标签是两件事，可以同时出现：
        // 「候车」= 你现在看的是到这一站的车（进来的那个站），
        // 「离我最近」= 定位算出来的最近站（不会因为你点了别的站就跑掉）
        if (picked) col.addView(tinyTag("候车", getColor(R.color.brand), getColor(R.color.brand_soft)));
        if (s.nearest) col.addView(tinyTag("离我最近", 0xFFE8734A, 0x1FE8734A));
        return col;
    }

    private TextView tinyTag(String text, int fg, int bg) {
        TextView tag = new TextView(this);
        tag.setText(text);
        tag.setTextSize(9);
        tag.setGravity(Gravity.CENTER);
        tag.setTextColor(fg);
        tag.setBackground(roundRect(bg, dp(4)));
        tag.setPadding(dp(3), dp(2), dp(3), dp(2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        tag.setLayoutParams(tlp);
        return tag;
    }

    /** 站线下面的全站清单：35 站的线路靠横滑找站不现实，竖着也能翻。 */
    private View stationListCard(BusModels.LineDetail d) {
        LinearLayout c = card();
        for (int i = 0; i < d.stops.size(); i++) {
            if (i > 0) {
                View div = divider();
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
                dlp.leftMargin = dp(16);
                c.addView(div, dlp);
            }
            c.addView(stationRow(d.stops.get(i)));
        }
        return c;
    }

    private View stationRow(BusModels.Stop s) {
        final boolean picked = isPicked(s);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rippleRow());
        row.setClickable(true);
        row.setOnClickListener(v -> onStopTap(s));
        row.setPadding(dp(16), dp(12), dp(14), dp(12));

        TextView order = new TextView(this);
        order.setText(s.orderLabel);
        order.setTextSize(11.5f);
        order.setTextColor(s.nearest || picked
                ? getColor(R.color.brand) : getColor(R.color.text_tertiary));
        order.setGravity(Gravity.CENTER);
        row.addView(order, new LinearLayout.LayoutParams(dp(26),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextSize(14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (s.nearest || picked) {
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(getColor(R.color.brand));
        } else {
            name.setTextColor(getColor(R.color.text_primary));
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(6);
        row.addView(name, nlp);

        // 三个标签说的不是一件事，能同时存在：
        // 「候车」= 正在看的是到这一站的车；「离我最近」= 定位算出来的最近站；
        // 「车辆在此」= 有车正停在这一站。挤不下时只留前两个。
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        if (picked) pills.addView(pill("候车", getColor(R.color.brand), getColor(R.color.brand_soft)));
        if (s.nearest) {
            if (pills.getChildCount() > 0) {
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gp.leftMargin = dp(5);
                pills.addView(pill("离我最近", 0xFFE8734A, 0x1FE8734A), gp);
            } else {
                pills.addView(pill("离我最近", 0xFFE8734A, 0x1FE8734A));
            }
        }
        if (s.busHere && pills.getChildCount() == 0) {
            pills.addView(pill("车辆在此", 0xFFE8734A, 0x1FE8734A));
        }
        if (pills.getChildCount() > 0) row.addView(pills);
        return row;
    }

    private TextView pill(String text, int fg, int bg) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(9.5f);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(fg);
        v.setBackground(roundRect(bg, dp(6)));
        v.setPadding(dp(6), dp(3), dp(6), dp(3));
        return v;
    }

    /** 一列最多放几个字，超过才折第二列。 */
    private static final int NAME_COL_MAX = 7;

    /**
     * 站线上的站名：一列竖排最多 7 字，再长就顺次折到第二列（先从上到下，再从左到右），
     * 不分半。整块宽度撑满一列再居中，才能和上面的圆点对齐。
     */
    private View stopName(String name, int color, boolean bold) {
        String s = name == null ? "" : name;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (s.length() <= NAME_COL_MAX) {
            row.addView(nameColumn(s, color, bold));
            return row;
        }
        row.addView(nameColumn(s.substring(0, NAME_COL_MAX), color, bold));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(1);
        row.addView(nameColumn(s.substring(NAME_COL_MAX), color, bold), gap);
        return row;
    }

    private TextView nameColumn(String seg, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(vertical(seg));
        v.setTextSize(seg.length() > 4 ? 11.5f : 12.5f);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    /** 站名竖排：逐字换行，和车来了站线的排法一致。 */
    private static String vertical(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private void toggleFavorite(TextView btn) {
        BusStore.Fav f = new BusStore.Fav();
        f.cityId = cityId;
        f.lineId = lineId;
        f.name = lineName;
        f.direction = direction;
        boolean now = store.toggle(f);
        btn.setText(now ? "已收藏" : "收藏");
    }

    // ──────────────────────────── 城市与定位 ────────────────────────────

    /**
     * 切数据源：两家的优劣用大白话写在选项里，选完记住，
     * 下次打开直接用他选的那个（持久化在 BusStore）。
     */
    private void pickSource() {
        String[] opts = new String[]{
                "车来了（默认）\n到站时间准，但少数城乡线路查不到",
                "掌上公交\n线路更全（含城乡公交），个别线路时间偏差大"
        };
        OptionSheet.show(this, "切换数据源", opts, BusClient.source(), idx -> {
            if (idx == BusClient.source()) return;
            switchSource(idx);
        });
    }

    private void switchSource(int src) {
        store.setSource(src);          // 记住偏好 + 切 BusClient + 作废缓存
        cityId = store.cityId();       // 城市编号换了一套，先落默认值
        // 顶栏按钮是 onCreate 时画好的，这里手动把字换掉（三角是画的，只换文字）
        if (sourceBtn != null) sourceBtn.setText(BusClient.sourceName());
        expanded.clear();
        showingSearch = false;
        if (searchBox != null) searchBox.setText("");
        stopPlatforms.clear();
        android.widget.Toast.makeText(this,
                "已切换为「" + BusClient.sourceName() + "」", android.widget.Toast.LENGTH_SHORT).show();
        // 两套城市编号对不上：按城市名在后台对一遍，对不上就保持默认城市
        new Thread(() -> {
            try {
                String want = normCity(store.cityName());
                for (BusModels.City c : BusClient.cities()) {
                    if (normCity(c.name).equals(want)) {
                        store.setCity(c.id, c.name);
                        cityId = c.id;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                reload();
            });
        }, "bus-switch-src").start();
    }

    /** 「唐山市」「唐山」「迁西县」都折成可比较的名字。 */
    private static String normCity(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("市") || v.endsWith("县")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private void pickCity() {
        android.widget.Toast.makeText(this, "正在加载城市列表…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<BusModels.City> cities = null;
            try {
                cities = BusClient.cities();
            } catch (Exception ignored) {
            }
            final List<BusModels.City> data = cities;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (data == null || data.isEmpty()) {
                    infoDialog("城市列表加载失败", "请检查网络后重试；也可以直接搜索线路，默认城市是唐山");
                    return;
                }
                String[] names = new String[data.size()];
                int sel = 0;
                for (int i = 0; i < data.size(); i++) {
                    names[i] = data.get(i).name;
                    if (data.get(i).id.equals(cityId)) sel = i;
                }
                OptionSheet.show(this, "选择城市", names, sel, idx -> {
                    BusModels.City c = data.get(idx);
                    store.setCity(c.id, c.name);
                    cityId = c.id;
                    showingSearch = false;
                    renderHome();
                });
            });
        }, "bus-cities").start();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 系统里那个「最后已知位置」多旧还愿意先拿来铺一屏（本地读取，不等定位）。 */
    private static final long PROVISIONAL_AGE_MS = 3 * 60 * 1000;
    /** 兜底用的旧点超过这个年龄就再要一帧：太旧的点会把附近站点停在几公里外。 */
    private static final long FALLBACK_MAX_AGE_MS = 60000;
    /** 主动定位最多等这么久，超时就用最后一次已知位置兜底。 */
    private static final long LOC_TIMEOUT_MS = 7000;
    /** 连着两次要定位的间隔：刚拿到就不要再要，避免重复发。 */
    private static final long FIX_DEDUPE_MS = 2000;
    /** 首页停留时每隔这么久自动对一次位置。 */
    private static final long RELOCATE_EVERY_MS = 60000;

    private void ensureLocation(boolean force) {
        if (!force && lat > 0 && lng > 0) {
            loadNearby();
            return;
        }
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, 7);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        long now = System.currentTimeMillis();
        // 已经有一帧在等，或者刚刚才拿到：不重复发
        if (locating || now - lastFixAt < FIX_DEDUPE_MS) {
            // 手动下拉时不能因为「刚定位过」就把这次刷新吞掉：坐标就是现在的，
            // 直接把附近站点重查一遍，不然下拉会一直转圈。
            if (!locating && forceReload && MODE_HOME.equals(mode)) renderHome();
            return;
        }

        Location last = lastKnown(lm);

        // 先把手里的点当下「现在的位置」立刻铺一屏：读本地缓存，毫秒级，不用等定位
        // 那一两秒。同时照样去要一帧新的，两个点差得远时新帧回来会静默重铺一次。
        // 这样最常见的情况（刚在别处被定位过 / 模拟定位刚切过）是「进页面就到位」。
        if (last != null && now - last.getTime() < PROVISIONAL_AGE_MS) {
            useLocation(last, true);
        }
        requestFix(lm, last, 0);
    }

    /**
     * 主动要一帧定位。
     *
     * <p>第一次超时后，如果手里那个已知点已经很旧（比如半小时前在宿舍留下的），
     * 直接拿它兜底等于「位置没刷新」，用户会一直看到别处的站点。所以再要一轮，
     * 两轮都拿不到才退回旧点，并且旧点也会触发重铺判断。
     */
    private void requestFix(final LocationManager lm, final Location fallback, final int attempt) {
        locating = true;
        lastLocateAt = System.currentTimeMillis();
        updateLocatingHint();
        final LocListener l = new LocListener(this, lm);
        try {
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                try {
                    lm.requestLocationUpdates(p, 0, 0, l);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            if (l.finished()) return;             // 已经收到帧了，useLocation 里收尾
            l.stop();
            locating = false;
            if (isFinishing() || isDestroyed()) return;

            // 等的这段时间里系统里可能刚落了一个新点，取较新的那个
            Location best = newer(fallback, lastKnown(lm));
            long age = best == null ? Long.MAX_VALUE
                    : System.currentTimeMillis() - best.getTime();
            if (attempt == 0 && age > FALLBACK_MAX_AGE_MS) {
                requestFix(lm, best, 1);          // 手里的点太旧，再要一帧
                return;
            }
            if (best != null) {
                useLocation(best);
            } else {
                updateLocatingHint();
                if (pull != null) pull.setRefreshing(false);
                if (MODE_HOME.equals(mode) && (lat <= 0 || lng <= 0)) renderHome();
            }
        }, LOC_TIMEOUT_MS);
    }

    private static Location newer(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.getTime() > a.getTime() ? b : a;
    }

    /** 系统里最近一次已知位置（GPS / 网络取时间较新的那条）。 */
    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private void useLocation(Location l) {
        useLocation(l, false);
    }

    /**
     * @param provisional 只是先拿系统缓存的那个点把页面铺出来，后面还有一帧新定位要进来；
     *                    这种不算「定位完成」，不能清掉 lastFixAt / locating 的去重状态。
     */
    private void useLocation(Location l, boolean provisional) {
        double prevLat = lat, prevLng = lng;
        lat = l.getLatitude();
        lng = l.getLongitude();
        store.setLastLocation(lat, lng);
        if (!provisional) {
            locating = false;
            lastFixAt = System.currentTimeMillis();
        }
        if (!MODE_HOME.equals(mode)) {
            updateLocatingHint();
            return;
        }

        showingSearch = false;
        // 回到首页要的是「当前位置的公交」：位置真变了就整套重铺（距离、顺序都变）；
        // 位置没动（大多数时候是这样）就保留已经滑出来的站点和滚动位置，只重算到站，
        // 但手动下拉刷新要的是「重新查一遍」，位置没动也得重铺。
        boolean moved = prevLat <= 0 || prevLng <= 0
                || BusClient.distanceMeters(prevLat, prevLng, lat, lng) > 50;
        // 第一屏还在路上（loading）就别再开一轮，等它回来直接铺，省一次请求
        boolean reloadList = forceReload || moved || (!loading && nearList.isEmpty());
        forceReload = false;
        if (reloadList) {
            renderHome();
        } else {
            updateLocatingHint();
            refreshShownEta(ETA_PER_PRIME);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != 7) return;
        if (hasLocationPermission()) {
            ensureLocation(true);
        } else if (MODE_HOME.equals(mode)) {
            renderHomeContent();
            body.addView(infoCard("没有定位权限", "「附近站点」需要位置权限；不授权也可以直接搜索线路"));
        }
    }

    /** 只收第一帧定位结果就注销，避免持续定位。 */
    private static final class LocListener implements LocationListener {
        private final BusActivity host;
        private final LocationManager lm;
        private boolean done;

        LocListener(BusActivity h, LocationManager m) {
            host = h;
            lm = m;
        }

        @Override
        public void onLocationChanged(Location location) {
            if (done) return;
            done = true;
            stop();
            host.useLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        void stop() {
            try {
                lm.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }

        /** 已经收到过一帧定位了吗（超时兜底用它判断要不要顶上去）。 */
        boolean finished() {
            return done;
        }
    }

    // ──────────────────────────── 通用小件 ────────────────────────────

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private android.graphics.drawable.Drawable rippleCard() {
        return new RippleDrawable(ColorStateList.valueOf(0x14000000),
                getDrawable(R.drawable.card_bg), null);
    }

    /** 行内涟漪：不盖卡片底色，只叠一层很淡的按下反馈。 */
    private android.graphics.drawable.Drawable rippleRow() {
        return new RippleDrawable(ColorStateList.valueOf(0x0F000000), null, null);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.border_subtle));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
        return v;
    }

    private TextView sectionTitle(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(getColor(R.color.text_tertiary));
        v.setPadding(dp(4), dp(16), 0, dp(8));
        return v;
    }

    /** 线路徽标：品牌浅底 + 品牌字。 */
    private View badge(String name) {
        TextView v = new TextView(this);
        v.setText(name == null ? "" : name);
        v.setTextSize(12.5f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(getColor(R.color.brand));
        v.setBackground(roundRect(getColor(R.color.brand_soft), dp(8)));
        v.setMinWidth(dp(36));
        v.setPadding(dp(7), dp(4), dp(7), dp(4));
        return v;
    }

    private android.graphics.drawable.Drawable roundRect(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private View loadingCard() {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(30), dp(16), dp(30));
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.brand)));
        c.addView(pb, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = new TextView(this);
        t.setText("正在获取实时数据…");
        t.setTextSize(12.5f);
        t.setTextColor(getColor(R.color.text_tertiary));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, 0);
        c.addView(t);
        return c;
    }

    private View infoCard(String title, String sub) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(28), dp(16), dp(28));
        TextView a = new TextView(this);
        a.setText(title);
        a.setTextSize(14);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        a.setTextColor(getColor(R.color.text_primary));
        a.setGravity(Gravity.CENTER);
        c.addView(a);
        TextView b = new TextView(this);
        b.setText(sub);
        b.setTextSize(12);
        b.setTextColor(getColor(R.color.text_tertiary));
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(8), 0, 0);
        c.addView(b);
        return c;
    }

    private View errorCard(String msg) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(16), dp(26), dp(16), dp(26));
        TextView a = new TextView(this);
        a.setText(msg);
        a.setTextSize(13);
        a.setTextColor(getColor(R.color.text_secondary));
        a.setGravity(Gravity.CENTER);
        c.addView(a);
        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextSize(13);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setTextColor(getColor(R.color.brand));
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(16), dp(10), dp(16), dp(6));
        retry.setOnClickListener(v -> reload());
        c.addView(retry);
        return c;
    }

    private void infoDialog(String title, String msg) {
        ConfirmDialog.alert(this, title, msg);
    }

    /** 网络异常翻译成人话，别把 IOException 甩给用户。 */
    private static String friendly(Exception e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        if (e instanceof java.net.SocketTimeoutException) return "网络超时，换个网络再试一次";
        if (e instanceof java.net.UnknownHostException) return "连不上服务器，检查一下网络";
        if (m.contains("HTTP 5")) return "服务器暂时不可用，稍后再试";
        if (m.contains("HTTP 4")) return "请求被拒绝，稍后再试";
        return "获取实时数据失败，稍后重试";
    }

    /** 17 → 17 路；B1 → B1（带字母的不加「路」）。 */
    private static String displayLine(String name) {
        if (name == null || name.isEmpty()) return "线路";
        boolean digits = true;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                digits = false;
                break;
            }
        }
        return digits ? name + " 路" : name;
    }

    /**
     * 站线候车站徽标里的白色右箭头（车来了同款）。
     * 用 Canvas 画路径而不是字符「›」：TextView 的字形受字体 ascent/descent
     * 左右，怎么调 padding 都差半个像素的居中；几何路径以 View 正中为原点，
     * 天然居中。静态嵌套类，不碰 d8 对内部类的那点脾气。
     */
    private static final class RailArrow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        RailArrow(Context c, int color) {
            super(c);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float s = Math.min(getWidth(), getHeight()) * 0.2f;
            path.reset();
            path.moveTo(cx - s * 0.55f, cy - s);
            path.lineTo(cx + s * 0.55f, cy);
            path.lineTo(cx - s * 0.55f, cy + s);
            canvas.drawPath(path, paint);
        }
    }
}
