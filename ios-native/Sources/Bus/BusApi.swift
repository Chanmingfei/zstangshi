import Foundation
import CommonCrypto

// MARK: - 实时公交 · 网络层实现
//
// 单文件移植 Android v4 的 bus/ChelaileClient.kt（车来了）+ MygolbsClient.kt（掌上公交）
// + BusClient.kt（分发器）。页面层只认 BusGateway，这里按 prefs.source() 分发到两套
// transport，失败一律抛 BusError.upstream，绝不返回伪造数据：上游没给的字段保持
// 模型默认值（-1 / 空串 / 空数组）。
//
// 协议要点（车来了）：
//   · 请求参数按「插入顺序」拼 `"k"="v"&...` 加盐 SIGN_SALT 求 MD5 → cryptoSign；
//   · 响应外层 `**YGKJ{...}`，取 jsonr.data.encryptResult，AES-256-ECB/PKCS7 解密
//     后才是业务 JSON（两把固定密钥都试，解出来必须是合法 JSON）。
// 协议要点（掌上公交）：
//   · POST https://h5.mygolbs.com/ApiData.do，form-urlencoded，CMD=xxx；
//   · 坐标系是 GCJ-02，请求前 wgs2gcj、解析后 gcj2wgs。

// MARK: - 有序参数表（车来了签名强依赖插入顺序，Swift 字典无序，故用数组）

private struct BusApiParams {
    private(set) var pairs: [(String, String)] = []

    mutating func add(_ k: String, _ v: String) {
        if let i = pairs.firstIndex(where: { $0.0 == k }) {
            pairs[i] = (k, v)
        } else {
            pairs.append((k, v))
        }
    }
}

// MARK: - 内部中间结构（对齐 Android BusClient.Pred / ChelaileClient.BusDetail / MygolbsClient.Realtime）

/// 一次车辆预测的结果。大字 / 小字 /「下辆」必须全部来自同一份 Pred。
private struct BusApiPred {
    var secs: Int = -1
    var stops: Int = -1
    var dist: Int = -1
    var arrived: Bool = false
    var busId: String = ""
    var depDesc: String = ""
    var preArrival: String = ""
    var nextSecs: Int = -1
    var nextStops: Int = -1
    var nextBusId: String = ""
}

/// 逐车到候车站的明细（车来了 encryptedBusDetail 一次响应里按 busId 回填）。
private struct BusApiBusEta {
    var busId: String = ""
    var seconds: Int = -1     // 到「候车站」的剩余秒数，不是到终点
    var distance: Int = -1
    var tip: String = ""
    var order: Int = 0
}

private struct BusApiBusDetail {
    var pred = BusApiPred()
    var byBus: [String: BusApiBusEta] = [:]
}

private struct BusApiCand {
    var stops: Int = -1
    var secs: Int = -1
    var dist: Int = -1
    var state: Int = 0
}

private struct BusApiMgBus {
    var number = ""
    var index = -1
    var statusType = ""
    var stationName = ""
    var lat = 0.0
    var lng = 0.0
}

private struct BusApiMgEta {
    var busNumber = ""
    var minutes = -1
    var count = -1
    var distance = -1
    var arrived = false
}

private struct BusApiRt {
    var hasReal = true
    var runState = 0
    var planTime = ""
    var statusTips = ""
    var buses: [BusApiMgBus] = []
    var etas: [BusApiMgEta] = []
    var etaSecondsByBus: [String: Int] = [:]
    var distanceByBus: [String: Int] = [:]
}

// MARK: - JSON 取值辅助（JSONSerialization 返回 [String: Any] / NSNumber）

private func busObj(_ v: Any?) -> [String: Any]? { v as? [String: Any] }
private func busArr(_ v: Any?) -> [Any]? { v as? [Any] }

private func busStr(_ o: [String: Any]?, _ k: String) -> String {
    guard let v = o?[k] else { return "" }
    if let s = v as? String { return s }
    if let n = v as? NSNumber { return n.stringValue }
    return ""
}

private func busInt(_ o: [String: Any]?, _ k: String, _ def: Int = -1) -> Int {
    guard let v = o?[k] else { return def }
    if let n = v as? NSNumber { return n.intValue }
    if let s = v as? String { return Int(s) ?? def }
    return def
}

private func busDouble(_ o: [String: Any]?, _ k: String, _ def: Double = 0.0) -> Double {
    guard let v = o?[k] else { return def }
    if let n = v as? NSNumber { return n.doubleValue }
    if let s = v as? String { return Double(s) ?? def }
    return def
}

private func busHas(_ o: [String: Any]?, _ k: String) -> Bool { o?[k] != nil }

private func busParseJSON(_ s: String?) -> [String: Any]? {
    guard let s = s, let data = s.data(using: .utf8) else { return nil }
    guard let obj = try? JSONSerialization.jsonObject(with: data, options: []) else { return nil }
    return obj as? [String: Any]
}

/// 从原始文本里第一个 '{' 起做括号计数，截出配对的 JSON 主体（跳过字符串里的花括号）。
private func busExtractJSONBody(_ raw: String) -> String? {
    let chars = Array(raw)
    guard let start = chars.firstIndex(of: "{") else { return nil }
    var depth = 0
    var inStr = false
    var esc = false
    var i = start
    while i < chars.count {
        let c = chars[i]
        if inStr {
            if esc { esc = false }
            else if c == "\\" { esc = true }
            else if c == "\"" { inStr = false }
        } else {
            if c == "\"" { inStr = true }
            else if c == "{" { depth += 1 }
            else if c == "}" {
                depth -= 1
                if depth == 0 { return String(chars[start...i]) }
            }
        }
        i += 1
    }
    return nil
}

// MARK: - 编码 / 摘要 / 解密

/// 贴近 Java URLEncoder：ASCII 字母数字与 -_.* 保留，空格转 '+'，其余字节 %XX 大写。
private func busFormEscape(_ s: String) -> String {
    var out = ""
    for b in Array(s.utf8) {
        if b == 32 { out.append("+"); continue }
        let alphaNum = (b >= 48 && b <= 57) || (b >= 65 && b <= 90) || (b >= 97 && b <= 122)
        let safe = b == 45 || b == 95 || b == 46 || b == 42   // - _ . *
        if alphaNum || safe {
            out += String(decoding: [b], as: UTF8.self)
            continue
        }
        out += String(format: "%%%02X", Int(b))
    }
    return out
}

private func busFmt(_ v: Double) -> String {
    String(format: "%.6f", v)
}

private func busDecode(_ data: Data) -> String {
    if let text = String(data: data, encoding: .utf8) { return text }
    // 兜底：老接口偶尔回 GB18030（与 Net/Http.swift 的 decode 同一套 CF 编码换算）
    let gb = CFStringConvertEncodingToNSStringEncoding(
        CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
    if let text = String(data: data, encoding: String.Encoding(rawValue: gb)) { return text }
    return String(decoding: data, as: UTF8.self)
}

private func busMD5(_ s: String) -> String {
    let data = Data(s.utf8)
    var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
    data.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
        _ = CC_MD5(buf.baseAddress!, CC_LONG(buf.count), &digest)
    }
    return digest.map { String(format: "%02x", $0) }.joined()
}

private func busAESDecrypt(_ b64: String) -> String? {
    let clean = b64.components(separatedBy: .whitespacesAndNewlines).joined()
    guard let cipher = Data(base64Encoded: clean) else { return nil }
    for key in [ChelaileTransport.keyWX, ChelaileTransport.keyH5] {
        if let text = busAESOnce(cipher: cipher, key: key) { return text }
    }
    return nil
}

private func busAESOnce(cipher: Data, key: String) -> String? {
    let keyData = Data(key.utf8)
    let capacity = cipher.count + Int(kCCBlockSizeAES128)
    var out = Data(count: capacity)
    var outLen: size_t = 0
    let status: CCCryptorStatus = out.withUnsafeMutableBytes { outBuf -> CCCryptorStatus in
        keyData.withUnsafeBytes { keyBuf -> CCCryptorStatus in
            cipher.withUnsafeBytes { cipherBuf -> CCCryptorStatus in
                CCCrypt(
                    CCOperation(kCCDecrypt),
                    CCAlgorithm(kCCAlgorithmAES128),
                    CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                    keyBuf.baseAddress!, keyData.count,
                    nil,
                    cipherBuf.baseAddress!, cipher.count,
                    outBuf.baseAddress!, capacity,
                    &outLen
                )
            }
        }
    }
    guard status == kCCSuccess, outLen > 0 else { return nil }
    out = Data(out.prefix(Int(outLen)))
    guard let text = String(data: out, encoding: .utf8) else { return nil }
    let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
    return (t.hasPrefix("{") || t.hasPrefix("[")) ? t : nil
}

// MARK: - 通用小工具

private func busEffectiveOrder(_ order: Int, _ sId: String) -> Int {
    if order < 999 { return order }
    let seg = sId.split(separator: "$", omittingEmptySubsequences: false).map(String.init)
    if seg.count >= 3, let a = Int(seg[1]), let b = Int(seg[2]) { return a + b }
    return order
}

private func busArrivedOf(_ secs: Int, _ state: Int, _ stops: Int) -> Bool {
    if secs == 0 { return true }
    if secs > 0 { return false }
    return state == 1 || stops == 0
}

private func busDistanceMeters(_ lat1: Double, _ lng1: Double,
                               _ lat2: Double, _ lng2: Double) -> Double {
    let r = 6371000.0
    let p1 = lat1 * .pi / 180.0
    let p2 = lat2 * .pi / 180.0
    let dp = p2 - p1
    let dl = (lng2 - lng1) * .pi / 180.0
    let h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * asin(min(1.0, sqrt(h)))
}

private func busHHMM(_ s: String) -> String {
    if let r = s.range(of: "\\d{1,2}:\\d{2}", options: .regularExpression) {
        return String(s[r])
    }
    return s.trimmingCharacters(in: .whitespacesAndNewlines)
}

