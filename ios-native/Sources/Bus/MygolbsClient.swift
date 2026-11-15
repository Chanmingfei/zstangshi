import Foundation

/// 掌上公交（mygolbs）客户端。
///
/// 对齐 Android `bus/MygolbsClient.kt`：POST 到 `https://h5.mygolbs.com/ApiData.do`，
/// 表单编码，参数名全大写，命令用 `CMD`。
///
/// 必带请求头（缺了会被拒或返回异常数据）：
///   · `Origin: https://h5.mygolbs.com`
///   · `Referer: https://h5.mygolbs.com/`（**末尾带斜杠**）
///   · UA 伪装成 Android Chrome
///
/// 没有签名 / 加密 / token，响应是明文 JSON，`status == "1"` 才算成功。
actor MygolbsClient {

    private static let endpoint = "https://h5.mygolbs.com/ApiData.do"
    private static let userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/120.0.0.0 Mobile Safari/537.36"

    /// 命令码（与安卓一致）
    enum CMD {
        /// 附近站点
        static let nearby = "106"
        /// 站点经过的线路
        static let lineList = "101"
        /// 线路途经站
        static let lineStations = "103"
        /// 首末班时刻
        static let timetable = "104"
        /// 实时车辆
        static let realtime = "105"
        /// 搜站点
        static let searchStation = "110"
        /// 搜线路
        static let searchLine = "114"
        /// 站点信息
        static let stationInfo = "207"
    }

    private let session: URLSession
    private let cityName: String

    init(cityName: String = BusSource.mygolbs.defaultCityId) {
        self.cityName = cityName
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 22
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        session = URLSession(configuration: cfg)
    }

    // MARK: - 对外

    func nearbyStations(lat: Double, lng: Double, range: Int) async throws -> [BusStation] {
        let body = try await call(CMD.nearby, extra: [
            ("XY", "\(Self.fixed6(lng)),\(Self.fixed6(lat))"),
            ("RANGE", "\(max(500, range))")
        ])
        return Self.parseStations(body)
    }

    func search(keyword: String) async throws -> (stations: [BusStation], lines: [BusLine]) {
        var stations: [BusStation] = []
        var lines: [BusLine] = []

        if let sBody = try? await call(CMD.searchStation, extra: [("KEY", keyword)]) {
            stations = Self.parseStations(sBody)
        }
        if let lBody = try? await call(CMD.searchLine, extra: [("KEY", keyword)]) {
            lines = Self.parseLines(lBody)
        }
        return (stations, lines)
    }

    func lineDetail(lineId: String, targetOrder: Int) async throws -> BusLineDetail {
        let body = try await call(CMD.lineStations, extra: [("LINEID", lineId)])
        var d = BusLineDetail()
        d.cityId = BusSource.mygolbs.defaultCityId
        d.lineId = lineId

        let dicts = Self.dicts(body)

        // 线路信息（第一行通常是线路头）
        if let first = dicts.first {
            d.line = Self.parseLine(first)
            d.line.lineId = lineId
            d.line.cityId = d.cityId
        }

        // 途经站
        var stops: [BusStop] = []
        for (idx, o) in dicts.enumerated() {
            let name = Self.pickS(o, "ZDMC", "STATIONNAME", "XM")
            guard !name.isEmpty else { continue }
            var s = BusStop()
            s.name = name
            s.order = Self.pickI(o, "XH", "ORDER", "SORT") ?? (idx + 1)
            s.sId = Self.pickS(o, "ZDID", "STATIONID")
            s.lat = Self.pickD(o, "WD", "LAT") ?? 0
            s.lng = Self.pickD(o, "JD", "LNG") ?? 0
            stops.append(s)
        }
        stops.sort { $0.order < $1.order }
        d.stops = stops
        d.targetOrder = targetOrder > 0 ? targetOrder : (stops.first?.order ?? 0)
        d.at = Date()
        return d
    }

    func realtime(lineId: String, stationId: String, stationOrder: Int) async throws
        -> (vehicles: [BusVehicle], arrivals: [BusArrival]) {
        let body = try await call(CMD.realtime, extra: [
            ("LINEID", lineId),
            ("ZDID", stationId),
            ("XH", "\(stationOrder)")
        ])
        var vehicles: [BusVehicle] = []
        var arrivals: [BusArrival] = []
        for o in Self.dicts(body) {
            var v = BusVehicle()
            v.busId = Self.pickS(o, "CLID", "BUSID")
            v.plate = Self.pickS(o, "CPH", "PLATE")
            v.order = Self.pickI(o, "XH", "ORDER") ?? 0
            if let dist = Self.pickI(o, "JL", "DISTANCE") { v.distanceToTarget = dist }
            if let sp = Self.pickI(o, "SD", "SPEED") { v.speed = sp }
            let xy = Self.pickS(o, "XY", "GPS")
            let parts = xy.split(separator: ",").compactMap { Double($0) }
            if parts.count >= 2 {
                v.lng = parts[0]
                v.lat = parts[1]
            }
            vehicles.append(v)

            var a = BusArrival()
            a.lineId = lineId
            a.stopsAway = Self.pickI(o, "ZSC", "STATIONCOUNT") ?? -1
            a.distanceMeters = v.distanceToTarget
            if let sec = Self.pickI(o, "YDSJ", "ARRIVALTIME") {
                a.seconds = sec
                a.minutes = BusText.minutesOfSecs(sec)
                a.arrived = sec == 0
            } else if v.distanceToTarget >= 0, v.speed > 0 {
                let sec = Int(Double(v.distanceToTarget) / (Double(v.speed) * 1000 / 3600))
                a.seconds = sec
                a.minutes = BusText.minutesOfSecs(sec)
            }
            a.realtime = true
            arrivals.append(a)
        }
        arrivals.sort { $0.minutes < $1.minutes }
        return (vehicles, arrivals)
    }

    // MARK: - 请求

    private func call(_ cmd: String, extra: [(String, String)]) async throws -> Any {
        var form: [(String, String)] = [
            ("CMD", cmd),
            ("CITY", cityName)
        ]
        form.append(contentsOf: extra)

        guard let url = URL(string: Self.endpoint) else { throw BusError.network("URL 无效") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded; charset=UTF-8",
                     forHTTPHeaderField: "Content-Type")
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("https://h5.mygolbs.com", forHTTPHeaderField: "Origin")
        req.setValue("https://h5.mygolbs.com/", forHTTPHeaderField: "Referer")
        req.httpBody = Self.encodeForm(form).data(using: .utf8)

        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else { throw BusError.network("没有收到响应") }
        guard http.statusCode < 400 else {
            throw BusError.network("掌上公交服务返回 HTTP \(http.statusCode)")
        }
        guard !data.isEmpty,
              let obj = try? JSONSerialization.jsonObject(with: data) else {
            throw BusError.upstream("掌上公交返回了空或非法的 JSON")
        }

        if let dict = obj as? [String: Any] {
            let status = Self.pickS(dict, "status", "STATUS")
            if !status.isEmpty, status != "1" {
                let msg = Self.pickS(dict, "msg", "MSG", "info")
                throw BusError.upstream(msg.isEmpty ? "掌上公交返回失败（status=\(status)）" : msg)
            }
        }
        return obj
    }

    /// RFC 3986 表单编码，空格编成 `+`（与 Java URLEncoder 一致）
    private static func encodeForm(_ form: [(String, String)]) -> String {
        form.map { "\(escape($0.0))=\(escape($0.1))" }.joined(separator: "&")
    }

    private static func escape(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = s.addingPercentEncoding(withAllowedCharacters: allowed) else { return "" }
        return encoded.replacingOccurrences(of: "%20", with: "+")
    }

    // MARK: - 解析

    /// 把 JSON 根对象里的数组统一取出来：兼容 `{"data":[...]}`、`{"return":[...]}`、根数组
    private static func dicts(_ obj: Any) -> [[String: Any]] {
        if let arr = obj as? [[String: Any]] { return arr }
        guard let dict = obj as? [String: Any] else { return [] }
        for key in ["data", "return", "DATA", "list", "rst"] {
            if let arr = dict[key] as? [[String: Any]] { return arr }
            if let inner = dict[key] as? [String: Any] {
                for k2 in ["data", "list", "return"] {
                    if let arr = inner[k2] as? [[String: Any]] { return arr }
                }
            }
        }
        return []
    }

    private static func parseLine(_ o: [String: Any]) -> BusLine {
        var b = BusLine()
        b.name = pickS(o, "XM", "LINENAME", "NAME")
        b.lineId = pickS(o, "ID", "LINEID")
        b.cityId = BusSource.mygolbs.defaultCityId
        b.startName = pickS(o, "QDZ", "STARTSTATION")
        b.endName = pickS(o, "ZDZ", "ENDSTATION")
        b.firstTime = pickS(o, "SBSJ", "FBSJ")
        b.lastTime = pickS(o, "MBSJ")
        return b
    }

    private static func parseStations(_ obj: Any) -> [BusStation] {
        var out: [BusStation] = []
        for o in dicts(obj) {
            let name = pickS(o, "ZDMC", "STATIONNAME", "XM", "NAME")
            guard !name.isEmpty else { continue }
            var s = BusStation()
            s.name = name
            s.sId = pickS(o, "ZDID", "STATIONID", "ID")
            s.physicalStId = s.sId
            s.cityId = BusSource.mygolbs.defaultCityId
            if let d = pickI(o, "JL", "DISTANCE") { s.distanceMeters = d }
            s.lat = pickD(o, "WD", "LAT") ?? 0
            s.lng = pickD(o, "JD", "LNG") ?? 0

            let lineDicts = (o["LINES"] as? [[String: Any]])
                ?? (o["lines"] as? [[String: Any]]) ?? []
            var arrivals: [BusArrival] = []
            for l in lineDicts {
                var b = parseLine(l)
                if b.name.isEmpty { continue }
                if b.lineId.isEmpty { b.lineId = "mg|\(name)|\(b.name)" }
                var a = BusArrival()
                a.lineId = b.lineId
                a.lineName = b.name
                a.startName = b.startName
                a.endName = b.endName
                if let sec = pickI(l, "YDSJ", "ARRIVALTIME") {
                    a.seconds = sec
                    a.minutes = BusText.minutesOfSecs(sec)
                    a.arrived = sec == 0
                    a.realtime = true
                }
                if let dist = pickI(l, "JL", "DISTANCE") { a.distanceMeters = dist }
                a.status = pickS(l, "ZT", "STATUS")
                arrivals.append(a)
            }
            s.arrivals = arrivals
            out.append(s)
        }
        out.sort {
            let a = $0.distanceMeters < 0 ? Int.max : $0.distanceMeters
            let b = $1.distanceMeters < 0 ? Int.max : $1.distanceMeters
            return a < b
        }
        return out
    }

    private static func parseLines(_ obj: Any) -> [BusLine] {
        dicts(obj).compactMap { o in
            let name = pickS(o, "XM", "LINENAME", "NAME")
            guard !name.isEmpty else { return nil }
            var b = parseLine(o)
            b.name = name
            if b.lineId.isEmpty { b.lineId = "mg|\(name)|1" }
            return b
        }
    }

    // MARK: - 小工具

    private static func fixed6(_ v: Double) -> String {
        String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), v)
    }

    private static func pickS(_ o: [String: Any], _ keys: String...) -> String {
        for k in keys {
            if let s = o[k] as? String, !s.isEmpty { return s }
            if let n = o[k] as? NSNumber { return n.stringValue }
        }
        return ""
    }

    private static func pickI(_ o: [String: Any], _ keys: String...) -> Int? {
        for k in keys {
            if let i = o[k] as? Int { return i }
            if let n = o[k] as? NSNumber { return n.intValue }
            if let s = o[k] as? String, let v = Int(s) { return v }
        }
        return nil
    }

    private static func pickD(_ o: [String: Any], _ keys: String...) -> Double? {
        for k in keys {
            if let d = o[k] as? Double { return d }
            if let n = o[k] as? NSNumber { return n.doubleValue }
            if let s = o[k] as? String, let v = Double(s) { return v }
        }
        return nil
    }
}