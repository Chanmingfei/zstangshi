import com.tstc.zstangshi.shared.CourseColorAssigner
import com.tstc.zstangshi.shared.Dates
import com.tstc.zstangshi.shared.KbParser
import com.tstc.zstangshi.shared.Timetable
import com.tstc.zstangshi.shared.WeekMath
import com.tstc.zstangshi.shared.WeekRange
import com.tstc.zstangshi.shared.bus.ChelaileApi
import com.tstc.zstangshi.shared.bus.ChelaileCrypto
import com.tstc.zstangshi.shared.bus.BusText
import com.tstc.zstangshi.shared.bus.parseLineDetail
import com.tstc.zstangshi.shared.bus.parseNearby
import com.tstc.zstangshi.shared.bus.parseStopDetail
import com.tstc.zstangshi.shared.bus.parseTimetable
import com.tstc.zstangshi.shared.bus.Mygolbs
import com.tstc.zstangshi.shared.crypto.base64Decode
import com.tstc.zstangshi.shared.crypto.md5Hex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * P0 探针：证明「共享层逻辑在非 macOS 环境上可编译、可运行、结果与 Android 一致」。
 *
 * 这不是单元测试框架，是给一个结论用的脚本：退出码 0 = 全绿。
 * 跑法：bash ios-kmp/run-probe.sh
 */

private var passed = 0
private val failures = ArrayList<String>()

private fun check(ok: Boolean, label: String, actual: Any? = null, expected: Any? = null) {
    if (ok) { passed++; return }
    failures.add("$label\n      期望: $expected\n      实际: $actual")
}

private fun eq(actual: Any?, expected: Any?, label: String) {
    check(actual == expected, label, actual, expected)
}

