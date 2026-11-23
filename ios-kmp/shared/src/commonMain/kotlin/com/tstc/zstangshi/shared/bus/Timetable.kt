package com.tstc.zstangshi.shared.bus

/** 一段发车时刻（含该段的具体班次）。 */
class BusTimetableSegment {
    var fromTime: String = ""
    var toTime: String = ""
    var interval: Int = -1
    val times: MutableList<String> = ArrayList()
}

/** 首站发车时刻表（含「今天预测 / 昨日实际 / 前日实际」三种来源）。 */
class BusTimetable {
    var lineName: String = ""
    var startName: String = ""
    var endName: String = ""
    var depTitle: String = ""
    var tip: String = ""
    var historyTitle: String = ""
    var firstDep: String = ""
    var lastDep: String = ""
    var interval: Int = -1

    private val _today = ArrayList<BusTimetableSegment>()
    private val _history = ArrayList<BusTimetableSegment>()
    val today: List<BusTimetableSegment> get() = _today
    val history: List<BusTimetableSegment> get() = _history

    internal fun addToday(s: BusTimetableSegment) { _today.add(s) }
    internal fun addHistory(s: BusTimetableSegment) { _history.add(s) }

    /** 今天的全部班次，升序。 */
    fun todayTimes(): List<String> = _today.flatMap { it.times }.sorted()
    fun historyTimes(): List<String> = _history.flatMap { it.times }.sorted()
}
