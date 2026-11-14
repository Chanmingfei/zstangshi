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

    /** 微信主界面。 */
    private const val SCHEME_WECHAT = "weixin://"

    /**
     * 「城市通」小程序的分享短链（微信里「分享 → 复制链接」得到的那种）。
     * 粘进微信任意聊天框点一下就能进小程序，是外部 App 唯一不依赖对方后台的入口。
     */
    private const val WECHAT_SHARE = "#小程序://城市通/kGiKiIMYbioAfDI"

    /**
     * 「城市通」的 URL Scheme 票据，形如 `weixin://dl/business/?t=xxxxxxxx`。
     *
     * 微信只认这种票据，**没有**「用 appid 拼一个 scheme」的公开写法：
     * 实测 `weixin://dl/business/?appid=wx...&path=...` 会被微信拒成
     * 「对不起，当前页面无法访问」，`?t=分享短链` 同样不行。
     * 票据必须由「城市通」这个小程序自己在后台
     * （小程序后台 → 工具 → 生成 URL Scheme / URL Link）用它的 appid+secret 生成，
     * 最长 30 天有效，外部拼不出来。
     *
     * 拿到票据后把它填在这里，首次点击就会直达小程序；留空则走下面的
     * 「复制分享链接 + 打开微信」流程。
     */
    private const val SCHEME_WECHAT_TICKET = ""

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
            WECHAT -> "复制链接，在微信里粘贴打开「城市通」"
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
     * 微信「城市通」小程序。
     *
     * 三级：
     *  1. 配了 URL Scheme 票据 → 直接拉起小程序（最理想，需要城市通后台生成，见 [SCHEME_WECHAT_TICKET]）；
     *  2. 把「#小程序://城市通/…」分享短链复制到剪贴板，再打开微信 —— 用户粘到任意聊天框点一下即可；
     *  3. 微信都拉不起来 → 提示未安装。
     */
    private fun openWechat(ctx: Context): Boolean {
        if (SCHEME_WECHAT_TICKET.isNotEmpty()) {
            if (startScheme(ctx, SCHEME_WECHAT_TICKET, PACKAGE_WECHAT)) return true
            if (startScheme(ctx, SCHEME_WECHAT_TICKET, null)) return true
        }
        copyShareLink(ctx)
        val tip = "已复制「城市通」链接：在微信任意聊天框粘贴后点一下即可进入"
        if (startScheme(ctx, SCHEME_WECHAT, PACKAGE_WECHAT)) {
            Toast.makeText(ctx, tip, Toast.LENGTH_LONG).show()
            return true
        }
        return startHome(ctx, PACKAGE_WECHAT, tip, "未安装微信")
    }

    /** 把小程序分享短链塞进剪贴板；失败也不影响后面的「打开微信」。 */
    private fun copyShareLink(ctx: Context) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("城市通", WECHAT_SHARE))
        } catch (ignored: Exception) {
            // 部分 ROM 对剪贴板有额外限制，复制不上就只提示用户自己在微信里搜
        }
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
