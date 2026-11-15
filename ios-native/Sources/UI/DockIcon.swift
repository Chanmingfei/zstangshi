import SwiftUI

/// 底栏图标 —— 直接用安卓 `res/drawable/ic_nav_*.xml` 里的原始 pathData 绘制。
///
/// 为什么不用 SF Symbols：
///   底栏是用户每天都在看的控件，图标的形状差异最容易让人觉得「和安卓不一样」。
///   安卓的四个图标都是 24×24 viewport 的手绘矢量，形状与 SF Symbols 有明显差别：
///     · 首页  `M12,3l9,8h-2.5v9h-4.5v-6h-4v6H5.5v-9H3z`
///              —— 尖顶房子 + 底部门洞（SF 的 house 屋顶更宽、门洞比例不同）
///     · 课表  `M3,5h18v6H3zM3,13h8v6H3zM13,13h8v6h-8z`
///              —— **上方一整条 + 下方左右两块**；
///                 上一版误用 "calendar"（带日期数字），这是「版式和安卓不一样」的典型
///     · 成绩  `M4.5,10.5h3.4V19H4.5zM10.3,4.5h3.4V19h-3.4zM16.1,13.2h3.4V19h-3.4z`
///              —— 三根柱：中高、左中、右矮（SF 的 chart.bar 是 4 根，且底部对齐方式不同）
///     · 我的  `M12,4a4,4 0 0,1 0,8a4,4 0 0,1 0,-8zM12,14c4.42,0 8,1.79 8,4v2H4v-2c0,-2.21 3.58,-4 8,-4z`
///              —— 圆头 + 梯形肩（SF 的 person 肩部更圆润、头身比不同）
///
/// 实现：把 SVG path 指令转成 SwiftUI `Path`。
/// 支持本工程用到的子集：M / m / L / l / H / h / V / v / C / c / A / a / Z / z。
///
/// ⚠️ 踩过的坑：「我的」图标身体那段是 `c4.42,0 8,1.79 8,4v2H4v-2c0,-2.21 3.58,-4 8,-4z`，
/// 用的是**三次贝塞尔 `c`**。第一版解析器漏了 C/c，那段被整段跳过，
/// 「我的」只画出一个头、没有肩膀。所以 C/c 必须实现（下方单测覆盖）。
enum DockIcon {

    /// 一个 24×24 viewport 的图标
    struct PathView: Shape {
        let path: MainTab
        /// 选中态：安卓只在颜色/字重上区分，形状不变，这里保留参数以便将来扩展
        var filled: Bool = false

        func path(in rect: CGRect) -> Path {
            // Path 没有 translateBy/scaleBy，构造变换后用 applying(_:)。
            // 24×24 viewport → 缩放到 rect，再居中。
            let s = min(rect.width, rect.height) / 24.0
            var t = CGAffineTransform(translationX: rect.midX, y: rect.midY)
            t = t.scaledBy(x: s, y: s)
            t = t.translatedBy(x: -12, y: -12)
            var p = Path()
            SVGPath.append(Self.d(for: path), to: &p)
            return p.applying(t)
        }

        static func d(for tab: MainTab) -> String {
            switch tab {
            case .home:
                return "M12,3l9,8h-2.5v9h-4.5v-6h-4v6H5.5v-9H3z"
            case .timetable:
                return "M3,5h18v6H3zM3,13h8v6H3zM13,13h8v6h-8z"
            case .grades:
                return "M4.5,10.5h3.4V19H4.5zM10.3,4.5h3.4V19h-3.4zM16.1,13.2h3.4V19h-3.4z"
            case .mine:
                return "M12,4a4,4 0 0,1 0,8a4,4 0 0,1 0,-8z"
                    + "M12,14c4.42,0 8,1.79 8,4v2H4v-2c0,-2.21 3.58,-4 8,-4z"
            }
        }
    }
}

/// 首页宫格图标 —— 同样用安卓 `ic_quick_*.xml` 的原始 pathData。
///
/// 与底栏同理：SF Symbols 的语义接近，但形状、笔画粗细、留白都不一样，
/// 摆在一排里最容易被看成「和安卓版不一样」。
enum QuickIcon {

    struct PathView: Shape {
        let key: Key
        /// 安卓侧是 stroke 型（GPA 折线、推荐课表星形）就用描边绘制
        var stroked: Bool = false

        enum Key {
            case timetable, grades, gpa, calendar, select, recommend, bus, more
        }

