import SwiftUI

// MARK: - 选择周次

/// 点顶栏「第 N 周」弹出的周次格子（对应 Android `openWeekPicker`）。
struct WeekPickerSheet: View {
    let current: Int
    let total: Int
    var onPick: (Int) -> Void

    @Environment(\.dismiss) private var dismiss

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 6)

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("选择周次")
                .font(F.pageTitle)
                .foregroundStyle(T.textPrimary)
                .padding(.top, 18)
                .padding(.horizontal, 18)

            ScrollView {
                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(1...max(1, total), id: \.self) { w in
                        let on = w == current
                        Button {
                            onPick(w)
                            dismiss()
                        } label: {
                            Text("\(w)")
                                .font(.system(size: 14, weight: on ? .bold : .regular))
                                .foregroundStyle(on ? .white : T.textPrimary)
                                .frame(maxWidth: .infinity)
                                .frame(height: 42)
                                .background(on ? T.brand : T.bgSunken)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 24)
            }
        }
        .background(T.bgPage)
    }
}

// MARK: - 课程详情

/// 点课块弹出的详情（对应 Android `openDetail`）。
struct CourseDetailSheet: View {
    let course: Course
    let session: Session
    let week: Int
    let schedule: Schedule

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(T.courseColor(course.colorIndex))
                    .frame(width: 4, height: 22)
                Text(course.name)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(T.textPrimary)
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(T.textTertiary)
                        .frame(width: 30, height: 30)
                        .background(T.bgSunken)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 20)
            .padding(.horizontal, 18)

            ScrollView {
                VStack(spacing: 0) {
                    detailRow("时间", timeText)
                    detailRow("地点", placeText)
                    detailRow("教师", session.teacher(for: week, courseTeacher: course.teacher))
                    detailRow("周次", weeksText)
                    if course.credit > 0 {
                        detailRow("学分", trimZero(course.credit))
                    }
                    if !course.category.isEmpty {
                        detailRow("课程性质", course.category)
                    }
                    if !course.examType.isEmpty {
                        detailRow("考核方式", course.examType)
                    }
                    if !course.note.isEmpty {
                        detailRow("备注", course.note)
                    }
                    if !session.roomType.isEmpty {
                        detailRow("场地类别", session.roomType)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 8)
                .padding(.bottom, 28)
            }
        }
        .background(T.bgPage)
    }

    private var timeText: String {
        if session.hasCustomTime {
            return "\(session.timeStart)-\(session.timeEnd)"
        }
        let a = session.startSection, b = max(session.startSection, session.endSection)
        let sec = a == b ? "第\(a)节" : "\(a)-\(b)节"
        return "\(sec) · \(schedule.startTime(a))-\(schedule.endTime(b))"
    }

    private var placeText: String {
        var parts: [String] = []
        if !session.campus.isEmpty { parts.append(session.campus) }
        if !session.location.isEmpty { parts.append(session.location) }
        return parts.isEmpty ? "—" : parts.joined(separator: " ")
    }

    private var weeksText: String {
        if session.weeks.isEmpty {
            return session.weeksRaw.isEmpty ? "每周" : session.weeksRaw
        }
        return "\(WeekRange.compress(session.weeks)) 周（共 \(session.weeks.count) 周）"
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(T.textTertiary)
                .frame(width: 68, alignment: .leading)
            Text(value.isEmpty ? "—" : value)
                .font(.system(size: 14))
                .foregroundStyle(T.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 11)
        .overlay(alignment: .bottom) {
            Rectangle().fill(T.border).frame(height: 0.6)
        }
    }

    private func trimZero(_ v: Double) -> String {
        v == v.rounded() ? String(Int(v)) : String(format: "%.1f", v)
    }
}

// MARK: - 校历

/// 校历：内置的学期起止 + 当前进度（Android 有独立的 CalendarActivity，
/// iOS 首版先做成一张卡片式 sheet，信息一致，交互更轻）。
struct CalendarSheet: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(AcademicCalendar.terms, id: \.id) { term in
                        termCard(term)
                    }
                    Text("校历来源：教务处 jwc.tstc.edu.cn。学校发布新校历后，App 更新一版即可。")
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                        .padding(.top, 4)
                }
                .padding(16)
            }
            .background(T.bgPage)
            .navigationTitle("校历")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    private func termCard(_ term: AcademicCalendar.Term) -> some View {
        let end = AcademicCalendar.endDate(term)
        let current = AcademicCalendar.term(for: WeekMath.today())?.id == term.id
        let week = WeekMath.weekOfDate(start: term.classStart, date: WeekMath.today())

        return Card(padding: 16) {
            HStack {
                Text(term.name)
                    .font(F.bodyBold)
                    .foregroundStyle(T.textPrimary)
                Spacer()
                if current {
                    Text("当前学期")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(T.brand)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(T.brandSoft)
                        .clipShape(Capsule())
                }
            }
            Text("第 1 周周一 \(term.classStart) · 共 \(term.teachingWeeks) 周")
                .font(F.sub)
                .foregroundStyle(T.textSecondary)
                .padding(.top, 8)
            Text("结束 \(end)")
                .font(F.sub)
                .foregroundStyle(T.textSecondary)
                .padding(.top, 3)
            if current, week >= 1 {
                Text("今天是第 \(week) 教学周")
                    .font(F.sub)
                    .foregroundStyle(T.brand)
                    .padding(.top, 3)
            }
        }
    }
}

// MARK: - 学期与教学周

/// 「我的 → 课表设置 → 学期与教学周」。
/// 只保留这一项（用户明确要求：其余设置项不展示）。
struct TermSheet: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var year = ""
    @State private var term = 1
    @State private var startDate = Date()

    private var years: [String] {
        let current = Calendar.current.component(.year, from: Date())
        return (0..<4).map { "\(current - 2 + $0)-\(current - 1 + $0)" }
    }

    private var semesterId: String { "\(year)-\(term)" }

    var body: some View {
        NavigationStack {
            Form {
                Section("学年") {
                    Picker("学年", selection: $year) {
                        ForEach(years, id: \.self) { Text("\($0) 学年").tag($0) }
                    }
                }
                Section("学期") {
                    Picker("学期", selection: $term) {
                        Text("第一学期").tag(1)
                        Text("第二学期").tag(2)
                    }
                    .pickerStyle(.segmented)
                }
                Section {
                    DatePicker("第一周周一", selection: $startDate, displayedComponents: .date)
                        .environment(\.locale, Locale(identifier: "zh_CN"))
                } footer: {
                    Text("保存后按这个学期与第一周算周数，重启仍生效；非周一的日期会自动归到那一周的周一。")
                }
            }
            .navigationTitle("学期与教学周")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存并生效") {
                        app.applySemester(id: semesterId, name: "\(year) 学年", startDate: WeekMath.ymd(startDate))
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .onAppear(perform: loadCurrent)
        }
    }

    private func loadCurrent() {
        let id = app.timetable?.semester.id ?? app.store.activeSemester()
        let parts = id.split(separator: "-")
        if parts.count >= 3 {
            year = "\(parts[0])-\(parts[1])"
            term = Int(parts[2]) == 2 ? 2 : 1
        } else {
            let current = Calendar.current.component(.year, from: Date())
            year = "\(current - 1)-\(current)"
        }
        if let t = app.timetable, let d = WeekMath.parse(t.semester.startDate) {
            startDate = d
        } else if let d = WeekMath.parse(AcademicCalendar.term(id: id)?.classStart ?? "") {
            startDate = d
        }
    }
}
