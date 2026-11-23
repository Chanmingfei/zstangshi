import SwiftUI
import Shared

/// 城市切换。城市列表走明文接口，数据量不大（几十条），一次拉完本地过滤即可。
struct BusCityPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var cities: [BusCity] = []
    @State private var keyword = ""
    @State private var status = "加载中…"

    private var filtered: [BusCity] {
        let k = keyword.trimmingCharacters(in: .whitespaces)
        guard !k.isEmpty else { return cities }
        return cities.filter { $0.cityName.contains(k) }
    }

    var body: some View {
        List {
            Section { Text(status).font(.caption).foregroundColor(.secondary) }
            ForEach(filtered, id: \.cityId) { c in
                Button {
                    BusPrefs.cityId = c.cityId
                    BusPrefs.cityName = c.cityName
                    dismiss()
                } label: {
                    HStack {
                        Text(c.cityName)
                        Spacer()
                        if c.cityId == BusPrefs.cityId {
                            Image(systemName: "checkmark").foregroundColor(.accentColor)
                        }
                    }
                }
            }
        }
        .searchable(text: $keyword, prompt: "搜索城市")
        .navigationTitle("选择城市")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        guard cities.isEmpty else { return }
        do {
            let body = try await BusFetch.text(ChelaileApi.shared.citiesUrl())
            let list = BusCityKt.parseCities(body: body)
            cities = list
            status = "\(list.count) 个城市"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }
}
