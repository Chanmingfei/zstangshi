// svg-test.swift -- verify the DockIcon SVG path parser produces correct geometry.
// Build: swiftc -o svgtest svg-test.swift <DockIcon sources that don't need SwiftUI>
// Here we inline a Foundation-only copy of the parser so it runs on Windows.

import Foundation

// MARK: - Minimal Path stand-in (mirrors SwiftUI.Path's API surface we use)

final class TestPath {
    var commands: [String] = []
    var points: [CGPoint] = []
    /// 贝塞尔的控制点（曲线真正的极值通常落在这里，不在端点）
    var controlPoints: [CGPoint] = []

    func move(to p: CGPoint) { commands.append("M"); points.append(p) }
    func addLine(to p: CGPoint) { commands.append("L"); points.append(p) }
    func addCurve(to end: CGPoint, control1: CGPoint, control2: CGPoint) {
        commands.append("C")
        controlPoints.append(control1)
        controlPoints.append(control2)
        points.append(end)
    }
    func closeSubpath() { commands.append("Z") }

    /// 采样曲线上的点（用控制点做保守包围盒）
    var allY: [CGFloat] { (points + controlPoints).map(\.y) }
    var allX: [CGFloat] { (points + controlPoints).map(\.x) }
}

// MARK: - Copy of SVGPath (Foundation/CoreGraphics only)

enum SVGPathTest {

