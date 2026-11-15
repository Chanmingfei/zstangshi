import Foundation
import CommonCrypto

/// 车来了（Chelaile）客户端 —— 严格按安卓 `bus/ChelaileClient.kt` 的真实协议实现。
///
/// ⚠️ 这里修掉的是**上一版 iOS 的核心错误**：
/// 旧代码凭猜测写了 `/api/encryptedHomePage`、`/api/query!nSearch` 这类路径和参数，
/// 而真实协议是：
///   · 路径：`/bus/stop!encryptedHomePage.action`、`/bus/query!nSearch.action`、
///           `/bus/line!encryptedLineDetail.action`、`/bus/stop!encryptedPhyStnDetail.action`
///   · 基础参数（**每一项都必须带**，顺序也影响签名）：
///         s=h5, wxs=wx_app, sign=1, h5RealData=1, v=3.11.28, src=weixinapp_cx,
///         ctm_mp=mp_wx, vc=2, favoriteGray=1, gpstype=wgs, geo_type=wgs, scene=1256
///   · 签名：按插入顺序拼 `"k"="v"&...` + 盐 `qwihrnbtmj`，MD5 小写，键名 `cryptoSign`
///   · 响应：外层 `**YGKJ{...}`，`jsonr.data` 里可能是 string / 带 encryptResult 的对象；
///           加密内容是 **Base64 → AES/ECB/PKCS5**，密钥是 **32 个 ASCII 字符**（AES-256）
///
/// 另一处关键：`buses[].distanceToWaitStn` 实际是「到终点站的距离」，
/// 必须靠 `encryptedBusDetail` 回填真正的「到候车站距离」，否则会出现
/// 「已到站 · 距本站 11.7 公里」这种荒谬文案。
actor ChelaileClient {

    // MARK: - 常量（与安卓逐字一致）

    private static let signSalt = "qwihrnbtmj"
    /// 微信小程序通道密钥：32 个 ASCII 字符 → AES-256
    private static let keyWx = "FF32AE65FBFD19414EAAFF6291A54B42"
    /// H5 通道备用密钥
    private static let keyH5 = "422556651C7F7B2B5C266EED06068230"

    private static let baseURL = "https://web.chelaile.net.cn/api"

    /// 必须伪装成「Windows 微信小程序」。第一个分支是 Chrome 常规 UA，
    /// 后半段才是识别关键：`MicroMessenger/7.0.20.1781(0x6700143B)` 之间**没有空格**，
    /// 且要带 `MiniProgramEnv/Windows`。
    private static let userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        + " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781"
        + "(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF"

    private static let referer = "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html"

    static let defaultCityId = "056"        // 唐山
    static let defaultCityName = "唐山"

    private let session: URLSession
    private let cityId: String

    init(cityId: String = ChelaileClient.defaultCityId) {
        self.cityId = cityId
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 18
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        // 车来了是标准 HTTPS，不需要 ATS 放行；这里不设置任何 cookie
        session = URLSession(configuration: cfg)
    }

    // MARK: - 基础参数（顺序即签名顺序，不可乱）

    private static func base() -> [(String, String)] {
        [
            ("s", "h5"),
            ("wxs", "wx_app"),
            ("sign", "1"),
            ("h5RealData", "1"),
            ("v", "3.11.28"),
            ("src", "weixinapp_cx"),
            ("ctm_mp", "mp_wx"),
            ("vc", "2"),
            ("favoriteGray", "1"),
            ("gpstype", "wgs"),
            ("geo_type", "wgs"),
            ("scene", "1256")
        ]
    }

    /// 车来了签名：按参数**插入顺序**拼 `"k"="v"&...` 再加盐取 MD5 小写。
    static func cryptoSign(_ params: [(String, String)]) -> String {
        var sb = ""
        for (k, v) in params {
            if !sb.isEmpty { sb += "&" }
            sb += "\"" + k + "\"=\"" + v + "\""
        }
        sb += signSalt
        return md5Hex(sb)
    }

    // MARK: - 对外接口

    /// 支持实时公交的城市（明文接口，不在 /api 下，也不需要签名）
    func cities() async throws -> [BusCity] {
        var q: [String] = []
        for (k, v) in Self.base() {
            q.append("\(Self.urlEncode(k))=\(Self.urlEncode(v))")
        }
        let raw = try await httpGet("https://web.chelaile.net.cn/wwd/ncitylist?\(q.joined(separator: "&"))")
        guard let start = raw.firstIndex(of: "{") else { throw BusError.upstream("城市列表响应异常") }
        let body = Self.jsonSlice(raw, from: start)
        guard let data = body.data(using: .utf8),
              let env = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw BusError.upstream("城市列表解析失败")
        }
        let payload = (env["data"] as? [String: Any]) ?? env
        guard let arr = payload["cityList"] as? [[String: Any]] else { return [] }
        return arr.compactMap { c in
            let name = Self.str(c["cityName"])
            let id = Self.str(c["cityId"])
            guard !name.isEmpty, !id.isEmpty else { return nil }
            return BusCity(id: id, name: name)
        }
    }

    /// 附近站点（带每条线的到站）
    func nearby(lat: Double, lng: Double, limit: Int = 15) async throws -> [BusStation] {
        var p = Self.base()
        p.append(("cityId", cityId))
        p.append(("localCityId", "undefined"))
        p.append(("lat", Self.fmt(lat)))
        p.append(("lng", Self.fmt(lng)))
        p.append(("geo_lat", Self.fmt(lat)))
        p.append(("geo_lng", Self.fmt(lng)))
        p.append(("type", "5"))
        p.append(("permission", "0"))

        let data = try await call("/bus/stop!encryptedHomePage.action", params: p)
        guard let arr = data["nearSts"] as? [[String: Any]] else { return [] }
        var out: [BusStation] = []
        for s in arr {
            if out.count >= limit { break }
            let name = Self.str(s["sn"])
            guard !name.isEmpty else { continue }
            var st = BusStation()
            st.sId = Self.str(s["sId"])
            st.name = name
            st.physicalStId = Self.str(s["physicalStId"])
            st.namesakeStId = Self.str(s["namesakeStId"])
            st.lat = Self.num(s["lat"]) ?? 0
            st.lng = Self.num(s["lng"]) ?? 0
            st.distanceMeters = Self.int(s["distance"]) ?? -1
            st.cityId = cityId
            st.arrivals = Self.readArrivals(s["lines"] as? [[String: Any]] ?? [])
            out.append(st)
        }
        return out
    }

    /// 站点详情：一个物理站台下全部线路与到站
    func stopDetail(physicalId: String?, namesakeId: String?, stationId: String?,
                    lat: Double, lng: Double) async throws -> BusStation {
        var p = Self.base()
        p.append(("cityId", cityId))
        p.append(("localCityId", cityId))
        p.append(("physicalStId", physicalId ?? ""))
        p.append(("namesakeStId", namesakeId ?? ""))
        p.append(("firstLineId", ""))
        p.append(("stationId", stationId ?? ""))
        let la = lat > 0 ? Self.fmt(lat) : ""
        let ln = lng > 0 ? Self.fmt(lng) : ""
        p.append(("lat", la))
        p.append(("lng", ln))
        p.append(("geo_lat", la))
        p.append(("geo_lng", ln))
        p.append(("actionState", "1"))
        p.append(("permission", "0"))

        let data = try await call("/bus/stop!encryptedPhyStnDetail.action", params: p)
        var out = BusStation()
        out.cityId = cityId
        out.sId = stationId ?? ""
        out.physicalStId = physicalId ?? ""
        out.namesakeStId = namesakeId ?? ""

        var merged: [BusArrival] = []
        var seenPlatform: [String] = []
        if let list = data["stationList"] as? [[String: Any]] {
            for s in list {
                if out.name.isEmpty { out.name = Self.str(s["sn"]) }
                if out.sId.isEmpty { out.sId = Self.str(s["sId"]) }
                if out.physicalStId.isEmpty { out.physicalStId = Self.str(s["physicalStId"]) }
                if out.namesakeStId.isEmpty { out.namesakeStId = Self.str(s["namesakeStId"]) }
                // 站点坐标从响应取；传进来的 lat/lng 是用户位置，
                // 拿来当站点位置会让回填列表时的距离算成 0
                if out.lat == 0 { out.lat = Self.num(s["lat"]) ?? 0 }
                if out.lng == 0 { out.lng = Self.num(s["lng"]) ?? 0 }
                if out.distanceMeters < 0 { out.distanceMeters = Self.int(s["distance"]) ?? -1 }

                let pid = Self.str(s["physicalStId"])
                if !pid.isEmpty && !seenPlatform.contains(pid) {
                    seenPlatform.append(pid)
                    out.platforms.append(BusPlatform(id: pid, name: Self.str(s["sn"])))
                }
                merged.append(contentsOf: Self.readArrivals(s["lines"] as? [[String: Any]] ?? []))
            }
        }
        out.arrivals = Self.dedupeAndSort(merged)
        return out
    }

    /// 关键字搜索：站点 + 线路一起搜
    func search(keyword: String, lat: Double = 0, lng: Double = 0) async throws -> BusSearchHit {
        let result = try await searchResult(keyword: keyword, lat: lat, lng: lng, more: false)
        var hit = BusSearchHit()
        hit.lineCount = Self.int(result["lineCount"]) ?? 0
        hit.stationCount = Self.int(result["stationCount"]) ?? 0
        hit.stations = Self.readStations(result)
        hit.lines = Self.readLines(result)
        return hit
    }

    /// 搜索「还有更多」：同一接口带 more=true，一次给全量线路（但不再回站点）
    func searchMore(keyword: String, lat: Double = 0, lng: Double = 0) async throws -> [BusLine] {
        let result = try await searchResult(keyword: keyword, lat: lat, lng: lng, more: true)
        return Self.readLines(result)
    }

    private func searchResult(keyword: String, lat: Double, lng: Double,
                              more: Bool) async throws -> [String: Any] {
        var p = Self.base()
        p.append(("cityId", cityId))
        p.append(("localCityId", cityId))
        p.append(("key", keyword))
        p.append(("supportPhyStn", "true"))
        if more { p.append(("more", "true")) }
        if lat > 0 && lng > 0 {
            p.append(("lat", Self.fmt(lat)))
            p.append(("lng", Self.fmt(lng)))
            p.append(("geo_lat", Self.fmt(lat)))
            p.append(("geo_lng", Self.fmt(lng)))
        }
        let data = try await call("/bus/query!nSearch.action", params: p)
        return (data["result"] as? [String: Any]) ?? [:]
    }

    /// 线路详情：站表 + 在途车辆
    /// - Parameter targetOrder: 候车站站序（1 基），>0 时额外补一次车辆预测
    func lineDetail(lineId: String, targetOrder: Int,
                    stationId: String?, lat: Double, lng: Double) async throws -> BusLineDetail {
        var p = Self.base()
        p.append(("cityId", cityId))
        p.append(("localCityId", cityId))
        p.append(("lineId", lineId))
        let la = lat > 0 ? Self.fmt(lat) : ""
        let ln = lng > 0 ? Self.fmt(lng) : ""
        p.append(("lat", la))
        p.append(("lng", ln))
        p.append(("geo_lat", la))
        p.append(("geo_lng", ln))
        // 上游用 targetOrder 决定「哪一站算候车站」，0 也能出站表
        p.append(("targetOrder", "\(max(0, targetOrder))"))
        p.append(("stationId", stationId ?? ""))
        p.append(("permission", "0"))

        let data = try await call("/bus/line!encryptedLineDetail.action", params: p)
        var d = BusLineDetail()
        d.cityId = cityId
        d.lineId = lineId
        d.raw = data

        if let line = data["line"] as? [String: Any] {
            d.line = Self.readLine(line)
        }
        // 站表：stations 数组（含站序 / sId / 坐标）
        if let arr = data["stations"] as? [[String: Any]] {
            d.stops = arr.enumerated().compactMap { idx, s in
                let name = Self.str(s["sn"])
                guard !name.isEmpty else { return nil }
                var stop = BusStop()
                stop.name = name
                stop.order = Self.int(s["order"]) ?? (idx + 1)
                stop.sId = Self.str(s["sId"])
                stop.lat = Self.num(s["lat"]) ?? 0
                stop.lng = Self.num(s["lng"]) ?? 0
                return stop
            }
            d.stops.sort { $0.order < $1.order }
        }
        // 在途车辆
        if let arr = data["buses"] as? [[String: Any]] {
            d.buses = arr.compactMap { Self.readVehicle($0) }
        }
        if let t = Self.int(data["targetOrder"]), t > 0 {
            d.targetOrder = t
        }
        d.at = Date()
        return d
    }

    /// 车辆预测：返回「最快到候车站的车」+ 按 busId 摊开的每车明细。
    ///
    /// 上游 `buses[].distanceToWaitStn` 是**到终点站**的距离，绝不能直接用。
    func busDetail(lineId: String, targetOrder: Int,
                   stationId: String?, lat: Double, lng: Double) async throws -> BusDetailResult {
        var p = Self.base()
        p.append(("cshow", "busDetail"))
        p.append(("specail", "0"))          // 注意：上游字段名就是拼错的 specail
        p.append(("specialType", "undefined"))
        p.append(("cityId", cityId))
        p.append(("localCityId", cityId))
        p.append(("lineId", lineId))
        p.append(("targetOrder", "\(targetOrder)"))
        p.append(("specialTargetOrder", "\(targetOrder)"))
        p.append(("stationId", stationId ?? ""))
        let la = lat > 0 ? Self.fmt(lat) : ""
        let ln = lng > 0 ? Self.fmt(lng) : ""
        p.append(("lat", la))
        p.append(("lng", ln))
        p.append(("geo_lat", la))
        p.append(("geo_lng", ln))
        p.append(("userId", ""))
        p.append(("h5Id", ""))

        let data = try await call("/bus/line!encryptedBusDetail.action", params: p)
        var out = BusDetailResult()

        guard let arr = data["buses"] as? [[String: Any]] else { return out }
        for b in arr {
            let busId = Self.str(b["busId"])
            guard !busId.isEmpty else { continue }
            let travels = b["travels"] as? [[String: Any]] ?? []
            guard let first = travels.first else { continue }
            let secs = Self.int(first["travelTime"]) ?? -1
            let dist = Self.int(first["distance"]) ?? -1
            let tip = Self.str(first["tip"])
            let order = Self.int(first["order"]) ?? 0
            out.byBus[busId] = BusEta(busId: busId, seconds: secs,
                                      distance: dist, tip: tip, order: order)
        }

        // 取最快那辆做主预测
        let sorted = out.byBus.values
            .filter { $0.seconds >= 0 }
            .sorted { $0.seconds < $1.seconds }
        if let fastest = sorted.first {
            var pred = BusPrediction()
            pred.seconds = fastest.seconds
            pred.distance = fastest.distance
            pred.busId = fastest.busId
            pred.tip = fastest.tip
            pred.arrived = fastest.seconds == 0
            if sorted.count > 1 {
                pred.nextSeconds = sorted[1].seconds
                pred.nextBusId = sorted[1].busId
            }
            out.prediction = pred
        }
        return out
    }

    /// 首站发车时刻表
    func preStartTimetable(lineId: String, lineNo: String?, direction: Int) async throws -> [String] {
        var p = Self.base()
        p.append(("cityId", cityId))
        p.append(("lineId", lineId))
        p.append(("lineNo", lineNo ?? ""))
        p.append(("direction", "\(direction)"))
        let data = try await call("/bus/line!preStartTimetableNew.action", params: p)
        var out: [String] = []
        if let arr = data["times"] as? [Any] {
            out = arr.map { Self.str($0) }.filter { !$0.isEmpty }
        }
        return out
    }

    // MARK: - 协议核心

    /// 发一次 GET，返回**解密后的业务 JSON**
    private func call(_ path: String, params: [(String, String)]) async throws -> [String: Any] {
        var signed = params
        signed.append(("cryptoSign", Self.cryptoSign(params)))

        let q = signed.map { "\(Self.urlEncode($0.0))=\(Self.urlEncode($0.1))" }
            .joined(separator: "&")
        let raw = try await httpGet(Self.baseURL + path + "?" + q)
        return try Self.extract(raw)
    }

    private func httpGet(_ urlString: String) async throws -> String {
        guard let url = URL(string: urlString) else { throw BusError.network("URL 无效") }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(Self.referer, forHTTPHeaderField: "Referer")
        req.setValue("1", forHTTPHeaderField: "xweb_xhr")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")

        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw BusError.network("没有收到响应") }
        guard http.statusCode < 400 else {
            throw BusError.network("车来了服务返回 HTTP \(http.statusCode)")
        }
        guard let text = String(data: data, encoding: .utf8), !text.isEmpty else {
            throw BusError.upstream("车来了返回空响应")
        }
        return text
    }

    /// 剥掉 `**YGKJ` 外层并解出业务 JSON
    static func extract(_ raw: String) throws -> [String: Any] {
        guard let start = raw.firstIndex(of: "{") else { throw BusError.upstream("响应不是 JSON") }
        let body = jsonSlice(raw, from: start)
        guard let data = body.data(using: .utf8),
              let env = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw BusError.upstream("响应 JSON 解析失败")
        }

        let jsonr = env["jsonr"] as? [String: Any]
        let dataValue: Any? = jsonr.map { $0["data"] } ?? env["data"]

        if let s = dataValue as? String {
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return [:] }
            if let dec = decrypt(trimmed),
               let d = dec.data(using: .utf8),
               let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                return o
            }
            if let d = trimmed.data(using: .utf8),
               let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                return o
            }
            return [:]
        }
        if let obj = dataValue as? [String: Any] {
            let enc = str(obj["encryptResult"])
            if !enc.isEmpty, let dec = decrypt(enc),
               let d = dec.data(using: .utf8),
               let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                return o
            }
            return obj
        }
        return jsonr ?? env
    }

    /// 括号计数找 JSON 结尾（跳过字符串字面量里的花括号）
    static func jsonSlice(_ raw: String, from start: String.Index) -> String {
        let chars = Array(raw[start...])
        guard chars.first == "{" else { return String(chars) }
        var depth = 0
        var inStr = false
        var esc = false
        for i in 0..<chars.count {
            let c = chars[i]
            if inStr {
                if esc { esc = false }
                else if c == "\\" { esc = true }
                else if c == "\"" { inStr = false }
                continue
            }
            if c == "\"" { inStr = true }
            else if c == "{" { depth += 1 }
            else if c == "}" {
                depth -= 1
                if depth == 0 { return String(chars[0...i]) }
            }
        }
        return String(chars)
    }

    /// AES/ECB/PKCS5 解密。两把密钥都试，解出来必须是合法 JSON 才算成功。
    ///
    /// 关键：密钥是 **32 个十六进制字符按字面**当 ASCII 字节用（AES-256），
    /// **不是** hex-decode 成 16 字节。搞错会得到乱码。
    static func decrypt(_ b64: String) -> String? {
        let compact = b64.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        guard let cipher = Data(base64Encoded: compact, options: .ignoreUnknownCharacters),
              !cipher.isEmpty else { return nil }

        for key in [keyWx, keyH5] {
            guard let plain = aesECBDecrypt(cipher, key: key),
                  let text = String(data: plain, encoding: .utf8) else { continue }
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.hasPrefix("{") || t.hasPrefix("[") { return t }
        }
        return nil
    }

    /// AES-ECB + PKCS7 解密（CryptoKit 不支持 ECB，只能走 CommonCrypto）
    static func aesECBDecrypt(_ data: Data, key: String) -> Data? {
        let keyBytes = Array(key.utf8)
        guard [16, 24, 32].contains(keyBytes.count) else { return nil }

        let outLen = data.count + kCCBlockSizeAES128
        var out = Data(count: outLen)
        var moved = 0

        let status = out.withUnsafeMutableBytes { outPtr -> CCCryptorStatus in
            data.withUnsafeBytes { inPtr -> CCCryptorStatus in
                keyBytes.withUnsafeBytes { keyPtr -> CCCryptorStatus in
                    CCCrypt(CCOperation(kCCDecrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                            keyPtr.baseAddress, keyBytes.count, nil,
                            inPtr.baseAddress, data.count,
                            outPtr.baseAddress, outLen,
                            &moved)
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return out.prefix(moved)
    }

    // MARK: - 解析辅助

    /// 解析 `lines[]`：`stnStates[].value` 是「距本站还剩几站」，`travelTime` 是秒。
    /// 按到站先后排序取前两辆：第一辆 → minutes/stopsAway，第二辆 → nextXxx。
    static func readArrivals(_ lines: [[String: Any]]) -> [BusArrival] {
        var out: [BusArrival] = []
        for li in lines {
            guard let lo = li["line"] as? [String: Any] else { continue }
            if (int(lo["subwayV2"]) ?? 0) == 1 { continue }

            var a = BusArrival()
            a.lineId = str(lo["lineId"])
            a.lineName = str(lo["name"])
            if a.lineName.isEmpty { a.lineName = str(lo["lineNo"]) }
            a.direction = int(lo["direction"]) ?? 0
            a.startName = str(lo["startSn"])
            a.endName = str(lo["endSn"])
            a.status = str(lo["shortDesc"])
            if a.status.isEmpty { a.status = str(lo["nextOperationTimeDesc"]) }
            if a.lineName.isEmpty { continue }
            // 没有 lineId 的是「只有名字」的占位线路，没有到站数据，混进来会变成一排「暂无车」
            if a.lineId.isEmpty { continue }

            // 这个站在该线路上的站序（虚拟站组 order=999，要用 sId 还原）
            if let target = li["targetStation"] as? [String: Any] {
                a.stopId = str(target["sId"])
                a.orderInLine = effectiveOrder(int(target["order"]) ?? 0, a.stopId)
            }

            var cand: [(stops: Int, secs: Int, dist: Int, state: Int)] = []
            if let states = li["stnStates"] as? [[String: Any]] {
                a.realtime = !states.isEmpty
                for b in states {
                    let stops = int(b["value"]) ?? -1
                    let secs = int(b["travelTime"]) ?? -1
                    let state = int(b["state"]) ?? 0
                    let dist = int(b["distanceToDest"]) ?? -1
                    // state==1 是「已到站」，这种条目有时 value/travelTime 都是 -1，不能丢
                    if stops < 0 && secs < 0 && state != 1 { continue }
                    cand.append((stops, secs, dist, state))
                }
            }
            cand.sort { x, y in
                let sx = x.stops >= 0 ? x.stops : 9999
                let sy = y.stops >= 0 ? y.stops : 9999
                if sx != sy { return sx < sy }
                let a1 = x.secs < 0 ? 99999 : x.secs
                let b1 = y.secs < 0 ? 99999 : y.secs
                return a1 < b1
            }

            // 没有到站数据的先标 pending，界面显示「…」
            a.pending = cand.isEmpty && a.orderInLine > 0
            if let f = cand.first {
                a.stopsAway = f.stops
                a.distanceMeters = f.dist
                a.minutes = BusText.minutesOfSecs(f.secs)
                a.arrived = arrivedOf(minutes: a.minutes, secs: f.secs,
                                      state: f.state, stops: f.stops)
                if cand.count > 1 {
                    let s2 = cand[1]
                    a.nextStopsAway = s2.stops
                    a.nextMinutes = BusText.minutesOfSecs(s2.secs)
                }
            }
            out.append(a)
        }
        return out
    }

    /// 「已到站」只认「还剩 0 分钟」或上游明确 state=1。
    /// 不能只看 state：「车在别站停靠」也会是 state=1，会和详情页的「1 分钟」打架。
    static func arrivedOf(minutes: Int, secs: Int, state: Int, stops: Int) -> Bool {
        if secs == 0 { return true }
        if secs > 0 { return false }
        return state == 1 || stops == 0
    }

    /// 同一线路可能在多个占位里重复出现，按 lineId 去重，留到站信息最好的一条
    static func dedupeAndSort(_ input: [BusArrival]) -> [BusArrival] {
        var best: [String: BusArrival] = [:]
        var order: [String] = []
        for a in input {
            if let cur = best[a.lineId] {
                if rank(a) < rank(cur) { best[a.lineId] = a }
            } else {
                best[a.lineId] = a
                order.append(a.lineId)
            }
        }
        return order.compactMap { best[$0] }.sorted { x, y in
            let rx = rank(x), ry = rank(y)
            return rx != ry ? rx < ry : x.minutes < y.minutes
        }
    }

    /// 0=已到站，1=有分钟数，2=等待发车/收班，3=完全没数据
    static func rank(_ a: BusArrival) -> Int {
        if a.arrived { return 0 }
        if a.minutes >= 0 { return 1 }
        if !a.status.isEmpty { return 2 }
        return 3
    }

    /// 虚拟站组：sId 形如 `999$30$1`，真实站序 = 段[1] + 段[2]
    static func effectiveOrder(_ order: Int, _ sId: String) -> Int {
        if order == 999 || order == 0 {
            let parts = sId.split(separator: "$")
            if parts.count >= 3, let a = Int(parts[1]), let b = Int(parts[2]) {
                return a + b
            }
        }
        return order
    }

    /// 搜索结果里的线路：上游把一条线正反方向当两条记录，合成一行（优先正方向）
    static func readLines(_ result: [String: Any]) -> [BusLine] {
        guard let lines = result["lines"] as? [[String: Any]] else { return [] }
        var uniq: [String: BusLine] = [:]
        var order: [String] = []
        for l in lines {
            if (int(l["subwayV2"]) ?? 0) == 1 { continue }   // 纯地铁线没有实时数据
            var line = readLine(l)
            if line.name.isEmpty { line.name = line.lineNo }
            guard !line.lineId.isEmpty, !line.name.isEmpty else { continue }
            let key = line.lineNo.isEmpty ? line.name : line.lineNo
            if let old = uniq[key] {
                if old.direction != 0 && line.direction == 0 { uniq[key] = line }
            } else {
                uniq[key] = line
                order.append(key)
            }
        }
        return order.compactMap { uniq[$0] }
    }

    static func readLine(_ l: [String: Any]) -> BusLine {
        var line = BusLine()
        line.lineId = str(l["lineId"])
        line.name = str(l["name"])
        if line.name.isEmpty { line.name = str(l["lineNo"]) }
        line.lineNo = str(l["lineNo"])
        line.direction = int(l["direction"]) ?? 0
        line.startName = str(l["startSn"])
        line.endName = str(l["endSn"])
        line.firstTime = str(l["firstTime"])
        line.lastTime = str(l["lastTime"])
        return line
    }

    /// 搜索结果里的站点（自带经纬度与距离，可直接点进详情）
    static func readStations(_ result: [String: Any]) -> [BusStation] {
        guard let arr = result["stations"] as? [[String: Any]] else { return [] }
        var out: [BusStation] = []
        for s in arr {
            if (int(s["subwayV2"]) ?? 0) == 1 { continue }
            var st = BusStation()
            st.name = str(s["sn"]).trimmingCharacters(in: .whitespaces)
            st.sId = str(s["sId"])
            st.physicalStId = str(s["physicalStId"])
            st.namesakeStId = str(s["namesakeStId"])
            st.lat = num(s["lat"]) ?? 0
            st.lng = num(s["lng"]) ?? 0
            st.distanceMeters = int(s["distance"]) ?? -1
            guard !st.name.isEmpty, !st.sId.isEmpty else { continue }
            out.append(st)
        }
        return out
    }

    static func readVehicle(_ o: [String: Any]) -> BusVehicle? {
        let busId = str(o["busId"])
        guard !busId.isEmpty else { return nil }
        var v = BusVehicle()
        v.busId = busId
        v.plate = str(o["busCardId"])
        v.order = effectiveOrder(int(o["order"]) ?? 0, str(o["sId"]))
        v.distanceToTerminal = int(o["distanceToDest"]) ?? -1
        // ⚠️ 上游这个字段是「到终点站的距离」，名字骗人，不能当「到候车站」用
        v.distanceToWaitStnRaw = int(o["distanceToWaitStn"]) ?? -1
        if let sp = num(o["speed"]) { v.speed = Int(sp) }
        if let a = num(o["angle"]) { v.angle = a }
        if let la = num(o["lat"]) { v.lat = la }
        if let ln = num(o["lng"]) { v.lng = ln }
        return v
    }

    // MARK: - 小工具

    static func fmt(_ v: Double) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), v)
    }

    static func urlEncode(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }

    static func md5Hex(_ s: String) -> String {
        let bytes = Array(s.utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
        // 空数组的 baseAddress 可能是 nil，会导致崩溃 —— 显式判空
        if bytes.isEmpty {
            _ = CC_MD5([], 0, &digest)
        } else {
            bytes.withUnsafeBytes { buf in
                _ = CC_MD5(buf.baseAddress, CC_LONG(bytes.count), &digest)
            }
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func str(_ v: Any?) -> String {
        switch v {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }

    static func int(_ v: Any?) -> Int? {
        switch v {
        case let i as Int: return i
        case let n as NSNumber: return n.intValue
        case let s as String: return Int(s)
        default: return nil
        }
    }

    static func num(_ v: Any?) -> Double? {
        switch v {
        case let d as Double: return d
        case let n as NSNumber: return n.doubleValue
        case let s as String: return Double(s)
        default: return nil
        }
    }
}