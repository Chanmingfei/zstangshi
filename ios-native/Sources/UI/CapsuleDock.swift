import SwiftUI

/// 悬浮胶囊 Dock —— 完整复刻安卓 `ui/CapsuleDock.kt`。
///
/// 这是本 App 的招牌控件，几何与交互都按安卓源码逐值对齐：
///
/// 几何（CapsuleDock.kt:127-133, 210-285）：
///   · 高度 64pt（MainActivity.kt:79 的 64dp）
///   · 左右边距 22pt，圆角 = 高度/2（全胶囊）
///   · 内部 pad = 5pt，选中块从 pad 内缩到 pad
///   · 图标 20pt 在上、标签 10.5pt 在下，间隔 2pt
///
/// 交互（CapsuleDock.kt:135-196）：
///   · 点一下 → 选中块以 320ms + Overshoot(1.4) 滑过去
///   · 按住横拖 → 选中块跟手，横向拉长到 1.06，每过一格轻震
///   · 按着别的格 → 先浮出一颗淡胶囊预告落点
///
/// 材质：
///   · iOS 26+ → 真 Liquid Glass（glassEffect）
///   · iOS 17–25 → 回退到与安卓一致的「半透明白 + 细描边」
///
/// ⚠️ 与上一版 iOS 的关键区别：上一版直接用了系统 `TabView`（5 个 tab），
/// 那既不是安卓的设计、也让「实时公交」错位成了第 5 个一级页。
/// 这里恢复成安卓原本的**4 项自绘胶囊**。
struct CapsuleDock: View {
    /// 当前选中的 tab（0..count-1）
    @Binding var selected: Int
    /// 页面带的连续位置（小数），拖动时跟随手指
    let deckPosition: CGFloat
    /// 点击某项
    var onSelect: (Int) -> Void
    /// 再次点击已选中项（首页=刷新，课表=回到本周）
    var onReselect: (Int) -> Void

    @State private var dragOrigin: CGFloat = 0
    @State private var dragging = false
    @State private var pressedSlot: Int? = nil
    @State private var lastHover = 0

    private let tabs = MainTab.allCases
    private let pad: CGFloat = 5
    private let iconSize: CGFloat = 20
    private let iconGap: CGFloat = 2

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            let cellW = (w - pad * 2) / CGFloat(tabs.count)
            // 选中块中心位置（格单位，可小数）：跟手拖动时用 local，否则跟页面带
            let pos: CGFloat = dragging ? dragOrigin : deckPosition

