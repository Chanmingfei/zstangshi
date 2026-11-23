package com.tstc.zstangshi.shared.bus

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面距离（米）。移植自追客（Trackr，MIT）`ProvinceCityCatalog.haversineMeters`。
 *
 * 用途：上游偶尔不给 `distance`，或给的是直线距离而我们想按用户真实位置算。
 * 经验：车来了的 `distance` 一般可信，优先用它；只有缺失时才回落到自算。
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        if (lat1 == 0.0 && lon1 == 0.0) return 0
        if (lat2 == 0.0 && lon2 == 0.0) return 0
        // 不用 Math.toRadians：JVM 专属。直接乘 PI/180，两端一致。
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return (EARTH_RADIUS_M * c).toInt()
    }

    /** 站名字里带「地铁」或形如「XX号线」的，判为地铁站（不作为附近公交站展示）。 */
    fun isSubwayStationName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.contains("地铁")) return true
        if (!name.contains("号线")) return false
        // 「1号线」「2号线」这类纯线路名
        val idx = name.indexOf("号线")
        if (idx <= 0) return false
        return name.substring(0, idx).all { it.isDigit() }
    }
}
