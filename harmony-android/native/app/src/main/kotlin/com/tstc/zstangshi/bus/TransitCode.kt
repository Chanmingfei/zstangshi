package com.tstc.zstangshi.bus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * 乘车码渠道。
 *
 * 只做「唤起」——把用户送到对应 App 的乘车码页面，不在本 App 内生成任何码，
 * 所以这里不需要网络、不需要权限、也不持有任何票据。
 *
 * scheme 与降级顺序照抄 Trackr（MIT）：先用带 package 的 scheme 直达，
 * 失败去掉 package 再试一次（有些机型/ROM 的 intent 解析只在全局范围生效），
 * 还不行就退到「打开 App 首页 + Toast 提示自己在首页找乘车码」。
 * 每一次都可能因为对方 App 没装、版本不支持 scheme、系统限制后台拉起而失败，
 * 所以全程 try/catch，失败只 Toast，不抛出去。
 */
object TransitCode {

    /** 每次询问：调用方弹选择层，本类不做任何事。 */
    const val ASK = "ask"

    const val ALIPAY = "alipay"
    const val UNIONPAY = "unionpay"

    const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    const val PACKAGE_UNIONPAY = "com.unionpay"

    /** 支付宝乘车码通用 scheme（appId=200011235 是支付宝官方的「乘车码」入口）。 */
    private const val SCHEME_ALIPAY = "alipays://platformapi/startapp?appId=200011235"

    /** 云闪付全国通用乘车码路由（不用地区限定版，避免换城市后失效）。 */
    private const val SCHEME_UNIONPAY = "upwallet://rn/rnhtmlridingcode"

    /*
     * 微信渠道已下线（2026-11）。
     *
     * 唐山公交的微信乘车码在「城市通」小程序里，而微信不允许外部 App 凭 appid 直达小程序：
     *   · `weixin://dl/business/?appid=…&path=…`（明文 scheme）能不能用，取决于小程序自己在
     *     「小程序后台 → 账号设置 → 隐私与安全 → 明文 scheme 拉起此小程序」里有没有把这个
     *     路径加进白名单。城市通没加，实测一律被微信回「对不起，当前页面无法访问」
     *     （带 query / 不带 query 都试过）；
     *   · `weixin://dl/business/?t=<票据>` 也只能由小程序方用 appid+secret 生成。
     * 所以这里不留半成品，选择层会直接说明「由于微信限制，暂不提供微信乘车码跳转」。
     */

    /** 老版本可能存过 "wechat"，一律当 ASK 处理，别让用户卡在一个不存在的渠道上。 */
    private const val LEGACY_WECHAT = "wechat"

    /**
     * 所有渠道的固定展示顺序：支付宝 → 云闪付。
     */
    @JvmStatic
    fun ids(): Array<String> = arrayOf(ALIPAY, UNIONPAY)

    /** 展示名：每次询问 / 支付宝乘车码 / 云闪付乘车码。 */
    @JvmStatic
    fun label(id: String): String {
        return when (norm(id)) {
            ALIPAY -> "支付宝乘车码"
            UNIONPAY -> "云闪付乘车码"
            else -> "每次询问"
        }
    }

    /** 弹层里每行下面的说明小字。 */
    @JvmStatic
    fun desc(id: String): String {
        return when (norm(id)) {
            ALIPAY -> "打开支付宝里的乘车码"
            UNIONPAY -> "打开云闪付里的乘车码"
            else -> "每次点乘车码时再问一次"
        }
    }

    /** 该渠道的 App 是否已安装。ASK 恒 true（它不需要装任何东西）。 */
    @JvmStatic
    fun installed(ctx: Context, id: String): Boolean {
        return when (norm(id)) {
            ALIPAY -> has(ctx, PACKAGE_ALIPAY)
            UNIONPAY -> has(ctx, PACKAGE_UNIONPAY)
            else -> true
        }
    }

    /**
     * 打开对应乘车码。成功返回 true。
     *
     * ASK 恒返回 false —— 由调用方弹选择层，本类不猜用户想用哪个。
     * 失败时自己 Toast 一句，不要抛异常。
     */
    @JvmStatic
    fun open(ctx: Context, id: String): Boolean {
        return when (norm(id)) {
            ALIPAY -> openAlipay(ctx)
            UNIONPAY -> openUnionPay(ctx)
            else -> false
        }
    }

    // ---------------- 支付宝 ----------------

    /** 三级降级：带 package 的 scheme → 不带 package 的 scheme → App 首页。 */
    private fun openAlipay(ctx: Context): Boolean {
        if (startScheme(ctx, SCHEME_ALIPAY, PACKAGE_ALIPAY)) return true
        if (startScheme(ctx, SCHEME_ALIPAY, null)) return true
        return startHome(ctx, PACKAGE_ALIPAY, "已打开支付宝，请在首页使用乘车码", "未安装支付宝")
    }

    // ---------------- 云闪付 ----------------

    /** 同支付宝：带 package → 不带 package → App 首页。 */
    private fun openUnionPay(ctx: Context): Boolean {
        if (startScheme(ctx, SCHEME_UNIONPAY, PACKAGE_UNIONPAY)) return true
        if (startScheme(ctx, SCHEME_UNIONPAY, null)) return true
        return startHome(ctx, PACKAGE_UNIONPAY, "已打开云闪付，请在首页使用乘车码", "未安装云闪付客户端")
    }

    // ---------------- 公共小工具 ----------------

    /** 用 ACTION_VIEW 拉起 scheme。pkg 为 null 时不限包名，交给系统解析。 */
    private fun startScheme(ctx: Context, scheme: String, pkg: String?): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.setPackage(pkg)
            ctx.startActivity(i)
            true
        } catch (ignored: Exception) {
            // ActivityNotFoundException / SecurityException / 各种 ROM 的怪异常都算「这一步不行」
            false
        }
    }

    /**
     * 最后一级降级：打开 App 首页。
     * @param okMsg  成功拉起后的提示（告诉用户自己在首页找入口）
     * @param noMsg  没装时的提示
     */
    private fun startHome(ctx: Context, pkg: String, okMsg: String, noMsg: String): Boolean {
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) {
                Toast.makeText(ctx, noMsg, Toast.LENGTH_SHORT).show()
                false
            } else {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                Toast.makeText(ctx, okMsg, Toast.LENGTH_LONG).show()
                true
            }
        } catch (ignored: Exception) {
            Toast.makeText(ctx, noMsg, Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun has(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        } catch (ignored: Exception) {
            false
        }
    }

    /** 脏值（旧版本存的字符串、手改的配置）一律当 ASK 处理，不要抛。 */
    private fun norm(id: String?): String {
        return when (id) {
            // LEGACY_WECHAT 也归到 ASK：渠道已下线，别让存过它的用户卡住
            ALIPAY, UNIONPAY, ASK, LEGACY_WECHAT -> if (id == LEGACY_WECHAT) ASK else id
            else -> ASK
        }
    }
}
