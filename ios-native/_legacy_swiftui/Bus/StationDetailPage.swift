import SwiftUI

/// 站点详情页。
///
/// 对齐 Android `bus/Views/StationDetailView`：
/// 显示该站经过的全部线路与到站，可切换到同名的其他站台，可进线路详情。
struct StationDetailPage: View {
    @Environment(\.dismiss) private var dismiss
    let station: BusStation

    @StateObject private var vm = BusViewModel()
    @State private var openedLine: BusLine?
    @State private var showCode = false

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                headerCard
                if let d = vm.stationDetail {
                    if d.arrivals.isEmpty {
                        if vm.loadingDetail {
                            StateBlock(message: "正在获取经过的线路…", loading: true)
                        } else {
                            StateBlock(message: vm.detailError ?? "该站暂无经过线路",
                                       retry: { Task { await vm.openStation(station) } })
                        }
                    } else {
                        arrivalsCard(d)
                    }
                } else if vm.loadingDetail {
                    StateBlock(message: "正在获取经过的线路…", loading: true)
                } else {
                    StateBlock(message: vm.detailError ?? "站点信息加载失败",
                               retry: { Task { await vm.openStation(station) } })
                }
                codeButton
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 8)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
        .background(T.bgPage.ignoresSafeArea())
        .navigationTitle(station.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await vm.openStation(station) }
        .onDisappear { vm.closeStation() }
        .sheet(item: $openedLine) { line in
            NavigationStack { LineDetailPage(line: line) }
        }
        .sheet(isPresented: $showCode) { TransitCodeSheet() }
    }

    private var displayStation: BusStation {
        vm.stationDetail ?? station
    }

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(displayStation.name)
                .font(F.headline)
                .foregroundStyle(T.textPrimary)
            if !displayStation.distanceText.isEmpty {
                Text("距你约 " + displayStation.distanceText)
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
            }
            // 同名其他站台（可切换）
            if displayStation.platforms.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(displayStation.platforms) { p in
                            Text(p.name)
                                .font(F.micro)
                                .foregroundStyle(p.id == displayStation.physicalStId
                                                 ? .white : T.textSecondary)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(
                                    Capsule().fill(p.id == displayStation.physicalStId
                                                   ? T.brand : T.bgSunken)
                                )
                        }
                    }
                    .padding(.top, 4)
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

    private func arrivalsCard(_ d: BusStation) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "经过线路 · \(d.arrivals.count) 条")
                .padding(.bottom, 8)

            ForEach(d.arrivals) { a in
                Button {
                    Haptics.light()
                    var line = BusLine()
                    line.lineId = a.lineId
                    line.name = a.lineName
                    line.startName = a.startName
                    line.endName = a.endName
                    line.direction = a.direction
                    line.cityId = d.cityId
                    openedLine = line
                } label: {
                    HStack(spacing: 10) {
                        Text(a.lineName)
                            .font(F.bodyBold)
                            .foregroundStyle(T.brand)
                            .frame(minWidth: 52, alignment: .leading)

                        VStack(alignment: .leading, spacing: 2) {
                            if !a.directionText.isEmpty {
                                Text(a.directionText)
                                    .font(F.micro)
                                    .foregroundStyle(T.textTertiary)
                                    .lineLimit(1)
                            }
                            if !a.secondaryText.isEmpty {
                                Text(a.secondaryText)
                                    .font(F.micro)
                                    .foregroundStyle(T.textTertiary)
                            }
                        }

                        Spacer()

                        Text(a.primaryText)
                            .font(F.caption)
                            .foregroundStyle(a.arrived ? T.warn
                                             : (a.minutes >= 0 ? T.go : T.textTertiary))

                        Image(systemName: "chevron.right")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(T.textTertiary)
                    }
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if a.id != d.arrivals.last?.id {
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

    private var codeButton: some View {
        Button {
            Haptics.light()
            showCode = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "qrcode")
                Text("乘车码")
            }
            .font(F.bodyBold)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 46)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous).fill(T.brand)
            )
        }
        .buttonStyle(.pressable(0.98))
    }
}