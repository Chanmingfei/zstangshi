import SwiftUI

/// 个人课表页（对应 Android `TimetablePage`）。
///
/// 顶栏：标题 + ‹ [第 N 周] › + ＋；中间 7×N 网格；右下角「回到本周」。
/// 手势：在网格上左右滑 = 上一周 / 下一周（跟手位移 + 松手翻页），
/// 与 Android 一样**不用**系统分页手势 —— 那会和纵向滚动抢事件。
struct TimetableView: View {
    @EnvironmentObject private var app: AppState

    @State private var showWeekPicker = false
    @State private var detail: CourseHit?
    @State private var dragX: CGFloat = 0
    @State private var dragging = false

    private var totalWeeks: Int {
        let start = app.timetable?.semester.startDate ?? ""
        return max(WeekMath.openWeeksMin, WeekMath.openWeekSpan(start: start))
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            if let t = app.timetable, !t.isEmpty {
                TimetableGrid(timetable: t, week: app.week, schedule: app.schedule) { course, session in
                    detail = CourseHit(course: course, session: session)
                }
                .offset(x: dragX)
                .simultaneousGesture(weekDrag)
                .overlay(alignment: .bottomTrailing) { backToWeekButton }
            } else {
                Spacer()
                EmptyCard(title: "还没有课表",
                          subtitle: "到「我的」里登录教务同步一次，之后没网也能看")
                    .padding(.horizontal, T.pagePadding)
                Spacer()
                Spacer()
            }
        }
        .background(T.bgPage)
        .sheet(isPresented: $showWeekPicker) {
            WeekPickerSheet(current: app.week, total: totalWeeks) { w in
                withAnimation(PageDeck<EmptyView>.curve(0.2)) { app.week = w }
            }
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $detail) { hit in
            CourseDetailSheet(course: hit.course, session: hit.session, week: app.week, schedule: app.schedule)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    // MARK: - 顶栏

    private var header: some View {
        HStack(spacing: 0) {
            Text("我的课表")
                .font(F.pageTitle)
                .foregroundStyle(T.textPrimary)
            Spacer(minLength: 6)

            stepButton("chevron.left") { changeWeek(-1) }

            Button {
                showWeekPicker = true
            } label: {
                Text("第 \(app.week) 周")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(T.textPrimary)
                    .frame(width: 82, height: 34)
                    .background(T.brandSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            }
            .buttonStyle(.plain)

            stepButton("chevron.right") { changeWeek(1) }

            Button {
                app.showToast("新增课程请在 Mac 端的课程编辑器里完成")
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(T.brand)
                    .frame(width: 40, height: 34)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private func stepButton(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(T.textPrimary)
                .frame(width: 36, height: 34)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func changeWeek(_ delta: Int) {
        let target = app.week + delta
        guard target >= 1, target <= totalWeeks else { return }
        withAnimation(PageDeck<EmptyView>.curve(0.22)) { app.week = target }
    }

    // MARK: - 回到本周

    @ViewBuilder
    private var backToWeekButton: some View {
        if app.week != app.currentWeek() {
            Button {
                withAnimation(PageDeck<EmptyView>.curve(0.24)) { app.week = app.currentWeek() }
            } label: {
                Text("回到本周")
                    .font(.system(size: 12.5, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .frame(height: 42)
                    .background(T.brand)
                    .clipShape(Capsule())
                    .shadow(color: T.brand.opacity(0.28), radius: 8, y: 4)
            }
            .buttonStyle(.plain)
            .padding(.trailing, 18)
            .padding(.bottom, 12)
            .transition(.opacity.combined(with: .scale(scale: 0.92)))
        }
    }

    // MARK: - 周次手势

    private var weekDrag: some Gesture {
        DragGesture(minimumDistance: 20)
            .onChanged { value in
                let dx = value.translation.width
                // 只接管横向为主的拖动，纵向留给 ScrollView
                guard abs(dx) > abs(value.translation.height) else { return }
                dragging = true
                // 到边界时阻尼，避免「拖着不动」的错觉
                let atEdge = (dx > 0 && app.week <= 1) || (dx < 0 && app.week >= totalWeeks)
                dragX = atEdge ? dx * 0.2 : dx * 0.55
            }
            .onEnded { value in
                guard dragging else { return }
                dragging = false
                let dx = value.translation.width
                let threshold: CGFloat = 56
                var target = app.week
                if dx < -threshold, app.week < totalWeeks { target += 1 }
                if dx > threshold, app.week > 1 { target -= 1 }

                withAnimation(PageDeck<EmptyView>.curve(0.24)) {
                    dragX = 0
                    app.week = target
                }
            }
    }
}

/// 点中的课程 + 时段（`sheet(item:)` 需要 Identifiable）
struct CourseHit: Identifiable {
    let id = UUID()
    let course: Course
    let session: Session
}