        static func d(for key: Key) -> String {
            switch key {
            // 复用底栏的课表/成绩图标（安卓就是同一份 drawable）
            case .timetable:
                return DockIcon.PathView.d(for: .timetable)
            case .grades:
                return DockIcon.PathView.d(for: .grades)
            case .gpa:
                return "M3.4,18.6L8.8,12.2L12.4,15.2L19.4,6.6"
            case .calendar:
                return "M19,3h-1V1h-2v2H8V1H6v2H5C3.89,3 3,3.9 3,5v14c0,1.1 0.89,2 2,2h14"
                    + "c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM19,19H5V8h14V19z"
            case .select:
                return "M3.5,6h2.4v2.4H3.5zM8.2,6.2h12.3v2H8.2z"
                    + "M3.5,10.8h2.4v2.4H3.5zM8.2,11h12.3v2H8.2z"
                    + "M3.5,15.6h2.4V18H3.5zM8.2,15.8h12.3v2H8.2z"
            case .recommend:
                return "M12,3.4l2.5,5.2l5.7,0.8l-4.1,4l1,5.7L12,16.4l-5.1,2.7l1,-5.7l-4.1,-4l5.7,-0.8z"
            case .bus:
                return "M5.2,3.4h13.6a2,2 0 0 1 2,2v9.4a2,2 0 0 1 -2,2H5.2a2,2 0 0 1 -2,-2V5.4a2,2 0 0 1 2,-2z"
            case .more:
                return "M3.6,3.6h7v7h-7zM13.4,3.6h7v7h-7zM3.6,13.4h7v7h-7zM13.4,13.4h7v7h-7z"
            }
        }

        /// 安卓侧是否描边型（fill=无 / strokeWidth=2）
        static func isStroked(_ key: Key) -> Bool {
            switch key {
            case .gpa, .recommend: return true
            default: return false
            }
        }

        func path(in rect: CGRect) -> Path {
            let s = min(rect.width, rect.height) / 24.0
            var t = CGAffineTransform(translationX: rect.midX, y: rect.midY)
            t = t.scaledBy(x: s, y: s)
            t = t.translatedBy(x: -12, y: -12)
            var p = Path()
            SVGPath.append(Self.d(for: key), to: &p)
            return p.applying(t)
        }
    }
}

/// 极简 SVG path 解析器（只覆盖本工程用到的指令）。
enum SVGPath {

