import SwiftUI
import Shared

/// 课表模型：先读本地缓存秒开，再后台同步教务。
@MainActor
final class TimetableModel: ObservableObject {
    @Published var timetable: Timetable?
    @Published var week: Int = 1
    @Published var maxWeek: Int = 20
    @Published var cells: [GridCell] = []
    @Published var maxSection: Int = 12
    @Published var status = ""

    func bootstrap() async {
        if let cached = TimetableStore.load() {
            apply(cached, week: nil)
            status = "本地缓存"
        }
        await sync()
    }

    func sync() async {
        guard JwAuth.shared.loggedIn else {
            if timetable == nil { status = "未登录：点首页登录教务后同步" }
            return
        }
        status = "同步中…"
        guard let t = await JwAuth.shared.fetchTimetable() else {
            status = JwAuth.shared.message.isEmpty ? "同步失败" : JwAuth.shared.message
            return
        }
        TimetableStore.save(t)
        apply(t, week: nil)
        status = "已同步 · \(t.courses.count) 门课"
    }

    func apply(_ t: Timetable, week newWeek: Int?) {
        timetable = t
        maxWeek = Int(TimetableGrid.shared.maxWeek(t: t, def: Int32(max(20, t.semester.totalWeeks))))
        maxSection = Int(Sections.shared.count())
        if let w = newWeek { week = w }
        refresh()
    }

    func refresh() {
        guard let t = timetable else { return }
        cells = TimetableGrid.shared.cells(t: t, week: Int32(week))
    }

    func cellsOf(day: Int) -> [GridCell] {
        cells.filter { $0.day == Int32(day) }
    }

    func course(of key: String) -> Course? {
        timetable?.courseList().first { $0.key == key }
    }

    /// 当前教学周：有学期开始日就按日期推，否则用 1。
    func currentWeek() -> Int {
        guard let t = timetable else { return 1 }
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let today = Dates.shared.daysFromCivil(
            y0: Int32(comps.year ?? 2026), m: Int32(comps.month ?? 1), d: Int32(comps.day ?? 1))
        if t.semester.startDate.isEmpty { return 1 }
        return Int(WeekMath.shared.currentWeek(
            startDate: t.semester.startDate,
            totalWeeks: Int32(max(1, t.semester.totalWeeks)),
            today: today))
    }

    func addCourse(name: String, teacher: String, room: String, campus: String,
                   day: Int, start: Int, end: Int, weeks: [Int], color: Int) {
        guard let t = timetable else { return }
        _ = TimetableEdit.shared.addCourse(
            t: t, name: name, teacher: teacher, room: room, campus: campus,
            dayOfWeek: Int32(day), startSection: Int32(start), endSection: Int32(end),
            weeks: weeks.map { KotlinInt(int: Int32($0)) }, colorIndex: Int32(color))
        TimetableStore.save(t)
        refresh()
    }

    func updateCourse(_ c: Course, name: String, teacher: String, room: String, campus: String,
                      day: Int, start: Int, end: Int, weeks: [Int], color: Int) {
        TimetableEdit.shared.setColor(c: c, index: Int32(color))
        TimetableEdit.shared.updateCourse(
            c: c, name: name, teacher: teacher, room: room, campus: campus,
            dayOfWeek: Int32(day), startSection: Int32(start), endSection: Int32(end),
            weeks: weeks.map { KotlinInt(int: Int32($0)) })
        if let t = timetable { TimetableStore.save(t) }
        refresh()
    }

    func deleteCourse(_ c: Course) {
        guard let t = timetable else { return }
        _ = TimetableEdit.shared.removeCourse(t: t, key: c.key)
        TimetableStore.save(t)
        refresh()
    }
}

struct TimetableView: View {
    @StateObject private var model = TimetableModel()

    @State private var editingKey: String?
    @State private var editingCourse: Course?
    @State private var adding = false
    @State private var addDay = 1
    @State private var addSection = 1

