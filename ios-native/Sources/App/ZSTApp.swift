import SwiftUI

// MARK: - 应用入口
//
// v4 起 iOS 侧按 HIG 重写导航骨架：
//   · 主导航 = 系统 `TabView`。用 Xcode 26 / iOS 26 SDK 编译后，标签栏会自动
//     获得 Liquid Glass；系统负责材质、滚动时的收起/展开、safe area 与无障碍。
//     不再自绘胶囊底栏 —— 那是 Android 的平台习惯，不是 iOS 的。
//   · 每个 tab 各自一根 `NavigationStack`，二级页走系统 push/pop + 边缘返回手势。
//   · 弹层统一 `.sheet`，系统给玻璃卡片与下拉关闭。
//   · 低版本（iOS 17–25）系统标签栏仍是原生材质，自定义浮动控件由
//     `GlassSurface` 回退到 `.ultraThinMaterial`，同一套代码两种外观。

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

/// 五个主页面：首页 / 课表 / 实时公交 / 成绩 / 我的。
/// 「实时公交」与 Android v4 底栏同级，放在中间位（HIG 建议主任务居中、不超 5 项）。
struct RootView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView(selection: Binding(get: { app.tab }, set: { app.switchTab($0) })) {
            NavigationStack {
                HomeView()
                    .navigationTitle("掌上唐师")
                    .navigationBarTitleDisplayMode(.large)
            }
            .tabItem { Label("首页", systemImage: "house.fill") }
            .tag(0)

            NavigationStack {
                TimetableView()
                    .navigationTitle("我的课表")
                    .navigationBarTitleDisplayMode(.inline)
            }
            .tabItem { Label("课表", systemImage: "calendar") }
            .tag(1)

            NavigationStack {
                BusHomeView()
                    .navigationTitle("实时公交")
                    .navigationBarTitleDisplayMode(.large)
            }
            .tabItem { Label("实时公交", systemImage: "bus.fill") }
            .tag(2)

            NavigationStack {
                GradesHubView()
                    .navigationTitle("成绩")
                    .navigationBarTitleDisplayMode(.large)
            }
            .tabItem { Label("成绩", systemImage: "chart.bar.fill") }
            .tag(3)

            NavigationStack {
                MineView()
                    .navigationTitle("我的")
                    .navigationBarTitleDisplayMode(.large)
            }
            .tabItem { Label("我的", systemImage: "person.fill") }
            .tag(4)
        }
        // iOS 26 上系统自动玻璃化；这里只保证内容不被标签栏压住。
        // 不要再给 TabView 叠自定义 background —— 会挡住系统材质取样。
        .toast($app.toast)
        .fullScreenCover(isPresented: $app.showLogin) {
            LoginView().environmentObject(app)
        }
        .onAppear {
            app.bootstrap()
            if app.store.autoLogin(), !app.store.pass.isEmpty, app.timetable == nil {
                Task {
                    if let error = await app.syncTimetable() { app.showToast(error) }
                }
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { app.bootstrap() }
        }
    }
}
