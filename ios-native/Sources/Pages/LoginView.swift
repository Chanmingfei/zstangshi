import SwiftUI

/// 登录页（对应 Android `LoginActivity`）。
///
/// 与 Android 的三点差异都是 iOS 平台约束，不是偷懒：
///   1. 密码存 Keychain（不是 UserDefaults）—— 见 `Store`；
///   2. 「显示/隐藏密码」用 SF Symbol 的 eye / eye.slash，状态由显式布尔驱动
///      （Android 那次踩过 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` 与 PASSWORD 位相与的坑，
///      这边不再依赖任何位运算推断）；
///   3. 键盘弹出时表单整体上移，靠 `ScrollView` + `safeAreaInset` 托住，避免遮挡按钮。
struct LoginView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var user = ""
    @State private var password = ""
    @State private var remember = false
    @State private var autoLogin = false
    @State private var pwdShown = false
    @State private var busy = false
    @State private var error = ""
    @FocusState private var focus: Field?

    private enum Field { case user, password }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                form
                actions
                footer
            }
            .padding(.horizontal, 22)
            .padding(.bottom, 40)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(T.bgPage)
        .overlay(alignment: .topTrailing) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(T.textSecondary)
                    .frame(width: 32, height: 32)
                    .background(T.bgSunken)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(16)
        }
        .onAppear(perform: prefill)
    }

    // MARK: - 头部

    private var header: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(T.brand)
                    .frame(width: 66, height: 66)
                Text("唐")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(.white)
            }
            .padding(.top, 64)

            Text("登录教务")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(T.textPrimary)

            Text("登录后可同步课表、成绩与学分绩点")
                .font(F.sub)
                .foregroundStyle(T.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, 26)
    }

    // MARK: - 表单

    private var form: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "person")
                    .font(.system(size: 14))
                    .foregroundStyle(T.textTertiary)
                    .frame(width: 22)
                TextField("学号", text: $user)
                    .font(F.body)
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .user)
                    .submitLabel(.next)
                    .onSubmit { focus = .password }
            }
            .padding(.horizontal, 14)
            .frame(height: 50)

            Rectangle().fill(T.border).frame(height: 0.6).padding(.leading, 46)

            HStack(spacing: 10) {
                Image(systemName: "lock")
                    .font(.system(size: 14))
                    .foregroundStyle(T.textTertiary)
                    .frame(width: 22)

                Group {
                    if pwdShown {
                        TextField("密码", text: $password)
                    } else {
                        SecureField("密码", text: $password)
                    }
                }
                .font(F.body)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focus, equals: .password)
                .submitLabel(.go)
                .onSubmit { submit() }

                Button {
                    // 显式布尔取反，不根据 inputType 反推（Android 老坑）
                    pwdShown.toggle()
                } label: {
                    Image(systemName: pwdShown ? "eye.slash" : "eye")
                        .font(.system(size: 15))
                        .foregroundStyle(T.textTertiary)
                        .frame(width: 30, height: 30)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(pwdShown ? "隐藏密码" : "显示密码")
            }
            .padding(.horizontal, 14)
            .frame(height: 50)
        }
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(T.border, lineWidth: 0.8)
        )

        if !error.isEmpty {
            Text(error)
                .font(F.sub)
                .foregroundStyle(Color(hex: 0xD93A3A))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10)
                .padding(.horizontal, 4)
        }

        Toggle("记住密码", isOn: $remember)
            .font(F.body)
            .tint(T.brand)
            .padding(.top, 14)
            .padding(.horizontal, 4)

        Toggle("自动登录", isOn: $autoLogin)
            .font(F.body)
            .tint(T.brand)
            .padding(.top, 6)
            .padding(.horizontal, 4)
    }

    // MARK: - 按钮区

    private var actions: some View {
        VStack(spacing: 12) {
            Button(action: submit) {
                ZStack {
                    if busy {
                        ProgressView().tint(.white)
                    } else {
                        Text("登录")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(busy ? T.brand.opacity(0.6) : T.brand)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(busy)
            .padding(.top, 22)

            Button {
                dismiss()
                openAffairs()
            } label: {
                Text("先不登录，直接去学工系统 ›")
                    .font(F.sub)
                    .foregroundStyle(T.brand)
            }
            .buttonStyle(.plain)
        }
    }

    private var footer: some View {
        Text("密码只存本机 Keychain，用于自动登录；卸载 App 即清除。\n教务地址 jwxt.tstc.edu.cn")
            .font(.system(size: 11))
            .foregroundStyle(T.textTertiary)
            .multilineTextAlignment(.center)
            .padding(.top, 26)
    }

    // MARK: - 行为

    private func prefill() {
        user = app.store.user()
        password = app.store.pass
        remember = app.store.rememberPwd() || !password.isEmpty
        autoLogin = app.store.autoLogin()
        focus = user.isEmpty ? .user : .password
    }

    private func submit() {
        guard !busy else { return }
        error = ""
        busy = true
        focus = nil
        Task {
            let message = await app.login(user: user, password: password,
                                          remember: remember, autoLogin: autoLogin)
            busy = false
            if let message {
                error = message
                return
            }
            app.showToast("登录成功")
            dismiss()
            // 登录成功后顺手同步一次课表（用户点了「登录」就是想要课表）
            if let syncError = await app.syncTimetable() {
                app.showToast(syncError)
            }
        }
    }

    private func openAffairs() {
        guard let url = URL(string: JwClient.affairsURL) else { return }
        UIApplication.shared.open(url)
    }
}
