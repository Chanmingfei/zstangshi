import SwiftUI

// MARK: - 通用组件
//
// 对齐 Android `ui/` 下的自绘控件与各页面里重复出现的小结构。
// 这里只做「纯展示」，不持有业务状态。

// MARK: 卡片

/// 基础卡片容器（安卓 card_bg.xml：白底 + 16dp 圆角 + 1px 描边）
struct ZSTCard<Content: View>: View {
    var padding: EdgeInsets = EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
    var gap: CGFloat = 12
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: gap) {
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(padding)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }
}

/// 小标题（安卓各页 sectionTitle：13sp 灰粗体）
struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(F.footnoteBold)
            .foregroundStyle(T.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 「左标题 + 右值」的一行（我的页 / 详情页反复出现）
struct InfoRow: View {
    let title: String
    var value: String = ""
    var valueColor: Color = T.textTertiary
    var showChevron: Bool = false

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(F.body)
                .foregroundStyle(T.textPrimary)
            Spacer(minLength: 8)
            if !value.isEmpty {
                Text(value)
                    .font(F.footnote)
                    .foregroundStyle(valueColor)
                    .multilineTextAlignment(.trailing)
            }
            if showChevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(T.textTertiary)
            }
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

/// 详情页的「左标签右值」字段行（课表详情弹层里用，标签固定 52pt 宽）
struct FieldRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Text(label)
                .font(F.caption)
                .foregroundStyle(T.textTertiary)
                .frame(width: 52, alignment: .leading)
            Text(value)
                .font(F.callout)
                .foregroundStyle(T.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 11)
    }
}

// MARK: 空态

/// 空态卡片（安卓 emptyCard：居中标题 + 说明）
struct EmptyCard: View {
    let title: String
    let subtitle: String
    var icon: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            if let icon {
                Image(systemName: icon)
                    .font(.system(size: 28, weight: .regular))
                    .foregroundStyle(T.textTertiary.opacity(0.7))
                    .padding(.bottom, 14)
            }
            Text(title)
                .font(F.subhead)
                .foregroundStyle(T.textPrimary)
                .multilineTextAlignment(.center)
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .padding(.horizontal, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }
}

// MARK: 按钮

/// 主按钮（安卓 btn_primary：品牌色实底 + 胶囊）
struct PrimaryButtonStyle: ButtonStyle {
    var enabled: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(F.bodyBold)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(enabled ? T.brand : T.textTertiary)
            )
            .opacity(configuration.isPressed ? 0.86 : 1)
            .animation(M.press, value: configuration.isPressed)
    }
}

/// 次按钮（安卓 btn_outline：白底 + 品牌色文字 + 描边）
struct OutlineButtonStyle: ButtonStyle {
    var enabled: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(F.bodyBold)
            .foregroundStyle(enabled ? T.brand : T.textTertiary)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(T.bgSurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.9)
            )
            .opacity(configuration.isPressed ? 0.86 : 1)
            .animation(M.press, value: configuration.isPressed)
    }
}

// MARK: 分段控件（安卓 SegmentedControl：胶囊底 + 滑动选中块）

struct Segmented: View {
    let titles: [String]
    @Binding var selected: Int
    var onChange: ((Int) -> Void)? = nil

    @Namespace private var ns

    var body: some View {
        GeometryReader { geo in
            let count = max(1, titles.count)
            let cellW = geo.size.width / CGFloat(count)

            ZStack(alignment: .leading) {
                // 滑动选中块
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(T.bgSurface)
                    .shadow(color: .black.opacity(0.08), radius: 3, y: 1)
                    .frame(width: cellW - 4, height: geo.size.height - 8)
                    .offset(x: CGFloat(selected) * cellW + 2)

                HStack(spacing: 0) {
                    ForEach(titles.indices, id: \.self) { i in
                        Text(titles[i])
                            .font(selected == i ? F.footnoteBold : F.footnote)
                            .foregroundStyle(selected == i ? T.textPrimary : T.textSecondary)
                            .frame(width: cellW, height: geo.size.height)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                guard selected != i else { return }
                                Haptics.select()
                                withAnimation(M.overshoot) { selected = i }
                                onChange?(i)
                            }
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .fill(T.bgSunken)
            )
        }
        .frame(height: 40)
        .animation(M.overshoot, value: selected)
    }
}

// MARK: Toast（安卓 Toast：底部浮出，1.8 秒自动消失）

struct ToastView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(F.caption)
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 18)
            .padding(.vertical, 11)
            .background(
                Capsule(style: .continuous)
                    .fill(Color.black.opacity(0.82))
            )
            .padding(.horizontal, 40)
    }
}

