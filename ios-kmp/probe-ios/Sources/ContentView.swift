import SwiftUI
import UIKit

@MainActor
final class ProbeViewModel: ObservableObject {
    @Published var studentNo: String = ""
    @Published var password: String = ""
    @Published var lines: [ProbeLine] = []
    @Published var running: Bool = false

    private let probe = JwProbe()

    func start() async {
        guard !running else { return }
        running = true
        lines = [ProbeLine(text: "开始 \(Self.stamp())", mark: .info)]
        let result = await probe.run(studentNo: studentNo, password: password)
        lines.append(contentsOf: result)
        running = false
    }

    var plainText: String {
        lines.map { line in
            let prefix: String
            switch line.mark {
            case .ok: prefix = "✓ "
            case .fail: prefix = "✗ "
            case .warn: prefix = "! "
            case .info: prefix = "  "
            }
            return prefix + line.text
        }.joined(separator: "\n")
    }

    func copyLog() {
        UIPasteboard.general.string = plainText
    }

    private static func stamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f.string(from: Date())
    }
}

struct ContentView: View {
    @StateObject private var vm = ProbeViewModel()
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                form
                Divider()
                logList
            }
            .navigationTitle("掌上唐师 · iOS 探针")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(copied ? "已复制" : "复制日志") {
                        vm.copyLog()
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                    }
                    .disabled(vm.lines.isEmpty)
                }
            }
        }
    }

    private var form: some View {
        VStack(spacing: 12) {
            HStack {
                Text("学号").frame(width: 44, alignment: .leading).foregroundColor(.secondary)
                TextField("教务学号", text: $vm.studentNo)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .keyboardType(.asciiCapable)
                    .textFieldStyle(.roundedBorder)
            }
            HStack {
                Text("密码").frame(width: 44, alignment: .leading).foregroundColor(.secondary)
                SecureField("教务密码", text: $vm.password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .textFieldStyle(.roundedBorder)
            }
            Button {
                Task { await vm.start() }
            } label: {
                HStack(spacing: 8) {
                    if vm.running { ProgressView().progressViewStyle(.circular) }
                    Text(vm.running ? "正在验证…" : "开始验证")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color(red: 0.46, green: 0.08, blue: 0.10))
                .foregroundColor(.white)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .disabled(vm.running || vm.studentNo.isEmpty || vm.password.isEmpty)

            Text("只在本机发请求，不保存密码；日志里也不会出现密码与 Cookie 值。")
                .font(.caption2)
                .foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(16)
    }

    private var logList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 6) {
                ForEach(vm.lines) { line in
                    Text(line.text)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(color(of: line.mark))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
            }
            .padding(14)
        }
        .background(Color(UIColor.systemGroupedBackground))
    }

    private func color(of mark: ProbeLine.Mark) -> Color {
        switch mark {
        case .ok: return Color(red: 0.05, green: 0.45, blue: 0.20)
        case .fail: return Color(red: 0.72, green: 0.10, blue: 0.10)
        case .warn: return Color(red: 0.65, green: 0.40, blue: 0.02)
        case .info: return Color.primary
        }
    }
}
