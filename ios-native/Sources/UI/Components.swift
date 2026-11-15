import SwiftUI

// MARK: - 小标题

struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(F.sectionTitle)
            .foregroundStyle(T.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
            .padding(.top, 18)
            .padding(.bottom, 8)
    }
}

// MARK: - 卡片

struct Card<Content: View>: View {
    var padding: CGFloat = 0
    let content: Content

    init(padding: CGFloat = 0, @ViewBuilder content: () -> Content) {
        self.padding = padding
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .stroke(T.border, lineWidth: 0.8)
            )
    }
}

// MARK: - 设置行

/// 「学期与教学周  2026-2027 学年第一学期  ›」这类行。
/// 对应 Android `MinePage.item(...)`。
struct InfoRow: View {
    let title: String
    var value: String = ""
    var subtitle: String = ""
    var showsChevron = false
    var action: (() -> Void)?

    var body: some View {
        let row = HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(F.body)
                    .foregroundStyle(T.textPrimary)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
            }
            Spacer(minLength: 8)
            if !value.isEmpty {
                Text(value)
                    .font(F.sub)
                    .foregroundStyle(T.textTertiary)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
            }
            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(T.textTertiary)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .contentShape(Rectangle())

        if let action {
            Button(action: action) { row }
                .buttonStyle(.plain)
        } else {
            row
        }
    }
}

struct RowDivider: View {
    var body: some View {
        Rectangle()
            .fill(T.border)
            .frame(height: 0.8)
            .padding(.leading, 16)
    }
}

// MARK: - 分段控件

/// 浅色 / 深色 / 跟随系统、成绩 / 学分绩点。
///
/// v4 起换成系统 `Picker(.segmented)`：Android 那边是「底槽 + 白色滑块」的自绘控件，
/// 但 iOS 有原生分段控件，用系统版本才能自动获得 iOS 26 的材质、动态类型缩放、
/// 选中态动画与 VoiceOver 语义。旧调用方 `(options:index:)` 接口保持不变。
struct Segmented: View {
    let options: [String]
    @Binding var index: Int

    var body: some View {
        Picker("", selection: $index) {
            ForEach(options.indices, id: \.self) { i in
                Text(options[i]).tag(i)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
    }
}

// MARK: - 带图标的链接小胶囊

/// 「GitHub主页」「Gitee主页」这种带平台 logo 的链接。
/// 用 `Link` 交给系统打开 —— iOS 的等价物是 `UIApplication.open`，
/// 但 SwiftUI 里 `Link` 更省事，且长按会出系统预览菜单。
struct LinkChip: View {
    let systemIcon: String
    let text: String
    let url: String

    var body: some View {
        if let target = URL(string: url) {
            Link(destination: target) {
                HStack(spacing: 4) {
                    Image(systemName: systemIcon)
                        .font(.system(size: 11))
                    Text(text)
                        .font(.system(size: 11))
                }
                .foregroundStyle(T.brand)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(T.brandSoft)
                .clipShape(Capsule())
            }
        }
    }
}

// MARK: - Toast

/// 轻提示（Android 的 Toast 在 iOS 上的等价物）。
///
/// iOS 没有系统 Toast；按平台习惯做成**顶部下拉横幅**：系统材质底 + 语义色文字，
/// 不压住底部标签栏，也不自绘纯黑胶囊。两秒后自动收起，深浅色都可读。
struct ToastHost: ViewModifier {
    @Binding var text: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if let message = text, !message.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "info.circle.fill")
                        .font(F.sub)
                    Text(message)
                        .font(F.sub)
                        .multilineTextAlignment(.leading)
                }
                .foregroundStyle(T.textPrimary)
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .frame(minHeight: T.minTap)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(T.border, lineWidth: 0.8)
                )
                .padding(.horizontal, T.pagePadding)
                .transition(.opacity.combined(with: .move(edge: .top)))
                .task(id: message) {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    withAnimation(.easeOut(duration: 0.2)) { text = nil }
                }
            }
        }
        .animation(Motion.fade, value: text)
    }
}

extension View {
    func toast(_ text: Binding<String?>) -> some View {
        modifier(ToastHost(text: text))
    }
}

// MARK: - 空态

struct EmptyCard: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 8) {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(T.textPrimary)
            Text(subtitle)
                .font(F.sub)
                .foregroundStyle(T.textTertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .padding(.horizontal, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .stroke(T.border, lineWidth: 0.8)
        )
    }
}
