import SwiftUI

/// 悬浮胶囊底栏（视觉与交互对齐 Android `CapsuleDock`）。
///
/// Android 那个是 Canvas 手绘 + ValueAnimator（选中块 320ms Overshoot 滑过去）；
/// iOS 这里用 SwiftUI 最贴近的等价物：
///   - 选中块 = 一个 `Capsule`，位置靠 `matchedGeometryEffect` 在格与格之间补间；
///   - 补间动画用 `.spring(response:dampingFraction:)`，手感和 OvershootInterpolator(1.4) 接近；
///   - 轻震用 `UIImpactFeedbackGenerator`（对应 `HapticFeedbackConstants`）。
///
/// 为什么不用系统 `TabView`：系统 tab bar 在 iOS 26 上是玻璃悬浮条，虽然好看，
/// 但和 Android 端的胶囊宽度、圆角、选中块形态都对不上；双端一致性优先，这里自绘。
struct CapsuleDock: View {

    @Binding var selected: Int
    /// 再点一次当前项（课表页 = 回到本周）
    var onReselect: (Int) -> Void

    private let items: [(icon: String, label: String)] = [
        ("house.fill", "首页"),
        ("calendar", "课表"),
        ("chart.bar.fill", "成绩"),
        ("person.fill", "我的")
    ]

    @Namespace private var pill

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { i in
                cell(i)
            }
        }
        .padding(6)
        .frame(height: T.dockHeight)
        .background(
            Capsule(style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay(
                    Capsule(style: .continuous).stroke(T.border, lineWidth: 0.8)
                )
        )
        .shadow(color: Color.black.opacity(0.10), radius: 12, x: 0, y: 6)
        .padding(.horizontal, 22)
        .padding(.bottom, 6)
    }

    private func cell(_ i: Int) -> some View {
        let on = selected == i
        return Button {
            if selected == i {
                onReselect(i)
            } else {
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                withAnimation(.spring(response: 0.32, dampingFraction: 0.72)) {
                    selected = i
                }
            }
        } label: {
            VStack(spacing: 3) {
                Image(systemName: items[i].icon)
                    .font(.system(size: 18, weight: on ? .semibold : .regular))
                Text(items[i].label)
                    .font(.system(size: 10.5, weight: on ? .semibold : .regular))
            }
            .foregroundStyle(on ? T.brand : T.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if on {
                    Capsule(style: .continuous)
                        .fill(T.brandSoft)
                        .matchedGeometryEffect(id: "pill", in: pill)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(items[i].label)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}
