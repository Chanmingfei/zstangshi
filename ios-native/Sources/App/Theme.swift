import SwiftUI
import UIKit

// MARK: - 设计令牌
//
// 与 Android `res/values/colors.xml` + `values-night/colors.xml` 一一对应，但按 HIG
// 做两处原生化：
//   1. 颜色走动态色 `UIColor { traits in … }`，切深浅色自动重解析，不需要 `if dark`；
//   2. 字号走 **Dynamic Type 语义字号**，跟随系统无障碍字号，不再写死 sp。
//
// 每个自定义色都给浅色 + 深色变体（HIG 要求四上下文，至少两套）。

enum T {

    // MARK: 语义背景（内容层用实色，不叠玻璃）

    static let bgPage = Color(uiColor: .systemGroupedBackground)
    static let bgSurface = dyn(0xFFFFFF, 0x1C2330)
    static let bgSunken = dyn(0xEDEFF4, 0x232B3A)
    static let bgElevated = dyn(0xFFFFFF, 0x242C3A)

    // MARK: 文字四档

    static let textPrimary = Color(uiColor: .label)
    static let textSecondary = Color(uiColor: .secondaryLabel)
    static let textTertiary = Color(uiColor: .tertiaryLabel)
    static let textPlaceholder = Color(uiColor: .placeholderText)

    // MARK: 描边 / 分隔

    static let border = dyn(0xE3E7EF, 0x2A3446)
    static let separator = Color(uiColor: .separator)

    // MARK: 品牌色（浅 / 深）

    static let brand = dyn(0x0062CC, 0x0A84FF)
    static let brandDeep = dyn(0x004C9E, 0x409CFF)
    static let brandSoft = dyn(0xE8F1FC, 0x14294A)
    static let brandOnSoft = dyn(0x0062CC, 0x6FA8FF)

    /// iOS 系统蓝，玻璃 tint / 强调色用
    static let accent = Color(uiColor: .tintColor)
    static let systemBlue = Color(uiColor: .systemBlue)

    // MARK: 状态语义色（同一颜色只表达一种含义）

    /// 到站 / 有车 —— 绿
    static let go = dyn(0x2E9E73, 0x30D158)
    static let goSoft = dyn(0xE7F5EF, 0x17332A)
    /// 已到站 / 提醒 —— 橙
    static let warn = dyn(0xE8734A, 0xFF9F0A)
    static let warnSoft = dyn(0xFCEDE7, 0x3A241C)
    static let danger = Color(uiColor: .systemRed)
    static let success = Color(uiColor: .systemGreen)

    // MARK: 课表色板（8 色，顺序与 Android CourseColorAssigner 一致）

    static let course: [Color] = [
        dyn(0x3B7BE8, 0x5B96F5),
        dyn(0xE8734A, 0xF08C68),
        dyn(0x3FA37A, 0x55C092),
        dyn(0xB06BDA, 0xC48CE8),
        dyn(0xD9A21B, 0xE8B743),
        dyn(0x4FA8C7, 0x6FC0DA),
        dyn(0xC9527A, 0xDB7196),
        dyn(0x6E7FA8, 0x8C9BC0)
    ]

    static let courseSoft: [Color] = [
        dyn(0xE7EFFC, 0x1B2A44),
        dyn(0xFCEDE7, 0x3A241C),
        dyn(0xE7F5EF, 0x17332A),
        dyn(0xF4EBFB, 0x2E1F3D),
        dyn(0xFBF3DE, 0x3A2F14),
        dyn(0xE7F3F8, 0x152E38),
        dyn(0xFAE9EF, 0x3A1F29),
        dyn(0xEDF0F6, 0x232A38)
    ]

    static func courseColor(_ index: Int) -> Color {
        course[abs(index) % course.count]
    }

    static func courseBg(_ index: Int) -> Color {
        courseSoft[abs(index) % courseSoft.count]
    }

    // MARK: 尺寸（HIG：点击目标 44pt，页面左右 20pt）

    static let cardRadius: CGFloat = 16
    static let pagePadding: CGFloat = 20
    /// 旧胶囊底栏高度 —— 保留常量是为了兼容仍在引用它的旧代码，
    /// v4 已改用系统 TabView，新页面不要再读它。
    static let dockHeight: CGFloat = 64
    static let minTap: CGFloat = 44
    static let rowGap: CGFloat = 12

    // MARK: 工具

    static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }

    /// 动态色也要照顾「增强对比度」：系统提高对比时把次级文字推到主色。
    static func dynamicText(secondary: Bool) -> Color {
        Color(UIColor { traits in
            if traits.accessibilityContrast == .high {
                return .label
            }
            return secondary ? .secondaryLabel : .label
        })
    }
}

extension Color {
    init(hex: UInt32) { self.init(UIColor(hex: hex)) }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

// MARK: - 卡片（内容层：实色，不用玻璃）

extension View {
    /// 统一的卡片外观：实底 + 连续圆角 + 细描边。
    /// HIG 明确规定玻璃只给悬浮控件层，内容层叠玻璃会造成层级混乱。
    func zstCard(padding: CGFloat = 0) -> some View {
        self
            .padding(padding)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .stroke(T.border, lineWidth: 0.8)
            )
    }

    /// 分组列表里的行容器：系统 grouped 背景 + 内部分隔线由调用方画
    func zstGroupedCard(padding: CGFloat = 0) -> some View {
        self
            .padding(padding)
            .background(T.bgSurface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

// MARK: - Dynamic Type 语义字号
//
// Android 侧把字体缩放关掉、写死 sp；iOS 侧刻意跟随系统字号，属于平台习惯差异。
// 常量名沿用旧代码，值换成语义字号 —— 现有页面无需改动就自动获得无障碍适配。

enum F {
    static let largeTitle = Font.largeTitle.weight(.bold)
    static let title = Font.title.weight(.bold)
    static let title2 = Font.title2.weight(.bold)
    static let title3 = Font.title3.weight(.semibold)
    static let pageTitle = Font.headline
    static let cardTitle = Font.title3.weight(.semibold)
    static let headline = Font.headline
    static let body = Font.body
    static let bodyBold = Font.body.weight(.semibold)
    static let callout = Font.callout
    static let sub = Font.subheadline
    static let caption = Font.footnote
    static let caption2 = Font.caption2
    static let sectionTitle = Font.subheadline.weight(.semibold)
    /// 数字大字（到站分钟等）：等宽数字避免刷新时左右跳
    static let numeric = Font.system(.title2, design: .rounded).weight(.bold).monospacedDigit()
}
