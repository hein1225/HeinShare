package com.heinshare.core

import android.content.Context

/**
 * 一条「已接收文件」的持久记录。
 *
 * 记录页要能直接点开以前收到的文件，所以不能只留一句话，
 * 得把能定位文件的东西存下来：
 *   - [target] content:// URI 或绝对路径（打开文件用）
 *   - [display] 给人看的路径
 *   - [time] 接收时间
 */
data class ReceivedFile(
    val name: String,
    val target: String,
    val display: String,
    val mime: String,
    val time: Long
)

/** 已接收文件的持久化接口。 */
interface ReceivedFileStore {
    fun load(): List<ReceivedFile>
    fun save(files: List<ReceivedFile>)
}

/**
 * 已接收文件的编解码（纯逻辑，可直接单测）。
 *
 * 一行一条，字段用 TAB 分隔；文件名与路径做 URL 编码，避免带分隔符。
 */
object ReceivedFileCodec {

    fun encode(files: List<ReceivedFile>): String {
        val sb = StringBuilder()
        for (f in files) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(escape(f.name)).append('\t')
                .append(escape(f.target)).append('\t')
                .append(escape(f.display)).append('\t')
                .append(escape(f.mime)).append('\t')
                .append(f.time)
        }
        return sb.toString()
    }

    fun decode(text: String): List<ReceivedFile> {
        val out = ArrayList<ReceivedFile>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 5) continue
            val time = f[4].toLongOrNull() ?: continue
            out.add(
                ReceivedFile(
                    name = unescape(f[0]),
                    target = unescape(f[1]),
                    display = unescape(f[2]),
                    mime = unescape(f[3]),
                    time = time
                )
            )
        }
        return out
    }

    private fun escape(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun unescape(value: String): String =
        try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
}

/** 用 SharedPreferences 存已接收文件。 */
class PrefsReceivedFileStore(context: Context) : ReceivedFileStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("heinshare_received", Context.MODE_PRIVATE)

    override fun load(): List<ReceivedFile> {
        val text = try {
            prefs.getString(KEY, "") ?: ""
        } catch (e: Exception) {
            ""
        }
        return ReceivedFileCodec.decode(text)
    }

    override fun save(files: List<ReceivedFile>) {
        try {
            prefs.edit().putString(KEY, ReceivedFileCodec.encode(files)).apply()
        } catch (e: Exception) {
            // 存不下去也不能影响传输
        }
    }

    companion object {
        private const val KEY = "received_files"
    }
}
