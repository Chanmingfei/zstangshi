package com.tstc.zstangshi.bus

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList

/**
 * 实时公交的本地状态：当前城市、收藏的线路、上次定位。
 *
 * 用单独的 SharedPreferences 文件，和教务的 `Store` 解耦 ——
 * 这块数据跟登录态无关，换账号也不该清掉。
 */
class BusStore(c: Context) {

    /** 一条收藏线路。方向不同的同号线路要分开存，所以连 lineId 一起带上。 */
    class Fav {
        @JvmField var cityId: String = BusClient.DEFAULT_CITY_ID
        @JvmField var lineId: String = ""
        @JvmField var name: String = ""
        @JvmField var direction: Int = 0
        @JvmField var startName: String = ""
        @JvmField var endName: String = ""

        fun terminus(): String {
            if (startName.isEmpty() && endName.isEmpty()) return ""
            return "$startName → $endName"
        }

        fun key(): String {
            return "$cityId|$lineId"
        }
    }

    private val sp: SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        restoreSource()
        migrateLegacyCity()
    }

    /** 把上次选的数据源读回 BusClient；没选过默认车来了。 */
    private fun restoreSource() {
        BusClient.setSource(sp.getInt(K_SRC, BusClient.SRC_CHELAILE))
    }

    /**
     * 旧版只存一个城市，而且不同版本存的体系还不一样
     * （3.0.33 以前是车来了的「056」，3.0.34-test 是掌上公交的「唐山市」）。
     * 按值判断该归到哪个源，迁完把旧键删掉。
     */
    private fun migrateLegacyCity() {
        val legacyId = sp.getString(K_CITY_ID_LEGACY, "") ?: ""
        if (legacyId.isEmpty()) return
        val mg = !legacyId.matches(Regex("[0-9]+"))
        val ki = if (mg) K_CITY_ID_MG else K_CITY_ID_CL
        val kn = if (mg) K_CITY_NAME_MG else K_CITY_NAME_CL
        if ((sp.getString(ki, "") ?: "").isEmpty()) {
            sp.edit()
                .putString(ki, legacyId)
                .putString(kn, sp.getString(K_CITY_NAME_LEGACY, ""))
                .apply()
        }
        sp.edit().remove(K_CITY_ID_LEGACY).remove(K_CITY_NAME_LEGACY).apply()
    }

    /** 记住用户选的数据源（下次打开直接用他选的那个）。 */
    fun setSource(src: Int) {
        sp.edit().putInt(K_SRC, src).apply()
        BusClient.setSource(src)
    }

    fun source(): Int {
        return sp.getInt(K_SRC, BusClient.SRC_CHELAILE)
    }

    fun cityId(): String {
        return sp.getString(if (BusClient.ml()) K_CITY_ID_MG else K_CITY_ID_CL, BusClient.DEFAULT_CITY_ID) ?: ""
    }

    fun cityName(): String {
        return sp.getString(
            if (BusClient.ml()) K_CITY_NAME_MG else K_CITY_NAME_CL,
            BusClient.DEFAULT_CITY_NAME
        ) ?: ""
    }

    fun setCity(id: String, name: String) {
        val ki = if (BusClient.ml()) K_CITY_ID_MG else K_CITY_ID_CL
        val kn = if (BusClient.ml()) K_CITY_NAME_MG else K_CITY_NAME_CL
        sp.edit().putString(ki, id).putString(kn, name).apply()
    }

    fun lastLat(): Double {
        return java.lang.Double.longBitsToDouble(sp.getLong(K_LAT, java.lang.Double.doubleToLongBits(0.0)))
    }

    fun lastLng(): Double {
        return java.lang.Double.longBitsToDouble(sp.getLong(K_LNG, java.lang.Double.doubleToLongBits(0.0)))
    }

    fun setLastLocation(lat: Double, lng: Double) {
        sp.edit()
            .putLong(K_LAT, java.lang.Double.doubleToLongBits(lat))
            .putLong(K_LNG, java.lang.Double.doubleToLongBits(lng))
            .apply()
    }

    fun favorites(): List<Fav> {
        val out = ArrayList<Fav>()
        val raw = sp.getString(K_FAV, "") ?: ""
        if (raw.isEmpty()) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = Fav()
                f.cityId = o.optString("cityId", BusClient.DEFAULT_CITY_ID)
                f.lineId = o.optString("lineId")
                f.name = o.optString("name")
                f.direction = o.optInt("direction", 0)
                f.startName = o.optString("startName")
                f.endName = o.optString("endName")
                // 两个源的 lineId 编号体系互不相通，只把「当前源」的收藏给出去，
                // 另一个源的留着不删 —— 用户切回去还在
                if (f.lineId.isEmpty() || MygolbsClient.sameSource(f.lineId) != BusClient.ml()) {
                    continue
                }
                out.add(f)
            }
        } catch (ignored: Exception) {
        }
        return out
    }

    fun favoritesOf(cityId: String): List<Fav> {
        val out = ArrayList<Fav>()
        for (f in favorites()) {
            if (cityId == f.cityId) out.add(f)
        }
        return out
    }

    fun isFavorite(cityId: String, lineId: String): Boolean {
        for (f in favorites()) {
            if (cityId == f.cityId && lineId == f.lineId) return true
        }
        return false
    }

    /** 收藏 / 取消收藏，返回收藏后的状态。 */
    fun toggle(item: Fav): Boolean {
        val all = ArrayList(favorites())
        for (i in all.indices) {
            if (all[i].key() == item.key()) {
                all.removeAt(i)
                save(all)
                return false
            }
        }
        all.add(item)
        save(all)
        return true
    }

    private fun save(all: List<Fav>) {
        val arr = JSONArray()
        for (f in all) {
            val o = JSONObject()
            try {
                o.put("cityId", f.cityId)
                o.put("lineId", f.lineId)
                o.put("name", f.name)
                o.put("direction", f.direction)
                o.put("startName", f.startName)
                o.put("endName", f.endName)
            } catch (ignored: Exception) {
            }
            arr.put(o)
        }
        sp.edit().putString(K_FAV, arr.toString()).apply()
    }

    companion object {
        private const val FILE = "zsts_bus"

        /** 数据源：0 车来了（默认），1 掌上公交。选过就记住。 */
        private const val K_SRC = "source"

        /** 城市按源各存一份：两套的编号体系对不上（车来了 056，掌上公交 唐山市）。 */
        private const val K_CITY_ID_CL = "city_id_cl"
        private const val K_CITY_NAME_CL = "city_name_cl"
        private const val K_CITY_ID_MG = "city_id_mg"
        private const val K_CITY_NAME_MG = "city_name_mg"

        /** 旧版单城市的键，迁移完就删。 */
        private const val K_CITY_ID_LEGACY = "city_id"
        private const val K_CITY_NAME_LEGACY = "city_name"
        private const val K_FAV = "favorites"
        private const val K_LAT = "last_lat"
        private const val K_LNG = "last_lng"
    }
}
