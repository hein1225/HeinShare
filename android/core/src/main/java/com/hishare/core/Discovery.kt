package com.hishare.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备发现：UDP 5684，CoAP 风格报文。
 *
 * 对应 Rust 版 `src/src/discovery.rs`。
 */
object Discovery {
    const val PORT = 5684
    const val SHARE_CAPABILITY_BIT = 256L
    const val CAPABILITY_BITS = 455L

    /**
     * 安卓端对外宣称的身份类型。
     *
     * 与 Rust 版一致用 `12`（电脑）：对端（华为手机 / iPad）把本机当作「可接收分享的电脑」，
     * 才会把本机列进可发送目标；宣称成手机（14）反而可能被对端当成同类设备过滤掉。
     */
    const val DEVICE_TYPE_COMPUTER = 12

    const val MULTICAST = "224.0.0.1"

    data class Peer(
        val id: String,
        val host: String,
        val port: Int,
        val name: String,
        val kind: String,
        val accepts: Boolean,
        val lastSeen: Long
    )

    fun summaryHex(identity: String): String =
        Bytes.hexUpper(Crypto.sha1(identity.toByteArray(Charsets.UTF_8)))

    private fun pushOption(out: ByteArrayOutputStream, delta: Int, value: ByteArray) {
        val len = value.size
        if (len < 13) {
            out.write((delta shl 4) or len)
        } else {
            out.write((delta shl 4) or 13)
            out.write(len - 13)
        }
        out.write(value)
    }

