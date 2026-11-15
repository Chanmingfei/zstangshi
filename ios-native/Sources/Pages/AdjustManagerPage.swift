import SwiftUI

/// 调课 / 停课 / 补课 / 放假 —— Adjust 的完整管理界面。
///
/// 背景：安卓的数据模型（`data/Timetable.kt` 的 `Adjust`）与渲染管线
/// （`data/WeekOverlay.kt`）都是完整的，但**安卓没有任何 UI 入口**
/// （全工程 grep 无 `t.adjusts.add(...)`）。也就是说这些能力在安卓里是「埋着的」。
///
/// iOS 端把它补全，四种类型的语义严格按 `WeekOverlay.build()` 的行为实现：
///
/// | 类型 | 语义 | 课表上的效果 |
/// |---|---|---|
/// | `cancel` | 某周某天某节停一次课 | 原格不画，显示「停课」 |
/// | `move` | 把某次课调到另一周/天/节 | 原格显示「已调走」，目标格显示「调课」 |
/// | `off` | 某天放假 | 当天全部课程置灰 + 表头标签 |
/// | `makeup` | 某天补课（上 fromDate 那天的课） | 目标日显示「补课」标签并画出课程 |
///
/// 注意：`cancel`/`move` 是**按课程名匹配**（`WeekOverlay.matches` 比较 `c.name == a.name`），
/// 不是按 course.key。
struct AdjustManagerPage: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var editing: Adjust?
    @State private var showNewSheet = false
    @State private var newKind: AdjustKind = .cancel
    @State private var confirmDelete: Adjust?

    enum AdjustKind: String, CaseIterable, Identifiable {
        case cancel, move, off, makeup
        var id: String { rawValue }

        var title: String {
            switch self {
            case .cancel: return "停课"
            case .move: return "调课"
            case .off: return "放假"
            case .makeup: return "补课"
            }
        }

        var icon: String {
            switch self {
            case .cancel: return "xmark.circle"
            case .move: return "arrow.left.arrow.right"
            case .off: return "sun.max"
            case .makeup: return "arrow.uturn.backward.circle"
            }
        }

        var hint: String {
            switch self {
            case .cancel: return "某次课本周不上（如老师临时调休）"
            case .move: return "把某次课挪到别的周 / 星期 / 节次"
            case .off: return "某天整天放假，当天课程置灰"
            case .makeup: return "某天补课，上另一天（日期）的课"
            }
        }

        var adjustsType: String {
            switch self {
            case .cancel: return Adjust.cancel
            case .move: return Adjust.move
            case .off: return Adjust.off
            case .makeup: return Adjust.makeup
            }
        }
    }

    private var adjusts: [Adjust] {
        app.timetable?.adjusts ?? []
    }

    var body: some View {
        List {
            Section {
                ForEach(AdjustKind.allCases) { k in
                    Button {
                        Haptics.light()
                        newKind = k
                        showNewSheet = true
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: k.icon)
                                .font(.system(size: 17))
                                .foregroundStyle(T.brand)
                                .frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(k.title)
                                    .font(F.body)
                                    .foregroundStyle(T.textPrimary)
                                Text(k.hint)
                                    .font(F.micro)
                                    .foregroundStyle(T.textTertiary)
                                    .multilineTextAlignment(.leading)
                            }
                            Spacer()
                            Image(systemName: "plus.circle")
                                .foregroundStyle(T.brand)
                        }
                        .padding(.vertical, 4)
                    }
                }
            } header: {
                Text("新增")
            } footer: {
                Text("这些调整只影响课表显示，不会改动教务数据；重新同步课表时会保留。")
            }

            if !adjusts.isEmpty {
                Section("已有的调整（\(adjusts.count)）") {
                    ForEach(adjusts, id: \.id) { a in
                        adjustRow(a)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    delete(a)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .navigationTitle("调课与停课")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showNewSheet) {
            AdjustEditorSheet(kind: newKind) { newAdjust in
                add(newAdjust)
            }
            .environmentObject(app)
        }
    }

    private func adjustRow(_ a: Adjust) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(kindTitle(a.type))
                    .font(F.micro)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(kindColor(a.type)))
                Text(describe(a))
                    .font(F.callout)
                    .foregroundStyle(T.textPrimary)
            }
            if !a.note.isEmpty {
                Text(a.note)
                    .font(F.micro)
                    .foregroundStyle(T.textTertiary)
            }
        }
        .padding(.vertical, 2)
    }

    private func kindTitle(_ type: String) -> String {
        switch type {
        case Adjust.cancel: return "停课"
        case Adjust.move: return "调课"
        case Adjust.off: return "放假"
        case Adjust.makeup: return "补课"
        default: return type
        }
    }

    private func kindColor(_ type: String) -> Color {
        switch type {
        case Adjust.cancel: return T.danger
        case Adjust.move: return T.brand
        case Adjust.off: return T.warn
        case Adjust.makeup: return T.go
        default: return T.textTertiary
        }
    }

    /// 一句话描述
    private func describe(_ a: Adjust) -> String {
        switch a.type {
        case Adjust.cancel:
            var s = a.name
            if a.week > 0 { s += " · 第\(a.week)周" }
            if a.day > 0 { s += " " + Dates.cnDay(a.day) }
            if a.start > 0 {
                s += " 第\(a.start)" + (a.end > a.start ? "-\(a.end)" : "") + "节"
            }
            return s
        case Adjust.move:
            var s = a.name
            if a.week > 0 { s += " 第\(a.week)周" }
            s += " → "
            if a.toWeek > 0 { s += "第\(a.toWeek)周 " }
            if a.toDay > 0 { s += Dates.cnDay(a.toDay) + " " }
            if a.toStart > 0 {
                s += "第\(a.toStart)" + (a.toEnd > a.toStart ? "-\(a.toEnd)" : "") + "节"
            }
            if a.hasToTime { s += " \(a.toTimeStart)-\(a.toTimeEnd)" }
            return s
        case Adjust.off:
            return a.date + (a.note.isEmpty ? " 放假" : "（\(a.note)）")
        case Adjust.makeup:
            return a.date + " 补 " + (a.fromDate.isEmpty ? "" : a.fromDate) + " 的课"
        default:
            return a.name
        }
    }

    private func add(_ a: Adjust) {
        guard var tt = app.timetable else {
            app.showToast("先同步一次课表")
            return
        }
        // 去重（sameAs 忽略 id/note）
        if tt.adjusts.contains(where: { $0.sameAs(a) }) {
            app.showToast("这条调整已经存在")
            return
        }
        tt.adjusts.append(a)
        app.saveTimetable(tt)
        Haptics.success()
        app.showToast("已添加\(kindTitle(a.type))")
    }

    private func delete(_ a: Adjust) {
        guard var tt = app.timetable else { return }
        tt.adjusts.removeAll { $0.id == a.id }
        app.saveTimetable(tt)
        Haptics.success()
        app.showToast("已删除")
    }
}

