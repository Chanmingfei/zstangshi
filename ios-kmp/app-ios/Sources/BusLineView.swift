import SwiftUI
import Shared

/// 线路详情 —— 按安卓 `BusActivity` 线详页复刻：
/// 主卡（ETA / 全部车辆两态）→ 线路图 → 首站发车 → 全部站点 → 乘车码。
@MainActor
final class BusLineModel: ObservableObject {
    @Published var detail: BusLineDetail?
    @Published var pred: BusPrediction?
    @Published var timetable: BusTimetable?
    @Published var status = "加载中…"
    @Published var loading = false
    @Published var showVehicles = false
    @Published var waitOrder: Int = 0
    @Published var favorite = false

    let lineId: String
    let lineName: String
    let cityId: String
    private var reverseId = ""
    private var uid = ""

    /// 掌上公交的线路 id 形如 `mg|唐山市|21路|1`，它没有车来了那种反向 lineId，靠方向位切换。
    private let isMg: Bool
    private let mgCity: String
    private let mgLine: String
    private var mgDir: Int

    init(lineId: String, lineName: String) {
        self.lineId = lineId
        self.lineName = lineName
        self.cityId = BusPrefs.cityId
        self.favorite = BusPrefs.isFavorite(lineId)
        self.uid = lineId
        let seg = Mygolbs.shared.parseLineId(lineId: lineId)
        self.isMg = seg != nil && seg!.count == 3
        self.mgCity = (seg?.count == 3) ? seg![0] : BusPrefs.cityId
        self.mgLine = (seg?.count == 3) ? seg![1] : lineName
        self.mgDir = (seg?.count == 3 && seg![2] == "2") ? 1 : 0
    }

    func loadAll() async {
        loading = true
        defer { loading = false }
        await loadDetail()
        if waitOrder == 0, let stops = detail?.stops, !stops.isEmpty {
            waitOrder = nearestOrder(stops)
        }
        await loadPrediction()
        await loadTimetable()
    }

