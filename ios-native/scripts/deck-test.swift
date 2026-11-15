// deck-layout-test.swift -- reproduce the PageDeck + CapsuleDock geometry in pure Swift
// so we can verify the math on Windows without SwiftUI.
//
// The bug being chased: tapping a dock item shows the WRONG page.
//
// Layout contract (what we WANT):
//   - 4 pages, each exactly `W` wide, laid out left-to-right at x = 0, W, 2W, 3W
//   - page i is visible when band offset = -i * W
//   - dock cell i is centred at pad + cellW * (i + 0.5)
//   - tapping cell i must end with page i filling the screen
//
// This file models both the "stacked" (buggy) and "HStack" (correct) layouts and
// asserts only the HStack one satisfies the contract.

import Foundation

let W: CGFloat = 390          // iPhone 14-ish logical width
let pageCount = 4

// MARK: - Layout models

/// BUGGY: all pages placed at the same origin (SwiftUI's default ZStack/VStack child placement
/// for a @ViewBuilder tuple when no container is used). Every page occupies x = 0.
func stackedPageOrigin(_ i: Int) -> CGFloat { 0 }

/// CORRECT: HStack(spacing: 0) places page i at x = i * W.
func hstackPageOrigin(_ i: Int) -> CGFloat { CGFloat(i) * W }

/// Band offset applied to the whole strip when we want page `index` visible.
func bandOffset(index: Int) -> CGFloat { -CGFloat(index) * W }

/// Which page's [x, x+W) window is covering the screen when the band is offset by `off`?
/// Page i occupies [origin_i + off, origin_i + off + W). The screen is [0, W).
/// Returns the index of the page that fully covers the screen, or nil.
func visiblePage(off: CGFloat, origin: (Int) -> CGFloat) -> Int? {
    for i in 0..<pageCount {
        let x = origin(i) + off
        if abs(x) < 0.001 { return i }   // this page starts exactly at the screen's left edge
    }
    return nil
}

// MARK: - Dock geometry (mirrors CapsuleDock.kt / CapsuleDock.swift)

let dockPad: CGFloat = 5
let dockWidth: CGFloat = W - 44     // 22pt side margins
let cellW: CGFloat = (dockWidth - dockPad * 2) / CGFloat(pageCount)

/// Finger x -> fractional cell position (Android CapsuleDock.slotAt).
func slotAt(_ x: CGFloat) -> CGFloat {
    guard cellW > 0 else { return 0 }
    let p = (x - dockPad) / cellW - 0.5
    return min(max(p, 0), CGFloat(pageCount - 1))
}

/// Centre x of dock cell i
func cellCenter(_ i: Int) -> CGFloat { dockPad + cellW * (CGFloat(i) + 0.5) }

// MARK: - Tests

var passed = 0, failed = 0
func check(_ cond: Bool, _ msg: String) {
    if cond { passed += 1 } else { failed += 1; print("  FAIL: \(msg)") }
}

print("── 1. 叠放布局（上一版的写法）必然错位 ──")
do {
    var wrongCount = 0
    for i in 0..<pageCount {
        let v = visiblePage(off: bandOffset(index: i), origin: stackedPageOrigin)
        print("  点第 \(i) 项 -> 屏幕上是第 \(v.map(String.init) ?? "?") 页")
        // 4 个页面都在 x=0，所以 offset 非 0 时屏幕上**一个完整页面都没有**
        if v != i { wrongCount += 1 }
    }
    check(wrongCount > 0, "stacked layout cannot satisfy the contract (expected mismatches)")
}

print("── 2. HStack 布局满足契约 ──")
do {
    for i in 0..<pageCount {
        let v = visiblePage(off: bandOffset(index: i), origin: hstackPageOrigin)
        check(v == i, "tap \(i) shows page \(i) (got \(v.map(String.init) ?? "nil"))")
    }
}

print("── 3. 页面带总宽度与裁剪 ──")
do {
    let total = CGFloat(pageCount) * W
    check(abs(total - 1560) < 0.001, "strip width = 4W = \(total)")
    // 最后一页的右边缘等于整条带宽度
    let lastRight = hstackPageOrigin(pageCount - 1) + W
    check(abs(lastRight - total) < 0.001, "last page right edge = strip width")
    // 带滚到第 3 页时，offset = -3W，最后一页正好填满屏幕
    check(abs(hstackPageOrigin(3) + bandOffset(index: 3)) < 0.001, "page 3 visible at offset -3W")
}

print("── 4. 拖动跟手：offset 与位置连续 ──")
do {
    // 位置 1.5（正拖到一半）时，屏幕左边缘应落在第 1 页与第 2 页交界
    let off: CGFloat = -1.5 * W
    let leftEdgeInStrip = -off            // 屏幕上 x=0 对应带内坐标
    check(abs(leftEdgeInStrip - 1.5 * W) < 0.001, "drag midpoint maps to strip x = 1.5W")
    // 4 个页面都在带内且不重叠
    var covered: [(CGFloat, CGFloat)] = []
    for i in 0..<pageCount {
        let x = hstackPageOrigin(i)
        covered.append((x, x + W))
    }
    for i in 1..<pageCount {
        check(abs(covered[i].0 - covered[i - 1].1) < 0.001, "pages \(i-1)/\(i) tile without gap or overlap")
    }
}

print("── 5. Dock 命中测试：点每一格都落到正确的索引 ──")
do {
    for i in 0..<pageCount {
        let x = cellCenter(i)                       // 点在格中心
        let idx = Int(slotAt(x).rounded())
        check(idx == i, "tap centre of cell \(i) -> index \(idx)")
    }
    // 边界：点第一格最左
    check(Int(slotAt(dockPad + 0.5).rounded()) == 0, "left edge of cell 0 -> 0")
    // 点最后一格最右
    let lastRight = dockPad + cellW * CGFloat(pageCount) - 0.5
    check(Int(slotAt(lastRight).rounded()) == pageCount - 1, "right edge of last cell -> \(pageCount - 1)")
    // 超出边界要夹住
    check(Int(slotAt(-100).rounded()) == 0, "x < 0 clamps to 0")
    check(Int(slotAt(10_000).rounded()) == pageCount - 1, "x huge clamps to last")
}

print("── 6. dock 格中心与页面中心的对应关系 ──")
do {
    // 选中块中心 = pad + cellW*(pos+0.5)，pos 应等于当前页索引
    for i in 0..<pageCount {
        let pill = dockPad + cellW * (CGFloat(i) + 0.5)
        check(abs(pill - cellCenter(i)) < 0.001, "pill centre matches cell \(i) centre")
    }
    // 整条 dock 的左右留白对称
    let leftGap = cellCenter(0) - cellW / 2
    let rightGap = dockWidth - (cellCenter(pageCount - 1) + cellW / 2)
    check(abs(leftGap - dockPad) < 0.001, "left padding = 5 (got \(leftGap))")
    check(abs(rightGap - dockPad) < 0.001, "right padding = 5 (got \(rightGap))")
}

print("\n============================================================")
print("通过 \(passed) · 失败 \(failed)")
print("============================================================")
if failed > 0 { exit(1) }