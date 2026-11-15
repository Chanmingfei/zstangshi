import SwiftUI

// MARK: - 线路详情（三态）
//
// 站点到站 / 全部车辆 / 单车跟随 三态由 vm.panel 驱动，都在同一根 NavigationStack 里，
// 不自建页面带。返回层级由 vm.backOneLevel() 逐层退，退到顶交给系统 dismiss。
// 进入页面用 openLine 取数并开始 20s 轮询，离开即停。
struct LineDetailView: View {
    let lineId: String
    @ObservedObject var vm: BusViewModel
    var targetOrder: Int = 0

    @Environment(\.dismiss) private var dismiss
    @State private var fabExpanded = true
    @State private var lastScrollY: CGFloat = 0
    @State private var showTransitSheet = false
    @State private var transitAlert: String?

    private let pageSpace = "busLineScroll"

    var body: some View {
        ScrollView {
            ScrollOffsetMarker(space: pageSpace, key: BusLineScrollOffsetKey.self)
            LazyVStack(spacing: 12) {
                if let err = vm.errorText { errorCard(err) }
                if let d = vm.detail {
                    lineHeader(d)
                    overview(d)
                    switch vm.panel {
                    case .station: stationPanel(d)
                    case .busList: busListPanel(d)
                    case .follow:  followPanel(d)
                    }
                    timetableCard
                } else if vm.panel != .station {
                    EmptyCard(title: "线路信息加载中", subtitle: "请稍候，正在获取实时数据")
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 12)
            .padding(.bottom, 96)
            // 结构键不变时（例行补预测）不重建内容，滚动位置不跳；换线/换向才换身份
            .id(vm.structureKey)
        }
        .coordinateSpace(name: pageSpace)
        .background(T.bgPage)
        .refreshable { await vm.refresh() }
        .onPreferenceChange(BusLineScrollOffsetKey.self) { minY in updateFab(minY) }
        .overlay(alignment: .bottomTrailing) {
            TransitCodeFab(expanded: $fabExpanded, onOpen: openTransit)
                .padding(.trailing, 20)
                .padding(.bottom, 20)
        }
        .sheet(isPresented: $showTransitSheet) {
            TransitCodeSheet(choice: vm.transitChoice, onPick: handleTransit)
        }
        .alert("无法打开乘车码", isPresented: alertPresented) {
            Button("好", role: .cancel) { transitAlert = nil }
        } message: {
            Text(transitAlert ?? "")
        }
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { goBack() } label: {
                    Label("返回", systemImage: "chevron.left")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                favoriteButton
            }
        }
        .task {
            await vm.openLine(lineId: lineId, targetOrder: targetOrder)
            vm.startPolling()
        }
        .onDisappear { vm.stopPolling() }
    }

    // MARK: - 返回层级

