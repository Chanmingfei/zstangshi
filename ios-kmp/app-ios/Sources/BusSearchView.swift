import SwiftUI
import Shared

/// 公交搜索页：验证「Kotlin 拼 URL/签名/解密 + Swift 发请求」这条分工能跑。
///
/// 这里刻意不引入任何第三方网络库：URLSession 直接带 Referer（车来了缺了就 400），
/// 拿到的 body 原样交给 Kotlin 的 parseSearchHits 去拆壳解密。
struct BusSearchView: View {
    @State private var keyword: String = "游1"
    @State private var hits: [BusLineHit] = []
    @State private var status: String = "输入线路名后点搜索"
    @State private var loading = false
    @State private var rawLength: Int = 0

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("线路名或站点名", text: $keyword)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("搜索") { Task { await search() } }
                        .disabled(loading || keyword.isEmpty)
                }
                Text(status)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Section("结果（唐山 056）") {
                if hits.isEmpty {
                    Text("暂无结果").foregroundColor(.secondary)
                } else {
                    ForEach(hits, id: \.lineId) { hit in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(hit.name).font(.headline)
                                if !hit.no.isEmpty {
                                    Text(hit.no).font(.caption).foregroundColor(.secondary)
                                }
                            }
                            Text("\(hit.startSn) → \(hit.endSn)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Text(hit.lineId).font(.system(size: 10, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("实时公交")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func search() async {
        loading = true
        defer { loading = false }
        let urlText = ChelaileApi.shared.searchUrl(cityId: "056", keyword: keyword)
        guard let url = URL(string: urlText) else {
            status = "URL 拼接失败"
            return
        }

        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        // 两个头都由 Kotlin 侧给出，避免两边各写一份以后漂移
        req.setValue(ChelaileApi.shared.UA, forHTTPHeaderField: "User-Agent")
        req.setValue(ChelaileApi.shared.REFERER, forHTTPHeaderField: "Referer")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")

        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
            let body = String(data: data, encoding: .utf8) ?? ""
            rawLength = body.count
            if code != 200 {
                status = "HTTP \(code)，\(body.prefix(120))"
                return
            }
            let parsed = ChelaileApi.shared.parseSearchHits(body: body)
            hits = parsed
            status = "HTTP 200 · \(body.count) 字节 · 解析出 \(parsed.count) 条"
        } catch {
            status = "网络失败：\(error.localizedDescription)"
        }
    }
}