    private func loadDetail() async {
        if isMg {
            do {
                let body = try await BusFetch.postForm(
                    Mygolbs.shared.API,
                    form: MygolbsRouteKt.mygolbsRouteForm(
                        cityName: mgCity, lineName: mgLine, dir: Int32(mgDir)),
                    headers: ["User-Agent": Mygolbs.shared.UA,
                              "Origin": Mygolbs.shared.ORIGIN,
                              "Referer": Mygolbs.shared.REFERER])
                let d = MygolbsRouteKt.parseMygolbsRoute(
                    body: body, cityName: mgCity, lineName: mgLine, dir: Int32(mgDir))
                detail = d
                status = "\(d.stops.count) 站"
            } catch {
                status = "失败：\(error.localizedDescription)"
            }
            return
        }
        let url = ChelaileApi.shared.lineDetailUrl(cityId: cityId, lineId: uid, lat: 39.63, lng: 118.18)
        do {
            let body = try await BusFetch.text(url)
            let d = ChelaileParseKt.parseLineDetail(body: body, lineId: uid)
            detail = d
            if reverseId.isEmpty { reverseId = d.reverseId }
            status = "\(d.stops.count) 站 · \(d.vehicles.count) 辆在途"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }

    private func loadPrediction() async {
        guard waitOrder > 0, let d = detail else { return }
        if isMg {
            guard let body = try? await BusFetch.postForm(
                Mygolbs.shared.API,
                form: MygolbsRouteKt.mygolbsRealtimeForm(
                    cityName: mgCity, lineName: mgLine, dir: Int32(mgDir), order: Int32(waitOrder)),
                headers: ["User-Agent": Mygolbs.shared.UA,
                          "Origin": Mygolbs.shared.ORIGIN,
                          "Referer": Mygolbs.shared.REFERER]) else { return }
            pred = MygolbsRouteKt.parseMygolbsRealtime(body: body)
            return
        }
        let stop = d.stops.first { Int($0.order) == waitOrder }
        let url = ChelaileApi.shared.busDetailUrl(
            cityId: cityId, lineId: uid, order: Int32(waitOrder),
            stationId: stop?.id ?? "", lat: 39.63, lng: 118.18)
        guard let body = try? await BusFetch.text(url) else { return }
        pred = BusDetailKt.parseBusDetail(body: body)
    }

    private func loadTimetable() async {
        if isMg {
            guard let body = try? await BusFetch.postForm(
                Mygolbs.shared.API,
                form: MygolbsRouteKt.mygolbsTimetableForm(
                    cityName: mgCity, lineName: mgLine, dir: Int32(mgDir)),
                headers: ["User-Agent": Mygolbs.shared.UA,
                          "Origin": Mygolbs.shared.ORIGIN,
                          "Referer": Mygolbs.shared.REFERER]) else { return }
            timetable = MygolbsRouteKt.parseMygolbsTimetable(body: body, lineName: mgLine)
            return
        }
        let url = ChelaileApi.shared.timetableUrl(
            cityId: cityId, lineId: uid, lineNo: detail?.line.lineNo ?? "", direction: 0)
        guard let body = try? await BusFetch.text(url) else { return }
        timetable = ChelaileParseKt.parseTimetable(body: body)
    }

    func switchDirection() async {
        if isMg {
            // 掌上公交没有反向 lineId：方向位取反后重新拉站表
            mgDir = mgDir == 0 ? 1 : 0
            waitOrder = 0
            detail = nil
            pred = nil
            await loadAll()
            return
        }
        guard !reverseId.isEmpty else {
            status = "这条线没有反向数据"
            return
        }
        let tmp = uid
        uid = reverseId
        reverseId = tmp
        waitOrder = 0
        detail = nil
        pred = nil
        await loadAll()
    }

    func selectStop(_ order: Int) async {
        guard order != waitOrder else { return }
        waitOrder = order
        await loadPrediction()
    }

    func toggleFavorite() {
        BusPrefs.toggleFavorite(lineId: lineId, name: lineName)
        favorite = BusPrefs.isFavorite(lineId)
    }

    /// 离用户最近的站（安卓「离我最近」）：没有定位就取第 1 站。
    private func nearestOrder(_ stops: [BusStop]) -> Int {
        Int(stops.first?.order ?? 1)
    }

    // MARK: - 展示用的派生值

    var waitStop: BusStop? {
        detail?.stops.first { Int($0.order) == waitOrder }
    }

    var etaMinutes: Int {
        guard let p = pred, p.secs >= 0 else { return -1 }
        let secs = Int(p.secs)
        if secs == 0 { return 0 }
        if secs < 60 { return 0 }
        return (secs + 59) / 60
    }

    var etaArrived: Bool { pred?.arrived ?? false }

    var bigText: String {
        guard let d = detail else { return "—" }
        if etaArrived { return "到站了" }
        if etaMinutes > 0 { return "\(etaMinutes)" }
        if etaMinutes == 0 { return "即将到站" }
        if !d.status.isEmpty { return d.status }
        return "暂无实时"
    }

    var bigTextIsNumber: Bool { !etaArrived && etaMinutes > 0 }

    var subtitle: String {
        guard let p = pred, let d = detail else { return "" }
        var bits: [String] = []
        if let v = d.vehicles.first(where: { $0.id == p.busId }) {
            let gap = waitOrder - Int(v.order)
            if gap > 0 { bits.append("还有 \(gap) 站") }
            if !v.plate.isEmpty { bits.append(v.plate) }
            if v.speedKmh > 0 { bits.append("\(Int(v.speedKmh)) km/h") }
        }
        if p.distanceMeters > 0 {
            bits.append(String(format: "距本站 %.1f 公里", Double(p.distanceMeters) / 1000.0))
        }
        if p.secs > 0 {
            let t = Date().addingTimeInterval(Double(p.secs))
            let f = DateFormatter()
            f.dateFormat = "HH:mm"
            bits.append("\(f.string(from: t)) 到")
        }
        return bits.joined(separator: " · ")
    }

    var nextBusText: String {
        guard let p = pred else { return "" }
        if p.nextSecs < 0 { return p.secs >= 0 ? "暂无下一辆" : "" }
        let ns = Int(p.nextSecs)
        let mins = ns < 60 ? 0 : (ns + 59) / 60
        return mins > 0 ? "下一辆 \(mins) 分钟" : "下一辆 即将到站"
    }

    var firstDepartText: String {
        guard let t = timetable else { return "" }
        let times = t.todayTimes().isEmpty ? t.historyTimes() : t.todayTimes()
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        let now = f.string(from: Date())
        if let next = times.first(where: { $0 >= now }) { return next }
        return times.last ?? ""
    }

    var firstDepartHint: String {
        guard let t = timetable else { return "" }
        let times = t.todayTimes().isEmpty ? t.historyTimes() : t.todayTimes()
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        let now = f.string(from: Date())
        guard let next = times.first(where: { $0 >= now }) else {
            return times.isEmpty ? "暂无班次" : "今日班次已结束"
        }
        let parts = next.split(separator: ":")
        if parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]) {
            let cal = Calendar.current
            let start = cal.date(bySettingHour: h, minute: m, second: 0, of: Date()) ?? Date()
            let mins = Int(start.timeIntervalSinceNow / 60)
            if mins <= 0 { return "即将发车" }
            return "还有 \(mins) 分钟发车"
        }
        return ""
    }

    var departSourceLabel: String {
        guard let t = timetable else { return "" }
        if !t.today.isEmpty { return "今日预测" }
        return t.historyTitle
    }
}

