import SwiftUI

/// 应用入口。
///
/// 导航骨架与安卓 `MainActivity.kt` 完全对应：
///   · 一条横向「页面带」（PageDeck），四个主页面等宽排开；
///   · 切 tab = 整条带平移 —— 从首页点「我的」时，课表、成绩会真的依次掠过屏幕；
///   · 底部悬浮胶囊 Dock，选中块自己滑过去并轻微回弹；
///   · **没有系统 TabView**（那是 iOS 默认习惯，但会破坏安卓的视觉效果，
///     也让「实时公交」被迫塞成第 5 个一级 tab）。
@main
struct ZSTApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .preferredColorScheme(app.preferredScheme)
                .tint(T.brand)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var didBootstrap = false
    @State private var showSplash = true
    @State private var openBusAfterSplash = false

    var body: some View {
        ZStack {
            if showSplash {
                SplashPage(
                    onFinish: {
                        withAnimation(M.fadeIn) { showSplash = false }
                    },
                    onOpenBus: {
                        openBusAfterSplash = true
                        withAnimation(M.fadeIn) { showSplash = false }
                    }
                )
                .environmentObject(app)
                .transition(.opacity)
            } else {
                mainDeck
                    .transition(.opacity)
            }
        }
        .pageBackground()
        .toastHost($app.toast)
        .fullScreenCover(isPresented: $app.showLogin) {
            LoginPage()
                .environmentObject(app)
        }
        .task {
            guard !didBootstrap else { return }
            didBootstrap = true
            app.bootstrap()
            // 不自动同步：启动页自己会根据 autoLogin 决定要不要跑同步（与安卓一致）
        }
        .onChange(of: showSplash) { _, showing in
            guard !showing, openBusAfterSplash else { return }
            openBusAfterSplash = false
            // 启动页选「直接进入实时公交」：直接切到底栏的公交 tab
            app.setTab(MainTab.bus.rawValue, animated: true)
        }
        .onChange(of: scenePhase) { _, phase in
            // 从后台回前台：重读本地数据 + 刷小组件
            if phase == .active { app.reloadFromDisk() }
        }
    }

    // MARK: - 主界面（页面带 + Dock）

    private var mainDeck: some View {
        GeometryReader { geo in
            let width = geo.size.width

            ZStack(alignment: .bottom) {
                PageDeck(
                    position: $app.deckPosition,
                    index: $app.tab,
                    width: width,
                    reduceMotion: reduceMotion
                ) {
                    HomePage(width: width)
                        .frame(width: width)
                    TimetablePage(width: width)
                        .frame(width: width)
                    GradesPage(width: width)
                        .frame(width: width)
                    // 实时公交一键直达：作为第 4 个页面（tab 3）
                    BusRootPage()
                        .frame(width: width)
                    MinePage(width: width)
                        .frame(width: width)
                }
                // 高度撑满、宽度跟随几何；PageDeck 内部自己负责横向裁剪
                .frame(width: width, height: geo.size.height)

                CapsuleDock(
                    selected: Binding(
                        get: { app.tab },
                        set: { app.setTab($0, animated: true) }
                    ),
                    deckPosition: app.deckPosition,
                    onSelect: { i in app.setTab(i, animated: true) },
                    onReselect: { i in handleReselect(i) }
                )
            }
            .frame(width: width, height: geo.size.height)
        }
    }

    /// 再点一次已选中的 tab
    private func handleReselect(_ index: Int) {
        Haptics.light()
        switch MainTab(rawValue: index) {
        case .home:
            Task { await app.refreshHome() }
        case .timetable:
            NotificationCenter.default.post(name: .zstJumpToThisWeek, object: nil)
        default:
            break
        }
    }
}

extension Notification.Name {
    /// 再点一次「课表」= 回到本周
    static let zstJumpToThisWeek = Notification.Name("zst.jumpToThisWeek")
    /// 请求打开实时公交（启动页「直接进入」用）
    static let zstOpenBus = Notification.Name("zst.openBus")
}

// MARK: - PageDeck
//
// 对应安卓 `ui/PageDeck.kt`：所有页面都 layout 在同一块区域，
// 只用 offset 区分位置，整条带按 (槽位 - 当前位置) × 屏宽 平移。

struct PageDeck<Content: View>: View {
    @Binding var position: CGFloat
    @Binding var index: Int
    let width: CGFloat
    let reduceMotion: Bool
    @ViewBuilder var content: () -> Content

    @State private var dragOffset: CGFloat = 0
    @State private var dragging = false
    @GestureState private var pressing = false

    var body: some View {
        // 4 个页面横向排开成一条宽 4W 的带子，再整体平移。
        //
        // 关键点（都是踩过的坑）：
        //   1. 必须用 HStack —— SwiftUI 默认容器会把多个子视图**叠**在一起，
        //      叠起来后平移量再对也对不上页面（上一版就是这样，点课表显示别的页）。
        //   2. 这里**不能**再套 .frame(width: width * 4)。
        //      HStack 的宽度本来就由 4 个子页面的 .frame(width: width) 累加得到；
        //      外面再强加一个 frame 会让 SwiftUI 重新居中内容，偏移量被吃掉一半，
        //      页面反而更歪（第二版就是这样，用户反馈「更加错位」）。
        //   3. .clipped() 要加在 offset **之后**，把带子裁到屏幕范围内。
        HStack(spacing: 0) {
            content()
        }
        .offset(x: -position * width + dragOffset)
        .frame(width: width, alignment: .leading)
        .clipped()
        .animation(dragging ? nil : M.respecting(
            Animation.timingCurve(0.0, 1.0, 0.32, 1.0,
                                  duration: M.slideDuration(remain: 1)),
            enabled: reduceMotion
        ), value: position)
        .gesture(swipe)
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 24, coordinateSpace: .global)
            .onChanged { value in
                guard !reduceMotion else { return }
                let dx = value.translation.width
                let dy = value.translation.height
                // 只接管明显的横向手势；纵向滚动 / 课表横滑翻周各自处理
                guard abs(dx) > abs(dy) * 1.4 else { return }
                dragging = true
                // 边界阻尼：到两端时只跟 28%
                let atEdge = (dx > 0 && index == 0) || (dx < 0 && index == MainTab.count - 1)
                dragOffset = atEdge ? dx * 0.28 : dx
            }
            .onEnded { value in
                defer { dragging = false }
                guard !reduceMotion else { dragOffset = 0; return }
                let dx = value.translation.width
                let predicted = value.predictedEndTranslation.width - dx
                let threshold = width * 0.22
                var target = index
                if abs(dx) >= threshold || abs(predicted) > 120 {
                    target = index + (dx < 0 ? 1 : -1)
                }
                target = max(0, min(MainTab.count - 1, target))
                let remain = Double(abs(target - index))
                withAnimation(M.respecting(
                    Animation.timingCurve(0.0, 1.0, 0.32, 1.0,
                                          duration: M.slideDuration(remain: remain)),
                    enabled: reduceMotion
                )) {
                    dragOffset = 0
                    if target != index { Haptics.select() }
                    index = target
                    position = CGFloat(target)
                }
            }
    }
}