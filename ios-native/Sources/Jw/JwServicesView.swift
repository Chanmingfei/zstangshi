import SwiftUI

/// 「教务服务」入口页。首页宫格用 `NavigationLink` 直接 push，因此必须是**无参构造**，
/// `AppState` 由 RootView 注入的 `@EnvironmentObject` 沿导航栈自动继承。
///
/// 分工（与 Android v4 的 FeatureActivity / WebViewActivity 对齐，但按 iOS 平台切分）：
///   · 成绩 / 学分绩点 → 原生数据链 `GradesContent`（已存在，复用环境里的 `app.api`）；
///   · 推荐课表 / 校历 → 原生二级页 push；
///   · 自主选课 / 学工服务 → 共享教务会话的内置网页 `WebScreen`。
///
/// 网页入口为什么用 `.sheet` 而不是 push：`WebScreen` 内部自带一根 `NavigationStack`
/// 和「关闭」按钮（`@Environment(\.dismiss)`），若再 push 进外层导航栈会形成嵌套导航栈 /
/// 双导航栏。HomeView 的既有实现与注释也是这个口径，这里保持一致。
/// `.sheet` 不会自动继承 `@EnvironmentObject`，所以显式 `.environmentObject(app)`
/// （与 HomeView / MineView 的写法一致）。
struct JwServicesView: View {
    @EnvironmentObject private var app: AppState

    /// 共享会话网页的呈现目标
    @State private var web: JwWebTarget?

    var body: some View {
        List {
            accountSection
            gradesSection
            teachingSection
            affairsSection
        }
        .listStyle(.insetGrouped)
        .background(T.bgPage)
        .navigationTitle("教务服务")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $web) { target in
            WebScreen(url: target.url, title: target.title)
                .environmentObject(app)
        }
    }

    // MARK: - 账号状态卡

    private var accountSection: some View {
        Section {
            accountCard
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                .listRowBackground(Color.clear)
        }
    }

    private var accountCard: some View {
        Card(padding: 16) {
            HStack(spacing: 12) {
                Image(systemName: app.loggedIn ? "checkmark.circle.fill" : "person.crop.circle")
                    .font(.system(size: 26))
                    .foregroundStyle(app.loggedIn ? T.brand : T.textTertiary)
                    .frame(width: 40, alignment: .leading)

                VStack(alignment: .leading, spacing: 4) {
                    Text(accountTitle)
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    Text(accountSubtitle)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                statusBadge
            }

            if !app.loggedIn {
                Button {
                    app.switchTab(4)
                } label: {
                    Text("前往「我的」登录")
                        .font(F.sub)
                        .foregroundStyle(T.brand)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: T.minTap)
                        .background(T.brandSoft)
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)
                .padding(.top, 12)
            }
        }
    }

    private var statusBadge: some View {
        Text(app.loggedIn ? "已登录" : "访客")
            .font(F.caption2)
            .foregroundStyle(app.loggedIn ? T.brand : T.textSecondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 4)
            .background(app.loggedIn ? T.brandSoft : T.bgSunken)
            .clipShape(Capsule())
    }

    private var accountTitle: String {
        if !app.studentName.isEmpty { return app.studentName }
        return app.loggedIn ? "已登录教务" : "未登录教务"
    }

    private var accountSubtitle: String {
        app.loggedIn
            ? "成绩、选课、学工页面共享本次登录会话"
            : "到「我的」里登录教务，才能查看成绩与选课"
    }

    // MARK: - 成绩查询

    private var gradesSection: some View {
        Section {
            linkRow("成绩", systemImage: "chart.bar.fill") {
                GradesContent(kind: .grades)
                    .navigationTitle("成绩")
                    .navigationBarTitleDisplayMode(.inline)
            }
            linkRow("学分绩点", systemImage: "chart.line.uptrend.xyaxis") {
                GradesContent(kind: .gpa)
                    .navigationTitle("学分绩点")
                    .navigationBarTitleDisplayMode(.inline)
            }
        } header: {
            Text("成绩查询")
        } footer: {
            Text("成绩与绩点由 App 读取教务「信息查询」数据，共享 App 内登录会话。")
        }
    }

    // MARK: - 教学服务

    private var teachingSection: some View {
        Section {
            linkRow("推荐课表", systemImage: "square.grid.2x2") {
                RecommendedTimetableView()
            }
            webRow("自主选课", systemImage: "checkmark.rectangle",
                   url: JwClient.baseURL, webTitle: "自主选课")
            linkRow("校历", systemImage: "calendar.badge.clock") {
                CalendarPageView()
            }
        } header: {
            Text("教学服务")
        } footer: {
            Text("教务 / 学工页面共享 App 内登录会话；网页入口在应用内浏览器打开，无需重复登录。")
        }
    }

    // MARK: - 学工服务

    private var affairsSection: some View {
        Section {
            webRow("奖助贷", systemImage: "yensign.circle", badge: "外部网页",
                   url: JwClient.affairsURL, webTitle: "奖助贷")
            webRow("请假", systemImage: "clock", badge: "外部网页",
                   url: JwClient.affairsURL, webTitle: "请假")
            webRow("综合素质", systemImage: "star.fill", badge: "外部网页",
                   url: JwClient.affairsURL, webTitle: "综合素质")
        } header: {
            Text("学工服务")
        } footer: {
            Text("教务 / 学工页面共享 App 内登录会话；标注「外部网页」的入口由学工系统网页呈现。")
        }
    }

    // MARK: - 行构造

    private func linkRow<D: View>(_ title: String, systemImage: String,
                                  @ViewBuilder destination: () -> D) -> some View {
        NavigationLink(destination: destination) {
            rowLabel(title, systemImage: systemImage, badge: nil)
        }
    }

    private func webRow(_ title: String, systemImage: String, badge: String? = nil,
                        url: String, webTitle: String? = nil) -> some View {
        Button {
            web = JwWebTarget(url: url, title: webTitle ?? title)
        } label: {
            rowLabel(title, systemImage: systemImage, badge: badge)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func rowLabel(_ title: String, systemImage: String, badge: String?) -> some View {
        HStack(spacing: 12) {
            Label {
                Text(title)
                    .font(F.body)
                    .foregroundStyle(T.textPrimary)
            } icon: {
                Image(systemName: systemImage)
                    .font(.system(size: 17))
                    .foregroundStyle(T.brand)
                    .frame(width: 28, alignment: .center)
            }
            Spacer(minLength: 6)
            if let badge {
                Text(badge)
                    .font(F.caption2)
                    .foregroundStyle(T.textTertiary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(T.bgSunken)
                    .clipShape(Capsule())
            }
        }
        .frame(minHeight: T.minTap)
    }
}

/// 共享会话网页的呈现目标（`Identifiable` 供 `.sheet(item:)` 使用）。
/// 与 HomeView 的 `WebTarget` 分开命名，避免跨页面耦合；本组三个页面复用它。
struct JwWebTarget: Identifiable {
    let id = UUID()
    let url: String
    let title: String
}
