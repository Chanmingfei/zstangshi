import SwiftUI

@main
struct ZSTApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                // 主题：浅色/深色/跟随系统。对应 Android 的 applyOverrideConfiguration 注入夜间位，
                // iOS 这边官方做法就是 preferredColorScheme —— 传 nil 即跟随系统。
                .preferredColorScheme(app.preferredScheme)
                .tint(T.brand)
        }
    }
}

/// 主界面：四个页面横排成一条带 + 悬浮胶囊底栏。
struct RootView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.scenePhase) private var scenePhase

    /// 底栏占位高度（胶囊 64 + 上 12 + 下 14），页面内容要给它让出位置
    private let dockBand: CGFloat = 90

    private var dockSelection: Binding<Int> {
        Binding(
            get: { app.tab },
            set: { app.switchTab($0) }
        )
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            T.bgPage.ignoresSafeArea()

            PageDeck(index: dockSelection, count: 4) { i in
                page(i)
                    .padding(.bottom, dockBand)
            }

            CapsuleDock(selected: dockSelection) { index in
                // 再点当前项：首页 = 刷新，课表 = 回到本周（对应 Android onReselect）
                if index == 0 {
                    Task { await app.refreshHome() }
                } else if index == 1 {
                    app.week = app.currentWeek()
                }
            }
        }
        .toast($app.toast)
        .fullScreenCover(isPresented: $app.showLogin) {
            LoginView()
                .environmentObject(app)
        }
        .onAppear {
            app.bootstrap()
            // 自动登录：只在「勾了自动登录 + 存了密码 + 还没有本地课表」时静默跑一遍
            if app.store.autoLogin(), !app.store.pass.isEmpty, app.timetable == nil {
                Task {
                    if let error = await app.syncTimetable() {
                        app.showToast(error)
                    }
                }
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { app.bootstrap() }
        }
    }

    @ViewBuilder
    private func page(_ index: Int) -> some View {
        switch index {
        case 0: HomeView()
        case 1: TimetableView()
        case 2: GradesHubView()
        default: MineView()
        }
    }
}
