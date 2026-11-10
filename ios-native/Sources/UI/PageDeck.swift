import SwiftUI

/// 横向「页面带」：四个主页面按底栏顺序横排，整体平移 `-index × 屏宽`。
///
/// 这正是 Android `PageDeck` 的 iOS 写法 —— 关键在于**四页同时在屏外排着**：
/// 从首页点「我的」时，课表、成绩会真的依次掠过屏幕，而不是两页对着擦。
///
/// 实现细节：`HStack` 里每页宽度都等于屏宽，整体用 `offset` 平移，外层 `clipped()`。
/// 之所以不用 `TabView(.page)`：那个会顺手接管左右滑动手势，和课表页「横滑翻周次」打架
/// （Android 当初专门为此放弃了滑动切页手势，这里保持一致）。
struct PageDeck<Content: View>: View {

    @Binding var index: Int
    /// 页面数量（固定 4，留参数是为了以后加页不用改结构）
    let count: Int
    @ViewBuilder let page: (Int) -> Content

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            HStack(spacing: 0) {
                ForEach(0..<count, id: \.self) { i in
                    page(i)
                        .frame(width: w, height: geo.size.height)
                }
            }
            .frame(width: w * CGFloat(count), alignment: .leading)
            .offset(x: -CGFloat(index) * w)
        }
        .clipped()
    }

    // MARK: - 时长与缓动（与 Android v3.0.22 完全同一套数字）

    /// 基准时长：不管从哪一页点到哪一页都是这个时间，手感才统一
    static let baseMS = 290
    static let stepMS = 45
    static let maxMS = 400
    static let minMS = 160

    /// 跨多格只按「每多一格 +45ms」略微加时并封顶 400ms。
    /// 首页→课表 290ms、→成绩 335ms、→我的 380ms，差不到 0.1s，观感是同一套节奏；
    /// 又不会因为完全固定时长让中间两页一糊而过。
    static func duration(for distance: CGFloat) -> Double {
        let raw = Double(baseMS) + Double(stepMS) * Double(max(0, distance - 1))
        return Double(min(maxMS, max(minMS, Int(raw)))) / 1000.0
    }

    /// Material 3 的 emphasized decelerate，对应 Android 的 `DecelerateInterpolator(1.1)`
    static func curve(_ duration: Double) -> Animation {
        .timingCurve(0.05, 0.7, 0.1, 1.0, duration: duration)
    }
}
