package com.tstc.zstangshi.harmony

import android.app.Application

/**
 * 鸿蒙专版的 Application 入口。
 *
 * 只做一件事：装上 [CrashGuard]。
 *
 * 标准版没有自定义 Application（用系统默认的），这里单独加一个是因为
 * 鸿蒙兼容层缺的 API 没法穷举，必须有一层全局兜底：
 * 任何漏网的 `NoClassDefFoundError` / `NoSuchMethodError` 都在这里被吞掉，
 * 用户回到桌面而不是看到「应用已停止」。
 *
 * 注意：本类**不做任何重量级初始化**（不读磁盘、不建线程、不查系统服务），
 * onCreate 跑得越简单，它在鸿蒙上出问题的概率就越低。
 */
class ZstsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            CrashGuard.install(this)
        } catch (t: Throwable) {
            // 连装兜底都失败：继续启动，不能因此崩
        }
    }
}