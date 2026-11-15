import SwiftUI

// MARK: - 搜索 sheet
//
// 线路 / 站点结果分组，searchMore 加载更多。sheet 自带一根 NavigationStack，
// 结果行通过 NavigationLink 在这根栈里 push 站点/线路详情。
struct BusSearchView: View {
    @ObservedObject var vm: BusViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if vm.searching && vm.searchHit.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("搜索中…")
                            .font(F.sub)
                            .foregroundStyle(T.textTertiary)
                    }
                }
                stationSection
                lineSection
                emptySection
            }
            .listStyle(.insetGrouped)
            .searchable(text: $vm.searchText, prompt: "线路号 / 站名")
            .onChange(of: vm.searchText) { q in
                Task { await vm.search(q) }
            }
            .navigationTitle("搜索")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }

    // MARK: - 站点结果

    @ViewBuilder
    private var stationSection: some View {
        if !vm.searchHit.stations.isEmpty {
            Section("站点") {
                ForEach(Array(vm.searchHit.stations.enumerated()), id: \.offset) { _, s in
                    NavigationLink {
                        StationDetailView(station: s, vm: vm)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.name)
                                .font(F.body)
                                .foregroundStyle(T.textPrimary)
                            if !s.distanceLabel.isEmpty {
                                Text(s.distanceLabel)
                                    .font(F.caption)
                                    .foregroundStyle(T.textTertiary)
                            }
                        }
                    }
                }
                if vm.searchHit.stationsTruncated {
                    Text("还有更多站点未显示，可换个更精确的关键词")
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                }
            }
        }
    }

    // MARK: - 线路结果

    @ViewBuilder
    private var lineSection: some View {
        if !vm.searchHit.lines.isEmpty {
            Section("线路") {
                ForEach(Array(vm.searchHit.lines.enumerated()), id: \.offset) { _, l in
                    NavigationLink {
                        LineDetailView(lineId: l.lineId, vm: vm)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(l.name)
                                .font(F.body)
                                .foregroundStyle(T.textPrimary)
                            Text(l.terminusLabel)
                                .font(F.caption)
                                .foregroundStyle(T.textTertiary)
                        }
                    }
                }
                // searchMore 只补线路，站点结果没有翻页接口
                if vm.searchHit.linesTruncated {
                    Button {
                        Task { await vm.searchMore() }
                    } label: {
                        Text("加载更多线路")
                            .font(F.sub)
                            .foregroundStyle(T.brand)
                    }
                }
            }
        }
    }

    // MARK: - 空态

    @ViewBuilder
    private var emptySection: some View {
        if vm.searchHit.isEmpty && !vm.searching && !vm.searchText.isEmpty {
            Section {
                Text("没有找到相关线路或站点")
                    .font(F.sub)
                    .foregroundStyle(T.textTertiary)
            }
        } else if vm.searchHit.isEmpty && vm.searchText.isEmpty {
            Section {
                Text("输入线路号或站名开始搜索")
                    .font(F.sub)
                    .foregroundStyle(T.textTertiary)
            }
        }
    }
}
