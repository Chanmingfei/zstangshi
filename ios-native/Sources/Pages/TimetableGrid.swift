import SwiftUI

/// 课表网格：左侧节次时间列 + 7 个星期列，课程按时段绝对定位。
///
/// 为什么手写而不是 `LazyVGrid`：课块要跨行（3-4 节 = 两行高）、同日冲突要并排分栏，
/// 用网格布局反而要跟行高斗智斗勇；直接按 (start-1)*行高 定位最省事，也和 Android
/// `TimetableGridView` 的算法一致。
struct TimetableGrid: View {

    let timetable: Timetable
    let week: Int
    let schedule: Schedule
    var onTap: (Course, Session) -> Void

    private let timeWidth: CGFloat = 32
    private let rowHeight: CGFloat = 54
    private let headerHeight: CGFloat = 46

    private var rows: Int {
        let used = Schedule.maxSectionUsed(timetable)
        return min(Schedule.maxSections, max(10, max(used, min(schedule.sectionCount, 12))))
    }

    var body: some View {
        GeometryReader { geo in
            let colWidth = (geo.size.width - timeWidth) / 7

            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 0) {
                    dayHeader(colWidth: colWidth)
                    ZStack(alignment: .topLeading) {
                        gridLines(colWidth: colWidth)
                        ForEach(blocks) { block in
                            blockView(block, colWidth: colWidth)
                        }
                    }
                    .frame(height: rowHeight * CGFloat(rows))
                }
                .padding(.bottom, 8)
            }
        }
    }

    // MARK: - 表头

    private func dayHeader(colWidth: CGFloat) -> some View {
        let labels = WeekMath.weekDayLabels(timetable.semester.startDate, week: week)
        let today = WeekMath.todayWeekday()
        let todayIndex = WeekMath.weekOfDate(start: timetable.semester.startDate, date: WeekMath.today()) == week
            ? today : -1

        return HStack(spacing: 0) {
            Text("节")
                .font(.system(size: 10))
                .foregroundStyle(T.textTertiary)
                .frame(width: timeWidth, height: headerHeight)
            ForEach(0..<7, id: \.self) { i in
                VStack(spacing: 2) {
                    Text(["一", "二", "三", "四", "五", "六", "日"][i])
                        .font(.system(size: 11, weight: todayIndex == i + 1 ? .bold : .regular))
                        .foregroundStyle(todayIndex == i + 1 ? T.brand : T.textSecondary)
                    Text(i < labels.count ? labels[i] : "")
                        .font(.system(size: 9))
                        .foregroundStyle(todayIndex == i + 1 ? T.brand : T.textTertiary)
                }
                .frame(width: colWidth, height: headerHeight)
            }
        }
        .background(T.bgPage)
    }

    // MARK: - 网格线 + 时间列

    private func gridLines(colWidth: CGFloat) -> some View {
        VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { i in
                HStack(spacing: 0) {
                    VStack(spacing: 1) {
                        Text("\(i + 1)")
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(T.textSecondary)
                        Text(schedule.startTime(i + 1))
                            .font(.system(size: 8))
                            .foregroundStyle(T.textTertiary)
                    }
                    .frame(width: timeWidth, height: rowHeight)

                    Rectangle()
                        .fill(T.border.opacity(0.6))
                        .frame(height: 0.6)
                }
                .frame(height: rowHeight, alignment: .bottom)
            }
        }
    }

    // MARK: - 课块

    private func blockView(_ block: Block, colWidth: CGFloat) -> some View {
        let laneWidth = colWidth / CGFloat(block.laneCount)
        let x = timeWidth + CGFloat(block.day - 1) * colWidth + CGFloat(block.lane) * laneWidth
        let y = CGFloat(block.start - 1) * rowHeight

        return Button {
            onTap(block.course, block.session)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(block.course.name)
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(T.courseColor(block.course.colorIndex))
                    .lineLimit(block.span > 1 ? 4 : 2)
                    .multilineTextAlignment(.leading)
                if !block.session.location.isEmpty {
                    Text(block.session.location)
                        .font(.system(size: 8.5))
                        .foregroundStyle(T.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 4)
            .frame(width: max(0, laneWidth - 3), height: rowHeight * CGFloat(block.span) - 3, alignment: .topLeading)
            .background(T.courseBg(block.course.colorIndex))
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(T.courseColor(block.course.colorIndex).opacity(0.35), lineWidth: 0.8)
            )
        }
        .buttonStyle(.plain)
        .offset(x: x + 1.5, y: y + 1.5)
    }

    // MARK: - 分栏

    private struct Block: Identifiable {
        let id = UUID()
        let course: Course
        let session: Session
        let day: Int
        let start: Int
        let span: Int
        let lane: Int
        let laneCount: Int
    }

    /// 同一天里时间重叠的课并排分栏（真的撞课才分栏，不同名不同时段互不影响）
    private var blocks: [Block] {
        var out: [Block] = []
        for day in 1...7 {
            var items: [(Course, Session)] = []
            for c in timetable.courses {
                for s in c.sessions where s.dayOfWeek == day && s.isActive(week) {
                    guard s.startSection <= rows else { continue }
                    items.append((c, s))
                }
            }
            guard !items.isEmpty else { continue }

            let sorted = items.sorted { a, b in
                a.1.startSection == b.1.startSection
                    ? a.0.name < b.0.name
                    : a.1.startSection < b.1.startSection
            }

            var laneEnds: [Int] = []
            var lanes: [Int] = []
            for (_, s) in sorted {
                let end = max(s.startSection, s.endSection)
                if let free = laneEnds.firstIndex(where: { $0 < s.startSection }) {
                    laneEnds[free] = end
                    lanes.append(free)
                } else {
                    laneEnds.append(end)
                    lanes.append(laneEnds.count - 1)
                }
            }
            let laneCount = max(1, laneEnds.count)

            for (idx, pair) in sorted.enumerated() {
                let (c, s) = pair
                let start = max(1, min(rows, s.startSection))
                let end = max(start, min(rows, max(s.startSection, s.endSection)))
                out.append(Block(course: c, session: s, day: day, start: start,
                                 span: end - start + 1, lane: lanes[idx], laneCount: laneCount))
            }
        }
        return out
    }
}