private func busRank(_ a: BusArrival) -> Int {
    if a.arrived { return 0 }
    if a.minutes >= 0 { return 1 }
    if !a.status.isEmpty { return 2 }
    return 3
}

private func busDedupeSort(_ input: [BusArrival]) -> [BusArrival] {
    var best: [String: BusArrival] = [:]
    var order: [String] = []
    for a in input {
        if let cur = best[a.lineId] {
            if busRank(a) < busRank(cur) { best[a.lineId] = a }
        } else {
            best[a.lineId] = a
            order.append(a.lineId)
        }
    }
    var out = order.compactMap { best[$0] }
    out.sort { x, y in
        let rx = busRank(x), ry = busRank(y)
        if rx != ry { return rx < ry }
        return x.minutes < y.minutes
    }
    return out
}

// 时刻表辅助
private func busMinutesOf(_ hhmm: String) -> Int {
    let v = hhmm.trimmingCharacters(in: .whitespaces)
    guard let i = v.firstIndex(of: ":") else { return -1 }
    let h = Int(v[v.startIndex..<i].trimmingCharacters(in: .whitespaces)) ?? -1
    let m = Int(v[v.index(after: i)...].trimmingCharacters(in: .whitespaces)) ?? -1
    if h < 0 || h > 23 || m < 0 || m > 59 { return -1 }
    return h * 60 + m
}

private func busSortByClock(_ times: inout [String]) {
    times.sort { busMinutesOf($0) < busMinutesOf($1) }
}

private func busFirstAfter(_ sorted: [String], _ now: String) -> String? {
    let n = busMinutesOf(now)
    if n < 0 { return nil }
    for t in sorted where busMinutesOf(t) >= n { return t }
    return nil
}

private func busNowHHMM(_ tz: TimeZone? = nil) -> String {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    if let tz = tz { f.timeZone = tz }
    return f.string(from: Date())
}

private func busEstimateInterval(_ times: [String]) -> Int {
    var gaps: [Int] = []
    if times.count > 1 {
        for i in 1..<times.count {
            let a = busMinutesOf(times[i - 1]), b = busMinutesOf(times[i])
            if a >= 0 && b > a { gaps.append(b - a) }
        }
    }
    if gaps.isEmpty { return -1 }
    gaps.sort()
    return gaps[gaps.count / 2]
}

/// iOS 的 BusTtSegment 只有 title + times，把上游的时段 / 提示 / 间隔合成可读标题。
private func busTtTitle(from: String, to: String, tips: String,
                        interval: Int, times: [String]) -> String {
    var sb = ""
    if !from.isEmpty || !to.isEmpty { sb += from + "–" + to }
    if !tips.isEmpty {
        if !sb.isEmpty { sb += " · " }
        sb += tips
    }
    var iv = interval
    if iv <= 0 && times.count > 1 { iv = busEstimateInterval(times) }
    if iv > 0 {
        if !sb.isEmpty { sb += " · " }
        sb += "约 \(iv) 分钟一班"
    }
    return sb
}

// MARK: - 网络会话（GET / POST，async-await，失败统一转 BusError.upstream）

private enum BusApiNet {
    static let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 20
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        return URLSession(configuration: cfg)
    }()

    static func get(_ urlString: String, headers: [String: String]) async throws -> String {
        guard let url = URL(string: urlString) else { throw BusError.upstream("公交地址无效") }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = 15
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        do {
            let (data, resp) = try await session.data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                throw BusError.upstream("公交 HTTP \(http.statusCode)")
            }
            return busDecode(data)
        } catch let e as BusError {
            throw e
        } catch {
            throw BusError.upstream("公交网络异常：\(error.localizedDescription)")
        }
    }

    static func post(_ urlString: String, headers: [String: String],
                     body: String) async throws -> String {
        guard let url = URL(string: urlString) else { throw BusError.upstream("公交地址无效") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 12
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        req.httpBody = body.data(using: .utf8)
        do {
            let (data, resp) = try await session.data(for: req)
            if let http = resp as? HTTPURLResponse, http.statusCode >= 400 {
                throw BusError.upstream("公交 HTTP \(http.statusCode)")
            }
            return busDecode(data)
        } catch let e as BusError {
            throw e
        } catch {
            throw BusError.upstream("公交网络异常：\(error.localizedDescription)")
        }
    }
}

// MARK: - 车来了 transport

private enum ChelaileTransport {
    static let baseURL = "https://web.chelaile.net.cn/api"
    static let signSalt = "qwihrnbtmj"
    static let keyWX = "FF32AE65FBFD19414EAAFF6291A54B42"   // ASCII 32 字节 → AES-256
    static let keyH5 = "422556651C7F7B2B5C266EED06068230"
    static let ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        + " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781"
        + "(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF"
    static let referer = "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html"

    static var headers: [String: String] {
        ["User-Agent": ua, "Referer": referer, "xweb_xhr": "1",
         "Accept": "*/*", "Accept-Language": "zh-CN,zh;q=0.9"]
    }

    /// 固定参数，顺序与 Android base() 逐条一致，签名依赖这个顺序。
    static func baseParams() -> BusApiParams {
        var p = BusApiParams()
        p.add("s", "h5")
        p.add("wxs", "wx_app")
        p.add("sign", "1")
        p.add("h5RealData", "1")
        p.add("v", "3.11.28")
        p.add("src", "weixinapp_cx")
        p.add("ctm_mp", "mp_wx")
        p.add("vc", "2")
        p.add("favoriteGray", "1")
        p.add("gpstype", "wgs")
        p.add("geo_type", "wgs")
        p.add("scene", "1256")
        return p
    }

    static func sign(_ p: BusApiParams) -> String {
        var sb = ""
        for (k, v) in p.pairs {
            if !sb.isEmpty { sb += "&" }
            sb += "\"\(k)\"=\"\(v)\""
        }
        sb += signSalt
        return busMD5(sb)
    }

    static func call(_ path: String, _ p: BusApiParams) async throws -> [String: Any] {
        let sig = sign(p)
        var parts: [String] = []
        for (k, v) in p.pairs { parts.append("\(busFormEscape(k))=\(busFormEscape(v))") }
        parts.append("\(busFormEscape("cryptoSign"))=\(busFormEscape(sig))")
        let raw = try await BusApiNet.get(baseURL + path + "?" + parts.joined(separator: "&"),
                                          headers: headers)
        return try busChelaileExtract(raw)
    }

    /// 明文接口（城市列表）：不在 /api 下、不签名。
    static func plainGetData(_ urlString: String) async throws -> [String: Any] {
        let raw = try await BusApiNet.get(urlString, headers: headers)
        guard let body = busExtractJSONBody(raw), let env = busParseJSON(body) else {
            throw BusError.upstream("车来了响应异常")
        }
        return busObj(env["data"]) ?? env
    }

    // MARK: 城市
    static func cities() async throws -> [BusCity] {
        var parts: [String] = []
        for (k, v) in baseParams().pairs { parts.append("\(busFormEscape(k))=\(busFormEscape(v))") }
        let data = try await plainGetData(
            "https://web.chelaile.net.cn/wwd/ncitylist?" + parts.joined(separator: "&"))
        var out: [BusCity] = []
        guard let arr = busArr(data["cityList"]) else { return out }
        for item in arr {
            guard let c = busObj(item) else { continue }
            let name = busStr(c, "cityName")
            let id = busStr(c, "cityId")
            if name.isEmpty || id.isEmpty { continue }
            var city = BusCity()
            city.id = id
            city.name = name
            out.append(city)
        }
        return out
    }

    // MARK: 附近站点
    static func nearby(cityId: String, coord: BusCoordinate, limit: Int) async throws -> [BusStation] {
        var p = baseParams()
        p.add("cityId", cityId)
        p.add("localCityId", "undefined")
        p.add("lat", busFmt(coord.lat))
        p.add("lng", busFmt(coord.lng))
        p.add("geo_lat", busFmt(coord.lat))
        p.add("geo_lng", busFmt(coord.lng))
        p.add("type", "5")
        p.add("permission", "0")
        let data = try await call("/bus/stop!encryptedHomePage.action", p)
        var out: [BusStation] = []
        guard let arr = busArr(data["nearSts"]) else { return out }
        for item in arr {
            if out.count >= limit { break }
            guard let s = busObj(item) else { continue }
            var st = BusStation()
            st.id = busStr(s, "sId")
            st.name = busStr(s, "sn")
            st.physicalId = busStr(s, "physicalStId")
            st.namesakeId = busStr(s, "namesakeStId")
            st.lat = busDouble(s, "lat")
            st.lng = busDouble(s, "lng")
            st.distanceMeters = busHas(s, "distance") ? busInt(s, "distance") : -1
            readArrivals(busArr(s["lines"]), into: &st.arrivals)
            if st.name.isEmpty { continue }
            out.append(st)
        }
        return out
    }

