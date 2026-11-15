import SwiftUI

/// 启动页（对齐 Android `SplashActivity.kt`）。
///
/// 元素：
///   · 校徽（呼吸动画：alpha 1↔0.86、scale 1↔0.995、1300ms 往返）
///   · 应用名「掌上唐师」+ 标语「唐山师范学院 · 教务数据随身看」
///   · 三颗呼吸圆点（alpha 0.24→1→0.24，1200ms，错开 160ms）
///   · 状态文字（自动登录中 / 登录结果）
///   · 「直接进入实时公交」按钮（后台登录继续跑）
///
/// 分支：
///   A. 未开自动登录 → 350ms 后进主界面（iOS 不需要强制登录才能看课表）
///   B. 已开自动登录 → 后台登录 + 同步；25s 超时则提示手动登录
///   C. 点「直接进入实时公交」→ 记住标记，登录成功后回主界面
struct SplashPage: View {
    @EnvironmentObject private var app: AppState
    /// 启动完成回调（进入主界面）
    var onFinish: () -> Void
    /// 直接跳到实时公交
    var onOpenBus: () -> Void

    @State private var logoPulse = false
    @State private var dotPhase = 0
    @State private var status = ""
    @State private var autoRunning = false
    @State private var didStart = false
    @State private var failed: String?

    var body: some View {
        ZStack {
            T.bgPage.ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                // 校徽
                Group {
                    if UIImage(named: "CampusArt") != nil {
                        Image("CampusArt")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 92, height: 92)
                    } else {
                        Image(systemName: "graduationcap.fill")
                            .font(.system(size: 46, weight: .medium))
                            .foregroundStyle(T.brand)
                            .frame(width: 92, height: 92)
                            .background(
                                RoundedRectangle(cornerRadius: 22, style: .continuous)
                                    .fill(T.brandSoft)
                            )
                    }
                }
                .opacity(logoPulse ? 0.86 : 1)
                .scaleEffect(logoPulse ? 0.995 : 1)
                .animation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true),
                           value: logoPulse)

                Text("掌上唐师")
                    .font(.system(size: F.scaled(24), weight: .bold))
                    .foregroundStyle(T.textPrimary)
                    .padding(.top, 22)

                Text("唐山师范学院 · 教务数据随身看")
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
                    .padding(.top, 6)

                // 三颗呼吸点
                HStack(spacing: 10) {
                    ForEach(0..<3, id: \.self) { i in
                        Circle()
                            .fill(T.brand)
                            .frame(width: 6, height: 6)
                            .opacity(dotOpacity(i))
                            .animation(
                                .easeInOut(duration: 1.2)
                                    .repeatForever(autoreverses: true)
                                    .delay(Double(i) * 0.16),
                                value: dotPhase
                            )
                    }
                }
                .padding(.top, 40)

                if !status.isEmpty {
                    Text(status)
                        .font(F.small)
                        .foregroundStyle(failed == nil ? T.textTertiary : T.danger)
                        .multilineTextAlignment(.center)
                        .padding(.top, 16)
                        .padding(.horizontal, 40)
                }

                Spacer()

                // 「直接进入实时公交」：后台登录继续跑
                if app.store.autoLogin() && autoRunning {
                    Button {
                        Haptics.light()
                        onOpenBus()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "bus.fill")
                            Text("直接进入实时公交")
                        }
                        .font(F.caption)
                        .foregroundStyle(T.brand)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 9)
                        .background(Capsule().fill(T.brandSoft))
                    }
                    .buttonStyle(.pressable(0.95))
                    .padding(.bottom, 24)
                }

                // 失败时的「去手动登录」
                if failed != nil {
                    Button {
                        Haptics.light()
                        onFinish()
                        app.showLogin = true
                    } label: {
                        Text("去手动登录")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, 40)
                    .padding(.bottom, 24)
                }

                if let failed {
                    Text(failed)
                        .font(F.micro)
                        .foregroundStyle(T.danger)
                        .padding(.bottom, 20)
                }
            }
        }
        .onAppear {
            logoPulse = true
            dotPhase = 1
            start()
        }
    }

    private func dotOpacity(_ i: Int) -> Double {
        dotPhase == 0 ? 0.24 : 1
    }

    // MARK: - 启动逻辑

    private func start() {
        guard !didStart else { return }
        didStart = true

        let autoLogin = app.store.autoLogin()
        let hasPass = !app.store.pass.isEmpty

        // 分支 A：没开自动登录（或没存密码）→ 直接进主界面
        // iOS 不需要「必须登录才能看课表」，本地有课表就进去
        guard autoLogin && hasPass else {
            status = ""
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                onFinish()
            }
            return
        }

        // 分支 B：后台自动登录
        autoRunning = true
        status = "自动登录中…"

        Task {
            let started = Date()
            let err = await app.syncTimetable()
            autoRunning = false

            // 25 秒超时兜底（与安卓一致）
            let elapsed = Date().timeIntervalSince(started)
            if elapsed > 25 && err != nil {
                failed = "自动登录超时，请手动登录"
                status = ""
                return
            }

            if let err {
                failed = err
                status = ""
                Haptics.warning()
            } else {
                status = "已同步课表"
                Haptics.success()
                try? await Task.sleep(nanoseconds: 350_000_000)
                onFinish()
            }
        }
    }
}