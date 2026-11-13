package com.heinshare.core

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket

/** 网络小工具：本机局域网 IPv4、子网判定。 */
object Net {

    /**
     * 本机所在子网的广播地址（例如 192.168.8.39/24 → 192.168.8.255）。
     *
     * 用途：发现包要发给同网段的华为手机。
     * 255.255.255.255（有限广播）走的是**默认路由**，
     * 手机开着数据流量时默认路由在蜂窝网上，包根本出不了 Wi-Fi；
     * 子网广播地址走的是直连路由，一定从 Wi-Fi 出去。
     */
    fun subnetBroadcast(ip: String): String {
        return try {
            val addr = InetAddress.getByName(ip) as? Inet4Address ?: return LIMITED_BROADCAST
            val nif = NetworkInterface.getByInetAddress(addr) ?: return LIMITED_BROADCAST
            val ia = nif.interfaceAddresses.firstOrNull { it.address == addr } ?: return LIMITED_BROADCAST
            broadcastAddress(ip, ia.networkPrefixLength.toInt())
        } catch (e: Exception) {
            LIMITED_BROADCAST
        }
    }

    /** 由本机 IP 和前缀长度算出子网广播地址（纯计算，便于单测）。 */
    fun broadcastAddress(ip: String, prefix: Int): String {
        return try {
            val src = InetAddress.getByName(ip).address
            val bc = ByteArray(4) { i ->
                // 该字节里有多少位属于网络部分，其余是主机位（广播地址里主机位全 1）
                val netBits = (prefix - i * 8).coerceIn(0, 8)
                val hostMask = (0xFF ushr netBits) and 0xFF
                (src[i].toInt() or hostMask).toByte()
            }
            InetAddress.getByAddress(bc).hostAddress ?: LIMITED_BROADCAST
        } catch (e: Exception) {
            LIMITED_BROADCAST
        }
    }

    /** 有限广播地址。 */
    const val LIMITED_BROADCAST = "255.255.255.255"

    /** VPN / 虚拟网卡前缀：这些网卡的地址不能作为华为分享的 wlanIp。 */
    private val VIRTUAL_IFACE_PREFIXES = listOf("tun", "tap", "ppp", "wg", "docker", "veth", "vboxnet", "virbr")

    /** 取当前对外的 IPv4（优先 wlan / eth）。 */
    fun localIPv4(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var fallback: String? = null
        while (interfaces != null && interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            // 跳过 VPN / 虚拟网卡：华为分享只认真实 Wi-Fi 地址，wlanIp 填虚拟地址会导致对端不回连
            if (VIRTUAL_IFACE_PREFIXES.any { nif.name.startsWith(it) }) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    // 169.254.x.x 是 link-local，不是真实局域网地址
                    if (ip.startsWith("169.254.")) continue
                    if (nif.name.startsWith("wlan") || nif.name.startsWith("eth")) return ip
                    if (fallback == null) fallback = ip
                }
            }
        }
        return fallback ?: "127.0.0.1"
    }

    /** 连到某个对端时，本机实际会用的源地址。 */
    fun localIPv4For(peerIp: String): String {
        return try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(peerIp, 9), 800)
                s.localAddress?.hostAddress ?: localIPv4()
            }
        } catch (e: Exception) {
            localIPv4()
        }
    }

    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val o = parts.mapNotNull { it.toIntOrNull() }
        if (o.size != 4 || o.any { it !in 0..255 }) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254) ||
            o[0] == 127
    }

    fun sameSubnet(a: String, b: String): Boolean {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        if (pa.size != 4 || pb.size != 4) return false
        for (i in 0 until 3) if (pa[i] != pb[i]) return false
        return true
    }
}
