package com.tstc.zstangshi

import android.Manifest
import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

import com.tstc.zstangshi.bus.BusClient
import com.tstc.zstangshi.bus.BusModels
import com.tstc.zstangshi.bus.BusStore
import com.tstc.zstangshi.bus.BusText
import com.tstc.zstangshi.bus.TransitCode
import com.tstc.zstangshi.ui.ConfirmDialog
import com.tstc.zstangshi.ui.HeaderBar
import com.tstc.zstangshi.ui.OptionSheet
import com.tstc.zstangshi.ui.PageSlide
import com.tstc.zstangshi.ui.PullRefreshLayout
import com.tstc.zstangshi.ui.Sheet
import com.tstc.zstangshi.ui.TransitCodeSheet

/**
 * 实时公交（二级页）。
 *
 * 数据与协议移植自 `Trackr`（github.com/5151561/Trackr，MIT），见 [BusClient]；
 * 信息层级与交互参照车来了：首页「点站名进站点、点线路块进线路」，线路详情用
 * 横排可滑动的站线并标出「离我最近」的候车站。视觉仍沿用本 App 的既有规范
 * （[HeaderBar]、`card_bg` 卡片、品牌色与语义色、圆角卡片进出场），
 * 深浅色只跟随系统，不提供主题切换。
 *
 * 三种模式共用一个 Activity，靠 Intent extra 区分：
 *   · HOME —— 城市 / 搜索 / 收藏 / 附近站点
 *   · STOP —— 某个站点的全部线路与到站
 *   · LINE —— 线路详情（候车站预测 + 横排站线）
 */
class BusActivity : BaseActivity() {

    private lateinit var store: BusStore
    private var slide: PageSlide? = null
    private var pull: PullRefreshLayout? = null
    private lateinit var body: LinearLayout
    private var searchBox: EditText? = null

    /**
     * 到站文案的即时更新器。补预测是一条条回来的，只换那一行的文字就行，
     * 整页重画会把用户正看着的位置和展开状态全打乱。
     */
    private val etaBinders: MutableMap<BusModels.Arrival, Runnable> =
        java.util.Collections.synchronizedMap(
            java.util.IdentityHashMap<BusModels.Arrival, Runnable>()
        )

    /** 线路详情当前这份数据 + 当前选中的候车站（原地切换用）。 */
    private var detail: BusModels.LineDetail? = null
    private var selectedOrder = -1

    /** 候车站的 sId：虚拟站组共用 order=999，只按站序认站会一次高亮四五个站。 */
    private var selectedStopId = ""
    private var pageScroll: ScrollView? = null
    private var stripScroll: HorizontalScrollView? = null

    /**
     * 线路详情顶部那张固定主卡。三态共用这一块：
     * 站点到站（[PANEL_STATION]）→ 全部车辆（[PANEL_BUS_LIST]）→ 单车跟随（[PANEL_BUS_FOLLOW]）。
     * 卡片在滚动区外面，所以点站、切面板都不会带动页面滚动。
     */
    private var heroHost: FrameLayout? = null
    private var panel = PANEL_STATION

    /** 单车跟随里正在看的那辆车（上游车辆标识）。 */
    private var followedBusId = ""

    /** 主卡高度/淡入动画；换面板时先 cancel，免得两次动画抢 */
    private var heroAnim: ValueAnimator? = null

    /**
     * 主卡当前的身份（面板 + 线路 + 方向 + 跟随的车）。
     * 身份没变就只是数据刷新（20 秒一轮的静默刷新、点别的站看同一张卡），
     * **不播高度动画、不淡入**——卡在自己身上换字，页面一点不抖，
     * 和追客「点其他站点页面完全不动」是同一个观感。
     */
    private var heroKey = ""

    /**
     * 滚动区结构（站线 + 时刻表槽 + 站表）的身份，只跟线路和方向有关。
     *
     * 换候车站、20 秒一轮的例行刷新都不会让它变 —— 这时**不重铺整页**，
     * 只换主卡、站线上车的位置和选中态、站表的选中态。追客就是这么做的：
     * 它的 `static.stops` 不变时 LazyColumn 一个 item 都不动，所以点别的站页面纹丝不动。
     * 我们如果每次 removeAllViews 再 post{scrollTo} 恢复，网络那几百毫秒里用户滑过的位置
     * 就白滑了，这就是「偶尔跳一下」的来源。
     */
    private var structKey = ""

    /** 站线的内层横排 / 站表卡片：结构没变时只重填这两个容器。 */
    private var stripInner: LinearLayout? = null
    private var stopListInner: LinearLayout? = null

    /** 顶栏「收藏」按钮：收藏是加进首页常用，页面里只有这一个入口。 */
    private var favBtn: TextView? = null

    /** 底栏两个动作（换向 / 乘车码），刷新时要跟着改可用性与可用状态。 */
    private var swapBtn: View? = null
    private var codeBtn: View? = null

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var fetching = false
    private var lastTouchAt = 0L

    /** 页面建起来的时刻。onResume 里补刷时要避开 onCreate 那一次请求。 */
    private var openedAt = System.currentTimeMillis()

    /**
     * 当前真正画在屏幕上的那些到站行。首页 / 站点详情每 20 秒只重算这一批，
     * 数字不会停在刚进页面时的老值，也不至于一次轰几十个请求被上游掐掉。
     * 线路详情整页重拉，用不到它。
     */
    private val shownEta: MutableSet<BusModels.Arrival> =
        java.util.LinkedHashSet<BusModels.Arrival>()

    /** 上一轮刷到哪儿了；每轮接着往下轮，整屏都能在一两分钟内轮完。 */
    private var etaCursor = 0
    private var etaRefreshAt = 0L

    private val autoTick: Runnable = Runnable { autoStep() }

    private fun autoStep() {
        quietRefresh()
        ticker.postDelayed(autoTick, AUTO_MS)
    }

    private var mode = MODE_HOME

    /** 原名 title，与 Activity.title 属性冲突，改名后仍只在本类内使用（行为不变）。 */
    private var pageTitle = "实时公交"
    private var cityId = BusClient.DEFAULT_CITY_ID
    private var lat = 0.0
    private var lng = 0.0

    private var stationName = ""
    private var physicalId = ""
    private var namesakeId = ""
    private var stationId = ""
    private var lineId = ""
    private var lineName = ""
    private var direction = 0
    private var targetOrder = 0

    /** 线路详情里的首站发车时刻表；换线路/换向就作废。 */
    private var tt: BusModels.Timetable? = null

    /** tt 属于哪条线（lineId#direction），对不上就不显示，免得串台。 */
    private var ttKey = ""
    private var ttAt = 0L

    /** 时刻表卡片的占位容器：数据晚到也不会把整页重排一遍。 */
    private var ttSlot: LinearLayout? = null
    private var ttLoading = false

    private var showingSearch = false

    /** 正在拉附近站点；和「等定位」用两个标志，不然一边在等定位会把列表请求挡住。 */
    private var loading = false

    /** 正在等一帧新定位。 */
    private var locating = false

    /** 最近一次真的拿到定位的时刻：连着两次进入页面不重复要。 */
    private var lastFixAt = 0L

    /** 最近一次发起定位的时刻：首页停留时每分钟自动对一次位置。 */
    private var lastLocateAt = 0L

    /** 手动下拉刷新：位置没动也要重新查一遍附近站点。 */
    private var forceReload = false

    /** 附近站点请求代号：换了位置会重开一轮，上一轮的响应回来直接丢掉。 */
    private var nearGen = 0

    /** 首页「附近站点」标题，等定位时挂一句提示。 */
    private var nearTitle: TextView? = null

    /** 顶栏的数据源按钮：切换后要跟着改字，不然一直显示旧数据源的名字。 */
    private var sourceBtn: TextView? = null

    /** 首页里被「展开全部」的站点（默认每条线路只露三条，和车来了一致）。 */
    private val expanded: MutableSet<String> = HashSet()

    /**
     * 本站的站台顺序。服务端会把「本次请求的那个站台」排到 stationList 首位，
     * 直接用返回顺序渲染会导致序号错位（点了站台2，高亮却还在站台1）。
     */
    private val stopPlatforms: MutableList<BusModels.Platform> = ArrayList()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        store = BusStore(this)
        // BusStore 构造时已把用户选过的数据源读回 BusClient（没选过默认车来了），
        // 城市编号跟源走，各存一份
        cityId = store.cityId()
        lat = store.lastLat()
        lng = store.lastLng()

        val itt = intent
        mode = itt.getStringExtra(EXTRA_MODE) ?: MODE_HOME
        stationName = safe(itt.getStringExtra(EXTRA_NAME))
        physicalId = safe(itt.getStringExtra(EXTRA_PHYSICAL))
        namesakeId = safe(itt.getStringExtra(EXTRA_NAMESAKE))
        stationId = safe(itt.getStringExtra(EXTRA_STATION))
        lineId = safe(itt.getStringExtra(EXTRA_LINE))
        lineName = safe(itt.getStringExtra(EXTRA_NAME))
        direction = itt.getIntExtra(EXTRA_DIRECTION, 0)
        targetOrder = itt.getIntExtra(EXTRA_ORDER, 0)
        val c = itt.getStringExtra(EXTRA_CITY)
        if (c != null && c.isNotEmpty()) cityId = c
        if (itt.hasExtra(EXTRA_LAT)) lat = itt.getDoubleExtra(EXTRA_LAT, lat)
        if (itt.hasExtra(EXTRA_LNG)) lng = itt.getDoubleExtra(EXTRA_LNG, lng)

        if (MODE_STOP == mode) {
            pageTitle = if (stationName.isEmpty()) "站点" else stationName
        } else if (MODE_LINE == mode) {
            pageTitle = displayLine(lineName)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pageColor())
        root.addView(header())