            ZStack(alignment: .topLeading) {
                // ── 背景 ──
                // iOS 26：由 GlassGroup 里的 glassEffect 提供真玻璃材质，
                //         这里只补一条极淡的实底，保证内容透出时有足够对比度。
                // iOS 17–25：用与安卓一致的半透明白 + 细描边。
                if GlassKit.isLiquidGlass {
                    Capsule(style: .continuous)
                        .fill(.clear)
                        .background(
                            Capsule(style: .continuous)
                                .fill(.ultraThinMaterial)
                        )
                } else {
                    Capsule(style: .continuous)
                        .fill(T.bgSurface)
                        .overlay(
                            Capsule(style: .continuous)
                                .strokeBorder(T.borderSubtle, colorScheme == .dark
                                              ? 0.18 : 0.11,
                                              lineWidth: 0.8)
                        )
                        .shadow(color: .black.opacity(0.10), radius: 14, y: 5)
                }

                // ── 选中块 ──
                let stretch: CGFloat = dragging ? 1.06 : 1
                let cx = pad + cellW * (pos + 0.5)
                let halfW = cellW / 2 * stretch
                Capsule(style: .continuous)
                    .fill(T.brandSoft)
                    .frame(width: halfW * 2, height: h - pad * 2)
                    .offset(x: cx - halfW, y: pad)

                // ── 按着别的格：淡胶囊预告落点 ──
                if let slot = pressedSlot, slot != Int(pos.rounded()) {
                    let pcx = pad + cellW * (CGFloat(slot) + 0.5)
                    Capsule(style: .continuous)
                        .fill(T.brandSoft.opacity(0.55))
                        .frame(width: cellW, height: h - pad * 2)
                        .offset(x: pcx - cellW / 2, y: pad)
                        .transition(.opacity)
                }

                // ── 图标 + 文字 ──
                HStack(spacing: 0) {
                    ForEach(tabs.indices, id: \.self) { i in
                        dockItem(index: i, pos: pos, height: h)
                            .frame(width: cellW, height: h)
                    }
                }
                .offset(x: pad)
                .padding(.vertical, pad)
            }
            .contentShape(Capsule(style: .continuous))
            .gesture(dockGesture(width: w, pad: pad, cellW: cellW))
        }
        .frame(height: T.dockHeight)
        .padding(.horizontal, T.dockSideMargin)
        .padding(.top, T.dockTopGap)
        .padding(.bottom, T.dockBottomGap)
    }

    // MARK: - 单项

    @ViewBuilder
    private func dockItem(index i: Int, pos: CGFloat, height: CGFloat) -> some View {
        let near = max(0, 1 - abs(pos - CGFloat(i)))     // 离选中块越近越亮
        let tint = near > 0.5 ? T.brand : T.textTertiary
        let tab = tabs[i]

        VStack(spacing: iconGap) {
            Spacer(minLength: 0)
            Image(systemName: near > 0.5 ? tab.iconFilled : tab.icon)
                .font(.system(size: iconSize, weight: near > 0.5 ? .semibold : .regular))
                .foregroundStyle(tint)
                .frame(height: iconSize)
            Text(tab.title)
                .font(.system(size: F.scaled(10.5), weight: near > 0.5 ? .bold : .regular))
                .foregroundStyle(tint)
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .animation(M.overshoot, value: near)
    }

    // MARK: - 手势

    private func dockGesture(width: CGFloat, pad: CGFloat, cellW: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if !dragging {
                    dragOrigin = deckPosition
                    pressedSlot = slotAt(value.startLocation.x, pad: pad, cellW: cellW)
                    lastHover = Int(dragOrigin.rounded())
                }
                let dx = value.translation.width
                let dy = value.translation.height

                // 明显横拖（>12pt 且横向占优）才接管，轻微抖动仍算点按
                if !dragging, abs(dx) > 12, abs(dx) > abs(dy) * 1.5 {
                    dragging = true
                    pressedSlot = nil
                }
                if dragging {
                    let p = slotAt(value.location.x, pad: pad, cellW: cellW)
                    dragOrigin = p
                    let hover = Int(p.rounded())
                    if hover != lastHover {
                        lastHover = hover
                        Haptics.tick()
                    }
                }
            }
            .onEnded { value in
                let wasDrag = dragging
                let dx = value.translation.width
                let dy = value.translation.height
                dragging = false

                if wasDrag {
                    let target = Int(dragOrigin.rounded())
                    pressedSlot = nil
                    commit(target, fromDrag: true)
                    return
                }

                // 点按：位移很小才算点，否则当作误触忽略
                pressedSlot = nil
                guard abs(dx) < 12, abs(dy) < 12 else { return }
                let target = slotAt(value.location.x, pad: pad, cellW: cellW)
                commit(target, fromDrag: false)
            }
    }

    private func commit(_ target: Int, fromDrag: Bool) {
        let clamped = max(0, min(tabs.count - 1, target))
        if clamped != selected {
            if !fromDrag { Haptics.select() }
            onSelect(clamped)
        } else {
            if !fromDrag { onReselect(clamped) }
        }
    }

    /// 手指 x → 格位置（小数，夹在 0..n-1）。对应安卓 CapsuleDock.slotAt。
    private func slotAt(_ x: CGFloat, pad: CGFloat, cellW: CGFloat) -> CGFloat {
        guard cellW > 0 else { return 0 }
        let p = (x - pad) / cellW - 0.5
        return max(0, min(CGFloat(tabs.count - 1), p))
    }

    // MARK: - 材质参数
    //
    // 对应安卓 CapsuleDock.kt:224-232：
    //   夜间 bgPaint 0xF21C222E（95% 不透明的深蓝黑）、描边 0x2EFFFFFF（18% 白）
    //   浅色 bgPaint 0xFAFFFFFF（98% 白）、描边 0x1C14203A（11% 墨）

    @Environment(\.colorScheme) private var colorScheme
}