    // MARK: 站点详情
    static func stopDetail(cityId: String, station: BusStation,
                           coord: BusCoordinate) async throws -> BusStation {
        var p = baseParams()
        p.add("cityId", cityId)
        p.add("localCityId", cityId)
        p.add("physicalStId", station.physicalId)
        p.add("namesakeStId", station.namesakeId)
        p.add("firstLineId", "")
        p.add("stationId", station.id)
        let la = coord.lat > 0 ? busFmt(coord.lat) : ""
        let ln = coord.lng > 0 ? busFmt(coord.lng) : ""
        p.add("lat", la)
        p.add("lng", ln)
        p.add("geo_lat", la)
        p.add("geo_lng", ln)
        p.add("actionState", "1")
        p.add("permission", "0")
        let data = try await call("/bus/stop!encryptedPhyStnDetail.action", p)

        var out = BusStation()
        out.id = station.id
        out.physicalId = station.physicalId
        out.namesakeId = station.namesakeId
        // 站点坐标从响应取，绝不拿用户坐标冒充（否则回填距离会变成 0）
        var merged: [BusArrival] = []
        var seenPlatform = Set<String>()
        if let list = busArr(data["stationList"]) {
            for item in list {
                guard let s = busObj(item) else { continue }
                if out.name.isEmpty { out.name = busStr(s, "sn") }
                if out.id.isEmpty { out.id = busStr(s, "sId") }
                if out.physicalId.isEmpty { out.physicalId = busStr(s, "physicalStId") }
                if out.namesakeId.isEmpty { out.namesakeId = busStr(s, "namesakeStId") }
                if out.lat == 0.0 { out.lat = busDouble(s, "lat") }
                if out.lng == 0.0 { out.lng = busDouble(s, "lng") }
                if out.distanceMeters < 0 && busHas(s, "distance") {
                    out.distanceMeters = busInt(s, "distance")
                }
                let pid = busStr(s, "physicalStId")
                if !pid.isEmpty && !seenPlatform.contains(pid) {
                    seenPlatform.insert(pid)
                    var pf = BusPlatform()
                    pf.id = pid
                    pf.name = busStr(s, "sn")
                    out.platforms.append(pf)
                }
                readArrivals(busArr(s["lines"]), into: &merged)
            }
        }
        out.arrivals = busDedupeSort(merged)
        return out
    }

    /// 解析 lines[] 的 stnStates：第一辆进 minutes/stopsAway，第二辆进 nextMinutes/nextStopsAway。
    /// 两个数来自同一份数组，缺哪个字段就保持 -1。
    static func readArrivals(_ lines: [Any]?, into out: inout [BusArrival]) {
        guard let lines = lines else { return }
        for item in lines {
            guard let li = busObj(item), let lo = busObj(li["line"]) else { continue }
            if busInt(lo, "subwayV2", 0) == 1 { continue }
            var a = BusArrival()
            a.lineId = busStr(lo, "lineId")
            a.lineName = busStr(lo, "name")
            if a.lineName.isEmpty { a.lineName = busStr(lo, "lineNo") }
            a.direction = busInt(lo, "direction", 0)
            a.startName = busStr(lo, "startSn")
            a.endName = busStr(lo, "endSn")
            a.status = busStr(lo, "shortDesc")
            if a.status.isEmpty { a.status = busStr(lo, "nextOperationTimeDesc") }
            // 只有名字、没有 lineId 的占位线路不进列表
            if a.lineName.isEmpty || a.lineId.isEmpty { continue }
            if let target = busObj(li["targetStation"]) {
                a.stopId = busStr(target, "sId")
                a.orderInLine = busEffectiveOrder(busInt(target, "order", 0), a.stopId)
            }
            let states = busArr(li["stnStates"])
            a.realtime = (states?.count ?? 0) > 0
            var cand: [BusApiCand] = []
            if let states = states {
                for sItem in states {
                    guard let b = busObj(sItem) else { continue }
                    let c = BusApiCand(stops: busInt(b, "value"),
                                       secs: busInt(b, "travelTime"),
                                       dist: busInt(b, "distanceToDest"),
                                       state: busInt(b, "state", 0))
                    if c.stops < 0 && c.secs < 0 && c.state != 1 { continue }
                    cand.append(c)
                }
            }
            cand.sort { x, y in
                let sx = x.stops >= 0 ? x.stops : 9999
                let sy = y.stops >= 0 ? y.stops : 9999
                if sx != sy { return sx < sy }
                let ex = x.secs < 0 ? 99999 : x.secs
                let ey = y.secs < 0 ? 99999 : y.secs
                return ex < ey
            }
            a.pending = cand.isEmpty && a.orderInLine > 0
            if let f = cand.first {
                a.stopsAway = f.stops
                a.distanceMeters = f.dist
                a.minutes = BusText.minutesOfSecs(f.secs)
                a.arrived = busArrivedOf(f.secs, f.state, f.stops)
                if cand.count > 1 {
                    let s2 = cand[1]
                    a.nextStopsAway = s2.stops
                    a.nextMinutes = BusText.minutesOfSecs(s2.secs)
                }
            }
            out.append(a)
        }
    }

    // MARK: 搜索
    static func searchResult(cityId: String, keyword: String,
                             coord: BusCoordinate, more: Bool) async throws -> [String: Any]? {
        var p = baseParams()
        p.add("cityId", cityId)
        p.add("localCityId", cityId)
        p.add("key", keyword)
        p.add("supportPhyStn", "true")
        if more { p.add("more", "true") }
        if coord.lat > 0 && coord.lng > 0 {
            p.add("lat", busFmt(coord.lat))
            p.add("lng", busFmt(coord.lng))
            p.add("geo_lat", busFmt(coord.lat))
            p.add("geo_lng", busFmt(coord.lng))
        }
        let env = try await call("/bus/query!nSearch.action", p)
        return busObj(env["result"])
    }