        val p = PullRefreshLayout(this)
        p.setRefreshText("正在获取实时公交…")
        p.setOnRefresh { reload() }
        pull = p
        val scroller: View = p
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16f), dp(12f), dp(16f), dp(28f))
        p.scrollView().addView(
            body, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pageScroll = p.scrollView()
        pageScroll!!.setOnTouchListener { v, e ->
            val a = e.actionMasked
            if (a == android.view.MotionEvent.ACTION_DOWN
                || a == android.view.MotionEvent.ACTION_MOVE
            ) {
                lastTouchAt = System.currentTimeMillis()
            }
            false
        }
        // 线路详情：主卡不单独置顶，和站线、时刻表一起放在滚动区最上面，跟着页面滚。
        // 只留底部一条操作栏（乘车码 / 换向）。
        val host = LinearLayout(this)
        host.orientation = LinearLayout.VERTICAL
        if (MODE_LINE == mode) {
            // 复用同一个 FrameLayout：每次重铺都把它挂到 body 顶端，
            // 只换里面的卡片，不新建视图（换面板时才能做高度过渡）
            heroHost = FrameLayout(this)
        }
        host.addView(
            scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        if (MODE_LINE == mode) {
            host.addView(bottomBar(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(host, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        applyInsets(root)
        val sld = PageSlide(this, pageColor())
        slide = sld
        sld.enter()
        sld.attachBack()
        if (MODE_LINE == mode) {
            // 卡片里退一级（车辆列表 / 单车跟随 → 站点卡）不该把整页带走，
            // 所以返回手势先问页面：要不要自己消化掉这次返回
            sld.backInterceptor = { panel != PANEL_STATION }
            sld.backAction = { backToStation() }
        }

        if (MODE_HOME == mode) {
            // 先用手里的旧坐标铺一屏（不至于白屏），紧接着就要一帧「此刻的位置」：
            // 旧坐标可能是几小时前在别处留下的，不重新定位就会一直显示那个地方的站点。
            renderHome()
            ensureLocation(true)
        } else if (MODE_STOP == mode) {
            loadStop()
        } else {
            loadLine()
        }
    }

    // ──────────────────────────── 骨架 ────────────────────────────

    private fun header(): LinearLayout {
        val h = HeaderBar.create(this, pageTitle) { closePage() }
        if (MODE_HOME == mode) {
            // 三角用几何绘制、与文字垂直居中；sourceBtn 只是其中的文字部分
            val srcBox = HeaderBar.actionWithCaret(this, BusClient.sourceName()) { pickSource() }
            sourceBtn = srcBox.getChildAt(0) as TextView
            h.addView(srcBox)
            h.addView(HeaderBar.action(this, "刷新") { reload() })
        } else if (MODE_LINE == mode) {
            val fav = store.isFavorite(cityId, lineId)
            val btn = HeaderBar.action(this, if (fav) "已收藏" else "收藏") {
                toggleTrack()
            }
            favBtn = btn
            h.addView(btn)
        }
        return h
    }

    private fun closePage() {
        // 顶栏返回和系统返回手势同一个口径：卡片里退一级，不直接关页
        if (MODE_LINE == mode && panel != PANEL_STATION) {
            backToStation()
            return
        }
        val s = slide
        if (s != null) s.finish()
        else finish()
    }

    private fun applyInsets(root: View) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            root.setPadding(0, if (id > 0) resources.getDimensionPixelSize(id) else 0, 0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(autoTick)
        ticker.postDelayed(autoTick, AUTO_MS)
        ticker.post { onEnter() }
    }

    /**
     * 每次回到这一页都要做一次刷新，但两层页面的口径不同：
     *
     * - **首页**：位置和公交信息都要新 —— 人可能已经从宿舍走到校门口了，
     *   只刷到站时间会算出一堆「离你 900 米」的假距离。所以重新要一次定位，
     *   位置真的变了就整套重铺（顺序、距离都跟着变）。
     * - **站点 / 线路详情**：位置没有意义（这一站、这一条线是固定的），
     *   只把到站时间和车辆位置重算一遍。
     */
    private fun onEnter() {
        if (MODE_HOME != mode) {
            quietRefresh()
            return
        }
        if (showingSearch) return              // 搜索页是静态列表，不用刷

        // 先要位置。以前这里会先补一轮到站（最多 36 条预测），把链路堵在后面，
        // 定位和 nearby 都得排队；位置没变时 keepList 那条路会补到站，变了则
        // renderNearby 会补，不用在这儿抢跑。
        ensureLocation(true)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(autoTick)
    }

    override fun onDestroy() {
        ticker.removeCallbacks(autoTick)
        super.onDestroy()
    }

    /**
     * 后台静默刷新：只更新到站/车辆位置，不显示下拉动画、不重置候车站和滚动位置。
     * 用户正在滑动或刚点过就先让一让，免得内容跳。
     */
    private fun quietRefresh() {
        if (isFinishing || isDestroyed || fetching) return
        // 刚进页面时 onCreate 已经在拉了，别在它落地前再补一次
        if (System.currentTimeMillis() - openedAt < 3000) return
        if (System.currentTimeMillis() - lastTouchAt < 2500) return
        if (MODE_LINE == mode) {
            if (detail != null) fetchLine(true, true)
        } else if (MODE_STOP == mode) {
            // 站点详情：整站重拉一次（上游会把每条线的到站一起重算），
            // 顺带把补不到的几条再补一遍
            if (stopPlatforms.isNotEmpty() || shownEta.isNotEmpty()) loadStop(true)
        } else if (MODE_HOME == mode) {
            // 搜索页是静态结果，别在这里重铺列表，也别把用户从搜索里踢回去
            if (showingSearch) return
            // 首页：只重算屏幕上这一批到站，不重铺站点列表，滚动位置和展开状态都不动
            refreshShownEta(ETA_PER_TICK)
            // 人可能就在页面停留期间走了一段：每分钟对一次位置，真换了地方再整套重铺
            if (System.currentTimeMillis() - lastLocateAt > RELOCATE_EVERY_MS) {
                ensureLocation(true)
            }
        }
    }

    /**
     * 刚铺好一屏站点：立刻把这一屏的到站整体算一遍，不等到 20 秒后的那一轮。
     * 进页面、下拉刷新、往下翻出更远的站点都走这里。
     */
    private fun primeShownEta() {
        primeShownEtaFrom(0)
    }

    /** 从第 from 行开始算（往下翻出新站点时，从新的那几条算起）。 */
    private fun primeShownEtaFrom(from: Int) {
        etaRefreshAt = 0
        etaCursor = Math.max(0, from)
        refreshShownEta(ETA_PER_PRIME)
    }

    /**
     * 首页 / 站点详情的静默刷新：把当前显示着的到站行按线路详情同一个口径重算。
     *
     * 每轮只算 limit 条、从上次停下的地方接着轮，一条回来就换一行文字，
     * 不重画页面。nearSts 给的那点站数只能当占位，真正的分钟数以这里为准 ——
     * 不然首页写「已到站 / 17 站外」，点进线路详情却是「1 分钟」。
     */
    private fun refreshShownEta(limit: Int) {
        if (shownEta.isEmpty()) return
        if (lat <= 0 || lng <= 0) {
            // 没定位就算不了预测，把「…」收掉，别让它一直转
            for (a in shownEta) a.pending = false
            for (a in shownEta) {
                val r = etaBinders[a]
                if (r != null) r.run()
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - etaRefreshAt < ETA_GAP_MS) return
        etaRefreshAt = now

        val all = ArrayList(shownEta)
        val n = all.size
        val take = Math.min(limit, n)
        val start = etaCursor % n
        val batch = ArrayList<BusModels.Arrival>(take)
        for (i in 0 until take) batch.add(all[(start + i) % n])
        etaCursor = (start + take) % n
        BusClient.refreshEta(cityId, batch, lat, lng, take, true) { a -> onEtaFilled(a) }
        // 一点数据都没有的几条先显示「…」——比先写「暂无实时」再跳成「6分钟」诚实
        for (a in batch) {
            if (!a.pending) continue
            val r = etaBinders[a]
            if (r != null) r.run()
        }
    }

    private fun reload() {
        if (MODE_HOME == mode) {
            // 搜索页下拉：按当前关键词重搜一遍，不把人踢回首页
            if (showingSearch) {
                if (searchKey.isNotEmpty()) doSearch(searchKey)
                else pull?.setRefreshing(false)
                return
            }
            searchBox?.setText("")
            // 首页的下拉刷新是「换个位置重新看」：先要一次新定位，拿到新坐标后
            // useLocation → renderHome 会按新位置把附近站点整套重铺（距离、顺序都变）。
            // 所以这里不清 list，等新坐标回来一次到位，避免先闪一屏旧位置的站点。
            nearDone = false
            nearRing = 0
            // 手动下拉是「重新查一遍」：就算位置没动也要重铺列表，不能只刷到站
            forceReload = true
            if (lat <= 0 || lng <= 0) renderHome()
            ensureLocation(true)
        } else if (MODE_STOP == mode) {
            loadStop(true)
        } else {
            // 线路详情的刷新只换实时位置：不动已选的候车站、不重排站表、不回顶部
            fetchLine(true, true)
        }
    }

    // ──────────────────────────── 首页 ────────────────────────────

    private fun renderHome() {
        renderHomeContent()
        if (lat > 0 && lng > 0) {
            // 重铺意味着位置可能变了：作废还在飞的上一轮请求，
            // 否则旧位置的响应晚一步回来，会把刚铺好的新列表又盖回旧站点
            nearGen++
            loading = false
            body.addView(loadingCard())
            loadNearby()
        } else {
            body.addView(
                infoCard(
                    "还没有定位",
                    "点上方「重新定位」获取附近站点；也可以直接搜索线路"
                )
            )
        }
    }

    /** 首页正文：搜索 + 城市 + 收藏 + 附近站点标题。 */
    private fun renderHomeContent() {
        body.removeAllViews()
        body.addView(searchCard())
        body.addView(cityRow())
        body.addView(favoritesSection())
        val nt = sectionTitle("附近站点")
        nearTitle = nt
        body.addView(nt)
        updateLocatingHint()
    }

    /** 正在等定位时，在「附近站点」后面挂一句提示：此刻列表还可能是上一次的位置。 */
    private fun updateLocatingHint() {
        val nt = nearTitle ?: return
        nt.text = if (locating) "附近站点 · 正在定位…" else "附近站点"
    }

    private fun searchCard(): View {
        val c = card()
        c.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL

        val box = EditText(this)
        searchBox = box
        box.setBackgroundResource(R.drawable.input_bg)
        box.hint = "搜索线路、站点"
        box.setHintTextColor(getColor(R.color.text_tertiary))
        box.setTextColor(getColor(R.color.text_primary))
        box.textSize = 14f
        box.isSingleLine = true
        box.imeOptions = EditorInfo.IME_ACTION_SEARCH
        box.setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        box.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(box.text.toString().trim())
                return@setOnEditorActionListener true
            }
            false
        }
        c.addView(
            box, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        val go = TextView(this)
        go.text = "搜索"
        go.textSize = 13f
        go.typeface = Typeface.DEFAULT_BOLD
        go.gravity = Gravity.CENTER
        go.setTextColor(0xFFFFFFFF.toInt())
        go.background = roundRect(getColor(R.color.brand), dp(10f))
        go.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        go.setOnClickListener { doSearch(box.text.toString().trim()) }
        val glp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        glp.leftMargin = dp(8f)
        c.addView(go, glp)

        // 搜索页要给一条退回附近站点的路：不然后退键只能退出整个功能
        if (showingSearch) {
            val cancel = TextView(this)
            cancel.text = "取消"
            cancel.textSize = 13f
            cancel.setTextColor(getColor(R.color.text_secondary))
            cancel.gravity = Gravity.CENTER
            cancel.setPadding(dp(12f), dp(9f), dp(4f), dp(9f))
            cancel.setOnClickListener { exitSearch() }
            val clp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            clp.leftMargin = dp(4f)
            c.addView(cancel, clp)
        }
        return c
    }

    /** 退出搜索，回到附近站点（不重新定位，位置没变）。 */
    private fun exitSearch() {
        showingSearch = false
        searchKey = ""
        searchAll.clear()
        searchShown = 0
        searchLinesBox = null
        searchFooter = null
        searchBox?.setText("")
        renderHome()
    }

    private fun cityRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(6f), dp(12f), dp(6f), dp(4f))

        val dot = TextView(this)
        dot.text = "●"
        dot.textSize = 9f
        dot.setTextColor(0xFF3FA37A.toInt())
        row.addView(dot)

        val city = TextView(this)
        city.text = store.cityName() + " · 实时公交"
        city.textSize = 12.5f
        city.setTextColor(getColor(R.color.text_secondary))
        city.setPadding(dp(6f), 0, 0, 0)
        row.addView(city)

        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val pick = TextView(this)
        pick.text = "切换城市"
        pick.textSize = 12.5f
        pick.setTextColor(getColor(R.color.brand))
        pick.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        pick.setOnClickListener { pickCity() }
        row.addView(pick)

        val reloc = TextView(this)
        reloc.text = "重新定位"
        reloc.textSize = 12.5f
        reloc.setTextColor(getColor(R.color.text_tertiary))
        reloc.setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
        reloc.setOnClickListener { ensureLocation(true) }
        row.addView(reloc)
        return row
    }

    private fun favoritesSection(): View {
        val favs = store.favoritesOf(cityId)
        if (favs.isEmpty()) return LinearLayout(this)

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(sectionTitle("收藏线路"))
        for (f in favs) {
            val c = card()
            c.setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
            c.orientation = LinearLayout.HORIZONTAL
            c.gravity = Gravity.CENTER_VERTICAL
            c.isClickable = true
            c.background = rippleCard()
            c.setOnClickListener {
                openLine(this, cityId, f.lineId, f.name, f.direction, 0, "", lat, lng)
            }

            c.addView(badge(f.name))

            val mid = LinearLayout(this)
            mid.orientation = LinearLayout.VERTICAL
            val t = TextView(this)
            t.text = displayLine(f.name)
            t.textSize = 15f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(getColor(R.color.text_primary))
            mid.addView(t)
            if (f.terminus().isNotEmpty()) {
                val d = TextView(this)
                d.text = f.terminus()
                d.textSize = 11.5f
                d.setTextColor(getColor(R.color.text_tertiary))
                d.setPadding(0, dp(3f), 0, 0)
                mid.addView(d)
            }
            val mlp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            mlp.leftMargin = dp(10f)
            c.addView(mid, mlp)

            val arrow = TextView(this)
            arrow.text = "›"
            arrow.textSize = 20f
            arrow.setTextColor(getColor(R.color.text_tertiary))
            c.addView(arrow)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8f)
            c.layoutParams = lp
            wrap.addView(c)
        }
        return wrap
    }

    private fun loadNearby() {
        if (loading) return
        loading = true
        val cid = cityId
        val la = lat
        val ln = lng
        // 换位置会重开一轮，上一轮晚到的响应不能拿来覆盖新位置的列表
        nearGen++
        val gen = nearGen
        Thread({
            var list: List<BusModels.Station>? = null
            try {
                list = BusClient.nearby(cid, la, ln, 8)
            } catch (e: Exception) {
                val error = friendly(e)
                runOnUiThread {
                    if (gen != nearGen) return@runOnUiThread
                    loading = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pull?.setRefreshing(false)
                    if (showingSearch) return@runOnUiThread
                    renderNearby(null, error)
                }
                return@Thread
            }
            // 第一屏只用 nearby 的结果，几百毫秒就能上屏；
            // 站点详情那批每个站一条请求、通常要一两秒，回来再补进已有卡片里。
            // 以前是等两批都齐了才画，换一次位置要多等两秒。
            val first: List<BusModels.Station> = list ?: return@Thread
            runOnUiThread {
                if (gen != nearGen) return@runOnUiThread
                loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                pull?.setRefreshing(false)
                if (showingSearch) return@runOnUiThread
                renderNearby(first, null)
            }
            // 附近接口只给最近两三站算实时，其余站补一次详情，避免一排「暂无车」
            val details = BusClient.stationDetails(cid, first, la, ln)
            if (details.isEmpty()) return@Thread
            runOnUiThread {
                if (gen != nearGen) return@runOnUiThread
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (showingSearch) return@runOnUiThread
                var any = false
                for (s in nearList) {
                    val full = details[s.id]
                    if (full != null) {
                        BusClient.mergeDetail(s, full)
                        any = true
                    }
                }
                if (!any) return@runOnUiThread
                // 合并换了 Arrival 对象，旧的行绑定要一起换掉；重画之后必须补一次预测：
                // 详情里没实时数据的线路是带 pending 的，不补就一直挂着「…」，
                // 只能靠用户点「展开全部」或等 20 秒轮询。
                etaBinders.clear()
                shownEta.clear()
                etaCursor = 0
                fillNearCards()
                primeArrivals(ArrayList(shownEta))
            }
        }, "bus-nearby").start()
    }

    /** 附近站点列表：先给最近的一批，用户继续往上翻再按距离往外扩。 */
    private val nearList: MutableList<BusModels.Station> = ArrayList()
    private var nearBox: LinearLayout? = null
    private var nearFooter: TextView? = null
    private var nearRing = 0
    private var nearDone = false
    private var nearLoading = false

    /** 连续几圈没探到新站；中间只要有一圈探到就清零。 */
    private var nearMiss = 0

    private fun renderNearby(list: List<BusModels.Station>?, error: String?) {
        renderHomeContent()
        etaBinders.clear()
        shownEta.clear()
        etaCursor = 0
        nearList.clear()
        nearRing = 0
        nearDone = false
        nearLoading = false
        nearMiss = 0

        val nb = LinearLayout(this)
        nearBox = nb
        nb.orientation = LinearLayout.VERTICAL
        body.addView(nb)

        if (error != null) {
            body.addView(errorCard(error))
            return
        }
        if (list == null || list.isEmpty()) {
            body.addView(infoCard("附近没有查到站点", "换个城市，或直接搜索线路"))
            return
        }
        nearList.addAll(list)
        fillNearCards()

        val nf = footerHint("继续下滑，加载更远的站点")
        nearFooter = nf
        body.addView(nf)
        attachScrollPager()
        // 这一屏的到站整体算一遍：nearSts 只给最近几站算实时，其余只有站数没有分钟，
        // 统一走线路详情那个接口，两处的数字才同一个口径
        primeShownEta()
    }

    private fun fillNearCards() {
        nearBox!!.removeAllViews()
        for (s in nearList) nearBox!!.addView(stationCard(s))
    }

    /** 补到一条预测：只重刷那一行，不整页重画。 */
    private fun onEtaFilled(a: BusModels.Arrival) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val r = etaBinders[a]
            if (r != null) r.run()
        }
    }

    private fun footerHint(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 12f
        v.setTextColor(getColor(R.color.text_tertiary))
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(10f), 0, dp(6f))
        return v
    }

    /**
     * 滚到接近底部：搜索页继续放线路，首页继续按距离往外扩站点。
     * 两处共用一个滚动监听 —— 换成各自 set 会互相覆盖。
     */
    private fun attachScrollPager() {
        val ps = pageScroll ?: return
        ps.setOnScrollChangeListener { v, sx, sy, ox, oy ->
            val child = (v as ViewGroup).getChildAt(0) ?: return@setOnScrollChangeListener
            if (sy + v.height < child.height - dp(260f)) return@setOnScrollChangeListener
            if (showingSearch) loadMoreSearch()
            else loadMoreNear()
        }
    }

    private fun loadMoreNear() {
        if (showingSearch) return
        if (nearLoading || nearDone || nearList.isEmpty() || lat <= 0 || lng <= 0) return
        nearLoading = true
        nearRing++
        nearFooter?.text = "正在加载更远的站点…"

        val ring = nearRing
        val la = lat
        val ln = lng
        val cid = cityId
        val have: MutableSet<String> = HashSet()
        for (s in nearList) have.add(s.id)
        Thread({
            var more: List<BusModels.Station> = ArrayList()
            try {
                more = BusClient.fartherStations(cid, la, ln, ring, have)
            } catch (ignored: Exception) {
            }
            val data = more
            runOnUiThread {
                nearLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val before = shownEta.size
                var added = 0
                for (s in data) {
                    if (have.contains(s.id)) continue
                    have.add(s.id)
                    // 按距离插进去，别让后翻出来的近站排到远站后头
                    var at = nearList.size
                    for (i in 0 until nearList.size) {
                        if (s.distanceMeters < nearList[i].distanceMeters) {
                            at = i
                            break
                        }
                    }
                    nearList.add(at, s)
                    nearBox!!.addView(stationCard(s), at)
                    added++
                }
                if (added == 0) {
                    // 某一圈探空了不一定是真没了：再往外一圈可能还有，连续两圈空才收尾
                    nearMiss++
                    if (nearMiss >= 2 || nearRing >= 8) finishNear()
                    else {
                        nearFooter?.text = "继续下滑，加载更远的站点"
                        // 这一圈空出来的位置继续往下探，用户不用再滑一次
                        pageScroll!!.post { loadMoreNear() }
                    }
                } else {
                    nearMiss = 0
                    // 新翻出来的这批也得算到站，从新增的那几行开始
                    primeShownEtaFrom(before)
                    if (nearRing >= 8) finishNear()
                    else nearFooter?.text = "继续下滑，加载更远的站点"
                }
            }
        }, "bus-more").start()
    }

    private fun finishNear() {
        nearDone = true
        nearFooter?.text = "附近能查到的站点就这些了"
    }

    /**
     * 附近站点卡，结构照车来了：
     * 顶部一整行是「站名 + 几个站台 + 距离」，点它进站点详情；
     * 下面每条线路是独立一行，点它直接进线路详情。
     */
    private fun stationCard(s: BusModels.Station): View {
        val c = card()
        c.setPadding(dp(14f), 0, dp(14f), 0)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(2f), dp(13f), 0, dp(11f))
        head.isClickable = true
        head.setOnClickListener { openStop(this, s, cityId, lat, lng) }

        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_quick_bus)
        ic.setColorFilter(getColor(R.color.brand))
        ic.background = roundRect(getColor(R.color.brand_soft), dp(9f))
        ic.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        head.addView(ic, LinearLayout.LayoutParams(dp(28f), dp(28f)))

        val name = TextView(this)
        name.text = s.name
        name.textSize = 15f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(getColor(R.color.text_primary))
        name.setPadding(dp(9f), 0, 0, 0)
        head.addView(name)

        val meta = TextView(this)
        meta.textSize = 11.5f
        meta.setTextColor(getColor(R.color.text_tertiary))
        val m = StringBuilder()
        if (s.platforms.size > 1) m.append(" ").append(s.platforms.size).append(" 个站台")
        if (s.distanceLabel().isNotEmpty()) m.append(" ").append(s.distanceLabel())
        meta.text = m.toString()
        head.addView(meta)

        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val arrow = TextView(this)
        arrow.text = "›"
        arrow.textSize = 19f
        arrow.setTextColor(getColor(R.color.text_tertiary))
        head.addView(arrow)
        c.addView(head)

        val linesBox = LinearLayout(this)
        linesBox.orientation = LinearLayout.VERTICAL
        c.addView(linesBox)
        fillStationLines(linesBox, s)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10f)
        c.layoutParams = lp
        return c
    }

    /** 站点卡里的线路列表。「展开全部」只重画这一块，不整页重排（否则会跳回顶部）。 */
    private fun fillStationLines(box: LinearLayout, s: BusModels.Station) {
        box.removeAllViews()
        // 重铺之前先把这一站上一轮的行摘掉：不摘的话「展开/收起」会把同一批 Arrival
        // 一遍遍塞进 shownEta，收起后看不见的行还在被轮询。
        for (a in s.arrivals) {
            etaBinders.remove(a)
            shownEta.remove(a)
        }
        if (s.arrivals.isEmpty()) {
            val none = TextView(this)
            none.text = "暂无实时数据"
            none.textSize = 11.5f
            none.setTextColor(getColor(R.color.text_tertiary))
            none.setPadding(dp(2f), 0, 0, dp(12f))
            box.addView(none)
            return
        }
        val open = expanded.contains(s.id)
        val show = if (open) s.arrivals.size else Math.min(3, s.arrivals.size)
        val visible = ArrayList<BusModels.Arrival>(show)
        for (i in 0 until show) {
            val a = s.arrivals[i]
            visible.add(a)
            box.addView(divider())
            box.addView(lineBlock(a))
        }
        // 刚展开出来的行还挂着「…」（上游没给它们的 stnStates），得马上算一次，
        // 别等下一轮轮询——那时候用户早就盯着几个省略号在等了
        if (open) primeArrivals(visible)
        if (s.arrivals.size > 3) {
            val more = TextView(this)
            more.text = if (open) "收起" else "展开全部"
            more.textSize = 12.5f
            more.setTextColor(getColor(R.color.text_tertiary))
            more.gravity = Gravity.CENTER
            more.setPadding(0, dp(10f), 0, dp(10f))
            more.isClickable = true
            more.setOnClickListener {
                if (open) expanded.remove(s.id)
                else expanded.add(s.id)
                fillStationLines(box, s)
            }
            box.addView(more)
        }
    }

    private fun primeArrivals(list: List<BusModels.Arrival>?) {
        if (list == null || list.isEmpty()) return
        val todo = ArrayList<BusModels.Arrival>()
        for (a in list) {
            // 没有站序就算不出「还有几站」，别让「…」一直转
            if (a.orderInLine <= 0) {
                a.pending = false
                continue
            }
            // 已经有数的行交给定时刷新，这里只救那些空着的
            if (!a.pending && (a.arrived || a.minutes >= 0)) continue
            todo.add(a)
        }
        if (todo.isEmpty() || lat <= 0 || lng <= 0) {
            for (a in todo) a.pending = false
            for (a in list) {
                val r = etaBinders[a]
                if (r != null) r.run()
            }
            return
        }
        BusClient.refreshEta(cityId, todo, lat, lng, todo.size, true) { a -> onEtaFilled(a) }
        for (a in todo) {
            if (!a.pending) continue
            val r = etaBinders[a]
            if (r != null) r.run()
        }
    }

    /** 一条线路：左边线路名 + 开往，右边「23分钟·14站」+「下辆…」。 */
    private fun lineBlock(a: BusModels.Arrival): View {
        shownEta.add(a)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(2f), dp(11f), dp(2f), dp(11f))
        row.background = rippleRow()
        // 点哪个站的线路，就默认候车在那个站（a.orderInLine 是该站在该线的站序）；
        // 拿不到站序（=0）时 BusClient 会退回到「离我最近的那站」
        row.setOnClickListener {
            openLine(
                this, cityId, a.lineId, a.lineName, a.direction,
                a.orderInLine, a.stopId, lat, lng
            )
        }

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = displayLine(a.lineName)
        name.textSize = 16f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(getColor(R.color.text_primary))
        left.addView(name)

        val dest = TextView(this)
        dest.text = "开往 " + (if (a.destination().isEmpty()) "—" else a.destination())
        dest.textSize = 11.5f
        dest.setTextColor(getColor(R.color.text_tertiary))
        dest.setPadding(0, dp(3f), 0, 0)
        dest.maxLines = 1
        dest.ellipsize = android.text.TextUtils.TruncateAt.END
        left.addView(dest)
        row.addView(
            left, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END

        val eta = TextView(this)
        eta.textSize = 16f
        eta.typeface = Typeface.DEFAULT_BOLD
        eta.gravity = Gravity.END
        right.addView(eta)

        // 「下辆」先占好位置再按需显示，补预测回来时只换字不插行，列表不会跳一下
        val next = TextView(this)
        next.textSize = 11f
        next.setTextColor(getColor(R.color.text_tertiary))
        next.setPadding(0, dp(3f), 0, 0)
        next.gravity = Gravity.END
        right.addView(next)
        bindEta(a, eta, next)

        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rlp.leftMargin = dp(10f)
        row.addView(right, rlp)
        return row
    }

    /** 把一行的到站文案绑到 Arrival 上，补预测回来后直接改这几个 TextView。 */
    private fun bindEta(a: BusModels.Arrival, eta: TextView, next: TextView) {
        val r = Runnable {
            eta.text = a.etaCompact()
            eta.setTextColor(etaColor(a))
            val nx = a.nextLabel()
            next.text = nx
            next.visibility = if (nx.isEmpty()) View.GONE else View.VISIBLE
        }
        r.run()
        etaBinders[a] = r
    }

    /** 到站时间的颜色：已到站 / 3 分钟内用暖色提醒，其余用品牌蓝。 */
    private fun etaColor(a: BusModels.Arrival): Int {
        if (a.arrived || (a.minutes >= 0 && a.minutes <= 3)) return 0xFFE8734A.toInt()
        if (a.minutes < 0 && a.stopsAway < 0) return getColor(R.color.text_tertiary)
        return getColor(R.color.brand)
    }

    // ──────────────────────────── 搜索 ────────────────────────────

    private var searchKey = ""

    /** 已拿到的线路（第一屏只是上游的前 10 条），按 lineId#direction 去重后追加 */
    private val searchAll: MutableList<BusModels.Line> = ArrayList()
    private var searchShown = 0
    private var searchMoreLoading = false

    /** 上游全量已经取过一次（不管有没有新东西），不再重复请求 */
    private var searchMoreDone = false
    private var searchLinesBox: LinearLayout? = null
    private var searchFooter: TextView? = null

    private fun doSearch(key: String) {
        if (key.isEmpty()) return
        showingSearch = true
        searchKey = key
        searchAll.clear()
        searchShown = 0
        searchMoreLoading = false
        searchMoreDone = false
        searchLinesBox = null
        searchFooter = null
        shownEta.clear()
        etaBinders.clear()

        body.removeAllViews()
        body.addView(searchCard())
        val sb = searchBox
        if (sb != null) {
            sb.setText(key)
            sb.setSelection(sb.text.length)
        }
        body.addView(sectionTitle("「" + key + "」的搜索结果"))
        body.addView(loadingCard())
        // 搜索页也有「滑到底继续加载」，和首页共用一套滚动判据
        attachScrollPager()

        val kw = key
        Thread({
            var hit: BusModels.SearchHit? = null
            var err: String? = null
            try {
                hit = BusClient.search(cityId, kw, lat, lng)
            } catch (e: Exception) {
                err = friendly(e)
            }
            val data = hit
            val error = err
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 连按两次搜索：只认最后一次的关键词，别让先回来的旧结果覆盖新结果
                if (!showingSearch || kw != searchKey) return@runOnUiThread
                renderSearch(data, error)
            }
        }, "bus-search").start()
    }

    private fun renderSearch(data: BusModels.SearchHit?, error: String?) {
        pull?.setRefreshing(false)
        body.removeAllViews()
        body.addView(searchCard())
        val sb = searchBox
        if (sb != null) {
            sb.setText(searchKey)
            sb.setSelection(sb.text.length)
        }
        searchLinesBox = null
        searchFooter = null

        if (error != null) {
            body.addView(errorCard(error))
            return
        }
        if (data == null || data.empty()) {
            body.addView(
                infoCard(
                    "没有找到相关站点或线路",
                    "站名要写成连着的词（如「大学公寓」），线路写「60」或「60路」都行"
                )
            )
            return
        }

        if (data.stations.isNotEmpty()) {
            body.addView(sectionTitle("站点"))
            for (s in data.stations) body.addView(searchStationRow(s))
            if (data.stationsTruncated()) {
                // 上游只回前 3 个站，剩下没有可翻的列表 —— 如实说明，别让人以为站点就这些
                body.addView(
                    footerHint(
                        "共 " + data.stationCount + " 个站点命中，这里只列最接近的 "
                                + data.stations.size + " 个 · 站名写全一些能找得更准"
                    )
                )
            }
        }

        if (data.lines.isNotEmpty()) {
            val t = if (data.lineCount > 0) "线路 · 共 " + data.lineCount + " 条" else "线路"
            body.addView(sectionTitle(t))
            val box = LinearLayout(this)
            searchLinesBox = box
            box.orientation = LinearLayout.VERTICAL
            body.addView(box)
            searchAll.addAll(dedupe(data.lines))
            // 上游报的命中数已经全在这一屏里了，就不用再为「更多」多发一次请求
            searchMoreDone = !data.linesTruncated()
            appendSearchLines(SEARCH_PAGE)
            val fh = footerHint("")
            searchFooter = fh
            body.addView(fh)
            updateSearchFooter()
            // 上游第一屏只给 10 条记录（合并方向后只剩 5 条线），不够一屏就立刻补全，
            // 别让人以为「就这么几条」
            if (searchShown < SEARCH_PAGE && !searchMoreDone) loadMoreSearch()
        }
    }

    /** 把还没铺出来的线路接着铺 count 条。 */
    private fun appendSearchLines(count: Int) {
        val box = searchLinesBox ?: return
        val end = Math.min(searchAll.size, searchShown + count)
        for (i in searchShown until end) {
            box.addView(searchRow(searchAll[i]))
        }
        searchShown = end
    }

    private fun updateSearchFooter() {
        val f = searchFooter ?: return
        if (searchMoreLoading) {
            f.text = "正在加载更多线路…"
        } else if (searchShown < searchAll.size) {
            f.text = "继续下滑，加载更多线路"
        } else if (!searchMoreDone) {
            f.text = "继续下滑，加载更多线路"
        } else {
            f.text = "已显示全部 " + searchShown + " 条线路"
        }
    }

    /**
     * 搜索页滑到底：手上还有没铺完的先铺（不发请求），铺完了再按 `more=true`
     * 向上游要一次全量 —— 这个模式一次给全，之后都是本地翻页。
     */
    private fun loadMoreSearch() {
        if (!showingSearch || searchLinesBox == null || searchMoreLoading) return
        if (searchShown < searchAll.size) {
            appendSearchLines(SEARCH_PAGE)
            updateSearchFooter()
            return
        }
        if (searchMoreDone) return

        searchMoreLoading = true
        updateSearchFooter()
        val kw = searchKey
        Thread({
            var more: List<BusModels.Line>? = null
            try {
                more = BusClient.searchMore(cityId, kw, lat, lng)
            } catch (ignored: Exception) {
            }
            val got = more
            runOnUiThread {
                if (isFinishing || isDestroyed || !showingSearch) return@runOnUiThread
                if (kw != searchKey) return@runOnUiThread
                searchMoreLoading = false
                searchMoreDone = true
                if (got != null && got.isNotEmpty()) searchAll.addAll(dedupe(got))
                appendSearchLines(SEARCH_PAGE)
                updateSearchFooter()
            }
        }, "bus-search-more").start()
    }

    /** 上游每条线的正反方向已在上层合并成一行，这里按 lineNo（没有就用名字）去重。 */
    private fun dedupe(input: List<BusModels.Line>): List<BusModels.Line> {
        val seen: MutableSet<String> = HashSet()
        for (l in searchAll) seen.add(lineKey(l))
        val out = ArrayList<BusModels.Line>()
        for (l in input) {
            if (seen.add(lineKey(l))) out.add(l)
        }
        return out
    }

    private fun searchStationRow(s: BusModels.Station): View {
        val c = card()
        c.setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = rippleCard()
        c.isClickable = true
        c.setOnClickListener { openStop(this, s, cityId, lat, lng) }

        val ic = ImageView(this)
        ic.setImageResource(R.drawable.ic_quick_bus)
        ic.setColorFilter(getColor(R.color.brand))
        ic.background = roundRect(getColor(R.color.brand_soft), dp(9f))
        ic.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        c.addView(ic, LinearLayout.LayoutParams(dp(28f), dp(28f)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = s.name
        t.textSize = 15f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(getColor(R.color.text_primary))
        mid.addView(t)
        val d = TextView(this)
        d.text = if (s.distanceLabel().isEmpty()) "公交站点" else "公交站点 · 距你 " + s.distanceLabel()
        d.textSize = 11.5f
        d.setTextColor(getColor(R.color.text_tertiary))
        d.setPadding(0, dp(3f), 0, 0)
        mid.addView(d)
        val mlp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        mlp.leftMargin = dp(10f)
        c.addView(mid, mlp)

        val arrow = TextView(this)
        arrow.text = "›"
        arrow.textSize = 20f
        arrow.setTextColor(getColor(R.color.text_tertiary))
        c.addView(arrow)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8f)
        c.layoutParams = lp
        return c
    }

    private fun searchRow(l: BusModels.Line): View {
        val c = card()
        c.setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.isClickable = true
        c.background = rippleCard()
        c.setOnClickListener {
            openLine(this, cityId, l.lineId, l.name, l.direction, 0, "", lat, lng)
        }

        c.addView(badge(l.name))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = displayLine(l.name)
        t.textSize = 15f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(getColor(R.color.text_primary))
        mid.addView(t)
        if (l.terminusLabel().isNotEmpty()) {
            val d = TextView(this)
            d.text = l.terminusLabel()
            d.textSize = 11.5f
            d.setTextColor(getColor(R.color.text_tertiary))
            d.setPadding(0, dp(3f), 0, 0)
            mid.addView(d)
        }
        val mlp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        mlp.leftMargin = dp(10f)
        c.addView(mid, mlp)

        val arrow = TextView(this)
        arrow.text = "›"
        arrow.textSize = 20f
        arrow.setTextColor(getColor(R.color.text_tertiary))
        c.addView(arrow)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8f)
        c.layoutParams = lp
        return c
    }

    // ──────────────────────────── 站点详情 ────────────────────────────

    private fun loadStop() {
        loadStop(false)
    }

    /** silent=true：不闪加载卡，只把到站时间换掉（定时刷新 / 下拉刷新都走这条）。 */
    private fun loadStop(silent: Boolean) {
        if (fetching) return
        fetching = true
        val keepY = if (silent && pageScroll != null) pageScroll!!.scrollY else -1
        if (!silent) {
            body.removeAllViews()
            body.addView(loadingCard())
        }
        Thread({
            var s: BusModels.Station? = null
            var err: String? = null
            try {
                s = BusClient.stopDetail(cityId, physicalId, namesakeId, stationId, lat, lng)
            } catch (e: Exception) {
                err = friendly(e)
            }
            val data = s
            val error = err
            runOnUiThread {
                fetching = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                pull?.setRefreshing(false)
                body.removeAllViews()
                if (error != null) {
                    body.addView(errorCard(error))
                    return@runOnUiThread
                }
                if (data == null || data.arrivals.isEmpty()) {
                    body.addView(
                        infoCard(
                            "这个站点暂时没有实时数据",
                            "可能已停用，或该线路还没接入实时调度"
                        )
                    )
                    return@runOnUiThread
                }
                mergePlatforms(data.platforms)
                if (stopPlatforms.size > 1) body.addView(platformChips())
                body.addView(sectionTitle("途经 " + data.arrivals.size + " 条线路"))
                etaBinders.clear()
                shownEta.clear()
                etaCursor = 0
                for (a in data.arrivals) {
                    body.addView(stopLineCard(a))
                }
                // 站点详情同样按线路详情那个口径算一遍，别和点进去之后的数字打架
                primeShownEta()
                if (keepY > 0 && pageScroll != null) {
                    pageScroll!!.post { pageScroll!!.scrollTo(0, keepY) }
                }
            }
        }, "bus-stop").start()
    }

    private fun mergePlatforms(incoming: List<BusModels.Platform>) {
        for (p in incoming) {
            var found = false
            for (q in stopPlatforms) {
                if (q.id == p.id) {
                    found = true
                    break
                }
            }
            if (!found) stopPlatforms.add(p)
        }
    }

    /** 站台切换：同一站名下的多个物理站台各自有独立的到站数据。 */
    private fun platformChips(): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(2f), dp(10f), dp(2f), dp(2f))

        for (i in stopPlatforms.indices) {
            val p = stopPlatforms[i]
            val on = p.id == physicalId
            val chip = TextView(this)
            chip.text = "站台 " + (i + 1)
            chip.textSize = 12.5f
            chip.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.gravity = Gravity.CENTER
            chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else getColor(R.color.text_secondary))
            chip.background = roundRect(
                if (on) getColor(R.color.brand)
                else getColor(R.color.bg_sunken), dp(16f)
            )
            chip.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            if (!on) {
                chip.setOnClickListener {
                    physicalId = p.id
                    loadStop()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = dp(8f)
            row.addView(chip, lp)
        }
        hs.addView(row)
        return hs
    }

    private fun stopLineCard(a: BusModels.Arrival): View {
        shownEta.add(a)
        val c = card()
        c.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        c.isClickable = true
        c.background = rippleCard()
        // 从站点详情点进线路：候车站就是刚才看的这一站，而不是离我最近的那站
        c.setOnClickListener {
            openLine(
                this, cityId, a.lineId, a.lineName, a.direction,
                a.orderInLine, a.stopId, lat, lng
            )
        }

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(badge(a.lineName))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        val dest = TextView(this)
        dest.text = "开往 " + (if (a.destination().isEmpty()) "—" else a.destination())
        dest.textSize = 13f
        dest.setTextColor(getColor(R.color.text_secondary))
        dest.maxLines = 1
        dest.ellipsize = android.text.TextUtils.TruncateAt.END
        mid.addView(dest)
        val next = TextView(this)
        next.textSize = 11f
        next.setTextColor(getColor(R.color.text_tertiary))
        next.setPadding(0, dp(3f), 0, 0)
        mid.addView(next)
        val mlp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        mlp.leftMargin = dp(10f)
        top.addView(mid, mlp)

        val eta = TextView(this)
        eta.textSize = 15f
        eta.typeface = Typeface.DEFAULT_BOLD
        val elp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        elp.leftMargin = dp(8f)
        top.addView(eta, elp)
        bindEta(a, eta, next)
        c.addView(top)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8f)
        c.layoutParams = lp
        return c
    }

    // ──────────────────────────── 线路详情 ────────────────────────────

    private fun loadLine() {
        fetchLine(false, false)
    }

    /**
     * 拉线路详情。silent=true 时不闪加载卡、保留当前滚动位置——
     * 换候车站、换向都在本页原地完成，不新开页面。
     */
    private fun fetchLine(silent: Boolean, keepScroll: Boolean) {
        if (fetching) return
        fetching = true
        if (!silent) {
            body.removeAllViews()
            body.addView(loadingCard())
        }
        val lid = lineId
        val order = targetOrder
        Thread({
            var d: BusModels.LineDetail? = null
            var err: String? = null
            try {
                d = BusClient.lineDetail(cityId, lid, order, stationId, lat, lng)
            } catch (e: Exception) {
                err = friendly(e)
            }
            val data = d
            val error = err
            runOnUiThread {
                fetching = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                pull?.setRefreshing(false)
                if (error != null) {
                    body.removeAllViews()
                    body.addView(errorCard(error))
                    return@runOnUiThread
                }
                if (data == null || data.stops.isEmpty()) {
                    body.removeAllViews()
                    body.addView(infoCard("这条线路没有站表数据", "可能是新开线路，或上游还没维护"))
                    return@runOnUiThread
                }
                detail = data
                selectedOrder = data.waitingOrder
                selectedStopId = data.waitingStopId
                // 滚动位置在这里才取：请求发出去到回来这几百毫秒里用户可能已经滑走了，
                // 拿发请求时的位置去恢复，就会把人拽回去 —— 这就是「偶尔跳一下」的来源
                val keepY = if (keepScroll && pageScroll != null) pageScroll!!.scrollY else -1
                val keepX = if (keepScroll && stripScroll != null) stripScroll!!.scrollX else -1
                renderLine(keepY, keepX)
                // 站表先上屏，时刻表晚一步补：它要多一次请求，别拖慢首屏
                loadTimetable(data)
            }
        }, "bus-line").start()
    }

    /**
     * 拉首站发车时刻表。5 分钟内的不重复拉（它变化慢），但每次都会重算「下一班」，
     * 因为那是拿本机时钟和班次表比的。
     */
    private fun loadTimetable(d: BusModels.LineDetail) {
        val key = lineId + "#" + d.line.direction
        if (key == ttKey && tt != null) {
            fillTimetableSlot()
            if (System.currentTimeMillis() - ttAt < TT_TTL_MS) return
        }
        if (ttLoading) return
        if (d.line.lineNo.isEmpty() && d.line.name.isEmpty()) return
        ttLoading = true
        val cid = cityId
        val lid = lineId
        val dir = d.line.direction
        val no = d.line.lineNo
        Thread({
            var got: BusModels.Timetable? = null
            try {
                got = BusClient.timetable(cid, lid, dir, no)
            } catch (ignored: Exception) {
            }
            val data = got
            runOnUiThread {
                ttLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (data == null || data.empty()) return@runOnUiThread
                // 首末班优先用线路官方的：昨日实际的首班常常比公布的晚一点
                val cur = detail
                if (cur != null) {
                    if (cur.line.firstTime.isNotEmpty()) data.firstDep = cur.line.firstTime
                    if (cur.line.lastTime.isNotEmpty()) data.lastDep = cur.line.lastTime
                }
                tt = data
                ttKey = key
                ttAt = System.currentTimeMillis()
                fillTimetableSlot()
            }
        }, "bus-tt").start()
    }

    /** 往占位容器里填时刻表卡片（只动这一块，滚动位置不受影响）。 */
    private fun fillTimetableSlot() {
        val slot = ttSlot ?: return
        slot.removeAllViews()
        val cur = detail
        if (tt == null || ttKey != lineId + "#" + (if (cur == null) -1 else cur.line.direction)) return
        slot.addView(timetableCard(tt!!))
    }

    private fun renderLine(keepY: Int, keepX: Int) {
        val d = detail ?: return
        val key = lineId + "#" + d.line.direction + "#" + d.stops.size
        // 结构没变（换候车站 / 例行刷新）：只换内容，一个视图都不增删。
        // 和追客一样 —— 它换站时 LazyColumn 的 item 不动，所以页面完全不跳。
        if (key == structKey && heroHost?.parent === body && body.childCount > 1) {
            renderHero()
            restyleStops(d)
            if (ttKey != lineId + "#" + d.line.direction) tt = null
            fillTimetableSlot()
            return
        }
        structKey = key
        body.removeAllViews()
        // 主卡在滚动区最上面（不置顶），跟着页面一起滚
        heroHost?.let { h ->
            h.setPadding(0, 0, 0, 0)
            body.addView(
                h, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // 主卡内容：换站、轮询只换卡片里的字
        renderHero()
        // 线路信息：方向 / 运营时间 / 票价 / 全程站数（换向按钮就在方向那一行）
        body.addView(lineInfoCard(d))
        // 滚动区：① 主卡 → ② 线路信息 → ③ 线路图 → ④ 首站发车 → ⑤ 全部站点
        body.addView(sectionTitle("线路图 · 左右滑动查看"))
        // 站线只在首进页面时居中一次；之后不管换候车站还是例行刷新，
        // 一律恢复用户当前的滚动位置——用户能点到那站，说明它就在屏幕里，
        // 擅自居中只会产生跳动感
        body.addView(stopStrip(d, if (keepX <= 0) 1 else 0))
        // 时刻表占个位：数据回来只填这里，不重排整页
        val slot = LinearLayout(this)
        ttSlot = slot
        slot.orientation = LinearLayout.VERTICAL
        body.addView(slot)
        body.addView(sectionTitle("全部站点 · 点站名切换候车站"))
        body.addView(stationListCard(d))
        if (ttKey != lineId + "#" + d.line.direction) tt = null
        fillTimetableSlot()
        if (keepY > 0 && pageScroll != null) {
            pageScroll!!.post { pageScroll!!.scrollTo(0, keepY) }
        }
        if (keepX > 0 && stripScroll != null) {
            stripScroll!!.post { stripScroll!!.scrollTo(keepX, 0) }
        }
    }

    /** 点站名：没选中就切换候车站（本页原地换），已选中就问要不要去看站点详情。 */
    private fun onStopTap(s: BusModels.Stop) {
        if (isPicked(s)) {
            // 虚拟站没有物理站，跳过去也查不到东西，直接说明白
            if (s.physicalId.isEmpty()) {
                ConfirmDialog.alert(
                    this, "这一站是附带小站",
                    "上游只给了站名，没有独立的站点详情；候车站已经切到它了。"
                )
            } else {
                askOpenStop(s)
            }
            return
        }
        targetOrder = s.order
        selectedOrder = s.order
        selectedStopId = s.id
        stationId = s.id
        fetchLine(true, true)
    }

    /** 当前候车站是不是这一站。站序可能重复（999 的虚拟站组），优先按 id 认。 */
    private fun isPicked(s: BusModels.Stop): Boolean {
        if (selectedStopId.isNotEmpty()) return selectedStopId == s.id
        return s.order == selectedOrder
    }

    private fun askOpenStop(s: BusModels.Stop) {
        ConfirmDialog.show(
            this,
            "跳转到「" + s.name + "」站点详情？",
            "可以看到途经这一站的全部线路，以及每趟车还有几站到。",
            "留在本页", "跳转",
            { openStop(this, s.toStation(), cityId, lat, lng) }
        )
    }

    /** 换向：反方向线路 id 上游已经给了，本页重新拉一次即可。 */
    private fun swapDirection() {
        val d = detail
        if (d == null || !d.reversible()) return
        lineId = d.reverseId
        direction = 1 - direction
        targetOrder = 0
        selectedOrder = -1
        selectedStopId = ""
        fetchLine(true, false)
    }

    /** 头部：候车站 + 还有几分钟 + 站数/距离，右侧换向。 */
    // ─────────────── 线路详情 · 主卡（站点到站 / 全部车辆 / 单车跟随） ───────────────
    //
    // 三态与文案逐字对齐追客（github.com/5151561/Trackr，MIT）：
    //   · 站点卡：候车站胶囊 + 大字到站 +「还有 1 站 · 31512070 · 距本站 1.2公里 · 22:46 到 · 3 km/h」
    //             + 分隔线 +「下一辆 28 分钟 · 12 站」+「全线 2 辆车在途 · 查看」
    //   · 车辆列表：「到「丰润区车场」的全部车辆」+「共 2 辆」，每辆一行，右侧是到站徽标
    //   · 单车跟随：「单车跟随 · 31512055」+「开往 丰润区车场」+ 距终点 / 时速 / 到终点预测
    //             + 「全线途经站行程流：」+ 逐站时间轴（已过 / 正在接近 / 下一站）
    // 卡片钉在滚动区上方：切面板只换卡片里的内容（淡入 + 高度动画），页面本身一动不动。

    private fun renderHero() {
        val d = detail ?: return
        val host = heroHost ?: return
        val key = when (panel) {
            PANEL_BUS_LIST -> "B|" + cityId + "|" + lineId + "|" + direction
            PANEL_BUS_FOLLOW -> "F|" + cityId + "|" + lineId + "|" + direction + "|" + followedBusId
            else -> "S|" + cityId + "|" + lineId + "|" + direction
        }
        // 第一次上屏、换面板、换线、换车才播动画；同一张卡的例行刷新原地换字
        val animate = key != heroKey || host.childCount != 1
        heroKey = key
        val v = when (panel) {
            PANEL_BUS_LIST -> busListCard(d)
            PANEL_BUS_FOLLOW -> followCard(d)
            else -> stationCard(d)
        }
        swapHero(host, v, animate)
        refreshTrackBtn()
    }

    /**
     * 换掉主卡内容：新卡淡入，高度用 200ms 从旧卡走过去（结束后交回 wrap_content）。
     * 整页只有卡片自己在长高/缩矮，下面的滚动区不动 —— 和追客的 AnimatedContent 同一个观感。
     *
     * @param animate false = 同一张卡的例行刷新：直接换、不淡入、不做高度动画
     */
    private fun swapHero(host: FrameLayout, v: View, animate: Boolean) {
        val old = if (host.childCount > 0) host.getChildAt(0) else null
        val w = host.width - host.paddingLeft - host.paddingRight
        if (old == null || w <= 0 || !animate) {
            heroAnim?.cancel()
            val p = host.layoutParams
            if (p.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                p.height = ViewGroup.LayoutParams.WRAP_CONTENT
                host.layoutParams = p
            }
            host.removeAllViews()
            host.addView(
                v, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            v.alpha = 1f
            return
        }
        // 先把新卡量出来，拿到高度动画的终点
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val from = host.height
        val to = v.measuredHeight
        host.removeAllViews()
        host.addView(
            v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        if (from > 0 && to > 0 && from != to) {
            val lp = host.layoutParams
            lp.height = from
            host.layoutParams = lp
            heroAnim?.cancel()
            val a = ValueAnimator.ofInt(from, to)
            a.duration = 200
            a.interpolator = android.view.animation.DecelerateInterpolator()
            a.addUpdateListener { va ->
                val h = va.animatedValue as Int
                val p = host.layoutParams
                if (p.height != h) {
                    p.height = h
                    host.layoutParams = p
                }
            }
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val p = host.layoutParams
                    p.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    host.layoutParams = p
                }
            })
            a.start()
            heroAnim = a
        }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(200).setStartDelay(30).start()
    }

    /** 主卡外壳：和全站卡片同一套白底、18dp 圆角，不另加阴影，和别的卡片齐平。 */
    private fun heroCard(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = getDrawable(R.drawable.card_bg)
        c.setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10f)
        c.layoutParams = lp
        return c
    }

    // ── 线路信息：方向 / 运营时间 / 票价 / 全程站数 ──

    /**
     * 线路信息卡。放在主卡下面、站线上面：先回答「车什么时候到」，再交代「这是哪条线、
     * 开往哪儿、几点到几点、多少钱」。
     *
     * 换向按钮就贴在「方向」这一行右边 —— 它改的就是方向，放底栏没人知道它是干嘛的。
     */
    private fun lineInfoCard(d: BusModels.LineDetail): View {
        val c = card()
        c.setPadding(dp(16f), dp(13f), dp(16f), dp(13f))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val lab = TextView(this)
        lab.text = "方向"
        lab.textSize = 11f
        lab.setTextColor(getColor(R.color.text_tertiary))
        col.addView(lab)
        val dir = TextView(this)
        val from = d.line.startName
        val to = d.line.endName
        dir.text = when {
            from.isNotEmpty() && to.isNotEmpty() -> "$from → $to"
            to.isNotEmpty() -> "开往 $to"
            from.isNotEmpty() -> "开往 $from"
            else -> "—"
        }
        dir.textSize = 15f
        dir.typeface = Typeface.DEFAULT_BOLD
        dir.setTextColor(getColor(R.color.text_primary))
        dir.setPadding(0, dp(3f), 0, 0)
        dir.maxLines = 1
        dir.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(dir)
        top.addView(
            col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (d.reversible()) {
            val pill = swapPill()
            val plp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            plp.leftMargin = dp(10f)
            top.addView(pill, plp)
            swapBtn = pill
        }
        c.addView(top)

        val dlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))
        )
        dlp.topMargin = dp(11f)
        dlp.bottomMargin = dp(10f)
        val div = View(this)
        div.setBackgroundColor(getColor(R.color.border_subtle))
        c.addView(div, dlp)

        val cells = LinearLayout(this)
        cells.orientation = LinearLayout.HORIZONTAL
        val first = if (d.line.firstTime.isEmpty()) "" else d.line.firstTime
        val last = if (d.line.lastTime.isEmpty()) "" else d.line.lastTime
        val hours = when {
            first.isNotEmpty() && last.isNotEmpty() -> "$first - $last"
            first.isNotEmpty() -> "$first 起"
            last.isNotEmpty() -> "至 $last"
            else -> "—"
        }
        cells.addView(infoCell("运营时间", hours))
        cells.addView(infoCell("票价", if (d.line.price.isEmpty()) "—" else d.line.price))
        val total = if (d.line.stationsNum > 0) d.line.stationsNum else d.stops.size
        cells.addView(infoCell("全程", if (total > 0) "$total 站" else "—"))
        c.addView(cells)
        return c
    }

    /** 信息卡里的一栏：上面灰色小标签，下面深色数值，左对齐。 */
    private fun infoCell(label: String, value: String): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l = TextView(this)
        l.text = label
        l.textSize = 11f
        l.setTextColor(getColor(R.color.text_tertiary))
        col.addView(l)
        val v = TextView(this)
        v.text = value
        v.textSize = 14f
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(getColor(R.color.text_primary))
        v.setPadding(0, dp(3f), 0, 0)
        v.maxLines = 1
        v.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(v)
        return col
    }

    /** 换向：品牌色描边 + 淡底 + 图标文字，和顶栏「收藏」区分开，一眼看出能点。 */
    private fun swapPill(): TextView {
        val t = TextView(this)
        t.text = "⇄  换向"
        t.textSize = 13f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setTextColor(getColor(R.color.brand))
        t.background = RippleDrawable(
            ColorStateList.valueOf(0x1F0A84FF),
            outlineBox(getColor(R.color.brand), getColor(R.color.brand_soft), dp(16f)),
            null
        )
        t.setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        t.isClickable = true
        t.setOnClickListener { swapDirection() }
        pressScale(t)
        return t
    }

    // ── 状态一：站点到站 ──

    private fun stationCard(d: BusModels.LineDetail): View {
        val c = heroCard()
        val order = if (selectedOrder > 0) selectedOrder else d.waitingOrder
        val name = if (d.waitingStopName.isEmpty()) "选择候车站" else d.waitingStopName

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val chip = TextView(this)
        chip.text = name
        chip.textSize = 15f
        chip.typeface = Typeface.DEFAULT_BOLD
        chip.setTextColor(getColor(R.color.brand))
        chip.background = roundRect(getColor(R.color.brand_soft), dp(8f))
        chip.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        chip.maxWidth = resources.displayMetrics.widthPixels * 55 / 100
        chip.isSingleLine = true
        chip.ellipsize = android.text.TextUtils.TruncateAt.END
        // 点站名：把下面的站表滚到这一站，在那儿换站
        chip.setOnClickListener { scrollStationListTo(order) }
        top.addView(chip)

        top.addView(
            View(this), LinearLayout.LayoutParams(0, dp(1f), 1f)
        )
        if (order > 0) {
            val ord = TextView(this)
            ord.text = "第 $order 站"
            ord.textSize = 12f
            ord.setTextColor(getColor(R.color.text_tertiary))
            top.addView(ord)
        }
        c.addView(top)

        val head = arrivalHeadline(d)
        head.setPadding(0, dp(10f), 0, 0)
        c.addView(head)

        // 这行小字不管有没有内容都占住位置：拿不到车牌/距离时只是留白，
        // 卡片高度不变，下面那张卡片就不会跟着往上顶一下
        val facts = arrivalFacts(d, order)
        val f = TextView(this)
        f.text = if (facts.isEmpty()) " " else facts
        f.textSize = 13f
        f.setTextColor(getColor(R.color.text_secondary))
        f.setPadding(0, dp(4f), 0, 0)
        f.maxLines = 1
        f.ellipsize = android.text.TextUtils.TruncateAt.END
        c.addView(f)

        val dv = View(this)
        dv.setBackgroundColor(getColor(R.color.border_subtle))
        val dlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))
        )
        dlp.topMargin = dp(12f)
        dlp.bottomMargin = dp(10f)
        dv.layoutParams = dlp
        c.addView(dv)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        val next = TextView(this)
        next.text = followingText(d, order)
        next.textSize = 13f
        next.setTextColor(getColor(R.color.text_secondary))
        next.isSingleLine = true
        next.ellipsize = android.text.TextUtils.TruncateAt.END
        bottom.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val count = if (d.vehicles.isNotEmpty()) d.vehicles.size else d.busCount
        val look = TextView(this)
        look.text = if (count > 0) "全线 $count 辆车在途 · 查看" else "全线暂无在途车辆"
        look.textSize = 13f
        look.typeface = Typeface.DEFAULT_BOLD
        look.setTextColor(if (count > 0) getColor(R.color.brand) else getColor(R.color.text_tertiary))
        look.setPadding(dp(8f), dp(6f), 0, dp(6f))
        if (count > 0) look.setOnClickListener { openBusList() }
        bottom.addView(look)
        c.addView(bottom)
        return c
    }

    /** 大字：到站了 / N 分钟 / N 站 / 下一班 19:34 / 状态文案。 */
    private fun arrivalHeadline(d: BusModels.LineDetail): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM

        var value = ""
        var unit: String? = null
        var size = 40f
        var unitSize = 17f
        var tone = getColor(R.color.text_primary)

        when {
            d.etaArrived -> {
                value = "到站了"
                size = 28f
                tone = toneGo()
            }
            d.etaMinutes == 0 -> {
                value = "到站了"
                size = 28f
                tone = toneGo()
            }
            d.etaMinutes in 1..59 -> {
                value = d.etaMinutes.toString()
                unit = "分钟"
                tone = if (d.etaMinutes <= 3) toneGo() else getColor(R.color.text_primary)
            }
            d.etaMinutes >= 60 -> {
                value = BusModels.minutesText(d.etaMinutes)
                size = 28f
            }
            d.etaStops > 0 -> {
                value = d.etaStops.toString()
                unit = "站"
                tone = if (d.etaStops <= 1) toneGo() else getColor(R.color.text_primary)
            }
            d.estArrival.isNotEmpty() -> {
                value = "下一班 " + d.estArrival
                size = 24f
                tone = getColor(R.color.text_secondary)
            }
            else -> {
                value = if (d.status.isEmpty()) "暂无预测" else d.status
                size = if (d.status.length > 8) 16f else 22f
                tone = getColor(R.color.text_secondary)
            }
        }

        val big = TextView(this)
        big.text = value
        big.textSize = size
        big.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        big.setTextColor(tone)
        row.addView(big)
        if (unit != null) {
            val u = TextView(this)
            u.text = unit
            u.textSize = unitSize
            u.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            u.setTextColor(tone)
            u.setPadding(dp(4f), 0, 0, dp(6f))
            row.addView(u)
        }
        return row
    }

    /** 大字下面那行小字：快去上车 / 还有几站 · 车牌 · 距本站 · 几点到 · 时速（拿不到的不写）。 */
    private fun arrivalFacts(d: BusModels.LineDetail, order: Int): String {
        val bits = ArrayList<String>()
        if (d.etaArrived) bits.add("快去上车")
        val v = nearestVehicle(d, order)
        if (v != null) {
            if (!d.etaArrived && order > 0 && v.order > 0) {
                val left = order - v.order
                bits.add(if (left <= 0) "下一站就到" else "还有 $left 站")
            }
            if (v.plate.isNotEmpty()) bits.add(v.plate)
            val dist = BusText.distanceText(v.distanceMeters)
            if (dist.isNotEmpty()) bits.add("距本站 " + dist)
            val clock = BusText.clockAfter(v.etaSeconds, System.currentTimeMillis())
            if (clock.isNotEmpty()) bits.add(clock + " 到")
            if (v.speedKmh > 0) bits.add(v.speedKmh.toInt().toString() + " km/h")
        }
        if (bits.isEmpty()) {
            val detail = d.etaDetail()
            if (detail.isNotEmpty()) bits.add(detail)
        }
        return bits.joinToString(" · ")
    }

    /** 「下一辆 28 分钟 · 12 站」；只有一样就只说一样；都没有就「后面还有车」。 */
    private fun followingText(d: BusModels.LineDetail, order: Int): String {
        val v = nearestVehicle(d, order) ?: return ""
        val rest = ArrayList<BusModels.Vehicle>()
        for (x in d.vehicles) {
            if (x !== v) rest.add(x)
        }
        var following: BusModels.Vehicle? = null
        for (x in rest) {
            if (order > 0 && x.order > order) continue
            if (following == null || x.order > following!!.order) following = x
        }
        val f = following ?: return ""
        val mins = if (f.etaSeconds > 0) Math.round(f.etaSeconds / 60.0).toInt().coerceAtLeast(1) else null
        val stops = if (order > 0 && f.order > 0 && order - f.order >= 0) order - f.order else null
        return when {
            mins != null && stops != null -> "下一辆 $mins 分钟 · $stops 站"
            mins != null -> "下一辆 $mins 分钟"
            stops != null -> "下一辆还有 $stops 站"
            else -> "后面还有车"
        }
    }

    // ── 状态二：到「候车站」的全部车辆 ──

    private fun busListCard(d: BusModels.LineDetail): View {
        val c = heroCard()
        val order = if (selectedOrder > 0) selectedOrder else d.waitingOrder
        val name = if (d.waitingStopName.isEmpty()) "当前站" else d.waitingStopName

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(backChip())
        val title = TextView(this)
        title.text = "到「" + name + "」的全部车辆"
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(getColor(R.color.text_primary))
        title.maxLines = 2
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dp(4f)
        top.addView(title, tlp)
        val cnt = TextView(this)
        cnt.text = "共 " + d.vehicles.size + " 辆"
        cnt.textSize = 13f
        cnt.setTextColor(getColor(R.color.text_secondary))
        top.addView(cnt)
        c.addView(top)

        if (d.vehicles.isEmpty()) {
            val empty = TextView(this)
            empty.text = "当前线路上暂无在途运营车辆"
            empty.textSize = 14f
            empty.setTextColor(getColor(R.color.text_secondary))
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, dp(24f), 0, dp(24f))
            c.addView(empty)
            return c
        }

        // 排序和追客一致：还没过本站的按到站时间升序在前，已过站的沉底
        val sorted = d.vehicles.sortedWith(
            compareBy<BusModels.Vehicle> { if (order > 0 && it.order > order) 1 else 0 }
                .thenBy { if (it.etaSeconds > 0) it.etaSeconds else Int.MAX_VALUE }
                .thenBy { order - it.order }
        )
        for (v in sorted) {
            val row = busRow(v, order)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8f)
            c.addView(row, lp)
        }
        return c
    }

    /** 车辆列表里的一行：圆标 + 车牌/时速 + 状态文字 + 到站徽标 + 到达时刻 + ›。 */
    private fun busRow(v: BusModels.Vehicle, order: Int): View {
        val passed = order > 0 && v.order > order
        val atStop = order > 0 && v.order == order && v.isArrived
        val stopsLeft = if (order > 0 && v.order > 0) order - v.order else -1

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = RippleDrawable(
            ColorStateList.valueOf(0x0F000000),
            outlineBox(getColor(R.color.border_subtle), getColor(R.color.bg_surface), dp(12f)),
            null
        )
        row.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        row.isClickable = true
        row.setOnClickListener { openFollow(v) }
        pressScale(row)

        val icon = FrameLayout(this)
        val circle = View(this)
        circle.background = roundRect(
            if (passed) getColor(R.color.bg_sunken) else getColor(R.color.brand_soft), dp(18f)
        )
        icon.addView(circle, FrameLayout.LayoutParams(dp(36f), dp(36f)))
        val busImg = ImageView(this)
        busImg.setImageResource(R.drawable.ic_quick_bus)
        busImg.setColorFilter(if (passed) getColor(R.color.text_tertiary) else getColor(R.color.brand))
        icon.addView(busImg, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
        row.addView(icon, LinearLayout.LayoutParams(dp(36f), dp(36f)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.setPadding(dp(12f), 0, 0, 0)
        val l1 = LinearLayout(this)
        l1.orientation = LinearLayout.HORIZONTAL
        l1.gravity = Gravity.CENTER_VERTICAL
        val plate = TextView(this)
        plate.text = if (v.plate.isNotEmpty()) v.plate else when {
            passed -> "已过站车辆"
            atStop -> "已到站车辆"
            else -> "在途车辆"
        }
        plate.textSize = 15f
        plate.typeface = Typeface.DEFAULT_BOLD
        plate.setTextColor(if (passed) getColor(R.color.text_tertiary) else getColor(R.color.text_primary))
        plate.isSingleLine = true
        plate.ellipsize = android.text.TextUtils.TruncateAt.END
        l1.addView(plate)
        if (!passed && v.speedKmh > 0) {
            val sp = TextView(this)
            sp.text = v.speedKmh.toInt().toString() + " km/h"
            sp.textSize = 11f
            sp.setTextColor(getColor(R.color.text_secondary))
            sp.setPadding(dp(6f), 0, 0, 0)
            l1.addView(sp)
        }
        mid.addView(l1)

        val st = TextView(this)
        st.text = when {
            passed -> "已过该站"
            atStop -> "已到站"
            stopsLeft > 0 -> "还有 $stopsLeft 站 · 正在接近第 " + v.order + " 站"
            else -> "正在进站"
        }
        st.textSize = 12f
        st.setTextColor(if (passed) getColor(R.color.text_tertiary) else getColor(R.color.text_secondary))
        st.setPadding(0, dp(2f), 0, 0)
        mid.addView(st)
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        val hot = atStop || (!passed && stopsLeft == 1)
        val badgeText = when {
            passed -> "已过站"
            atStop -> "已到站"
            v.etaSeconds > 0 -> BusText.etaText(v.etaSeconds, false)
            BusText.distanceText(v.distanceMeters).isNotEmpty() -> "距本站 " + BusText.distanceText(v.distanceMeters)
            else -> "暂无预测"
        }
        right.addView(statusBadge(badgeText, hot && !passed))
        if (!passed && v.etaSeconds > 0) {
            val clock = BusText.clockAfter(v.etaSeconds, System.currentTimeMillis())
            if (clock.isNotEmpty()) {
                val t = TextView(this)
                t.text = clock + " 到"
                t.textSize = 11f
                t.setTextColor(getColor(R.color.text_secondary))
                t.setPadding(0, dp(2f), 0, 0)
                right.addView(t)
            }
        }
        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rlp.leftMargin = dp(8f)
        row.addView(right, rlp)

        val chev = TextView(this)
        chev.text = "›"
        chev.textSize = 18f
        chev.setTextColor(getColor(R.color.text_tertiary))
        chev.setPadding(dp(4f), 0, 0, 0)
        row.addView(chev)
        return row
    }

    // ── 状态三：单车跟随 ──

    private fun followCard(d: BusModels.LineDetail): View {
        val c = heroCard()
        val v = followedVehicle(d)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(backChip())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "单车跟随 · " + if (v != null && v.plate.isNotEmpty()) v.plate else "指定车辆"
        title.textSize = 17f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(getColor(R.color.text_primary))
        title.isSingleLine = true
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(title)
        val heading = TextView(this)
        heading.text = "开往  " + (if (d.line.endName.isEmpty()) "终点站" else d.line.endName)
        heading.textSize = 12f
        heading.setTextColor(getColor(R.color.text_secondary))
        col.addView(heading)
        val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        clp.leftMargin = dp(4f)
        top.addView(col, clp)
        c.addView(top)

        if (v == null) {
            val gone = TextView(this)
            gone.text = "这辆车已不在线上（可能已收班或已到达终点）。"
            gone.textSize = 13f
            gone.setTextColor(getColor(R.color.text_secondary))
            gone.gravity = Gravity.CENTER
            gone.setPadding(dp(8f), dp(24f), dp(8f), dp(24f))
            c.addView(gone)
            return c
        }

        val toTerminus = if (d.stops.isNotEmpty()) Math.max(0, d.stops.size - v.order) else 0
        val summary = LinearLayout(this)
        summary.orientation = LinearLayout.HORIZONTAL
        summary.background = roundRect(getColor(R.color.bg_sunken), dp(12f))
        summary.setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        val slp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(10f)
        summary.layoutParams = slp

        summary.addView(summaryColumn("距终点", toTerminus.toString() + " 站", null))
        summary.addView(
            summaryColumn(
                "时速",
                if (v.speedKmh > 0) v.speedKmh.toInt().toString() + " km/h" else "—",
                null
            )
        )
        summary.addView(
            summaryColumn(
                "到终点预测",
                null,
                if (v.etaSeconds > 0) BusText.etaText(v.etaSeconds, false) else "无预测"
            )
        )
        c.addView(summary)

        val dist = BusText.distanceText(v.distanceMeters)
        if (dist.isNotEmpty()) {
            val t = TextView(this)
            t.text = "距候车站 " + dist
            t.textSize = 13f
            t.setTextColor(getColor(R.color.text_secondary))
            t.setPadding(0, dp(8f), 0, 0)
            c.addView(t)
        }

        val flow = TextView(this)
        flow.text = "全线途经站行程流："
        flow.textSize = 13f
        flow.typeface = Typeface.DEFAULT_BOLD
        flow.setTextColor(getColor(R.color.text_secondary))
        flow.setPadding(0, dp(12f), 0, dp(2f))
        c.addView(flow)

        val sc = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        sc.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        c.addView(sc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(240f)
        ))

        val last = d.stops.size - 1
        for (i in d.stops.indices) {
            list.addView(flowRow(d.stops[i], v, i == 0, i == last))
        }
        // 默认把当前车所在的那一站滚到中间偏上，跟追客一致
        val idx = Math.max(0, v.order - 3)
        sc.post { sc.scrollTo(0, idx * dp(36f)) }
        return c
    }

    /** 概览三列：一列标签 + 一行值（值可能是徽标）。 */
    private fun summaryColumn(label: String, value: String?, badge: String?): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l = TextView(this)
        l.text = label
        l.textSize = 11f
        l.setTextColor(getColor(R.color.text_tertiary))
        col.addView(l)
        if (value != null) {
            val t = TextView(this)
            t.text = value
            t.textSize = 15f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(getColor(R.color.text_primary))
            t.setPadding(0, dp(3f), 0, 0)
            col.addView(t)
        }
        if (badge != null) {
            val b = statusBadge(badge, true)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(2f)
            col.addView(b, lp)
        }
        return col
    }

    /** 行程流里的一行：竖轨 + 节点 + 站序 + 站名 + 右侧状态。 */
    private fun flowRow(s: BusModels.Stop, v: BusModels.Vehicle, first: Boolean, last: Boolean): View {
        val passed = s.order < v.order
        val current = s.order == v.order
        val next = s.order == v.order + 1

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(36f)

        val rail = FrameLayout(this)
        val lineColor = if (s.order <= v.order) alphaOf(getColor(R.color.text_tertiary), 0.35f)
        else getColor(R.color.brand)
        rail.addView(
            TimelineRail(this, first, last, lineColor),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        if (current) {
            val ring = FrameLayout(this)
            ring.background = roundRect(toneGo(), dp(10f))
            val ic = ImageView(this)
            ic.setImageResource(R.drawable.ic_quick_bus)
            ic.setColorFilter(android.graphics.Color.WHITE)
            ring.addView(ic, FrameLayout.LayoutParams(dp(12f), dp(12f), Gravity.CENTER))
            rail.addView(ring, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
        } else {
            val dot = View(this)
            dot.background = roundRect(
                if (passed) alphaOf(getColor(R.color.text_tertiary), 0.45f) else getColor(R.color.brand),
                dp(4f)
            )
            rail.addView(dot, FrameLayout.LayoutParams(dp(8f), dp(8f), Gravity.CENTER))
        }
        row.addView(rail, LinearLayout.LayoutParams(dp(28f), ViewGroup.LayoutParams.MATCH_PARENT))

        val ord = TextView(this)
        ord.text = (if (s.orderLabel.isEmpty()) s.order.toString() else s.orderLabel)
        ord.textSize = 11f
        ord.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        ord.setTextColor(
            when {
                current -> toneGo()
                passed -> alphaOf(getColor(R.color.text_tertiary), 0.6f)
                else -> getColor(R.color.brand)
            }
        )
        val olp = LinearLayout.LayoutParams(dp(30f), ViewGroup.LayoutParams.WRAP_CONTENT)
        olp.leftMargin = dp(6f)
        row.addView(ord, olp)

        val nm = TextView(this)
        nm.text = s.name
        nm.textSize = if (current) 14f else 13f
        nm.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        nm.setTextColor(
            when {
                current -> toneGo()
                passed -> getColor(R.color.text_tertiary)
                else -> getColor(R.color.text_primary)
            }
        )
        nm.isSingleLine = true
        nm.ellipsize = android.text.TextUtils.TruncateAt.END
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.rightMargin = dp(6f)
        row.addView(nm, nlp)

        when {
            current -> row.addView(statusBadge(if (v.isArrived) "已到站停靠" else "正在接近", true))
            next -> row.addView(statusBadge("下一站", false))
            passed -> {
                val p = TextView(this)
                p.text = "已过"
                p.textSize = 11f
                p.setTextColor(alphaOf(getColor(R.color.text_tertiary), 0.6f))
                p.setPadding(0, 0, dp(4f), 0)
                row.addView(p)
            }
        }
        return row
    }

    // ── 主卡共用小件 ──

    private fun backChip(): View {
        val tv = TextView(this)
        tv.text = "←"
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(getColor(R.color.text_secondary))
        tv.background = RippleDrawable(
            ColorStateList.valueOf(0x0F000000), roundRect(getColor(R.color.bg_sunken), dp(16f)), null
        )
        tv.layoutParams = LinearLayout.LayoutParams(dp(32f), dp(32f))
        tv.setOnClickListener { backToStation() }
        return tv
    }

    /** 到站徽标：品牌浅底 + 品牌字；普通态用浅灰底。 */
    private fun statusBadge(text: String, hot: Boolean): View {
        val v = TextView(this)
        v.text = text
        v.textSize = 11.5f
        v.typeface = Typeface.DEFAULT_BOLD
        v.gravity = Gravity.CENTER
        v.setTextColor(if (hot) getColor(R.color.brand) else getColor(R.color.text_secondary))
        v.background = roundRect(
            if (hot) getColor(R.color.brand_soft) else getColor(R.color.bg_sunken), dp(8f)
        )
        v.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        return v
    }

    /** 带描边的圆角盒子（车辆行外框）。 */
    private fun outlineBox(stroke: Int, fill: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = radius.toFloat()
        g.setStroke(Math.max(1, dp(1f)), stroke)
        return g
    }

    /** 按下轻微内缩，松手弹回 —— 卡片上的行点起来有反馈，但不抢点击。 */
    private fun pressScale(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(110).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun alphaOf(color: Int, f: Float): Int {
        val a = Math.round(android.graphics.Color.alpha(color) * f)
        return android.graphics.Color.argb(
            a, android.graphics.Color.red(color),
            android.graphics.Color.green(color), android.graphics.Color.blue(color)
        )
    }

    /** 到站大字的「绿」：本站有车、马上到，和追客同一个语义色位。 */
    private fun toneGo(): Int = getColor(R.color.course_2)

    // ── 三态切换 ──

    private fun openBusList() {
        panel = PANEL_BUS_LIST
        followedBusId = ""
        renderHero()
    }

    private fun openFollow(v: BusModels.Vehicle) {
        followedBusId = v.id
        panel = PANEL_BUS_FOLLOW
        renderHero()
    }

    private fun backToStation() {
        if (panel == PANEL_STATION) return
        panel = PANEL_STATION
        followedBusId = ""
        renderHero()
    }

    /** 离候车站最近、还没开过去的那辆车。 */
    private fun nearestVehicle(d: BusModels.LineDetail, order: Int): BusModels.Vehicle? {
        var best: BusModels.Vehicle? = null
        for (v in d.vehicles) {
            if (order > 0 && v.order > order) continue
            if (best == null || v.order > best!!.order) best = v
        }
        return best
    }

    private fun followedVehicle(d: BusModels.LineDetail): BusModels.Vehicle? {
        for (v in d.vehicles) {
            if (v.id == followedBusId) return v
        }
        for (v in d.vehicles) {
            if (v.plate.isNotEmpty() && v.plate == followedBusId) return v
        }
        return null
    }

    /** 点胶囊：把「全部站点」那一块滚到这一站附近（页面自己不动，只动滚动区）。 */
    private fun scrollStationListTo(order: Int) {
        if (order <= 0) return
        val d = detail ?: return
        var index = -1
        for (i in d.stops.indices) {
            if (d.stops[i].order == order) {
                index = i
                break
            }
        }
        if (index < 0) return
        val sc = pageScroll ?: return
        // 站表在页面下半部分，从顶往下估：站线（约 108dp）+ 标题 + 时刻表槽
        val y = dp(210f) + index * dp(46f)
        sc.smoothScrollTo(0, y)
    }

    // ── 底栏：乘车码（换向在「线路信息」卡里，那儿才是它该待的地方） ──

    private fun bottomBar(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(getColor(R.color.bg_surface))
        wrap.elevation = dp(8f).toFloat()
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16f), dp(10f), dp(16f), dp(10f))

        // 乘车码是这一页最常用的动作：整条占满，图标 + 文字（和追客的 FAB 一个形状）
        val code = LinearLayout(this)
        code.orientation = LinearLayout.HORIZONTAL
        code.gravity = Gravity.CENTER
        code.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF), roundRect(getColor(R.color.brand), dp(14f)), null
        )
        code.isClickable = true
        code.setOnClickListener { openTransitCode() }
        code.setOnLongClickListener {
            pickTransitCode()
            true
        }
        codeBtn = code
        val icon = qrGlyph()
        code.addView(icon, LinearLayout.LayoutParams(dp(20f), dp(20f)))
        val lab = TextView(this)
        lab.text = "乘车码"
        lab.textSize = 15f
        lab.typeface = Typeface.DEFAULT_BOLD
        lab.setTextColor(android.graphics.Color.WHITE)
        lab.setPadding(dp(8f), 0, 0, 0)
        code.addView(lab)
        row.addView(code, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)
        ))

        wrap.addView(row)
        return wrap
    }

    /** 乘车码小图标：三个定位方块 + 几个点，几何画出来，不用图片资源。 */
    /** 乘车码图标：画在品牌色按钮上，所以用白色。 */
    private fun qrGlyph(): View = QrGlyph(this, android.graphics.Color.WHITE)

    private fun toggleTrack() {
        val d = detail
        val f = BusStore.Fav()
        f.cityId = cityId
        f.lineId = lineId
        f.name = lineName
        f.direction = direction
        if (d != null) {
            f.startName = d.line.startName
            f.endName = d.line.endName
        }
        val on = store.toggle(f)
        refreshTrackBtn()
        android.widget.Toast.makeText(
            this, if (on) "已收藏，可在首页查看" else "已取消收藏",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** 顶栏「收藏」跟着 store 走：收藏就是加进首页常用，不再有第二个入口。 */
    private fun refreshTrackBtn() {
        favBtn?.text = if (store.isFavorite(cityId, lineId)) "已收藏" else "收藏"
    }

    private fun openTransitCode() {
        val id = store.transitCode()
        if (TransitCode.ASK == id) {
            pickTransitCode()
            return
        }
        if (!TransitCode.open(this, id)) pickTransitCode()
    }

    private fun pickTransitCode() {
        TransitCodeSheet.show(this, store.transitCode()) { id, remember ->
            if (remember) store.setTransitCode(id)
            TransitCode.open(this, id)
        }
    }

    /**
     * 行程流左侧的竖轨：上半段接上一站，下半段接下一站，首末站各截掉一半。
     * 必须是 static 嵌套类（d8 不允许非静态内部类），所以颜色都由外面传进来。
     */
    private class TimelineRail(
        c: Context,
        private val first: Boolean,
        private val last: Boolean,
        private val color: Int
    ) : View(c) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val mid = height / 2f
            paint.color = color
            paint.strokeWidth = 2f * resources.displayMetrics.density
            if (!first) canvas.drawLine(cx, 0f, cx, mid, paint)
            if (!last) canvas.drawLine(cx, mid, cx, height.toFloat(), paint)
        }
    }

    /**
     * 乘车码图标。几何逐格照搬追客用的 material `ic_qr_code`（24×24 视口）：
     * 三个 8×8 的定位方块（2 单位粗的方环，中间 4×4 挖空）+ 右下 8 个 2×2 码点。
     * 只把填充色换成本 App 的品牌色/白色，形状不再自己乱画。
     */
    private class QrGlyph(c: Context, private val color: Int) : View(c) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = Path()

        override fun onDraw(canvas: Canvas) {
            val u = Math.min(width, height).toFloat() / 24f
            if (u <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = color
            // 左上、右上、左下三个定位方块
            finder(canvas, 3f * u, 3f * u, u)
            finder(canvas, 13f * u, 3f * u, u)
            finder(canvas, 3f * u, 13f * u, u)
            // 右下 8 个码点（坐标取自 ic_qr_code 的 pathData）
            for (i in DOTS.indices step 2) {
                val x = DOTS[i] * u
                val y = DOTS[i + 1] * u
                canvas.drawRect(x, y, x + 2f * u, y + 2f * u, paint)
            }
        }

        /** 8×8 方环：外框实心 + 内框挖空（EVEN_ODD 让中间不填色）。 */
        private fun finder(canvas: Canvas, x: Float, y: Float, u: Float) {
            rect.reset()
            rect.fillType = Path.FillType.EVEN_ODD
            rect.addRect(x, y, x + 8f * u, y + 8f * u, Path.Direction.CW)
            rect.addRect(x + 2f * u, y + 2f * u, x + 6f * u, y + 6f * u, Path.Direction.CW)
            canvas.drawPath(rect, paint)
        }

        companion object {
            /** 码点左上角坐标（单位 = 24 分之一个图标边长），x,y 成对。 */
            private val DOTS = floatArrayOf(
                13f, 13f, 15f, 15f, 13f, 17f, 17f, 17f,
                19f, 19f, 15f, 19f, 17f, 13f, 19f, 15f
            )
        }
    }

    // ───────────────────── 首站发车时刻表 ─────────────────────

    /**
     * 时刻表卡片：下一班几点发、从哪儿发、多久一班，点「完整时刻表」看全天。
     * 放在候车站卡片下面，是因为「这站的车还有几分钟」和「首站下一班几点发」
     * 回答的是两个不同的问题——车还没发出来时，只有后者能看。
     */
    private fun timetableCard(t: BusModels.Timetable): View {
        val c = card()
        c.setPadding(dp(16f), dp(13f), dp(16f), dp(13f))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = "首站发车"
        title.textSize = 13f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(getColor(R.color.text_primary))
        head.addView(title)

        val from = TextView(this)
        from.text = " " + (if (t.startName.isEmpty()) "" else t.startName)
        from.textSize = 11.5f
        from.setTextColor(getColor(R.color.text_tertiary))
        head.addView(from)

        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val more = TextView(this)
        more.text = "完整时刻表 ›"
        more.textSize = 12.5f
        more.setTextColor(getColor(R.color.brand))
        more.setPadding(dp(8f), dp(4f), 0, dp(4f))
        more.isClickable = true
        more.setOnClickListener { showTimetableSheet() }
        head.addView(more)
        c.addView(head)

        val big = LinearLayout(this)
        big.orientation = LinearLayout.HORIZONTAL
        big.gravity = Gravity.BOTTOM
        big.setPadding(0, dp(8f), 0, 0)

        val num = TextView(this)
        if (t.nextDep.isEmpty()) {
            num.text = "今日已收班"
            num.textSize = 20f
            num.setTextColor(getColor(R.color.text_secondary))
        } else {
            num.text = t.nextDep
            num.textSize = 26f
            num.setTextColor(getColor(R.color.brand))
        }
        num.typeface = Typeface.DEFAULT_BOLD
        big.addView(num)

        if (t.nextDep.isNotEmpty() && t.nextFrom.isNotEmpty()) {
            val src = TextView(this)
            var wait = ""
            val nowMin = nowMinutes()
            val dep = BusModels.minutesOf(t.nextDep)
            if (nowMin >= 0 && dep >= 0 && dep >= nowMin && dep - nowMin <= 120) {
                wait = "还有 " + (dep - nowMin) + " 分钟发车"
            }
            src.text = if (wait.isEmpty()) "  下一班 · " + t.nextFrom
            else "  " + wait + " · " + t.nextFrom
            src.textSize = 11.5f
            src.setTextColor(getColor(R.color.text_tertiary))
            src.setPadding(0, 0, 0, dp(4f))
            big.addView(src)
        }
        c.addView(big)

        val meta = ArrayList<String>()
        if (t.firstDep.isNotEmpty() || t.lastDep.isNotEmpty()) {
            meta.add("首班 " + t.firstDep + " · 末班 " + t.lastDep)
        }
        if (t.interval > 0) meta.add("约 " + t.interval + " 分钟一班")
        if (meta.isNotEmpty()) {
            val sub = TextView(this)
            sub.text = meta.joinToString(" · ")
            sub.textSize = 11.5f
            sub.setTextColor(getColor(R.color.text_tertiary))
            sub.setPadding(0, dp(3f), 0, 0)
            c.addView(sub)
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10f)
        c.layoutParams = lp
        return c
    }

    /** 完整时刻表：今日预测 + 昨日实际，从底部弹层里看。 */
    private fun showTimetableSheet() {
        val t = tt ?: return

        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18f), dp(2f), dp(18f), dp(20f))
        sv.addView(box)

        val name = TextView(this)
        val d = detail
        var head = if (d == null || d.line.name.isEmpty())
            displayLine(t.lineName) else displayLine(d.line.name)
        if (t.startName.isNotEmpty()) head += " · 首站 " + t.startName
        name.text = head
        name.textSize = 16f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(getColor(R.color.text_primary))
        box.addView(name)

        val sub = TextView(this)
        val bits = ArrayList<String>()
        if (t.firstDep.isNotEmpty() || t.lastDep.isNotEmpty()) {
            bits.add("首班 " + t.firstDep + " · 末班 " + t.lastDep)
        }
        if (t.interval > 0) bits.add("约 " + t.interval + " 分钟一班")
        sub.text = bits.joinToString(" · ")
        sub.textSize = 11.5f
        sub.setTextColor(getColor(R.color.text_tertiary))
        sub.setPadding(0, dp(4f), 0, dp(2f))
        box.addView(sub)

        if (t.today.isNotEmpty()) {
            box.addView(ttSection("今日发车预测", "还没发的班次，按现在算的", t.today, t.nextDep))
        }
        if (t.history.isNotEmpty()) {
            box.addView(ttSection("昨日实际班次", "昨天这一天的真实发车记录", t.history, ""))
        }
        if (t.tip.isNotEmpty()) {
            val tip = TextView(this)
            tip.text = t.tip
            tip.textSize = 11f
            tip.setTextColor(getColor(R.color.text_tertiary))
            tip.setPadding(0, dp(14f), 0, 0)
            box.addView(tip)
        }
        Sheet.show(this, sv, 0)
    }

    private fun ttSection(
        title: String,
        hint: String,
        segs: List<BusModels.TtSegment>,
        highlight: String
    ): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16f), 0, 0)

        val h = TextView(this)
        h.text = title
        h.textSize = 13.5f
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(getColor(R.color.text_primary))
        box.addView(h)

        if (hint.isNotEmpty()) {
            val t = TextView(this)
            t.text = hint
            t.textSize = 11f
            t.setTextColor(getColor(R.color.text_tertiary))
            t.setPadding(0, dp(2f), 0, 0)
            box.addView(t)
        }

        for (seg in segs) {
            val lab = seg.label()
            if (lab.isNotEmpty()) {
                val s = TextView(this)
                s.text = lab
                s.textSize = 11.5f
                s.setTextColor(getColor(R.color.text_secondary))
                s.setPadding(0, dp(12f), 0, dp(6f))
                box.addView(s)
            }
            box.addView(ttGrid(seg.times, highlight))
            if (seg.tips.isNotEmpty()) {
                val tp = TextView(this)
                tp.text = seg.tips
                tp.textSize = 11f
                tp.setTextColor(getColor(R.color.text_tertiary))
                tp.setPadding(0, dp(4f), 0, 0)
                box.addView(tp)
            }
        }
        return box
    }

    /** 时间网格：一行四个，像时刻表那样对齐着看。 */
    private fun ttGrid(times: List<String>, highlight: String): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        var row: LinearLayout? = null
        for (i in times.indices) {
            if (i % 4 == 0) {
                val r = LinearLayout(this)
                row = r
                r.orientation = LinearLayout.HORIZONTAL
                col.addView(r)
            }
            val hit = highlight.isNotEmpty() && highlight == times[i]
            val chip = TextView(this)
            chip.text = times[i]
            chip.textSize = 12.5f
            chip.gravity = Gravity.CENTER
            chip.typeface = if (hit) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            chip.setTextColor(if (hit) 0xFFFFFFFF.toInt() else getColor(R.color.text_primary))
            chip.background = roundRect(
                if (hit) getColor(R.color.brand)
                else getColor(R.color.bg_sunken), dp(8f)
            )
            chip.setPadding(0, dp(7f), 0, dp(7f))
            val lp = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.setMargins(dp(3f), dp(3f), dp(3f), dp(3f))
            row!!.addView(chip, lp)
        }
        // 最后一行不满四个，补空位，别让时间被拉开
        if (row != null) {
            val filled = times.size % 4
            if (filled != 0) {
                for (i in filled until 4) {
                    row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
        }
        return col
    }

    /** centerMode：0 不动（滚动位置由外部恢复），1 瞬时居中（仅首进页面）。 */
    private fun stopStrip(d: BusModels.LineDetail, centerMode: Int): View {
        val wrap = card()
        wrap.setPadding(0, 0, 0, 0)

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false

        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.TOP
        strip.clipChildren = false
        strip.setPadding(dp(8f), dp(12f), dp(8f), dp(14f))
        stripInner = strip
        fillStrip(strip, d)

        var pickedCol: View? = null
        var nearestCol: View? = null
        val n = d.stops.size
        for (i in 0 until n) {
            val s = d.stops[i]
            val col = strip.getChildAt(i) ?: continue
            if (pickedCol == null && isPicked(s)) pickedCol = col
            if (nearestCol == null && s.nearest) nearestCol = col
        }
        val anchor: View? = pickedCol ?: nearestCol
        hs.addView(strip)
        wrap.addView(hs)
        stripScroll = hs

        if (anchor != null && centerMode > 0) {
            // 等布局完成再居中，否则 getLeft() 还是 0
            hs.post {
                val x = anchor.left + anchor.width / 2 - hs.width / 2
                hs.scrollTo(Math.max(0, x), 0)
            }
        }
        return wrap
    }

    /** 按当前数据填满站线（换站、轮询时只重填这里，不动页面）。 */
    private fun fillStrip(strip: LinearLayout, d: BusModels.LineDetail) {
        strip.removeAllViews()
        val n = d.stops.size
        for (i in 0 until n) {
            strip.addView(
                stripColumn(d.stops[i], i == 0, i == n - 1), LinearLayout.LayoutParams(
                    dp(STRIP_COL.toFloat()),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /**
     * 结构没变时的「原地换内容」：站线上车的位置与选中态、站表的选中态。
     * 不 removeAllViews 外层容器，也不碰 ScrollView —— 页面因此纹丝不动。
     */
    private fun restyleStops(d: BusModels.LineDetail) {
        val strip = stripInner
        if (strip == null) {
            // 站线不见了（上一次渲染失败之类），退回整页重铺
            structKey = ""
            renderLine(-1, -1)
            return
        }
        val x = stripScroll?.scrollX ?: 0
        fillStrip(strip, d)
        if (x > 0) stripScroll?.scrollTo(x, 0)
        stopListInner?.let { fillStationList(it, d) }
    }

    /**
     * 站线上的一列：车辆槽 → 横线 + 站点圆点 → 竖排站名 →（标签）。
     * 车辆槽和圆点行都是固定高度，所有列才能对齐成一条连续的线；
     * 站名一律排在线下面，和车来了那套一致。
     */
    private fun stripColumn(s: BusModels.Stop, first: Boolean, last: Boolean): View {
        val picked = isPicked(s)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.clipChildren = false
        col.isClickable = true
        col.background = rippleRow()
        col.setOnClickListener { onStopTap(s) }

        val slot = FrameLayout(this)
        slot.clipChildren = false
        if (s.busRatio >= 0) {
            val bus = ImageView(this)
            bus.setImageResource(R.drawable.ic_quick_bus)
            bus.setColorFilter(0xFFE8734A.toInt())
            slot.addView(bus, FrameLayout.LayoutParams(dp(19f), dp(19f), Gravity.CENTER))
            // 车在路上就按比例停在两站中间，只有真到站了才贴到圆点上
            bus.translationX = -((1 - s.busRatio) * dp(STRIP_COL.toFloat())).toFloat()
        }
        col.addView(
            slot, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(21f)
            )
        )

        val rail = FrameLayout(this)
        rail.clipChildren = false
        val line = View(this)
        val lg = GradientDrawable()
        lg.setColor(getColor(R.color.border_subtle))
        line.background = lg
        // 线条学车来了加粗一档（1.5dp 太细，横排看起来像条虚线）
        val llp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, dp(5f)), Gravity.CENTER_VERTICAL
        )
        if (first) llp.leftMargin = dp(STRIP_COL / 2f)
        if (last) llp.rightMargin = dp(STRIP_COL / 2f)
        rail.addView(line, llp)

        if (picked) {
            // 车来了同款：候车站在线条上是一个蓝色圆底 + 白色右箭头，
            // 一眼能认出「车往这边开、你在这站等」
            val badge = FrameLayout(this)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(getColor(R.color.brand))
            badge.background = bg
            // 白箭头用几何路径画：字符「›」受字体基线影响，永远做不到真正居中
            badge.addView(
                RailArrow(this, 0xFFFFFFFF.toInt()), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            rail.addView(badge, FrameLayout.LayoutParams(dp(19f), dp(19f), Gravity.CENTER))
        } else {
            val dot = View(this)
            val dg = GradientDrawable()
            dg.shape = GradientDrawable.OVAL
            if (s.nearest) {
                // 家/公司那站：空心圈，永远保留「离我最近」
                dg.setColor(pageColor())
                dg.setStroke(Math.max(2, dp(2.2f)), getColor(R.color.brand))
            } else {
                dg.setColor(if (night()) 0xFF39424F.toInt() else 0xFFD7DCE5.toInt())
            }
            dot.background = dg
            val ds = if (s.nearest) dp(13f) else dp(9f)
            rail.addView(dot, FrameLayout.LayoutParams(ds, ds, Gravity.CENTER))
        }
        col.addView(
            rail, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20f)
            )
        )

        val order = TextView(this)
        order.text = s.orderLabel
        order.textSize = 10.5f
        order.gravity = Gravity.CENTER
        order.setTextColor(
            if (s.nearest || picked)
                getColor(R.color.brand) else getColor(R.color.text_tertiary)
        )
        order.setPadding(0, dp(4f), 0, 0)
        col.addView(order)

        val nameColor = if (s.nearest || picked) getColor(R.color.brand)
        else if (s.busHere) getColor(R.color.text_primary) else getColor(R.color.text_secondary)
        col.addView(stopName(s.name, nameColor, s.nearest || picked))

        // 两个标签是两件事，可以同时出现：
        // 「候车」= 你现在看的是到这一站的车（进来的那个站），
        // 「离我最近」= 定位算出来的最近站（不会因为你点了别的站就跑掉）
        if (picked) col.addView(tinyTag("候车", getColor(R.color.brand), getColor(R.color.brand_soft)))
        if (s.nearest) col.addView(tinyTag("离我最近", 0xFFE8734A.toInt(), 0x1FE8734A))
        return col
    }

    private fun tinyTag(text: String, fg: Int, bg: Int): TextView {
        val tag = TextView(this)
        tag.text = text
        tag.textSize = 9f
        tag.gravity = Gravity.CENTER
        tag.setTextColor(fg)
        tag.background = roundRect(bg, dp(4f))
        tag.setPadding(dp(3f), dp(2f), dp(3f), dp(2f))
        val tlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tlp.topMargin = dp(4f)
        tag.layoutParams = tlp
        return tag
    }

    /** 站线下面的全站清单：35 站的线路靠横滑找站不现实，竖着也能翻。 */
    private fun stationListCard(d: BusModels.LineDetail): View {
        val c = card()
        stopListInner = c
        fillStationList(c, d)
        return c
    }

    /** 按当前数据填满站表（换站、轮询时只重填这里）。 */
    private fun fillStationList(c: LinearLayout, d: BusModels.LineDetail) {
        c.removeAllViews()
        for (i in d.stops.indices) {
            if (i > 0) {
                val div = divider()
                val dlp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))
                )
                dlp.leftMargin = dp(16f)
                c.addView(div, dlp)
            }
            c.addView(stationRow(d.stops[i]))
        }
    }

    private fun stationRow(s: BusModels.Stop): View {
        val picked = isPicked(s)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = rippleRow()
        row.isClickable = true
        row.setOnClickListener { onStopTap(s) }
        row.setPadding(dp(16f), dp(12f), dp(14f), dp(12f))

        val order = TextView(this)
        order.text = s.orderLabel
        order.textSize = 11.5f
        order.setTextColor(
            if (s.nearest || picked)
                getColor(R.color.brand) else getColor(R.color.text_tertiary)
        )
        order.gravity = Gravity.CENTER
        row.addView(
            order, LinearLayout.LayoutParams(
                dp(26f),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val name = TextView(this)
        name.text = s.name
        name.textSize = 14f
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        if (s.nearest || picked) {
            name.typeface = Typeface.DEFAULT_BOLD
            name.setTextColor(getColor(R.color.brand))
        } else {
            name.setTextColor(getColor(R.color.text_primary))
        }
        val nlp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        nlp.leftMargin = dp(6f)
        row.addView(name, nlp)

        // 三个标签说的不是一件事，能同时存在：
        // 「候车」= 正在看的是到这一站的车；「离我最近」= 定位算出来的最近站；
        // 「车辆在此」= 有车正停在这一站。挤不下时只留前两个。
        val pills = LinearLayout(this)
        pills.orientation = LinearLayout.HORIZONTAL
        pills.gravity = Gravity.CENTER_VERTICAL
        if (picked) pills.addView(pill("候车", getColor(R.color.brand), getColor(R.color.brand_soft)))
        if (s.nearest) {
            if (pills.childCount > 0) {
                val gp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gp.leftMargin = dp(5f)
                pills.addView(pill("离我最近", 0xFFE8734A.toInt(), 0x1FE8734A), gp)
            } else {
                pills.addView(pill("离我最近", 0xFFE8734A.toInt(), 0x1FE8734A))
            }
        }
        if (s.busHere && pills.childCount == 0) {
            pills.addView(pill("车辆在此", 0xFFE8734A.toInt(), 0x1FE8734A))
        }
        if (pills.childCount > 0) row.addView(pills)
        return row
    }

    private fun pill(text: String, fg: Int, bg: Int): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 9.5f
        v.gravity = Gravity.CENTER
        v.setTextColor(fg)
        v.background = roundRect(bg, dp(6f))
        v.setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
        return v
    }

    /**
     * 站线上的站名：一列竖排最多 7 字，再长就顺次折到第二列（先从上到下，再从左到右），
     * 不分半。整块宽度撑满一列再居中，才能和上面的圆点对齐。
     */
    private fun stopName(name: String?, color: Int, bold: Boolean): View {
        val s = name ?: ""
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        if (s.length <= NAME_COL_MAX) {
            row.addView(nameColumn(s, color, bold))
            return row
        }
        row.addView(nameColumn(s.substring(0, NAME_COL_MAX), color, bold))
        val gap = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gap.leftMargin = dp(1f)
        row.addView(nameColumn(s.substring(NAME_COL_MAX), color, bold), gap)
        return row
    }

    private fun nameColumn(seg: String, color: Int, bold: Boolean): TextView {
        val v = TextView(this)
        v.text = vertical(seg)
        v.textSize = if (seg.length > 4) 11.5f else 12.5f
        v.gravity = Gravity.CENTER_HORIZONTAL
        v.setTextColor(color)
        v.includeFontPadding = false
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        return v
    }

    private fun toggleFavorite(btn: TextView) {
        val f = BusStore.Fav()
        f.cityId = cityId
        f.lineId = lineId
        f.name = lineName
        f.direction = direction
        val now = store.toggle(f)
        btn.text = if (now) "已收藏" else "收藏"
    }

    // ──────────────────────────── 城市与定位 ────────────────────────────

    /**
     * 切数据源：两家的优劣用大白话写在选项里，选完记住，
     * 下次打开直接用他选的那个（持久化在 BusStore）。
     */
    private fun pickSource() {
        val opts = arrayOf(
            "车来了（默认）\n到站时间准，但少数城乡线路查不到",
            "掌上公交\n线路更全（含城乡公交），个别线路时间偏差大"
        )
        OptionSheet.show(this, "切换数据源", opts, BusClient.source(),
            object : OptionSheet.OnPick {
                override fun onPick(index: Int) {
                    if (index == BusClient.source()) return
                    switchSource(index)
                }
            })
    }

    private fun switchSource(src: Int) {
        store.setSource(src)          // 记住偏好 + 切 BusClient + 作废缓存
        cityId = store.cityId()       // 城市编号换了一套，先落默认值
        // 顶栏按钮是 onCreate 时画好的，这里手动把字换掉（三角是画的，只换文字）
        sourceBtn?.text = BusClient.sourceName()
        expanded.clear()
        showingSearch = false
        searchBox?.setText("")
        stopPlatforms.clear()
        android.widget.Toast.makeText(
            this,
            "已切换为「" + BusClient.sourceName() + "」", android.widget.Toast.LENGTH_SHORT
        ).show()
        // 两套城市编号对不上：按城市名在后台对一遍，对不上就保持默认城市
        Thread({
            try {
                val want = normCity(store.cityName())
                for (c in BusClient.cities()) {
                    if (normCity(c.name) == want) {
                        store.setCity(c.id, c.name)
                        cityId = c.id
                        break
                    }
                }
            } catch (ignored: Exception) {
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                reload()
            }
        }, "bus-switch-src").start()
    }

    private fun pickCity() {
        android.widget.Toast.makeText(this, "正在加载城市列表…", android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            var cities: List<BusModels.City>? = null
            try {
                cities = BusClient.cities()
            } catch (ignored: Exception) {
            }
            val data = cities
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (data == null || data.isEmpty()) {
                    infoDialog("城市列表加载失败", "请检查网络后重试；也可以直接搜索线路，默认城市是唐山")
                    return@runOnUiThread
                }
                val names = Array(data.size) { "" }
                var sel = 0
                for (i in data.indices) {
                    names[i] = data[i].name
                    if (data[i].id == cityId) sel = i
                }
                OptionSheet.show(
                    this, "选择城市", names, sel,
                    object : OptionSheet.OnPick {
                        override fun onPick(index: Int) {
                            val c = data[index]
                            store.setCity(c.id, c.name)
                            cityId = c.id
                            showingSearch = false
                            renderHome()
                        }
                    })
            }
        }, "bus-cities").start()
    }

    private fun hasLocationPermission(): Boolean {
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        return coarse == PackageManager.PERMISSION_GRANTED || fine == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocation(force: Boolean) {
        if (!force && lat > 0 && lng > 0) {
            loadNearby()
            return
        }
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 7
            )
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        if (lm == null) return

        val now = System.currentTimeMillis()
        // 已经有一帧在等，或者刚刚才拿到：不重复发
        if (locating || now - lastFixAt < FIX_DEDUPE_MS) {
            // 手动下拉时不能因为「刚定位过」就把这次刷新吞掉：坐标就是现在的，
            // 直接把附近站点重查一遍，不然下拉会一直转圈。
            if (!locating && forceReload && MODE_HOME == mode) renderHome()
            return
        }

        val last = lastKnown(lm)

        // 先把手里的点当下「现在的位置」立刻铺一屏：读本地缓存，毫秒级，不用等定位
        // 那一两秒。同时照样去要一帧新的，两个点差得远时新帧回来会静默重铺一次。
        // 这样最常见的情况（刚在别处被定位过 / 模拟定位刚切过）是「进页面就到位」。
        if (last != null && now - last.time < PROVISIONAL_AGE_MS) {
            useLocation(last, true)
        }
        requestFix(lm, last, 0)
    }

    /**
     * 主动要一帧定位。
     *
     * 第一次超时后，如果手里那个已知点已经很旧（比如半小时前在宿舍留下的），
     * 直接拿它兜底等于「位置没刷新」，用户会一直看到别处的站点。所以再要一轮，
     * 两轮都拿不到才退回旧点，并且旧点也会触发重铺判断。
     */
    private fun requestFix(lm: LocationManager, fallback: Location?, attempt: Int) {
        locating = true
        lastLocateAt = System.currentTimeMillis()
        updateLocatingHint()
        val l = LocListener(this, lm)
        try {
            for (p in arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(p, 0L, 0f, l)
                } catch (ignored: Exception) {
                }
            }
        } catch (ignored: Exception) {
        }
        android.os.Handler(getMainLooper()).postDelayed({
            if (l.finished()) return@postDelayed             // 已经收到帧了，useLocation 里收尾
            l.stop()
            locating = false
            if (isFinishing || isDestroyed) return@postDelayed

            // 等的这段时间里系统里可能刚落了一个新点，取较新的那个
            val best = newer(fallback, lastKnown(lm))
            val age = if (best == null) Long.MAX_VALUE
            else System.currentTimeMillis() - best.time
            if (attempt == 0 && age > FALLBACK_MAX_AGE_MS) {
                requestFix(lm, best, 1)          // 手里的点太旧，再要一帧
                return@postDelayed
            }
            if (best != null) {
                useLocation(best)
            } else {
                updateLocatingHint()
                pull?.setRefreshing(false)
                if (MODE_HOME == mode && (lat <= 0 || lng <= 0)) renderHome()
            }
        }, LOC_TIMEOUT_MS)
    }

    private fun useLocation(l: Location) {
        useLocation(l, false)
    }

    /**
     * @param provisional 只是先拿系统缓存的那个点把页面铺出来，后面还有一帧新定位要进来；
     *                    这种不算「定位完成」，不能清掉 lastFixAt / locating 的去重状态。
     */
    private fun useLocation(l: Location, provisional: Boolean) {
        val prevLat = lat
        val prevLng = lng
        lat = l.latitude
        lng = l.longitude
        store.setLastLocation(lat, lng)
        if (!provisional) {
            locating = false
            lastFixAt = System.currentTimeMillis()
        }
        if (MODE_HOME != mode) {
            updateLocatingHint()
            return
        }

        showingSearch = false
        // 回到首页要的是「当前位置的公交」：位置真变了就整套重铺（距离、顺序都变）；
        // 位置没动（大多数时候是这样）就保留已经滑出来的站点和滚动位置，只重算到站，
        // 但手动下拉刷新要的是「重新查一遍」，位置没动也得重铺。
        val moved = prevLat <= 0 || prevLng <= 0
                || BusClient.distanceMeters(prevLat, prevLng, lat, lng) > 50
        // 第一屏还在路上（loading）就别再开一轮，等它回来直接铺，省一次请求
        val reloadList = forceReload || moved || (!loading && nearList.isEmpty())
        forceReload = false
        if (reloadList) {
            renderHome()
        } else {
            updateLocatingHint()
            refreshShownEta(ETA_PER_PRIME)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req != 7) return
        if (hasLocationPermission()) {
            ensureLocation(true)
        } else if (MODE_HOME == mode) {
            renderHomeContent()
            body.addView(infoCard("没有定位权限", "「附近站点」需要位置权限；不授权也可以直接搜索线路"))
        }
    }

    /** 只收第一帧定位结果就注销，避免持续定位。 */
    private class LocListener(private val host: BusActivity, private val lm: LocationManager) :
        LocationListener {
        private var done = false

        override fun onLocationChanged(location: Location) {
            if (done) return
            done = true
            stop()
            host.useLocation(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
        }

        fun stop() {
            try {
                lm.removeUpdates(this)
            } catch (ignored: Exception) {
            }
        }

        /** 已经收到过一帧定位了吗（超时兜底用它判断要不要顶上去）。 */
        fun finished(): Boolean {
            return done
        }
    }

    // ──────────────────────────── 通用小件 ────────────────────────────

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = getDrawable(R.drawable.card_bg)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10f)
        c.layoutParams = lp
        return c
    }

    private fun rippleCard(): android.graphics.drawable.Drawable {
        return RippleDrawable(
            ColorStateList.valueOf(0x14000000),
            getDrawable(R.drawable.card_bg), null
        )
    }

    /** 行内涟漪：不盖卡片底色，只叠一层很淡的按下反馈。 */
    private fun rippleRow(): android.graphics.drawable.Drawable {
        return RippleDrawable(ColorStateList.valueOf(0x0F000000), null, null)
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(getColor(R.color.border_subtle))
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))
        )
        return v
    }

    private fun sectionTitle(s: String): TextView {
        val v = TextView(this)
        v.text = s
        v.textSize = 13f
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(getColor(R.color.text_tertiary))
        v.setPadding(dp(4f), dp(16f), 0, dp(8f))
        return v
    }

    /** 线路徽标：品牌浅底 + 品牌字。 */
    private fun badge(name: String?): View {
        val v = TextView(this)
        v.text = name ?: ""
        v.textSize = 12.5f
        v.typeface = Typeface.DEFAULT_BOLD
        v.gravity = Gravity.CENTER
        v.setTextColor(getColor(R.color.brand))
        v.background = roundRect(getColor(R.color.brand_soft), dp(8f))
        v.minWidth = dp(36f)
        v.setPadding(dp(7f), dp(4f), dp(7f), dp(4f))
        return v
    }

    private fun roundRect(color: Int, radius: Int): android.graphics.drawable.Drawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    private fun loadingCard(): View {
        val c = card()
        c.gravity = Gravity.CENTER
        c.setPadding(dp(16f), dp(30f), dp(16f), dp(30f))
        val pb = ProgressBar(this)
        pb.indeterminateTintList = ColorStateList.valueOf(getColor(R.color.brand))
        c.addView(pb, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        val t = TextView(this)
        t.text = "正在获取实时数据…"
        t.textSize = 12.5f
        t.setTextColor(getColor(R.color.text_tertiary))
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(10f), 0, 0)
        c.addView(t)
        return c
    }

    private fun infoCard(title: String, sub: String): View {
        val c = card()
        c.gravity = Gravity.CENTER
        c.setPadding(dp(16f), dp(28f), dp(16f), dp(28f))
        val a = TextView(this)
        a.text = title
        a.textSize = 14f
        a.typeface = Typeface.DEFAULT_BOLD
        a.setTextColor(getColor(R.color.text_primary))
        a.gravity = Gravity.CENTER
        c.addView(a)
        val b = TextView(this)
        b.text = sub
        b.textSize = 12f
        b.setTextColor(getColor(R.color.text_tertiary))
        b.gravity = Gravity.CENTER
        b.setPadding(0, dp(8f), 0, 0)
        c.addView(b)
        return c
    }

    private fun errorCard(msg: String): View {
        val c = card()
        c.gravity = Gravity.CENTER
        c.setPadding(dp(16f), dp(26f), dp(16f), dp(26f))
        val a = TextView(this)
        a.text = msg
        a.textSize = 13f
        a.setTextColor(getColor(R.color.text_secondary))
        a.gravity = Gravity.CENTER
        c.addView(a)
        val retry = TextView(this)
        retry.text = "重试"
        retry.textSize = 13f
        retry.typeface = Typeface.DEFAULT_BOLD
        retry.setTextColor(getColor(R.color.brand))
        retry.gravity = Gravity.CENTER
        retry.setPadding(dp(16f), dp(10f), dp(16f), dp(6f))
        retry.setOnClickListener { reload() }
        c.addView(retry)
        return c
    }

    private fun infoDialog(title: String, msg: String) {
        ConfirmDialog.alert(this, title, msg)
    }

    /**
     * 站线候车站徽标里的白色右箭头（车来了同款）。
     * 用 Canvas 画路径而不是字符「›」：TextView 的字形受字体 ascent/descent
     * 左右，怎么调 padding 都差半个像素的居中；几何路径以 View 正中为原点，
     * 天然居中。静态嵌套类，不碰 d8 对内部类的那点脾气。
     */
    private class RailArrow(c: Context, color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            paint.setColor(color)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c.resources.displayMetrics.density * 1.8f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val s = Math.min(width, height) * 0.2f
            path.reset()
            path.moveTo(cx - s * 0.55f, cy - s)
            path.lineTo(cx + s * 0.55f, cy)
            path.lineTo(cx - s * 0.55f, cy + s)
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PHYSICAL = "physical_id"
        private const val EXTRA_NAMESAKE = "namesake_id"
        private const val EXTRA_STATION = "station_id"
        private const val EXTRA_LINE = "line_id"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ORDER = "order"
        private const val EXTRA_CITY = "city_id"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"

        private const val MODE_HOME = "home"
        private const val MODE_STOP = "stop"
        private const val MODE_LINE = "line"

        /** 实时位置定时刷新：20 秒一轮，静默更新，不动下拉动画。 */
        private const val AUTO_MS = 20000L

        /** 一轮最多重算几条（3 并发 + 每条间隔 70ms，12 条约 3~4 秒）。 */
        /** 线路详情主卡的三态：站点到站 / 全部车辆 / 单车跟随 */
        private const val PANEL_STATION = 0
        private const val PANEL_BUS_LIST = 1
        private const val PANEL_BUS_FOLLOW = 2

        private const val ETA_PER_TICK = 12

        /** 刚进页面 / 刚铺完一屏时先整体算一遍，多给一些名额。 */
        private const val ETA_PER_PRIME = 36

        /** 一轮还没走完就不再起新一轮，免得自己把自己限流。 */
        private const val ETA_GAP_MS = 8000L

        /** 时刻表 5 分钟内不重复拉：它变化慢，没必要跟着 20 秒轮询走。 */
        private const val TT_TTL_MS = 5 * 60 * 1000L

        /** 一屏先给 12 条线路，滑到底再补 12 条。 */
        private const val SEARCH_PAGE = 12

        /** 站线里每一列的宽度，决定一屏能露出几个站。 */
        private const val STRIP_COL = 54

        /** 一列最多放几个字，超过才折第二列。 */
        private const val NAME_COL_MAX = 7

        /** 系统里那个「最后已知位置」多旧还愿意先拿来铺一屏（本地读取，不等定位）。 */
        private const val PROVISIONAL_AGE_MS = 3 * 60 * 1000L

        /** 兜底用的旧点超过这个年龄就再要一帧：太旧的点会把附近站点停在几公里外。 */
        private const val FALLBACK_MAX_AGE_MS = 60000L

        /** 主动定位最多等这么久，超时就用最后一次已知位置兜底。 */
        private const val LOC_TIMEOUT_MS = 7000L

        /** 连着两次要定位的间隔：刚拿到就不要再要，避免重复发。 */
        private const val FIX_DEDUPE_MS = 2000L

        /** 首页停留时每隔这么久自动对一次位置。 */
        private const val RELOCATE_EVERY_MS = 60000L

        /** 从首页宫格进入。 */
        @JvmStatic
        fun open(host: Activity) {
            host.startActivity(Intent(host, BusActivity::class.java).putExtra(EXTRA_MODE, MODE_HOME))
        }

        private fun openStop(
            host: Activity,
            s: BusModels.Station,
            cityId: String,
            lat: Double,
            lng: Double
        ) {
            host.startActivity(
                Intent(host, BusActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_STOP)
                    .putExtra(EXTRA_NAME, s.name)
                    .putExtra(EXTRA_PHYSICAL, s.physicalId)
                    .putExtra(EXTRA_NAMESAKE, s.namesakeId)
                    .putExtra(EXTRA_STATION, s.id)
                    .putExtra(EXTRA_CITY, cityId)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LNG, lng)
            )
        }

        private fun openLine(
            host: Activity, cityId: String, lineId: String, name: String,
            direction: Int, order: Int, stopId: String?, lat: Double, lng: Double
        ) {
            host.startActivity(
                Intent(host, BusActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_LINE)
                    .putExtra(EXTRA_LINE, lineId)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_DIRECTION, direction)
                    .putExtra(EXTRA_ORDER, order)
                    .putExtra(EXTRA_STATION, stopId ?: "")
                    .putExtra(EXTRA_CITY, cityId)
                    .putExtra(EXTRA_LAT, lat)
                    .putExtra(EXTRA_LNG, lng)
            )
        }

        private fun safe(s: String?): String {
            return s ?: ""
        }

        /** 现在几点（今天的第几分钟）。 */
        private fun nowMinutes(): Int {
            val c = java.util.Calendar.getInstance()
            return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        }

        /** 「唐山市」「唐山」「迁西县」都折成可比较的名字。 */
        private fun normCity(s: String?): String {
            var v = if (s == null) "" else s.trim()
            while (v.endsWith("市") || v.endsWith("县")) {
                v = v.substring(0, v.length - 1)
            }
            return v
        }

        private fun newer(a: Location?, b: Location?): Location? {
            if (a == null) return b
            if (b == null) return a
            return if (b.time > a.time) b else a
        }

        /** 系统里最近一次已知位置（GPS / 网络取时间较新的那条）。 */
        private fun lastKnown(lm: LocationManager): Location? {
            var best: Location? = null
            for (p in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || l.time > best.time) best = l
                } catch (ignored: Exception) {
                }
            }
            return best
        }

        /** 站名竖排：逐字换行，和车来了站线的排法一致。 */
        private fun vertical(s: String?): String {
            if (s == null || s.isEmpty()) return ""
            val sb = StringBuilder()
            for (i in s.indices) {
                if (i > 0) sb.append('\n')
                sb.append(s[i])
            }
            return sb.toString()
        }

        private fun lineKey(l: BusModels.Line): String {
            return if (l.lineNo.isEmpty()) l.name + "#" + l.lineId else l.lineNo
        }

        /** 网络异常翻译成人话，别把 IOException 甩给用户。 */
        private fun friendly(e: Exception?): String {
            val m = if (e == null) "" else e.message.toString()
            if (e is java.net.SocketTimeoutException) return "网络超时，换个网络再试一次"
            if (e is java.net.UnknownHostException) return "连不上服务器，检查一下网络"
            if (m.contains("HTTP 5")) return "服务器暂时不可用，稍后再试"
            if (m.contains("HTTP 4")) return "请求被拒绝，稍后再试"
            return "获取实时数据失败，稍后重试"
        }

        /** 17 → 17 路；B1 → B1（带字母的不加「路」）。 */
        private fun displayLine(name: String?): String {
            if (name == null || name.isEmpty()) return "线路"
            var digits = true
            for (i in name.indices) {
                if (!Character.isDigit(name[i])) {
                    digits = false
                    break
                }
            }
            return if (digits) "$name 路" else name
        }
    }
}