    private let palette: [Color] = [
        Color(red: 0.90, green: 0.95, blue: 0.99), Color(red: 0.99, green: 0.93, blue: 0.91),
        Color(red: 0.92, green: 0.97, blue: 0.92), Color(red: 0.99, green: 0.96, blue: 0.89),
        Color(red: 0.95, green: 0.93, blue: 0.99), Color(red: 0.91, green: 0.97, blue: 0.97),
        Color(red: 0.99, green: 0.94, blue: 0.97), Color(red: 0.95, green: 0.95, blue: 0.92),
    ]
    private let ink: [Color] = [
        Color(red: 0.10, green: 0.35, blue: 0.62), Color(red: 0.62, green: 0.25, blue: 0.20),
        Color(red: 0.16, green: 0.45, blue: 0.22), Color(red: 0.55, green: 0.42, blue: 0.10),
        Color(red: 0.35, green: 0.26, blue: 0.60), Color(red: 0.10, green: 0.45, blue: 0.45),
        Color(red: 0.60, green: 0.22, blue: 0.42), Color(red: 0.35, green: 0.38, blue: 0.20),
    ]

    private let sectionHeight: CGFloat = 54
    private let headHeight: CGFloat = 44
    private let dayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Divider()
            if model.timetable == nil {
                Spacer()
                Text(model.status.isEmpty ? "还没有课表" : model.status)
                    .font(.footnote).foregroundColor(.secondary)
                Spacer()
            } else {
                grid
            }
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("")
        .navigationBarHidden(true)
        .task {
            model.week = model.currentWeek()
            await model.bootstrap()
        }
        .sheet(isPresented: Binding(
            get: { editingCourse != nil },
            set: { if !$0 { editingCourse = nil; editingKey = nil } }
        )) {
            if let c = editingCourse { editSheet(for: c) }
        }
        .sheet(isPresented: $adding) {
            CourseEditSheet(existing: nil, defaultDay: addDay,
                            defaultSection: addSection, maxWeek: max(model.maxWeek, 20)) { name, teacher, room, campus, day, s, e, weeks, color in
                model.addCourse(name: name, teacher: teacher, room: room, campus: campus,
                                day: day, start: s, end: e, weeks: weeks, color: color)
            }
        }
    }

    // MARK: 顶部

    private var topBar: some View {
        HStack(spacing: 10) {
            Text(semesterName).font(.system(size: 17, weight: .semibold))
            Spacer()
            Button {
                if model.week > 1 { model.week -= 1; model.refresh() }
            } label: { Image(systemName: "chevron.left") }
                .disabled(model.week <= 1)

            Menu {
                ForEach(1...max(model.maxWeek, 1), id: \.self) { w in
                    Button("第 \(w) 周") { model.week = w; model.refresh() }
                }
            } label: {
                Text("第 \(model.week) 周")
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }

            Button {
                if model.week < model.maxWeek { model.week += 1; model.refresh() }
            } label: { Image(systemName: "chevron.right") }
                .disabled(model.week >= model.maxWeek)

            Button {
                addDay = 1
                addSection = 1
                adding = true
            } label: {
                Image(systemName: "plus")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var semesterName: String {
        let n = model.timetable?.semester.name ?? ""
        return n.isEmpty ? "本学期" : n
    }

    // MARK: 网格

    private var grid: some View {
        ScrollView(.vertical) {
            HStack(alignment: .top, spacing: 0) {
                // 左侧节次 + 时间
                VStack(spacing: 0) {
                    Color.clear.frame(height: headHeight)
                    ForEach(1...max(model.maxSection, 1), id: \.self) { s in
                        VStack(spacing: 1) {
                            Text("\(s)").font(.system(size: 11, weight: .medium))
                            Text(Sections.shared.start(section: Int32(s)))
                                .font(.system(size: 8)).foregroundColor(.secondary)
                            Text(Sections.shared.end(section: Int32(s)))
                                .font(.system(size: 8)).foregroundColor(.secondary)
                        }
                        .frame(width: 40, height: sectionHeight)
                    }
                }

                ForEach(0..<7, id: \.self) { i in
                    dayColumn(day: i + 1)
                }
            }
            .padding(.trailing, 6)
            .padding(.bottom, 90)
        }
    }

    private func dayColumn(day: Int) -> some View {
        let h = CGFloat(max(model.maxSection, 1)) * sectionHeight
        let isToday = isTodayColumn(day)
        return VStack(spacing: 0) {
            VStack(spacing: 1) {
                Text(dayNames[day - 1])
                    .font(.system(size: 12, weight: isToday ? .bold : .regular))
                    .foregroundColor(isToday ? Color(red: 0.0, green: 0.48, blue: 1.0)
                                     : (day >= 6 ? .secondary : .primary))
                Text(dateLabel(day))
                    .font(.system(size: 9))
                    .foregroundColor(isToday ? Color(red: 0.0, green: 0.48, blue: 1.0) : .secondary)
            }
            .frame(height: headHeight)
            .frame(maxWidth: .infinity)

            ZStack(alignment: .top) {
                VStack(spacing: 0) {
                    ForEach(1...max(model.maxSection, 1), id: \.self) { _ in
                        Rectangle()
                            .fill(isToday ? Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.04)
                                  : Color(UIColor.secondarySystemGroupedBackground))
                            .frame(height: sectionHeight)
                            .overlay(Rectangle().stroke(Color(UIColor.separator).opacity(0.18), lineWidth: 0.5))
                    }
                }

                ForEach(Array(model.cellsOf(day: day).enumerated()), id: \.offset) { pair in
                    cellBlock(pair.element, onTap: { key in
                        if let c = model.course(of: key) {
                            editingKey = key
                            editingCourse = c
                        }
                    })
                }
            }
            .frame(height: h)
        }
        .frame(maxWidth: .infinity)
    }

    private func cellBlock(_ cell: GridCell, onTap: @escaping (String) -> Void) -> some View {
        let span = CGFloat(Int(cell.endSection) - Int(cell.startSection) + 1)
        let idx = abs(Int(cell.colorIndex)) % palette.count
        return Button {
            onTap(cell.courseKey)
        } label: {
            VStack(alignment: .leading, spacing: 1) {
                Text(cell.name)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(ink[idx])
                    .lineLimit(4)
                if !cell.room.isEmpty {
                    Text(cell.room)
                        .font(.system(size: 9))
                        .foregroundColor(ink[idx].opacity(0.85))
                        .lineLimit(3)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 3).padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: span * sectionHeight - 3, alignment: .top)
            .background(palette[idx])
            .overlay(alignment: .leading) {
                Rectangle().fill(ink[idx]).frame(width: 2.5)
            }
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            .padding(.horizontal, 1)
            .offset(y: CGFloat(Int(cell.startSection) - 1) * sectionHeight + 1.5)
        }
        .buttonStyle(.plain)
    }

    private func editSheet(for c: Course) -> some View {
        CourseEditSheet(existing: c, defaultDay: 1, defaultSection: 1,
                        maxWeek: max(model.maxWeek, 20)) { name, teacher, room, campus, day, s, e, weeks, color in
            model.updateCourse(c, name: name, teacher: teacher, room: room, campus: campus,
                               day: day, start: s, end: e, weeks: weeks, color: color)
        } onDelete: {
            model.deleteCourse(c)
        }
    }


    // MARK: 日期

    private func isTodayColumn(_ day: Int) -> Bool {
        guard model.week == model.currentWeek() else { return false }
        let w = Calendar.current.component(.weekday, from: Date())
        return (w == 1 ? 7 : w - 1) == day
    }

    /// 本周第 day 天的日期（以「今天」为锚点反推本周一）。
    private func dateLabel(_ day: Int) -> String {
        let cal = Calendar.current
        let todayWeekday = cal.component(.weekday, from: Date())
        let offsetToMonday = (todayWeekday == 1 ? -6 : 2 - todayWeekday)
        let weekDelta = (model.week - model.currentWeek()) * 7
        let target = cal.date(byAdding: .day,
                              value: offsetToMonday + (day - 1) + weekDelta,
                              to: Date()) ?? Date()
        let f = DateFormatter()
        f.dateFormat = "M/d"
        return f.string(from: target)
    }
}
