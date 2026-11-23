import SwiftUI
import Shared

/// 课表页：周次切换 + 7 列 × N 节网格。
///
/// 格子由 Kotlin `TimetableGrid.cells(timetable, week)` 摊平好再交过来 ——
/// 周次展开、跨节次、同格多课这些规则只写一份，和安卓同源。
@MainActor
final class TimetableModel: ObservableObject {
    @Published var timetable: Timetable?
    @Published var week: Int = 1
    @Published var maxWeek: Int = 20
    @Published var cells: [GridCell] = []
    @Published var maxSection: Int = 12
    @Published var status = "加载中…"

    func load() async {
        status = "正在同步课表…"
        guard let t = await JwAuth.shared.fetchTimetable() else {
            status = JwAuth.shared.message.isEmpty ? "课表加载失败" : JwAuth.shared.message
            return
        }
        timetable = t
        maxWeek = Int(TimetableGrid.shared.maxWeek(t: t, def: Int32(max(20, t.semester.totalWeeks))))
        maxSection = Int(TimetableGrid.shared.maxSection(t: t))
        week = 1
        refresh()
        status = "\(t.courses.count) 门课 · \(maxWeek) 周"
    }

    func refresh() {
        guard let t = timetable else { return }
        cells = TimetableGrid.shared.cells(t: t, week: Int32(week))
    }

    func cellsOf(day: Int) -> [GridCell] {
        cells.filter { $0.day == Int32(day) }
    }
}

struct TimetableView: View {
    @StateObject private var model = TimetableModel()

    private let palette: [Color] = [
        Color(red: 0.85, green: 0.92, blue: 0.99), Color(red: 0.99, green: 0.90, blue: 0.88),
        Color(red: 0.88, green: 0.96, blue: 0.89), Color(red: 0.99, green: 0.95, blue: 0.85),
        Color(red: 0.92, green: 0.90, blue: 0.99), Color(red: 0.88, green: 0.97, blue: 0.97),
        Color(red: 0.99, green: 0.92, blue: 0.96), Color(red: 0.93, green: 0.94, blue: 0.90),
    ]
    private let ink: [Color] = [
        Color(red: 0.10, green: 0.35, blue: 0.62), Color(red: 0.62, green: 0.25, blue: 0.20),
        Color(red: 0.16, green: 0.45, blue: 0.22), Color(red: 0.55, green: 0.42, blue: 0.10),
        Color(red: 0.35, green: 0.26, blue: 0.60), Color(red: 0.10, green: 0.45, blue: 0.45),
        Color(red: 0.60, green: 0.22, blue: 0.42), Color(red: 0.35, green: 0.38, blue: 0.20),
    ]

    private let sectionHeight: CGFloat = 52
    private let dayNames = ["一", "二", "三", "四", "五", "六", "日"]

    var body: some View {
        VStack(spacing: 0) {
            weekBar
            Divider()
            if model.timetable == nil {
                Spacer()
                Text(model.status).font(.footnote).foregroundColor(.secondary)
                Spacer()
            } else {
                grid
            }
        }
        .navigationTitle("我的课表")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    private var weekBar: some View {
        HStack(spacing: 8) {
            Button {
                if model.week > 1 { model.week -= 1; model.refresh() }
            } label: { Image(systemName: "chevron.left") }
                .disabled(model.week <= 1)

            Menu {
                ForEach(1...max(model.maxWeek, 1), id: \.self) { w in
                    Button("第 \(w) 周") { model.week = w; model.refresh() }
                }
            } label: {
                Text("第 \(model.week) 周").font(.headline)
            }

            Button {
                if model.week < model.maxWeek { model.week += 1; model.refresh() }
            } label: { Image(systemName: "chevron.right") }
                .disabled(model.week >= model.maxWeek)

            Spacer()
            Text(model.status).font(.caption2).foregroundColor(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var grid: some View {
        ScrollView(.vertical) {
            HStack(alignment: .top, spacing: 0) {
                // 左侧节次列
                VStack(spacing: 0) {
                    Text("").frame(height: 24)
                    ForEach(1...max(model.maxSection, 1), id: \.self) { s in
                        Text("\(s)")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                            .frame(height: sectionHeight)
                    }
                }
                .frame(width: 20)

                ForEach(0..<7, id: \.self) { i in
                    dayColumn(day: i + 1)
                }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 20)
        }
    }

    private func dayColumn(day: Int) -> some View {
        let h = CGFloat(max(model.maxSection, 1)) * sectionHeight
        return VStack(spacing: 0) {
            Text(dayNames[day - 1])
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(day == 6 || day == 7 ? .secondary : .primary)
                .frame(height: 24)

            ZStack(alignment: .top) {
                VStack(spacing: 0) {
                    ForEach(1...max(model.maxSection, 1), id: \.self) { _ in
                        Rectangle()
                            .fill(Color(UIColor.systemBackground))
                            .frame(height: sectionHeight)
                            .overlay(Rectangle().stroke(Color(UIColor.separator).opacity(0.25), lineWidth: 0.5))
                    }
                }

                ForEach(Array(model.cellsOf(day: day).enumerated()), id: \.offset) { pair in
                    cellBlock(pair.element)
                }
            }
            .frame(height: h)
        }
        .frame(maxWidth: .infinity)
    }

    /// 一个课程块。抽成函数是因为 ViewBuilder 里不能连写 let（会被判成 `()`）。
    private func cellBlock(_ cell: GridCell) -> some View {
        let span = CGFloat(Int(cell.endSection) - Int(cell.startSection) + 1)
        let idx = abs(Int(cell.colorIndex)) % palette.count
        return VStack(alignment: .leading, spacing: 1) {
            Text(cell.name)
                .font(.system(size: 10, weight: .medium))
                .foregroundColor(ink[idx])
                .lineLimit(4)
            if !cell.room.isEmpty {
                Text(cell.room)
                    .font(.system(size: 9))
                    .foregroundColor(ink[idx].opacity(0.85))
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: span * sectionHeight - 3, alignment: .top)
        .background(palette[idx])
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        .padding(.horizontal, 1.5)
        .offset(y: CGFloat(Int(cell.startSection) - 1) * sectionHeight + 1.5)
    }
}
