import SwiftUI
import UIKit

// MARK: - 实时公交 · 页面可复用组件
//
// 只渲染，不持有业务状态；数据一律来自 BusViewModel / BusModels，文案一律走 BusText。
// 玻璃只给悬浮控件（FAB），内容层用 T.bgSurface 实色（zstCard）。

// MARK: 滚动偏移 PreferenceKey
//
// 三个独立 key：首页、线路页、行程流内嵌窗口各用一个，避免嵌套 ScrollView 的
// preference 沿视图树往上传时互相串台。

struct BusHomeScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

struct BusLineScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

struct FlowScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// 放在可滚动内容最顶部的透明标记：把自身在命名坐标空间里的 minY 写进 preference。
/// 内容向上滚时 minY 变负，取 -minY 即滚动偏移。
struct ScrollOffsetMarker<K: PreferenceKey>: View where K.Value == CGFloat {
    let space: String
    let key: K.Type

    var body: some View {
        GeometryReader { geo in
            Color.clear
                .preference(key: key, value: geo.frame(in: .named(space)).minY)
        }
        .frame(height: 0)
    }
}

// MARK: - 呼吸光晕

/// 圆点呼吸光晕：1.0 → 1.25 → 1.0，800ms 往复。
/// 候车站用品牌色、车辆绿点用 T.go；「减弱动态效果」开启时静止。
struct PulseHalo: View {
    let color: Color
    var size: CGFloat = 24

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var big = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .scaleEffect(reduceMotion ? 1 : (big ? 1.25 : 1))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                    big = true
                }
            }
    }
}

// MARK: - 横排站线（换候车站）

/// 横排站线：ScrollView(.horizontal)，当前候车站圆点带品牌色 PulseHalo，点击换站。
struct RouteRail: View {
    let stops: [BusStop]
    let selectedId: String
    let onPick: (BusStop) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 0) {
                ForEach(Array(stops.enumerated()), id: \.offset) { idx, stop in
                    BusRouteRailNode(
                        stop: stop,
                        selected: stop.id == selectedId,
                        first: idx == 0,
                        last: idx == stops.count - 1,
                        onPick: onPick
                    )
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
        }
        .background(T.bgSurface)
    }
}

private struct BusRouteRailNode: View {
    let stop: BusStop
    let selected: Bool
    let first: Bool
    let last: Bool
    let onPick: (BusStop) -> Void

    var body: some View {
        Button { onPick(stop) } label: {
            VStack(spacing: 6) {
                ZStack {
                    // 本节点占 56pt：左右各一段 28pt 连接线，在圆点处相接
                    HStack(spacing: 0) {
                        Rectangle().fill(first ? Color.clear : T.brand.opacity(0.35)).frame(width: 28, height: 2)
                        Rectangle().fill(last ? Color.clear : T.brand.opacity(0.35)).frame(width: 28, height: 2)
                    }
                    dot
                }
                .frame(width: 56, height: 28)

                Text(stop.name)
                    .font(F.caption)
                    .foregroundStyle(selected ? T.brand : T.textSecondary)
                    .lineLimit(1)
                    .frame(width: 68)
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var dot: some View {
        if selected {
            PulseHalo(color: T.brand.opacity(0.20), size: 26)
            Circle().fill(T.brand).frame(width: 12, height: 12)
        } else {
            Circle().fill(T.textTertiary.opacity(0.55)).frame(width: 9, height: 9)
        }
    }
}

// MARK: - 竖向行程流（240pt 内嵌窗口）

/// 行程流：保持约 240pt 的内嵌滚动窗口，不摊平（否则下方信息被推下去）。
/// 车辆所在站绿点带绿色 PulseHalo，已过站用灰、未到站用品牌色。
/// scrollY 用于 20s 重建时保留滚动位置（对应 Android flowScrollY）。
struct FlowRail: View {
    let detail: BusLineDetail
    @Binding var scrollY: CGFloat
    /// 单车跟随时传入所跟车辆站序；nil 时由 displayVehicle / waitingOrder 推导
    var highlightedOrder: Int? = nil

    private let rowHeight: CGFloat = 36
    private let windowHeight: CGFloat = 240
    private let spaceName = "flowRailSpace"

    private var currentOrder: Int {
        if let o = highlightedOrder { return o }
        if let v = detail.displayVehicle(), v.order > 0 { return v.order }
        return detail.waitingOrder
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 0) {
                    ScrollOffsetMarker(space: spaceName, key: FlowScrollOffsetKey.self)
                    ForEach(Array(detail.stops.enumerated()), id: \.offset) { idx, stop in
                        BusFlowRailRow(
                            stop: stop,
                            currentOrder: currentOrder,
                            first: idx == 0,
                            last: idx == detail.stops.count - 1
                        )
                        .id(idx)
                    }
                }
            }
            .coordinateSpace(name: spaceName)
            .frame(height: windowHeight)
            .onPreferenceChange(FlowScrollOffsetKey.self) { minY in
                scrollY = max(0, -minY)
            }
            .onAppear { restore(proxy) }
            .onChange(of: detail) { _ in restore(proxy) }
        }
    }

