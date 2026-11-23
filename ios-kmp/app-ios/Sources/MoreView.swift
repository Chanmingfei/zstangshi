import SwiftUI
import Shared

/// 更多功能：把不常驻宫格的入口收在一起。
struct MoreView: View {
    @ObservedObject private var auth = JwAuth.shared

    var body: some View {
        List {
            Section("课表") {
                NavigationLink("我的课表") { TimetableView() }
                NavigationLink("学分绩点") { GpaView() }
            }
            Section("公交") {
                NavigationLink("实时公交") { BusHomeView() }
                NavigationLink("乘车码") { TransitCodeView() }
            }
            Section("账号") {
                NavigationLink("我的") { ProfileView() }
                if !auth.loggedIn {
                    NavigationLink("登录学工系统") { LoginView().environmentObject(auth) }
                }
            }
            Section("关于") {
                HStack {
                    Text("版本")
                    Spacer()
                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                        .foregroundColor(.secondary)
                }
                HStack {
                    Text("包名")
                    Spacer()
                    Text(Bundle.main.bundleIdentifier ?? "—")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("更多功能")
        .navigationBarTitleDisplayMode(.inline)
    }
}