// MARK: - 新增表单

struct AdjustEditorSheet: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    let kind: AdjustManagerPage.AdjustKind
    var onSave: (Adjust) -> Void

    // 通用
    @State private var note = ""
    /// 停课/调课：选择哪门课
    @State private var courseName = ""
    @State private var week = 1
    @State private var day = 1
    @State private var startSection = 1
    @State private var endSection = 1
    /// 调课目标
    @State private var toWeek = 1
    @State private var toDay = 1
    @State private var toStart = 1
    @State private var toEnd = 1
    @State private var useCustomTime = false
    @State private var toTimeStart = "14:30"
    @State private var toTimeEnd = "16:30"
    /// 放假/补课日期
    @State private var date = Date()
    @State private var fromDate = Date()

    private let dayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    private var totalWeeks: Int {
        let n = app.timetable?.semester.totalWeeks ?? 20
        return n > 0 ? n : 20
    }
    private var currentWeek: Int {
        app.timetable.map {
            WeekMath.currentWeek(startDate: $0.semester.startDate,
                                 totalWeeks: totalWeeks)
        } ?? 1
    }
    private var courses: [Course] { app.timetable?.courses ?? [] }
    private var maxSections: Int { ScheduleSettings.maxSections }

    var body: some View {
        NavigationStack {
            Form {
                switch kind {
                case .cancel: cancelForm
                case .move: moveForm
                case .off: offForm
                case .makeup: makeupForm
                }

                Section("备注（可选）") {
                    TextField("如：老师出差", text: $note)
                }
            }
            .navigationTitle("新增\(kind.title)")
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
            .onAppear { initDefaults() }
        }
    }

    // MARK: 三种表单

    @ViewBuilder
    private var cancelForm: some View {
        Section("哪门课") {
            coursePicker
            Picker("第几周", selection: $week) {
                ForEach(1...totalWeeks, id: \.self) { w in
                    Text("第 \(w) 周").tag(w)
                }
            }
            Picker("星期", selection: $day) {
                ForEach(1...7, id: \.self) { d in
                    Text(dayNames[d - 1]).tag(d)
                }
            }
            Picker("起始节", selection: $startSection) {
                ForEach(1...maxSections, id: \.self) { s in
                    Text("第 \(s) 节").tag(s)
                }
            }
            Picker("结束节", selection: $endSection) {
                ForEach(startSection...maxSections, id: \.self) { s in
                    Text("第 \(s) 节").tag(s)
                }
            }
        }
    }

    @ViewBuilder
    private var moveForm: some View {
        Section("原位置") {
            coursePicker
            Picker("原第几周", selection: $week) {
                ForEach(1...totalWeeks, id: \.self) { w in
                    Text("第 \(w) 周").tag(w)
                }
            }
            Picker("原星期", selection: $day) {
                ForEach(1...7, id: \.self) { d in
                    Text(dayNames[d - 1]).tag(d)
                }
            }
        }
        Section("调到") {
            Picker("目标周", selection: $toWeek) {
                ForEach(1...totalWeeks, id: \.self) { w in
                    Text("第 \(w) 周").tag(w)
                }
            }
            Picker("目标星期", selection: $toDay) {
                ForEach(1...7, id: \.self) { d in
                    Text(dayNames[d - 1]).tag(d)
                }
            }
            Toggle("使用自定义时间", isOn: $useCustomTime)
            if useCustomTime {
                HStack {
                    Text("开始")
                    Spacer()
                    TextField("HH:mm", text: $toTimeStart)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numbersAndPunctuation)
                        .frame(width: 80)
                }
                HStack {
                    Text("结束")
                    Spacer()
                    TextField("HH:mm", text: $toTimeEnd)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numbersAndPunctuation)
                        .frame(width: 80)
                }
            } else {
                Picker("目标起始节", selection: $toStart) {
                    ForEach(1...maxSections, id: \.self) { s in
                        Text("第 \(s) 节").tag(s)
                    }
                }
                Picker("目标结束节", selection: $toEnd) {
                    ForEach(toStart...maxSections, id: \.self) { s in
                        Text("第 \(s) 节").tag(s)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var offForm: some View {
        Section("放假日期") {
            DatePicker("日期", selection: $date, displayedComponents: .date)
                .environment(\.locale, Locale(identifier: "zh_CN"))
            Text("当天所有课程会置灰，表头显示「放假」")
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
        }
    }

    @ViewBuilder
    private var makeupForm: some View {
        Section("补课日期") {
            DatePicker("补课日期", selection: $date, displayedComponents: .date)
                .environment(\.locale, Locale(identifier: "zh_CN"))
            DatePicker("上哪天的课", selection: $fromDate, displayedComponents: .date)
                .environment(\.locale, Locale(identifier: "zh_CN"))
            Text("补课日会显示「上 MM/dd 那天的课」的课程")
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
        }
    }

    private var coursePicker: some View {
        Picker("课程", selection: $courseName) {
            Text("选择课程").tag("")
            ForEach(courses, id: \.key) { c in
                Text(c.name).tag(c.name)
            }
        }
    }

    // MARK: 逻辑

    private func initDefaults() {
        week = max(1, min(totalWeeks, currentWeek))
        toWeek = week
        let today = Dates.dayOfWeek(Dates.todayLocal())
        day = today >= 1 && today <= 7 ? today : 1
        toDay = day
        if let first = courses.first { courseName = first.name }
    }

    private var canSave: Bool {
        switch kind {
        case .cancel, .move:
            return !courseName.isEmpty
        case .off, .makeup:
            return true
        }
    }

    private func save() {
        var a = Adjust()
        a.id = "adj_\(Int(Date().timeIntervalSince1970 * 1000))"
        a.type = kind.adjustsType
        a.note = note.trimmingCharacters(in: .whitespaces)

        switch kind {
        case .cancel:
            a.name = courseName
            a.week = week
            a.day = day
            a.start = min(startSection, endSection)
            a.end = max(startSection, endSection)

        case .move:
            a.name = courseName
            a.week = week
            a.day = day
            a.toWeek = toWeek
            a.toDay = toDay
            if useCustomTime {
                a.toTimeStart = toTimeStart
                a.toTimeEnd = toTimeEnd
            } else {
                a.toStart = min(toStart, toEnd)
                a.toEnd = max(toStart, toEnd)
            }

        case .off:
            a.date = ymd(date)

        case .makeup:
            a.date = ymd(date)
            a.fromDate = ymd(fromDate)
        }

        onSave(a)
        dismiss()
    }

    private func ymd(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: d)
    }
}