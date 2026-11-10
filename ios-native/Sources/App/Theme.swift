import SwiftUI

/// 设计令牌：与 Android `res/values/colors.xml` + `values-night/colors.xml` 一一对应。
///
/// 为什么不做两套颜色常量：Android 用 `@color` 语义色 + values-night 覆盖，
/// iOS 最接近的等价物是 `UIColor { traits in … }` 动态色 —— 系统切换深色时
/// 自动重解析，SwiftUI 的 `Color` 直接跟着变，不需要任何 `if dark` 分支。
enum T {

    // MARK: - 语义色

    static let bgPage = dyn(0xF3F5F9, 0x0F1520)
    static let bgSurface = dyn(0xFFFFFF, 0x1A2230)
    static let bgSunken = dyn(0xEDEFF4, 0x232B3A)

    static let textPrimary = dyn(0x141821, 0xF2F5FA)
    static let textSecondary = dyn(0x5A6273, 0xB6BECD)
    static let textTertiary = dyn(0x8B93A5, 0x8B93A5)

    static let border = dyn(0xE3E7EF, 0x2A3446)

    static let brand = dyn(0x0062CC, 0x2E86E8)
    static let brandDeep = dyn(0x004C9E, 0x1E6BBE)
    static let brandSoft = dyn(0xE8F1FC, 0x14294A)
    static let brandOnSoft = dyn(0x0062CC, 0x6FA8FF)

    // MARK: - 课表色板（8 色，顺序与 CourseColorAssigner 一致）

    static let course: [Color] = [
        dyn(0x3B7BE8, 0x3B7BE8),
        dyn(0xE8734A, 0xE8734A),
        dyn(0x3FA37A, 0x3FA37A),
        dyn(0xB06BDA, 0xB06BDA),
        dyn(0xD9A21B, 0xD9A21B),
        dyn(0x4FA8C7, 0x4FA8C7),
        dyn(0xC9527A, 0xC9527A),
        dyn(0x6E7FA8, 0x6E7FA8)
    ]

    /// 课块浅底：深色下压暗，否则整屏发白（对应 values-night 的 course_N_soft）
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

    // MARK: - 尺寸

    /// 卡片圆角：Android `card_bg` 是 16dp
    static let cardRadius: CGFloat = 16
    /// 页面左右留白（Android 各页 dp(16)）
    static let pagePadding: CGFloat = 16
    /// 底栏胶囊高度
    static let dockHeight: CGFloat = 64

    // MARK: - 工具

    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}

extension Color {
    /// 十六进制取色（写死几个品牌色时用；语义色仍然走 T.*）
    init(hex: UInt32) {
        self.init(UIColor(hex: hex))
    }
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

extension View {
    /// 统一的卡片外观（对应 drawable/card_bg：白底 + 1px 描边 + 16 圆角）
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
}

/// 字体：Android 侧标题用 DEFAULT_BOLD、正文 12～15sp。
/// iOS 侧统一走 Dynamic Type 的语义字号，无障碍字号放大会自动适配
/// （Android 那边是把字体缩放关掉的，这里刻意跟随系统，属于平台习惯差异）。
enum F {
    static let title = Font.system(size: 24, weight: .bold)
    static let pageTitle = Font.system(size: 17, weight: .bold)
    static let cardTitle = Font.system(size: 19, weight: .bold)
    static let body = Font.system(size: 14)
    static let bodyBold = Font.system(size: 14, weight: .bold)
    static let sub = Font.system(size: 12.5)
    static let caption = Font.system(size: 11.5)
    static let sectionTitle = Font.system(size: 13, weight: .bold)
}