/// Toast 宿主：挂在根视图上，`text` 变非 nil 就浮出
struct ToastHost: ViewModifier {
    @Binding var text: String?
    @State private var visible = false
    @State private var hideTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if visible, let text, !text.isEmpty {
                    ToastView(text: text)
                        .padding(.bottom, T.dockBand + 68)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .allowsHitTesting(false)
                }
            }
            .onChange(of: text) { _, newValue in
                guard newValue != nil else { return }
                withAnimation(M.fadeIn) { visible = true }
                hideTask?.cancel()
                hideTask = Task {
                    try? await Task.sleep(nanoseconds: 1_800_000_000)
                    guard !Task.isCancelled else { return }
                    await MainActor.run {
                        withAnimation(M.fadeOut) { visible = false }
                        text = nil
                    }
                }
            }
    }
}

extension View {
    func toastHost(_ text: Binding<String?>) -> some View {
        modifier(ToastHost(text: text))
    }
}

// MARK: 下拉刷新
//
// 安卓用自绘 PullRefreshLayout（品牌色圆环 + 保底 2 秒）。
// iOS 侧用 Refreshable，但要保住「保底转 2 秒」的手感就得自己控制时长。

extension View {
    /// 标准 `.refreshable`，但保证动作至少持续 `minDuration` 秒，
    /// 复现安卓「一刷新就结束会让人怀疑没刷新」的安抚反馈。
    ///
    /// 直接转发给系统 `refreshable`：它的签名是
    /// `@escaping @Sendable () async -> Void`，所以这里也要求 `@Sendable`。
    /// 调用方只需写 `{ @MainActor in await xxx() }` —— SwiftUI View 本身就在主 actor 上。
    func zstRefreshable(minDuration: TimeInterval = 2.0,
                        action: @escaping @Sendable () async -> Void) -> some View {
        self.refreshable {
            let start = Date()
            await action()
            let elapsed = Date().timeIntervalSince(start)
            if elapsed < minDuration {
                try? await Task.sleep(nanoseconds: UInt64((minDuration - elapsed) * 1_000_000_000))
            }
        }
    }
}

// MARK: 加载圈

struct LoadingRing: View {
    var size: CGFloat = 20
    @State private var spinning = false

    var body: some View {
        Circle()
            .trim(from: 0.05, to: 0.75)
            .stroke(T.brand, style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
            .frame(width: size, height: size)
            .rotationEffect(.degrees(spinning ? 360 : 0))
            .animation(.linear(duration: 0.85).repeatForever(autoreverses: false),
                       value: spinning)
            .onAppear { spinning = true }
    }
}

// MARK: 状态 / 错误块

struct StateBlock: View {
    let message: String
    var loading = false
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 14) {
            if loading { LoadingRing(size: 26) }
            Text(message)
                .font(F.callout)
                .foregroundStyle(T.textSecondary)
                .multilineTextAlignment(.center)
            if let retry {
                Button("重新读取", action: retry)
                    .buttonStyle(OutlineButtonStyle())
                    .frame(maxWidth: 160)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 26)
        .padding(.horizontal, 18)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }
}

// MARK: 数字（等宽，刷新时不跳）

struct MonoNumber: View {
    let text: String
    var size: CGFloat = 22

    var body: some View {
        Text(text)
            .font(.system(size: F.scaled(size), weight: .bold, design: .rounded))
            .monospacedDigit()
            .foregroundStyle(T.brand)
    }
}