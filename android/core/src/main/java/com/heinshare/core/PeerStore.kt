package com.heinshare.core

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 已知设备的持久化接口。
 *
 * 「已知设备」= 之前跟它传输过的设备。
 * 平时不扫描局域网，设备列表就靠这份历史撑住；
 * 用户手动扫描时发现的设备是临时的，不落盘。
 */
interface PeerStore {
    fun load(): List<Discovery.Peer>
    fun save(peers: List<Discovery.Peer>)
}

/**
 * 已知设备的编解码（纯逻辑，不依赖 Android，可直接单测）。
 *
 * 一行一台设备，字段用 TAB 分隔；设备名做 URL 编码，避免里面出现分隔符。
 * 格式：id \t name \t host \t port \t kind \t accepts \t lastSeen
 */
object PeerStoreCodec {

    fun encode(peers: List<Discovery.Peer>): String {
        val sb = StringBuilder()
        for (p in peers) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(escape(p.id)).append('\t')
                .append(escape(p.name)).append('\t')
                .append(escape(p.host)).append('\t')
                .append(p.port).append('\t')
                .append(escape(p.kind)).append('\t')
                .append(if (p.accepts) "1" else "0").append('\t')
                .append(p.lastSeen)
        }
        return sb.toString()
    }

    fun decode(text: String): List<Discovery.Peer> {
        val out = ArrayList<Discovery.Peer>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            if (f.size < 7) continue
            val port = f[3].toIntOrNull() ?: continue
            val lastSeen = f[6].toLongOrNull() ?: continue
            out.add(
                Discovery.Peer(
                    id = unescape(f[0]),
                    host = unescape(f[2]),
                    port = port,
                    name = unescape(f[1]),
                    kind = unescape(f[4]),
                    accepts = f[5] == "1",
                    lastSeen = lastSeen,
                    fromHistory = true
                )
            )
        }
        return out
    }

    private fun escape(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun unescape(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
}

/**
 * 用 SharedPreferences 存已知设备。
 *
 * 单独一个 preference 文件，别跟设置项混在一起 ——
 * 设备列表会被频繁重写，跟设置项混在一起容易把设置写坏。
 */
class PrefsPeerStore(context: Context) : PeerStore {

    private val prefs = context.applicationContext
        .getSharedPreferences("heinshare_peers", Context.MODE_PRIVATE)

    override fun load(): List<Discovery.Peer> {
        val text = try {
            prefs.getString(KEY, "") ?: ""
        } catch (e: Exception) {
            ""
        }
        return PeerStoreCodec.decode(text)
    }

    override fun save(peers: List<Discovery.Peer>) {
        try {
            prefs.edit().putString(KEY, PeerStoreCodec.encode(peers)).apply()
        } catch (e: Exception) {
            // 存不进去也不能影响传输
        }
    }

    companion object {
        private const val KEY = "known_peers"
    }
}
