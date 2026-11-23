import SwiftUI
import Shared

/// 校历（月历视图）—— 对齐安卓 CalendarPage。
///
/// 格子里的信息只放「真的推得出来的」：教学周里的小点（当前周更亮）、今天描边、
/// 选中日圆角底；放假/调休没有可靠数据源就不编。所有计算在 Kotlin
/// `AcademicCalendar`（共享层），Swift 只负责画格子。
struct CalendarView: View {
    private let cal = AcademicCalendar.shared

    @State private var year: Int
    @State private var month: Int
    @State private var selected: String
    @State private var termName = "本学期"
    @State private var startDate = ""
    @State private var totalWeeks = 20

    private let weekNames = ["一", "二", "三", "四", "五", "六", "日"]
    private let brand = Color(red: 0.46, green: 0.08, blue: 0.10)

    init() {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let y = c.year ?? 2026
        let m = c.month ?? 11
        _year = State(initialValue: y)
        _month = State(initialValue: m)
        let ymd = Dates.shared.formatYmd(
            epochDay: Int32(Dates.shared.daysFromCivil(
                y0: Int32(y), m: Int32(m), d: Int32(c.day ?? 1))))
        _selected = State(initialValue: ymd)
    }

    private var todayEpoch: Int32 {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return Dates.shared.daysFromCivil(
            y0: Int32(c.year ?? 2026), m: Int32(c.month ?? 11), d: Int32(c.day ?? 1))
    }

    private var cells: [AcademicCalendar.Cell] {
        cal.monthCells(
            year: Int32(year), month: Int32(month),
            startDate: startDate.isEmpty ? nil : startDate,
            totalWeeks: Int32(totalWeeks),
            todayEpochDay: todayEpoch,
            selectedYmd: selected)
    }

    private var offset: Int { Int(cal.monthOffset(year: Int32(year), month: Int32(month))) }

    private var dayInfo: String {
        cal.dayInfo(selectedYmd: selected, startDate: startDate, totalWeeks: Int32(totalWeeks))
    }

    private var termSubtitle: String {
        guard !startDate.isEmpty else { return "还没设置开学日期，去「我的」补上才能算周次" }
        let w = Int(currentWeek)
        var s = "第 1 周从 \(startDate) 开始 · 共 \(totalWeeks) 周"
        if w >= 1 && w <= totalWeeks { s += " · 现在第 \(w) 周" }
        return s
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                termCard
                monthNav
                weekHeader
                monthGrid
                dayInfoCard
            }
            .padding(14)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("校历")
        .navigationBarTitleDisplayMode(.inline)
        .task { loadTerm() }
    }

    // MARK: 学期信息卡

    private var termCard: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(termName).font(.system(size: 15, weight: .bold))
            Text(termSubtitle)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: 月份切换

    private var monthNav: some View {
        HStack {
            Button { shiftMonth(-1) } label: {
                Image(systemName: "chevron.left").font(.title3).frame(width: 44)
            }
            Spacer()
            Text("\(year) 年 \(month) 月").font(.system(size: 16, weight: .bold))
            Spacer()
            Button { shiftMonth(1) } label: {
                Image(systemName: "chevron.right").font(.title3).frame(width: 44)
            }
            Button("今天") { backToToday() }
                .font(.caption)
                .foregroundColor(brand)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(brand.opacity(0.5), lineWidth: 1))
        }
        .padding(.vertical, 4)
    }

    private func shiftMonth(_ delta: Int) {
        month += delta
        if month < 1 { month = 12; year -= 1 }
        else if month > 12 { month = 1; year += 1 }
    }

    private func backToToday() {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        year = c.year ?? 2026
        month = c.month ?? 11
        selected = Dates.shared.formatYmd(
            epochDay: Int32(Dates.shared.daysFromCivil(
                y0: Int32(year), m: Int32(month), d: Int32(c.day ?? 1))))
    }

    // MARK: 星期表头

    private var weekHeader: some View {
        HStack(spacing: 0) {
            ForEach(weekNames, id: \.self) { n in
                Text(n).font(.system(size: 12)).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: 月历格

    private var monthGrid: some View {
        let rows = Int(cal.monthRows(year: Int32(year), month: Int32(month)))
        return VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<7, id: \.self) { col in
                        cellView(row: row, col: col)
                    }
                }
            }
        }
        .padding(.vertical, 6)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    @ViewBuilder
    private func cellView(row: Int, col: Int) -> some View {
        let idx = row * 7 + col
        if let cell = cells.first(where: { $0.dayNo > 0 && indexOf(dayNo: Int($0.dayNo)) == idx }) {
            cellContent(cell)
        } else {
            Color.clear.frame(height: 52)
        }
    }

    /// 该格子在 42 格数组里的真实下标 = dayNo + offset - 1。
    private func indexOf(dayNo: Int) -> Int { dayNo + offset - 1 }

    @ViewBuilder
    private func cellContent(_ cell: AcademicCalendar.Cell) -> some View {
        let picked = cell.isSelected
        VStack(spacing: 6) {
            // 选中（含今天）= 蓝色实心圆 + 白字，和安卓一致
            Text("\(cell.dayNo)")
                .font(.system(size: 14, weight: picked ? .semibold : .regular))
                .foregroundColor(picked ? .white : .primary)
                .frame(width: 32, height: 32)
                .background(Circle().fill(picked ? brand : Color.clear))
            // 教学周小点：当前周/选中=蓝，其他教学周=灰，非教学周不画
            Circle()
                .fill(cell.inTeachingWeek
                      ? (picked || cell.week == currentWeek ? brand : Color.secondary.opacity(0.4))
                      : Color.clear)
                .frame(width: 4, height: 4)
        }
        .frame(maxWidth: .infinity, minHeight: 52)
        .contentShape(Rectangle())
        .onTapGesture { selected = cell.ymd }
    }

    private var currentWeek: Int32 {
        guard !startDate.isEmpty else { return -1 }
        return WeekMath.shared.currentWeek(
            startDate: startDate, totalWeeks: Int32(totalWeeks), today: todayEpoch)
    }

    // MARK: 选中说明

    private var dayInfoCard: some View {
        Text(dayInfo)
            .font(.footnote)
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(UIColor.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: 数据

    private func loadTerm() {
        guard let t = TimetableStore.load() else { return }
        termName = t.semester.name.isEmpty ? "本学期" : t.semester.name
        startDate = t.semester.startDate
        totalWeeks = Int(t.semester.totalWeeks)
    }
}