    /// follow → busList（留在页面）；busList → station → 交还系统返回。
    private func goBack() {
        let handled = vm.backOneLevel()
        if !handled || vm.panel == .station { dismiss() }
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
            transitAlert = "未安装\(c.label)，无法打开乘车码"
        }
    }

    // MARK: - 收藏

    @ViewBuilder
    private var favoriteButton: some View {
        if let d = vm.detail {
            Button { vm.toggleFavorite(d.line) } label: {
                Image(systemName: vm.isFavorite(d.line.lineId) ? "star.fill" : "star")
                    .font(F.body)
                    .foregroundStyle(T.warn)
            }
        }
    }

    // MARK: - 状态卡

    private func errorCard(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .font(F.body)
                .foregroundStyle(T.warn)
            Text(text).font(F.sub).foregroundStyle(T.textSecondary)
            Spacer(minLength: 0)
        }
        .zstCard(padding: 14)
    }

    // MARK: - 线路信息头

    private func lineHeader(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(d.line.name)
                    .font(F.title3)
                    .foregroundStyle(T.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if d.reversible {
                    Button { Task { await vm.switchDirection() } } label: {
                        Label("换向", systemImage: "arrow.left.arrow.right")
                            .font(F.sub.weight(.semibold))
                            .foregroundStyle(T.brand)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(T.brandSoft)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(T.brand.opacity(0.45), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            Text(d.line.terminusLabel)
                .font(F.sub)
                .foregroundStyle(T.textSecondary)
                .lineLimit(1)
            HStack(spacing: 8) {
                if !d.line.firstTime.isEmpty || !d.line.lastTime.isEmpty {
                    Text(operationTime(d.line))
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
                if !d.line.price.isEmpty {
                    Text(d.line.price)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
                let total = d.line.stationsNum > 0 ? d.line.stationsNum : d.stops.count
                if total > 0 {
                    Text("全程 \(total) 站")
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .zstCard()
    }

    private func operationTime(_ l: BusLine) -> String {
        if !l.firstTime.isEmpty && !l.lastTime.isEmpty { return "\(l.firstTime) - \(l.lastTime)" }
        if !l.firstTime.isEmpty { return "\(l.firstTime) 起" }
        return "至 \(l.lastTime)"
    }

    // MARK: - 顶部三列概览

    /// 注意第三列是「到候车站预测」，不是「到终点预测」；距终点那列才是终点口径。
    private func overview(_ d: BusLineDetail) -> some View {
        let v = focusVehicle(d)
        let toTerminus = v.map { max(0, d.stops.count - $0.order) } ?? 0
        let speed: String = {
            guard let veh = v, veh.speedKmh > 0 else { return "—" }
            return "\(Int(veh.speedKmh)) km/h"
        }()
        let eta: String = {
            guard let veh = v, veh.etaSeconds > 0 else { return "无预测" }
            return BusText.etaText(veh.etaSeconds, arrived: false)
        }()
        return HStack(spacing: 0) {
            overviewColumn("距终点", "\(toTerminus) 站")
            overviewColumn("时速", speed)
            overviewColumn("到候车站预测", eta)
        }
        .padding(.vertical, 12)
        .background(T.bgSunken)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func overviewColumn(_ label: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(label)
                .font(F.caption2)
                .foregroundStyle(T.textTertiary)
            Text(value)
                .font(F.sub)
                .fontWeight(.semibold)
                .foregroundStyle(T.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
    }

    /// 单车跟随认所跟车辆，其余认聚合预测那辆（大字/小字同源）。
    private func focusVehicle(_ d: BusLineDetail) -> BusVehicle? {
        if vm.panel == .follow { return vm.followedVehicle() }
        return vm.displayVehicle()
    }

    // MARK: - 状态一：站点到站

    private func stationPanel(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(d.waitingStopName.isEmpty ? "选择候车站" : d.waitingStopName)
                        .font(F.bodyBold)
                        .foregroundStyle(T.brand)
                    Spacer(minLength: 4)
                    if d.waitingOrder > 0 {
                        Text("第 \(d.waitingOrder) 站")
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                }
                Text(arrivalHeadline(d))
                    .font(F.title)
                    .foregroundStyle(headlineColor(d))
                if !d.etaDetail.isEmpty {
                    Text(d.etaDetail)
                        .font(F.sub)
                        .foregroundStyle(T.textSecondary)
                }
                if let next = vm.followingVehicle() {
                    Text("下一辆 " + BusText.etaText(next.etaSeconds, arrived: false))
                        .font(F.sub)
                        .foregroundStyle(T.textSecondary)
                }
            }
            .padding(14)
            .zstCard()

            routeRail(d)
            flowSection(d, highlight: nil)

            if !d.vehicles.isEmpty {
                Button {
                    Task { await vm.openLine(lineId: lineId, targetOrder: targetOrder) }
                } label: {
                    Text("查看全部 \(d.vehicles.count) 辆车")
                        .font(F.bodyBold)
                        .foregroundStyle(T.brand)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(T.brandSoft)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func arrivalHeadline(_ d: BusLineDetail) -> String {
        if d.etaArrived { return "到站了" }
        if d.etaMinutes == 0 { return "即将到站" }
        if d.etaMinutes > 0 { return "\(d.etaMinutes) 分钟" }
        if d.etaStops > 0 { return "\(d.etaStops) 站" }
        if !d.estArrival.isEmpty { return "下一班 \(d.estArrival)" }
        return d.status.isEmpty ? "暂无实时" : d.status
    }

    private func headlineColor(_ d: BusLineDetail) -> Color {
        if d.etaArrived || d.etaMinutes == 0 || (d.etaMinutes > 0 && d.etaMinutes <= 3) {
            return T.go
        }
        if d.etaMinutes < 0 && d.etaStops < 0 { return T.textSecondary }
        return T.textPrimary
    }

    // MARK: - 状态二：全部车辆

    private func busListPanel(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "全部车辆 · 共 \(d.vehicles.count) 辆")
            if d.vehicles.isEmpty {
                EmptyCard(title: "全线暂无在途车辆", subtitle: "上游暂时没有返回车辆位置")
            } else {
                ForEach(Array(d.vehicles.enumerated()), id: \.offset) { _, v in
                    Button { vm.openFollow(v.id) } label: {
                        vehicleRow(v)
                    }
                    .buttonStyle(.plain)
                }
            }
            routeRail(d)
            flowSection(d, highlight: nil)
        }
    }

    private func vehicleRow(_ v: BusVehicle) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "bus.fill")
                .font(F.body)
                .foregroundStyle(T.brand)
                .frame(width: 36, height: 36)
                .background(T.brandSoft)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(v.plate.isEmpty ? "在途车辆" : v.plate)
                    .font(F.bodyBold)
                    .foregroundStyle(T.textPrimary)
                    .lineLimit(1)
                Text(vehicleStatus(v))
                    .font(F.caption)
                    .foregroundStyle(T.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            Text(BusText.etaText(v.etaSeconds, arrived: v.isArrived))
                .font(F.sub)
                .foregroundStyle(T.brand)
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(T.textTertiary)
        }
        .zstCard(padding: 14)
    }

    private func vehicleStatus(_ v: BusVehicle) -> String {
        var bits: [String] = []
        let dist = BusText.distanceText(v.distanceMeters)
        if !dist.isEmpty { bits.append("距候车站 " + dist) }
        if v.speedKmh > 0 { bits.append("\(Int(v.speedKmh)) km/h") }
        if !v.nextStopName.isEmpty { bits.append(v.nextStopName) }
        return bits.isEmpty ? "暂无实时" : bits.joined(separator: " · ")
    }

    // MARK: - 状态三：单车跟随

    private func followPanel(_ d: BusLineDetail) -> some View {
        let v = vm.followedVehicle()
        return VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    ZStack {
                        PulseHalo(color: T.go.opacity(0.25), size: 22)
                        Circle().fill(T.go).frame(width: 10, height: 10)
                    }
                    Text("单车跟随 · " + followedPlate(v))
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                        .lineLimit(1)
                }
                if !d.line.endName.isEmpty {
                    Text("开往 \(d.line.endName)")
                        .font(F.sub)
                        .foregroundStyle(T.textSecondary)
                }
                if let veh = v {
                    HStack(spacing: 12) {
                        let dist = BusText.distanceText(veh.distanceMeters)
                        if !dist.isEmpty {
                            Label("距候车站 \(dist)", systemImage: "location")
                                .font(F.caption)
                                .foregroundStyle(T.textSecondary)
                        }
                        Label(BusText.etaText(veh.etaSeconds, arrived: veh.isArrived),
                              systemImage: "clock")
                            .font(F.caption)
                            .foregroundStyle(T.brand)
                    }
                }
            }
            .padding(14)
            .zstCard()

            routeRail(d)
            flowSection(d, highlight: v?.order)
        }
    }

    private func followedPlate(_ v: BusVehicle?) -> String {
        guard let v else { return "—" }
        return v.plate.isEmpty ? (v.id.isEmpty ? "—" : v.id) : v.plate
    }

    // MARK: - 候车站横排 / 行程流

    private func routeRail(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("候车站 · 点击站线切换")
                .font(F.sectionTitle)
                .foregroundStyle(T.textTertiary)
            RouteRail(stops: d.stops, selectedId: vm.selectedStopId) { stop in
                Task { await vm.switchWaitingStop(stop) }
            }
        }
    }

    private func flowSection(_ d: BusLineDetail, highlight: Int?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("全线途经站行程流")
                .font(F.sectionTitle)
                .foregroundStyle(T.textTertiary)
            FlowRail(detail: d, scrollY: $vm.flowScrollY, highlightedOrder: highlight)
                .zstCard(padding: 8)
        }
    }

    // MARK: - 首站发车时刻表

    @ViewBuilder
    private var timetableCard: some View {
        if let t = vm.timetable, !t.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Text("首站发车")
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    if !t.startName.isEmpty {
                        Text(t.startName)
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                    Spacer(minLength: 0)
                }
                Text(t.nextDep.isEmpty ? "今日已收班" : t.nextDep)
                    .font(F.numeric)
                    .foregroundStyle(t.nextDep.isEmpty ? T.textSecondary : T.brand)
                if !t.nextFrom.isEmpty {
                    Text(t.nextFrom).font(F.caption).foregroundStyle(T.textTertiary)
                }
                if !t.depTitle.isEmpty {
                    Text(t.depTitle).font(F.caption).foregroundStyle(T.textTertiary)
                }
                if !t.tip.isEmpty {
                    Text(t.tip).font(F.caption2).foregroundStyle(T.textTertiary)
                }
                if !t.historyTitle.isEmpty {
                    Text(t.historyTitle).font(F.sectionTitle).foregroundStyle(T.textTertiary)
                }
                ForEach(t.today) { seg in timetableSegment(seg) }
                ForEach(t.history) { seg in timetableSegment(seg) }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .zstCard()
        }
    }

    private func timetableSegment(_ seg: BusTtSegment) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(seg.title).font(F.sub).foregroundStyle(T.textSecondary)
            Text(seg.times.joined(separator: "  "))
                .font(F.caption)
                .foregroundStyle(T.textPrimary)
        }
    }
}
