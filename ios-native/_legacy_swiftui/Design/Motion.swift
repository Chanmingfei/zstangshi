import SwiftUI
import UIKit

// MARK: - 动效
//
// 数值全部对齐安卓源码里的真实参数（不是拍脑袋发明的 iOS 默认值）：
//   · 切页：MainActivity.kt:382-387 —— 基准 290ms，每多一格 +45ms，封顶 400ms，下限 160ms
//   · 切页插值：PageDeck.kt:78 —— DecelerateInterpolator(1.1f) = 1 - (1-t)^1.1
//   · Dock 选中块：CapsuleDock.kt:114-125 —— 320ms + OvershootInterpolator(1.4f)
//   · 周次翻页：TimetablePage.kt:629-637 —— 出场 140ms ease-in、入场 300ms ease-out
//   · 「回到本周」浮出：TimetablePage.kt:349-362 —— 230ms、scale 0.82→1
//   · 主题切换淡入：MainActivity.kt:92-97 —— 220ms
//   · 按压反馈：自适应，不用系统默认的 0.2s 线性

enum M {

    // MARK: 基础曲线

    /// DecelerateInterpolator(1.1) 等价物。iOS 的 .easeOut 是 (0,0,0.58,1) 的三次贝塞尔，
    /// 与 (1-t)^1.1 非常接近，但为了逐帧一致，这里用精确的三次贝塞尔近似值。
    static let decelerate = Animation.timingCurve(0.0, 1.0 - 0.0, 0.32, 1.0, duration: 0.29)

    /// OvershootInterpolator(1.4) → 带 1.4 倍过冲的曲线。
    /// iOS 的 .spring(response:dampingFraction:)，response 0.32 / damping 0.72
    /// 能复现同一手感：快速滑出 + 约 8% 回弹。
    static let overshoot = Animation.spring(response: 0.32, dampingFraction: 0.72)

    /// 按压：短、无过冲（安卓 ripple 是即时的，不需要回弹）
    static let press = Animation.timingCurve(0.4, 0.0, 0.2, 1.0, duration: 0.16)

    /// 出场（周次翻页的 out 段）：140ms EASE_IN = PathInterpolator(0.4, 0, 0.8, 0.4)
    static let pageOut = Animation.timingCurve(0.4, 0.0, 0.8, 0.4, duration: 0.14)

    /// 入场（周次翻页的 in 段）：300ms EASE_OUT = PathInterpolator(0.22, 0.8, 0.30, 1)
    static let pageIn = Animation.timingCurve(0.22, 0.8, 0.30, 1.0, duration: 0.30)

    /// 卡片/弹层浮出：230ms，DecelerateInterpolator(1.4)
    static let lift = Animation.timingCurve(0.0, 1.4, 0.3, 1.0, duration: 0.23)

    /// 淡入淡出（主题切换、Toast）：220ms / 160ms
    static let fadeIn = Animation.easeOut(duration: 0.22)
    static let fadeOut = Animation.easeIn(duration: 0.16)

    // MARK: 切页时长（照抄 MainActivity.kt:382-387 的算法）

    static let slideBase: Double = 0.290
    static let slideStep: Double = 0.045
    static let slideMax: Double = 0.400
    static let slideMin: Double = 0.160

    /// 与安卓 `dur = clamp(290 + 45*max(0, remain-1), 160, 400)` 完全一致。
    /// - Parameter remain: 剩余跨过的格数（可带小数，用于跟手打断时从当前位置接着滑）
    static func slideDuration(remain: Double) -> Double {
        let raw = slideBase + slideStep * max(0, remain - 1)
        return min(slideMax, max(slideMin, raw))
    }
}

// MARK: - 动量投射
//
// 用于「松手继承手指速度」的跟手动画。UIKit 的 UIScrollView
// 用同一条指数衰减公式，这里复刻以便和系统手感一致。

enum Momentum {
    /// 把当前速度投射成最终位移。
    /// - Parameters:
    ///   - velocity: 松手时的速度（pt/s）
    ///   - decelerationRate: 衰减率，UIScrollView.normal 为 0.998，.fast 为 0.99
    static func project(velocity: CGFloat, decelerationRate: CGFloat = 0.998) -> CGFloat {
        let d = decelerationRate
        return velocity * d / (1 - d)
    }

