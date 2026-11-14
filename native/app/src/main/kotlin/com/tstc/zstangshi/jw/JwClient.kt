package com.tstc.zstangshi.jw

import android.util.Base64
import com.tstc.zstangshi.data.JwglxtFetcher
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.Timetable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.LinkedHashMap
import java.util.regex.Pattern
import javax.crypto.Cipher

/**
 * 正方（强智）教务客户端：登录 + 取课表。
 *
 * 为什么用 HttpURLConnection 而不是 OkHttp：本项目零第三方依赖（构建环境拉不到
 * maven），而教务这边只需要「带 Cookie 的 GET/POST + 跟随 302」，framework 自带
 * 的 HttpURLConnection 完全够用（它默认跟随跳转）。
 *
 * 登录协议（2026-11 实测于 jwxt.tstc.edu.cn）：
 *   1. GET  /xtgl/login_slogin.html      → 取隐藏域 csrftoken
 *   2. GET  /xtgl/login_getPublicKey.html → {modulus, exponent}（base64 的 BigInteger）
 *   3. RSA/ECB/PKCS1Padding 加密密码
 *   4. POST /xtgl/login_slogin.html      → yhm / mm / hidMm / csrftoken / language / mmsfjm
 *   5. GET  /xtgl/index_cxMenuList.html  → 必须含 gnmkdm，否则视为没登上
 *
 * 协议自适应：校外移动数据下 443 常常不通、80 正常，所以先试 https，
 * 连不上直接落 http（与旧版 jwxtOrigin 的结论一致）。
 */
class JwClient {

    private var originPrefix: String = ""
    private val store: Store?

    constructor() : this(null)

    /** 传 Store 进来就能记住上次连得上的协议，冷启动不再白等一轮 443 超时。 */
    constructor(s: Store?) {
        this.store = s
        val remembered = if (s == null) "" else s.originScheme()
        originPrefix = if ("http" == remembered) "http://$HOST" else "https://$HOST"
    }

    /** 已经探测到的协议前缀，登录成功后可直接拼外链。 */
    fun origin(): String {
        return originPrefix
    }

    fun clearSession() {
        JAR.cookieStore.removeAll()
        sessionUser = ""
        sessionName = ""
        sessionAt = 0L
        sessionProbeAt = 0L
    }

