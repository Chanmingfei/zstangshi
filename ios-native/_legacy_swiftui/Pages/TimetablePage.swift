import SwiftUI

/// 课表页（对齐 Android `pages/TimetablePage.kt` + `ui/TimetableGridView.kt`）。
///
/// 结构：
///   顶部：标题（学期简称）+ ‹ 第 N 周 › + ＋（新增课程）
///   网格：左节次栏（46pt）+ 7 天 × N 节；星期表头 34pt 含日期
///   右下：翻到别的周时浮出「回到本周」
///
/// 关键交互：
///   · 左右滑 = 上一周 / 下一周，跟手位移 + 松手翻页；边界有 28% 阻尼
///   · 翻页时先滑出屏幕、在不可见阶段换数据、再从另一侧滑入（安卓同款，避免掉帧）
///   · 点周次文字弹周次网格；点课程看详情；点空格新建
struct TimetablePage: View {
    @EnvironmentObject private var app: AppState
    let width: CGFloat

    @State private var week = 1
    @State private var didInitWeek = false
    @State private var weekPickerShown = false
    @State private var detail: CourseDetail?
    /// 课程编辑器（nil 且 editorShown=true 表示新建）
    @State private var editorShown = false
    @State private var editingCourse: Course?
    @State private var editingSession: Session?
    @State private var editorDay = 1
    @State private var editorSection = 0
    @State private var dragDX: CGFloat = 0
    @State private var flipping = false
    @State private var showTodayFab = false

    /// 几何常量（照抄 TimetableGridView.kt:81-82）
    private let headH: CGFloat = 34
    private let slotW: CGFloat = 46

    struct CourseDetail: Identifiable {
        let id = UUID()
        let course: Course
        let session: Session
        let day: Int
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            if let t = app.timetable, !t.courses.isEmpty {
                gridArea(t)
            } else {
                emptyState
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(T.bgPage.ignoresSafeArea())
        .overlay(alignment: .bottomTrailing) { todayFab }
        .onAppear { reload() }
        .onChange(of: app.timetable?.semester.id) { _, _ in reload() }
        .onReceive(NotificationCenter.default.publisher(for: .zstJumpToThisWeek)) { _ in
            backToThisWeek()
        }
        .sheet(item: $detail) { d in
            CourseDetailSheet(detail: d, week: week, schedule: app.schedule,
                              onEdit: {
                                  editingCourse = d.course
                                  editingSession = d.session
                                  editorDay = d.day
                                  editorSection = d.session.startSection
                                  detail = nil
                                  DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                                      editorShown = true
                                  }
                              })
        }
        .sheet(isPresented: $weekPickerShown) {
            WeekPickerSheet(week: $week, totalWeeks: totalWeeks)
                .presentationDetents([.height(sheetHeight)])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $editorShown, onDismiss: {
            editingCourse = nil
            editingSession = nil
        }) {
            CourseEditor(course: editingCourse,
                         session: editingSession,
                         initialDay: editorDay,
                         initialSection: editorSection,
                         onSaved: { reload() })
                .environmentObject(app)
        }
    }

    // MARK: - 顶部栏

