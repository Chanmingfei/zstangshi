import SwiftUI
import Shared

/// 「我的」页 —— 账号状态、同步课表、隐藏课程管理、清缓存、退出。
///
/// 同步策略和安卓一致：
///   · 首次登录（或换账号）自动同步一次；
///   · 之后不再自动同步，想刷新只能点这里的「同步课表」。
struct ProfileView: View {
    @ObservedObject private var auth = JwAuth.shared

    @State private var syncing = false
    @State private var message = ""
    @State private var hidden: [Course] = []

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: auth.loggedIn ? "person.crop.circle.badge.checkmark" : "person.crop.circle")
                        .font(.title2)
                        .foregroundColor(auth.loggedIn ? Color(red: 0.10, green: 0.45, blue: 0.22)
                                         : Color(red: 0.46, green: 0.08, blue: 0.10))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(auth.loggedIn ? auth.studentNo : "未登录")
                            .font(.headline)
                        Text(auth.loggedIn ? "教务会话已建立" : "登录后可同步课表与成绩")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }

            Section {
                Button {
                    Task { await doSync() }
                } label: {
                    HStack {
                        if syncing { ProgressView().padding(.trailing, 6) }
                        Text(syncing ? "同步中…" : "同步课表")
                    }
                }
                .disabled(syncing || !auth.loggedIn)

                if !message.isEmpty {
                    Text(message).font(.caption).foregroundColor(.secondary)
                }
            } footer: {
                Text("教务是权威数据：同步会用教务的最新课表覆盖本地，只保留你手动添加的课程和隐藏设置。"
                     + "首次登录会自动同步一次，之后需要手动点这里。")
            }

            if !hidden.isEmpty {
                Section("已隐藏的课程") {
                    ForEach(Array(hidden.enumerated()), id: \.offset) { pair in
                        HStack {
                            Text(pair.element.name).font(.subheadline)
                            Spacer()
                            Button("恢复") {
                                restore(pair.element)
                            }
                            .font(.footnote)
                        }
                    }
                }
            }

            Section {
                NavigationLink("登录 / 换账号") {
                    LoginView().environmentObject(auth)
                }
                Button("退出登录", role: .destructive) {
                    auth.logout()
                    UserDefaults.standard.removeObject(forKey: "jw.syncedNo")
                    message = "已退出，本地课表保留"
                }
                Button("清除本地课表缓存", role: .destructive) {
                    TimetableStore.clear()
                    hidden = []
                    message = "已清除，下次同步会重新拉取"
                }
            } footer: {
                Text("退出不会删除本地课表；换账号登录后会自动用新账号的课表覆盖。")
            }

            Section {
                HStack {
                    Text("版本")
                    Spacer()
                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                        .foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("我的")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reloadHidden() }
    }

    private func reloadHidden() {
        let t = TimetableStore.load()
        guard let t = t else { hidden = []; return }
        let keys = t.hiddenList()
        hidden = t.courseList().filter { keys.contains($0.key) }
    }

    private func restore(_ c: Course) {
        guard let t = TimetableStore.load() else { return }
        TimetableEdit.shared.unhideCourse(t: t, key: c.key)
        TimetableStore.save(t)
        reloadHidden()
    }

    private func doSync() async {
        syncing = true
        defer { syncing = false }
        let t = await JwAuth.shared.fetchTimetable()
        guard let fresh = t else {
            message = JwAuth.shared.message.isEmpty ? "同步失败" : JwAuth.shared.message
            return
        }
        // 保留隐藏列表
        if let local = TimetableStore.load() {
            for k in local.hiddenList() {
                TimetableEdit.shared.hideCourse(t: fresh, key: k)
            }
        }
        TimetableStore.save(fresh)
        UserDefaults.standard.set(JwAuth.shared.studentNo, forKey: "jw.syncedNo")
        message = "已同步 · \(TimetableGrid.shared.visibleCount(t: fresh)) 门课"
        reloadHidden()
    }
}
