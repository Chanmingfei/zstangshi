import com.tstc.zstangshi.shared.CourseColorAssigner
import com.tstc.zstangshi.shared.Dates
import com.tstc.zstangshi.shared.KbParser
import com.tstc.zstangshi.shared.Timetable
import com.tstc.zstangshi.shared.WeekMath
import com.tstc.zstangshi.shared.WeekRange
import com.tstc.zstangshi.shared.bus.ChelaileCrypto
import com.tstc.zstangshi.shared.crypto.base64Decode
import com.tstc.zstangshi.shared.crypto.md5Hex
import java.io.File
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
