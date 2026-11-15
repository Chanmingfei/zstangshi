import SwiftUI
import UIKit

// MARK: - 设计令牌
//
// 与 Android `native/res/values/colors.xml` + `values-night/colors.xml` 逐值对齐。
// 这里的每一个色值都直接取自安卓源码，不做「iOS 化改编」——用户要求整体外观完全保留。
//
// 唯一按期平台惯例做的调整：
//   · 颜色走动态色（UIColor dynamic provider），切深浅色自动重解析，不需要 `if dark`；
//   · 字号使用可缩放的相对字号（基于系统 body / 固定 pt 的 `.custom(_, size:, relativeTo:)`），
//     既能跟随系统无障碍字号，又保持与安卓 sp 一致的视觉比例。

enum T {

    // MARK: 品牌色（colors.xml:5-8 / values-night:15-18）

    static let brand     = dyn(0x0062CC, 0x2E86E8)
    static let brandDeep = dyn(0x004C9E, 0x1E6BBE)
    static let brandSoft = dyn(0xE8F1FC, 0x14294A)
    static let brandOnSoft = dyn(0x0062CC, 0x6FA8FF)

    // MARK: 语义背景（colors.xml:11-14 / values-night:5-11）

    static let bgPage    = dyn(0xF3F5F9, 0x0F1520)
    static let bgSurface = dyn(0xFFFFFF, 0x1A2230)
    static let bgSunken  = dyn(0xEDEFF4, 0x232B3A)

    // MARK: 文字四档

    static let textPrimary   = dyn(0x141821, 0xF2F5FA)
    static let textSecondary = dyn(0x5A6273, 0xB6BECD)
    static let textTertiary  = dyn(0x8B93A5, 0x8B93A5)
    static let borderSubtle  = dyn(0xE3E7EF, 0x2A3446)

    // MARK: 课表色板（8 色，与 CourseColorAssigner / course_N 一一对应）
    //
    // 浅色模式取 course_N 原值；深色模式下安卓保持同一套「主色」、
    // 只把「软底」压暗（values-night 里只重定义了 course_N_soft），这里照做。

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

    /// 课程主色。索引先取模 8，与安卓 `Math.abs(idx) % 8` 等价。
    static func courseColor(_ index: Int) -> Color {
        course[abs(index) % course.count]
    }

    /// 课程软底（卡片 / 色块背景）。
    static func courseBg(_ index: Int) -> Color {
        courseSoft[abs(index) % courseSoft.count]
    }

    // MARK: 状态色（从课表色板与品牌色里取，不额外发明颜色）

    static let go      = dyn(0x3FA37A, 0x3FA37A)   // 到站 / 有车
    static let goSoft  = dyn(0xE7F5EF, 0x17332A)
    static let warn    = dyn(0xE8734A, 0xE8734A)   // 延误 / 提示
    static let warnSoft = dyn(0xFCEDE7, 0x3A241C)
    static let danger  = dyn(0xC9527A, 0xC9527A)   // 错误

    // MARK: 尺寸
    //
    // Android 全部用 dp；iOS 上 1dp ≈ 1pt（视觉等大）。胶囊 Dock 的几何参数
    // 直接照抄 MainActivity.kt:68-84：
    //   dockBand = 12 + 64 + 14 = 90
    //   dock 左右边距 22，高 64，上 12 / 下 14

    static let dockHeight: CGFloat = 64
    static let dockSideMargin: CGFloat = 22
    static let dockTopGap: CGFloat = 12
    static let dockBottomGap: CGFloat = 14
    /// 页面需要给 Dock 让出的总高度（12 + 64 + 14）
    static let dockBand: CGFloat = dockTopGap + dockHeight + dockBottomGap

    /// 卡片圆角。安卓 card_bg.xml 用 16dp。
    static let cardRadius: CGFloat = 16
    /// 页面左右内边距，安卓各页用 16dp。
    static let pagePadding: CGFloat = 16

    // MARK: 工具