fun main(args: Array<String>) {
    WeekMath.todayProvider = { LocalDate.now().toEpochDay().toInt() }

    // ---------- 1. 日期内核 ----------
    eq(Dates.parseYmd("1970-01-01"), 0, "1970-01-01 应为 epoch day 0")
    eq(Dates.dayOfWeek(0), 4, "1970-01-01 是周四")

    var roundTrip = 0
    var rtFail = 0
    for (d in -25000..25000) {
        val ymd = Dates.civilFromDays(d)
        val back = Dates.daysFromCivil(ymd.year, ymd.month, ymd.day)
        if (back == d) roundTrip++ else rtFail++
    }
    check(rtFail == 0, "日期往返 50001 天全部可逆（失败 $rtFail 天）", rtFail, 0)

    eq(Dates.parseYmd("2028-02-29"), Dates.daysFromCivil(2028, 2, 29), "闰年 2/29 可解析")
    eq(Dates.parseYmd("2027-02-29"), null, "平年 2/29 应判非法")
    eq(Dates.parseYmd("2026-13-01"), null, "13 月应判非法")
    eq(Dates.parseYmd("2026-1-1"), null, "非 yyyy-MM-dd 应判非法")
    eq(Dates.formatYmd(Dates.daysFromCivil(2026, 9, 7)), "2026-09-07", "formatted 输出补零")

    // ---------- 2. 归一到周一 ----------
    // 2026-09-07 是周一
    eq(Dates.dayOfWeek(Dates.daysFromCivil(2026, 9, 7)), 1, "2026-09-07 是周一")
    val week = arrayOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10",
        "2026-09-11", "2026-09-12", "2026-09-13")
    for (d in week) eq(WeekMath.normalizeToMonday(d), "2026-09-07", "$d 应归一到 2026-09-07")
    eq(WeekMath.normalizeToMonday("2026-09-14"), "2026-09-14", "下一个周一不变")
    eq(WeekMath.isMonday("2026-09-07"), true, "isMonday(周一)")
    eq(WeekMath.isMonday("2026-09-08"), false, "isMonday(周二)=false")

    // ---------- 3. 周次 ----------
    eq(WeekMath.weekOfDate("2026-09-07", "2026-09-07"), 1, "开学日 = 第 1 周")
    eq(WeekMath.weekOfDate("2026-09-07", "2026-09-13"), 1, "第 1 周周日仍是第 1 周")
    eq(WeekMath.weekOfDate("2026-09-07", "2026-09-14"), 2, "第 8 天 = 第 2 周")
    eq(WeekMath.weekOfDate("2026-09-07", "2026-09-06"), 0, "开学前一天 = 第 0 周（floorDiv 语义）")
    eq(WeekMath.weekOfDate("2026-09-07", "2026-01-05"), -34, "更早的日期为负周次")
    eq(WeekMath.currentWeek("2026-09-07", 20, Dates.daysFromCivil(2026, 9, 21)), 3, "currentWeek 第 3 周")
    eq(WeekMath.currentWeek("2026-09-07", 20, Dates.daysFromCivil(2026, 1, 1)), 1, "开学前 clamp 到 1")
    eq(WeekMath.currentWeek("2026-09-07", 20, Dates.daysFromCivil(2027, 6, 1)), 20, "超学期 clamp 到总周数")
    eq(WeekMath.daysUntilStart("2026-09-07", Dates.daysFromCivil(2026, 9, 1)), 6, "距开学 6 天")
    eq(WeekMath.daysUntilStart("2026-09-07", Dates.daysFromCivil(2026, 9, 20)), 0, "已开学返回 0")

    val dates = WeekMath.weekDates("2026-09-07", 2)
    eq(dates.size, 7, "一周 7 天")
    eq(dates[0], "9/14", "第 2 周起始 9/14")
    eq(dates[6], "9/20", "第 2 周结束 9/20")
    eq(WeekMath.weekRangeStart("2026-09-07", 3), "2026-09-21", "第 3 周周一")
    eq(WeekMath.weekRangeLabel("2026-09-07", 1), "9/7 - 9/13", "周区间文案")
    eq(WeekMath.shortDate("2026-09-07"), "9/7", "短日期不带前导零")

    // 单调性：连续 20 周，每 7 天恰好 +1
    var monoOk = true
    val base = Dates.parseYmd("2026-09-07")!!
    for (w in 1..20) {
        val monday = base + (w - 1) * 7
        if (WeekMath.weekOfDate("2026-09-07", Dates.formatYmd(monday)) != w) monoOk = false
        if (WeekMath.weekOfDate("2026-09-07", Dates.formatYmd(monday + 6)) != w) monoOk = false
    }
    check(monoOk, "20 周内周一/周日都落在同一周且与周次一致")

    // ---------- 4. 周次表达式 ----------
    fun weeks(expr: String): List<Int> = WeekRange.parse(expr).weeks
    eq(weeks("1-16周"), (1..16).toList(), "1-16周")
    eq(weeks("5-12周"), (5..12).toList(), "5-12周")
    eq(weeks("第9周"), listOf(9), "第9周")
    eq(weeks("1-6周,8周"), (1..6).toList() + 8, "多区间")
    eq(weeks("10-16周(双)"), listOf(10, 12, 14, 16), "双周过滤")
    eq(weeks("11-15周(单)"), listOf(11, 13, 15), "单周过滤")
    eq(weeks("1,3,5,7周"), listOf(1, 3, 5, 7), "离散周")
    eq(weeks("1-16"), (1..16).toList(), "无「周」字")
    eq(weeks("12-5周"), (5..12).toList(), "写反的区间也能认")
    eq(weeks("1-5周，7-9周"), listOf(1, 2, 3, 4, 5, 7, 8, 9), "全角逗号")
    eq(weeks("1、3、5周"), listOf(1, 3, 5), "顿号分隔")
    check(weeks("").isEmpty(), "空表达式返回空")
    eq(WeekRange.isActive("", 5), true, "解析失败按全周显示（保底）")
    eq(WeekRange.compress(listOf(1, 2, 3, 5, 7, 8)), "1-3,5,7-8", "compress 压回区间")

    // ---------- 5. 真实课表样本 ----------
    val samplePath = args.firstOrNull() ?: "docs/diagnostics/bjkbdy-kb-sample.json"
    val sample = File(samplePath)
    if (!sample.isFile) {
        failures.add("找不到样本 $samplePath（在仓库根目录跑）")
    } else {
        val r = KbParser.parse(sample.readText(Charsets.UTF_8))
        eq(r.rawRecords, 14, "样本共 14 条 kbList")
        eq(r.courseCount, 11, "按 jxb_id 聚合成 11 门课")
        eq(r.failedWeekExpr, 0, "没有周次解析失败")
        eq(r.mergedDuplicates, 0, "这个样本没有可合并的重复时段")
        check(r.warnings.isEmpty(), "无告警", r.warnings, "无")
        check(r.ok(), "解析结果为 ok")

        val names = r.timetable.courses.map { it.name }.sorted()
        eq(names.distinct().size, 11, "11 门课名互不相同")
        check(names.contains("供应链与物流管理实训"), "含实训课")

        val retail = r.timetable.courses.first { it.name == "网络零售与贸易" }
        eq(retail.sessions.size, 3, "网络零售与贸易 3 个时段")
        eq(retail.teacher, "闫啸", "教师解析")
        eq(retail.credit > 0.0, true, "学分解析为正数")
        eq(retail.sessions.map { it.dayOfWeek }.sorted(), listOf(1, 1, 4), "三个时段分布在周一/周四")
        eq(retail.sessions.flatMap { it.weeks }.minOrNull(), 1, "最早从第 1 周开始")
        eq(retail.sessions.flatMap { it.weeks }.maxOrNull(), 16, "最晚到第 16 周")

        val practice = r.timetable.courses.first { it.name == "国际贸易实务实训" }
        eq(practice.sessions[0].weeks, listOf(10, 12, 14, 16), "双周课周次")
        eq(practice.sessions[0].startSection, 5, "起始节次")
        eq(practice.sessions[0].endSection, 8, "结束节次")

        val logistics = r.timetable.courses.first { it.name == "供应链与物流管理实训" }
        eq(logistics.sessions.size, 2, "实训课 2 个时段（第9周 + 单周）")

        // 所有时段都必须有周次与合法节次，否则课表会漏课
        var badSession = 0
        for (c in r.timetable.courses) for (s in c.sessions) {
            if (s.weeks.isEmpty() || s.dayOfWeek !in 1..7 ||
                s.startSection < 1 || s.endSection < s.startSection
            ) badSession++
        }
        eq(badSession, 0, "所有时段字段合法")

        // 学期 id 缺 xsxx 时保持空；buildSemesterId 单独验
        eq(KbParser.buildSemesterId("2026", "3"), "2026-2027-1", "学期 id（第 1 学期）")
        eq(KbParser.buildSemesterId("2026", "12"), "2026-2027-2", "学期 id（第 2 学期）")
        eq(KbParser.buildSemesterId("2026", "16"), "2026-2027-3", "学期 id（第 3 学期）")

        // ---------- 6. 配色 ----------
        val colorOf = HashMap<String, Int>()
        for (c in r.timetable.courses) colorOf[c.name] = c.colorIndex
        eq(colorOf.values.toSet().size, 11, "11 门不同课拿到 11 种不同颜色")
        check(colorOf.values.all { it in 0 until CourseColorAssigner.PALETTE_SIZE }, "颜色索引在色板范围内")

        // ---------- 7. 首节课推算 ----------
        val t = Timetable()
        val withStart = r.timetable
        withStart.semester.startDate = "2026-09-07"
        eq(WeekMath.firstCourseDate("2026-09-07", withStart), "2026-09-07", "第 1 周周一有课 → 首节 9/7")
        check(WeekMath.firstCourseLabel("2026-09-07", withStart).endsWith("周一"), "首节课文案以周一结尾")
        eq(WeekMath.firstCourseDate("", r.timetable), "", "无开学日期返回空")
        eq(WeekMath.firstCourseLabel("", t), "暂无可计算的首节课", "空课表文案")
    }


    // ---------- 9. 车来了签名 / 解密 / 拆壳 ----------
    // 向量来源：MD5 用 Python hashlib 独立算；AES 密文用 openssl 命令行
    // （aes-256-ecb，密钥取 KEY 的 ASCII 字节）生成 —— 都是外部 oracle，不是我自己的实现。
    eq(md5Hex(""), "d41d8cd98f00b204e9800998ecf8427e", "MD5 空串")
    eq(md5Hex("abc"), "900150983cd24fb0d6963f7d28e17f72", "MD5 abc")

    val signParams = linkedMapOf(
        "cityId" to "050",
        "lineId" to "027-0",
        "stationName" to "TSSF",
        "lat" to "39.63",
        "lng" to "118.18"
    )
    eq(ChelaileCrypto.sign(signParams), "a844576714d1b8ea6836c4073475237d", "车来了签名（参数顺序敏感）")

    // 调换顺序必须得到不同签名，否则说明 Map 被重排过
    val reordered = linkedMapOf(
        "lineId" to "027-0",
        "cityId" to "050",
        "stationName" to "TSSF",
        "lat" to "39.63",
        "lng" to "118.18"
    )
    check(ChelaileCrypto.sign(reordered) != ChelaileCrypto.sign(signParams), "参数顺序变了签名必须变")

    val wxCipher = "YiWMVt9sJSHjs8vq5Y9Uj6S9ZXL3ssnefrLhxyLkaQGAPTIwJtYVpJ5ObaNG1OiarTCFw2O5hKCpViryIoBkvMgYGkdNwUEnfG6IaBhquKU="
    val h5Cipher = "S4BBp6bYl+wDqZee8gkwJOUsZFKFGQfFNSw+fqhTy29h0D7Nsr9botsLIynGPpuM8ZbUc8it/ODi27xmSBCQLw=="

    val wxPlain = runCatching { ChelaileCrypto.decrypt(wxCipher) }.getOrNull()
    eq(wxPlain, """{"code":0,"msg":"ok","data":{"lines":[{"lineId":"027-0","name":"you1"}]}}""",
        "AES-256-ECB 解密（KEY_WX）")

    val h5Plain = runCatching { ChelaileCrypto.decrypt(h5Cipher) }.getOrNull()
    eq(h5Plain, """{"code":0,"data":{"stnList":[{"sId":"abc","sn":"TSSF"}]}}""",
        "AES-256-ECB 解密（回落到 KEY_H5）")

    // base64 里混空白、缺填充都要能吃
    val messy = wxCipher.substring(0, 20) + "\n  " + wxCipher.substring(20).trimEnd('=')
    eq(runCatching { ChelaileCrypto.decrypt(messy) }.getOrNull(), wxPlain, "base64 带换行/缺 = 仍可解")

    // 拆壳：真实响应形态 `**YGKJ{jsonr:{data:{encryptResult}}}**YGKJ`
    val envelope = "**YGKJ{\"jsonr\":{\"code\":0,\"data\":{\"encryptResult\":\"$wxCipher\"}}}**YGKJ"
    val envObj = ChelaileCrypto.extract(envelope)
    eq(envObj?.optObj("data")?.optArr("lines")?.size, 1, "拆壳后能取到 lines[0]")
    eq(envObj?.optObj("data")?.optArr("lines")?.items?.let { (it[0] as? com.tstc.zstangshi.shared.Js.Obj)?.optString("name") },
        "you1", "拆壳后课表/线路名正确")

    // data 直接是 JSON 字符串（部分接口如此）
    val strData = "{\"jsonr\":{\"data\":\"{\\\"code\\\":0,\\\"msg\\\":\\\"plain\\\"}\"}}"
    eq(ChelaileCrypto.extract(strData)?.optString("msg"), "plain", "data 是明文字符串时直接解析")

    // 非 JSON（网关 HTML 错误页）返回 null，不能抛异常
    eq(ChelaileCrypto.extract("<html><body>502 Bad Gateway</body></html>"), null, "HTML 错误页返回 null")

    // 字符串里的花括号不能干扰括号计数
    eq(ChelaileCrypto.jsonEnd("{\"a\":\"}{\"}", 0), 10, "jsonEnd 跳过字符串内的花括号")

    // base64 解码基础行为
    eq(base64Decode("aGVsbG8=").decodeToString(), "hello", "base64 基本解码")
    eq(base64Decode("aGVs bG8").decodeToString(), "hello", "base64 忽略空格、允许缺填充")


    // ---------- 10. 请求 URL 组装（离线可验） ----------
    val p = ChelaileApi.baseParams()
    p["cityId"] = "056"
    p["key"] = "游1"
    val url = ChelaileApi.signedUrl("/bus/query!nSearch.action", p)
    check(url.startsWith("https://web.chelaile.net.cn/api/bus/query!nSearch.action?"), "接口地址前缀正确")
    check(url.contains("s=h5&wxs=wx_app&sign=1"), "公共参数保持插入顺序")
    check(url.contains("key=%E6%B8%B81"), "中文线路名按 UTF-8 百分号编码")
    check(Regex("cryptoSign=[0-9a-f]{32}").containsMatchIn(url), "带 32 位小写 cryptoSign")
    // 签名是对「未编码的原文」算的：拿原参数再算一次应当与 URL 里的一致
    val signInUrl = Regex("cryptoSign=([0-9a-f]{32})").find(url)?.groupValues?.get(1)
    eq(signInUrl, ChelaileCrypto.sign(p), "URL 里的签名与离线计算一致")

    // ---------- 11. 联网冒烟（可选，--net 才跑） ----------
    if (args.contains("--net")) {
        println("—— 联网冒烟：车来了线路搜索 ——")
        // 这里用 JDK 的 HttpURLConnection 代替 Swift 的 URLSession：
        // 两者职责相同（带 Referer 发 GET），这条断言验的是「签名能被服务端接受」，
        // 与用谁发请求无关。
        try {
            val url = ChelaileApi.searchUrl("056", "游1")   // 056 = 唐山
            val body = httpGetWithHeaders(url, ChelaileApi.headers())
            val result = ChelaileApi.parseResponse(body)?.optObj("result")
            val lines = result?.optArr("lines")
            val first = (lines?.items?.firstOrNull() as? com.tstc.zstangshi.shared.Js.Obj)
            val name = first?.optString("name") ?: first?.optString("lineNo") ?: ""
            println("   搜索到 ${lines?.size ?: 0} 条线路，首条：$name")
            check((lines?.size ?: 0) > 0, "联网冒烟：至少返回一条线路", lines?.size, ">0")
            eq(name, "游1", "联网冒烟：首条线路名")
        } catch (e: Throwable) {
            failures.add("联网冒烟失败：" + (e.message ?: e.toString()))
        }
    } else {
        println("（跳过联网冒烟，加 --net 才跑）")
    }


    // ---------- 12. 公交列表与详情解析（用真实响应样本） ----------
    // 样本来自现场抓取：fixtures/nearby.txt（加密原始响应）与 fixtures/linedetail.txt。
    // 这两个文件保留加密原样，正是因为要同时验证「拆壳 + 解密 + 字段映射」整条链路。
    val fixtures = File("ios-kmp/fixtures")
    if (!fixtures.isDirectory) {
        failures.add("找不到 ios-kmp/fixtures（在仓库根目录跑）")
    } else {
        val nearbyBody = File(fixtures, "nearby.txt").readText(Charsets.UTF_8)
        val stations = parseNearby(nearbyBody, 50, 39.63, 118.18)
        check(stations.isNotEmpty(), "附近站点解析非空", stations.size, ">0")
        eq(stations.first().name, "工人医院", "最近一站是工人医院")
        check(stations.first().distanceMeters in 1..3000, "首站距离合理", stations.first().distanceMeters, "1..3000")
        check(stations.none { Geo_isSubwayName(it.name) }, "地铁站已被过滤掉")
        val withRealtime = stations.count { st -> st.arrivals.any { it.realtime } }
        check(withRealtime > 0, "至少一站带实时到站")
        val firstArrival = stations.first().arrivals.firstOrNull()
        check(firstArrival != null, "首站有线路")
        if (firstArrival != null) {
            check(firstArrival.lineName.isNotEmpty(), "线路名非空")
            check(firstArrival.lineId.isNotEmpty(), "lineId 非空")
        }

        val detailBody = File(fixtures, "linedetail.txt").readText(Charsets.UTF_8)
        val detail = parseLineDetail(detailBody, "315282310627")
        eq(detail.line.name, "游1", "线路名")
        eq(detail.stops.size, 28, "站表 28 站")
        eq(detail.stops.first().order, 1, "首站站序为 1（取数组位置）")
        eq(detail.stops.last().order, 28, "末站站序等于站数")
        check(detail.vehicles.isNotEmpty(), "有在途车辆", detail.vehicles.size, ">0")
        check(detail.vehicles.first().order >= 1, "车辆 order 有效")
        check(detail.line.startName.isNotEmpty() && detail.line.endName.isNotEmpty(), "首末站名齐全")

        // 文案口径（和 Android 完全一致的那几条硬规则）
        eq(BusText.minutesOfSecs(-1), -1, "无数据 → -1")
        eq(BusText.minutesOfSecs(0), 0, "0 秒 → 0（即将到站）")
        eq(BusText.minutesOfSecs(59), 0, "不足 1 分钟 → 0")
        eq(BusText.minutesOfSecs(60), 1, "整 1 分钟 → 1")
        eq(BusText.minutesOfSecs(61), 2, "61 秒向上取整 → 2")
        eq(BusText.rowEtaText(0, 3, false, "", true, false), "即将到站", "0 分钟不能说「到站了」")
        eq(BusText.rowEtaText(0, 0, true, "", true, false), "到站了", "到站文案")
        eq(BusText.rowEtaText(-1, 4, false, "", true, false), "4 站", "无分钟时退到站数")
        eq(BusText.rowEtaText(-1, 0, false, "不在运营时间", false, false), "不在运营时间", "退到上游状态")
        eq(BusText.rowPrimaryText(12, 7, false, "", true, false), "12分钟 · 7站", "大字合并分钟与站数")
        eq(BusText.rowDetailText(2, -1, false, 12, 0), "还有 2 站 · 下一辆 12 分钟", "小字")

        // ---------- 13. 站点详情与时刻表（真实样本） ----------
        val stopBody = File(fixtures, "stopdetail.txt").readText(Charsets.UTF_8)
        val stopStations = parseStopDetail(stopBody, 39.63, 118.18)
        eq(stopStations.size, 3, "站点详情有 3 个同名站台（不合并，交给界面切换）")
        check(stopStations.any { it.arrivals.isNotEmpty() }, "至少一个站台有线路")
        check(stopStations.all { it.name.isNotEmpty() }, "所有站台名非空")
        eq(stopStations.first().arrivals.size, 7, "首站台 7 条线路")
        check(stopStations.first().name.isNotEmpty(), "站台名非空")

        val ttBody = File(fixtures, "timetable.txt").readText(Charsets.UTF_8)
        val tt = parseTimetable(ttBody)
        eq(tt.lineName, "游1", "时刻表线路名")
        check(tt.today.isNotEmpty(), "今日预测非空", tt.today.size, ">0")
        check(tt.history.isNotEmpty(), "历史班次非空", tt.history.size, ">0")
        check(tt.todayTimes().isNotEmpty(), "今日有具体班次")
        eq(tt.historyTitle, "昨日实际班次", "优先用昨日而非前日")
        check(tt.firstDep.isNotEmpty() && tt.lastDep.isNotEmpty(), "首末班已算出")
        check(tt.todayTimes() == tt.todayTimes().sorted(), "今日班次升序")
        check(tt.historyTimes() == tt.historyTimes().sorted(), "历史班次升序")

        val emptyToday = """{"timetable":{"timetable":[{"fTime":"06:00","eTime":"07:00","schedules":[]}]}}"""
        val emptyParsed = parseTimetable(emptyToday)
        eq(emptyParsed.today.size, 0, "空分段不算有班次")
        // ---------- 14. 掌上公交（第二数据源） ----------
        val mg106 = File(fixtures, "mg106.txt").readText(Charsets.UTF_8)
        val mgStops = Mygolbs.parseStops(mg106, "唐山市", 39.63, 118.18, 20)
        eq(mgStops.size, 16, "106 返回 16 个站")
        eq(mgStops.first().name, "工人医院站", "首站名")
        check(mgStops.first().distanceMeters in 1..5000, "首站距离合理", mgStops.first().distanceMeters, "1..5000")
        check(mgStops.first().id.startsWith("mg|唐山市|"), "站点 id 用 mg| 编码")

        val mg105 = File(fixtures, "mg105.txt").readText(Charsets.UTF_8)
        val mgStation = Mygolbs.parseStopLines(mg105, "唐山市", "工人医院站")
        eq(mgStation.arrivals.size, 9, "105 解析出 9 条线路")
        val firstMg = mgStation.arrivals.first()
        eq(firstMg.lineName, "71路", "首条线路名")
        eq(firstMg.direction, 0, "upperOrDown=1 → 上行")
        eq(firstMg.startName, "凤凰新城首末站", "起点站名")
        eq(firstMg.arrived, false, "「即将到站」不算已到站（只有已到站/正在进站才算）")
        eq(firstMg.minutes, 0, "「即将到站」分钟归零")
        check(mgStation.arrivals.none { it.lineName.contains("地铁") }, "地铁线路已跳过")

        // 文本 → 站数/分钟的换算（这些是最容易算错的地方）
        eq(Mygolbs.stopsOf("7站"), 7, "「7站」→7")
        eq(Mygolbs.stopsOf("即将到站"), 0, "「即将到站」→0")
        eq(Mygolbs.minutesOfText("23分钟"), 23, "「23分钟」→23")
        eq(Mygolbs.minutesOfText("小于1分钟"), 0, "「小于1分钟」→0")
        eq(Mygolbs.minutesOfText("1小时44分钟"), 104, "「1小时44分钟」→104（不能读成 44）")
        eq(Mygolbs.minutesOfText("14:05 到达"), -1, "绝对时间不猜分钟")
        eq(Mygolbs.cleanNearText("7站 [开往 益民小区]"), "7站", "去掉括号后缀")

        // 坐标纠偏：GCJ 与 WGS 往返误差应在米级
        val gcj = Mygolbs.wgs2gcj(39.63, 118.18)
        check(gcj[0] > 39.63 && gcj[1] > 118.18, "唐山在偏移区，GCJ 应偏大")
        val back = Mygolbs.gcj2wgs(gcj[0], gcj[1])
        check(kotlin.math.abs(back[0] - 39.63) < 0.00002 && kotlin.math.abs(back[1] - 118.18) < 0.00002,
            "WGS→GCJ→WGS 往返误差 <2m", "${back[0]},${back[1]}", "39.63,118.18")
    }

    // ---------- 8. 平台无关性证据 ----------
    // Kotlin 的 String.hashCode 在 JVM 与 Native 上算法相同，这里把 JVM 实测值钉住，
    // 将来在 iOS 上重跑同一个断言即可确认配色初值一致。
    eq("abc".hashCode(), 96354, "String.hashCode 算法基线")
    // 期望值由独立实现（Python 复刻 java.lang.String.hashCode）算出，
    // 将来在 iOS 上重跑同一断言即可确认配色初值两端一致。
    eq("jwglxt_54CF18E08E7A9A67E065000000000001".hashCode(), 113375376, "课程 key 的 hash 与 JVM 一致")

    println("—— 探针结果 ——")
    println("通过 $passed 项，失败 ${failures.size} 项")
    if (failures.isNotEmpty()) {
        for (f in failures) println("✗ $f")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASSED")
}

/** 探针专用：带请求头发 GET（车来了缺 Referer 会直接 400）。 */
private fun httpGetWithHeaders(url: String, headers: Map<String, String>): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code >= 400) throw IllegalStateException("HTTP $code：${text.take(200)}")
        text
    } finally {
        conn.disconnect()
    }
}

private fun Geo_isSubwayName(name: String): Boolean =
    com.tstc.zstangshi.shared.bus.Geo.isSubwayStationName(name)