    static func readLines(_ result: [String: Any]) -> [BusLine] {
        var uniq: [String: BusLine] = [:]
        var order: [String] = []
        guard let lines = busArr(result["lines"]) else { return [] }
        for item in lines {
            guard let l = busObj(item) else { continue }
            if busInt(l, "subwayV2", 0) == 1 { continue }
            var line = BusLine()
            line.lineId = busStr(l, "lineId")
            line.name = busStr(l, "name")
            if line.name.isEmpty { line.name = busStr(l, "lineNo") }
            line.lineNo = busStr(l, "lineNo")
            line.direction = busInt(l, "direction", 0)
            line.startName = busStr(l, "startSn")
            line.endName = busStr(l, "endSn")
            line.firstTime = busStr(l, "firstTime")
            line.lastTime = busStr(l, "lastTime")
            if line.lineId.isEmpty || line.name.isEmpty { continue }
            // 正反方向合成一行，留正方向（direction=0）
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

    static func readStations(_ result: [String: Any]) -> [BusStation] {
        var out: [BusStation] = []
        guard let arr = busArr(result["stations"]) else { return out }
        for item in arr {
            guard let s = busObj(item) else { continue }
            if busInt(s, "subwayV2", 0) == 1 { continue }
            var st = BusStation()
            st.name = busStr(s, "sn").trimmingCharacters(in: .whitespacesAndNewlines)
            st.id = busStr(s, "sId")
            st.physicalId = busStr(s, "physicalStId")
            st.namesakeId = busStr(s, "namesakeStId")
            st.lat = busDouble(s, "lat")
            st.lng = busDouble(s, "lng")
            st.distanceMeters = busHas(s, "distance") ? busInt(s, "distance") : -1
            if st.name.isEmpty || st.id.isEmpty { continue }
            out.append(st)
        }
        return out
    }

    static func search(cityId: String, keyword: String,
                       coord: BusCoordinate) async throws -> BusSearchHit {
        var hit = BusSearchHit()
        guard let result = try await searchResult(cityId: cityId, keyword: keyword,
                                                  coord: coord, more: false) else { return hit }
        hit.lineCount = busInt(result, "lineCount", 0)
        hit.stationCount = busInt(result, "stationCount", 0)
        hit.stations = readStations(result)
        hit.lines = readLines(result)
        return hit
    }

    static func searchMore(cityId: String, keyword: String,
                           coord: BusCoordinate) async throws -> [BusLine] {
        guard let result = try await searchResult(cityId: cityId, keyword: keyword,
                                                  coord: coord, more: true) else { return [] }
        return readLines(result)
    }

    // MARK: 线路详情
    static func lineDetail(cityId: String, lineId: String, targetOrder: Int,
                           coord: BusCoordinate) async throws -> BusLineDetail {
        var p = baseParams()
        p.add("cityId", cityId)
        p.add("localCityId", cityId)
        p.add("lineId", lineId)
        let la = coord.lat > 0 ? busFmt(coord.lat) : ""
        let ln = coord.lng > 0 ? busFmt(coord.lng) : ""
        p.add("lat", la)
        p.add("lng", ln)
        p.add("geo_lat", la)
        p.add("geo_lng", ln)
        let data = try await call("/bus/line!encryptedLineDetail.action", p)

        var d = BusLineDetail()
        let lo = busObj(data["line"])
        if let lo = lo {
            d.line.lineId = lineId
            d.line.name = busStr(lo, "name")
            if d.line.name.isEmpty { d.line.name = busStr(lo, "lineNo") }
            d.line.lineNo = busStr(lo, "lineNo")
            d.line.direction = busInt(lo, "direction", 0)
            d.line.startName = busStr(lo, "startSn")
            d.line.endName = busStr(lo, "endSn")
            d.line.firstTime = busStr(lo, "firstTime")
            d.line.lastTime = busStr(lo, "lastTime")
            d.line.price = busStr(lo, "price")
            d.line.stationsNum = busInt(lo, "stationsNum", 0)
        }
        d.status = busStr(data, "depDesc")
        d.nextDeparture = busStr(data, "preArrivalTime")
        if let lo = lo, d.status.isEmpty {
            d.status = busStr(lo, "shortDesc")
            if d.status.isEmpty { d.status = busStr(lo, "nextOperationTimeDesc") }
        }
        if let others = busArr(data["otherlines"]), let rev = busObj(others.first) {
            d.reverseId = busStr(rev, "lineId")
            d.reverseStart = busStr(rev, "startSn")
            d.reverseEnd = busStr(rev, "endSn")
        }

        // 站序一律用数组位置（上游虚拟站组的 order=999 不可信）
        if let sts = busArr(data["stations"]) {
            for (i, item) in sts.enumerated() {
                guard let s = busObj(item) else { continue }
                var stop = BusStop()
                stop.id = busStr(s, "sId")
                stop.physicalId = busStr(s, "physicalStId")
                stop.name = busStr(s, "sn")
                stop.order = i + 1
                stop.orderLabel = "\(i + 1)"
                stop.lat = busDouble(s, "wgsLat")
                stop.lng = busDouble(s, "wgsLng")
                d.stops.append(stop)
            }
        }

        if let buses = busArr(data["buses"]) {
            for item in buses {
                guard let b = busObj(item) else { continue }
                let order = busInt(b, "order", 0)
                if order <= 0 { continue }
                var v = BusVehicle()
                v.id = busStr(b, "busId")
                v.order = order
                let plate = BusText.sanitizePlate(busStr(b, "licence"))
                v.plate = plate.isEmpty ? BusText.sanitizePlate(v.id) : plate
                v.speedKmh = busDouble(b, "speed")
                v.nextStopName = busStr(b, "nextStationName").trimmingCharacters(in: .whitespacesAndNewlines)
                // 这里上游给的是「到终点」的距离，不能当「距本站」；距离只认逐车 busDetail 的值
                v.distanceMeters = -1
                d.vehicles.append(v)

                // 把车标到站表：车在上一站与下一站之间的比例
                guard let cur = d.stops.first(where: { $0.order == order }) else { continue }
                let prev = d.stops.first(where: { $0.order == order - 1 })
                let blat = busDouble(b, "lat"), blng = busDouble(b, "lng")
                var ratio = 1.0
                if let prev = prev, blat != 0, blng != 0,
                   prev.lat != 0, prev.lng != 0, cur.lat != 0, cur.lng != 0 {
                    let seg = busDistanceMeters(prev.lat, prev.lng, cur.lat, cur.lng)
                    let toPrev = busDistanceMeters(prev.lat, prev.lng, blat, blng)
                    if seg > 1 { ratio = max(0.0, min(1.0, toPrev / seg)) }
                }
                if let ci = d.stops.firstIndex(where: { $0.order == order }),
                   ratio > d.stops[ci].busRatio {
                    d.stops[ci].busRatio = ratio
                }
                d.busCount += 1
            }
            for i in d.stops.indices where d.stops[i].busRatio >= 0 {
                d.stops[i].busHere = d.stops[i].busRatio >= 0.9
            }
        }

        // 候车站：按 targetOrder 认站；没指定就按离用户最近；再兜底第一站
        var wait: BusStop?
        if targetOrder > 0 { wait = d.stops.first { $0.order == targetOrder } }
        var nearest: BusStop?
        if coord.lat > 0 && coord.lng > 0 {
            var best = Double.greatestFiniteMagnitude
            for s in d.stops {
                if s.lat == 0 && s.lng == 0 { continue }
                let m = busDistanceMeters(coord.lat, coord.lng, s.lat, s.lng)
                if m < best { best = m; nearest = s }
            }
        }
        if let nearest = nearest,
           let ni = d.stops.firstIndex(where: { $0.order == nearest.order }) {
            d.stops[ni].nearest = true
        }
        if wait == nil { wait = nearest }
        if wait == nil { wait = d.stops.first }
        if let wait = wait {
            d.waitingStopName = wait.name
            d.waitingOrder = wait.order
            d.waitingStopId = wait.id
            // 候车站定下来后再问一次 busDetail，回填 eta 大字与逐车明细
            d = await fillEta(d, cityId: cityId, lineId: lineId, wait: wait, coord: coord)
        }
        d.vehicles.sort { $0.order < $1.order }
        return d
    }

    // MARK: 时刻表
    static func timetable(cityId: String, line: BusLine, direction: Int) async throws -> BusTimetable {
        var p = baseParams()
        p.add("cityId", cityId)
        p.add("lineId", line.lineId)
        p.add("lineNo", line.lineNo)
        p.add("direction", String(direction))
        let data = try await call("/bus/line!preStartTimetableNew.action", p)

        var t = BusTimetable()
        if let lo = busObj(data["line"]) {
            t.lineName = busStr(lo, "name")
            t.startName = busStr(lo, "startSn")
            t.endName = busStr(lo, "endSn")
        }
        let todayBlock = busObj(data["timetable"])
        let yBlock = busObj(data["yesterdayTimetable"])
        let bBlock = busObj(data["beforeYesterdayTimetable"])
        let todaySegs = readScheduled(todayBlock)
        let ySegs = readScheduled(yBlock)
        let bSegs = readScheduled(bBlock)

        // 优先级：今日预测 → 昨日实际 → 前日实际；historyTitle 写真实口径。
        if !todaySegs.isEmpty {
            t.depTitle = busStr(todayBlock, "depTitle")
            t.tip = busStr(todayBlock, "tip")
            t.today = todaySegs
        }
        if !ySegs.isEmpty {
            t.history = ySegs
            t.historyTitle = "昨日实际班次"
            if t.depTitle.isEmpty { t.depTitle = busStr(yBlock, "depTitle") }
            if t.tip.isEmpty { t.tip = busStr(yBlock, "tip") }
        } else if !bSegs.isEmpty {
            t.history = bSegs
            t.historyTitle = "前日实际班次"
            if t.depTitle.isEmpty { t.depTitle = busStr(bBlock, "depTitle") }
            if t.tip.isEmpty { t.tip = "本时刻表取自前日的实际发车记录，仅供参考" }
        }

        var todayTimes: [String] = []
        for seg in t.today { todayTimes += seg.times }
        var histTimes: [String] = []
        for seg in t.history { histTimes += seg.times }
        busSortByClock(&todayTimes)
        busSortByClock(&histTimes)
        let now = busNowHHMM()
        if let next = busFirstAfter(todayTimes, now) {
            t.nextDep = next
            t.nextFrom = "今日预测"
        } else if let next = busFirstAfter(histTimes, now) {
            t.nextDep = next
            t.nextFrom = t.historyTitle == "前日实际班次" ? "前日实际" : "昨日实际"
        } else if let first = histTimes.first {
            t.nextDep = first
            t.nextFrom = "明日首班"
        }
        return t
    }

    static func readScheduled(_ block: [String: Any]?) -> [BusTtSegment] {
        guard let block = block else { return [] }
        var out: [BusTtSegment] = []
        guard let segs = busArr(block["timetable"]) else { return out }
        for item in segs {
            guard let s = busObj(item) else { continue }
            let from = busStr(s, "fTime")
            let to = busStr(s, "eTime")
            let tips = busStr(s, "depIntervalTips")
            let interval = busInt(s, "depInterval")
            var times: [String] = []
            if let sch = busArr(s["schedules"]) {
                for e in sch {
                    var time = ""
                    if let o = busObj(e) { time = busStr(o, "time") }
                    else if let str = e as? String { time = str }
                    if !time.isEmpty { times.append(time.trimmingCharacters(in: .whitespacesAndNewlines)) }
                }
            }
            if times.isEmpty, let plain = busArr(s["times"]) {
                for e in plain {
                    if let str = e as? String, !str.isEmpty {
                        times.append(str.trimmingCharacters(in: .whitespacesAndNewlines))
                    }
                }
            }
            busSortByClock(&times)
            // 只留真有班次的分组，空壳不渲染
            if times.isEmpty { continue }
            var seg = BusTtSegment()
            seg.title = busTtTitle(from: from, to: to, tips: tips,
                                   interval: interval, times: times)
            seg.times = times
            out.append(seg)
        }
        return out
    }

    // MARK: 预测（大字 / 小字 / 下辆 同一次 busDetail）
    static func predict(cityId: String, lineId: String, order: Int, stopId: String,
                        coord: BusCoordinate) async -> BusApiPred? {
        guard let bd = try? await busDetail(cityId: cityId, lineId: lineId, order: order,
                                            stopId: stopId, coord: coord) else { return nil }
        return bd.pred
    }

    static func busDetail(cityId: String, lineId: String, order: Int, stopId: String,
                          coord: BusCoordinate) async throws -> BusApiBusDetail {
        var p = baseParams()
        p.add("cshow", "busDetail")
        p.add("specail", "0")
        p.add("specialType", "undefined")
        p.add("cityId", cityId)
        p.add("localCityId", cityId)
        p.add("lineId", lineId)
        p.add("targetOrder", String(order))
        p.add("specialTargetOrder", String(order))
        p.add("stationId", stopId)
        let la = coord.lat > 0 ? busFmt(coord.lat) : ""
        let ln = coord.lng > 0 ? busFmt(coord.lng) : ""
        p.add("lat", la)
        p.add("lng", ln)
        p.add("geo_lat", la)
        p.add("geo_lng", ln)
        p.add("userId", "")
        p.add("h5Id", "")
        p.add("unionId", "")
        p.add("accountId", "")
        p.add("secret", "")
        let data = try await call("/bus/line!encryptedBusDetail.action", p)

        var bd = BusApiBusDetail()
        var out = bd.pred
        out.depDesc = busStr(data, "depDesc")
        out.preArrival = busStr(data, "preArrivalTime")
        guard let buses = busArr(data["buses"]) else { return bd }

        var bestSecs = Int.max
        var bestArrived = false
        var nextSecs = Int.max
        var nextStops = -1
        var nextBusId = ""
        let mine = busEffectiveOrder(order, stopId)
        for item in buses {
            guard let b = busObj(item),
                  let travels = busArr(b["travels"]),
                  let tr = busObj(travels.first) else { continue }
            let secs = busInt(tr, "travelTime")
            let arrived = secs == 0
            let bId = busStr(b, "busId")
            if !bId.isEmpty {
                var e = BusApiBusEta()
                e.busId = bId
                e.seconds = secs
                e.tip = busStr(tr, "recommTip")
                e.order = busInt(b, "order", 0)
                let dist = busInt(b, "distanceToWaitStn")
                e.distance = dist > 0 ? dist : -1
                bd.byBus[bId] = e
            }
            if secs < 0 { continue }
            let busOrder = busInt(b, "order", 0)
            let stops = busOrder > 0 ? max(0, mine - busOrder) : -1
            let dist = busInt(b, "distanceToWaitStn")
            if secs < bestSecs {
                if bestSecs != Int.max {
                    nextSecs = bestSecs
                    nextStops = out.stops
                    nextBusId = out.busId
                }
                bestSecs = secs
                bestArrived = arrived
                out.busId = bId
                out.stops = stops
                out.dist = dist > 0 ? dist : -1
            } else if secs < nextSecs {
                nextSecs = secs
                nextStops = stops
                nextBusId = bId
            }
        }
        out.secs = bestSecs == Int.max ? -1 : bestSecs
        out.arrived = bestArrived
        out.nextSecs = nextSecs == Int.max ? -1 : nextSecs
        out.nextStops = nextStops
        out.nextBusId = nextBusId
        bd.pred = out
        return bd
    }

    /// 补预测：聚合预测 + 逐车明细，全部来自同一次 busDetail 响应；返回填好后的副本
    /// （避免 async 函数使用 inout 参数）。
    static func fillEta(_ d: BusLineDetail, cityId: String, lineId: String,
                         wait: BusStop, coord: BusCoordinate) async -> BusLineDetail {
        var out = d
        guard let bd = try? await busDetail(cityId: cityId, lineId: lineId, order: wait.order,
                                            stopId: wait.id, coord: coord) else { return out }
        let pr = bd.pred
        if pr.secs >= 0 {
            out.etaArrived = pr.arrived
            out.etaMinutes = BusText.minutesOfSecs(pr.secs)
            out.etaStops = pr.stops
            out.etaDistanceMeters = pr.dist
            out.etaBusId = pr.busId
            out.etaNextBusId = pr.nextBusId
        } else if !pr.preArrival.isEmpty {
            out.estArrival = busHHMM(pr.preArrival)
        }
        if out.status.isEmpty {
            out.status = pr.depDesc
            if out.nextDeparture.isEmpty { out.nextDeparture = pr.preArrival }
        }
        for i in out.vehicles.indices {
            guard let e = bd.byBus[out.vehicles[i].id] else { continue }
            out.vehicles[i].etaSeconds = e.seconds
            if e.distance > 0 { out.vehicles[i].distanceMeters = e.distance }
            if out.vehicles[i].order == wait.order &&
                ((e.seconds >= 0 && e.seconds <= 20) || e.tip.contains("已到站")) {
                out.vehicles[i].isArrived = true
            }
        }
        return out
    }

    // MARK: 批量补预测
    static func refreshEta(cityId: String, arrivals: [BusArrival],
                           coord: BusCoordinate) async throws -> [BusArrival] {
        var out = arrivals
        for i in out.indices {
            if out[i].orderInLine <= 0 { continue }
            guard let pr = await predict(cityId: cityId, lineId: out[i].lineId,
                                         order: out[i].orderInLine, stopId: out[i].stopId,
                                         coord: coord) else {
                out[i].pending = false
                out[i].refreshed = true
                continue
            }
            if pr.secs >= 0 {
                out[i].busId = pr.busId
                out[i].minutes = BusText.minutesOfSecs(pr.secs)
                out[i].stopsAway = pr.stops
                out[i].distanceMeters = pr.dist
                out[i].realtime = true
                if pr.arrived { out[i].arrived = true }
                out[i].nextMinutes = BusText.minutesOfSecs(pr.nextSecs)
                out[i].nextStopsAway = pr.nextSecs >= 0 ? pr.nextStops : -1
                out[i].nextBusId = pr.nextBusId
            } else if !pr.depDesc.isEmpty {
                out[i].status = pr.depDesc
            }
            out[i].pending = false
            out[i].refreshed = true
        }
        return out
    }
}

/// 车来了响应解包：剥 **YGKJ 包裹 → jsonr.data.encryptResult → AES 解密 → 业务 JSON。
private func busChelaileExtract(_ raw: String) throws -> [String: Any] {
    guard let body = busExtractJSONBody(raw), let env = busParseJSON(body) else {
        throw BusError.upstream("车来了响应不是 JSON")
    }
    let data: Any?
    if let jsonr = busObj(env["jsonr"]) { data = jsonr["data"] }
    else { data = env["data"] }
    if let d = busObj(data) {
        let enc = busStr(d, "encryptResult")
        if !enc.isEmpty, let plain = busAESDecrypt(enc), let obj = busParseJSON(plain) {
            return obj
        }
        return d
    }
    if let s = data as? String {
        if let obj = busParseJSON(s) { return obj }
        if let plain = busAESDecrypt(s), let obj = busParseJSON(plain) { return obj }
    }
    return busObj(env["jsonr"]) ?? env
}

// MARK: - 掌上公交 transport

private enum MygolbsTransport {
    static let api = "https://h5.mygolbs.com/ApiData.do"
    static let origin = "https://h5.mygolbs.com"
    static let ua = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
        + " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    static let defaultCity = "唐山市"

    static var headers: [String: String] {
        ["Content-Type": "application/x-www-form-urlencoded",
         "Origin": origin, "Referer": origin + "/",
         "User-Agent": ua, "Accept": "*/*"]
    }

    static func call(_ p: BusApiParams) async throws -> [String: Any] {
        var parts: [String] = []
        for (k, v) in p.pairs { parts.append("\(busFormEscape(k))=\(busFormEscape(v))") }
        let raw = try await BusApiNet.post(api, headers: headers, body: parts.joined(separator: "&"))
        guard let body = busExtractJSONBody(raw), let j = busParseJSON(body) else {
            throw BusError.upstream("掌上公交响应异常")
        }
        let status = busStr(j, "status")
        if status != "1" {
            throw BusError.upstream("掌上公交 status=\(status) msg=\(busStr(j, "msg"))")
        }
        return j
    }

    static func single(_ cmd: String) -> BusApiParams {
        var p = BusApiParams()
        p.add("CMD", cmd)
        return p
    }

    // MARK: id / 城市 / 文案
    static func city(_ cityId: String) -> String {
        let v = cityId.trimmingCharacters(in: .whitespaces)
        if v.isEmpty { return defaultCity }
        if v.hasSuffix("市") || v.hasSuffix("县") || v.hasSuffix("区") { return v }
        let digits = v.unicodeScalars.allSatisfy { $0.value >= 48 && $0.value <= 57 }
        if digits { return defaultCity }
        return v + "市"
    }

    static func shortCityName(_ name: String) -> String {
        let v = name.trimmingCharacters(in: .whitespaces)
        if v.hasSuffix("市") && v.count > 2 { return String(v.dropLast()) }
        return v
    }

    static func lineId(_ city: String, _ lineName: String, _ dir: Int) -> String {
        "mg|\(city)|\(lineName)|\(dir == 0 ? "1" : "2")"
    }

    static func parseLineId(_ s: String) -> (String, String, String)? {
        guard s.hasPrefix("mg|") else { return nil }
        let seg = String(s.dropFirst(3)).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard seg.count == 3, !seg[0].isEmpty, !seg[1].isEmpty else { return nil }
        return (seg[0], seg[1], seg[2])
    }

    static func stopId(_ city: String, _ name: String) -> String { "mg|\(city)|\(name)" }

    static func stationNameOf(_ s: String) -> String {
        guard s.hasPrefix("mg|") else { return s.trimmingCharacters(in: .whitespaces) }
        let seg = String(s.dropFirst(3)).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard seg.count == 2 else { return "" }
        return seg[1].trimmingCharacters(in: .whitespaces)
    }

    static func newStation(_ city: String, _ name: String) -> BusStation {
        var s = BusStation()
        s.name = name
        s.id = stopId(city, name)
        s.physicalId = s.id
        s.namesakeId = s.id
        return s
    }

    static func cleanNearText(_ raw: String) -> String {
        var v = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var cut = v.firstIndex(of: "[")
        if let c2 = v.firstIndex(of: "【") { if cut == nil || c2 < cut! { cut = c2 } }
        if let c = cut { v = String(v[v.startIndex..<c]) }
        v = v.trimmingCharacters(in: .whitespacesAndNewlines)
        if v.hasSuffix("到达") { v = String(v.dropLast(2)).trimmingCharacters(in: .whitespaces) }
        return v
    }

    static func stopsOf(_ text: String) -> Int {
        if text.isEmpty { return -1 }
        if text.contains("即将到站") || text.contains("已到站") { return 0 }
        guard let i = text.firstIndex(of: "站") else { return -1 }
        return Int(String(text[text.startIndex..<i]).trimmingCharacters(in: .whitespaces)) ?? -1
    }

    static func minutesOfText(_ text: String) -> Int {
        let v = text.trimmingCharacters(in: .whitespaces)
        if v.isEmpty { return -1 }
        if v.contains("小于1分钟") || v.contains("小于一分钟") { return 0 }
        var total = 0
        var found = false
        if let h = firstNumberBefore(["小时", "时"], in: v) { total += h * 60; found = true }
        if let m = firstNumberBefore(["分钟", "分"], in: v) { total += m; found = true }
        return found ? total : -1
    }

    private static func firstNumberBefore(_ units: [String], in s: String) -> Int? {
        for u in units {
            guard let r = s.range(of: u) else { continue }
            let head = String(s[s.startIndex..<r.lowerBound])
            var buf = ""
            for ch in head.reversed() {
                let scalars = Array(ch.unicodeScalars)
                let digit = scalars.count == 1 && scalars[0].value >= 48 && scalars[0].value <= 57
                if digit || ch == "." { buf.append(ch) } else { break }
            }
            if let d = Double(String(buf.reversed())) { return Int(d.rounded()) }
        }
        return nil
    }

    static func orderOf(_ o: [String: Any]) -> Int {
        let order = busInt(o, "stationOrder", 0)
        if order > 0 { return order }
        let sid = busStr(o, "stationID").trimmingCharacters(in: .whitespaces)
        if sid.isEmpty { return 0 }
        if let v = Int(sid), v >= 0 { return v + 1 }
        return 0
    }

    static func hasRealOf(_ bean: [String: Any]?) -> Bool {
        guard let bean = bean else { return true }
        if bean["hasReal"] == nil { return true }
        return busInt(bean, "hasReal", 1) != 0
    }

    static func normalizeClock(_ raw: String) -> String {
        let v = raw.trimmingCharacters(in: .whitespaces)
        guard let i = v.firstIndex(of: ":"), i > v.startIndex else { return "" }
        let h = Int(v[v.startIndex..<i].trimmingCharacters(in: .whitespaces)) ?? -1
        let m = Int(v[v.index(after: i)...].trimmingCharacters(in: .whitespaces)) ?? -1
        if h < 0 || h > 23 || m < 0 || m > 59 { return "" }
        return (h < 10 ? "0" : "") + "\(h):" + (m < 10 ? "0" : "") + "\(m)"
    }

    static func splitGroup(_ group: String) -> (String, String) {
        let v = group.trimmingCharacters(in: .whitespaces)
        if v.isEmpty { return ("", "") }
        var sep = v.firstIndex(of: "~")
        if sep == nil { sep = v.firstIndex(of: "～") }
        guard let i = sep else { return (normalizeClock(v), "") }
        return (normalizeClock(String(v[v.startIndex..<i])),
                normalizeClock(String(v[v.index(after: i)...])))
    }

    static func firstArray(_ j: [String: Any], _ keys: [String]) -> [Any]? {
        for k in keys { if let a = busArr(j[k]) { return a } }
        return nil
    }

    // MARK: 坐标系（GCJ-02 <-> WGS-84）
    private static let A = 6378245.0
    private static let EE = 0.00669342162296594323

    private static func outOfChina(_ lat: Double, _ lng: Double) -> Bool {
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private static func transformLat(_ x: Double, _ y: Double) -> Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * .pi) + 20.0 * sin(2.0 * x * .pi)) * 2.0 / 3.0
        r += (20.0 * sin(y * .pi) + 40.0 * sin(y / 3.0 * .pi)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * .pi) + 320 * sin(y * .pi / 30.0)) * 2.0 / 3.0
        return r
    }

    private static func transformLng(_ x: Double, _ y: Double) -> Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * .pi) + 20.0 * sin(2.0 * x * .pi)) * 2.0 / 3.0
        r += (20.0 * sin(x * .pi) + 40.0 * sin(x / 3.0 * .pi)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * .pi) + 300.0 * sin(x / 30.0 * .pi)) * 2.0 / 3.0
        return r
    }

    static func wgs2gcj(_ lat: Double, _ lng: Double) -> (Double, Double) {
        if outOfChina(lat, lng) { return (lat, lng) }
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        let radLat = lat / 180.0 * .pi
        let magic = 1 - EE * sin(radLat) * sin(radLat)
        let sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * .pi)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * .pi)
        return (lat + dLat, lng + dLng)
    }

    static func gcj2wgs(_ lat: Double, _ lng: Double) -> (Double, Double) {
        if outOfChina(lat, lng) { return (lat, lng) }
        let g = wgs2gcj(lat, lng)
        return (lat - (g.0 - lat), lng - (g.1 - lng))
    }

    // MARK: 城市
    static func cities() async throws -> [BusCity] {
        let j = try await call(single("101"))
        var out: [BusCity] = []
        if let arr = firstArray(j, ["data", "list"]) {
            for item in arr {
                guard let c = busObj(item) else { continue }
                var name = busStr(c, "cityname")
                if name.isEmpty { name = busStr(c, "cityName") }
                if name.isEmpty { name = busStr(c, "CITYNAME") }
                name = name.trimmingCharacters(in: .whitespacesAndNewlines)
                if name.isEmpty { continue }
                var city = BusCity()
                city.id = name
                city.name = shortCityName(name)
                out.append(city)
            }
        }
        if out.isEmpty {
            var city = BusCity()
            city.id = defaultCity
            city.name = "唐山"
            out.append(city)
        }
        return out
    }

    // MARK: CMD=105 站点线路
    static func stopLines(city: String, stationName: String) async throws -> BusStation {
        var p = BusApiParams()
        p.add("CMD", "105")
        p.add("CITYNAME", city)
        p.add("STATIONNAME", stationName)
        p.add("MYLAT", "")
        p.add("MYLNG", "")
        p.add("CITYKEY", "")
        let j = try await call(p)
        var s = newStation(city, stationName)
        guard let arr = busArr(j["data"]) else { return s }
        for item in arr {
            guard let o = busObj(item) else { continue }
            var lineName = busStr(o, "lineName")
            if lineName.isEmpty { lineName = busStr(o, "routeName") }
            lineName = lineName.trimmingCharacters(in: .whitespacesAndNewlines)
            if lineName.isEmpty { continue }
            let bean = busObj(o["routeBean"])
            if let bean = bean, busStr(bean, "routeType") == "3" { continue }
            if lineName.contains("地铁") { continue }
            var up = busStr(o, "upperOrDown")
            if up.isEmpty { up = "1" }
            let dir = up == "1" ? 0 : 1

            var a = BusArrival()
            a.lineId = MygolbsTransport.lineId(city, lineName, dir)
            a.lineName = lineName
            a.direction = dir
            a.startName = busStr(o, "from")
            a.endName = busStr(o, "to")
            a.stopId = s.id
            a.orderInLine = orderOf(o)
            a.realtime = hasRealOf(bean)
            let nearText = cleanNearText(busStr(o, "neartext"))
            let nearTime = busStr(o, "neartime").trimmingCharacters(in: .whitespacesAndNewlines)
            a.status = nearText
            a.stopsAway = stopsOf(nearText)
            a.minutes = minutesOfText(nearTime)
            if nearText.contains("已到站") || nearText.contains("正在进站") {
                a.arrived = true
                if a.minutes < 0 { a.minutes = 0 }
            } else if nearText.contains("即将到站") && a.minutes < 0 {
                a.minutes = 0
            }
            a.pending = a.realtime && !a.arrived && a.minutes < 0 && a.orderInLine > 0
            s.arrivals.append(a)
        }
        return s
    }

    // MARK: 附近站点（CMD=106 探站 + 逐站 CMD=105 补线路）
    static func nearby(cityId: String, coord: BusCoordinate, limit: Int) async throws -> [BusStation] {
        let city = busMGCity(cityId)
        let g = wgs2gcj(coord.lat, coord.lng)
        var p = BusApiParams()
        p.add("CMD", "106")
        p.add("CITYNAME", city)
        p.add("LAT", busFmt(g.0))
        p.add("LNG", busFmt(g.1))
        p.add("MYLAT", busFmt(g.0))
        p.add("MYLNG", busFmt(g.1))
        let j = try await call(p)
        var out: [BusStation] = []
        guard let arr = busArr(j["data"]) else { return out }
        for item in arr {
            if out.count >= limit { break }
            guard let o = busObj(item) else { continue }
            let name = busStr(o, "name").trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty { continue }
            var s = newStation(city, name)
            let w = gcj2wgs(busDouble(o, "lat"), busDouble(o, "lon"))
            s.lat = w.0
            s.lng = w.1
            let dis = busInt(o, "dis")
            if dis > 0 { s.distanceMeters = dis }
            else if s.lat != 0 && s.lng != 0 {
                s.distanceMeters = Int(busDistanceMeters(coord.lat, coord.lng, s.lat, s.lng).rounded())
            }
            out.append(s)
        }
        // 这一屏才补线路（顺序请求，避免一次轰太多请求被上游掐掉）
        var filled: [BusStation] = []
        for s in out {
            var item = s
            if let full = try? await stopLines(city: city, stationName: s.name) {
                item.arrivals = full.arrivals
            }
            filled.append(item)
        }
        return filled
    }

    static func stopDetail(cityId: String, station: BusStation,
                           coord: BusCoordinate) async throws -> BusStation {
        let city = busMGCity(cityId)
        let name = stationNameOf(station.id)
        if name.isEmpty { throw BusError.upstream("站点缺少站名") }
        var s = try await stopLines(city: city, stationName: name)
        // 站点自己的坐标上游这里不给，别拿用户位置冒充
        s.lat = 0
        s.lng = 0
        return s
    }

    // MARK: CMD=103 站表
    static func route(city: String, lineName: String, dir: Int) async throws -> BusApiRoute {
        var p = BusApiParams()
        p.add("CMD", "103")
        p.add("CITYNAME", city)
        p.add("CITYCODE", "")
        p.add("LINENAME", lineName)
        p.add("DIRECTION", dir == 0 ? "1" : "2")
        p.add("CITYKEY", "")
        let j = try await call(p)
        var info = BusApiRoute()
        info.routeId = busStr(j, "routeId")
        if let fl = busArr(j["firstLast"]), let first = busObj(fl.first) {
            info.firstTime = busStr(first, "first")
            info.lastTime = busStr(first, "last")
        }
        if info.firstTime.isEmpty { info.firstTime = busStr(j, "beginTime") }
        if info.lastTime.isEmpty { info.lastTime = busStr(j, "endTime") }
        if let data = busArr(j["data"]) {
            for (i, item) in data.enumerated() {
                guard let st = busObj(item) else { continue }
                var stop = BusStop()
                var order = busInt(st, "stationOrder", i + 1)
                if order <= 0 { order = i + 1 }
                stop.order = order
                stop.orderLabel = "\(order)"
                stop.name = busStr(st, "stationName").trimmingCharacters(in: .whitespacesAndNewlines)
                let w = gcj2wgs(busDouble(st, "station_lat"), busDouble(st, "station_lon"))
                stop.lat = w.0
                stop.lng = w.1
                stop.id = stopId(city, stop.name)
                stop.physicalId = stop.id
                if stop.name.isEmpty { continue }
                info.stops.append(stop)
            }
        }
        return info
    }

    // MARK: CMD=104 实时
    static func realtime(city: String, lineName: String, dir: Int,
                         order: Int) async throws -> BusApiRt {
        var p = BusApiParams()
        p.add("CMD", "104")
        p.add("CITYNAME", city)
        p.add("CITYCODE", "")
        p.add("LINENAME", lineName)
        p.add("DIRECTION", dir == 0 ? "1" : "2")
        p.add("STATIONORDER", String(order))
        p.add("CITYKEY", "")
        let j = try await call(p)
        var rt = BusApiRt()
        rt.hasReal = busInt(j, "hasReal", 1) != 0
        rt.runState = busInt(j, "runState", 0)
        rt.planTime = busStr(j, "planTime").trimmingCharacters(in: .whitespacesAndNewlines)
        if let buses = busArr(j["list"]) {
            for item in buses {
                guard let b = busObj(item) else { continue }
                var bus = BusApiMgBus()
                bus.number = busStr(b, "busNumber")
                bus.index = busInt(b, "index")
                bus.statusType = busStr(b, "statusType")
                bus.stationName = busStr(b, "stationName")
                let w = gcj2wgs(busDouble(b, "bus_lat"), busDouble(b, "bus_lng"))
                bus.lat = w.0
                bus.lng = w.1
                rt.buses.append(bus)
            }
        }
        if let eta = busArr(j["routeOnStationRTimeInfoList"]) {
            for item in eta {
                guard let e = busObj(item) else { continue }
                let mapBus = busStr(e, "busNumber")
                if !mapBus.isEmpty {
                    let min = busDouble(e, "busToStationTime", -1.0)
                    if min >= 1.0 { rt.etaSecondsByBus[mapBus] = Int(min * 60) }
                    else if min > 0.0 { rt.etaSecondsByBus[mapBus] = 60 }
                    let dm = busInt(e, "busToStationDistance")
                    if dm > 0 { rt.distanceByBus[mapBus] = dm }
                }
                let tips = busStr(e, "busToStationTips")
                let count = busInt(e, "busToStationCount")
                if count < 0 && (tips.contains("非运营") || tips.contains("收班") ||
                                 tips.contains("末班") || tips.contains("停运")) {
                    if rt.statusTips.isEmpty { rt.statusTips = tips }
                    continue
                }
                if count < 0 { continue }
                var it = BusApiMgEta()
                it.busNumber = busStr(e, "busNumber")
                it.count = count
                it.minutes = minutesOfText(busStr(e, "busToStationTimeTips"))
                if it.minutes < 0 {
                    let t = busDouble(e, "busToStationTime", -1.0)
                    if t >= 0 { it.minutes = Int(t.rounded()) }
                }
                if it.minutes < 0 { continue }
                it.distance = busInt(e, "busToStationDistance")
                it.arrived = tips.contains("已到站") || (count == 0 && it.distance == 0)
                rt.etas.append(it)
            }
        }
        rt.etas.sort { $0.minutes < $1.minutes }
        if !rt.etas.isEmpty { rt.statusTips = "" }
        return rt
    }

    static func vehicles(from rt: BusApiRt) -> [BusVehicle] {
        var out: [BusVehicle] = []
        for b in rt.buses {
            let order = b.index + 1
            if order <= 0 { continue }
            var v = BusVehicle()
            v.id = b.number
            v.plate = BusText.sanitizePlate(b.number)
            v.order = order
            v.isArrived = b.statusType == "0"
            v.nextStopName = b.stationName
            v.etaSeconds = rt.etaSecondsByBus[b.number] ?? -1
            v.distanceMeters = rt.distanceByBus[b.number] ?? -1
            v.speedKmh = 0.0
            out.append(v)
        }
        out.sort { $0.order < $1.order }
        return out
    }

    // MARK: 搜索
    static func searchLines(cityId: String, keyword: String) async throws -> [BusLine] {
        let city = busMGCity(cityId)
        var p = BusApiParams()
        p.add("CMD", "114")
        p.add("CITYNAME", city)
        p.add("KEYWORD", keyword)
        p.add("CITYKEY", "")
        let j = try await call(p)
        guard let arr = firstArray(j, ["buslines", "data", "list"]) else { return [] }
        var uniq: [String: BusLine] = [:]
        var order: [String] = []
        var beanByName: [String: [String: Any]] = [:]
        for item in arr {
            guard let o = busObj(item) else { continue }
            var name = busStr(o, "lineName").trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty { name = busStr(o, "LINENAME").trimmingCharacters(in: .whitespacesAndNewlines) }
            if name.isEmpty { continue }
            let bean = busObj(o["routeBean"])
            if let bean = bean, busStr(bean, "routeType") == "3" { continue }
            if name.contains("地铁") { continue }
            if let bean = bean { beanByName[name] = bean }
            var up = busStr(o, "upperOrDown")
            if up.isEmpty { up = "1" }
            var line = BusLine()
            line.name = name
            line.lineNo = name
            line.direction = up == "1" ? 0 : 1
            line.lineId = MygolbsTransport.lineId(city, name, line.direction)
            line.startName = busStr(o, "from")
            if line.startName.isEmpty { line.startName = busStr(o, "startStation") }
            line.endName = busStr(o, "to")
            if line.endName.isEmpty { line.endName = busStr(o, "endStation") }
            line.price = busStr(o, "comments")
            if let old = uniq[name] {
                if old.direction != 0 && line.direction == 0 { uniq[name] = line }
            } else {
                uniq[name] = line
                order.append(name)
            }
        }
        var out = order.compactMap { uniq[$0] }
        for i in out.indices {
            guard let bean = beanByName[out[i].name] else { continue }
            let wins = busArr(bean[out[i].direction == 0 ? "seutimes" : "sedtimes"])
            if let arr = wins, let w = busObj(arr.first) {
                if out[i].firstTime.isEmpty { out[i].firstTime = busStr(w, "first") }
                if out[i].lastTime.isEmpty { out[i].lastTime = busStr(w, "last") }
            }
        }
        return out
    }

    static func searchStops(cityId: String, keyword: String) async throws -> [BusStation] {
        let city = busMGCity(cityId)
        var p = BusApiParams()
        p.add("CMD", "110")
        p.add("CITYNAME", city)
        p.add("KEYWORD", keyword)
        p.add("CITYKEY", "")
        let j = try await call(p)
        var out: [BusStation] = []
        guard let arr = busArr(j["busstations"]) else { return out }
        for item in arr {
            guard let o = busObj(item) else { continue }
            let name = busStr(o, "stationName").trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty || name.contains("地铁") { continue }
            if out.contains(where: { $0.name == name }) { continue }
            var s = newStation(city, name)
            let w = gcj2wgs(busDouble(o, "station_lat"), busDouble(o, "station_lon"))
            s.lat = w.0
            s.lng = w.1
            out.append(s)
        }
        return out
    }

    static func search(cityId: String, keyword: String,
                       coord: BusCoordinate) async throws -> BusSearchHit {
        var hit = BusSearchHit()
        // 站点搜不到不该连累线路结果
        if let stops = try? await searchStops(cityId: cityId, keyword: keyword) {
            hit.stations = stops
        }
        hit.lines = try await searchLines(cityId: cityId, keyword: keyword)
        hit.stationCount = hit.stations.count
        hit.lineCount = hit.lines.count
        return hit
    }

    static func searchMore(cityId: String, keyword: String,
                           coord: BusCoordinate) async throws -> [BusLine] {
        // 掌上公交一次给全量；这里照样发一次真实请求，返回全量线路。
        try await searchLines(cityId: cityId, keyword: keyword)
    }

    // MARK: 时刻表（CMD=103 拿 routeId + CMD=207）
    static func timetable(cityId: String, line: BusLine, direction: Int) async throws -> BusTimetable {
        guard let ref = parseLineId(line.lineId) else {
            throw BusError.upstream("这条线路来自旧的数据源，请重新搜索")
        }
        let city = ref.0, lineName = ref.1
        let dir = ref.2 == "1" ? 0 : 1
        let info = try await route(city: city, lineName: lineName, dir: dir)
        var tt = BusTimetable()
        tt.lineName = lineName
        tt.startName = info.stops.first?.name ?? ""
        tt.endName = info.stops.last?.name ?? ""
        if info.routeId.isEmpty {
            tt.tip = "该线路暂不提供首站发车时刻表"
            return tt
        }
        var p = BusApiParams()
        p.add("CMD", "207")
        p.add("CITYNAME", city)
        p.add("DIRECTION", dir == 0 ? "1" : "2")
        p.add("ROUTEID", info.routeId)
        p.add("CITYKEY", "")
        let j = try await call(p)
        let planned = busStr(j, "special") == "1"
        tt.depTitle = planned ? "计划发车时刻" : "历史推算班次"
        tt.tip = planned ? "本时刻表为计划发车时间，可能临时调整，仅供参考"
                         : "本时刻表数据根据历史数据计算得出，仅供参考"
        if let groups = busArr(j["list"]) {
            for item in groups {
                guard let g = busObj(item) else { continue }
                let span = splitGroup(busStr(g, "group"))
                var tips = ""
                let peak = busInt(g, "peak", 0)
                if peak == 1 { tips = "早高峰" }
                else if peak == 2 { tips = "晚高峰" }
                var times: [String] = []
                if let arr = busArr(g["times"]) {
                    for e in arr {
                        guard let t = busObj(e) else { continue }
                        if busInt(t, "isDelete", 0) == 1 { continue }
                        let clock = normalizeClock(busStr(t, "time"))
                        if !clock.isEmpty { times.append(clock) }
                    }
                }
                busSortByClock(&times)
                if times.isEmpty && span.0.isEmpty { continue }
                var seg = BusTtSegment()
                seg.title = busTtTitle(from: span.0, to: span.1, tips: tips,
                                       interval: -1, times: times)
                seg.times = times
                tt.today.append(seg)
            }
        }
        var all: [String] = []
        for seg in tt.today { all += seg.times }
        busSortByClock(&all)
        var next = normalizeClock(busStr(j, "nextTime"))
        if next.isEmpty {
            next = busFirstAfter(all, busNowHHMM(TimeZone(identifier: "Asia/Shanghai"))) ?? ""
        }
        tt.nextDep = next
        tt.nextFrom = next.isEmpty ? "" : (planned ? "计划班次" : "历史推算")
        if tt.isEmpty { tt.tip = "该线路暂不提供首站发车时刻表" }
        return tt
    }

    // MARK: 线路详情
    static func lineDetail(cityId: String, lineId: String, targetOrder: Int,
                           coord: BusCoordinate) async throws -> BusLineDetail {
        guard let ref = parseLineId(lineId) else {
            throw BusError.upstream("这条线路来自旧的数据源，请重新搜索")
        }
        let city = ref.0, lineName = ref.1
        let dir = ref.2 == "1" ? 0 : 1
        let info = try await route(city: city, lineName: lineName, dir: dir)
        if info.stops.isEmpty { throw BusError.upstream("这条线路暂无站表数据") }
        var d = BusLineDetail()
        d.line.lineId = lineId
        d.line.name = lineName
        d.line.lineNo = lineName
        d.line.direction = dir
        d.line.startName = info.stops.first?.name ?? ""
        d.line.endName = info.stops.last?.name ?? ""
        d.line.firstTime = info.firstTime
        d.line.lastTime = info.lastTime
        d.line.stationsNum = info.stops.count
        d.stops = info.stops
        d.reverseId = MygolbsTransport.lineId(city, lineName, 1 - dir)
        d.reverseStart = d.line.endName
        d.reverseEnd = d.line.startName

        let ask = targetOrder > 0 ? targetOrder : d.stops.count
        let rt = try await realtime(city: city, lineName: lineName, dir: dir, order: ask)
        d.busCount = rt.buses.count
        d.vehicles = vehicles(from: rt)
        d.nextDeparture = rt.planTime
        if rt.runState == 1 { d.status = "该线路已停运" }
        else if !rt.hasReal { d.status = "该线路未开通实时数据查询" }
        else if !rt.statusTips.isEmpty { d.status = rt.statusTips }

        // 候车站
        var wait: BusStop?
        if targetOrder > 0 { wait = d.stops.first { $0.order == targetOrder } }
        if wait == nil { wait = d.stops.first }
        if let wait = wait {
            d.waitingStopName = wait.name
            d.waitingOrder = wait.order
            d.waitingStopId = wait.id
            d = await fillEta(d, city: city, lineName: lineName, dir: dir,
                               wait: wait, coord: coord)
        }
        return d
    }

    /// 掌上公交补预测：CMD=104 的 etas 已按分钟升序，第一辆是大字、第二辆是「下辆」。
    static func predict(cityId: String, lineId: String, order: Int,
                        coord: BusCoordinate) async -> BusApiPred? {
        guard let ref = parseLineId(lineId) else { return nil }
        let dir = ref.2 == "1" ? 0 : 1
        guard let rt = try? await realtime(city: ref.0, lineName: ref.1, dir: dir, order: order) else {
            return nil
        }
        var out = BusApiPred()
        out.preArrival = rt.planTime
        if let e = rt.etas.first, e.minutes >= 0 {
            out.secs = e.minutes * 60
            out.stops = e.count
            out.dist = e.distance
            out.busId = e.busNumber
            for x in rt.etas where x.arrived {
                out.arrived = true
                if !x.busNumber.isEmpty { out.busId = x.busNumber }
                break
            }
        } else {
            out.secs = -1
            if rt.runState == 1 { out.depDesc = "该线路已停运" }
            else if !rt.hasReal { out.depDesc = "未开通实时" }
            else if !rt.statusTips.isEmpty { out.depDesc = rt.statusTips }
        }
        if rt.etas.count > 1 {
            let s2 = rt.etas[1]
            if s2.minutes >= 0 {
                out.nextSecs = s2.minutes * 60
                out.nextStops = s2.count
                out.nextBusId = s2.busNumber
            }
        }
        return out
    }

    static func fillEta(_ d: BusLineDetail, city: String, lineName: String,
                         dir: Int, wait: BusStop, coord: BusCoordinate) async -> BusLineDetail {
        var out = d
        guard let rt = try? await realtime(city: city, lineName: lineName, dir: dir,
                                           order: wait.order) else { return out }
        guard let e = rt.etas.first, e.minutes >= 0 else { return out }
        out.etaMinutes = BusText.minutesOfSecs(e.minutes * 60)
        out.etaStops = e.count
        out.etaDistanceMeters = e.distance
        out.etaBusId = e.busNumber
        out.etaArrived = rt.etas.contains { $0.arrived }
        if rt.etas.count > 1 { out.etaNextBusId = rt.etas[1].busNumber }
        return out
    }

    // MARK: 批量补预测
    static func refreshEta(cityId: String, arrivals: [BusArrival],
                           coord: BusCoordinate) async throws -> [BusArrival] {
        var out = arrivals
        for i in out.indices {
            if out[i].orderInLine <= 0 { continue }
            guard let pr = await predict(cityId: cityId, lineId: out[i].lineId,
                                         order: out[i].orderInLine, coord: coord) else {
                out[i].pending = false
                out[i].refreshed = true
                continue
            }
            if pr.secs >= 0 {
                out[i].busId = pr.busId
                out[i].minutes = BusText.minutesOfSecs(pr.secs)
                out[i].stopsAway = pr.stops
                out[i].distanceMeters = pr.dist
                out[i].realtime = true
                if pr.arrived { out[i].arrived = true }
                out[i].nextMinutes = BusText.minutesOfSecs(pr.nextSecs)
                out[i].nextStopsAway = pr.nextSecs >= 0 ? pr.nextStops : -1
                out[i].nextBusId = pr.nextBusId
            } else if !pr.depDesc.isEmpty {
                out[i].status = pr.depDesc
            }
            out[i].pending = false
            out[i].refreshed = true
        }
        return out
    }

    static func reverseLineId(line: BusLine) -> String {
        guard let ref = parseLineId(line.lineId) else { return "" }
        let dir = ref.2 == "1" ? 0 : 1
        return MygolbsTransport.lineId(ref.0, ref.1, 1 - dir)
    }
}

