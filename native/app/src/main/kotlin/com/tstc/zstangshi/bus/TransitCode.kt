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

    /** 唐山公交的微信乘车码走的是微信里的「城市通」小程序。 */
    const val WECHAT = "wechat"

    const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    const val PACKAGE_UNIONPAY = "com.unionpay"
    const val PACKAGE_WECHAT = "com.tencent.mm"

    /** 支付宝乘车码通用 scheme（appId=200011235 是支付宝官方的「乘车码」入口）。 */
    private const val SCHEME_ALIPAY = "alipays://platformapi/startapp?appId=200011235"

    /** 云闪付全国通用乘车码路由（不用地区限定版，避免换城市后失效）。 */
    private const val SCHEME_UNIONPAY = "upwallet://rn/rnhtmlridingcode"

    /** 微信主界面。微信没有公开的「打开某个小程序」scheme。 */
    private const val SCHEME_WECHAT = "weixin://"

    /**
     * 所有渠道的固定展示顺序：支付宝 → 云闪付 → 微信。
     * 选择层按这个顺序排，和 Trackr 一致。
     */
    @JvmStatic
    fun ids(): Array<String> = arrayOf(ALIPAY, UNIONPAY, WECHAT)

    /** 展示名：每次询问 / 支付宝乘车码 / 云闪付乘车码 / 微信乘车码（城市通）。 */
    @JvmStatic
    fun label(id: String): String {
        return when (norm(id)) {
            ALIPAY -> "支付宝乘车码"
            UNIONPAY -> "云闪付乘车码"
            WECHAT -> "微信乘车码（城市通）"
            else -> "每次询问"
        }
    }

    /** 弹层里每行下面的说明小字。 */
    @JvmStatic
    fun desc(id: String): String {
        return when (norm(id)) {
            ALIPAY -> "打开支付宝里的乘车码"
            UNIONPAY -> "打开云闪付里的乘车码"
            WECHAT -> "打开微信「城市通」小程序出示乘车码"
            else -> "每次点乘车码时再问一次"
        }
    }

    /** 该渠道的 App 是否已安装。ASK 恒 true（它不需要装任何东西）。 */
    @JvmStatic
    fun installed(ctx: Context, id: String): Boolean {
        return when (norm(id)) {
            ALIPAY -> has(ctx, PACKAGE_ALIPAY)
            UNIONPAY -> has(ctx, PACKAGE_UNIONPAY)
            WECHAT -> has(ctx, PACKAGE_WECHAT)
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
            WECHAT -> openWechat(ctx)
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

    // ---------------- 微信（城市通） ----------------

    /**
     * 微信只能「打开微信 + 提示用户自己搜小程序」，原因记在这里，别再有人去猜 scheme：
     *
     * 微信官方要求小程序方在「小程序管理后台 → 设置 → 基本设置 → 基础功能 → URL Scheme」
     * 里生成专属 scheme（形如 `weixin://dl/business/?t=xxxxxx`），第三方 App 才能凭它
     * 直达某个小程序。这个 t 值是小程序方自己生成、跟 appid 绑定的，外部拼不出来，
     * 也没有「用小程序名字当参数」的写法 —— 所以 `weixin://dl/business/?t=城市通`
     * 这类拼法是无效的，绝不能写进代码里假装能用。
     *
     * 唐山公交的「城市通」小程序没有公开过它的 scheme，因此这里只降级到：
     *   1. `weixin://` 拉起微信主界面；
     *   2. 不行就 getLaunchIntentForPackage 打开微信；
     *   3. 拉起成功后 Toast 告诉用户去哪儿搜。
     *
     * 将来拿到「城市通」的 scheme，只要在下面第 1 步前面插一行
     * `if (startScheme(ctx, 那个scheme, PACKAGE_WECHAT)) return true` 即可，
     * 其余降级链不用动。
     */
    private fun openWechat(ctx: Context): Boolean {
        if (startScheme(ctx, SCHEME_WECHAT, PACKAGE_WECHAT)) {
            Toast.makeText(ctx, "已打开微信，请在顶部搜索「城市通」小程序出示乘车码",
                Toast.LENGTH_LONG).show()
            return true
        }
        val ok = startHome(ctx, PACKAGE_WECHAT, "已打开微信，请在顶部搜索「城市通」小程序出示乘车码",
            "未安装微信")
        return ok
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
            ALIPAY, UNIONPAY, WECHAT, ASK -> id
            else -> ASK
        }
    }
}
