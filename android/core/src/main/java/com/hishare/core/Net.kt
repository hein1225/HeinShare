package com.hishare.core

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/** 网络小工具：本机局域网 IPv4、子网判定。 */
object Net {

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
