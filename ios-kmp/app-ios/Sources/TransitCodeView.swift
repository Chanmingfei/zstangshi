import SwiftUI
import UIKit

/// 乘车码入口。
///
/// iOS 不能像 Android 那样直接 `Intent.setPackage` 指定包名拉起，
/// 只能 `open(scheme)` 交给系统；因此顺序是「乘车码 scheme → App 首页 scheme → 提示装 App」。
/// scheme 与安卓 `bus/TransitCode.kt` 保持一致（那是照追客抄的）。
struct TransitCodeView: View {
    @State private var message = ""

    private let alipayCode = "alipays://platformapi/startapp?appId=200011235"
    private let alipayHome = "alipay://"
    private let unionCode = "upwallet://rn/rnhtmlridingcode"
    private let unionHome = "unionpay://"

    var body: some View {
        List {
            Section("地铁/公交乘车码") {
                Button {
                    open([alipayCode, alipayHome], app: "支付宝")
                } label: {
                    row("支付宝乘车码", "蓝色 App 图标", "a")
                }
                Button {
                    open([unionCode, unionHome], app: "云闪付")
                } label: {
                    row("云闪付乘车码", "银联", "u")
                }
            }
            if !message.isEmpty {
                Section { Text(message).font(.caption).foregroundColor(.secondary) }
            }
            Section {
                Text("iOS 不允许直接跳进某个 App 的具体页面，只能尝试用 scheme 唤到乘车码；"
                     + "被系统拦下时会退到 App 首页或提示安装。")
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .navigationTitle("乘车码")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func row(_ title: String, _ sub: String, _ initial: String) -> some View {
        HStack(spacing: 12) {
            Text(initial.uppercased())
                .font(.headline).foregroundColor(.white)
                .frame(width: 34, height: 34)
                .background(Color(red: 0.46, green: 0.08, blue: 0.10))
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundColor(.primary)
                Text(sub).font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    private func open(_ schemes: [String], app: String) {
        tryNext(schemes, index: 0, app: app)
    }

    private func tryNext(_ schemes: [String], index: Int, app: String) {
        guard index < schemes.count else {
            message = "没装\(app)？请先到 App Store 安装后再试。"
            return
        }
        guard let url = URL(string: schemes[index]) else {
            tryNext(schemes, index: index + 1, app: app)
            return
        }
        UIApplication.shared.open(url, options: [:]) { ok in
            if ok {
                message = "已唤起\(app)"
            } else {
                tryNext(schemes, index: index + 1, app: app)
            }
        }
    }
}
