package com.heinshare.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新。
 *
 * 更新源是 GitHub 仓库的 Release：
 *   https://github.com/hein1225/HeinShare/releases
 *
 * 规则：
 *   * App 版本号始终是 `1.0.0`，不带 `v` 前缀；Release 的 tag 也用同样的写法。
 *   * 启动时自动检查一次，但**每天最多自动检查一次**（记在 SharedPreferences 里）。
 *   * 设置页提供「检查更新」手动入口，随时可查。
 *
 * 检查逻辑：
 *   1. 读 GitHub `releases/latest`；
 *   2. 取 tag_name，比较版本号大小；
 *   3. 有新版本就在 tag 对应的 assets 里找 `.apk`，给出下载链接。
 */
object UpdateChecker {

    /** 仓库地址。 */
    const val REPO_URL = "https://github.com/hein1225/HeinShare"

    /** GitHub Release 列表（Latest）。 */
    private const val API_LATEST = "https://api.github.com/repos/hein1225/HeinShare/releases/latest"

    /** Release 页面（网页兜底，浏览器打开）。 */
    private const val RELEASES_PAGE = "$REPO_URL/releases"

    private const val PREFS = "heinshare_update"
    private const val KEY_LAST_CHECK_DAY = "last_check_day"

    /** 结果。 */
    sealed class Result {
        /** 已是最新。 */
        object UpToDate : Result()

        /** 有新版本。 */
        data class Available(
            val version: String,
            val apkUrl: String?,
            val releaseUrl: String,
            val notes: String?
        ) : Result()

        /** 检查失败（网络问题、仓库不存在等）。 */
        data class Failed(val reason: String) : Result()
    }

    /**
     * 当前 App 版本号。
     *
     * 走 PackageManager 而不是 BuildConfig，是为了让「运行时真实版本」和
     * 「写进 APK 的版本」永远一致（APK 被改名也不会读错）。
     */
    fun currentVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /** 今天是否已经自动检查过。 */
    fun checkedToday(context: Context): Boolean {
        val day = today()
        return prefs(context).getInt(KEY_LAST_CHECK_DAY, 0) == day
    }

    /** 记下「今天已检查」。 */
    fun markChecked(context: Context) {
        prefs(context).edit().putInt(KEY_LAST_CHECK_DAY, today()).apply()
    }

    /**
     * 启动时的自动检查。
     *
     * 每天只做一次；已经有新版本也只提示一次（当天不再重复弹）。
     */
    fun autoCheckIfNeeded(context: Context, onResult: (Result) -> Unit) {
        if (checkedToday(context)) return
        markChecked(context)
        check(context, onResult)
    }

    /** 手动检查（设置页点「检查更新」）。 */
    fun check(context: Context, onResult: (Result) -> Unit) {
        val app = context.applicationContext
        Thread {
            val result = fetch(app)
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    // ------------------------------------------------------------ 内部

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 今天的「天序号」（不是日历天，用 epochDay 更省事）。 */
    private fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()

    private fun fetch(context: Context): Result {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "heinshare-android")
            if (conn.responseCode !in 200..299) {
                return Result.Failed("无法访问 GitHub（HTTP ${conn.responseCode}）")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = (json.optString("tag_name") ?: "").trim()
            if (tag.isBlank()) return Result.Failed("Release 信息不完整")
            val latest = normalizeVersion(tag)
            val current = normalizeVersion(currentVersion(context))
            if (compareVersion(latest, current) <= 0) return Result.UpToDate

            // 在 assets 里找 apk
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name") ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                        if (apkUrl != null) break
                    }
                }
            }
            val releaseUrl = json.optString("html_url").takeIf { it.isNotBlank() }
                ?: "$RELEASES_PAGE/tag/$tag"
            return Result.Available(
                version = latest,
                apkUrl = apkUrl,
                releaseUrl = releaseUrl,
                notes = json.optString("body").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "检查更新失败")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 去掉 `v` 前缀，取纯版本号。 */
    fun normalizeVersion(tag: String): String {
        var t = tag.trim()
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1)
        return t
    }

    /**
     * 比较两个版本号。
     *
     * 按 `.` 分段逐段比数字；`1.0.1 > 1.0.0`。段数不同的按 0 补齐。
     * 每段取开头的数字（`1-beta` 记作 `1`），非数字段记作 0。
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = leadingNumber(pa.getOrNull(i))
            val y = leadingNumber(pb.getOrNull(i))
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /** 取一段里的开头数字；没有数字就是 0。 */
    private fun leadingNumber(segment: String?): Int {
        val s = segment?.trim() ?: return 0
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        if (i == 0) return 0
        return s.substring(0, i).toIntOrNull() ?: 0
    }
}
