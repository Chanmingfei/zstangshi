import SwiftUI
import UIKit

// MARK: - Liquid Glass 适配层
//
// HIG（2025-09 / 2026-09）：Liquid Glass 属于**功能层**——标签栏、工具栏、侧边栏、
// 悬浮控件。系统标准组件在 iOS 26 上会自动获得该材质，正确做法是**删掉自定义背景**
// 让它透出来，而不是再手画一层玻璃。只有自定义浮动控件才需要 glassEffect。
//
// 本文件只负责「自定义浮动控件」那一层，以及 iOS 17–25 的材质回退。
// 内容层（卡片、列表行、页面背景）一律不用玻璃，见 Theme.zstCard。

enum GlassKit {
    /// 运行时是否真的能呈现 Liquid Glass
    static var isLiquidGlass: Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }
}

// MARK: 玻璃表面

/// 给一个自定义浮动控件加玻璃底。
/// - iOS 26+：`glassEffect(_:in:)` 真液态玻璃，可选 tint / interactive / clear 变体
/// - iOS 17–25：`ultraThinMaterial` + 一道亮顶边（模拟光线打在材质上）
///
/// `in:` 必须显式给形状：系统默认形状是 capsule，卡片用它会变成药丸。
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
            content.background(
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    // 亮顶边：材质受光，浅色深色各留一线高光
                    shape.stroke(Color.white.opacity(0.34), lineWidth: 0.8)
                }
            )
        }
    }

    @available(iOS 26.0, *)
    private func makeGlass() -> Glass {
        // 显式写全类型，避免和本结构体的 `clear: Bool` 参数名产生推断歧义
        var g: Glass = clear ? Glass.clear : Glass.regular
        if let tint = tint { g = g.tint(tint) }
        // interactive 只给真正响应输入的控件，静态标签不要开
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

    func glassCard(radius: CGFloat = 16, tint: Color? = nil,
                   interactive: Bool = false) -> some View {
        glassSurface(in: RoundedRectangle(cornerRadius: radius, style: .continuous),
                     tint: tint, interactive: interactive)
    }
}

// MARK: 玻璃分组容器

/// 多个相邻玻璃控件必须包在同一个 `GlassEffectContainer` 里，否则各自取样背景、
/// 边缘会互相折射出重影。低版本直接原样返回内容。
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

// MARK: 玻璃形变（morphing）

extension View {
    /// 同一 namespace 里同 id 的两个玻璃控件切换时会互相「融」过去。
    /// 低版本退化为普通交叉淡化（由外层 animation 决定）。
    @ViewBuilder
    func glassMorphID(_ id: String, in namespace: Namespace.ID) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
    }
}

// MARK: 玻璃按钮

/// 自适应按钮样式：
/// - iOS 26+ 用系统 `.glass` / `.glassProminent`（系统负责材质、按压高光、vibrancy）
/// - 低版本回退到品牌实底 / 浅色描边，形态与点击目标（44pt）保持一致
struct AdaptiveGlassButton: ButtonStyle {
    var prominent: Bool = false
    var tint: Color = T.brand

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        let label = configuration.label
            .font(F.headline)
            .lineLimit(1)
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .contentShape(Capsule(style: .continuous))

        if #available(iOS 26.0, *) {
            // 手绘玻璃而不是在 ButtonStyle 里再套 `.buttonStyle(.glass)`：
            // 嵌套按钮样式会互相覆盖，且 .glass / .glassProminent 是两种不同类型，
            // 三元表达式推不出共同类型。glassEffect 由 GlassSurface 统一处理回退。
            label
                .foregroundStyle(prominent ? Color.white : tint)
                .glassSurface(in: Capsule(style: .continuous),
                              tint: prominent ? tint.opacity(0.85) : nil,
                              interactive: true)
                .animation(Motion.press, value: configuration.isPressed)
        } else {
            label
                .background(
                    Capsule(style: .continuous)
                        .fill(prominent ? tint : T.bgSurface)
                )
                .overlay(
                    Capsule(style: .continuous)
                        .stroke(prominent ? Color.clear : T.border, lineWidth: 0.8)
                )
                .foregroundStyle(prominent ? Color.white : tint)
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
                .animation(Motion.press, value: configuration.isPressed)
        }
    }
}

// MARK: 透明材质上的可读性

/// 富媒体背景之上用 clear 玻璃时，背景亮要压一层 35% 黑，保证控件可读。
struct ClearGlassDimming: ViewModifier {
    let dim: Bool
    func body(content: Content) -> some View {
        content.background(
            Group {
                if dim { Color.black.opacity(0.35) }
            }
        )
    }
}

// MARK: 触感反馈

enum Haptics {
    static func selection() { UISelectionFeedbackGenerator().selectionChanged() }
    static func light() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func medium() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}
