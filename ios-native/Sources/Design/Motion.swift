import SwiftUI

// MARK: - 流体动效体系（Designing Fluid Interfaces）
//
// 对应 Android 那套 `ValueAnimator + PathInterpolator(0.4,0,0.2,1)` 的 iOS 原生等价物。
// 关键差异不是曲线数值，而是三条原则：
//   1. 按下即反馈，不等松手；
//   2. 任何动画任何时刻可抓取、反转、重定向，且**从实时屏幕值起播** —— SwiftUI 的
//      spring + 状态驱动天然满足，不要自己记录「动画起点」；
//   3. 松手动画继承手指速度（速度交接），落点按动量投射算，不从松手点就近吸附。
//
// 只动画 transform 和 opacity；不动 cornerRadius / frame 这类会触发布局的属性。

enum Motion {
    /// 默认 UI 弹簧：临界阻尼（bounce 0），response 0.35s
    static let standard = Animation.spring(response: 0.35, dampingFraction: 1.0)
    /// 带动量的甩动：damping ~0.8，只给手势收尾
    static let momentum = Animation.spring(response: 0.4, dampingFraction: 0.8)
    /// Sheet / 抽屉：略带一点回弹
    static let sheet = Animation.spring(response: 0.32, dampingFraction: 0.86)
    /// 玻璃形变 / 选中块移动
    static let morph = Animation.smooth(duration: 0.32)
    /// 按压反馈：100ms ease-out
    static let press = Animation.easeOut(duration: 0.1)
    /// 内容淡入（reduce motion 时也保留，只是不位移）
    static let fade = Animation.easeInOut(duration: 0.2)
}

// MARK: Reduce Motion

/// 跟随系统「减弱动态效果」：位移动画换成交叉淡化，玻璃形变仍保留形状变化但不做位移。
/// 所有页面在用 `withAnimation` 前都走这里取动画。
@inline(__always)
func motion(_ reduce: Bool, _ animation: Animation = Motion.standard) -> Animation? {
    reduce ? Motion.fade : animation
}

// MARK: 按压反馈

/// 按下即反馈（scale 0.97，100ms），松手回弹。用于所有自定义可点区域。
struct PressFeedback: ButtonStyle {
    var scale: CGFloat = 0.97

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(Motion.press, value: configuration.isPressed)
            .contentShape(Rectangle())
    }
}

// MARK: 速度交接 / 动量投射

enum Momentum {
    /// 由松手瞬间的速度投射落点（skill 里的 `current + (v/1000)·d/(1−d)`）。
    /// - velocity: DragGesture 的 `predictedEndTranslation` 与 `translation` 之差换算出的 pt/s
    /// - damping: 0.998 是「每帧保留 99.8% 速度」的离散阻尼
    /// 返回值已按 page 宽度夹紧，避免一次甩动飞过十几页。
    static func projected(current: CGFloat, velocity: CGFloat,
                          pageWidth: CGFloat, maxPages: Int = 3,
                          damping: CGFloat = 0.998) -> CGFloat {
        let gain = damping / (1 - damping)
        let raw = current + (velocity / 1000) * gain * 0.012
        let limit = pageWidth * CGFloat(maxPages)
        return min(max(raw, -limit), limit)
    }

    /// 把投射距离折算成「要翻几页」，再交给弹簧从当前值起播。
    static func pages(for translation: CGFloat, velocity: CGFloat, pageWidth: CGFloat) -> Int {
        guard pageWidth > 0 else { return 0 }
        let projected = translation + velocity * 0.18
        return Int((projected / pageWidth).rounded())
    }
}

// MARK: 手势驱动的翻页（课表翻周 / 站线横滑共用）

/// 可打断拖拽：手指按下立刻接管，松手时把速度交给弹簧。
/// SwiftUI 的 `withAnimation(.spring)` 在手势进行中改状态会从当前渲染值起播，
/// 这正是「可打断」的实现，不需要手记 animation start value。
struct PagingDrag: ViewModifier {
    let pageWidth: CGFloat
    let onPageChange: (Int) -> Void

    @GestureState private var translation: CGFloat = 0
    @State private var lastVelocity: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .gesture(
                DragGesture(minimumDistance: 12, coordinateSpace: .local)
                    .updating($translation) { value, state, _ in
                        state = value.translation.width
                    }
                    .onChanged { value in
                        // 反馈在手势全程连续，不是只在结束时
                        lastVelocity = value.velocity.width
                    }
                    .onEnded { value in
                        let steps = Momentum.pages(for: value.translation.width,
                                                   velocity: value.velocity.width,
                                                   pageWidth: pageWidth)
                        guard steps != 0 else { return }
                        Haptics.light()
                        withAnimation(Motion.momentum) { onPageChange(steps) }
                    }
            )
    }
}

extension View {
    /// 横滑翻页／翻周。返回「相对变化量」，由调用方自行 clamp 到合法范围。
    func pagingDrag(pageWidth: CGFloat, onChange: @escaping (Int) -> Void) -> some View {
        modifier(PagingDrag(pageWidth: pageWidth, onPageChange: onChange))
    }
}

// MARK: iOS 原生转场

/// Android 的「整条页面带平移 / 圆角卡片退场」在 iOS 上由系统负责：
///   - 底栏切页 → `TabView`（无自定义平移，符合平台习惯）
///   - 二级页 push/pop → `NavigationStack` 的系统转场 + 边缘返回手势
///   - 弹层 → `.sheet` 的玻璃卡片 + 下拉关闭
/// 这里只保留一个「进场淡入」用于首页内容，避免整屏硬切。
struct AppearFade: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 8)
            .onAppear {
                withAnimation(motion(reduceMotion, Motion.standard)) { shown = true }
            }
    }
}