struct BusLineView: View {
    let hit: BusLineHit
    @StateObject private var model: BusLineModel

    init(hit: BusLineHit) {
        self.hit = hit
        _model = StateObject(wrappedValue: BusLineModel(lineId: hit.lineId, lineName: hit.name))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                heroCard
                diagramSection
                firstDepartCard
                stopsSection
                Text(model.status).font(.caption2).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .navigationTitle(hit.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    model.toggleFavorite()
                } label: {
                    Image(systemName: model.favorite ? "star.fill" : "star")
                }
            }
        }
        .task { await model.loadAll() }
    }

    // MARK: 主卡

    private var heroCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.showVehicles {
                vehiclesHeader
                ForEach(vehicleRows, id: \.id) { v in
                    vehicleRow(v)
                }
            } else {
                etaHeader
                etaBlock
            }

            Divider().padding(.vertical, 10)

            HStack {
                Text("\(model.detail?.line.startName ?? "") → \(model.detail?.line.endName ?? "")")
                    .font(.subheadline)
                Spacer()
                Button {
                    Task { await model.switchDirection() }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.left.arrow.right").font(.caption)
                        Text("换向").font(.footnote)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .overlay(Capsule().stroke(Color(red: 0.0, green: 0.48, blue: 1.0), lineWidth: 1))
                    .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                }
                .buttonStyle(.plain)
            }

            if let l = model.detail?.line {
                Text(metaLine(l))
                    .font(.caption2).foregroundColor(.secondary)
                    .padding(.top, 6)
            }
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .background(Color(UIColor.systemBackground))
    }

    private func metaLine(_ l: BusLine) -> String {
        var bits: [String] = []
        if !l.firstTime.isEmpty || !l.lastTime.isEmpty {
            bits.append("\(l.firstTime) - \(l.lastTime)")
        }
        if !l.price.isEmpty { bits.append("\(l.price)元") }
        if l.stationsNum > 0 { bits.append("全程 \(l.stationsNum) 站") }
        return bits.joined(separator: " · ")
    }

    private var etaHeader: some View {
        HStack {
            Text(model.waitStop?.name ?? "")
                .font(.footnote)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.12))
                .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            Spacer()
            Text("第 \(model.waitOrder) 站").font(.caption).foregroundColor(.secondary)
        }
    }

    private var etaBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.bigTextIsNumber {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(model.bigText).font(.system(size: 38, weight: .semibold))
                    Text("分钟").font(.title3)
                }
            } else {
                Text(model.bigText).font(.system(size: 30, weight: .semibold))
            }

            if !model.subtitle.isEmpty {
                Text(model.subtitle).font(.caption).foregroundColor(.secondary)
                    .lineLimit(1)
            }

            HStack {
                Text(model.nextBusText).font(.footnote)
                Spacer()
                if !vehicleRows.isEmpty {
                    Button {
                        model.showVehicles = true
                    } label: {
                        Text("全线 \(vehicleRows.count) 辆车在途 · 查看")
                            .font(.footnote)
                            .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 2)
        }
        .padding(.top, 10)
    }

    private var vehiclesHeader: some View {
        HStack {
            Button {
                model.showVehicles = false
            } label: {
                Image(systemName: "arrow.left")
                    .padding(6)
                    .background(Color(UIColor.tertiarySystemFill))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            Text("到「\(model.waitStop?.name ?? "")」的全部车辆").font(.subheadline).bold()
            Spacer()
            Text("共 \(vehicleRows.count) 辆").font(.caption).foregroundColor(.secondary)
        }
        .padding(.bottom, 10)
    }

    private var vehicleRows: [BusVehicle] {
        let fromDetail = model.detail?.vehicles ?? []
        return fromDetail.isEmpty ? (model.pred?.vehicles ?? []) : fromDetail
    }

    private func vehicleRow(_ v: BusVehicle) -> some View {
        let eta = model.pred?.etaOf(busId: v.id)
        let secs = Int(eta?.seconds ?? -1)
        let mins = secs < 0 ? -1 : (secs < 60 ? 0 : (secs + 59) / 60)
        // 到达时刻在 ViewBuilder 外面算好：ViewBuilder 里连续 let 会被判成 ()，编不过
        let arriveText: String = {
            guard secs > 0 else { return "" }
            let f = DateFormatter(); f.dateFormat = "HH:mm"
            return f.string(from: Date().addingTimeInterval(Double(secs))) + " 到"
        }()
        return HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.12))
                .frame(width: 34, height: 34)
                .overlay(Image(systemName: "bus.fill")
                    .font(.system(size: 14))
                    .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0)))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(v.plate.isEmpty ? v.id : v.plate).font(.subheadline).bold()
                    if v.speedKmh > 0 {
                        Text("\(Int(v.speedKmh)) km/h").font(.caption2).foregroundColor(.secondary)
                    }
                }
                Text("第 \(v.order) 站" + (v.nextStopName.isEmpty ? "" : " · 正在接近\(v.nextStopName)"))
                    .font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(mins < 0 ? "—" : (mins == 0 ? "即将" : "约 \(mins) 分钟"))
                    .font(.footnote)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color(UIColor.tertiarySystemFill))
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                if !arriveText.isEmpty {
                    Text(arriveText).font(.caption2).foregroundColor(.secondary)
                }
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: 线路图

    private var diagramSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("线路图 · 左右滑动查看").font(.footnote).foregroundColor(.secondary)
            if let stops = model.detail?.stops, !stops.isEmpty {
                LineDiagram(stops: stops, waitOrder: model.waitOrder,
                            vehicles: model.detail?.vehicles ?? []) { order in
                    Task { await model.selectStop(order) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: 首站发车

    private var firstDepartCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("首站发车").font(.footnote).bold()
                Text(model.detail?.line.startName ?? "").font(.footnote).foregroundColor(.secondary)
                Spacer()
                NavigationLink("完整时刻表 ›") { BusTimetableView(hit: hit) }
                    .font(.footnote)
            }
            if !model.firstDepartText.isEmpty {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(model.firstDepartText).font(.system(size: 30, weight: .semibold))
                        .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                    if !model.firstDepartHint.isEmpty {
                        Text("\(model.firstDepartHint) · \(model.departSourceLabel)")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
            }
            if let t = model.timetable {
                let bits = [
                    "首班 \(t.firstDep)", "末班 \(t.lastDep)",
                    t.interval > 0 ? "约 \(t.interval) 分钟一班" : ""
                ].filter { !$0.isEmpty }
                Text(bits.joined(separator: " · "))
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: 站表

    private var stopsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("全部站点 · 点站名切换候车站")
                .font(.footnote).foregroundColor(.secondary)
                .padding(.bottom, 8)

            if let stops = model.detail?.stops {
                VStack(spacing: 0) {
                    ForEach(stops, id: \.id) { s in
                        Button {
                            Task { await model.selectStop(Int(s.order)) }
                        } label: {
                            HStack(spacing: 10) {
                                Text("\(s.order)")
                                    .font(.caption2)
                                    .foregroundColor(Int(s.order) == model.waitOrder
                                                     ? Color(red: 0.0, green: 0.48, blue: 1.0) : .secondary)
                                    .frame(width: 26, alignment: .trailing)
                                Text(s.name)
                                    .font(.subheadline)
                                    .foregroundColor(Int(s.order) == model.waitOrder
                                                     ? Color(red: 0.0, green: 0.48, blue: 1.0) : .primary)
                                Spacer()
                                if Int(s.order) == model.waitOrder {
                                    Text("候车")
                                        .font(.caption2)
                                        .padding(.horizontal, 8).padding(.vertical, 2)
                                        .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.12))
                                        .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                                        .clipShape(Capsule())
                                }
                            }
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if s.id != stops.last?.id { Divider() }
                    }
                }
                .padding(.horizontal, 14)
                .background(Color(UIColor.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
    }
}

/// 横向站序图：圆点连线 + 站名竖排 + 候车站高亮。
struct LineDiagram: View {
    let stops: [BusStop]
    let waitOrder: Int
    let vehicles: [BusVehicle]
    let onSelect: (Int) -> Void

    /// 只渲染候车站前后各 3 站，避免几十站挤在一屏
    private var visible: [(index: Int, stop: BusStop)] {
        let idx = stops.firstIndex { Int($0.order) == waitOrder } ?? 0
        let lo = max(0, idx - 3)
        let hi = min(stops.count - 1, idx + 3)
        return (lo...hi).map { (i: $0, stop: stops[$0]) }
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 0) {
                    ForEach(visible, id: \.stop.id) { item in
                        VStack(spacing: 6) {
                            Text("\(item.stop.order)")
                                .font(.system(size: 9)).foregroundColor(.secondary)
                            ZStack {
                                Rectangle()
                                    .fill(Color(UIColor.tertiarySystemFill))
                                    .frame(height: 3)
                                Circle()
                                    .fill(Int(item.stop.order) == waitOrder
                                          ? Color(red: 0.0, green: 0.48, blue: 1.0)
                                          : Color(UIColor.systemGray4))
                                    .frame(width: Int(item.stop.order) == waitOrder ? 20 : 8,
                                           height: Int(item.stop.order) == waitOrder ? 20 : 8)
                                    .overlay(
                                        Group {
                                            if Int(item.stop.order) == waitOrder {
                                                Image(systemName: "chevron.right")
                                                    .font(.system(size: 9, weight: .bold))
                                                    .foregroundColor(.white)
                                            }
                                        }
                                    )
                            }
                            .frame(width: 56, height: 22)

                            Text(item.stop.name)
                                .font(.system(size: 11))
                                .foregroundColor(Int(item.stop.order) == waitOrder
                                                 ? Color(red: 0.0, green: 0.48, blue: 1.0) : .primary)
                                .frame(width: 44)
                                .multilineTextAlignment(.center)
                                .lineLimit(4)

                            if Int(item.stop.order) == waitOrder {
                                Text("候车").font(.system(size: 9))
                                    .padding(.horizontal, 5).padding(.vertical, 1)
                                    .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.12))
                                    .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                                    .clipShape(Capsule())
                                Text("离我最近").font(.system(size: 9)).foregroundColor(.orange)
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { onSelect(Int(item.stop.order)) }
                        .id(Int(item.stop.order))
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
            }
            .background(Color(UIColor.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .onAppear {
                proxy.scrollTo(waitOrder, anchor: .center)
            }
        }
    }
}
