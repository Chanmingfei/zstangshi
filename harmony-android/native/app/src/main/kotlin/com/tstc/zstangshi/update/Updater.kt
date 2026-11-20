package com.tstc.zstangshi.update

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

import org.json.JSONArray

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 检查更新 / 下载 / 拉起系统安装程序。
 *
 * **为什么不用 GitHub API 单源**：GitHub 在国内经常连不上或很慢。这个项目在 Gitee 有
 * 完整镜像（同名 tag、同样挂着 APK 附件），实测 Gitee 接口 0.4 秒内就能回，所以：
 *
 *   · 检查：Gitee API、GitHub API、gh-proxy 代理的 GitHub API **同时查**，
 *     谁能回就用谁，同一个 tag 的元数据合并，取版本号最大的那条；
 *   · 下载：把 Gitee / GitHub / gh-proxy 三条地址放在一起**并发测速**，
 *     挑第一个能连上且最快的，再交给系统下载器。
 *
 * 两个版本同包名、不同签名，不能互相覆盖安装，所以**先按当前 versionName 分通道**
 * （标准版 `v4.0.6` / 鸿蒙版 `harmony-v4.1.1`），再去各源里挑。
 *
 * 下载交给系统 DownloadManager：APK 落在应用自己的外部目录里，下载完成拿回
 * `content://` 的 Uri 再交给系统安装器 —— 不需要 FileProvider，也不需要存储权限。
 */
object Updater {

    private const val GH = "Chanmingfei/zstangshi"
    private const val GT = "mingfei123/zstangshi"
    private const val UA = "ZSTS-Android"

    /** 检查源，按「国内友好度」排序。任一能通就行。 */
    private val SOURCES = arrayOf(
        "https://gitee.com/api/v5/repos/$GT/releases?page=1&per_page=20",
        "https://api.github.com/repos/$GH/releases?per_page=30",
        "https://gh-proxy.com/https://api.github.com/repos/$GH/releases?per_page=30"
    )

    /** 给 GitHub 附件加一条代理地址（国内直连 release 经常卡住）。 */
    private const val GH_PROXY = "https://gh-proxy.com/"

    /** 更新失败时，浏览器里打开的发布页（Gitee 国内能开）。 */
    const val RELEASES_PAGE = "https://gitee.com/$GT/releases"

    /** 一条可用的 Release。 */
    class Release(
        @JvmField var version: String = "",
        @JvmField var tag: String = "",
        @JvmField var title: String = "",
        @JvmField var notes: String = "",
        @JvmField var apkName: String = "",
        @JvmField var size: Long = 0,
        @JvmField var publishedAt: String = "",
        /** 候选下载地址，按「先用哪个」排好；真正下载前还会再测一次速。 */
        @JvmField var urls: MutableList<String> = ArrayList()
    )

    /** 检查结果：三种终态靠字段区分，不要用 null 猜。 */
    class Result(
        @JvmField var hasUpdate: Boolean = false,
        @JvmField var upToDate: Boolean = false,
        @JvmField var release: Release? = null,
        @JvmField var error: String? = null
    )

    class Progress(
        @JvmField var state: Int = 0,
        @JvmField var downloaded: Long = 0,
        @JvmField var total: Long = 0
    )

    /** 鸿蒙版：versionName 带 `-harmony` 后缀（4.1.0-harmony）。 */
    fun isHarmony(versionName: String): Boolean {
        return versionName.contains("harmony", ignoreCase = true)
    }

    /** `harmony-v4.1.0` / `v4.0.5` / `4.0.5` → `4.0.5`；后缀 `-harmony` 一并去掉。 */
    fun plainVersion(text: String): String {
        var s = text.trim()
        if (s.startsWith("harmony-", ignoreCase = true)) s = s.substring(8)
        if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
        val dash = s.indexOf('-')
        if (dash >= 0) s = s.substring(0, dash)
        return s.trim()
    }

    /**
     * 版本比较：按 `.` 切成数字段逐位比，缺的段当 0。
     * 返回 >0 表示 a 比 b 新。
     */
    fun compare(a: String, b: String): Int {
        val x = plainVersion(a).split('.')
        val y = plainVersion(b).split('.')
        val n = Math.max(x.size, y.size)
        for (i in 0 until n) {
            val xi = if (i < x.size) numOf(x[i]) else 0
            val yi = if (i < y.size) numOf(y[i]) else 0
            if (xi != yi) return if (xi > yi) 1 else -1
        }
        return 0
    }

