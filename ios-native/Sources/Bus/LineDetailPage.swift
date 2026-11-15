import SwiftUI

/// 线路详情页。
///
/// 对齐 Android `bus/Views/LineDetailView` + `BusActivity` LINE 模式。
/// 三种视图：
///   1. **到站**：候车站的到站预测（最快那辆大字 + 后续车辆）
///   2. **车辆**：线路上的所有车（含距本站距离）
///   3. **跟随**：盯住某一辆车，显示它的位置与到候车站预测
///
/// 下方是完整途经站列表，候车站高亮，点击可换站。
struct LineDetailPage: View {
    @Environment(\.dismiss) private var dismiss
    let line: BusLine

    @StateObject private var vm = BusViewModel()
    @State private var mode: Mode = .arrivals
    @State private var favorite = false
    @State private var showStopPicker = false
    @State private var showTimetable = false
    @State private var timetable: [String] = []
    @State private var followedBusId: String?

    enum Mode: String, CaseIterable {
        case arrivals = "到站"
        case vehicles = "车辆"
        case follow = "跟随"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                headerCard

                if let d = vm.lineDetail {
                    modePicker
                    content(d)
                    stopsCard(d)
                } else if vm.loadingDetail || vm.detailError == nil {
                    StateBlock(message: "正在获取线路信息…", loading: true)
                } else {
                    StateBlock(message: vm.detailError ?? "线路信息加载失败",
                               retry: { Task { await vm.refreshLine() } })
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 8)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
        .background(T.bgPage.ignoresSafeArea())
        .navigationTitle(line.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    favorite = vm.toggleFavorite(displayLine)
                } label: {
                    Image(systemName: favorite ? "star.fill" : "star")
                        .foregroundStyle(favorite ? T.warn : T.textSecondary)
                }
                .accessibilityLabel(favorite ? "取消收藏" : "收藏线路")
            }
        }
        .task {
            await vm.openLine(line)
            favorite = vm.isFavorite(displayLine)
        }
        .onDisappear { vm.closeLine() }
        .sheet(isPresented: $showStopPicker) {
            if let d = vm.lineDetail {
                StopPickerSheet(stops: d.stops, targetOrder: d.targetOrder) { order in
                    Task { await vm.setTargetOrder(order) }
                }
            }
        }
        .sheet(isPresented: $showTimetable) {
            TimetableSheet(lineName: displayLine.name, times: timetable)
        }
    }

    /// 详情拿到后以它为准，否则用传入的
    private var displayLine: BusLine {
        if let d = vm.lineDetail, !d.line.lineId.isEmpty {
            var l = d.line
            if l.cityId.isEmpty { l.cityId = line.cityId }
            return l
        }
        return line
    }

    // MARK: - 顶部信息

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(displayLine.name)
                    .font(.system(size: F.scaled(22), weight: .bold))
                    .foregroundStyle(T.textPrimary)
                Spacer()
                Button {
                    Haptics.light()
                    Task {
                        timetable = await vm.loadTimetable()
                        showTimetable = true
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "clock")
                        Text("发车表")
                    }
                    .font(F.micro)
                    .foregroundStyle(T.brand)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(T.brandSoft))
                }
                .buttonStyle(.plain)
            }

            if !displayLine.routeText.isEmpty {
                Text(displayLine.routeText)
                    .font(F.caption)
                    .foregroundStyle(T.textSecondary)
            }

            if !displayLine.serviceTime.isEmpty {
                Label(displayLine.serviceTime, systemImage: "clock")
                    .font(F.micro)
                    .foregroundStyle(T.textTertiary)
            }

            // 候车站（可点换）
            if let d = vm.lineDetail, let stop = d.targetStop {
                Button {
                    showStopPicker = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "mappin.circle.fill")
                            .foregroundStyle(T.brand)
                        Text("候车站")
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                        Text(stop.name)
                            .font(F.captionBold)
                            .foregroundStyle(T.brand)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(T.brand)
                    }
                }
                .buttonStyle(.plain)
                .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private var modePicker: some View {
        Segmented(
            titles: Mode.allCases.map { $0.rawValue },
            selected: Binding(
                get: { Mode.allCases.firstIndex(of: mode) ?? 0 },
                set: { idx in
                    mode = Mode.allCases[idx]
                    if mode == .follow, followedBusId == nil {
                        followedBusId = vm.lineDetail?.buses.first?.busId
                    }
                }
            )
        )
    }

    // MARK: - 主体内容

    @ViewBuilder
    private func content(_ d: BusLineDetail) -> some View {
        switch mode {
        case .arrivals:
            arrivalsCard(d)
        case .vehicles:
            vehiclesCard(d)
        case .follow:
            followCard(d)
        }
    }

    /// 到站：主预测大字 + 后续车辆
    private func arrivalsCard(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            let pred = vm.busDetail.prediction
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(pred.primaryText)
                    .font(.system(size: F.scaled(30), weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(pred.arrived ? T.warn : T.brand)

                VStack(alignment: .leading, spacing: 2) {
                    if pred.distance >= 0 {
                        Text(BusText.distance(pred.distance))
                            .font(F.caption)
                            .foregroundStyle(T.textSecondary)
                    }
                    if pred.nextSeconds >= 0 {
                        Text("下辆 \(nextText(pred.nextSeconds))")
                            .font(F.micro)
                            .foregroundStyle(T.textTertiary)
                    }
                }
                Spacer()
            }

            if d.buses.isEmpty {
                Text("暂时没有在线的车")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
            } else {
                Divider().overlay(T.borderSubtle)
                ForEach(d.buses.prefix(5)) { v in
                    vehicleRow(v)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func nextText(_ secs: Int) -> String {
        if secs == 0 { return "已到站" }
        let m = (secs + 59) / 60
        return m == 0 ? "即将到站" : "\(m)分钟"
    }

    private func vehicleRow(_ v: BusVehicle) -> some View {
        let eta = vm.busDetail.byBus[v.busId]
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(v.plate.isEmpty ? "车辆 \(String(v.busId.suffix(4)))" : v.plate)
                    .font(F.body)
                    .foregroundStyle(T.textPrimary)
                HStack(spacing: 8) {
                    if let eta, eta.distance >= 0 {
                        Text("距本站 " + BusText.distance(eta.distance))
                            .font(F.micro)
                            .foregroundStyle(T.textTertiary)
                    }
                    if v.speed >= 0 {
                        Text("\(v.speed) km/h")
                            .font(F.micro)
                            .foregroundStyle(v.speed > 0 ? T.go : T.textTertiary)
                    }
                }
            }
            Spacer()
            Text(eta?.text ?? "—")
                .font(.system(size: F.scaled(15), weight: .bold))
                .foregroundStyle(eta == nil ? T.textTertiary : T.brand)

            Button {
                Haptics.tick()
                followedBusId = v.busId
                mode = .follow
            } label: {
                Image(systemName: "target")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(T.brand)
                    .padding(6)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    /// 车辆：全部车辆列表
    private func vehiclesCard(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "线路上 \(d.buses.count) 辆车")
                .padding(.bottom, 8)
            if d.buses.isEmpty {
                Text("暂时没有在线的车")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
            } else {
                ForEach(d.buses) { v in
                    vehicleRow(v)
                    if v.id != d.buses.last?.id {
                        Divider().overlay(T.borderSubtle)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    /// 跟随：单车详情 + 途经站时间轴
    @ViewBuilder
    private func followCard(_ d: BusLineDetail) -> some View {
        let bus = d.buses.first { $0.busId == followedBusId } ?? d.buses.first

        VStack(alignment: .leading, spacing: 10) {
            if let bus {
                let eta = vm.busDetail.byBus[bus.busId]
                HStack {
                    Text(bus.plate.isEmpty ? "跟随中的车" : bus.plate)
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    Spacer()
                    if bus.speed >= 0 {
                        Text("\(bus.speed) km/h")
                            .font(F.captionBold)
                            .foregroundStyle(bus.speed > 0 ? T.go : T.textTertiary)
                    }
                }

                HStack(spacing: 20) {
                    stat(label: "到本站",
                         value: eta?.text ?? "—",
                         highlight: true)
                    if let eta, eta.distance >= 0 {
                        stat(label: "距离", value: BusText.distance(eta.distance), highlight: false)
                    }
                    if bus.order > 0 {
                        stat(label: "当前站序", value: "第 \(bus.order) 站", highlight: false)
                    }
                }

                // 途经站时间轴
                if !d.stops.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 0) {
                            ForEach(Array(d.stops.enumerated()), id: \.offset) { idx, stop in
                                VStack(spacing: 4) {
                                    Circle()
                                        .fill(stop.order <= bus.order ? T.go : T.borderSubtle)
                                        .frame(width: stop.order == bus.order ? 10 : 6,
                                               height: stop.order == bus.order ? 10 : 6)
                                        .overlay {
                                            if stop.order == bus.order {
                                                Circle().stroke(T.go.opacity(0.25), lineWidth: 6)
                                            }
                                        }
                                        .frame(height: 12)
                                    Text(stop.name)
                                        .font(F.nano3)
                                        .foregroundStyle(stop.order == bus.order ? T.go : T.textTertiary)
                                        .lineLimit(1)
                                        .frame(width: 54)
                                }
                                if idx < d.stops.count - 1 {
                                    Rectangle()
                                        .fill(stop.order < bus.order ? T.go : T.borderSubtle)
                                        .frame(width: 10, height: 2)
                                        .offset(y: -10)
                                }
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
            } else {
                Text("这条线路暂时没有在线的车")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 20)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    private func stat(label: String, value: String, highlight: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
            Text(value)
                .font(.system(size: F.scaled(highlight ? 18 : 15), weight: .bold))
                .foregroundStyle(highlight ? T.brand : T.textPrimary)
        }
    }

    // MARK: - 途经站

    private func stopsCard(_ d: BusLineDetail) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "途经站 · \(d.stops.count) 站")
                .padding(.bottom, 8)

            if d.stops.isEmpty {
                Text("没有站点数据")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
            } else {
                ForEach(Array(d.stops.enumerated()), id: \.offset) { _, stop in
                    HStack(spacing: 10) {
                        Text("\(stop.order)")
                            .font(F.nano2)
                            .foregroundStyle(T.textTertiary)
                            .frame(width: 22, alignment: .trailing)

                        Circle()
                            .fill(stop.order == d.targetOrder ? T.brand : T.borderSubtle)
                            .frame(width: stop.order == d.targetOrder ? 8 : 5,
                                   height: stop.order == d.targetOrder ? 8 : 5)

                        Text(stop.name)
                            .font(stop.order == d.targetOrder ? F.bodyBold : F.body)
                            .foregroundStyle(stop.order == d.targetOrder ? T.brand : T.textPrimary)
                            .lineLimit(1)

                        Spacer()

                        if stop.order == d.targetOrder {
                            Text("候车站")
                                .font(F.nano2)
                                .foregroundStyle(T.brand)
                        }
                    }
                    .padding(.vertical, 7)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        Haptics.tick()
                        Task { await vm.setTargetOrder(stop.order) }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }
}

// MARK: - 候车站选择

struct StopPickerSheet: View {
    let stops: [BusStop]
    let targetOrder: Int
    var onPick: (Int) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(stops, id: \.id) { stop in
                Button {
                    onPick(stop.order)
                    dismiss()
                } label: {
                    HStack {
                        Text("\(stop.order)")
                            .font(F.nano2)
                            .foregroundStyle(T.textTertiary)
                            .frame(width: 26, alignment: .trailing)
                        Text(stop.name)
                            .font(F.body)
                            .foregroundStyle(T.textPrimary)
                        Spacer()
                        if stop.order == targetOrder {
                            Image(systemName: "checkmark")
                                .foregroundStyle(T.brand)
                        }
                    }
                }
            }
            .navigationTitle("选择候车站")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - 首站发车时刻表

struct TimetableSheet: View {
    let lineName: String
    let times: [String]
    @Environment(\.dismiss) private var dismiss

    private let cols = 5

    var body: some View {
        NavigationStack {
            ScrollView {
                if times.isEmpty {
                    EmptyCard(title: "暂无发车时刻表",
                              subtitle: "该线路未提供首站发车时刻，或数据源不支持")
                        .padding()
                } else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8),
                                             count: cols), spacing: 8) {
                        ForEach(times, id: \.self) { t in
                            Text(t)
                                .font(F.caption)
                                .foregroundStyle(T.textPrimary)
                                .frame(maxWidth: .infinity)
                                .frame(height: 38)
                                .background(
                                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                                        .fill(T.bgSunken)
                                )
                        }
                    }
                    .padding()
                }
            }
            .background(T.bgPage.ignoresSafeArea())
            .navigationTitle("\(lineName) 发车表")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}