    static func append(_ d: String, to path: TestPath) {
        let tokens = tokenize(d)
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
            if tokens[i].count == 1, let t = tokens[i].first,
               "MmLlHhVvCcAaZz".contains(t) {
                cmd = t
                i += 1
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
                cmd = "L"
            case "m":
                let x = num(), y = num()
                cur = CGPoint(x: cur.x + x, y: cur.y + y); start = cur
                path.move(to: cur)
                cmd = "l"
            case "L":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: num(), y: num()); path.addLine(to: cur)
            case "l":
                guard hasNum() else { i += 1; continue }
                let x = num(), y = num()
                cur = CGPoint(x: cur.x + x, y: cur.y + y); path.addLine(to: cur)
            case "H":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: num(), y: cur.y); path.addLine(to: cur)
            case "h":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: cur.x + num(), y: cur.y); path.addLine(to: cur)
            case "V":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: cur.x, y: num()); path.addLine(to: cur)
            case "v":
                guard hasNum() else { i += 1; continue }
                cur = CGPoint(x: cur.x, y: cur.y + num()); path.addLine(to: cur)
            case "A", "a":
                guard hasNum() else { i += 1; continue }
                let rx = num(), ry = num()
                _ = num()
                let large = num() != 0
                let sweep = num() != 0
                let ex = num(), ey = num()
                let endPt = cmd == "A" ? CGPoint(x: ex, y: ey)
                                       : CGPoint(x: cur.x + ex, y: cur.y + ey)
                appendArc(to: path, from: cur, to: endPt, rx: rx, ry: ry,
                          large: large, sweep: sweep)
                cur = endPt
            case "C", "c":
                guard hasNum() else { i += 1; continue }
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
                path.closeSubpath(); cur = start
            default:
                i += 1
            }
        }
    }

    private static func appendArc(to path: TestPath, from p0: CGPoint, to p1: CGPoint,
                                  rx: CGFloat, ry: CGFloat, large: Bool, sweep: Bool) {
        if rx == 0 || ry == 0 || (p0.x == p1.x && p0.y == p1.y) {
            path.addLine(to: p1); return
        }
        let rxA = abs(rx), ryA = abs(ry)
        let dx2 = (p0.x - p1.x) / 2, dy2 = (p0.y - p1.y) / 2
        let lambda = (dx2 * dx2) / (rxA * rxA) + (dy2 * dy2) / (ryA * ryA)
        var rx2 = rxA * rxA, ry2 = ryA * ryA
        if lambda > 1 { let k = sqrt(lambda); rx2 *= k; ry2 *= k }
        let rxS = sqrt(rx2), ryS = sqrt(ry2)

        let sign: CGFloat = (large != sweep) ? 1 : -1
        let numV = max(0, rx2 * ry2 - rx2 * dy2 * dy2 - ry2 * dx2 * dx2)
        let den = rx2 * dy2 * dy2 + ry2 * dx2 * dx2
        let coef = den == 0 ? 0 : sign * sqrt(numV / den)

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

        let segments = max(1, Int(ceil(abs(dTheta) / (.pi / 2))))
        let delta = dTheta / CGFloat(segments)
        let t = 4.0 / 3.0 * tan(delta / 4)

        var th = theta1
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
        }
    }

    static func tokenize(_ d: String) -> [String] {
        var out: [String] = []
        var buf = ""
        for ch in d {
            if ch.isLetter {
                if !buf.isEmpty { out.append(buf); buf = "" }
                out.append(String(ch))
            } else if ch == "," || ch == " " || ch == "\n" || ch == "\t" {
                if !buf.isEmpty { out.append(buf); buf = "" }
            } else if ch == "-" {
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

// MARK: - Tests

var passed = 0
var failed = 0

func check(_ cond: Bool, _ msg: String) {
    if cond { passed += 1 } else { failed += 1; print("  FAIL: \(msg)") }
}

func approx(_ a: CGFloat, _ b: CGFloat, _ eps: CGFloat = 0.001) -> Bool { abs(a - b) < eps }

print("── SVG tokenizer ──")
do {
    let t = SVGPathTest.tokenize("M12,3l9,8h-2.5v9h-4.5v-6h-4v6H5.5v-9H3z")
    check(t.first == "M", "starts with M")
    check(t.contains("-2.5"), "negative decimal -2.5 kept as one token")
    check(t.contains("-6"), "negative int -6")
    check(t.last == "z", "ends with z")
    // 关键：负号必须是新 token，不能粘到上一个数字
    let idx = t.firstIndex(of: "-2.5")
    check(idx != nil, "-2.5 is its own token")
}
do {
    // 无逗号、无空格的紧凑写法
    let t = SVGPathTest.tokenize("M4.5,10.5h3.4V19H4.5z")
    check(t.contains("3.4"), "3.4 parsed")
    check(t.contains("19"), "19 parsed")
}

print("── 首页：房子 ──")
do {
    let p = TestPath()
    SVGPathTest.append("M12,3l9,8h-2.5v9h-4.5v-6h-4v6H5.5v-9H3z", to: p)
    check(p.commands.first == "M", "starts with move")
    check(p.points.first!.x == 12 && p.points.first!.y == 3, "start point (12,3)")
    check(p.commands.contains("Z"), "closed")
    // 尺子：所有点都应落在 0..24 视口内
    let inBounds = p.points.allSatisfy { $0.x >= -0.001 && $0.x <= 24.001 && $0.y >= -0.001 && $0.y <= 24.001 }
    check(inBounds, "all house points inside 24x24 viewport")
    // 房顶最高点 y=3，门底 y=20
    let minY = p.points.map(\.y).min()!
    let maxY = p.points.map(\.y).max()!
    check(approx(minY, 3), "roof apex y = 3 (got \(minY))")
    check(approx(maxY, 20), "bottom y = 20 (got \(maxY))")
}

print("── 课表：表格（不是日历） ──")
do {
    let p = TestPath()
    SVGPathTest.append("M3,5h18v6H3zM3,13h8v6H3zM13,13h8v6h-8z", to: p)
    check(p.commands.filter { $0 == "Z" }.count == 3, "3 closed subpaths (top bar + 2 blocks)")
    check(p.commands.filter { $0 == "M" }.count == 3, "3 move commands")
    let xs = p.points.map(\.x)
    let ys = p.points.map(\.y)
    check(approx(xs.min()!, 3), "left edge x = 3")
    check(approx(xs.max()!, 21), "right edge x = 21")
    check(approx(ys.min()!, 5), "top edge y = 5")
    check(approx(ys.max()!, 19), "bottom edge y = 19")
    // 上条横跨全宽、下两块各占一半 => 视觉是「表格」而非日历
    check(approx(p.points[1].x, 21), "top bar spans to x=21")
}

print("── 成绩：三根柱 ──")
do {
    let p = TestPath()
    SVGPathTest.append("M4.5,10.5h3.4V19H4.5zM10.3,4.5h3.4V19h-3.4zM16.1,13.2h3.4V19h-3.4z", to: p)
    check(p.commands.filter { $0 == "Z" }.count == 3, "3 bars")
    let ys = p.points.map(\.y)
    check(approx(ys.min()!, 4.5), "tallest bar top y = 4.5")
    check(approx(ys.max()!, 19), "all bars bottom y = 19")
    // 中柱最高
    check(approx(p.points[4].y, 4.5), "middle bar is tallest")
}

print("── 我的：头 + 肩（含圆弧） ──")
do {
    let p = TestPath()
    SVGPathTest.append("M12,4a4,4 0 0,1 0,8a4,4 0 0,1 0,-8zM12,14c4.42,0 8,1.79 8,4v2H4v-2c0,-2.21 3.58,-4 8,-4z", to: p)
    check(p.commands.contains("C"), "arc converted to cubic bezier")
    check(p.commands.filter { $0 == "Z" }.count >= 1, "closed")

    // 头的圆弧：起点 (12,4)，半径 4 => 圆弧最左到 x=8、最右到 x=16，顶部 y≈4
    // 用控制点 + 端点做保守包围盒（贝塞尔不超出控制点凸包）
    let yLo = p.allY.min()!
    let yHi = p.allY.max()!
    check(yLo <= 4.01, "head reaches up to y≈4 (bbox top \(yLo))")
    check(yHi >= 19.99, "body reaches down to y≈20 (bbox bottom \(yHi))")
    let xLo = p.allX.min()!
    let xHi = p.allX.max()!
    check(xLo >= -0.01, "inside viewport left (min x \(xLo))")
    check(xHi <= 24.01, "inside viewport right (max x \(xHi))")

    // 两条子路径：头 + 身
    check(p.commands.filter { $0 == "M" }.count == 2, "2 subpaths (head, body)")
}

print("── 圆弧：半圆 (0,0)->(8,0) r=4 应向上鼓到 y=-4 ──")
do {
    let p = TestPath()
    // sweep=1 => 顺时针，从 (0,0) 向 (8,0) 走时向上（负 y）
    SVGPathTest.append("M0,0 a4,4 0 0,1 8,0", to: p)
    let minY = p.allY.min()!
    check(minY <= -3.9, "arc bulges up to y≈-4 (bbox top \(minY))")
    check(minY >= -4.1, "arc does not overshoot past -4 (bbox top \(minY))")
    check(p.commands.contains("C"), "bezier segments produced")
    let end = p.points.last!
    check(approx(end.x, 8, 0.01) && approx(end.y, 0, 0.01), "arc endpoint (8,0)")
}

print("── 公交：车身上宽下窄轮廓 ──")
do {
    let p = TestPath()
    // 安卓 ic_quick_bus.xml：外轮廓 x∈[3.2,20.8]，y 从 3.4 到 16.8（顶部圆角 2 + 高 9.4 + 底部圆角 2）
    SVGPathTest.append("M5.2,3.4h13.6a2,2 0 0 1 2,2v9.4a2,2 0 0 1 -2,2H5.2a2,2 0 0 1 -2,-2V5.4a2,2 0 0 1 2,-2z", to: p)
    check(p.commands.contains("C"), "bus uses arc (rounded corners) -> bezier")
    check(p.commands.contains("Z"), "bus outline is closed")
    let xLo = p.allX.min()!, xHi = p.allX.max()!
    let yLo = p.allY.min()!, yHi = p.allY.max()!
    check(xLo >= 3.19 && xLo <= 3.21, "bus left edge x=3.2 (got \(xLo))")
    check(xHi >= 20.79 && xHi <= 20.81, "bus right edge x=20.8 (got \(xHi))")
    check(yLo >= 3.39 && yLo <= 3.41, "bus top y=3.4 (got \(yLo))")
    check(yHi >= 16.79 && yHi <= 16.81, "bus bottom y=16.8 (got \(yHi))")
}

print("\n============================================================")
print("通过 \(passed) · 失败 \(failed)")
print("============================================================")
if failed > 0 { exit(1) }