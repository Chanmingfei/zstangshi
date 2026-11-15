import SwiftUI

/// 作息设置页：节次数量、第 1 节开始时间、每节时长与课间。
///
/// ⚠️ 这是**安卓没有 UI 入口**的能力（`ScheduleSettings` 有完整模型与 API，
/// 但 `Store.schedule()` 每次从默认重建，`sections[]` 永远无法持久化）。
/// iOS 端补全，并修掉安卓那个「改了也存不下来」的问题（`Store.setSchedule` 整体存 JSON）。
///
/// 时间推导规则（与安卓 `ScheduleSettings.startMinutes` 一致）：
///   第 N 节开始 = 第 1 节开始 + Σ(前面各节时长 + 各节课间)
struct ScheduleSettingsPage: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var draft = ScheduleSettings()
    @State private var startMinutes = 8 * 60      // 第 1 节开始（分钟）
    @State private var changed = false

    var body: some View {
        List {
            Section {
                Stepper(value: Binding(
                    get: { draft.clampedSectionCount },
                    set: { newVal in
                        draft.sectionCount = newVal
                        ensureSections()
                        changed = true
                    }
                ), in: ScheduleSettings.minSections...ScheduleSettings.maxSections) {
                    HStack {
                        Text("每天节数")
                        Spacer()
                        Text("\(draft.clampedSectionCount) 节")
                            .foregroundStyle(T.brand)
                    }
                }

                HStack {
                    Text("第 1 节开始")
                    Spacer()
                    DatePicker("", selection: Binding(
                        get: { dateFromMinutes(startMinutes) },
                        set: { d in
                            startMinutes = minutesFromDate(d)
                            draft.firstClassStart = ScheduleSettings.formatHm(startMinutes)
                            changed = true
                        }
                    ), displayedComponents: .hourAndMinute)
                    .labelsHidden()
                }
            } header: {
                Text("基本作息")
            } footer: {
                Text("唐山师范学院标准作息为 12 节、08:00 开始；每节 45 分钟。")
            }

            Section {
                ForEach(1...draft.clampedSectionCount, id: \.self) { n in
                    sectionRow(n)
                }
            } header: {
                Text("每节时长与课间")
            } footer: {
                Text("课间指「这一节下课后到下一节上课前」的休息分钟数。总表见下方预览。")
            }

            Section("预览") {
                ForEach(1...draft.clampedSectionCount, id: \.self) { n in
                    HStack {
                        Text("第 \(n) 节")
                            .font(F.callout)
                        Spacer()
                        Text("\(draft.startTime(n)) - \(draft.endTime(n))")
                            .font(F.callout)
                            .monospacedDigit()
                            .foregroundStyle(T.textSecondary)
                    }
                }
            }

            Section {
                Button("恢复默认（唐山师范学院作息）") {
                    draft = ScheduleSettings()
                    startMinutes = ScheduleSettings.parseHm(ScheduleSettings.defaultFirstClassStart)
                    changed = true
                    Haptics.light()
                }
                .foregroundStyle(T.brand)
            }
        }
        .navigationTitle("作息时间")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { save() }
                    .fontWeight(.bold)
                    .disabled(!changed)
            }
        }
        .onAppear { load() }
    }

    // MARK: - 单节编辑

    private func sectionRow(_ n: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("第 \(n) 节")
                    .font(F.callout.weight(.semibold))
                Spacer()
                Text("\(draft.startTime(n)) - \(draft.endTime(n))")
                    .font(F.small)
                    .monospacedDigit()
                    .foregroundStyle(T.textSecondary)
            }

            HStack(spacing: 12) {
                HStack(spacing: 4) {
                    Text("时长")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                    Stepper(value: Binding(
                        get: { sectionDuration(n) },
                        set: { v in
                            setDuration(n, v)
                            changed = true
                        }
                    ), in: 1...300) {
                        Text("\(sectionDuration(n)) 分")
                            .font(F.small)
                            .monospacedDigit()
                            .frame(width: 54, alignment: .trailing)
                    }
                    .labelsHidden()
                    .fixedSize()
                }

                if n < draft.clampedSectionCount {
                    HStack(spacing: 4) {
                        Text("课间")
                            .font(F.micro)
                            .foregroundStyle(T.textTertiary)
                        Stepper(value: Binding(
                            get: { sectionBreak(n) },
                            set: { v in
                                setBreak(n, v)
                                changed = true
                            }
                        ), in: 0...600) {
                            Text("\(sectionBreak(n)) 分")
                                .font(F.small)
                                .monospacedDigit()
                                .frame(width: 54, alignment: .trailing)
                        }
                        .labelsHidden()
                        .fixedSize()
                    }
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func sectionDuration(_ n: Int) -> Int {
        let idx = max(0, min(draft.sections.count - 1, n - 1))
        return draft.sections[idx].durationMinutes
    }

    private func sectionBreak(_ n: Int) -> Int {
        let idx = max(0, min(draft.sections.count - 1, n - 1))
        return draft.sections[idx].breakAfterMinutes
    }

    private func setDuration(_ n: Int, _ v: Int) {
        ensureSections()
        let idx = max(0, min(draft.sections.count - 1, n - 1))
        draft.sections[idx].durationMinutes = max(1, min(300, v))
    }

    private func setBreak(_ n: Int, _ v: Int) {
        ensureSections()
        let idx = max(0, min(draft.sections.count - 1, n - 1))
        draft.sections[idx].breakAfterMinutes = max(0, min(600, v))
    }

    private func ensureSections() {
        while draft.sections.count < ScheduleSettings.maxSections {
            draft.sections.append(ScheduleSettings.Section(draft.sections.count + 1, 45, 10))
        }
    }

    // MARK: - 时间工具

    private func dateFromMinutes(_ m: Int) -> Date {
        var c = DateComponents()
        c.hour = m / 60
        c.minute = m % 60
        return Calendar.current.date(from: c) ?? Date()
    }

    private func minutesFromDate(_ d: Date) -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        return (c.hour ?? 8) * 60 + (c.minute ?? 0)
    }

    // MARK: - 载入 / 保存

    private func load() {
        draft = app.schedule
        startMinutes = ScheduleSettings.parseHm(draft.firstClassStart)
        if startMinutes < 0 { startMinutes = 8 * 60 }
    }

    private func save() {
        draft.firstClassStart = ScheduleSettings.formatHm(startMinutes)
        app.setSchedule(draft)
        Haptics.success()
        app.showToast("作息已更新")
        dismiss()
    }
}