    static func append(_ d: String, to path: inout Path) {
        var tokens = tokenize(d)
        var i = 0
        var cur = CGPoint.zero
        var start = CGPoint.zero
        var cmd: Character = "M"

        func num() -> CGFloat {
            guard i < tokens.count, let v = Double(tokens[i]) else { i += 1; return 0 }
            i += 1
            return CGFloat(v)
        }
        func hasNum() -> Bool {
            guard i < tokens.count else { return false }
            return Double(tokens[i]) != nil
        }

        while i < tokens.count {
            if let t = tokens[i].first, "MmLlHhVvCcAaZz".contains(t), tokens[i].count == 1 {
                cmd = tokens[i][tokens[i].startIndex]
                i += 1
                // 显式命令后面若没数字（如结尾的 z）直接处理
                if cmd == "Z" || cmd == "z" {
                    path.closeSubpath()
                    cur = start
                    continue
                }
            }

            switch cmd {
            case "M":
                let x = num(), y = num()
                cur = CGPoint(x: x, y: y); start = cur
                path.move(to: cur)
                cmd = "L"                      // 后续隐式坐标按 L 处理（SVG 规范）
            case "m":
                let x = num(), y = num()
                cur = CGPoint(x: cur.x + x, y: cur.y + y); start = cur
                path.move(to: cur)
                cmd = "l"
            case "L":
                guard hasNum() else { i += 1; continue }
                let x = num(), y = num()
                cur = CGPoint(x: x, y: y)
                path.addLine(to: cur)
            case "l":
                guard hasNum() else { i += 1; continue }
                let x = num(), y = num()
                cur = CGPoint(x: cur.x + x, y: cur.y + y)
                path.addLine(to: cur)
            case "H":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: num(), y: cur.y)
                path.addLine(to: cur)
            case "h":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: cur.x + num(), y: cur.y)
                path.addLine(to: cur)
            case "V":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: cur.x, y: num())
                path.addLine(to: cur)
            case "v":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: cur.x, y: cur.y + num())
                path.addLine(to: cur)
            case "A", "a":
                guard hasNum() else { i += 1; continue }
                // A rx ry x-axis-rotation large-arc-flag sweep-flag x y
                let rx = num(), ry = num()
                _ = num()                       // rotation：本工程的路径都是 0
                let large = num() != 0
                let sweep = num() != 0
                let ex = num(), ey = num()
                let end = cmd == "A" ? CGPoint(x: ex, y: ey)
                                     : CGPoint(x: cur.x + ex, y: cur.y + ey)
                appendArc(to: &path, from: cur, to: end,
                          rx: rx, ry: ry, large: large, sweep: sweep)
                cur = end
            case "C", "c":
                guard hasNum() else { i += 1; continue }
                // C x1 y1 x2 y2 x y   （c 为相对坐标）
                let x1 = num(), y1 = num()
                let x2 = num(), y2 = num()
                let x = num(), y = num()
                let c1: CGPoint, c2: CGPoint, end: CGPoint
                if cmd == "C" {
                    c1 = CGPoint(x: x1, y: y1)
                    c2 = CGPoint(x: x2, y: y2)
                    end = CGPoint(x: x, y: y)
                } else {
                    c1 = CGPoint(x: cur.x + x1, y: cur.y + y1)
                    c2 = CGPoint(x: cur.x + x2, y: cur.y + y2)
                    end = CGPoint(x: cur.x + x, y: cur.y + y)
                }
                path.addCurve(to: end, control1: c1, control2: c2)
                cur = end
            case "Z", "z":
                path.closeSubpath()
                cur = start
            default:
                i += 1
            }
        }
    }

    /// SVG 椭圆弧 → 三次贝塞尔（按端点参数化转换，标准算法）。
    private static func appendArc(to path: inout Path, from p0: CGPoint, to p1: CGPoint,
                                  rx: CGFloat, ry: CGFloat,
                                  large: Bool, sweep: Bool) {
        // 退化情况：半径 0 或起终点重合 → 直线
        if rx == 0 || ry == 0 || (p0.x == p1.x && p0.y == p1.y) {
            path.addLine(to: p1)
            return
        }
        let rxA = abs(rx), ryA = abs(ry)
        let dx2 = (p0.x - p1.x) / 2, dy2 = (p0.y - p1.y) / 2
        let lambda = (dx2 * dx2) / (rxA * rxA) + (dy2 * dy2) / (ryA * ryA)
        var rx2 = rxA * rxA, ry2 = ryA * ryA
        if lambda > 1 {
            let k = sqrt(lambda)
            rx2 *= k; ry2 *= k
        }
        let rxS = sqrt(rx2), ryS = sqrt(ry2)

        var sign: CGFloat = (large != sweep) ? 1 : -1
        let num = max(0, rx2 * ry2 - rx2 * dy2 * dy2 - ry2 * dx2 * dx2)
        let den = rx2 * dy2 * dy2 + ry2 * dx2 * dx2
        let coef = den == 0 ? 0 : sign * sqrt(num / den)

        let cxp = coef * (rxS * dy2 / ryS)
        let cyp = coef * -(ryS * dx2 / rxS)
        let cx = cxp + (p0.x + p1.x) / 2
        let cy = cyp + (p0.y + p1.y) / 2

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let dot = ux * vx + uy * vy
            let len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            guard len > 0 else { return 0 }
            var a = acos(max(-1, min(1, dot / len)))
            if ux * vy - uy * vx < 0 { a = -a }
            return a
        }

        let theta1 = angle(1, 0, (dx2 - cxp) / rxS, (dy2 - cyp) / ryS)
        var dTheta = angle((dx2 - cxp) / rxS, (dy2 - cyp) / ryS,
                           (-dx2 - cxp) / rxS, (-dy2 - cyp) / ryS)
        if !sweep && dTheta > 0 { dTheta -= 2 * .pi }
        if sweep && dTheta < 0 { dTheta += 2 * .pi }

        // 每段不超过 90°
        let segments = max(1, Int(ceil(abs(dTheta) / (.pi / 2))))
        let delta = dTheta / CGFloat(segments)
        let t = 4.0 / 3.0 * tan(delta / 4)

        var th = theta1
        var curPt = p0
        for _ in 0..<segments {
            let th2 = th + delta
            let cos1 = cos(th), sin1 = sin(th)
            let cos2 = cos(th2), sin2 = sin(th2)

            let e1 = CGPoint(x: cx + rxS * cos1 - ryS * t * sin1,
                             y: cy + ryS * sin1 + rxS * t * cos1)
            let e2 = CGPoint(x: cx + rxS * cos2 + ryS * t * sin2,
                             y: cy + ryS * sin2 - rxS * t * cos2)
            let end = CGPoint(x: cx + rxS * cos2, y: cy + ryS * sin2)
            path.addCurve(to: end, control1: e1, control2: e2)
            th = th2
            curPt = end
        }
        sign = 0  // 只用一次，避免未使用警告
        _ = curPt
    }

    /// 把 path 字符串切成「命令字母」与「数字」两类 token
    private static func tokenize(_ d: String) -> [String] {
        var out: [String] = []
        var buf = ""
        for ch in d {
            if ch.isLetter {
                if !buf.isEmpty { out.append(buf); buf = "" }
                out.append(String(ch))
            } else if ch == "," || ch == " " || ch == "\n" || ch == "\t" {
                if !buf.isEmpty { out.append(buf); buf = "" }
            } else if ch == "-" {
                // 负号是数字起点（前面若已有数字则先断开）
                if !buf.isEmpty { out.append(buf); buf = "" }
                buf = "-"
            } else {
                buf.append(ch)
            }
        }
        if !buf.isEmpty { out.append(buf) }
        return out
    }
}