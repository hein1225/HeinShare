package com.heinshare.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 读取 GitHub 仓库的「about」信息。
 *
 * 「海因软件分享」区块里每个条目的介绍，直接取对应仓库在 GitHub 上写的
 * 仓库描述（就是仓库首页右侧 About 里的那句话），这样介绍永远跟仓库
 * 保持一致，改了仓库描述 App 里跟着变，不用再改代码。
 *
 * 拿不到时用调用方给的兜底文案。
 */
object GitHubRepoInfo {

    /** 仓库信息。 */
    data class Info(
        /** 仓库描述（About 里的那句话）。 */
        val description: String,
        /** 仓库主页。 */
        val htmlUrl: String,
        /** Star 数。 */
        val stars: Int
    )

    private const val PREFS = "heinshare_repo_info"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 小时

    /**
     * 取仓库信息（异步）。
     *
     * @param slug 形如 `hein1225/HeinPlay`
     * @param fallback 拿不到时的兜底介绍
     * @param onResult 回调（主线程）
     */
    fun fetch(context: Context, slug: String, fallback: String, onResult: (Info) -> Unit) {
        val app = context.applicationContext
        // 先给缓存（若有），让界面立刻有内容
        cached(app, slug)?.let { onResult(it) }
        Thread {
            val info = request(app, slug) ?: cached(app, slug)
            val result = info ?: Info(fallback, "https://github.com/$slug", 0)
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    // ------------------------------------------------------------ 内部

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读缓存（未过期才算）。 */
    private fun cached(context: Context, slug: String): Info? {
        val p = prefs(context)
        val key = slug.replace('/', '_')
        val ts = p.getLong("ts_$key", 0L)
        if (ts == 0L || System.currentTimeMillis() - ts > CACHE_TTL_MS) return null
        val desc = p.getString("desc_$key", null) ?: return null
        return Info(
            description = desc,
            htmlUrl = p.getString("url_$key", "https://github.com/$slug") ?: "https://github.com/$slug",
            stars = p.getInt("stars_$key", 0)
        )
    }

    private fun save(context: Context, slug: String, info: Info) {
        val key = slug.replace('/', '_')
        prefs(context).edit()
            .putLong("ts_$key", System.currentTimeMillis())
            .putString("desc_$key", info.description)
            .putString("url_$key", info.htmlUrl)
            .putInt("stars_$key", info.stars)
            .apply()
    }

    /** 请求 GitHub API。失败返回 null。 */
    private fun request(context: Context, slug: String): Info? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://api.github.com/repos/$slug").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "heinshare-android")
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val desc = json.optString("description").orEmpty().trim()
            val url = json.optString("html_url").orEmpty().trim()
                .ifBlank { "https://github.com/$slug" }
            val stars = json.optInt("stargazers_count", 0)
            if (desc.isBlank()) return null
            val info = Info(desc, url, stars)
            save(context, slug, info)
            return info
        } catch (e: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