    /** 生成发现报文。discovering=true 是「查询」，false 是「应答/广播自己」。 */
    fun beacon(localIp: String, port: Int, identity: String, name: String, discovering: Boolean): ByteArray {
        val seqNo = (System.nanoTime() and 0xFFFF).toInt()
        val mode = if (discovering) 1 else 10
        val deviceId = "{\"UDID\":\"$identity\"}"
        val json = if (discovering) {
            "{\"deviceId\":${Bytes.jsonString(deviceId)},\"devicename\":${Bytes.jsonString(Bytes.clipChars(name, 60))}," +
                "\"type\":$DEVICE_TYPE_COMPUTER,\"hicomversion\":\"\",\"mode\":$mode," +
                "\"deviceHash\":\"\",\"serviceData\":\"port:$port,\"," +
                "\"extendServiceData\":\"summary:${summaryHex(identity)},nbVer:9,OH:1,icon:1,abl:31,rssi:-58\"," +
                "\"wlanIp\":\"$localIp\",\"capabilityBitmap\":[$CAPABILITY_BITS],\"bType\":5,\"seqNo\":$seqNo}"
        } else {
            "{\"deviceId\":${Bytes.jsonString(deviceId)},\"devicename\":${Bytes.jsonString(Bytes.clipChars(name, 60))}," +
                "\"type\":$DEVICE_TYPE_COMPUTER,\"hicomversion\":\"\",\"mode\":$mode," +
                "\"deviceHash\":\"\",\"serviceData\":\"port:$port,\"," +
                "\"extendServiceData\":\"osType:2,nbVer:9,rssi:-58,abl:8\"," +
                "\"wlanIp\":\"$localIp\",\"capabilityBitmap\":[$CAPABILITY_BITS],\"bType\":5,\"seqNo\":$seqNo}"
        }

        val out = ByteArrayOutputStream()
        if (discovering) {
            out.write(0x50); out.write(0x02)
            val token = Crypto.randomBytes(2)
            out.write(token, 0, 2)
            pushOption(out, 3, localIp.toByteArray(Charsets.UTF_8))
            pushOption(out, 8, "device_discover".toByteArray(Charsets.UTF_8))
            out.write(0xFF)
        } else {
            out.write(0x42); out.write(0x02)
            val token = Crypto.randomBytes(4)
            out.write(token, 0, 4)
            out.write(0xBD); out.write(0x02)
            out.write("device_discover".toByteArray(Charsets.UTF_8))
            out.write(0x11); out.write(0x32)
            out.write(0xFF)
        }
        out.write(json.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * 鸿蒙风格的查询报文（对应 Rust 的 `advertisement_query_harmony`）。
     *
     * 与 beacon 的区别：首字节 0x42 + 4 字节 token、mode=1、JSON 里带 `coapUri` 字段。
     * 对端（华为手机）识别到 `coapUri` 才把本机当成一个正式的分享目标。
     */
    fun harmonyQuery(localIp: String, port: Int, identity: String, name: String): ByteArray {
        val seqNo = (System.nanoTime() and 0xFFFF).toInt()
        val deviceId = "{\"UDID\":\"$identity\"}"
        val json = "{\"deviceId\":${Bytes.jsonString(deviceId)},\"devicename\":${Bytes.jsonString(Bytes.clipChars(name, 60))}," +
            "\"type\":$DEVICE_TYPE_COMPUTER,\"hicomversion\":\"\",\"mode\":1," +
            "\"deviceHash\":\"\",\"serviceData\":\"port:$port,\"," +
            "\"extendServiceData\":\"osType:2,nbVer:9,rssi:-58,abl:8\"," +
            "\"wlanIp\":\"$localIp\",\"capabilityBitmap\":[$CAPABILITY_BITS],\"bType\":5,\"seqNo\":$seqNo," +
            "\"coapUri\":\"coap://$localIp/device_discover\"}"

        val out = ByteArrayOutputStream()
        out.write(0x42); out.write(0x02)
        val token = Crypto.randomBytes(4)
        out.write(token, 0, 4)
        out.write(0xBD); out.write(0x02)
        out.write("device_discover".toByteArray(Charsets.UTF_8))
        out.write(0x11); out.write(0x32)
        out.write(0xFF)
        out.write(json.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** 是否是发现报文（含 `device_discover` 标记）。 */
    fun isDiscoveryPacket(data: ByteArray): Boolean {
        if (data.size < 32) return false
        val tag = "device_discover".toByteArray(Charsets.UTF_8)
        for (i in 0..data.size - tag.size) {
            var hit = true
            for (j in tag.indices) {
                if (data[i + j] != tag[j]) { hit = false; break }
            }
            if (hit) return true
        }
        return false
    }

    /** 解析对端应答，返回 (peer, confirmable, messageId)。 */
    fun parseBeacon(data: ByteArray, host: String): Triple<Peer, Boolean, ByteArray>? {
        if (data.size < 4) return null
        if ((data[0].toInt() and 0xFF) shr 6 != 1) return null
        if ((data[0].toInt() and 0x0F) > 8) return null
        val messageType = (data[0].toInt() shr 4) and 3
        val tokenSize = data[0].toInt() and 0x0F
        if (messageType > 1 || data[1].toInt() != 2 || 4 + tokenSize > data.size) return null

        var offset = 4 + tokenSize
        val options = HashMap<Long, ByteArray>()
        var number = 0L
        while (offset < data.size && (data[offset].toInt() and 0xFF) != 0xFF) {
            val initial = data[offset].toInt() and 0xFF
            offset++
            number += readExtended(initial shr 4, data, offset)
            offset += extendedLength(initial shr 4)
            val length = readExtended(initial and 0x0F, data, offset).toInt()
            offset += extendedLength(initial and 0x0F)
            if (offset + length > data.size || options.size >= 16) return null
            options[number] = data.copyOfRange(offset, offset + length)
            offset += length
        }
        if (offset >= data.size) return null
        val marker = options[11L]
        if (marker == null || String(marker, Charsets.UTF_8) != "device_discover") return null

        var payload = data.copyOfRange(offset + 1, data.size)
        if (payload.isNotEmpty() && payload.last().toInt() == 0) payload = payload.copyOfRange(0, payload.size - 1)
        val json = Json.parseObject(String(payload, Charsets.UTF_8)) ?: return null

        if (json.str("wlanIp") != host) return null
        if (json.num("bType") != 5L) return null

        val caps = json.array("capabilityBitmap") ?: return null
        if (caps.isEmpty() || caps.size > 4) return null
        val firstCap = caps[0]

        val kind = when (json.num("type") ?: 0L) {
            12L -> "computer"
            14L -> "phone"
            17L -> "tablet"
            else -> "unknown"
        }
        val accepts = firstCap and SHARE_CAPABILITY_BIT != 0L

        val service = json.str("serviceData") ?: ""
        val port = service.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("port:") }
            ?.removePrefix("port:")?.toIntOrNull() ?: return null

        val name = json.str("devicename") ?: "Huawei"
        val deviceId = json.str("deviceId")?.let { Json.parseObject(it)?.str("UDID") } ?: "$host:$port"

        val peer = Peer(deviceId, host, port, name, kind, accepts, System.currentTimeMillis())
        val confirmable = messageType == 0
        return Triple(peer, confirmable, byteArrayOf(data[2], data[3]))
    }

    private fun extendedLength(nibble: Int): Int = when (nibble) {
        13 -> 1
        14 -> 2
        else -> 0
    }

    private fun readExtended(nibble: Int, data: ByteArray, offset: Int): Long = when (nibble) {
        13 -> {
            if (offset >= data.size) throw IllegalArgumentException("报文截断")
            (data[offset].toInt() and 0xFF).toLong() + 13
        }
        14 -> {
            if (offset + 2 > data.size) throw IllegalArgumentException("报文截断")
            (((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)).toLong() + 269
        }
        else -> nibble.toLong()
    }

    fun ack(messageId: ByteArray): ByteArray =
        byteArrayOf(0x60, 0x00, messageId[0], messageId[1])
}

/**
 * 发现套接字：绑定 5684，广播 + 组播，周期发应答并接收对端应答。
 */
class DiscoverySocket(
    private val localIp: String,
    private val tcpPort: Int,
    private val identity: String,
    private val deviceName: String,
    private val onPeer: (Discovery.Peer) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    @Volatile var running = false
        private set

    val peers = ConcurrentHashMap<String, Discovery.Peer>()

    /** 每个对端最近一次收到本机单播应答的时间（同一对端 2 秒一次）。 */
    private val answers = ConcurrentHashMap<String, Long>()

    fun start() {
        if (running) return
        running = true
        val s = MulticastSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(localIp, Discovery.PORT))
        s.broadcast = true
        try {
            s.joinGroup(InetAddress.getByName(Discovery.MULTICAST))
        } catch (e: Exception) {
            onLog("加入组播失败：${e.message}")
        }
        socket = s

        thread = Thread {
            val buf = ByteArray(65535)
            var lastBroadcast = 0L
            var lastQuery = 0L
            while (running) {
                try {
                    val now = System.currentTimeMillis()
                    // 与 Rust 的 spawn_advertiser 对齐：
                    //   beacon(discovering=true) 每 900ms 广播+组播一次；
                    //   harmony query 在没见到任何对端时每 1.5s 一次，见到之后每 10s 一次。
                    if (now - lastBroadcast >= 900) {
                        lastBroadcast = now
                        val beacon = Discovery.beacon(localIp, tcpPort, identity, deviceName, true)
                        sendTo(beacon, "255.255.255.255")
                        sendTo(beacon, Discovery.MULTICAST)

                        val queryEvery = if (peers.isEmpty()) 1500L else 10_000L
                        if (now - lastQuery >= queryEvery) {
                            lastQuery = now
                            val query = Discovery.harmonyQuery(localIp, tcpPort, identity, deviceName)
                            sendTo(query, "255.255.255.255")
                            sendTo(query, Discovery.MULTICAST)
                        }
                    }
                } catch (e: Exception) {
                    if (running) onLog("广播失败：${e.message}")
                }

                try {
                    s.soTimeout = 200
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val host = packet.address?.hostAddress ?: continue
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (host == localIp) continue
                    if (!Discovery.isDiscoveryPacket(data)) continue

                    val parsed = try {
                        Discovery.parseBeacon(data, host)
                    } catch (e: Exception) {
                        null
                    }
                    if (parsed != null) {
                        val (peer, confirmable, messageId) = parsed
                        if (peer.id != identity) {
                            peers[peer.id] = peer
                            onPeer(peer)
                        }
                        if (confirmable) {
                            // 对端用 CON 发的包必须回 4 字节 ACK，否则它会按 0/1.2/3.5s 重传三次
                            try {
                                val ack = Discovery.ack(messageId)
                                s.send(DatagramPacket(ack, ack.size, packet.address, Discovery.PORT))
                            } catch (e: Exception) {
                            }
                        }
                    }

                    // 单播应答：让「主动找到本机」的对端知道本机在线且能接收（同一对端 2s 一次）
                    val last = answers[host] ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - last >= 2000) {
                        answers[host] = now
                        val answer = Discovery.beacon(localIp, tcpPort, identity, deviceName, false)
                        sendTo(answer, host)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // 正常，继续下一轮
                } catch (e: Exception) {
                    if (running) onLog("发现套接字异常：${e.message}")
                }
            }
        }
        thread?.isDaemon = true
        thread?.start()
    }

    private fun sendTo(data: ByteArray, target: String) {
        val s = socket ?: return
        try {
            val addr = InetAddress.getByName(target)
            s.send(DatagramPacket(data, data.size, addr, Discovery.PORT))
        } catch (e: Exception) {
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        thread?.interrupt()
        thread = null
    }
}
