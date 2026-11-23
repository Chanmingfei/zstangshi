package com.tstc.zstangshi.shared.bus

/**
 * 公交数据模型（从 Android `bus/BusModels.kt` 取界面真正用到的那部分）。
 *
 * 全部用可变属性：解析时逐字段填，填完交给界面只读。
 * 这样 K/N 导出的就是普通 ObjC 类，Swift 侧 `hit.name` 直接用，
 * 不必让 Swift 去摸 JSON 树。
 */

/** 一条线路在某站的到站信息。 */
class BusArrival {
    var lineId: String = ""
    var lineName: String = ""
    var direction: Int = 0
    var startName: String = ""
    var endName: String = ""
    var status: String = ""

    var stopsAway: Int = -1
    var minutes: Int = -1
    var arrived: Boolean = false
    var distanceMeters: Int = -1

    /** 第二辆车的估算，对应界面「下一辆」。 */
    var nextStopsAway: Int = -1
    var nextMinutes: Int = -1

    /** 上游没给到站数据，待补一次预测；界面显示「…」。 */
    var pending: Boolean = false

    /** 是否真有实时数据（`stnStates` 非空）。 */
    var realtime: Boolean = false

    /** 用户候车的站序（1 基），来自 `targetStation.order`。 */
    var orderInLine: Int = 0
}

/** 一个站点（含经过它的线路到站）。 */
class BusStation {
    var id: String = ""
    var name: String = ""
    var physicalId: String = ""
    var namesakeId: String = ""
    var lat: Double = 0.0
    var lng: Double = 0.0
    var distanceMeters: Int = -1

    /** 同名站台数（车来了 `namesakeStSize`）。首页显示「N 个站台 X m」用。 */
    var platformCount: Int = 0

    // 对外只暴露只读 List：K/N 会把 MutableList 导成 NSMutableArray，
    // Swift 那边既不能 ForEach 也没有 isEmpty，只有 List 才映射成 [T]。
    private val _arrivals = ArrayList<BusArrival>()
    val arrivals: List<BusArrival> get() = _arrivals

    internal fun addArrival(a: BusArrival) {
        _arrivals.add(a)
    }
}

/** 线路详情里的一个站。 */
class BusStop {
    var id: String = ""
    var physicalId: String = ""
    var name: String = ""

    /** 站序一律取「在 stations 数组里的位置」，不用上游的 order（虚拟站组会乱）。 */
    var order: Int = 0
    var lat: Double = 0.0
    var lng: Double = 0.0
}

/** 线路线路本身的信息。 */
class BusLine {
    var lineId: String = ""
    var name: String = ""
    var lineNo: String = ""
    var direction: Int = 0
    var startName: String = ""
    var endName: String = ""
    var firstTime: String = ""
    var lastTime: String = ""
    var price: String = ""
    var stationsNum: Int = 0
}

/** 在途车辆。 */
class BusVehicle {
    var id: String = ""
    var plate: String = ""
    var order: Int = 0
    var speedKmh: Double = 0.0
    var nextStopName: String = ""
}

/** 线路详情：站表 + 在途车辆。 */
class BusLineDetail {
    val line: BusLine = BusLine()
    var status: String = ""
    var nextDeparture: String = ""
    var reverseId: String = ""
    var reverseStart: String = ""
    var reverseEnd: String = ""
    private val _stops = ArrayList<BusStop>()
    private val _vehicles = ArrayList<BusVehicle>()
    val stops: List<BusStop> get() = _stops
    val vehicles: List<BusVehicle> get() = _vehicles

    internal fun addStop(s: BusStop) {
        _stops.add(s)
    }

    internal fun addVehicle(v: BusVehicle) {
        _vehicles.add(v)
    }

    internal fun sortVehiclesByOrder() {
        _vehicles.sortBy { it.order }
    }
}
