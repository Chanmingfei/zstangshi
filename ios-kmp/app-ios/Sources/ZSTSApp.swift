import SwiftUI

/// 课表数据变更通知：任何页面同步/编辑课表后发一次，
/// 各 tab 据此重读本地缓存（对齐安卓 MainActivity.onTimetableChanged）。
extension Notification.Name {
    static let timetableChanged = Notification.Name("jw.timetableChanged")
}

/// 底部导航路由 —— 对齐安卓 MainActivity 的 4 个 tab：首页 | 课表 | 成绩 | 我的。
/// 首页宫格点「我的课表 / 成绩」时切到对应 tab，和安卓 switchTo() 口径一致。
final class TabRouter: ObservableObject {
    @Published var selection = 0
}

@main
struct ZSTSApp: App {
    @StateObject private var router = TabRouter()

    var body: some Scene {
        WindowGroup {
            TabView(selection: $router.selection) {
                NavigationStack { RootView() }
                    .tabItem { Label("首页", systemImage: "house.fill") }
                    .tag(0)

                NavigationStack { TimetableView() }
                    .tabItem { Label("课表", systemImage: "calendar") }
                    .tag(1)

                NavigationStack { GradesView() }
                    .tabItem { Label("成绩", systemImage: "chart.bar.fill") }
                    .tag(2)

                NavigationStack { ProfileView() }
                    .tabItem { Label("我的", systemImage: "person.crop.circle") }
                    .tag(3)
            }
            // 安卓主题深红，底栏选中色用同款（iOS 原生 TabView 样式）。
            .tint(Color(red: 0.46, green: 0.08, blue: 0.10))
            .environmentObject(router)
        }
    }
}
