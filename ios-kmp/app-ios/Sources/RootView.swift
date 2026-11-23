import SwiftUI
import Shared

/// 纯云构建的第一块砖：证明 Kotlin 共享层真的能被 Swift 调到。
///
/// 这里只调三个最简单、签名最不容易出错的函数（Int / String 出入），
/// 目的是把「framework 链接 → Kotlin/Native 运行时启动 → 返回值正确」这条路走通。
/// 一旦通了，后面才是界面。
struct RootView: View {
    @State private var results: [String] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header

                    Button {
                        runChecks()
                    } label: {
                        Text("运行共享层自检")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(Color(red: 0.46, green: 0.08, blue: 0.10))
                            .foregroundColor(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }

                    ForEach(results, id: \.self) { line in
                        Text(line)
                            .font(.system(size: 13, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                }
                .padding(18)
            }
            .navigationTitle("共享层联通测试")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            if results.isEmpty { runChecks() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Kotlin 共享层")
                .font(.headline)
            Text("下面的值全部由 Kotlin/Native 计算，Swift 只负责显示。"
                 + "如果全是 ✓，说明 framework 链接、ObjC 桥接、运行时都正常。")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    private func runChecks() {
        var out: [String] = []
        // 2026-09-07 是学期第 1 周周一；取 9 月 21 日应当落在第 3 周
        let today = Dates.shared.daysFromCivil(y0: 2026, m: 9, d: 21)

        let monday = WeekMath.shared.normalizeToMonday(date: "2026-09-09")
        out.append(mark(monday == "2026-09-07") + "归一化到周一：2026-09-09 → \(monday)（期望 2026-09-07）")

        let week = WeekMath.shared.currentWeek(startDate: "2026-09-07", totalWeeks: 20, today: today)
        out.append(mark(week == 3) + "当前周次：\(week)（期望 3）")

        let label = WeekMath.shared.weekRangeLabel(startDate: "2026-09-07", week: 1, today: today)
        out.append(mark(label == "9/7 - 9/13") + "第 1 周区间：\(label)（期望 9/7 - 9/13）")

        let ymd = Dates.shared.formatYmd(epochDay: today)
        out.append(mark(ymd == "2026-09-21") + "epoch day 格式化：\(ymd)（期望 2026-09-21）")

        results = out
    }

    private func mark(_ ok: Bool) -> String {
        ok ? "✓ " : "✗ "
    }
}
