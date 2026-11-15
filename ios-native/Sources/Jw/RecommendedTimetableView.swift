import SwiftUI

/// 「推荐课表」原生二级页（教务服务 → 推荐课表，无参构造，供 `NavigationLink` push）。
///
/// 数据真实性优先：教务没有对 App 开放「推荐课表」的筛选字典 / 课表查询接口，
/// 所以这里**不伪造任何课表结果**：
///   · 四级筛选是一组本地静态常见值（界面明确标注「来自本地缓存」），只用于演示原生级联交互；
///   · 「查询」只打开共享教务会话的 `WebScreen`，实际推荐课表由教务网页呈现；
///   · 「我的课表对照」读的是 `app.timetable` 本地课表，单独标注来源，绝不与推荐课表结果混淆。
struct RecommendedTimetableView: View {
    @EnvironmentObject private var app: AppState
    @State private var web: JwWebTarget?

    // 四级筛选的当前值；空串表示「不限」。切换上级时由对应 Binding 清空所有下级。
    @State private var grade = ""
    @State private var department = ""
    @State private var major = ""
    @State private var klass = ""

    var body: some View {
        List {
            noticeSection
            filterSection
            querySection
            localCompareSection
        }
        .listStyle(.insetGrouped)
        .background(T.bgPage)
        .navigationTitle("推荐课表")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $web) { target in
            WebScreen(url: target.url, title: target.title)
                .environmentObject(app)
        }
    }

    // MARK: - 说明卡（如实交代数据来源）

    private var noticeSection: some View {
        Section {
            noticeCard
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                .listRowBackground(Color.clear)
        }
    }

    private var noticeCard: some View {
        Card(padding: 14) {
            VStack(alignment: .leading, spacing: 10) {
                Label {
                    Text("推荐课表由教务网页呈现")
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                } icon: {
                    Image(systemName: "info.circle.fill")
                        .foregroundStyle(T.brand)
                }

                Text("App 保留原生四级筛选入口；点「查询」会打开共享登录会话的教务系统网页，实际推荐课表由教务页面展示。")
                    .font(F.caption)
                    .foregroundStyle(T.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                Label {
                    Text("筛选项来自本地缓存")
                        .font(F.caption)
                        .foregroundStyle(T.warn)
                } icon: {
                    Image(systemName: "externaldrive.fill")
                        .foregroundStyle(T.warn)
                }

                Text("下列年级 / 院系 / 专业 / 班级是 App 本地缓存的常见示例值，不是教务实时清单，也不代表学校当前实际设置；筛选值不会提交给教务，App 更不会在原生界面里生成或伪造课表结果。")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - 四级级联筛选

    private var filterSection: some View {
        Section {
            Picker("年级", selection: gradeBinding) {
                Text("不限年级").tag("")
                ForEach(JwCascadeCache.grades, id: \.self) { Text($0).tag($0) }
            }
            .pickerStyle(.menu)

            Picker("院系", selection: departmentBinding) {
                Text("不限院系").tag("")
                ForEach(JwCascadeCache.departments, id: \.self) { Text($0).tag($0) }
            }
            .pickerStyle(.menu)

            Picker("专业", selection: majorBinding) {
                Text("不限专业").tag("")
                ForEach(JwCascadeCache.majors(department), id: \.self) { Text($0).tag($0) }
            }
            .pickerStyle(.menu)

            Picker("班级", selection: klassBinding) {
                Text("不限班级").tag("")
                ForEach(JwCascadeCache.classes(major), id: \.self) { Text($0).tag($0) }
            }
            .pickerStyle(.menu)
        } header: {
            Text("筛选条件 · 本地缓存")
        } footer: {
            Text("专业随所选院系收窄、班级随所选专业生成；切换上一级会清空下一级。这些值来自本地缓存，不是教务实时数据。")
        }
    }

    /// 用自定义 Binding 在 setter 里做级联清空，避开 onChange 的版本差异（部署目标 iOS 16）。
    /// 闭包是 escaping，显式写 `self.` 让捕获语义清晰。
    private var gradeBinding: Binding<String> {
        Binding(
            get: { self.grade },
            set: { newValue in
                self.grade = newValue
                self.department = ""
                self.major = ""
                self.klass = ""
            }
        )
    }

    private var departmentBinding: Binding<String> {
        Binding(
            get: { self.department },
            set: { newValue in
                self.department = newValue
                self.major = ""
                self.klass = ""
            }
        )
    }

    private var majorBinding: Binding<String> {
        Binding(
            get: { self.major },
            set: { newValue in
                self.major = newValue
                self.klass = ""
            }
        )
    }

    private var klassBinding: Binding<String> {
        Binding(get: { self.klass }, set: { newValue in self.klass = newValue })
    }

    // MARK: - 查询（只打开教务网页，不产生课表结果）

    private var querySection: some View {
        Section {
            Button {
                web = JwWebTarget(url: JwClient.baseURL, title: "推荐课表 · 教务系统")
            } label: {
                Label {
                    Text("查询推荐课表")
                        .font(F.bodyBold)
                        .foregroundStyle(.white)
                } icon: {
                    Image(systemName: "safari")
                        .foregroundStyle(.white)
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: T.minTap)
                .background(T.brand)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
            .listRowBackground(Color.clear)
        } footer: {
            Text("查询会打开共享登录会话的教务系统首页，请在网页里进入「信息查询 → 推荐课表」查看。本地缓存的筛选值不会提交给教务，App 不生成课表结果。")
        }
    }

    // MARK: - 我的课表对照（本地课表，单独标注来源）

    private var localCompareSection: some View {
        Section {
            if let t = app.timetable, !t.isEmpty {
                myTimetableCard(t)
            } else {
                emptyCompareCard
            }
        } header: {
            Text("我的课表对照 · 本地")
        } footer: {
            Text("来源：App 本地已同步课表，按当前教学周汇总，仅作对照，与教务推荐课表结果无关。")
        }
    }

    private func myTimetableCard(_ t: Timetable) -> some View {
        let week = app.currentWeek()
        return Card(padding: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Text("第 \(week) 周 · 本地课表 \(t.courses.count) 门")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                ForEach(1...7, id: \.self) { day in
                    weekRow(day: day, week: week, timetable: t)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
        .listRowBackground(Color.clear)
    }

    private func weekRow(day: Int, week: Int, timetable: Timetable) -> some View {
        let names = courseNames(day: day, week: week, timetable: timetable)
        let dayNames = ["一", "二", "三", "四", "五", "六", "日"]
        return HStack(alignment: .top, spacing: 8) {
            Text("周\(dayNames[day - 1])")
                .font(F.caption)
                .foregroundStyle(T.brand)
                .frame(width: 34, alignment: .leading)
            Text(names.isEmpty ? "—" : names.joined(separator: "、"))
                .font(F.caption)
                .foregroundStyle(names.isEmpty ? T.textTertiary : T.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 4)
    }

    private func courseNames(day: Int, week: Int, timetable: Timetable) -> [String] {
        var out: [String] = []
        for c in timetable.courses {
            let onThisDay = c.sessions.contains { $0.dayOfWeek == day && $0.isActive(week) }
            guard onThisDay else { continue }
            if !out.contains(c.name) { out.append(c.name) }
        }
        return out
    }

    private var emptyCompareCard: some View {
        VStack(spacing: 12) {
            EmptyCard(title: "本地还没有课表",
                      subtitle: "到「我的」里同步课表后，这里会显示一份简版周表作对照")
            Button {
                app.switchTab(4)
            } label: {
                Text("前往「我的」同步课表")
                    .font(F.bodyBold)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: T.minTap)
                    .background(T.brand)
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
            .buttonStyle(.plain)
        }
        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
        .listRowBackground(Color.clear)
    }
}

/// 本地缓存的四级筛选常见值（**示例值，不是教务实时清单**）。
/// 教务未对 App 开放筛选字典接口，这里只保留一组静态值用于演示原生级联交互；
/// 真实可选值以教务网页为准。年级按当前年份客观推导，不写死届别。
private enum JwCascadeCache {
    static var grades: [String] {
        let y = Calendar.current.component(.year, from: Date())
        return (0..<4).map { "\(y - $0) 级" }
    }

    static let departments = ["文学院", "理学院", "教育学院", "管理学院", "信息技术学院", "艺术学院"]

    static let majorMap: [String: [String]] = [
        "文学院": ["汉语言文学", "秘书学"],
        "理学院": ["数学与应用数学", "物理学"],
        "教育学院": ["小学教育", "学前教育"],
        "管理学院": ["会计学", "物流管理"],
        "信息技术学院": ["计算机科学与技术", "软件工程"],
        "艺术学院": ["音乐学", "美术学"]
    ]

    /// 专业随院系收窄；未选院系时没有可选项（只剩「不限专业」）。
    static func majors(_ dept: String) -> [String] {
        majorMap[dept] ?? []
    }

    /// 班级随专业生成；未选专业时没有可选项（只剩「不限班级」）。
    static func classes(_ major: String) -> [String] {
        guard !major.isEmpty else { return [] }
        return ["\(major) 1 班", "\(major) 2 班", "\(major) 3 班"]
    }
}