    private var header: some View {
        HStack(spacing: 0) {
            Text(termTitle)
                .font(F.title3)
                .foregroundStyle(T.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            if hasTimetable {
                stepButton("chevron.left", label: "上一周") {
                    flip(to: week - 1)
                }
                .frame(width: 36, height: 34)

                Button {
                    weekPickerShown = true
                } label: {
                    Text("第 \(week) 周")
                        .font(F.footnote)
                        .foregroundStyle(T.textPrimary)
                        .frame(width: 82, height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(T.brandSoft)
                        )
                }
                .buttonStyle(.pressable(0.96))

                stepButton("chevron.right", label: "下一周") {
                    flip(to: week + 1)
                }
                .frame(width: 36, height: 34)
            }

            Button {
                // 新增课程：安卓要求先有课表，否则 Toast 拒绝
                guard app.timetable != nil else {
                    Haptics.warning()
                    app.showToast("先同步一次课表，再手工加课")
                    return
                }
                Haptics.light()
                editingCourse = nil
                editingSession = nil
                // 默认落在今天所在星期
                let today = Dates.dayOfWeek(Dates.todayLocal())
                editorDay = today >= 1 && today <= 7 ? today : 1
                editorSection = 0
                editorShown = true
            } label: {
                Text("＋")
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(T.brand)
                    .frame(width: 40, height: 34)
            }
            .buttonStyle(.pressable(0.94))
            .accessibilityLabel("新增课程")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private func stepButton(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(T.textPrimary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.pressable(0.9))
        .accessibilityLabel(label)
    }

    private var termTitle: String {
        guard let name = app.timetable?.semester.name, !name.isEmpty else { return "课表" }
        return WeekMath.shortTermName(name)
    }

    private var hasTimetable: Bool {
        !(app.timetable?.courses.isEmpty ?? true)
    }

    private var totalWeeks: Int {
        let n = app.timetable?.semester.totalWeeks ?? 20
        return n > 0 ? n : 20
    }

    // MARK: - 网格

    private func gridArea(_ t: Timetable) -> some View {
        GeometryReader { geo in
            let n = max(app.schedule.clampedSectionCount,
                        min(ScheduleSettings.maxSections, t.maxSectionUsed))
            let gridH = max(geo.size.height, headH + CGFloat(n) * 52)
            let overlay = WeekOverlay.build(t, start: t.semester.startDate, week: week)

            ScrollView(.vertical, showsIndicators: false) {
                TimetableGridView(
                    timetable: t,
                    week: week,
                    overlay: overlay,
                    schedule: app.schedule,
                    sectionCount: n,
                    headHeight: headH,
                    slotWidth: slotW,
                    onCourseTap: { course, session, day in
                        detail = CourseDetail(course: course, session: session, day: day)
                    },
                    onEmptyTap: { day, section in
                        // 点空格 = 就在这一格新建课程（安卓 TimetablePage.kt:127-132）
                        guard app.timetable != nil else {
                            Haptics.warning()
                            app.showToast("先同步一次课表，再手工加课")
                            return
                        }
                        Haptics.light()
                        editingCourse = nil
                        editingSession = nil
                        editorDay = day
                        editorSection = section
                        editorShown = true
                    }
                )
                .frame(width: geo.size.width, height: gridH)
                .offset(x: dragDX)
                .opacity(1 - min(0.28, abs(dragDX) / max(1, geo.size.width) * 0.5))
                // 用 highPriorityGesture：课表页的「横滑翻周」必须优先于外层 PageDeck 的「横滑切页」，
                // 否则在课表页左右滑会切到别的 tab 而不是翻周（这正是安卓要「主页面不接管横滑」的原因）。
                .highPriorityGesture(
                    DragGesture(minimumDistance: 12)
                        .onChanged { v in
                            guard !flipping else { return }
                            let dx = v.translation.width
                            let dy = v.translation.height
                            guard abs(dx) > abs(dy) * 1.3 else { return }
                            let atEdge = (dx < 0 && week >= totalWeeks) || (dx > 0 && week <= 1)
                            dragDX = dx * (atEdge ? 0.28 : 0.82)
                        }
                        .onEnded { v in
                            guard !flipping else { return }
                            let dx = v.translation.width
                            let threshold = max(60, geo.size.width * 0.20)
                            if abs(dx) < threshold || abs(dx) < abs(v.translation.height) * 1.3 {
                                springBack()
                            } else {
                                flip(to: week + (dx < 0 ? 1 : -1), width: geo.size.width)
                            }
                        }
                )
                // 底部留出胶囊高度，最后几节不被压住
                .padding(.bottom, T.dockBand)
            }
        }
    }

    private var emptyState: some View {
        VStack {
            Spacer()
            EmptyCard(title: "还没有课表",
                      subtitle: "到「我的」里登录教务同步一次，之后没网也能看",
                      icon: "calendar")
                .padding(.horizontal, T.pagePadding)
            Spacer()
            Spacer().frame(height: T.dockBand + 40)
        }
    }

    // MARK: - 「回到本周」

    @ViewBuilder
    private var todayFab: some View {
        if showTodayFab {
            Button {
                Haptics.light()
                backToThisWeek()
            } label: {
                Text("回到本周")
                    .font(F.captionBold)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .frame(height: 42)
                    .background(Capsule(style: .continuous).fill(T.brand))
                    .shadow(color: .black.opacity(0.16), radius: 8, y: 3)
            }
            .buttonStyle(.pressable(0.95))
            .padding(.trailing, 18)
            .padding(.bottom, T.dockBand + 12)
            .transition(.scale(scale: 0.82).combined(with: .opacity))
        }
    }

    // MARK: - 数据与动画

    private var currentWeekValue: Int {
        guard let t = app.timetable else { return 1 }
        let w = WeekMath.currentWeek(startDate: t.semester.startDate,
                                     totalWeeks: totalWeeks)
        return max(1, min(totalWeeks, w))
    }

    private func reload() {
        guard let t = app.timetable, !t.courses.isEmpty else {
            week = 1
            didInitWeek = false
            updateFab()
            return
        }
        // 只在首次载入时定位到本周；之后切 tab / 编辑课程都保留用户正在看的那一周
        if !didInitWeek {
            week = currentWeekValue
            didInitWeek = true
        }
        week = max(1, min(totalWeeks, week))
        updateFab()
    }

    private func updateFab() {
        let shouldShow = hasTimetable && week != currentWeekValue
        guard shouldShow != showTodayFab else { return }
        withAnimation(M.lift) { showTodayFab = shouldShow }
    }

    private func backToThisWeek() {
        flip(to: currentWeekValue)
    }

    /// 翻到指定周。先滑出、离屏时换数据、再从另一侧滑入（安卓同款时序）。
    private func flip(to target: Int, width gWidth: CGFloat? = nil) {
        guard target >= 1, target <= totalWeeks, target != week else {
            springBack()
            return
        }
        guard !flipping else { return }
        let w = gWidth ?? width
        let dir: CGFloat = target > week ? 1 : -1
        let out = -dir * w * 0.32

        flipping = true
        withAnimation(M.pageOut) {
            dragDX = out
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.14) {
            week = target
            updateFab()
            dragDX = -out
            withAnimation(M.pageIn) {
                dragDX = 0
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.30) {
                flipping = false
            }
        }
    }

    private func springBack() {
        withAnimation(M.pageIn) { dragDX = 0 }
    }

    /// 周次选择弹层的高度（5 列 × ceil(n/5) 行）
    private var sheetHeight: CGFloat {
        let rows = (totalWeeks + 4) / 5
        return CGFloat(96 + rows * 48)
    }
}

// MARK: - 课表网格
//
// 对应安卓 `ui/TimetableGridView.kt` 的自绘网格。SwiftUI 用 Canvas 重绘，
// 几何、配色、斑马纹、课程块布局全部逐值对齐。

struct TimetableGridView: View {
    let timetable: Timetable
    let week: Int
    let overlay: WeekOverlay
    let schedule: ScheduleSettings
    let sectionCount: Int
    let headHeight: CGFloat
    let slotWidth: CGFloat
    let onCourseTap: (Course, Session, Int) -> Void
    /// 点空格（星期 1-7，节次 1-N）
    var onEmptyTap: ((Int, Int) -> Void)? = nil

    @Environment(\.colorScheme) private var colorScheme
    private let dayNames = ["一", "二", "三", "四", "五", "六", "日"]

    private struct Block: Identifiable {
        let id = UUID()
        let course: Course
        let session: Session
        let rect: CGRect
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            let cellW = (w - slotWidth) / 7
            let cellH = max(1, (h - headHeight) / CGFloat(max(1, sectionCount)))
            let blocks = computeBlocks(cellW: cellW, cellH: cellH)

            ZStack(alignment: .topLeading) {
                // 背景 + 斑马纹 + 网格线 + 节次栏 + 表头（全部在一次 Canvas 里）
                Canvas { ctx, size in
                    draw(context: &ctx, size: size,
                         cellW: cellW, cellH: cellH)
                }
                // 点空格 → 在那一格新建课程。
                // 用一个覆盖整块网格的透明层接手势，提前于 Canvas 但晚于课程块（课程块在上层先命中）。
                .contentShape(Rectangle())
                .onTapGesture { location in
                    guard let onEmptyTap else { return }
                    guard location.x >= slotWidth, location.y >= headHeight else { return }
                    let day = Int((location.x - slotWidth) / cellW) + 1
                    let section = Int((location.y - headHeight) / cellH) + 1
                    guard day >= 1, day <= 7, section >= 1, section <= sectionCount else { return }
                    onEmptyTap(day, section)
                }

                // 课程块（用真实 View 以便响应点击）
                ForEach(blocks) { b in
                    courseChip(b)
                        .frame(width: b.rect.width, height: b.rect.height)
                        .position(x: b.rect.midX, y: b.rect.midY)
                        .onTapGesture {
                            Haptics.light()
                            onCourseTap(b.course, b.session, b.session.dayOfWeek)
                        }
                }
            }
        }
    }

    // MARK: 几何

    private func computeBlocks(cellW: CGFloat, cellH: CGFloat) -> [Block] {
        // 按「星期 + 起止节次」聚合：同一格多门课（真冲突）横向平分
        var grouped: [String: [(Course, Session)]] = [:]
        var order: [String] = []
        for c in timetable.courses {
            for s in c.sessions {
                guard s.isActive(week: week) else { continue }
                if overlay.cancelled[WeekOverlay.fingerprint(s)] != nil { continue }
                let key = "\(s.dayOfWeek):\(s.startSection)-\(s.endSection)"
                if grouped[key] == nil { order.append(key) }
                grouped[key, default: []].append((c, s))
            }
        }

        var out: [Block] = []
        let inset: CGFloat = 1.5
        for key in order {
            guard let list = grouped[key], !list.isEmpty else { continue }
            let count = list.count
            for (i, pair) in list.enumerated() {
                let (c, s) = pair
                let day = s.dayOfWeek
                let left = slotWidth + CGFloat(day - 1) * cellW
                let top = headHeight + CGFloat(s.startSection - 1) * cellH
                let fullW = cellW
                let partW = fullW / CGFloat(count)
                var r = CGRect(x: left + CGFloat(i) * partW,
                               y: top,
                               width: partW,
                               height: CGFloat(s.endSection - s.startSection + 1) * cellH)
                r = r.insetBy(dx: inset, dy: inset)
                out.append(Block(course: c, session: s, rect: r))
            }
        }
        return out
    }

    // MARK: 绘制背景

    private func draw(context: inout GraphicsContext, size: CGSize,
                      cellW: CGFloat, cellH: CGFloat) {
        let n = max(1, sectionCount)
        let w = size.width
        let h = size.height

        // Canvas 里无法从 context 读 colorScheme，统一用 SwiftUI 的动态色：
        // SwiftUI 的 Color 在 Canvas 里会自动按当前环境解析明暗。
        let surface = T.bgSurface
        let page = T.bgPage
        let border = T.borderSubtle
        let ink = T.textPrimary
        let sub = T.textTertiary
        let brand = T.brand
        let isDark = colorScheme == .dark

        // 页面底色
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(page))

        // 星期表头底
        context.fill(Path(CGRect(x: 0, y: 0, width: w, height: headHeight)),
                     with: .color(surface))

        // 斑马纹：偶数节次铺一层极淡的底
        let zebraAlpha = isDark ? 0.05 : 0.03
        for i in 0..<n where i % 2 == 0 {
            let top = headHeight + CGFloat(i) * cellH
            context.fill(Path(CGRect(x: slotWidth, y: top, width: w - slotWidth, height: cellH)),
                         with: .color((isDark ? Color.white : Color.black).opacity(zebraAlpha)))
        }

        // 网格线
        let lineW: CGFloat = 0.6
        for i in 0...n {
            let y = headHeight + CGFloat(i) * cellH
            context.stroke(Path { p in
                p.move(to: CGPoint(x: 0, y: y))
                p.addLine(to: CGPoint(x: w, y: y))
            }, with: .color(border), lineWidth: lineW)
        }
        for i in 0...7 {
            let x = slotWidth + CGFloat(i) * cellW
            context.stroke(Path { p in
                p.move(to: CGPoint(x: x, y: headHeight))
                p.addLine(to: CGPoint(x: x, y: h))
            }, with: .color(border), lineWidth: lineW)
        }

        // 星期表头文字（周几 + 日期）
        let todayDow = Dates.dayOfWeek(Dates.todayLocal())
        let dates = WeekMath.weekDates(timetable.semester.startDate, week: week)
        for i in 1...7 {
            let cx = slotWidth + (CGFloat(i) - 0.5) * cellW
            let isToday = i == todayDow
            let c = isToday ? brand : ink

            context.draw(
                Text("周" + dayNames[i - 1])
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(c),
                at: CGPoint(x: cx, y: headHeight * 0.34), anchor: .center)

            if dates.count >= i, !dates[i - 1].isEmpty {
                context.draw(
                    Text(dates[i - 1])
                        .font(.system(size: 9.5))
                        .foregroundColor(isToday ? brand : sub),
                    at: CGPoint(x: cx, y: headHeight * 0.76), anchor: .center)
            }
        }

        // 节次栏：节次号 + 起止时间
        for s in 1...n {
            let top = headHeight + CGFloat(s - 1) * cellH
            context.draw(
                Text("\(s)").font(.system(size: 11)).foregroundColor(ink),
                at: CGPoint(x: slotWidth / 2, y: top + cellH * 0.36), anchor: .center)

            context.draw(
                Text(schedule.startTime(s)).font(.system(size: 7.5)).foregroundColor(sub),
                at: CGPoint(x: slotWidth / 2, y: top + cellH * 0.62), anchor: .center)

            context.draw(
                Text(schedule.endTime(s)).font(.system(size: 7.5)).foregroundColor(sub),
                at: CGPoint(x: slotWidth / 2, y: top + cellH * 0.82), anchor: .center)
        }
    }

    // MARK: 课程块

    private func courseChip(_ b: Block) -> some View {
        let idx = abs(b.course.colorIndex) % 8
        let textColor = T.courseColor(idx)

        return ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(T.courseBg(idx))
                .overlay(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .strokeBorder(T.courseColor(idx).opacity(0.18), lineWidth: 0.5)
                )

            // 左侧色条
            HStack(spacing: 0) {
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(T.courseColor(idx))
                    .frame(width: 2.5)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .padding(.leading, 2)

            VStack(alignment: .leading, spacing: 1) {
                Text(b.course.name)
                    .font(F.courseChip)
                    .foregroundStyle(textColor)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
                    .multilineTextAlignment(.leading)

                if b.rect.height > 34, !b.session.location.isEmpty {
                    Text(b.session.location)
                        .font(F.nano3)
                        .foregroundStyle(T.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, 6)
            .padding(.trailing, 3)
            .padding(.top, 3)
        }
        .contentShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
    }
}

// MARK: - 周次选择

struct WeekPickerSheet: View {
    @Binding var week: Int
    let totalWeeks: Int

    private let cols = 5
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("选择周次")
                .font(F.headline)
                .foregroundStyle(T.textPrimary)
                .padding(.bottom, 4)

            Text("当前第 \(week) 周 · 共 \(totalWeeks) 周")
                .font(F.tiny)
                .foregroundStyle(T.textTertiary)
                .padding(.bottom, 10)

            let rows = (totalWeeks + cols - 1) / cols
            VStack(spacing: 6) {
                ForEach(0..<rows, id: \.self) { r in
                    HStack(spacing: 6) {
                        ForEach(0..<cols, id: \.self) { c in
                            let w = r * cols + c + 1
                            if w <= totalWeeks {
                                Button {
                                    Haptics.select()
                                    week = w
                                    dismiss()
                                } label: {
                                    Text("\(w)")
                                        .font(week == w ? F.footnoteBold : F.footnote)
                                        .foregroundStyle(week == w ? .white : T.textPrimary)
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 42)
                                        .background(
                                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                                .fill(week == w ? T.brand : T.brandSoft)
                                        )
                                }
                                .buttonStyle(.pressable(0.94))
                            } else {
                                Color.clear.frame(height: 42)
                            }
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, 4)
        .presentationBackground(T.bgSurface)
    }
}

// MARK: - 课程详情

struct CourseDetailSheet: View {
    let detail: TimetablePage.CourseDetail
    let week: Int
    let schedule: ScheduleSettings
    /// 「编辑这门课」回调（安卓 TimetablePage.kt:453-463）
    var onEdit: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    private let dayNames = ["一", "二", "三", "四", "五", "六", "日"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // 头部：色点 + 课名
                HStack(spacing: 10) {
                    Circle()
                        .fill(T.courseColor(detail.course.colorIndex))
                        .frame(width: 10, height: 10)
                    Text(detail.course.name)
                        .font(F.title2)
                        .foregroundStyle(T.textPrimary)
                    Spacer(minLength: 0)
                }

                Text(subline)
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .padding(.top, 8)
                    .padding(.bottom, 16)

                // 字段表
                VStack(spacing: 0) {
                    row("时间", schedule.startTime(detail.session.startSection)
                        + " - " + schedule.endTime(detail.session.endSection))
                    if !detail.session.campus.isEmpty {
                        row("校区", detail.session.campus)
                    }
                    if !detail.session.location.isEmpty {
                        row("地点", detail.session.location)
                    }
                    if !detail.session.weeksRaw.isEmpty {
                        row("周次", detail.session.weeksRaw)
                    }
                    if detail.course.credit > 0 {
                        row("学分", trimZero(detail.course.credit))
                    }
                    if !detail.course.note.isEmpty {
                        row("备注", detail.course.note)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(T.bgSurface)
                .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                        .strokeBorder(T.borderSubtle, lineWidth: 0.7)
                )

                // 「编辑这门课」固定底部按钮（安卓 TimetablePage.kt:453-463）
                if let onEdit {
                    Button {
                        Haptics.light()
                        onEdit()
                    } label: {
                        Text("编辑这门课")
                    }
                    .buttonStyle(OutlineButtonStyle())
                    .padding(.top, 14)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(T.bgPage)
    }

    private var subline: String {
        let d = max(1, min(7, detail.day))
        var s = "周" + dayNames[d - 1] + "  ·  第 \(detail.session.startSection)"
        if detail.session.endSection != detail.session.startSection {
            s += "-\(detail.session.endSection)"
        }
        s += " 节"
        let teacher = detail.session.teacherFor(week: week, courseTeacher: detail.course.teacher)
        if !teacher.isEmpty { s += "  ·  " + teacher }
        return s
    }

    private func row(_ label: String, _ value: String) -> some View {
        FieldRow(label: label, value: value)
    }

    private func trimZero(_ v: Double) -> String {
        v == v.rounded() ? String(Int(v)) : String(v)
    }
}