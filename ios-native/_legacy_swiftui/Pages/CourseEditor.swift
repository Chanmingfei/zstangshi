import SwiftUI

/// 课程编辑器 —— 严格对齐安卓 `pages/CourseEditor.kt`（491 行）。
///
/// 入口三处：课表页「＋」、课表页点空格、课程详情「编辑这门课」。
///
/// 可编辑 **8 项**（不要臆造别的字段）：
///   课程名 / 教师 / 教室 / 校区 / 星期 / 节次起止 / 上课周次 / 颜色
///
/// 关键语义：
///   · 周次是**离散集合勾选**（每行 7 格 + 全选/单周/双周快捷），
///     不是区间选择器 —— 因为教务周次本来就能是任意集合；
///   · 编辑模式才显示「删除」，且只删**当前时段**（多时段课程要逐段删）；
///   · 保存时：新建写 `Course.teacher`，编辑只写 `Session.teacher`；
///   · 学分 / 备注 / 自定义时间 / 按周换老师 / 场地类别 —— 编辑器**没有**入口，只展示。
struct CourseEditor: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    /// nil = 新建
    let course: Course?
    /// 要编辑的时段（编辑模式必传）
    let session: Session?
    /// 点空格进来时的初始星期
    var initialDay: Int = 1
    /// 点空格进来时的初始节次（0 = 未指定）
    var initialSection: Int = 0
    /// 保存后回调
    var onSaved: () -> Void

    // MARK: - 表单状态

    @State private var name = ""
    @State private var teacher = ""
    @State private var location = ""
    @State private var campus = ""
    @State private var day = 1
    @State private var startSection = 1
    @State private var endSection = 2
    @State private var weeks: Set<Int> = []
    @State private var colorIndex = 0
    @State private var showDeleteConfirm = false

    @FocusState private var focusedField: Field?
    private enum Field { case name, teacher, location, campus }

    private var isCreating: Bool { course == nil }
    private var totalWeeks: Int {
        let n = app.timetable?.semester.totalWeeks ?? 20
        return n > 0 ? n : 20
    }
    private var maxSections: Int { ScheduleSettings.maxSections }

    private let dayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    basicCard
                    placeCard
                    scheduleCard
                    weeksCard
                    colorCard
                }
                .padding(.horizontal, T.pagePadding)
                .padding(.top, 8)
                .padding(.bottom, 100)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(T.bgPage.ignoresSafeArea())
            .navigationTitle(isCreating ? "新增课程" : "编辑课程")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .fontWeight(.bold)
                        .disabled(!canSave)
                }
            }
            .safeAreaInset(edge: .bottom) {
                bottomBar
            }
            .confirmationDialog("删除这个时段？", isPresented: $showDeleteConfirm) {
                Button("删除时段", role: .destructive) { deleteSession() }
                Button("取消", role: .cancel) {}
            } message: {
                Text("只删除当前这一节课时段。如果是多时段课程，其余时段不受影响。")
            }
        }
        .onAppear { loadInitial() }
    }

    // MARK: - 基础信息

    private var basicCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            fieldRow("课程名") {
                TextField("如：高等数学", text: $name)
                    .focused($focusedField, equals: .name)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .teacher }
            }

            Divider().overlay(T.borderSubtle)

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("教师")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                    TextField("教师", text: $teacher)
                        .font(F.body)
                        .focused($focusedField, equals: .teacher)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Divider().frame(height: 30).overlay(T.borderSubtle)

                VStack(alignment: .leading, spacing: 4) {
                    Text("教室")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                    TextField("教室", text: $location)
                        .font(F.body)
                        .focused($focusedField, equals: .location)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 12)
        }
        .padding(.horizontal, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func fieldRow<Content: View>(_ label: String,
                                         @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
                .frame(width: 42, alignment: .leading)
            content()
                .font(F.body)
        }
        .padding(.vertical, 12)
    }

    // MARK: - 校区

    private var placeCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                Text("校区")
                    .font(F.micro)
                    .foregroundStyle(T.textTertiary)
                    .frame(width: 42, alignment: .leading)
                TextField("如：东校区（可不填）", text: $campus)
                    .font(F.body)
                    .focused($focusedField, equals: .campus)
            }
            .padding(.vertical, 12)
        }
        .padding(.horizontal, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    // MARK: - 星期 / 节次

    private var scheduleCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 星期
            Menu {
                ForEach(1...7, id: \.self) { d in
                    Button(dayNames[d - 1]) { day = d }
                }
            } label: {
                pickRow(label: "星期", value: dayNames[day - 1])
            }

            Divider().overlay(T.borderSubtle)

            // 节次起
            Menu {
                ForEach(1...maxSections, id: \.self) { s in
                    Button("第 \(s) 节") {
                        startSection = s
                        // 若结束节次小于起始，自动拉到同一节
                        if endSection < startSection { endSection = startSection }
                    }
                }
            } label: {
                pickRow(label: "节次起", value: "第 \(startSection) 节")
            }

            Divider().overlay(T.borderSubtle)

            // 节次止
            Menu {
                ForEach(startSection...maxSections, id: \.self) { s in
                    Button("第 \(s) 节") { endSection = s }
                }
            } label: {
                pickRow(label: "节次止", value: "第 \(endSection) 节")
            }

            // 时间提示（只读）
            HStack(spacing: 8) {
                Text(sectionTimeText)
                    .font(F.small)
                    .foregroundStyle(T.textTertiary)
                Spacer()
            }
            .padding(.bottom, 12)
            .padding(.top, 2)
        }
        .padding(.horizontal, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func pickRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(F.body)
                .foregroundStyle(T.textPrimary)
            Spacer()
            Text(value)
                .font(F.body)
                .foregroundStyle(T.brand)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(T.textTertiary)
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }

    private var sectionTimeText: String {
        let s = app.schedule.startTime(startSection)
        let e = app.schedule.endTime(endSection)
        return "\(s) - \(e)"
    }

    // MARK: - 上课周次（离散勾选）

    private var weeksCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("上课周次")
                    .font(F.captionBold)
                    .foregroundStyle(T.textSecondary)
                Spacer()
                quickButton("全选") { weeks = Set(1...totalWeeks) }
                quickButton("单周") { weeks = Set(stride(from: 1, through: totalWeeks, by: 2)) }
                quickButton("双周") { weeks = Set(stride(from: 2, through: totalWeeks, by: 2)) }
            }

            // 7 格一行
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 7),
                      spacing: 6) {
                ForEach(1...totalWeeks, id: \.self) { w in
                    let picked = weeks.contains(w)
                    Button {
                        Haptics.tick()
                        if picked { weeks.remove(w) } else { weeks.insert(w) }
                    } label: {
                        Text("\(w)")
                            .font(picked ? F.captionBold : F.caption)
                            .foregroundStyle(picked ? .white : T.textSecondary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 36)
                            .background(
                                RoundedRectangle(cornerRadius: 8, style: .continuous)
                                    .fill(picked ? T.brand : T.bgSunken)
                            )
                    }
                    .buttonStyle(.pressable(0.94))
                }
            }

            Text(weeksSummary)
                .font(F.tiny)
                .foregroundStyle(weeks.isEmpty ? T.warn : T.textTertiary)
        }
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func quickButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.light()
            action()
        } label: {
            Text(title)
                .font(F.micro)
                .foregroundStyle(T.brand)
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(Capsule().fill(T.brandSoft))
        }
        .buttonStyle(.plain)
    }

    /// 「已选 N 周 · 1-8,10-12周」
    private var weeksSummary: String {
        if weeks.isEmpty { return "还没有选周次（至少选一周才能保存）" }
        let sorted = weeks.sorted()
        let compressed = WeekRange.compress(sorted)
        return "已选 \(weeks.count) 周 · \(compressed)周"
    }

    // MARK: - 颜色

    private var colorCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("课程颜色")
                .font(F.captionBold)
                .foregroundStyle(T.textSecondary)

            HStack(spacing: 10) {
                ForEach(0..<8, id: \.self) { i in
                    Circle()
                        .fill(T.courseColor(i))
                        .frame(width: 30, height: 30)
                        .overlay(
                            Circle()
                                .strokeBorder(Color.white, lineWidth: selectedColor == i ? 2.5 : 0)
                        )
                        .overlay(
                            Circle()
                                .strokeBorder(T.courseColor(i), lineWidth: selectedColor == i ? 1 : 0)
                                .padding(-3)
                        )
                        .scaleEffect(selectedColor == i ? 1.08 : 1)
                        .animation(M.press, value: selectedColor)
                        .onTapGesture {
                            Haptics.tick()
                            colorIndex = i
                        }
                }
                Spacer(minLength: 0)
            }
        }
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    /// 8 色板实际取值（colorIndex 可能 >7，取模后要高亮对应那个）
    private var selectedColor: Int { abs(colorIndex) % 8 }

    // MARK: - 底部按钮

    private var bottomBar: some View {
        HStack(spacing: 10) {
            if !isCreating {
                Button {
                    showDeleteConfirm = true
                } label: {
                    Text("删除")
                }
                .buttonStyle(OutlineButtonStyle())
                .foregroundStyle(T.danger)
                .frame(width: 100)
            }

            Button("保存") { save() }
                .buttonStyle(PrimaryButtonStyle(enabled: canSave))
                .disabled(!canSave)
        }
        .padding(.horizontal, T.pagePadding)
        .padding(.top, 10)
        .padding(.bottom, 10)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Rectangle().fill(T.borderSubtle).frame(height: 0.5)
        }
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !weeks.isEmpty
    }

    // MARK: - 初始值

    private func loadInitial() {
        if let course, let session {
            // 编辑模式
            name = course.name
            teacher = session.teacher.isEmpty ? course.teacher : session.teacher
            location = session.location
            campus = session.campus
            day = max(1, min(7, session.dayOfWeek))
            startSection = max(1, min(maxSections, session.startSection))
            endSection = max(startSection, min(maxSections, session.endSection))
            weeks = Set(session.weeks.isEmpty ? Array(1...totalWeeks) : session.weeks)
            colorIndex = course.colorIndex
        } else {
            // 新建模式
            name = ""
            teacher = ""
            location = ""
            campus = ""
            day = max(1, min(7, initialDay))
            startSection = initialSection > 0 ? min(maxSections, initialSection) : 1
            endSection = min(maxSections, startSection + 1)
            // 新建时默认 1..min(16, totalWeeks) 全选
            weeks = Set(1...min(16, totalWeeks))
            colorIndex = (app.timetable?.courses.count ?? 0) % 8
        }
    }

    // MARK: - 保存

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty else {
            app.showToast("请填写课程名")
            Haptics.warning()
            return
        }
        guard !weeks.isEmpty else {
            app.showToast("至少选一周")
            Haptics.warning()
            return
        }
        guard var tt = app.timetable else {
            app.showToast("先同步一次课表，再手工加课")
            Haptics.warning()
            return
        }

        // 构造时段
        var ses = session ?? Session()
        ses.dayOfWeek = day
        ses.startSection = min(startSection, endSection)
        ses.endSection = max(startSection, endSection)
        ses.weeks = weeks.sorted()
        ses.weeksRaw = WeekRange.compress(ses.weeks) + "周"
        ses.location = location.trimmingCharacters(in: .whitespaces)
        ses.campus = campus.trimmingCharacters(in: .whitespaces)
        ses.teacher = teacher.trimmingCharacters(in: .whitespaces)

        if let course {
            // 编辑：只改 Session.teacher，不动 Course.teacher
            guard let ci = tt.courses.firstIndex(where: { $0.key == course.key }) else {
                app.showToast("找不到这门课，请重新同步")
                return
            }
            tt.courses[ci].name = trimmedName
            tt.courses[ci].colorIndex = colorIndex
            tt.courses[ci].colorTouched = true
            // 定位原时段（按原值匹配）
            if let old = session,
               let si = tt.courses[ci].sessions.firstIndex(where: {
                   $0.dayOfWeek == old.dayOfWeek
                       && $0.startSection == old.startSection
                       && $0.endSection == old.endSection
                       && $0.weeksRaw == old.weeksRaw
               }) {
                tt.courses[ci].sessions[si] = ses
            } else {
                tt.courses[ci].sessions.append(ses)
            }
        } else {
            // 新建：同时写 Course.teacher
            var c = Course()
            c.key = "custom_\(Int(Date().timeIntervalSince1970 * 1000))"
            c.name = trimmedName
            c.teacher = ses.teacher
            c.source = Timetable.sourceCustom
            c.colorIndex = colorIndex
            c.colorTouched = true
            c.sessions = [ses]
            tt.courses.append(c)
        }

        tt.normalizeColors()
        app.saveTimetable(tt)
        Haptics.success()
        app.showToast(isCreating ? "已添加课程" : "已保存")
        onSaved()
        dismiss()
    }

    private func deleteSession() {
        guard let course, let session, var tt = app.timetable else { return }
        guard let ci = tt.courses.firstIndex(where: { $0.key == course.key }) else { return }

        tt.courses[ci].sessions.removeAll {
            $0.dayOfWeek == session.dayOfWeek
                && $0.startSection == session.startSection
                && $0.endSection == session.endSection
                && $0.weeksRaw == session.weeksRaw
        }

        // 没有时段了就把整门课删掉
        if tt.courses[ci].sessions.isEmpty {
            tt.courses.remove(at: ci)
        }

        tt.normalizeColors()
        app.saveTimetable(tt)
        Haptics.success()
        app.showToast("已删除时段")
        onSaved()
        dismiss()
    }
}