    private fun numOf(s: String): Int {
        val sb = StringBuilder()
        for (c in s.toCharArray()) {
            if (c < '0' || c > '9') break
            sb.append(c)
        }
        if (sb.length == 0) return 0
        return try {
            Integer.parseInt(sb.toString())
        } catch (ignored: NumberFormatException) {
            0
        }
    }

    /**
     * 查一次更新。
     *
     * 多个源并发请求，各自解析后按 tag 合并（Gitee 没有 size，用 GitHub 的补），
     * 再在本通道里取版本号最大的一条。
     *
     * 阻塞调用，必须在后台线程跑；失败不抛异常，把话写进 [Result.error]。
     */
    fun check(currentVersionName: String): Result {
        val harmony = isHarmony(currentVersionName)
        val cur = plainVersion(currentVersionName)
        val out = Result()

        val bodies = arrayOfNulls<String>(SOURCES.size)
        val latch = CountDownLatch(SOURCES.size)
        for (i in SOURCES.indices) {
            val idx = i
            Thread({
                try {
                    bodies[idx] = httpGet(SOURCES[idx])
                } catch (ignored: Exception) {
                } finally {
                    latch.countDown()
                }
            }, "update-src-$i").start()
        }
        try {
            latch.await(9, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }

        val merged = LinkedHashMap<String, Release>()
        var reachable = 0
        for (i in bodies.indices) {
            val body = bodies[i] ?: continue
            reachable++
            try {
                collect(body, harmony, merged)
            } catch (ignored: Exception) {
            }
        }

        if (reachable == 0) {
            out.error = "连不上更新服务器（Gitee / GitHub 都没响应），请检查网络后重试"
            return out
        }

        var best: Release? = null
        for (r in merged.values) {
            if (best == null || compare(r.version, best!!.version) > 0) best = r
        }
        val b = best
        if (b == null) {
            out.error = "没有找到适用于这个版本的安装包"
            return out
        }
        out.release = b
        out.hasUpdate = compare(b.version, cur) > 0
        out.upToDate = !out.hasUpdate
        return out
    }

    /** 解析一个源的 JSON，按 tag 合并进 [into]。 */
    private fun collect(body: String, harmony: Boolean, into: MutableMap<String, Release>) {
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft", false)) continue
            // GitHub 有 prerelease；Gitee 没有这个字段，optBoolean 默认 false
            if (o.optBoolean("prerelease", false)) continue
            val tag = o.optString("tag_name")
            if (tag.isEmpty()) continue
            val isH = tag.startsWith("harmony-", ignoreCase = true)
            if (isH != harmony) continue
            val ver = plainVersion(tag)
            if (ver.isEmpty()) continue
            val apk = apkAssets(o.optJSONArray("assets"), harmony) ?: continue

            var r = into[tag]
            if (r == null) {
                r = Release()
                r.version = ver
                r.tag = tag
                into[tag] = r
            }
            if (r.title.isEmpty()) r.title = o.optString("name").ifBlank { "v$ver" }
            if (r.notes.isEmpty()) r.notes = o.optString("body")
            val time = o.optString("published_at").ifBlank { o.optString("created_at") }
            if (r.publishedAt.isEmpty()) r.publishedAt = time
            if (r.apkName.isEmpty()) r.apkName = apk.name
            if (r.size <= 0) r.size = apk.size
            for (u in apk.urls) if (!r.urls.contains(u)) r.urls.add(u)
        }
    }

    private class Apk(
        @JvmField var name: String = "",
        @JvmField var size: Long = 0,
        @JvmField var urls: MutableList<String> = ArrayList()
    )

