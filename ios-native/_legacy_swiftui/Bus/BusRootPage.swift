import SwiftUI
import CoreLocation

/// 实时公交首页。
///
/// 这是**首页宫格进入的二级页**，不是一级 tab。
/// 结构（对齐 Android `bus/Views/BusHomeView` + `BusActivity` HOME 模式）：
///   1. 搜索框（线路 / 站点）
///   2. 城市 + 数据源切换行
///   3. 定位状态 / 「重新定位」
///   4. 收藏线路
///   5. 附近站点列表（每站最多 3 条线路 + 展开全部）
struct BusRootPage: View {
    @EnvironmentObject private var app: AppState
    @StateObject private var vm = BusViewModel()

    @State private var searchText = ""
    @State private var searching = false
    @State private var searchHit: BusSearchHit?
    @State private var showCityPicker = false
    @State private var openedLine: BusLine?
    @State private var openedStation: BusStation?

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                searchBar
                citySourceRow
                locationRow

                if let hit = searchHit {
                    searchResultSection(hit)
                } else {
                    if !vm.favorites.isEmpty {
                        favoritesSection
                    }
                    nearbySection
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 8)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
        .background(T.bgPage.ignoresSafeArea())
        .navigationTitle("实时公交")
        .navigationBarTitleDisplayMode(.inline)
        .task { await vm.bootstrap() }
        .sheet(item: $openedLine) { line in
            NavigationStack {
                LineDetailPage(line: line)
                    .environmentObject(app)
            }
        }
        .sheet(item: $openedStation) { st in
            NavigationStack {
                StationDetailPage(station: st)
                    .environmentObject(app)
            }
        }
        .sheet(isPresented: $showCityPicker) {
            CityPickerSheet(vm: vm)
        }
    }

    // MARK: - 搜索

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15))
                .foregroundStyle(T.textTertiary)

            TextField("搜线路或站点，如 22路 / 学院路", text: $searchText)
                .font(F.body)
                .submitLabel(.search)
                .onSubmit { Task { await runSearch() } }

            if searching {
                LoadingRing(size: 14)
            } else if !searchText.isEmpty {
                Button {
                    searchText = ""
                    searchHit = nil
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(T.textTertiary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 46)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.8)
        )
    }

    // MARK: - 城市 / 数据源

    private var citySourceRow: some View {
        HStack(spacing: 8) {
            // 城市
            Button {
                Haptics.light()
                showCityPicker = true
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "mappin.circle.fill")
                    Text(vm.cityName)
                        .font(F.captionBold)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .bold))
                }
                .foregroundStyle(T.brand)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule(style: .continuous).fill(T.brandSoft))
            }
            .buttonStyle(.pressable(0.95))

            // 数据源
            Menu {
                ForEach(BusSource.allCases, id: \.self) { s in
                    Button {
                        Task { await vm.changeSource(s) }
                    } label: {
                        if vm.source == s {
                            Label(s.label, systemImage: "checkmark")
                        } else {
                            Text(s.label)
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(vm.source.label)
                        .font(F.caption)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .bold))
                }
                .foregroundStyle(T.textSecondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Capsule(style: .continuous).fill(T.bgSunken))
            }

            Spacer()

            if let err = vm.error {
                Text(err)
                    .font(F.micro)
                    .foregroundStyle(T.danger)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 2)
    }

    // MARK: - 定位状态

    @ViewBuilder
    private var locationRow: some View {
        if vm.locationDenied {
            HStack(spacing: 10) {
                Image(systemName: "location.slash")
                    .foregroundStyle(T.warn)
                VStack(alignment: .leading, spacing: 2) {
                    Text("定位权限未开启")
                        .font(F.captionBold)
                        .foregroundStyle(T.textPrimary)
                    Text("开启后才能查找附近的公交站")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                }
                Spacer()
                Button("去设置") {
                    // 打开系统设置
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .font(F.captionBold)
                .foregroundStyle(T.brand)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(T.warnSoft)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        } else if vm.locating {
            HStack(spacing: 8) {
                LoadingRing(size: 13)
                Text("正在定位…")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                Spacer()
            }
            .padding(.horizontal, 4)
        }
    }

    // MARK: - 搜索结果

    private func searchResultSection(_ hit: BusSearchHit) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if hit.isEmpty {
                EmptyCard(title: "没有找到", subtitle: "换个关键字试试，比如「22路」或站名")
            } else {
                if !hit.lines.isEmpty {
                    SectionTitle(text: "线路 · \(hit.lines.count)")
                        .padding(.bottom, 6)
                    ForEach(hit.lines) { line in
                        Button {
                            Haptics.light()
                            openedLine = line
                        } label: {
                            busLineRow(line)
                        }
                        .buttonStyle(.plain)
                        if line.id != hit.lines.last?.id {
                            Divider().overlay(T.borderSubtle)
                        }
                    }
                }
                if !hit.stations.isEmpty {
                    SectionTitle(text: "站点 · \(hit.stations.count)")
                        .padding(.top, hit.lines.isEmpty ? 0 : 12)
                        .padding(.bottom, 6)
                    ForEach(hit.stations) { st in
                        Button {
                            Haptics.light()
                            Task { await openStation(st) }
                        } label: {
                            stationRow(st)
                        }
                        .buttonStyle(.plain)
                        if st.id != hit.stations.last?.id {
                            Divider().overlay(T.borderSubtle)
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func busLineRow(_ line: BusLine) -> some View {
        HStack(spacing: 10) {
            Text(line.name)
                .font(F.bodyBold)
                .foregroundStyle(T.brand)
                .frame(minWidth: 54, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                if !line.routeText.isEmpty {
                    Text(line.routeText)
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                        .lineLimit(1)
                }
                if !line.serviceTime.isEmpty {
                    Text(line.serviceTime)
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(T.textTertiary)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    private func stationRow(_ st: BusStation) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "mappin.circle")
                .foregroundStyle(T.brand)
            Text(st.name)
                .font(F.body)
                .foregroundStyle(T.textPrimary)
            Spacer()
            if !st.distanceText.isEmpty {
                Text(st.distanceText)
                    .font(F.micro)
                    .foregroundStyle(T.textTertiary)
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(T.textTertiary)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    // MARK: - 收藏

    private var favoritesSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "收藏线路")
                .padding(.bottom, 6)
            ForEach(vm.favorites) { f in
                Button {
                    Haptics.light()
                    openedLine = f.asLine
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "star.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(T.warn)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.name)
                                .font(F.bodyBold)
                                .foregroundStyle(T.textPrimary)
                            if !f.routeText.isEmpty {
                                Text(f.routeText)
                                    .font(F.micro)
                                    .foregroundStyle(T.textTertiary)
                                    .lineLimit(1)
                            }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(T.textTertiary)
                    }
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if f.id != vm.favorites.last?.id {
                    Divider().overlay(T.borderSubtle)
                }
            }
        }
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    // MARK: - 附近站点

    private var nearbySection: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                SectionTitle(text: "附近站点")
                Spacer()
                Button {
                    Haptics.light()
                    Task { await vm.locateAndLoad() }
                } label: {
                    HStack(spacing: 4) {
                        if vm.locating || vm.loadingNearby {
                            LoadingRing(size: 12)
                        } else {
                            Image(systemName: "location")
                                .font(.system(size: 11, weight: .semibold))
                        }
                        Text(vm.loadingNearby ? "加载中" : "重新定位")
                            .font(F.caption)
                    }
                    .foregroundStyle(T.brand)
                }
                .buttonStyle(.plain)
                .disabled(vm.locating || vm.loadingNearby)
            }
            .padding(.bottom, 8)

            if vm.nearby.isEmpty {
                if vm.loadingNearby {
                    HStack(spacing: 8) {
                        LoadingRing(size: 14)
                        Text("正在查找附近的公交站…")
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 26)
                } else {
                    EmptyCard(title: vm.locationDenied ? "需要定位权限" : "还没有附近站点",
                              subtitle: vm.nearbyError ?? "点右上角「重新定位」查找附近公交站",
                              icon: "location")
                }
            } else {
                ForEach(vm.nearby) { st in
                    nearbyStationCard(st)
                    if st.id != vm.nearby.last?.id {
                        Divider().overlay(T.borderSubtle)
                    }
                }

                if vm.hasMoreNearby {
                    Button {
                        Haptics.light()
                        Task { await vm.loadNearby(reset: false) }
                    } label: {
                        HStack(spacing: 6) {
                            if vm.loadingNearby { LoadingRing(size: 12) }
                            Text(vm.loadingNearby ? "加载中…" : "加载更远的站点")
                                .font(F.caption)
                                .foregroundStyle(T.brand)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func nearbyStationCard(_ st: BusStation) -> some View {
        Button {
            Haptics.light()
            Task { await openStation(st) }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(st.name)
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    Spacer()
                    if !st.distanceText.isEmpty {
                        Text(st.distanceText)
                            .font(F.micro)
                            .foregroundStyle(T.textTertiary)
                    }
                }

                if st.arrivals.isEmpty {
                    Text("暂无经过线路")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                } else {
                    ForEach(st.arrivals.prefix(3)) { a in
                        arrivalRow(a)
                    }
                    if st.arrivals.count > 3 {
                        Text("还有 \(st.arrivals.count - 3) 条线路")
                            .font(F.micro)
                            .foregroundStyle(T.textTertiary)
                            .padding(.top, 2)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 一条线路在某站的到站行
    private func arrivalRow(_ a: BusArrival) -> some View {
        HStack(spacing: 8) {
            Text(a.lineName)
                .font(F.captionBold)
                .foregroundStyle(T.brand)
                .frame(minWidth: 46, alignment: .leading)

            Text(a.directionText)
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
                .lineLimit(1)

            Spacer()

            Text(a.primaryText)
                .font(F.caption)
                .foregroundStyle(arrivalColor(a))
        }
    }

    private func arrivalColor(_ a: BusArrival) -> Color {
        if a.arrived { return T.warn }
        if a.minutes >= 0 { return a.minutes <= 3 ? T.warn : T.go }
        return T.textTertiary
    }

    // MARK: - 动作

    private func runSearch() async {
        let kw = searchText.trimmingCharacters(in: .whitespaces)
        guard !kw.isEmpty else { return }
        searching = true
        defer { searching = false }
        searchHit = await vm.search(kw)
    }

    private func openStation(_ st: BusStation) async {
        await vm.openStation(st)
        if let full = vm.stationDetail {
            openedStation = full
        }
    }
}

// MARK: - 城市选择

struct CityPickerSheet: View {
    @ObservedObject var vm: BusViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var cities: [BusCity] = []
    @State private var loading = true
    @State private var filter = ""

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    VStack(spacing: 10) {
                        LoadingRing(size: 22)
                        Text("正在加载城市列表…")
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(filtered, id: \.id) { c in
                            Button {
                                Task {
                                    await vm.changeCity(c)
                                    dismiss()
                                }
                            } label: {
                                HStack {
                                    Text(c.name)
                                        .foregroundStyle(T.textPrimary)
                                    Spacer()
                                    if c.id == vm.cityId {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(T.brand)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("选择城市")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $filter, prompt: "搜城市")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .task {
                cities = await vm.availableCities()
                loading = false
            }
        }
    }

    private var filtered: [BusCity] {
        guard !filter.isEmpty else { return cities }
        return cities.filter { $0.name.contains(filter) }
    }
}