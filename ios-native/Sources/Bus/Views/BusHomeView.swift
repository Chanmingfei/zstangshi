import SwiftUI

// MARK: - 实时公交首页
//
// RootView 已经把本页放进一根 NavigationStack，且从外部设置了 navigationTitle /
// large 显示模式，所以这里**不再自建 NavigationStack**、也不重复设标题；
// 二级页用 NavigationLink 直接 push，弹层用 .sheet，全部是系统转场。
struct BusHomeView: View {
    @StateObject private var vm = BusViewModel()

    @State private var fabExpanded = true
    @State private var lastScrollY: CGFloat = 0
    @State private var showSearch = false
    @State private var showTransitSheet = false
    @State private var transitAlert: String?

    private let pageSpace = "busHomeScroll"

    var body: some View {
        ScrollView {
            ScrollOffsetMarker(space: pageSpace, key: BusHomeScrollOffsetKey.self)
            LazyVStack(spacing: 12) {
                controls
                searchEntry
                if let err = vm.errorText { errorCard(err) }
                if vm.loading && vm.stations.isEmpty { loadingCard }
                nearbySection
                favoritesSection
                if vm.stations.isEmpty && !vm.loading && vm.errorText == nil {
                    emptyHint
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 12)
            .padding(.bottom, 96) // 给悬浮 FAB 留空间
        }
        .coordinateSpace(name: pageSpace)
        .background(T.bgPage)
        .refreshable { await vm.refresh() }
        .onPreferenceChange(BusHomeScrollOffsetKey.self) { minY in updateFab(minY) }
        .overlay(alignment: .bottomTrailing) {
            TransitCodeFab(expanded: $fabExpanded, onOpen: openTransit)
                .padding(.trailing, 20)
                .padding(.bottom, 20)
        }
        .sheet(isPresented: $showSearch) {
            BusSearchView(vm: vm)
        }
        .sheet(isPresented: $showTransitSheet) {
            TransitCodeSheet(choice: vm.transitChoice, onPick: handleTransit)
        }
        .alert("无法打开乘车码", isPresented: alertPresented) {
            Button("好", role: .cancel) { transitAlert = nil }
        } message: {
            Text(transitAlert ?? "")
        }
        .onAppear { vm.start() }
    }

    // MARK: - 滚动收放 FAB

    private func updateFab(_ minY: CGFloat) {
        let y = max(0, -minY)
        let dy = y - lastScrollY
        if y <= 0 { fabExpanded = true }
        else if dy > 4 { fabExpanded = false }
        else if dy < -4 { fabExpanded = true }
        lastScrollY = y
    }

    // MARK: - 乘车码

    private var alertPresented: Binding<Bool> {
        Binding(get: { transitAlert != nil },
                set: { if !$0 { transitAlert = nil } })
    }

    private func openTransit() {
        if vm.transitChoice == .ask {
            showTransitSheet = true
        } else {
            handleTransit(vm.transitChoice, remember: false)
        }
    }

    private func handleTransit(_ c: TransitCodeChoice, remember: Bool) {
        if remember { vm.setTransitChoice(c) }
        guard c != .ask else { return }
        if !TransitCodeLauncher.attemptOpen(c) {
            transitAlert = "未安装\(c.label)，请安装后重试，或在 App 首页找乘车码"
        }
    }

    // MARK: - 顶部：数据源 / 城市

    private var controls: some View {
        HStack(spacing: 10) {
            sourceMenu
            cityMenu
            Spacer(minLength: 0)
        }
    }

    private var sourceMenu: some View {
        Menu {
            ForEach(BusSource.allCases) { s in
                Button { vm.setSource(s) } label: {
                    HStack {
                        Text(s.name)
                        if s == vm.source { Image(systemName: "checkmark") }
                    }
                }
            }
        } label: {
            menuLabel(vm.source.name, systemImage: "arrow.triangle.2.circlepath")
        }
    }

    private var cityMenu: some View {
        Menu {
            ForEach(Array(vm.cities.enumerated()), id: \.offset) { _, c in
                Button { vm.setCity(c) } label: {
                    HStack {
                        Text(c.name.isEmpty ? "未知城市" : c.name)
                        if c.id == vm.city.id { Image(systemName: "checkmark") }
                    }
                }
            }
        } label: {
            menuLabel(vm.city.name.isEmpty ? "选择城市" : vm.city.name,
                      systemImage: "mappin.and.ellipse")
        }
    }

    private func menuLabel(_ text: String, systemImage: String) -> some View {
        Label(text, systemImage: systemImage)
            .font(F.sub)
            .foregroundStyle(T.textSecondary)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(T.bgSurface)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(T.border, lineWidth: 0.8))
    }