    static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
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

// MARK: - 字号
//
// 安卓写死 sp（关闭字体缩放）。iOS 侧既保留同等视觉大小，又允许系统动态字号：
// 用 `.custom(_, size:, relativeTo:)` 让每个尺寸跟随最接近的语义档位缩放。
// 这样默认外观与安卓 100% 一致，用户开「更大字体」时也能用。

enum F {
    /// 24sp —— 首页问候语
    static let hero = Font.system(size: scaled(24), weight: .bold)
    /// 22sp —— 成绩概览大字
    static let display = Font.system(size: scaled(22), weight: .bold)
    /// 19sp —— 首页焦点课名 / 成绩页标题 / 成绩分数
    static let title = Font.system(size: scaled(19), weight: .bold)
    /// 18sp —— 「我的」弹层标题 / 课名
    static let title2 = Font.system(size: scaled(18), weight: .bold)
    /// 17sp —— 课表页标题
    static let title3 = Font.system(size: scaled(17), weight: .bold)
    /// 16sp —— 弹层主标题 / 个人页姓名 / 月历标题
    static let headline = Font.system(size: scaled(16), weight: .bold)
    /// 15sp —— 空态标题
    static let subhead = Font.system(size: scaled(15), weight: .bold)
    /// 14sp —— 正文 / 列表主文字 / 按钮
    static let body = Font.system(size: scaled(14))
    static let bodyBold = Font.system(size: scaled(14), weight: .bold)
    /// 13.5sp —— 表单值 / 详情字段值
    static let callout = Font.system(size: scaled(13.5))
    /// 13sp —— 小标题 / 导航 / 次要按钮
    static let footnote = Font.system(size: scaled(13))
    static let footnoteBold = Font.system(size: scaled(13), weight: .bold)
    /// 12.5sp —— 辅助说明
    static let caption = Font.system(size: scaled(12.5))
    static let captionBold = Font.system(size: scaled(12.5), weight: .bold)
    /// 12sp —— 次要说明
    static let small = Font.system(size: scaled(12))
    /// 11.5sp —— 单元格标签 / 元信息
    static let tiny = Font.system(size: scaled(11.5))
    static let tinyBold = Font.system(size: scaled(11.5), weight: .bold)
    /// 11sp —— 最次级说明
    static let micro = Font.system(size: scaled(11))
    /// 10.5sp —— 课表块内引起 / Dock 标签
    static let nano = Font.system(size: scaled(10.5))
    /// 9.5sp —— 课表格子里的日期
    static let nano2 = Font.system(size: scaled(9.5))
    /// 8.5sp —— 课表块里的教室
    static let nano3 = Font.system(size: scaled(8.5))
    /// 7.5sp —— 节次栏的起止时间
    static let nano4 = Font.system(size: scaled(7.5))

    /// 课程色块标题（安卓 10sp 粗体）
    static let courseChip = Font.system(size: scaled(10), weight: .bold)

    /// 把设计稿 pt 值交给 Dynamic Type 缩放。
    /// 基准档位选 `.body`（17pt）——绝大部分界面文字都在它附近，
    /// 用同一个基准能保证各字号之间的相对比例在任何字号档位下都不变形。
    static func scaled(_ size: CGFloat) -> CGFloat {
        UIFontMetrics(forTextStyle: .body).scaledValue(for: size)
    }
}

// MARK: - 卡片与通用表面

extension View {
    /// 卡片：实底 + 16pt 连续圆角 + 细描边（对应安卓 card_bg.xml）。
    func zstCard(padding: CGFloat = 0) -> some View {
        self
            .padding(padding)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.7)
            )
    }

    /// 圆角矩形底（对应安卓 roundRect(color, radius)）
    func roundRect(_ color: Color, radius: CGFloat, lineWidth: CGFloat = 0) -> some View {
        self.background(
            RoundedRectangle(cornerRadius: radius, style: .continuous).fill(color)
        )
    }

    /// 页面背景（安卓每页 setBackgroundColor(bg_page / 0xFF0F1520)）
    func pageBackground() -> some View {
        self.background(T.bgPage.ignoresSafeArea())
    }
}

// MARK: - 颜色便捷（读取当前 colorScheme 下的动态色）
//
// SwiftUI 的 Color 是惰性解析的，不能 `.opacity()` 之外直接取 RGBA。
// 需要「把某个动态色转成固定值」时（比如放进 Canvas / AttributedString），
// 用这个辅助函数在当前 trait 下解析。

extension Color {
    func resolved(_ scheme: ColorScheme) -> UIColor {
        UIColor(self).resolvedColor(with: UITraitCollection(userInterfaceStyle:
            scheme == .dark ? .dark : .light))
    }
}