    /**
     * 从 assets 里挑本通道的 APK，并把「直连 / 代理」两条地址都收进来。
     * 资产命名以后要是改了（不再带 harmony），标准版还能兜底；
     * 鸿蒙版不行 —— 装错签名只会失败，不如直接说没找到。
     */
    private fun apkAssets(assets: JSONArray?, harmony: Boolean): Apk? {
        if (assets == null) return null
        var fallback: Apk? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = a.optString("browser_download_url")
            if (url.isEmpty()) continue
            val apk = Apk()
            apk.name = name
            apk.size = a.optLong("size", 0L)
            apk.urls.add(url)
            if (url.startsWith("https://github.com/", ignoreCase = true)) {
                val proxied = GH_PROXY + url
                if (!apk.urls.contains(proxied)) apk.urls.add(proxied)
            }
            val isH = name.contains("harmony", ignoreCase = true)
            if (isH == harmony) return apk
            if (fallback == null) fallback = apk
        }
        return if (harmony) null else fallback
    }

    // ──────────────────────────── 下载 ────────────────────────────

    private const val DIR = "updates"

    /**
     * 并发探一下几条下载地址，挑最快的返回。
     *
     * 只发一个字节（Range: bytes=0-1），3 秒内没结果就当不可用。
     * 全都不通时退回第一条，让系统下载器自己去试 —— 有些 CDN 不认 Range，
     * 但真正下载是好的。
     */
    fun pickFastest(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        if (urls.size == 1) return urls[0]
        val n = urls.size
        val lat = LongArray(n)
        for (i in 0 until n) lat[i] = -1
        val latch = CountDownLatch(n)
        for (i in 0 until n) {
            val idx = i
            Thread({
                lat[idx] = probe(urls[idx])
                latch.countDown()
            }, "update-probe-$i").start()
        }
        try {
            latch.await(3500, TimeUnit.MILLISECONDS)
        } catch (ignored: InterruptedException) {
        }
        var best = -1
        var bestMs = Long.MAX_VALUE
        for (i in 0 until n) {
            if (lat[i] >= 0 && lat[i] < bestMs) {
                bestMs = lat[i]
                best = i
            }
        }
        return if (best >= 0) urls[best] else urls[0]
    }

    /** 返回毫秒延迟，失败 -1。 */
    private fun probe(url: String): Long {
        val t0 = System.currentTimeMillis()
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Range", "bytes=0-1")
            val code = c.responseCode
            // 200 = 不认 Range 但能用；206 = 正常；416 = 文件比 2 字节还小（不可能，但也算通）
            if (code == 200 || code == 206 || code == 416) {
                return System.currentTimeMillis() - t0
            }
            return -1
        } catch (e: Exception) {
            return -1
        } finally {
            try {
                c?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 交给系统下载。返回 DownloadManager 的 id；失败返回 -1。
     * 先清掉上一版留下的安装包，别让应用目录越攒越大。
     */
    fun startDownload(a: Activity, r: Release, url: String): Long {
        try {
            val base = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: a.cacheDir
            val dir = File(base, DIR)
            if (dir.isDirectory) {
                val old = dir.listFiles()
                if (old != null) for (f in old) {
                    if (f.name.endsWith(".apk", ignoreCase = true)) f.delete()
                }
            }
            val name = "zstangshi-" + r.version + ".apk"
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("掌上唐师 " + r.version)
                .setDescription("正在下载更新")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, "$DIR/$name")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            return dm.enqueue(req)
        } catch (e: Exception) {
            return -1L
        }
    }

    fun query(a: Activity, id: Long): Progress? {
        return try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
            try {
                if (!c.moveToFirst()) return null
                val p = Progress()
                p.state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                p.downloaded = c.getLong(
                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                p.total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                p
            } finally {
                c.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cancel(a: Activity, id: Long) {
        try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (ignored: Exception) {
        }
    }

    /** 下载完成后的安装包 Uri；还没下完或已被系统清掉时返回 null。 */
    fun downloadedUri(a: Activity, id: Long): Uri? {
        return try {
            val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.getUriForDownloadedFile(id)
        } catch (e: Exception) {
            null
        }
    }

    /** Android 8 起，装 APK 要先允许「安装未知应用」。 */
    fun canInstall(a: Activity): Boolean {
        return try {
            a.packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            true
        }
    }

    fun openInstallSettings(a: Activity): Boolean {
        return try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + a.packageName)))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 把下载好的包交给系统安装器。返回 false = 没有安装器 / 被拦下。 */
    fun install(a: Activity, uri: Uri): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            a.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 字节数 → 「3.6 MB」。 */
    fun sizeText(bytes: Long): String {
        if (bytes <= 0) return ""
        if (bytes < 1024 * 1024) return (bytes / 1024).toString() + " KB"
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    /** GitHub / Gitee 的时间戳 → `2026-11-20`。 */
    fun dateText(iso: String): String {
        if (iso.length >= 10) return iso.substring(0, 10)
        return iso
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val input = c.inputStream
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            return String(bos.toByteArray(), Charsets.UTF_8)
        } finally {
            try {
                c.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }
}
