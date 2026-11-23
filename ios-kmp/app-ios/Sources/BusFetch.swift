import Foundation
import Shared

/// 公交请求的统一出口。
///
/// 为什么 HTTP 在 Swift 而不是 Kotlin：车来了强制要求 `Referer`，而 Kotlin/Native 的
/// Foundation 绑定没暴露设置请求头的接口（详见 shared/net/Http.kt 的注释）。
/// 于是分工是 Kotlin 拼 URL/签名/解密/解析，Swift 只负责发请求。
enum BusFetch {

    struct Failure: LocalizedError {
        let code: Int
        let body: String
        var errorDescription: String? { "HTTP \(code)：\(body.prefix(120))" }
    }

    /// 表单 POST（掌上公交用）。参数顺序由 Kotlin 侧拼好，这里原样发。
    static func postForm(_ urlText: String, form: String, headers: [String: String]) async throws -> String {
        guard let url = URL(string: urlText) else {
            throw NSError(domain: "BusFetch", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "URL 非法"])
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 15
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        req.httpBody = Data(form.utf8)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        let body = String(data: data, encoding: .utf8) ?? ""
        guard code == 200 else { throw Failure(code: code, body: body) }
        return body
    }

    static func text(_ urlText: String) async throws -> String {
        guard let url = URL(string: urlText) else {
            throw NSError(domain: "BusFetch", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "URL 非法"])
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        req.setValue(ChelaileApi.shared.UA, forHTTPHeaderField: "User-Agent")
        req.setValue(ChelaileApi.shared.REFERER, forHTTPHeaderField: "Referer")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")

        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        let body = String(data: data, encoding: .utf8) ?? ""
        guard code == 200 else { throw Failure(code: code, body: body) }
        return body
    }
}
