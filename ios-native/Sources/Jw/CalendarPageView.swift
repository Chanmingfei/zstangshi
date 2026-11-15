import SwiftUI

/// 可 push 的整页校历（首页宫格「校历」、教务服务「校历」都指向这里，无参构造）。
///
/// 数据口径与 Android `CalendarPage` 一致：只放「真的推得出来的」——
/// 教学周次与日期区间由 `app.timetable?.semester.startDate` 经 `WeekMath` 推算；
/// 放假 / 调休 / 考试周这类逐日安排，App 没有可靠的内置数据源，就不罗列、不编造，
/// 只如实提示「以教务处官方校历为准」。
struct CalendarPageView: View {
    @EnvironmentObject private var app: AppState
    @State private var web: JwWebTarget?

    /// 教务处校历列表页。来源：Android `data/AcademicCalendar.kt` 的 `SOURCE_LIST_URL`，
    /// 不是 App 猜测的地址。
    private static let officialCalendarURL = "https://jwc.tstc.edu.cn/xlcx.htm"

    var body: some View {
        List {
            termSection
            weeksSection
            holidaySection
        }
        .listStyle(.insetGrouped)
        .background(T.bgPage)
        .navigationTitle("校历")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $web) { target in
            WebScreen(url: target.url, title: target.title)
                .environmentObject(app)
        }
    }

    // MARK: - 派生数据

    private var semester: Semester? { app.timetable?.semester }
    private var start: String { semester?.startDate ?? "" }

    private var totalWeeks: Int {
        max(1, min(WeekMath.openWeeksMax, semester?.totalWeeks ?? 20))
    }

    private var currentWeek: Int {
        WeekMath.currentWeek(start: start, totalWeeks: totalWeeks)
    }

    /// 有本地课表且开学日期合法，才能列出教学周。
    private var hasTerm: Bool {
        guard let s = semester else { return false }
        return WeekMath.isValid(s.startDate)
    }

    // MARK: - 学期信息 / 开学日期来源说明

    private var termSection: some View {
        Section {
            termCard
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                .listRowBackground(Color.clear)
        } header: {
            Text("学期信息")
        }
    }

    private var termCard: some View {
        Card(padding: 16) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(termName)
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    Spacer()
                    if hasTerm {
                        Text("第 \(currentWeek) 周")
                            .font(F.caption2)
                            .foregroundStyle(T.brand)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(T.brandSoft)
                            .clipShape(Capsule())
                    }
                }

                infoLine("第 1 周周一", hasTerm ? start : "未设置")
                infoLine("教学周数", "\(totalWeeks) 周")

                if let term = AcademicCalendar.term(for: WeekMath.today()) {
                    infoLine("内置校历学期", term.name)
                    infoLine("内置校历开学", "\(term.classStart) · 共 \(term.teachingWeeks) 周")
                }

                Text(sourceNote)
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func infoLine(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label)
                .font(F.caption)
                .foregroundStyle(T.textTertiary)
                .frame(width: 88, alignment: .leading)
            Text(value)
                .font(F.sub)
                .foregroundStyle(T.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var termName: String {
        let n = semester?.name ?? ""
        return n.isEmpty ? "本学期" : n
    }

    private var sourceNote: String {
        if hasTerm, let s = semester {
            let origin = s.startDateTouched
                ? "开学日期来自你在「我的 → 学期与教学周」里手动设置的值"
                : "开学日期随教务同步的课表带入，并由 App 归一到第 1 周周一"
            return origin
                + "。内置校历学期来自教务处 jwc.tstc.edu.cn（学校发布新校历后 App 更新一版）；上面的教学周次仅由开学日期推算。"
        }
        return "当前没有本地课表，开学日期未知。到「我的」里登录教务同步一次课表，或在「学期与教学周」里手动设置开学日期。内置校历学期来自教务处 jwc.tstc.edu.cn。"
    }

    // MARK: - 教学周列表

    @ViewBuilder
    private var weeksSection: some View {
        if hasTerm {
            Section {
                ForEach(1...totalWeeks, id: \.self) { w in
                    weekRow(w)
                        .listRowBackground(w == currentWeek ? T.brandSoft : T.bgSurface)
                }
            } header: {
                Text("教学周 · 共 \(totalWeeks) 周")
            } footer: {
                Text("周次与日期区间由开学日期推算，仅代表教学周顺序，不代表放假或调休安排。")
            }
        } else {
            Section {
                emptyCard
                    .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                    .listRowBackground(Color.clear)
            }
        }
    }

    private func weekRow(_ w: Int) -> some View {
        let on = w == currentWeek
        return HStack(spacing: 10) {
            Circle()
                .fill(on ? T.brand : T.textTertiary.opacity(0.35))
                .frame(width: 8, height: 8)
            Text("第 \(w) 周")
                .font(on ? F.bodyBold : F.body)
                .foregroundStyle(on ? T.brand : T.textPrimary)
            Spacer(minLength: 6)
            Text(WeekMath.weekRangeLabel(start, week: w))
                .font(F.sub)
                .foregroundStyle(T.textTertiary)
            if on {
                Text("本周")
                    .font(F.caption2)
                    .foregroundStyle(T.brand)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(T.brandSoft)
                    .clipShape(Capsule())
            }
        }
        .frame(minHeight: T.minTap)
    }

    private var emptyCard: some View {
        VStack(spacing: 12) {
            EmptyCard(title: "还没有本地课表",
                      subtitle: "到「我的」里登录教务同步一次课表，这里才能按开学日期列出教学周")
            Button {
                app.switchTab(4)
            } label: {
                Text("前往「我的」同步课表")
                    .font(F.bodyBold)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: T.minTap)
                    .background(T.brand)
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - 节假日提示（如实说明，不编造逐日数据）

    private var holidaySection: some View {
        Section {
            holidayCard
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                .listRowBackground(Color.clear)
            officialRow
        } header: {
            Text("节假日提示")
        } footer: {
            Text("教务 / 学工页面共享 App 内登录会话；官方校历为外部网页，由应用内浏览器打开。")
        }
    }

    private var holidayCard: some View {
        Card(padding: 14) {
            VStack(alignment: .leading, spacing: 8) {
                Label {
                    Text("放假、调休、考试周以官方校历为准")
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(T.warn)
                }
                Text("这类逐日安排 App 没有可靠的内置数据源，这里不逐条罗列，以免误导。请以教务处校历与学校通知为准；上面的教学周列表只表示由开学日期推算出来的周次顺序。")
                    .font(F.caption)
                    .foregroundStyle(T.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var officialRow: some View {
        Button {
            web = JwWebTarget(url: CalendarPageView.officialCalendarURL, title: "教务处校历")
        } label: {
            HStack(spacing: 12) {
                Label {
                    Text("查看教务处官方校历")
                        .font(F.body)
                        .foregroundStyle(T.textPrimary)
                } icon: {
                    Image(systemName: "safari")
                        .font(.system(size: 17))
                        .foregroundStyle(T.brand)
                        .frame(width: 28, alignment: .center)
                }
                Spacer(minLength: 6)
                Text("外部网页")
                    .font(F.caption2)
                    .foregroundStyle(T.textTertiary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(T.bgSunken)
                    .clipShape(Capsule())
            }
            .frame(minHeight: T.minTap)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