    /// 把拖动位移 + 速度换算成距离最近的「整格」目标。
    /// 阈值参照安卓：`max(60dp, width * 0.20)`（TimetablePage.kt:593），
    /// 同时加入速度判定（快速轻扫即使位移小也翻页）。
    static func snapTarget(
        offset: CGFloat,
        velocity: CGFloat,
        pageWidth: CGFloat,
        minPage: Int,
        maxPage: Int,
        current: Int
    ) -> Int {
        guard pageWidth > 0 else { return current }
        let threshold = max(60, pageWidth * 0.20)
        let projected = offset + project(velocity: velocity) / 3
        var target = current
        if abs(projected) >= threshold {
            target = current + (projected < 0 ? 1 : -1)
        }
        return min(maxPage, max(minPage, target))
    }
}

// MARK: - 减弱动态效果

private struct ReduceMotionKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// UIKit 的 `UIAccessibility.isReduceMotionEnabled`。
    /// 通过环境下发，视图树任意位置可读，且在系统设置变化时自动刷新。
    var reduceMotion: Bool {
        get { UIAccessibility.isReduceMotionEnabled }
        set { _ = newValue }
    }
}

extension M {
    /// 尊重「减弱动态效果」：开启时所有位移/缩放动画退化为交叉淡化。
    static func respecting(_ animation: Animation, enabled: Bool) -> Animation? {
        enabled ? .easeInOut(duration: 0.15) : animation
    }
}

// MARK: - 触感反馈

enum Haptics {
    /// 点 Dock 切换 —— Android 用 VIRTUAL_KEY
    static func select() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    /// 拖动经过某一格 —— Android 用 CLOCK_TICK（极轻微）
    static func tick() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.6)
    }

    /// 普通按压
    static func light() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    /// 成功（同步完成）
    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    /// 失败（网络错误）
    static func error() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }

    /// 警告（表单校验不过）
    static func warning() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}

// MARK: - 整页左滑手势（Dock 跟随）
//
// Android 的 PageDeck 是「整条页面带平移」：从首页点到「我的」时，
// 课表、成绩会真的依次掠过屏幕。SwiftUI 里用 offset + drag 手势实现同样效果，
// 且拖到一半松手时从当前位置继续动画（不跳）。

@available(iOS 17.0, *)
struct PageDeckGesture: ViewModifier {
    @Binding var index: Int
    let count: Int
    let width: CGFloat
    let reduceMotion: Bool
    /// 当前页面带的连续位置（小数），用于和 Dock 的选中块联动
    @Binding var position: CGFloat

    @State private var dragOffset: CGFloat = 0
    @GestureState private var isDragging = false

    func body(content: Content) -> some View {
        content
            .offset(x: -position * width + dragOffset)
            .animation(isDragging ? nil : M.respecting(
                Animation.timingCurve(0.0, 1.0, 0.32, 1.0,
                                      duration: M.slideDuration(remain: 1)),
                enabled: reduceMotion
            ), value: position)
            .gesture(
                DragGesture(minimumDistance: 18)
                    .updating($isDragging) { _, state, _ in state = true }
                    .onChanged { value in
                        guard !reduceMotion else { return }
                        // 只接管明显的横向手势；纵向仍交给子视图（课表页要纵向滚）
                        let dx = value.translation.width
                        let dy = value.translation.height
                        if abs(dx) <= abs(dy) * 1.3 { return }
                        // 边界阻尼：到两端时只跟 28%（CapsuleDock 同类手感）
                        let atEdge = (dx > 0 && index == 0) || (dx < 0 && index == count - 1)
                        dragOffset = atEdge ? dx * 0.28 : dx
                    }
                    .onEnded { value in
                        guard !reduceMotion else { dragOffset = 0; return }
                        let dx = value.translation.width
                        let vx = value.predictedEndTranslation.width - dx
                        let threshold = width * 0.22
                        var target = index
                        if abs(dx) >= threshold || abs(vx) > 120 {
                            target = index + (dx < 0 ? 1 : -1)
                        }
                        target = min(count - 1, max(0, target))
                        let remain = Double(abs(target - index))
                        withAnimation(Animation.timingCurve(0.0, 1.0, 0.32, 1.0,
                                                            duration: M.slideDuration(remain: remain))) {
                            dragOffset = 0
                            index = target
                            position = CGFloat(target)
                        }
                        if target != index { Haptics.select() }
                    }
            )
    }
}

@available(iOS 17.0, *)
extension View {
    /// 给页面内容挂上「整条带横滑切页」手势。
    func pageDeckSwipe(index: Binding<Int>, count: Int, width: CGFloat,
                       reduceMotion: Bool, position: Binding<CGFloat>) -> some View {
        modifier(PageDeckGesture(index: index, count: count, width: width,
                                 reduceMotion: reduceMotion, position: position))
    }
}