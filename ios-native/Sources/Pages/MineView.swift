import SwiftUI

/// 「我的」页（对应 Android `MinePage`）。
///
/// 五块内容，顺序与 Android 一致：
///   账号卡（姓名/学号 + 登录·同步按钮）→ 课表设置（只留「学期与教学周」）
///   → 外观（浅色/深色/跟随系统）→ 关于（版本/数据来源/开发者/荣誉出品/项目地址）→ 退出登录。
///
/// 课表设置里刻意只放一项：Android 那边原本也是三个模块，用户要求精简，iOS 同步精简。
struct MineView: View {
    @EnvironmentObject private var app: AppState

    @State private var showTerm = false
    @State private var syncing = false

    private let githubHome = "https://github.com/Chanmingfei/"
    private let giteeHome = "https://gitee.com/mingfei123/"
    private let repoGitHub = "https://github.com/Chanmingfei/zstangshi"
    private let repoGitee = "https://gitee.com/mingfei123/zstangshi"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                accountCard
                SectionTitle(text: "课表设置")
                Card { termRow }
                SectionTitle(text: "外观")
                Card { appearance }
                SectionTitle(text: "关于")
                Card { about }
                if app.loggedIn { logoutButton }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 10)
            .padding(.bottom, 28)
        }
        .background(T.bgPage)
        .sheet(isPresented: $showTerm) {
            TermSheet().environmentObject(app)
        }
    }

    // MARK: - 账号卡

    private var accountCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(T.brand.opacity(0.12)).frame(width: 48, height: 48)
                    Text(avatarText)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(T.brand)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(displayName)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(T.textPrimary)
                    Text(app.loggedIn ? maskedUser : "登录教务后可同步课表与成绩")
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
                Spacer()
                Text(app.loggedIn ? "已登录" : "访客")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(app.loggedIn ? T.brand : T.textSecondary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(app.loggedIn ? T.brandSoft : T.bgSunken)
                    .clipShape(Capsule())
            }

            Text(syncMeta)
                .font(F.sub)
                .foregroundStyle(T.textTertiary)
                .padding(.top, 12)

            HStack(spacing: 10) {
                Button {
                    app.showLogin = true
                } label: {
                    Text(app.loggedIn ? "重新登录" : "登录教务")
                        .font(F.bodyBold)
                        .foregroundStyle(T.brand)
                        .frame(maxWidth: .infinity)
                        .frame(height: 42)
                        .background(T.brandSoft)
                        .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)

                Button(action: syncTimetable) {
                    ZStack {
                        if syncing {
                            ProgressView().tint(.white)
                        } else {
                            Text("同步课表")
                                .font(F.bodyBold)
                                .foregroundStyle(.white)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 42)
                    .background(syncing ? T.brand.opacity(0.6) : T.brand)
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(syncing)
            }
            .padding(.top, 14)
        }
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .stroke(T.border, lineWidth: 0.8)
        )
    }

    private var displayName: String {
        if !app.studentName.isEmpty { return app.studentName }
        return app.user.isEmpty ? "未登录" : maskedUser
    }

    private var avatarText: String {
        if !app.studentName.isEmpty { return String(app.studentName.prefix(1)) }
        return "唐"
    }

    private var maskedUser: String {
        let no = app.user
        guard no.count >= 8 else { return no }
        return "\(no.prefix(4))****\(no.suffix(4))"
    }

    private var syncMeta: String {
        guard let t = app.timetable, !t.isEmpty else {
            return "本地还没有课表，登录教务同步一次"
        }
        let last = app.store.lastSync()
        let ago: String
        if last <= 0 {
            ago = "从未同步"
        } else {
            let minutes = Int(Date().timeIntervalSince1970 - last) / 60
            if minutes < 1 { ago = "刚刚" }
            else if minutes < 60 { ago = "\(minutes) 分钟前" }
            else if minutes < 1440 { ago = "\(minutes / 60) 小时前" }
            else { ago = "\(minutes / 1440) 天前" }
        }
        return "本地课表 \(t.courses.count) 门 · 上次同步 \(ago)"
    }

    // MARK: - 课表设置（只留一项）

    private var termRow: some View {
        InfoRow(title: "学期与教学周",
                value: app.timetable?.semester.name.isEmpty == false
                    ? app.timetable!.semester.name
                    : "点这里设置",
                showsChevron: true) {
            showTerm = true
        }
    }

    // MARK: - 外观

    private var appearance: some View {
        let index = Binding<Int>(
            get: {
                switch app.themeMode {
                case .light: return 0
                case .dark: return 1
                case .system: return 2
                }
            },
            set: { app.setTheme($0 == 0 ? .light : ($0 == 1 ? .dark : .system)) }
        )
        return Segmented(options: ["浅色", "深色", "跟随系统"], index: index)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
    }

    // MARK: - 关于

    private var about: some View {
        VStack(spacing: 0) {
            InfoRow(title: "版本", value: appVersion)
            RowDivider()
            InfoRow(title: "数据来源", value: "正方教务系统 · 教务处校历")
            RowDivider()

            // 开发者：姓名 + 两个带平台 logo 的链接（字母与文字等大）
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("开发者")
                        .font(F.body)
                        .foregroundStyle(T.textPrimary)
                    Spacer()
                    Text("陈铭霏")
                        .font(F.sub)
                        .foregroundStyle(T.textTertiary)
                }
                HStack(spacing: 8) {
                    LinkChip(systemIcon: "chevron.left.forwardslash.chevron.right",
                             text: "GitHub主页", url: githubHome)
                    LinkChip(systemIcon: "g.circle", text: "Gitee主页", url: giteeHome)
                    Spacer()
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 13)

            RowDivider()
            InfoRow(title: "荣誉出品", value: "百度唐山师范学院贴吧吧务组")
            RowDivider()

            VStack(alignment: .leading, spacing: 8) {
                Text("项目地址")
                    .font(F.body)
                    .foregroundStyle(T.textPrimary)
                HStack(spacing: 8) {
                    LinkChip(systemIcon: "chevron.left.forwardslash.chevron.right",
                             text: "GitHub仓库", url: repoGitHub)
                    LinkChip(systemIcon: "g.circle", text: "Gitee仓库", url: repoGitee)
                    Spacer()
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
        }
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "3.0.22"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "38"
        return "\(version) (\(build))"
    }

    // MARK: - 退出

    private var logoutButton: some View {
        Button {
            app.logout()
        } label: {
            Text("退出登录")
                .font(F.bodyBold)
                .foregroundStyle(Color(hex: 0xD93A3A))
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .background(T.bgSurface)
                .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                        .stroke(T.border, lineWidth: 0.8)
                )
        }
        .buttonStyle(.plain)
        .padding(.top, 18)
    }

    private func syncTimetable() {
        guard !syncing else { return }
        syncing = true
        Task {
            let error = await app.syncTimetable()
            syncing = false
            if let error {
                app.showToast(error)
            } else if let t = app.timetable {
                app.showToast("同步完成：\(t.courses.count) 门课")
            }
        }
    }
}
