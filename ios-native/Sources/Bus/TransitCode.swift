import SwiftUI
import UIKit

/// 乘车码跳转（对齐 Android `bus/TransitCode.kt`）。
///
/// 只唤起外部 App，不内置任何支付凭证：
///   支付宝 alipays://platformapi/startapp?appId=200011235
///   云闪付 upwallet://rn/rnhtmlridingcode
///
/// 三级降级：带包名 → 不带包名 → 只开 App 首页。
/// 微信渠道已于 2026-11 下线，不再提供。
struct TransitCodeSheet: View {
    @Environment(\.dismiss) private var dismiss

    private struct Channel: Identifiable {
        let id = UUID()
        let title: String
        let subtitle: String
        let icon: String
        let color: Color
        /// 依次尝试的 URL
        let candidates: [String]
        /// 用于 canOpenURL 探测的 scheme
        let schemes: [String]
    }

    private let channels: [Channel] = [
        Channel(title: "支付宝乘车码",
                subtitle: "打开支付宝 App 内的乘车码",
                icon: "a.circle.fill",
                color: Color(hex: 0x1677FF),
                candidates: [
                    "alipays://platformapi/startapp?appId=200011235",
                    "alipays://"
                ],
                schemes: ["alipays"]),
        Channel(title: "云闪付乘车码",
                subtitle: "打开云闪付 App 内的乘车码",
                icon: "c.circle.fill",
                color: Color(hex: 0xD32F2F),
                candidates: [
                    "upwallet://rn/rnhtmlridingcode",
                    "upwallet://"
                ],
                schemes: ["upwallet"])
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("乘车码")
                .font(F.headline)
                .foregroundStyle(T.textPrimary)

            Text("选择要使用的乘车码，将跳转到对应的 App。本 App 不内置任何支付凭证。")
                .font(F.caption)
                .foregroundStyle(T.textTertiary)

            ForEach(channels) { ch in
                Button {
                    open(ch)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: ch.icon)
                            .font(.system(size: 26))
                            .foregroundStyle(ch.color)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ch.title)
                                .font(F.bodyBold)
                                .foregroundStyle(T.textPrimary)
                            Text(ch.subtitle)
                                .font(F.micro)
                                .foregroundStyle(T.textTertiary)
                        }
                        Spacer()
                        Image(systemName: "arrow.up.forward.app")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(T.textTertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
                    .background(T.bgSurface)
                    .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                            .strokeBorder(T.borderSubtle, lineWidth: 0.7)
                    )
                }
                .buttonStyle(.pressable(0.97))
            }

            Button("关闭") { dismiss() }
                .buttonStyle(OutlineButtonStyle())
                .padding(.top, 4)
        }
        .padding(.horizontal, 18)
        .padding(.top, 18)
        .padding(.bottom, 24)
        .presentationDetents([.height(400)])
        .presentationDragIndicator(.visible)
        .presentationBackground(T.bgPage)
    }

    /// 三级降级：逐个 scheme 探测，能打开就用那一条
    private func open(_ ch: Channel) {
        Haptics.light()
        for scheme in ch.schemes {
            guard let probe = URL(string: scheme + "://"),
                  UIApplication.shared.canOpenURL(probe) else { continue }
            for candidate in ch.candidates {
                if let url = URL(string: candidate),
                   UIApplication.shared.canOpenURL(url) {
                    UIApplication.shared.open(url)
                    dismiss()
                    return
                }
            }
            // 都打不开具体页面 → 只开 App 首页
            if let home = URL(string: scheme + "://") {
                UIApplication.shared.open(home)
                dismiss()
                return
            }
        }
        // 一个都没装
        dismiss()
    }
}