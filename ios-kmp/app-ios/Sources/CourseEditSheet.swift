import SwiftUI
import Shared

/// 课程编辑抽屉 —— 对齐安卓：课程名 / 教师 / 教室 / 校区 / 星期 / 节次 / 周次芯片。
struct CourseEditSheet: View {
    @Environment(\.dismiss) private var dismiss

    /// nil = 新增；非 nil = 编辑（只有手动课能编辑/删除）
    let existing: Course?
    let defaultDay: Int
    let defaultSection: Int
    let maxWeek: Int
    let onSave: (_ name: String, _ teacher: String, _ room: String, _ campus: String,
                 _ day: Int, _ start: Int, _ end: Int, _ weeks: [Int], _ colorIndex: Int) -> Void
    let onDelete: (() -> Void)?

    @State private var name = ""
    @State private var teacher = ""
    @State private var room = ""
    @State private var campus = ""
    @State private var day = 1
    @State private var startSection = 1
    @State private var endSection = 1
    @State private var weeks: Set<Int> = []
    /// 课表配色（安卓弹窗里那排色圆）
    @State private var colorIndex: Int = 0

    private let weekNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    init(existing: Course?, defaultDay: Int, defaultSection: Int, maxWeek: Int,
         initialColor: Int = 0,
         onSave: @escaping (String, String, String, String, Int, Int, Int, [Int], Int) -> Void,
         onDelete: (() -> Void)? = nil) {
        self.existing = existing
        self.defaultDay = defaultDay
        self.defaultSection = defaultSection
        self.maxWeek = maxWeek
        self.onSave = onSave
        self.onDelete = onDelete

        if let c = existing, let s = c.sessions.first {
            _name = State(initialValue: c.name)
            _teacher = State(initialValue: c.teacher)
            _room = State(initialValue: s.location)
            _campus = State(initialValue: s.campus)
            _day = State(initialValue: Int(s.dayOfWeek))
            _startSection = State(initialValue: Int(s.startSection))
            _endSection = State(initialValue: Int(s.endSection))
            _weeks = State(initialValue: Set(s.weeks.map { Int(truncating: $0) }))
            _colorIndex = State(initialValue: Int(c.colorIndex))
        } else {
            _day = State(initialValue: defaultDay)
            _startSection = State(initialValue: defaultSection)
            _endSection = State(initialValue: defaultSection)
            _weeks = State(initialValue: Set(1...max(1, maxWeek)))
            _colorIndex = State(initialValue: initialColor)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("如：高等数学", text: $name)
                } header: { Text("课程名") }

                Section {
                    HStack {
                        TextField("教师", text: $teacher)
                        Divider()
                        TextField("教室", text: $room)
                    }
                } header: { Text("教师 / 教室") }

                Section {
                    TextField("如：东校区（可不填）", text: $campus)
                } header: { Text("校区") }

                Section {
                    Picker("星期", selection: $day) {
                        ForEach(1...7, id: \.self) { Text(weekNames[$0 - 1]).tag($0) }
                    }
                    HStack {
                        Picker("开始", selection: $startSection) {
                            ForEach(1...12, id: \.self) { Text("第 \($0) 节").tag($0) }
                        }
                        Picker("结束", selection: $endSection) {
                            ForEach(1...12, id: \.self) { Text("第 \($0) 节").tag($0) }
                        }
                    }
                    .onChange(of: startSection) { v in
                        if endSection < v { endSection = v }
                    }
                    Text("\(Sections.shared.start(section: Int32(startSection))) - \(Sections.shared.end(section: Int32(endSection)))")
                        .font(.caption).foregroundColor(.secondary)
                } header: { Text("节次") }

                Section {
                    weekChips
                    HStack(spacing: 8) {
                        quick("全选") { weeks = Set(1...max(1, maxWeek)) }
                        quick("单周") { weeks = Set(stride(from: 1, through: max(1, maxWeek), by: 2).map { $0 }) }
                        quick("双周") { weeks = Set(stride(from: 2, through: max(1, maxWeek), by: 2).map { $0 }) }
                    }
                    .padding(.vertical, 2)
                    Text("已选 \(weeks.count) 周 · \(TimetableEdit.shared.compressWeeks(weeks: weeks.sorted().map { KotlinInt(int: Int32($0)) }))周")
                        .font(.caption2).foregroundColor(.secondary)
                } header: {
                    Text("上课周次")
                }

                Section {
                    colorRow
                } header: {
                    Text("颜色")
                }

                if existing != nil, let del = onDelete {
                    Section {
                        Button("删除这门课", role: .destructive) {
                            del()
                            dismiss()
                        }
                    } footer: {
                        Text("只能删除手动添加的课程；教务同步来的课程删了下次还会回来。")
                    }
                }
            }
            .navigationTitle(existing == nil ? "新增课程" : "编辑课程")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(name, teacher, room, campus, day, startSection, endSection,
                               weeks.sorted(), colorIndex)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || weeks.isEmpty)
                }
            }
        }
    }

    private func quick(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.footnote)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.08))
                .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private let colorSwatches: [Color] = [
        Color(red: 0.20, green: 0.48, blue: 0.92), Color(red: 0.93, green: 0.45, blue: 0.30),
        Color(red: 0.30, green: 0.68, blue: 0.45), Color(red: 0.72, green: 0.52, blue: 0.95),
        Color(red: 0.90, green: 0.70, blue: 0.18), Color(red: 0.35, green: 0.70, blue: 0.78),
        Color(red: 0.88, green: 0.40, blue: 0.55),
    ]

    private var colorRow: some View {
        HStack(spacing: 10) {
            ForEach(Array(colorSwatches.enumerated()), id: \.offset) { pair in
                Button {
                    colorIndex = pair.offset
                } label: {
                    Circle()
                        .fill(pair.element)
                        .frame(width: 28, height: 28)
                        .overlay(
                            Circle().stroke(Color.black.opacity(colorIndex == pair.offset ? 0.85 : 0),
                                            lineWidth: colorIndex == pair.offset ? 2 : 0)
                        )
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }

    private var weekChips: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 7), spacing: 6) {
            ForEach(1...max(1, maxWeek), id: \.self) { w in
                Button {
                    if weeks.contains(w) { weeks.remove(w) } else { weeks.insert(w) }
                } label: {
                    Text("\(w)")
                        .font(.caption)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(weeks.contains(w)
                                    ? Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.15)
                                    : Color(UIColor.tertiarySystemFill))
                        .foregroundColor(weeks.contains(w) ? Color(red: 0.0, green: 0.48, blue: 1.0) : .primary)
                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }
}
