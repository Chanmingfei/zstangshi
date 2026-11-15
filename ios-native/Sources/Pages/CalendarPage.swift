import SwiftUI

/// 校历页（对齐 Android `pages/CalendarPage.kt` 的月历视图）。
///
/// 结构：学期信息卡 → 月份切换（‹ 2026 年 11 月 › + 今天）→ 星期表头
/// → 6×7 日期格 → 选中那天的一句说明。
///
/// 格子里的信息只放「真的推得出来的」：
///   · 教学周里的日子带一个小点，当前周更亮；
///   · 今天描一圈品牌色；
///   · 不在本学期范围内的日子灰掉。
/// 放假、调休这类细项没有可靠数据源，就不编。
struct CalendarPage: View {
    @EnvironmentObject private var app: AppState

    @State private var year: Int = Calendar.current.component(.year, from: Date())
    @State private var month: Int = Calendar.current.component(.month, from: Date())
    @State private var selected: String = Dates.todayLocal()

    private let dayNames = ["一", "二", "三", "四", "五", "六", "日"]

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                termCard
                monthNav
                weekHeader
                monthGrid
                dayInfo
                eventsCard
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)
            .padding(.bottom, 30)
        }
        .scrollIndicators(.hidden)
        .background(T.bgPage.ignoresSafeArea())
        .navigationTitle("校历")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - 学期信息

    private var termCard: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(termName)
                .font(F.subhead)
                .foregroundStyle(T.textPrimary)

            Text(termSubline)
                .font(F.small)
                .foregroundStyle(T.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private var termName: String {
        if let name = app.timetable?.semester.name, !name.isEmpty { return name }
        return AcademicCalendar.termFor(today: Dates.todayLocal())?.name ?? "本学期"
    }

    private var termSubline: String {
        guard let t = app.timetable, Dates.valid(t.semester.startDate) else {
            return "还没设置开学日期，去「我的」补上才能算周次"
        }
        let total = t.semester.totalWeeks > 0 ? t.semester.totalWeeks : 20
        let w = WeekMath.currentWeek(startDate: t.semester.startDate, totalWeeks: total)
        var s = "第 1 周从 \(t.semester.startDate) 开始 · 共 \(total) 周"
        if w >= 1 && w <= total { s += " · 现在第 \(w) 周" }
        return s
    }

    // MARK: - 月份切换

    private var monthNav: some View {
        HStack(spacing: 0) {
            Button { shiftMonth(-1) } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(T.textPrimary)
                    .frame(width: 44, height: 40)
            }
            .buttonStyle(.pressable(0.9))

            Text("\(year) 年 \(month) 月")
                .font(F.headline)
                .foregroundStyle(T.textPrimary)
                .frame(maxWidth: .infinity)

            Button { shiftMonth(1) } label: {
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(T.textPrimary)
                    .frame(width: 44, height: 40)
            }
            .buttonStyle(.pressable(0.9))

            Button {
                Haptics.light()
                backToToday()
            } label: {
                Text("今天")
                    .font(F.caption)
                    .foregroundStyle(T.brand)
                    .frame(width: 56, height: 30)
                    .background(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .strokeBorder(T.borderSubtle, lineWidth: 0.9)
                    )
            }
            .buttonStyle(.pressable(0.94))
            .padding(.leading, 4)
        }
    }

    private var weekHeader: some View {
        HStack(spacing: 0) {
            ForEach(dayNames, id: \.self) { n in
                Text(n)
                    .font(F.tiny)
                    .foregroundStyle(T.textTertiary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 26)
            }
        }
    }

    // MARK: - 日期格

    private var monthGrid: some View {
        let offset = firstWeekdayOffset()
        let days = daysInMonth()
        let rows = max(4, Int(ceil(Double(offset + days) / 7.0)))
        let cellH: CGFloat = 52

        return VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { r in
                HStack(spacing: 0) {
                    ForEach(0..<7, id: \.self) { c in
                        let dayNo = r * 7 + c - offset + 1
                        if dayNo >= 1 && dayNo <= days {
                            dayCell(dayNo: dayNo, height: cellH)
                        } else {
                            Color.clear.frame(maxWidth: .infinity).frame(height: cellH)
                        }
                    }
                }
            }
        }
    }

    private func dayCell(dayNo: Int, height: CGFloat) -> some View {
        let ymd = String(format: "%04d-%02d-%02d", year, month, dayNo)
        let isToday = ymd == Dates.todayLocal()
        let isPicked = ymd == selected
        let (inTerm, isCurrentWeek) = termInfo(ymd)

        return Button {
            Haptics.tick()
            selected = ymd
        } label: {
            VStack(spacing: 0) {
                Text("\(dayNo)")
                    .font(.system(size: F.scaled(13.5), weight: isToday ? .bold : .regular))
                    .foregroundStyle(isPicked ? .white : T.textPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: height * 0.62)

                // 教学周小点
                Circle()
                    .fill(isPicked ? T.brand
                          : (isCurrentWeek ? T.brand : T.textTertiary))
                    .frame(width: inTerm ? 4.4 : 0, height: inTerm ? 4.4 : 0)

                Spacer(minLength: 0).frame(height: height * 0.1)
            }
            .frame(height: height)
            .background {
                if isPicked {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(T.brand)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                } else if isToday {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .fill(T.brandSoft)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                }
            }
            .opacity(inTerm || isToday || isPicked ? 1 : 0.45)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }

    private func termInfo(_ ymd: String) -> (inTerm: Bool, isCurrentWeek: Bool) {
        guard let t = app.timetable, Dates.valid(t.semester.startDate) else { return (false, false) }
        let total = t.semester.totalWeeks > 0 ? t.semester.totalWeeks : 20
        let w = Dates.weekOf(start: t.semester.startDate, ymd: ymd)
        let inTerm = w >= 1 && w <= total
        let current = WeekMath.currentWeek(startDate: t.semester.startDate, totalWeeks: total)
        return (inTerm, inTerm && w == current)
    }

    // MARK: - 选中那天

    private var dayInfo: some View {
        Text(dayInfoText)
            .font(F.caption)
            .foregroundStyle(T.textSecondary)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.7)
            )
    }

    private var dayInfoText: String {
        guard let t = app.timetable, Dates.valid(t.semester.startDate) else {
            return "选一天看看是第几周"
        }
        let total = t.semester.totalWeeks > 0 ? t.semester.totalWeeks : 20
        let week = Dates.weekOf(start: t.semester.startDate, ymd: selected)
        let wd = "一二三四五六日"
        let dw = Dates.dayOfWeek(selected)
        let wdChar = dw >= 1 && dw <= 7 ? String(Array(wd)[dw - 1]) : ""

        if week < 1 {
            return selected + " · 周" + wdChar + "\n开学前，第 1 周从 " + t.semester.startDate + " 开始"
        }
        if week > total {
            return selected + " · 周" + wdChar + "\n本学期（共 \(total) 周）已经结束"
        }
        var s = selected + " · 周" + wdChar + " · 第 " + week + " 周"
        let evs = AcademicCalendar.eventsOn(selected)
        if let first = evs.first { s += "\n" + first.label }
        return s
    }

    // MARK: - 校历事件

    @ViewBuilder
    private var eventsCard: some View {
        let upcoming = AcademicCalendar.upcoming(Dates.todayLocal(), 5)
        if !upcoming.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionTitle(text: "近期校历")
                    .padding(.bottom, 6)
                ForEach(upcoming.indices, id: \.self) { i in
                    let e = upcoming[i]
                    HStack {
                        Text(e.label)
                            .font(F.body)
                            .foregroundStyle(T.textPrimary)
                        Spacer()
                        Text(AcademicCalendar.shortDate(e.start)
                             + (e.start == e.end ? "" : " - " + AcademicCalendar.shortDate(e.end)))
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                    .padding(.vertical, 9)
                    if i < upcoming.count - 1 {
                        Divider().overlay(T.borderSubtle)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.7)
            )
        }
    }

    // MARK: - 月历计算

    /// 当月 1 号是周几 → 前面要空几格（周一为 0）
    private func firstWeekdayOffset() -> Int {
        let ymd = String(format: "%04d-%02d-01", year, month)
        let dow = Dates.dayOfWeek(ymd)   // 1=周一 … 7=周日
        return dow == 0 ? 0 : dow - 1
    }

    private func daysInMonth() -> Int {
        var comps = DateComponents()
        comps.year = year
        comps.month = month
        let cal = Calendar.current
        guard let date = cal.date(from: comps),
              let range = cal.range(of: .day, in: .month, for: date) else {
            return 30
        }
        return range.count
    }

    private func shiftMonth(_ delta: Int) {
        var m = month + delta
        var y = year
        if m < 1 { m = 12; y -= 1 }
        else if m > 12 { m = 1; y += 1 }
        month = m
        year = y
        Haptics.tick()
    }

    private func backToToday() {
        let cal = Calendar.current
        year = cal.component(.year, from: Date())
        month = cal.component(.month, from: Date())
        selected = Dates.todayLocal()
    }
}