/// CMD=103 站表结果的内部载体。
private struct BusApiRoute {
    var routeId = ""
    var firstTime = ""
    var lastTime = ""
    var stops: [BusStop] = []
}

/// 掌上公交城市名规范化（顶层函数，供 transport 内部调用）。
private func busMGCity(_ cityId: String) -> String { MygolbsTransport.city(cityId) }

// MARK: - BusApi（对外唯一入口，按数据源分发）

final class BusApi: BusGateway {

    private let prefs: BusPreferences

    init(prefs: BusPreferences) {
        self.prefs = prefs
    }

    func cities() async throws -> [BusCity] {
        switch prefs.source() {
        case .chelaile: return try await ChelaileTransport.cities()
        case .mygolbs:  return try await MygolbsTransport.cities()
        }
    }

    func nearby(cityId: String, at coord: BusCoordinate,
                limit: Int) async throws -> [BusStation] {
        switch prefs.source() {
        case .chelaile: return try await ChelaileTransport.nearby(cityId: cityId, coord: coord, limit: limit)
        case .mygolbs:  return try await MygolbsTransport.nearby(cityId: cityId, coord: coord, limit: limit)
        }
    }

    func stopDetail(cityId: String, station: BusStation,
                    at coord: BusCoordinate) async throws -> BusStation {
        switch prefs.source() {
        case .chelaile:
            return try await ChelaileTransport.stopDetail(cityId: cityId, station: station, coord: coord)
        case .mygolbs:
            return try await MygolbsTransport.stopDetail(cityId: cityId, station: station, coord: coord)
        }
    }

