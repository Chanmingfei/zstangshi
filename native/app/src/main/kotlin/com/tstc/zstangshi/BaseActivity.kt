package com.tstc.zstangshi

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import com.tstc.zstangshi.data.Store

/**
 * 所有界面的基类：把「浅色 / 深色 / 跟随系统」这个应用内开关翻译成 Configuration 的
 * uiMode 夜间位，让 values-night/colors.xml 与所有 @color 资源一起跟着换肤。
 *
 * 关键坑（之前「跟随系统反着动 / 深色不生效」的根因）：
 *   本 App 在 manifest 里给每个 Activity 声明了 android:configChanges="...uiMode"，
 *   意思是「深浅色切换我自己处理，系统别重建」。于是系统会用它自己的配置（不带我们设的
 *   夜间位）通过 applyOverrideConfiguration 覆盖掉 attachBaseContext 里 createConfigurationContext
 *   设的值 —— 夜间位被抹掉，于是卡片/底栏/状态栏始终是浅色。
 *   修复：在 applyOverrideConfiguration 这个系统下发配置的唯一切口里重新注入夜间位；
 *   并在 onConfigurationChanged 里对「跟随系统」做重建刷新。
 */
open class BaseActivity : Activity() {

    /** 本次实际套用的夜间位，用来判断系统 / 配置的深浅是否真的变了。 */
    private var appliedUiMode = -1

    override fun attachBaseContext(base: Context) {
        // 冷启动（applyOverrideConfiguration 可能拿不到）时先靠它兜底
        super.attachBaseContext(wrapNight(base))
    }

    /**
     * 系统把「官方配置」下发给 Activity 的唯一切口。在这里注入我们的夜间位最可靠：
     * 显式 浅色/深色 直接定死；跟随系统 保留系统下发的夜间位（本来就是对的）。
     */
    override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
        if (overrideConfiguration != null) {
            val m = Store(this).themeMode()
            if (m == Store.THEME_DARK) {
                overrideConfiguration.uiMode = (overrideConfiguration.uiMode and MASK.inv()) or YES
            } else if (m == Store.THEME_LIGHT) {
                overrideConfiguration.uiMode = (overrideConfiguration.uiMode and MASK.inv()) or NO
            }
            // 跟随系统：保留系统下发的夜间位
            appliedUiMode = overrideConfiguration.uiMode and MASK
        }
        super.applyOverrideConfiguration(overrideConfiguration)
    }

    private fun wrapNight(base: Context): Context {
        val cfg = Configuration(base.resources.configuration)
        val night = resolveNight(base)
        cfg.uiMode = (cfg.uiMode and MASK.inv()) or (if (night) YES else NO)
        appliedUiMode = cfg.uiMode and MASK
        return base.createConfigurationContext(cfg)
    }

    /**
     * 解析当前应该走浅色还是深色：
     *   - 显式 深色 / 浅色 直接定；
     *   - 跟随系统：读「交付给本 App 的配置」里的夜间位。
     *     很多国产 ROM 不会动 Resources.getSystem() 的夜间位，但会给 App 下发正确的夜间位，
     *     所以这里读 base / app 的资源配置，而不是 Resources.getSystem()。
     */
    private fun resolveNight(ctx: Context): Boolean {
        val m = Store(ctx).themeMode()
        if (m == Store.THEME_DARK) return true
        if (m == Store.THEME_LIGHT) return false
        val sys = ctx.resources.configuration.uiMode and MASK
        return sys == YES
    }

    /** 系统切换深浅色（uiMode 在我们自己处理的范围里）会走这里，需要重建刷新手写 UI。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val sys = newConfig.uiMode and MASK
        if (sys != appliedUiMode && Store(this).themeMode() == Store.THEME_SYSTEM) {
            appliedUiMode = sys
            recreate()
        }
    }

    override fun onStart() {
        super.onStart()
        if (Store(this).themeMode() == Store.THEME_SYSTEM) {
            try {
                registerReceiver(
                    uiModeReceiver,
                    IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
                )
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(uiModeReceiver)
        } catch (ignored: Exception) {
        }
    }

    private val uiModeReceiver: UiModeReceiver = UiModeReceiver(this)

    /** ACTION_CONFIGURATION_CHANGED 兜底：与本次实际套用的夜间位不同则重建。 */
    fun onSystemUiModeMaybeChanged() {
        if (isFinishing) return
        val sys = applicationContext.resources.configuration.uiMode and MASK
        if (sys != appliedUiMode) {
            appliedUiMode = sys
            recreate()
        }
    }

    /** 静态嵌套类（d8 不允许非静态内部类）。 */
    private class UiModeReceiver(private val host: BaseActivity) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            host.onSystemUiModeMaybeChanged()
        }
    }

    protected fun night(): Boolean = Store(this).darkNow()

    /** 页面底色（深色时走资源 bg_page=夜间值；这里再硬编码一份防止万一没生效时闪白）。 */
    protected fun pageColor(): Int = if (night()) 0xFF0F1520.toInt() else getColor(R.color.bg_page)

    protected fun dp(v: Float): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        private const val MASK = Configuration.UI_MODE_NIGHT_MASK
        private const val YES = Configuration.UI_MODE_NIGHT_YES
        private const val NO = Configuration.UI_MODE_NIGHT_NO
    }
}