    /**
     * 把登录态交给 WebView 的 CookieManager —— 「更多功能 / 选课」打开原站点时
     * 就不必再登一次（这是内置浏览器唯一的意义）。
     */
    fun shareSessionToWebView() {
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for (c in JAR.cookieStore.cookies) {
                if (c.name == null || c.value == null) continue
                val v = c.name + "=" + c.value + "; path=/"
                cm.setCookie("https://$HOST/", v)
                cm.setCookie("http://$HOST/", v)
            }
            cm.flush()
        } catch (ignore: Throwable) {
            // 同步失败不影响登录本身
        }
    }

    // ---------- 对外 ----------

    fun login(studentNo: String, password: String): Result {
        val r = Result()

        // 同一进程、同一账号、十分钟内：一次轻量探测确认会话还活着就直接用，
        // 省掉整套 RSA 登录（老版就是登录一次后各页面复用会话）。
        if (studentNo == sessionUser
            && System.currentTimeMillis() - sessionAt < SESSION_TTL
        ) {
            val now = System.currentTimeMillis()
            if (now - sessionProbeAt < PROBE_GAP) {
                r.ok = true
                r.studentName = sessionName
                android.util.Log.d("ZSTS-JW", "复用已登录会话（免探测）")
                return r
            }
            val probe = get(MENU_LIST)
            if (probe != null && probe.contains("gnmkdm") && !probe.contains("login_slogin")) {
                r.ok = true
                r.studentName = sessionName
                sessionProbeAt = now
                android.util.Log.d("ZSTS-JW", "复用已登录会话，跳过登录四步")
                return r
            }
            // 会话过期了，下面重新登
        }
        clearSession()

        val page = get(LOGIN_PAGE)
        if (page == null) {
            r.message = "连不上教务系统，请检查网络（校外流量下建议改用校园网）"
            return r
        }
        val csrf = hidden(page, "csrftoken")
        if (csrf.isEmpty()) {
            r.message = "登录页结构变了（找不到 csrftoken），请更新 App"
            return r
        }

        val keyJson = get(PUBLIC_KEY + "?time=" + System.currentTimeMillis())
        if (keyJson == null) {
            r.message = "取加密公钥失败"
            return r
        }
        val modulus = jsonField(keyJson, "modulus")
        val exponent = jsonField(keyJson, "exponent")
        val enc = rsaEncrypt(modulus, exponent, password)
        if (enc == null) {
            r.message = "密码加密失败"
            return r
        }

        val form = LinkedHashMap<String, String>()
        form["csrftoken"] = csrf
        form["yhm"] = studentNo
        form["mm"] = enc
        form["hidMm"] = enc
        form["language"] = "zh_CN"
        form["mmsfjm"] = "1"
        val postText = post(LOGIN_PAGE, form)
        if (postText == null) {
            r.message = "登录请求没有响应"
            return r
        }
        android.util.Log.d("ZSTS-JW", "登录响应 " + postText.length + " 字节，含错误提示="
                + postText.contains("用户名或密码不正确") + "，含登录页=" + postText.contains("login_slogin"))
        if (postText.contains("用户名或密码不正确")) {
            r.message = "学号或密码不正确"
            return r
        }

        // 空响应既是成功的样子也是失败的样子，必须再要一次实锤
        val menu = get(MENU_LIST)
        if (menu == null) {
            r.message = "登录后核对会话失败，请检查网络"
            return r
        }
        android.util.Log.d("ZSTS-JW", "菜单页 " + menu.length + " 字节，含 gnmkdm="
                + menu.contains("gnmkdm") + "，被踢回登录=" + menu.contains("login_slogin"))
        if (menu.contains("login_slogin")) {
            r.message = "学号或密码不正确"
            return r
        }
        if (!menu.contains("gnmkdm")) {
            r.message = "无法确认登录状态，请重试"
            return r
        }

        r.ok = true
        // 记下本次登录时间：功能页据此判断「这个登录会话抓过成绩/绩点没有」
        store?.setLoginAt(System.currentTimeMillis())
        // 姓名按学号绑定：同一个号存过就不再请求个人信息页（省一次往返），
        // 换了账号旧姓名视为不存在，必须重新抓，否则「我的」页会显示上一个用户的名字
        val cachedName = if (store != null) store.studentName(studentNo) else ""
        r.studentName = if (cachedName.isEmpty()) fetchStudentName() else cachedName
        store?.setStudentName(r.studentName, studentNo)
        sessionUser = studentNo
        sessionName = r.studentName
        sessionAt = System.currentTimeMillis()
        sessionProbeAt = sessionAt
        android.util.Log.d("ZSTS-JW", "登录成功，姓名=" + r.studentName + "，协议=" + originPrefix)
        shareSessionToWebView()
        return r
    }

    /**
     * 抓学生姓名（旧版真机抓包确认过页面结构）。
     * 抓不到返回空串，绝不影响登录；界面会回退显示学号。
     */
    private fun fetchStudentName(): String {
        val page = get(PROFILE_PAGE)
        if (page == null || page.isEmpty()) return ""
        val patterns = arrayOf(
            "(?<!英)姓名\\s*[:：]?\\s*</label>[\\s\\S]{0,300}?form-control-static[^>]*>\\s*([^<]{1,24})\\s*<",
            "id=[\"']col_xm[\"'][\\s\\S]{0,300}?form-control-static[^>]*>\\s*([^<]{1,24})\\s*<",
            "name=[\"']xm[\"'][^>]*value=[\"']([^\"']{1,24})[\"']"
        )
        for (p in patterns) {
            val m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(page)
            if (m.find()) {
                val v = m.group(1).trim().replace(Regex("\\s+"), "")
                if (v.isNotEmpty() && !v.contains("姓名")) return v
            }
        }
        android.util.Log.d("ZSTS-JW", "姓名页 " + page.length + " 字节，没匹配到姓名")
        return ""
    }

    /** 取当前学期课表。 */
    fun fetchTimetable(): Result {
        val r = Result()

        // 两个页面写法都试：主路径是旧版真机实测的，备选是教务偶尔换的那个。
        // 判定标准不是「有没有响应」，而是「能不能从 select 里读到 xnm/xqm」——
        // 404 页、登录跳转页都会返回 200 文本，只看响应会把人骗过去。
        val candidates = arrayOf(KB_PAGE, KB_PAGE_ALT)
        var xnm = ""
        var xqm = ""
        var kicked = false
        var anyResponse = false
        for (path in candidates) {
            val p = get(path)
            if (p == null) {
                android.util.Log.d("ZSTS-JW", "课表页 " + path + " 无响应")
                continue
            }
            anyResponse = true
            if (p.contains("login_slogin")) {
                kicked = true
                android.util.Log.d("ZSTS-JW", "课表页 " + path + " 被踢回登录")
                continue
            }
            val a = selectedOption(p, "xnm")
            val b = selectedOption(p, "xqm")
            android.util.Log.d("ZSTS-JW", "课表页 " + path + " " + p.length + " 字节，xnm=" + a
                    + "，xqm=" + b + "，含 select=" + p.contains("<select"))
            if (a.isNotEmpty() && b.isNotEmpty()) {
                xnm = a
                xqm = b
                break
            }
        }
        if (xnm.isEmpty() || xqm.isEmpty()) {
            if (kicked) {
                r.message = "登录已过期，请重新登录"
            } else if (!anyResponse) {
                r.message = "连不上教务，请检查网络（校外建议用校园网）"
            } else {
                r.message = "课表页里找不到学年/学期，教务系统可能改版了"
            }
            return r
        }

        val form = LinkedHashMap<String, String>()
        form["xnm"] = xnm
        form["xqm"] = xqm
        val json = post(KB_DATA, form)
        if (json == null) {
            r.message = "课表接口没有返回"
            return r
        }
        android.util.Log.d("ZSTS-JW", "课表接口 " + json.length + " 字节，开头="
                + json.substring(0, Math.min(160, json.length)).replace("\n", " "))

        val parsed = JwglxtFetcher.parse(json)
        if (!parsed.ok()) {
            r.message = if (parsed.warnings.isEmpty()) "课表解析为空" else parsed.warnings[0]
            return r
        }
        r.ok = true
        r.timetable = parsed.timetable
        r.semesterId = parsed.timetable.semester.id
        return r
    }

    // ---------- HTTP ----------

    /** 供 JwApi（成绩 / 绩点 / 选课 / 推荐课表）复用同一条带 Cookie 的会话。 */
    fun get(path: String): String? {
        return request("GET", path, null)
    }

    fun post(path: String, form: Map<String, String>): String? {
        return request("POST", path, form)
    }

    private fun request(method: String, path: String, form: Map<String, String>?): String? {
        // 先按当前 origin 试；失败再换协议重试一次（校外 https 经常直接超时）
        val other = if (originPrefix.startsWith("https")) "http://$HOST" else "https://$HOST"
        var out = once(method, originPrefix + path, form, TIMEOUT)
        if (out == null) {
            // 换协议只是探测，别让它再白等一个完整 10 秒超时
            val alt = once(method, other + path, form, 4000)
            if (alt != null) {
                originPrefix = other
                out = alt
            }
        }
        // 只要这一轮有响应，就把当前协议记下来；下次登录/同步直接用，不必再探测
        if (out != null && store != null) {
            store.setOriginScheme(if (originPrefix.startsWith("https")) "https" else "http")
        }
        return out
    }

    private fun once(method: String, url: String, form: Map<String, String>?, timeout: Int): String? {
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.requestMethod = method
            c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

            if ("POST" == method && form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val body = encodeForm(form)
                c.outputStream.use { os: OutputStream ->
                    os.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val code = c.responseCode
            android.util.Log.d("ZSTS-JW", method + " " + url + " -> " + code)
            if (code >= 400 && code != 404) {
                // 404 对探测场景是有效响应；其余错误体没有解析价值
                val es = c.errorStream
                if (es != null) readAll(es)
                return null
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return if (stream == null) "" else readAll(stream)
        } catch (e: Exception) {
            // 登录失败最常见的原因就在这行日志里：连不上 / 证书 / 超时
            android.util.Log.w("ZSTS-JW", method + " " + url + " 失败：" + e)
            return null
        } finally {
            c?.disconnect()
        }
    }

    // ---------- 解析 ----------

    companion object {

        const val HOST = "jwxt.tstc.edu.cn"

        private const val LOGIN_PAGE = "/xtgl/login_slogin.html"
        private const val PUBLIC_KEY = "/xtgl/login_getPublicKey.html"
        private const val MENU_LIST = "/xtgl/index_cxMenuList.html"
        private const val PROFILE_PAGE =
            "/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=N100801&layout=default"

        /** 旧版 WebView 架构真机实测过的课表页路径，别再凭印象拼。 */
        private const val KB_PAGE =
            "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
        private const val KB_DATA = "/kbcx/xskbcx_cxXsKb.html"
        private const val KB_PAGE_ALT =
            "/kbcx/xskbcx_cxXskbIndex.html?gnmkdm=N2151&layout=default"

        private const val TIMEOUT = 10000

        /**
         * Cookie 交给 JDK 的 CookieManager 去管，而不是自己拼 Cookie 头。
         *
         * 原因（2026-11-10 真机踩到的）：登录 POST 会先回 302 再跳菜单页，JSESSIONID 就在那个
         * 302 的响应头里；手写 Cookie 只读最后一次响应，会话直接丢 —— 于是「登录成功」也被
         * 判成「账号密码错误」。CookieManager 会把跳转链上的 Set-Cookie 全收下来。
         */
        private val JAR = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)

        /** 进程内会话复用：点开第二个功能页不必再走一遍「登录页→公钥→POST→菜单」。 */
        @Volatile
        private var sessionUser: String = ""

        @Volatile
        private var sessionName: String = ""

        @Volatile
        private var sessionAt: Long = 0L

        /** 上次确认会话还活着的时间；短时间内连续切页不必每次都再拉一遍菜单页。 */
        @Volatile
        private var sessionProbeAt: Long = 0L

        private const val SESSION_TTL = 10 * 60_000L
        private const val PROBE_GAP = 30_000L

        init {
            java.net.CookieHandler.setDefault(JAR)
        }

        /** 取隐藏域值（属性顺序可能是 value 在前，两个方向都试）。 */
        @JvmStatic
        fun hidden(html: String, id: String): String {
            val m = Pattern.compile(
                "<input[^>]*id=[\"']" + Pattern.quote(id) + "[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            if (m.find()) {
                val tag = m.group()
                val v = Pattern.compile("value=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag)
                if (v.find()) return v.group(1)
            }
            return ""
        }

        /**
         * 取下拉框当前选中值。教务课表页写的是 name="xnm"（不是 id），
         * 而且 selected 可能排在 value 前面；以前只认 id 导致学期码永远读空。
         */
        @JvmStatic
        fun selectedOption(html: String, key: String): String {
            var inner: String? = null
            val selectRegs = arrayOf(
                "<select[^>]*id=[\"']" + Pattern.quote(key) + "[\"'][^>]*>([\\s\\S]*?)</select>",
                "<select[^>]*name=[\"']" + Pattern.quote(key) + "[\"'][^>]*>([\\s\\S]*?)</select>"
            )
            for (reg in selectRegs) {
                val m = Pattern.compile(reg, Pattern.CASE_INSENSITIVE).matcher(html)
                if (m.find()) {
                    inner = m.group(1)
                    break
                }
            }
            if (inner == null) return ""

            val opt = Pattern.compile("<option[^>]*>", Pattern.CASE_INSENSITIVE).matcher(inner)
            val valReg = Pattern.compile("value=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
            var first = ""
            while (opt.find()) {
                val tag = opt.group()
                val v = valReg.matcher(tag)
                val value = if (v.find()) v.group(1) else ""
                if (value.isEmpty()) continue
                if (first.isEmpty()) first = value
                if (tag.toLowerCase(java.util.Locale.ROOT).contains("selected")) return value
            }
            return first
        }

        @JvmStatic
        fun jsonField(json: String, name: String): String {
            val m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json)
            return if (m.find()) m.group(1) else ""
        }

        /** RSA/ECB/PKCS1Padding 加密密码，返回 base64。 */
        @JvmStatic
        fun rsaEncrypt(modulusB64: String?, exponentB64: String?, password: String): String? {
            if (modulusB64 == null || modulusB64.isEmpty() || exponentB64 == null || exponentB64.isEmpty()) {
                return null
            }
            try {
                val modulus = java.math.BigInteger(1, Base64.decode(modulusB64, Base64.DEFAULT))
                val exponent = java.math.BigInteger(1, Base64.decode(exponentB64, Base64.DEFAULT))
                val spec = RSAPublicKeySpec(modulus, exponent)
                val kf = KeyFactory.getInstance("RSA")
                val key: PublicKey = kf.generatePublic(spec)
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val out = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
                return Base64.encodeToString(out, Base64.NO_WRAP)
            } catch (e: Exception) {
                return null
            }
        }

        private fun encodeForm(form: Map<String, String>): String {
            val sb = StringBuilder()
            for ((key, value) in form.entries) {
                if (sb.length > 0) sb.append('&')
                sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                sb.append('=')
                sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8))
            }
            return sb.toString()
        }

        private fun readAll(input: InputStream): String {
            return try {
                input.use { inp ->
                    ByteArrayOutputStream().use { bos ->
                        val buf = ByteArray(8192)
                        var n = inp.read(buf)
                        while (n > 0) {
                            bos.write(buf, 0, n)
                            n = inp.read(buf)
                        }
                        bos.toString("UTF-8")
                    }
                }
            } catch (e: Exception) {
                ""
            }
        }
    }

    class Result {
        @JvmField
        var ok: Boolean = false

        @JvmField
        var message: String = ""

        @JvmField
        var timetable: Timetable? = null

        @JvmField
        var semesterId: String = ""

        @JvmField
        var studentName: String = ""
    }
}