    func lineDetail(cityId: String, lineId: String, targetOrder: Int,
                    at coord: BusCoordinate) async throws -> BusLineDetail {
        switch prefs.source() {
        case .chelaile:
            return try await ChelaileTransport.lineDetail(cityId: cityId, lineId: lineId,
                                                           targetOrder: targetOrder, coord: coord)
        case .mygolbs:
            return try await MygolbsTransport.lineDetail(cityId: cityId, lineId: lineId,
                                                          targetOrder: targetOrder, coord: coord)
        }
    }

    func timetable(cityId: String, line: BusLine, direction: Int) async throws -> BusTimetable {
        switch prefs.source() {
        case .chelaile: return try await ChelaileTransport.timetable(cityId: cityId, line: line, direction: direction)
        case .mygolbs:  return try await MygolbsTransport.timetable(cityId: cityId, line: line, direction: direction)
        }
    }

    func search(cityId: String, keyword: String,
                at coord: BusCoordinate) async throws -> BusSearchHit {
        switch prefs.source() {
        case .chelaile: return try await ChelaileTransport.search(cityId: cityId, keyword: keyword, coord: coord)
        case .mygolbs:  return try await MygolbsTransport.search(cityId: cityId, keyword: keyword, coord: coord)
        }
    }

    func searchMore(cityId: String, keyword: String,
                    at coord: BusCoordinate) async throws -> [BusLine] {
        switch prefs.source() {
        case .chelaile: return try await ChelaileTransport.searchMore(cityId: cityId, keyword: keyword, coord: coord)
        case .mygolbs:  return try await MygolbsTransport.searchMore(cityId: cityId, keyword: keyword, coord: coord)
        }
    }

    func refreshEta(cityId: String, arrivals: [BusArrival],
                    at coord: BusCoordinate) async throws -> [BusArrival] {
        switch prefs.source() {
        case .chelaile: return try await ChelaileTransport.refreshEta(cityId: cityId, arrivals: arrivals, coord: coord)
        case .mygolbs:  return try await MygolbsTransport.refreshEta(cityId: cityId, arrivals: arrivals, coord: coord)
        }
    }

    func reverseLineId(cityId: String, line: BusLine) async throws -> String {
        switch prefs.source() {
        case .chelaile:
            let d = try await ChelaileTransport.lineDetail(cityId: cityId, lineId: line.lineId,
                                                           targetOrder: 0, coord: BusCoordinate())
            return d.reverseId
        case .mygolbs:
            return MygolbsTransport.reverseLineId(line: line)
        }
    }
}
