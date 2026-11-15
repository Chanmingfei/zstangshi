import SwiftUI
import UIKit

// MARK: - Liquid Glass 适配层
//
// HIG：Liquid Glass 属于**功能层**（导航条、工具栏、Dock、悬浮按钮）。
// 正确做法是让系统标准组件在 iOS 26 上自动获得材质，而不是再手画一遍。
// 但本 App 的主导航是**自绘胶囊 Dock**（安卓设计，必须保留），
// 所以这里必须自己实现玻璃效果，并给出 iOS 17–25 的材质回退。
//
// 职责边界（很重要，别越界）：
//   · 玻璃只给「浮在内容之上的控件层」：胶囊 Dock、回到本周、Toast、悬浮按钮；
//   · 内容层（卡片、列表、页面背景）**一律不用玻璃** —— 见 Theme.zstCard。

enum GlassKit {
    /// 运行期是否真能渲染 iOS 26 Liquid Glass。
    /// 注意：这是**运行期**判断，编译期仍需要 iOS 26 SDK 才能引用这些符号。
    static var isLiquidGlass: Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }
}

// MARK: - 玻璃表面

/// 给自定义浮动控件加玻璃底。
///
/// - iOS 26+：`glassEffect(_:in:)` 真正的液态玻璃，支持 tint / interactive / clear 变体；
/// - iOS 17–25：`ultraThinMaterial` + 一道亮顶边，模拟光线打在材质上的高光。
///
/// `in:` 必须显式给形状 —— 系统默认形状是 capsule，卡片用它会变成药丸。
struct GlassSurface<S: Shape>: ViewModifier {
    let shape: S
    var tint: Color? = nil
    var interactive: Bool = false
    var clear: Bool = false

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(makeGlass(), in: shape)
        } else {
            // 回退：材质 + 极细高光边 + 极轻阴影，视觉上尽量贴近玻璃
            content
                .background(shape.fill(.ultraThinMaterial))
                .overlay(shape.strokeBorder(Color.white.opacity(0.28), lineWidth: 0.8))
                .shadow(color: .black.opacity(0.10), radius: 16, y: 6)
        }
    }

    @available(iOS 26.0, *)
    private func makeGlass() -> Glass {
        // 显式写全类型：本结构体自身有 `clear: Bool` 参数，
        // 不写死类型会让 `Glass.clear` 与它产生推断歧义。
        var g: Glass = clear ? Glass.clear : Glass.regular
        if let tint { g = g.tint(tint) }
        // interactive 只给真正响应输入的控件；静态标签不要开（会白掉一层高光）
        if interactive { g = g.interactive() }
        return g
    }
}

extension View {
    func glassSurface<S: Shape>(in shape: S, tint: Color? = nil,
                                interactive: Bool = false, clear: Bool = false) -> some View {
        modifier(GlassSurface(shape: shape, tint: tint, interactive: interactive, clear: clear))
    }

    func glassCapsule(tint: Color? = nil, interactive: Bool = false) -> some View {
        glassSurface(in: Capsule(style: .continuous), tint: tint, interactive: interactive)
    }

    func glassCard(radius: CGFloat = T.cardRadius, tint: Color? = nil,
                   interactive: Bool = false) -> some View {
        glassSurface(in: RoundedRectangle(cornerRadius: radius, style: .continuous),
                     tint: tint, interactive: interactive)
    }
}

// MARK: - 玻璃分组容器

/// 多个相邻的玻璃控件必须包在同一个容器里，否则各自取样背景、
/// 边缘会互相折射出重影。低版本用普通栈（回退材质不参与折射，不需要分组）。
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 16
    @ViewBuilder let content: Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

// MARK: - 玻璃形变

extension View {
    /// 同一 namespace 里同 id 的两个玻璃控件切换时会互相「融」过去。
    @ViewBuilder
    func glassMorphID(_ id: String, in namespace: Namespace.ID) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
    }
}

// MARK: - 自适应按钮样式

/// - iOS 26+：手绘玻璃 + 按压高光；
/// - 低版本：品牌实底 / 浅描边，形态与点击目标保持一致。
///
/// 注意不要在 ButtonStyle 里再套 `.buttonStyle(.glass)`：
/// 嵌套按钮样式会互相覆盖，且 `.glass` / `.glassProminent` 是两种不同类型，
/// 三元表达式推不出共同类型。
struct AdaptiveGlassButtonStyle: ButtonStyle {
    var prominent: Bool = false
    var tint: Color = T.brand

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        let label = configuration.label
            .font(F.bodyBold)
            .lineLimit(1)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .frame(minHeight: 44)
            .contentShape(Capsule(style: .continuous))

        if #available(iOS 26.0, *) {
            label
                .foregroundStyle(prominent ? Color.white : tint)
                .glassSurface(in: Capsule(style: .continuous),
                              tint: prominent ? tint.opacity(0.85) : nil,
                              interactive: true)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
                .animation(M.press, value: configuration.isPressed)
        } else {
            label
                .background(
                    Capsule(style: .continuous)
                        .fill(prominent ? tint : T.bgSurface)
                )
                .overlay(
                    Capsule(style: .continuous)
                        .strokeBorder(prominent ? Color.clear : T.borderSubtle, lineWidth: 0.7)
                )
                .foregroundStyle(prominent ? Color.white : tint)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
                .animation(M.press, value: configuration.isPressed)
        }
    }
}

extension ButtonStyle where Self == AdaptiveGlassButtonStyle {
    static var adaptiveGlass: AdaptiveGlassButtonStyle { AdaptiveGlassButtonStyle() }
    static var adaptiveGlassProminent: AdaptiveGlassButtonStyle {
        AdaptiveGlassButtonStyle(prominent: true)
    }
}

// MARK: - 按压反馈（卡片 / 列表行 / 宫格）

/// 给任意可点区域加「按下即缩、松手回弹」。安卓用 RippleDrawable 水波纹，
/// iOS 没有同款反馈，用 scale + 轻透明度替代是最贴近平台习惯的做法。
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.97

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(M.press, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableStyle {
    static var pressable: PressableStyle { PressableStyle() }
    static func pressable(_ scale: CGFloat) -> PressableStyle { PressableStyle(scale: scale) }
}