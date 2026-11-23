import SwiftUI

/// 呼吸光晕：外圈由小放大并淡出，循环播放。
///
/// 用途（对齐安卓）：候车站标记、单车跟随页「车辆所在站」标记。
/// 这两处都是"当前最重要的一格"，安卓用一圈缓慢扩散的淡色光圈做提示；
/// 用 `repeatForever(autoreverses: false)` + 一次性触发，避免来回缩放看起来像心跳。
struct PulseHalo: View {
    var color: Color = Color(red: 0.0, green: 0.48, blue: 1.0)
    var diameter: CGFloat = 30
    var duration: Double = 1.8

    @State private var animate = false

    var body: some View {
        Circle()
            .fill(color.opacity(0.28))
            .frame(width: diameter, height: diameter)
            .scaleEffect(animate ? 1.75 : 0.85)
            .opacity(animate ? 0 : 0.9)
            .animation(.easeOut(duration: duration).repeatForever(autoreverses: false), value: animate)
            .onAppear { animate = true }
            .allowsHitTesting(false)
    }
}