    // MARK: - 搜索入口

    private var searchEntry: some View {
        Button { showSearch = true } label: {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(F.body)
                    .foregroundStyle(T.textTertiary)
                Text("搜索线路 / 站点")
                    .font(F.body)
                    .foregroundStyle(T.textPlaceholder)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(T.border, lineWidth: 0.8)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - 状态卡

    private func errorCard(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .font(F.body)
                .foregroundStyle(T.warn)
            Text(text)
                .font(F.sub)
                .foregroundStyle(T.textSecondary)
            Spacer(minLength: 0)
        }
        .zstCard(padding: 14)
    }

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("正在获取附近站点…")
                .font(F.sub)
                .foregroundStyle(T.textTertiary)
            Spacer(minLength: 0)
        }
        .zstCard(padding: 16)
    }

    private var emptyHint: some View {
        EmptyCard(title: "还没有附近站点",
                  subtitle: "授权定位后会自动显示，也可以下拉刷新，或先在上方搜索线路 / 站点")
    }

    // MARK: - 附近站点

    @ViewBuilder
    private var nearbySection: some View {
        if !vm.stations.isEmpty {
            SectionTitle(text: "附近站点")
            ForEach(Array(vm.stations.enumerated()), id: \.offset) { _, station in
                NavigationLink {
                    StationDetailView(station: station, vm: vm)
                } label: {
                    stationCard(station)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func stationCard(_ s: BusStation) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "mappin.circle.fill")
                    .font(F.body)
                    .foregroundStyle(T.brand)
                Text(s.name)
                    .font(F.bodyBold)
                    .foregroundStyle(T.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if !s.distanceLabel.isEmpty {
                    Text(s.distanceLabel)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
                if s.platforms.count > 1 {
                    Text("\(s.platforms.count) 个站台")
                        .font(F.caption2)
                        .foregroundStyle(T.textTertiary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 6)

            if s.arrivals.isEmpty {
                Text("暂无实时数据")
                    .font(F.sub)
                    .foregroundStyle(T.textTertiary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(s.arrivals.prefix(3).enumerated()), id: \.offset) { _, a in
                        Divider().padding(.leading, 14)
                        ArrivalRow(arrival: a, bigStyle: true)
                            .padding(.horizontal, 14)
                    }
                }
            }
        }
        .zstCard()
    }

    // MARK: - 收藏线路

    @ViewBuilder
    private var favoritesSection: some View {
        if !vm.favorites.isEmpty {
            SectionTitle(text: "收藏线路")
            Card(padding: 0) {
                VStack(spacing: 0) {
                    ForEach(Array(vm.favorites.enumerated()), id: \.offset) { idx, line in
                        if idx > 0 { RowDivider() }
                        favoriteRow(line)
                    }
                }
            }
        }
    }

    private func favoriteRow(_ line: BusLine) -> some View {
        NavigationLink {
            LineDetailView(lineId: line.lineId, vm: vm)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "bus.fill")
                    .font(F.body)
                    .foregroundStyle(T.brand)
                    .frame(width: 32, height: 32)
                    .background(T.brandSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(line.name)
                        .font(F.body)
                        .foregroundStyle(T.textPrimary)
                        .lineLimit(1)
                    Text(line.terminusLabel)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                        .lineLimit(1)
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(T.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
