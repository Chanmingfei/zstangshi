import SwiftUI
import Shared

/// 只读周课表网格 —— 从 TimetableView 的课程块渲染抽出来，供「推荐课表」复用。
/// 与个人课表共用 Kotlin `TimetableGrid.cells`，所以周次展开/配色口径一致；
/// 只是没有编辑/加课，点课程弹出只读详情。
struct WeekGridView: View {
    let timetable: Timetable
    @Binding var week: Int
    let maxWeek: Int
    let sourceNote: String

    @State private var detail: GridCell?

    private let sectionHeight: CGFloat = 54
    private let headHeight: CGFloat = 44
    private let dayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
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

    private var maxSection: Int { Int(Sections.shared.count()) }
    private var cells: [GridCell] { TimetableGrid.shared.cells(t: timetable, week: Int32(week)) }
    private var today: Int {
        let w = Calendar.current.component(.weekday, from: Date())
        return w == 1 ? 7 : w - 1
    }

    var body: some View {
        VStack(spacing: 0) {
            if !sourceNote.isEmpty {
                Text(sourceNote)
                    .font(.caption2).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12).padding(.vertical, 6)
            }
            weekBar
            Divider()
            grid
        }
        .sheet(isPresented: Binding(
            get: { detail != nil },
            set: { if !$0 { detail = nil } }
        )) {
            if let c = detail { detailSheet(c) }
        }
    }

    private var weekBar: some View {
        HStack(spacing: 10) {
            Text(timetable.semester.name.isEmpty ? "推荐课表" : timetable.semester.name)
                .font(.system(size: 16, weight: .semibold))
            Spacer()
            Button { if week > 1 { week -= 1 } } label: {
                Image(systemName: "chevron.left")
            }.disabled(week <= 1)
            Menu {
                ForEach(1...max(maxWeek, 1), id: \.self) { w in
                    Button("第 \(w) 周") { week = w }
                }
            } label: {
                Text("第 \(week) 周")
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            Button { if week < maxWeek { week += 1 } } label: {
                Image(systemName: "chevron.right")
            }.disabled(week >= maxWeek)
            // 安卓推荐课表也有「回到本周」。
            Button { week = currentWeek() } label: {
                Image(systemName: "arrow.uturn.left.circle")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    private func currentWeek() -> Int {
        let s = timetable.semester
        guard !s.startDate.isEmpty else { return 1 }
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let today = Dates.shared.daysFromCivil(
            y0: Int32(c.year ?? 2026), m: Int32(c.month ?? 1), d: Int32(c.day ?? 1))
        return Int(WeekMath.shared.currentWeek(
            startDate: s.startDate, totalWeeks: Int32(max(1, s.totalWeeks)), today: today))
    }

    private var grid: some View {
        ScrollView(.vertical) {
            HStack(alignment: .top, spacing: 0) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: headHeight)
                    ForEach(1...max(maxSection, 1), id: \.self) { s in
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
                ForEach(0..<7, id: \.self) { i in dayColumn(day: i + 1) }
            }
            .padding(.trailing, 6).padding(.bottom, 40)
        }
    }

    private func dayColumn(day: Int) -> some View {
        let h = CGFloat(max(maxSection, 1)) * sectionHeight
        let isToday = day == today
        return VStack(spacing: 0) {
            VStack(spacing: 1) {
                Text(dayNames[day - 1])
                    .font(.system(size: 12, weight: isToday ? .bold : .regular))
                    .foregroundColor(isToday ? Color(red: 0.0, green: 0.48, blue: 1.0)
                                     : (day >= 6 ? .secondary : .primary))
            }
            .frame(height: headHeight).frame(maxWidth: .infinity)
            ZStack(alignment: .top) {
                VStack(spacing: 0) {
                    ForEach(1...max(maxSection, 1), id: \.self) { _ in
                        Rectangle()
                            .fill(isToday ? Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.04)
                                  : Color(UIColor.secondarySystemGroupedBackground))
                            .frame(height: sectionHeight)
                            .overlay(Rectangle().stroke(Color(UIColor.separator).opacity(0.18), lineWidth: 0.5))
                    }
                }
                ForEach(cells.filter { Int($0.day) == day }, id: \.courseKey) { c in cellBlock(c) }
            }
            .frame(height: h)
        }
        .frame(maxWidth: .infinity)
    }

    private func cellBlock(_ cell: GridCell) -> some View {
        let span = CGFloat(Int(cell.endSection) - Int(cell.startSection) + 1)
        let idx = abs(Int(cell.colorIndex)) % palette.count
        return Button { detail = cell } label: {
            VStack(alignment: .leading, spacing: 1) {
                Text(cell.name).font(.system(size: 10, weight: .medium))
                    .foregroundColor(ink[idx]).lineLimit(4)
                if !cell.room.isEmpty {
                    Text(cell.room).font(.system(size: 9))
                        .foregroundColor(ink[idx].opacity(0.85)).lineLimit(3)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 3).padding(.vertical, 3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: span * sectionHeight - 3, alignment: .top)
            .background(palette[idx])
            .overlay(alignment: .leading) { Rectangle().fill(ink[idx]).frame(width: 2.5) }
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            .padding(.horizontal, 1)
            .offset(y: CGFloat(Int(cell.startSection) - 1) * sectionHeight + 1.5)
        }
        .buttonStyle(.plain)
    }

    private func detailSheet(_ c: GridCell) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(c.name).font(.title3).bold()
            HStack { Text("教师"); Spacer(); Text(c.teacher.isEmpty ? "—" : c.teacher) }
            HStack { Text("地点"); Spacer(); Text(c.room.isEmpty ? "—" : c.room) }
            HStack {
                Text("节次")
                Spacer()
                Text("第 \(c.startSection)-\(c.endSection) 节")
            }
            Spacer()
        }
        .padding(20)
        .presentationDetents([.medium])
    }
}
