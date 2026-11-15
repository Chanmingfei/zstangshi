import SwiftUI

/// 登录页（对齐 Android `LoginActivity.kt`）。
///
/// 独立全屏页：品牌区 + 学号 / 密码 + 记住密码 / 自动登录 + 主按钮 +「仅使用实时公交」侧门。
/// 登录成功后先同步一次课表，再回到主界面。
struct LoginPage: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var user = ""
    @State private var pass = ""
    @State private var remember = true
    @State private var autoLogin = false
    @State private var busy = false
    @State private var error: String?
    @State private var showPassword = false
    @FocusState private var focused: Field?

    private enum Field { case user, pass }

    var body: some View {
        ZStack {
            T.bgPage.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    brandHeader
                    form
                    actions
                    busOnlyEntry
                }
                .padding(.horizontal, 20)
                .padding(.top, 40)
                .padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.interactively)

            // 关闭按钮（左上）
            VStack {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(T.textSecondary)
                            .frame(width: 32, height: 32)
                            .background(Circle().fill(T.bgSunken))
                    }
                    .buttonStyle(.pressable(0.92))
                    .accessibilityLabel("关闭")
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
            }
        }
        .onAppear {
            user = app.store.user()
            pass = app.store.pass
            remember = app.store.rememberPwd()
            autoLogin = app.store.autoLogin()
        }
    }

    // MARK: - 品牌区

    private var brandHeader: some View {
        VStack(spacing: 10) {
            // 校徽（资源存在才显示）
            if UIImage(named: "AppIcon") != nil {
                Image("AppIcon")
                    .resizable()
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .shadow(color: .black.opacity(0.10), radius: 10, y: 4)
            } else {
                Image(systemName: "graduationcap.fill")
                    .font(.system(size: 30, weight: .medium))
                    .foregroundStyle(T.brand)
                    .frame(width: 64, height: 64)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(T.brandSoft)
                    )
            }

            Text("掌上唐师")
                .font(.system(size: F.scaled(22), weight: .bold))
                .foregroundStyle(T.textPrimary)

            Text("登录正方教务，同步课表与成绩")
                .font(F.caption)
                .foregroundStyle(T.textTertiary)
        }
        .padding(.bottom, 30)
    }

    // MARK: - 表单

    private var form: some View {
        VStack(spacing: 0) {
            field(icon: "person", placeholder: "学号") {
                TextField("学号", text: $user)
                    .keyboardType(.numberPad)
                    .textContentType(.username)
                    .focused($focused, equals: .user)
                    .submitLabel(.next)
                    .onSubmit { focused = .pass }
            }

            Divider().overlay(T.borderSubtle).padding(.leading, 44)

            field(icon: "lock", placeholder: "密码") {
                HStack {
                    Group {
                        if showPassword {
                            TextField("密码", text: $pass)
                                .textContentType(.password)
                        } else {
                            SecureField("密码", text: $pass)
                                .textContentType(.password)
                        }
                    }
                    .focused($focused, equals: .pass)
                    .submitLabel(.go)
                    .onSubmit { submit() }

                    Button {
                        showPassword.toggle()
                    } label: {
                        Image(systemName: showPassword ? "eye.slash" : "eye")
                            .font(.system(size: 15))
                            .foregroundStyle(T.textTertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.8)
        )

        .overlay(alignment: .bottom) {
            if let error {
                Text(error)
                    .font(F.caption)
                    .foregroundStyle(T.danger)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .offset(y: 30)
            }
        }
    }

    private func field<Content: View>(icon: String, placeholder: String,
                                      @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundStyle(T.textTertiary)
                .frame(width: 24)
            content()
                .font(F.body)
                .foregroundStyle(T.textPrimary)
        }
        .padding(.horizontal, 14)
        .frame(height: 50)
    }

    // MARK: - 开关与按钮

    private var actions: some View {
        VStack(spacing: 16) {
            HStack {
                Toggle("记住密码", isOn: $remember)
                    .font(F.caption)
                    .tint(T.brand)
                Spacer()
                Toggle("自动登录", isOn: $autoLogin)
                    .font(F.caption)
                    .tint(T.brand)
            }
            .padding(.top, 42)

            Button {
                submit()
            } label: {
                if busy {
                    HStack(spacing: 8) {
                        LoadingRing(size: 15)
                            .tint(.white)
                        Text("登录中…")
                    }
                    .font(F.bodyBold)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(T.brand.opacity(0.75))
                    )
                } else {
                    Text("登录")
                        .font(F.bodyBold)
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(canSubmit ? T.brand : T.textTertiary)
                        )
                }
            }
            .buttonStyle(.pressable(0.98))
            .disabled(busy || !canSubmit)

            Text("密码只保存在本机钥匙串，不会上传到任何第三方服务器")
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
                .multilineTextAlignment(.center)
        }
    }

    private var busOnlyEntry: some View {
        Button {
            dismiss()
            // 实时公交是首页宫格的功能，不是一级 tab —— 这里先回首页
            app.setTab(MainTab.home.rawValue, animated: false)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "bus")
                Text("仅使用实时公交")
            }
            .font(F.caption)
            .foregroundStyle(T.brand)
        }
        .buttonStyle(.pressable(0.95))
        .padding(.top, 26)
    }

    private var canSubmit: Bool {
        !user.trimmingCharacters(in: .whitespaces).isEmpty && !pass.isEmpty
    }

    // MARK: - 提交

    private func submit() {
        guard canSubmit, !busy else { return }
        focused = nil
        busy = true
        error = nil

        Task {
            defer { busy = false }
            if let err = await app.login(user: user, password: pass,
                                         remember: remember, autoLogin: autoLogin) {
                error = err
                Haptics.error()
                return
            }
            Haptics.success()
            // 登录成功后顺手同步一次课表（失败也只提示，不阻断）
            if let syncErr = await app.syncTimetable() {
                app.showToast(syncErr)
            } else {
                app.showToast("登录成功，已同步课表")
            }
            dismiss()
        }
    }
}