    private func restore(_ proxy: ScrollViewProxy) {
        guard !detail.stops.isEmpty else { return }
        let idx = min(max(0, Int(scrollY / rowHeight)), detail.stops.count - 1)
        proxy.scrollTo(idx, anchor: .top)
    }
}

private struct BusFlowRailRow: View {
    let stop: BusStop
    let currentOrder: Int
    let first: Bool
    let last: Bool

    private var isCurrent: Bool { stop.order == currentOrder }
    private var isPassed: Bool { stop.order < currentOrder }

    var body: some View {
        HStack(spacing: 0) {
            rail.frame(width: 28)
            Text(stop.orderLabel.isEmpty ? "\(stop.order)" : stop.orderLabel)
                .font(F.caption)
                .foregroundStyle(orderColor)
                .frame(width: 30, alignment: .leading)
            Text(stop.name)
                .font(isCurrent ? F.bodyBold : F.body)
                .foregroundStyle(nameColor)
                .lineLimit(1)
            Spacer(minLength: 6)
        }
        .frame(height: 36)
    }

    private var rail: some View {
        ZStack {
            VStack(spacing: 0) {
                Rectangle().fill(first ? Color.clear : topLine).frame(width: 2, height: 18)
                Rectangle().fill(last ? Color.clear : bottomLine).frame(width: 2, height: 18)
            }
            if isCurrent {
                PulseHalo(color: T.go.opacity(0.25), size: 26)
                Circle().fill(T.go).frame(width: 20, height: 20)
            } else {
                Circle().fill(dotColor).frame(width: 8, height: 8)
            }
        }
    }

    private var topLine: Color {
        stop.order <= currentOrder ? T.textTertiary.opacity(0.35) : T.brand
    }
    private var bottomLine: Color {
        stop.order < currentOrder ? T.textTertiary.opacity(0.35) : T.brand
    }
    private var dotColor: Color {
        isPassed ? T.textTertiary.opacity(0.45) : T.brand
    }
    private var orderColor: Color {
        isCurrent ? T.go : (isPassed ? T.textTertiary.opacity(0.6) : T.brand)
    }
    private var nameColor: Color {
        isCurrent ? T.go : (isPassed ? T.textTertiary : T.textPrimary)
    }
}

// MARK: - 乘车码唤起

/// 只做唤起：scheme 对齐 Android，打不开由调用方提示。
/// 注意：canOpenURL 需要在 Info.plist 的 LSApplicationQueriesSchemes 里声明对应 scheme。
enum TransitCodeLauncher {
    static func schemeURL(for choice: TransitCodeChoice) -> URL? {
        switch choice {
        case .ask:      return nil
        // 与 Android bus/TransitCode.kt 完全一致：
        // 支付宝乘车码官方入口 appId=200011235；云闪付全国通用乘车码路由。
        case .alipay:   return URL(string: "alipays://platformapi/startapp?appId=200011235")
        case .unionpay: return URL(string: "upwallet://rn/rnhtmlridingcode")
        }
    }

