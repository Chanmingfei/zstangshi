import SwiftUI

// MARK: - 站点详情
//
// 站台分组 + 线路行 + 补预测 + 点击进线路详情。
// 由 BusHomeView / BusSearchView 通过 NavigationLink push（共用上层 NavigationStack）。
// 数据在 .task 里用 vm.openStation 补齐；vm.station 是同一份共享状态。
struct StationDetailView: View {
    let station: BusStation
    @ObservedObject var vm: BusViewModel

    /// vm.station 可能还残留上一次看的站点，认 id 后再采用。
    private var current: BusStation {
        (vm.station?.id == station.id ? vm.station : nil) ?? station
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                header
                if current.platforms.count > 1 {
                    platformChips(current.platforms)
                }
                SectionTitle(text: "途经 \(current.arrivals.count) 条线路")
                if current.arrivals.isEmpty {
                    EmptyCard(title: "暂无实时数据",
                              subtitle: "该站点可能已停用，或线路还没接入实时调度")
                } else {
                    ForEach(Array(current.arrivals.enumerated()), id: \.offset) { _, a in
                        NavigationLink {
                            LineDetailView(lineId: a.lineId, vm: vm,
                                           targetOrder: a.orderInLine)
                        } label: {
                            ArrivalRow(arrival: a, bigStyle: false)
                                .padding(.horizontal, 14)
                                .zstCard()
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .background(T.bgPage)
        .refreshable { await vm.refresh() }
        .navigationTitle(current.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await vm.openStation(station) }
    }

    // MARK: - 头部

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "mappin.circle.fill")
                .font(F.title3)
                .foregroundStyle(T.brand)
            VStack(alignment: .leading, spacing: 3) {
                Text(current.name)
                    .font(F.title3)
                    .foregroundStyle(T.textPrimary)
                HStack(spacing: 8) {
                    if !current.distanceLabel.isEmpty {
                        Text(current.distanceLabel)
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                    if current.platforms.count > 1 {
                        Text("\(current.platforms.count) 个站台")
                            .font(F.caption)
                            .foregroundStyle(T.textTertiary)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .zstCard(padding: 14)
    }

    // MARK: - 站台分组

    /// 同一站名下多个物理站台各自有独立到站数据，点选后按该 physicalId 重新拉一次。
    private func platformChips(_ platforms: [BusPlatform]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(platforms.enumerated()), id: \.offset) { idx, p in
                    let on = p.id == current.physicalId
                    Button {
                        Task { await selectPlatform(p) }
                    } label: {
                        Text(p.name.isEmpty ? "站台 \(idx + 1)" : p.name)
                            .font(F.sub)
                            .foregroundStyle(on ? T.brand : T.textPrimary)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(on ? T.brandSoft : T.bgSunken)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func selectPlatform(_ p: BusPlatform) async {
        var s = current
        s.physicalId = p.id
        await vm.openStation(s)
    }
}
