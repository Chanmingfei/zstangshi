package com.tstc.zstangshi.jw;

import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;

/**
 * 登录 + 同步的共用流程。
 *
 * 以前这段逻辑散在「我的」页里，加自动登录之后冷启动也要走一遍，所以抽出来：
 *   - 登录成功不代表课表是新的，只有「本地没有 / 超过 12 小时 / 用户主动点同步」才去抓；
 *   - 抓回来的课表先落盘，再让 UI 刷新（进程被杀也不丢）；
 *   - 开学日期教务不给，第一次同步用「今天所在周的周一」兜底，用户改过就用改过的。
 */
public final class Auth {

    private Auth() {}

    /** 本地多久没同步就认为该刷一次 */
    private static final long STALE_MS = 12 * 3600_000L;

    public static final class Outcome {
        /** 登录成功 */
        public boolean ok;
        /** 顺带同步了课表 */
        public boolean synced;
        public int courses;
        public String message = "";
    }

    /** 冷启动能不能直接自动登录：开关打开 + 账号密码都在本地。 */
    public static boolean canAutoLogin(Store s) {
        return s.autoLogin() && !s.user().isEmpty() && !s.pass().isEmpty();
    }

    public static Outcome loginAndMaybeSync(String user, String pass, Store store, boolean forceSync) {
        Outcome o = new Outcome();
        JwClient c = new JwClient(store);
        JwClient.Result r = c.login(user, pass);
        if (!r.ok) {
            o.message = r.message;
            return o;
        }
        o.ok = true;
        // 姓名是登录顺带抓回来的，写进本地（绑定学号，换账号不串）
        if (!r.studentName.isEmpty()) store.setStudentName(r.studentName, user);

        boolean stale = System.currentTimeMillis() - store.lastSync() > STALE_MS;
        if (!forceSync && store.hasLocal() && !stale) return o;

        JwClient.Result t = c.fetchTimetable();
        if (t.ok && t.timetable != null) {
            String old = store.startDate(t.semesterId);
            t.timetable.semester.startDate = (old == null || old.isEmpty())
                    ? guessStart() : old;
            store.saveTimetable(t.timetable);
            o.synced = true;
            o.courses = t.timetable.courses.size();
        } else {
            // 登录成功但抓课表失败：把原因带出去，别静默失败
            o.message = t.message;
        }
        return o;
    }

    /**
     * 教务不返回开学日期。第一次同步时按「今天所在周 = 第 1 周」倒推，
     * 用户可以在「我的 → 开学第一周周一」里改，改过就记住。
     */
    public static String guessStart() {
        return WeekMath.normalizeToMonday(WeekMath.todayDateLabel());
    }

    /** 只判断密码对不对，不动课表（「我的」里改密码、登录页点登录都用它）。 */
    public static JwClient.Result loginOnly(String user, String pass, Store store) {
        return new JwClient(store).login(user, pass);
    }

    public static int courseCount(Timetable t) {
        return t == null ? 0 : t.courses.size();
    }
}
