import SwiftUI

/// 登录教务：学号 / 密码 / 记住我。
struct LoginView: View {
    @EnvironmentObject private var auth: JwAuth
    @Environment(\.dismiss) private var dismiss

    @State private var no = Keychain.studentNo
    @State private var pwd = ""
    @State private var remember = UserDefaults.standard.bool(forKey: "jw.remember")
    @State private var busy = false
    @State private var useHttp = UserDefaults.standard.bool(forKey: "jw.forceHttp")
    @State private var error = ""

    var body: some View {
        Form {
            Section {
                TextField("学号", text: $no)
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("教务密码", text: $pwd)
            } footer: {
                Text("密码只保存在本机 Keychain，不上传任何服务器。")
            }

            Section {
                Toggle("使用 http 访问（校外 443 不通时打开）", isOn: $useHttp)
                    .onChange(of: useHttp) { on in
                        auth.setForceHttp(on)
                    }
            } footer: {
                Text("教务在校外移动数据下经常只有 80 端口能通，打开后会以 http 访问；校内网络建议关闭。")
            }

            Section {
                Toggle("记住密码（下次自动登录）", isOn: $remember)
                    .onChange(of: remember) { on in
                        UserDefaults.standard.set(on, forKey: "jw.remember")
                        if !on { Keychain.deletePassword() }
                    }
            }

            if !error.isEmpty {
                Section { Text(error).font(.caption).foregroundColor(.red) }
            }

            Section {
                Button {
                    Task { await submit() }
                } label: {
                    HStack {
                        if busy { ProgressView().padding(.trailing, 6) }
                        Text(busy ? "登录中…" : "登录")
                            .frame(maxWidth: .infinity)
                    }
                }
                .disabled(busy || no.isEmpty || pwd.isEmpty)
            }
        }
        .navigationTitle("登录教务系统")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func submit() async {
        busy = true
        error = ""
        defer { busy = false }
        let ok = await auth.login(no: no, password: pwd)
        if ok {
            dismiss()
        } else {
            error = auth.message
        }
    }
}
