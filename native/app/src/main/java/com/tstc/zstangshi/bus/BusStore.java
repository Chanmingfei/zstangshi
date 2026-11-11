package com.tstc.zstangshi.bus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时公交的本地状态：当前城市、收藏的线路、上次定位。
 *
 * 用单独的 SharedPreferences 文件，和教务的 {@code Store} 解耦 ——
 * 这块数据跟登录态无关，换账号也不该清掉。
 */
public final class BusStore {

    private static final String FILE = "zsts_bus";
    /** 数据源：0 车来了（默认），1 掌上公交。选过就记住。 */
    private static final String K_SRC = "source";
    /** 城市按源各存一份：两套的编号体系对不上（车来了 056，掌上公交 唐山市）。 */
    private static final String K_CITY_ID_CL = "city_id_cl";
    private static final String K_CITY_NAME_CL = "city_name_cl";
    private static final String K_CITY_ID_MG = "city_id_mg";
    private static final String K_CITY_NAME_MG = "city_name_mg";
    /** 旧版单城市的键，迁移完就删。 */
    private static final String K_CITY_ID_LEGACY = "city_id";
    private static final String K_CITY_NAME_LEGACY = "city_name";
    private static final String K_FAV = "favorites";
    private static final String K_LAT = "last_lat";
    private static final String K_LNG = "last_lng";

    /** 一条收藏线路。方向不同的同号线路要分开存，所以连 lineId 一起带上。 */
    public static final class Fav {
        public String cityId = BusClient.DEFAULT_CITY_ID;
        public String lineId = "";
        public String name = "";
        public int direction = 0;
        public String startName = "";
        public String endName = "";

        public String terminus() {
            if (startName.isEmpty() && endName.isEmpty()) return "";
            return startName + " → " + endName;
        }

        public String key() {
            return cityId + "|" + lineId;
        }
    }

    private final SharedPreferences sp;

    public BusStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        restoreSource();
        migrateLegacyCity();
    }

    /** 把上次选的数据源读回 BusClient；没选过默认车来了。 */
    private void restoreSource() {
        BusClient.setSource(sp.getInt(K_SRC, BusClient.SRC_CHELAILE));
    }

    /**
     * 旧版只存一个城市，而且不同版本存的体系还不一样
     * （3.0.33 以前是车来了的「056」，3.0.34-test 是掌上公交的「唐山市」）。
     * 按值判断该归到哪个源，迁完把旧键删掉。
     */
    private void migrateLegacyCity() {
        String legacyId = sp.getString(K_CITY_ID_LEGACY, "");
        if (legacyId.isEmpty()) return;
        boolean mg = !legacyId.matches("[0-9]+");
        String ki = mg ? K_CITY_ID_MG : K_CITY_ID_CL;
        String kn = mg ? K_CITY_NAME_MG : K_CITY_NAME_CL;
        if (sp.getString(ki, "").isEmpty()) {
            sp.edit()
                    .putString(ki, legacyId)
                    .putString(kn, sp.getString(K_CITY_NAME_LEGACY, ""))
                    .apply();
        }
        sp.edit().remove(K_CITY_ID_LEGACY).remove(K_CITY_NAME_LEGACY).apply();
    }

    /** 记住用户选的数据源（下次打开直接用他选的那个）。 */
    public void setSource(int src) {
        sp.edit().putInt(K_SRC, src).apply();
        BusClient.setSource(src);
    }

    public int source() {
        return sp.getInt(K_SRC, BusClient.SRC_CHELAILE);
    }

    public String cityId() {
        return sp.getString(BusClient.ml() ? K_CITY_ID_MG : K_CITY_ID_CL, BusClient.DEFAULT_CITY_ID);
    }

    public String cityName() {
        return sp.getString(BusClient.ml() ? K_CITY_NAME_MG : K_CITY_NAME_CL,
                BusClient.DEFAULT_CITY_NAME);
    }

    public void setCity(String id, String name) {
        String ki = BusClient.ml() ? K_CITY_ID_MG : K_CITY_ID_CL;
        String kn = BusClient.ml() ? K_CITY_NAME_MG : K_CITY_NAME_CL;
        sp.edit().putString(ki, id).putString(kn, name).apply();
    }

    public double lastLat() {
        return Double.longBitsToDouble(sp.getLong(K_LAT, Double.doubleToLongBits(0)));
    }

    public double lastLng() {
        return Double.longBitsToDouble(sp.getLong(K_LNG, Double.doubleToLongBits(0)));
    }

    public void setLastLocation(double lat, double lng) {
        sp.edit()
                .putLong(K_LAT, Double.doubleToLongBits(lat))
                .putLong(K_LNG, Double.doubleToLongBits(lng))
                .apply();
    }

    public List<Fav> favorites() {
        List<Fav> out = new ArrayList<>();
        String raw = sp.getString(K_FAV, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Fav f = new Fav();
                f.cityId = o.optString("cityId", BusClient.DEFAULT_CITY_ID);
                f.lineId = o.optString("lineId");
                f.name = o.optString("name");
                f.direction = o.optInt("direction", 0);
                f.startName = o.optString("startName");
                f.endName = o.optString("endName");
                // 两个源的 lineId 编号体系互不相通，只把「当前源」的收藏给出去，
                // 另一个源的留着不删 —— 用户切回去还在
                if (f.lineId.isEmpty() || MygolbsClient.sameSource(f.lineId) != BusClient.ml()) {
                    continue;
                }
                out.add(f);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public List<Fav> favoritesOf(String cityId) {
        List<Fav> out = new ArrayList<>();
        for (Fav f : favorites()) {
            if (cityId.equals(f.cityId)) out.add(f);
        }
        return out;
    }

    public boolean isFavorite(String cityId, String lineId) {
        for (Fav f : favorites()) {
            if (cityId.equals(f.cityId) && lineId.equals(f.lineId)) return true;
        }
        return false;
    }

    /** 收藏 / 取消收藏，返回收藏后的状态。 */
    public boolean toggle(Fav item) {
        List<Fav> all = favorites();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).key().equals(item.key())) {
                all.remove(i);
                save(all);
                return false;
            }
        }
        all.add(item);
        save(all);
        return true;
    }

    private void save(List<Fav> all) {
        JSONArray arr = new JSONArray();
        for (Fav f : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("cityId", f.cityId);
                o.put("lineId", f.lineId);
                o.put("name", f.name);
                o.put("direction", f.direction);
                o.put("startName", f.startName);
                o.put("endName", f.endName);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        sp.edit().putString(K_FAV, arr.toString()).apply();
    }
}