    /// 返回 true 表示已尝试拉起；false 表示打不开（调用方给提示）。
    static func attemptOpen(_ choice: TransitCodeChoice) -> Bool {
        guard let url = schemeURL(for: choice) else { return false }
        guard UIApplication.shared.canOpenURL(url) else { return false }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
        return true
    }
}

// MARK: - 乘车码 FAB

/// 右下悬浮玻璃 FAB：56pt 高、16pt 圆角、阴影。
/// 展开 260ms / 收起 220ms；文字用真实布局（fixedSize），不硬编码宽度。
struct TransitCodeFab: View {
    @Binding var expanded: Bool
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 10) {
                Image(systemName: "qrcode")
                    .font(F.bodyBold)
                    .frame(width: 24, height: 24)
                if expanded {
                    Text("乘车码")
                        .font(F.bodyBold)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .transition(.opacity.combined(with: .move(edge: .trailing)))
                }
            }
            .foregroundStyle(T.brand)
            .padding(.horizontal, expanded ? 18 : 16)
            .frame(height: 56)
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .glassSurface(in: RoundedRectangle(cornerRadius: 16, style: .continuous),
                          tint: T.brand.opacity(0.14), interactive: true)
        }
        .buttonStyle(.plain)
        .shadow(color: T.brand.opacity(0.28), radius: 12, y: 6)
        .animation(expanded
                   ? .easeInOut(duration: 0.26)
                   : .easeInOut(duration: 0.22),
                   value: expanded)
    }
}

// MARK: - 乘车码选择面板

/// 系统 Toggle「记住选择」+ 三个选项 + 微信下线说明。
/// onPick(choice, remember)：由调用方负责持久化与唤起；本面板只负责选择与收起。
struct TransitCodeSheet: View {
    let choice: TransitCodeChoice
    let onPick: (TransitCodeChoice, Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var remember: Bool

    init(choice: TransitCodeChoice,
         onPick: @escaping (TransitCodeChoice, Bool) -> Void) {
        self.choice = choice
        self.onPick = onPick
        _remember = State(initialValue: choice != .ask)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(TransitCodeChoice.allCases) { c in
                        optionRow(c)
                    }
                } header: {
                    Text("打开哪个乘车码")
                }
                Section {
                    Toggle("记住选择，下次直接打开", isOn: $remember)
                } footer: {
                    Text(TransitCodeChoice.wechatNotice)
                }
            }
            .navigationTitle("乘车码")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    private func optionRow(_ c: TransitCodeChoice) -> some View {
        Button {
            onPick(c, remember)
            dismiss()
        } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(c.label)
                        .font(F.body)
                        .foregroundStyle(T.textPrimary)
                    Text(c.detail)
                        .font(F.caption)
                        .foregroundStyle(T.textSecondary)
                }
                Spacer(minLength: 8)
                if c == choice {
                    Image(systemName: "checkmark.circle.fill")
                        .font(F.body)
                        .foregroundStyle(T.brand)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 到站行

/// 右侧蓝色车况字与左侧线路名同字号（F.body，绝不放大）；
/// 「下辆」用 nextLabel 且只在上游真给了数时才显示；
/// 大字用 etaCompact / label；无数字状态降到 F.sub。
struct ArrivalRow: View {
    let arrival: BusArrival
    var bigStyle: Bool = true

    private var primary: String {
        bigStyle ? arrival.etaCompact : arrival.label
    }
    private var numeric: Bool {
        let t = primary
        return t == "…" || t == "到站了" || t == "即将到站"
            || t.contains("分钟") || t.contains("站")
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(arrival.lineName)
                    .font(F.bodyBold)
                    .foregroundStyle(T.textPrimary)
                    .lineLimit(1)
                Text("开往 " + (arrival.destination.isEmpty ? "—" : arrival.destination))
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(primary)
                    .font(numeric ? F.body : F.sub)
                    .fontWeight(numeric ? .semibold : .regular)
                    .foregroundStyle(numeric ? T.brand : T.textSecondary)
                    .lineLimit(1)
                if !arrival.nextLabel.isEmpty {
                    Text(arrival.nextLabel)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 10)
    }
}
