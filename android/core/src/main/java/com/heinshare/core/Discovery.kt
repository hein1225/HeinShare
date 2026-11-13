package com.heinshare.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
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

    /**
     * 手动扫描窗口长度（毫秒）。
     *
     * 扫描期间才会主动查询局域网；窗口一过就恢复成「只广播自己、不主动找」。
     */
    const val SCAN_WINDOW_MS = 6000L

    data class Peer(
        val id: String,
        val host: String,
        val port: Int,
        val name: String,
        val kind: String,
        val accepts: Boolean,
        val lastSeen: Long,
        /**
         * true = 只是历史里的设备（之前传输过），本次没扫到。
         *
         * 界面用它区分「刚扫到的在线设备」和「上次用过的设备」。
         */
        val fromHistory: Boolean = false
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
    private val onLog: (String) -> Unit,
    /**
     * 见到对端时回调（不管有没有在扫描窗口内）。
     *
     * 平时不扫描，但别人的包照样会来 —— 用它记住「刚才见过谁」，
     * 界面据此显示历史设备的在线/离线。
     */
    private val onSeen: (Discovery.Peer) -> Unit = {}
) {
    private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    @Volatile var running = false
        private set

    val peers = ConcurrentHashMap<String, Discovery.Peer>()

    /**
     * 记住见过的对端（host → peer）。
     *
     * 与 [peers] 分开：平时不扫描，但别人发来的包照样要回，
     * 这时把对端记下来，收到分享请求时就能报出设备名而不是一串 IP，
     * 也能拿到它的设备 id（同一台设备在历史里只留一条）。
     */
    private val seenPeers = ConcurrentHashMap<String, Discovery.Peer>()

    /** 查某个 IP 之前见过的对端。 */
    fun seenPeerFor(host: String): Discovery.Peer? = seenPeers[host]

    /** 子网广播地址：发现包走它才能保证从 Wi-Fi 发出去。 */
    private val subnetBroadcast: String by lazy { Net.subnetBroadcast(localIp) }

    /** 每个对端最近一次收到本机单播应答的时间（同一对端 2 秒一次）。 */
    private val answers = ConcurrentHashMap<String, Long>()

    /**
     * 手动扫描请求（用户点「扫描」）。
     *
     * 用时间戳而不是布尔量：广播线程每次看到它就立刻补发一轮 beacon + query，
     * 并且把「已应答过的对端」清空，让对端立刻重新应答一遍 —— 否则要等
     * 自动应答的 10 秒间隔，用户会觉得「点了没反应」。
     */
    @Volatile private var scanRequestedAt = 0L
    /** 已经处理到哪个扫描请求，避免 3 秒窗口里每轮广播都重复发。 */
    private var scanHandledAt = 0L

    /**
     * 扫描窗口的截止时间。
     *
     * **只有在这个窗口里才会主动查询局域网、才会把对端收进设备列表。**
     * 窗口外只广播自己的存在（让别人能扫到我们），不主动找别人 ——
     * 用户要求：不自动扫描，平时设备列表只显示之前传输过的设备。
     */
    @Volatile private var scanUntil = 0L

    /** 触发一次扫描（线程安全，可在任意线程调用）。 */
    fun requestScan() {
        val now = System.currentTimeMillis()
        scanRequestedAt = now
        scanUntil = now + Discovery.SCAN_WINDOW_MS
        answers.clear()
        // 每次扫描都是「重新找一遍」：上一次的结果作废，避免列表里留着过时设备。
        peers.clear()
    }

    /** 此刻是否处于扫描窗口。 */
    private fun scanning(now: Long): Boolean = now < scanUntil

    fun start() {
        if (running) return
        running = true
        val s = MulticastSocket(null)
        s.reuseAddress = true
        // 绑定 0.0.0.0 而不是本机单播地址：
        // 绑定到具体单播地址的 UDP 套接字在 Linux 内核里**收不到组播包**，
        // 对端（华为手机）用组播发的查询就永远进不来，「有时搜不到本机」就是这么来的。
        s.bind(InetSocketAddress(Discovery.PORT))
        s.broadcast = true
        // 出口网卡固定成本机 IP 所在的网卡：
        // 广播 255.255.255.255 和组播 224.0.0.1 都按默认路由走，
        // 手机开数据流量时默认路由在蜂窝网上，发出去的手机根本收不到。
        val nif = try {
            NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
        } catch (e: Exception) {
            null
        }
        if (nif != null) {
            try {
                s.networkInterface = nif
                onLog("发现出口网卡：${nif.name}")
            } catch (e: Exception) {
                onLog("设置出口网卡失败：${e.message}")
            }
        } else {
            onLog("未找到本机 IP 对应的网卡，发现包可能走默认路由")
        }
        try {
            // 注意：安卓 SDK 的 MulticastSocket 只有 joinGroup(SocketAddress, NetworkInterface)
            val group = InetSocketAddress(InetAddress.getByName(Discovery.MULTICAST), 0)
            if (nif != null) s.joinGroup(group, nif) else s.joinGroup(InetAddress.getByName(Discovery.MULTICAST))
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
                    // 一直广播自己的存在（mode=10）：这是「让别人能找到我们」，
                    // 不是「扫描别人」，所以任何时候都在发。
                    if (now - lastBroadcast >= 900) {
                        lastBroadcast = now
                        val answer = Discovery.beacon(localIp, tcpPort, identity, deviceName, false)
                        sendBroadcast(answer)
                        sendTo(answer, Discovery.MULTICAST)
                    }
                    // 只有扫描窗口内才**主动查询局域网**（mode=1 + harmony query）。
                    // 用户要求：不自动扫描，只有点「扫描」才真的去找设备。
                    if (scanning(now) && (now - lastQuery >= 800 || scanRequestedAt > scanHandledAt)) {
                        scanHandledAt = scanRequestedAt
                        lastQuery = now
                        val query = Discovery.harmonyQuery(localIp, tcpPort, identity, deviceName)
                        sendBroadcast(query)
                        sendTo(query, Discovery.MULTICAST)
                        val beacon = Discovery.beacon(localIp, tcpPort, identity, deviceName, true)
                        sendBroadcast(beacon)
                        sendTo(beacon, Discovery.MULTICAST)
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
                            // 对端总是记住；只有扫描窗口内才把设备放进列表。
                            seenPeers[host] = peer
                            try {
                                onSeen(peer)
                            } catch (e: Exception) {
                            }
                            if (scanning(System.currentTimeMillis())) {
                                peers[peer.id] = peer
                                onPeer(peer)
                            }
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

    /**
     * 广播发现包。
     *
     * 同时发两份：子网广播地址（走直连路由，一定从 Wi-Fi 出去）
     * 与有限广播 255.255.255.255（走默认路由，手机开数据流量时会跑蜂窝网，但留着无害）。
     */
    private fun sendBroadcast(data: ByteArray) {
        sendTo(data, subnetBroadcast)
        sendTo(data, Net.LIMITED_BROADCAST)
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
