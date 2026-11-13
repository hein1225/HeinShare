package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现层报文的构造 / 解析回归测试。
 *
 * 对应 Rust 版 `src/src/discovery.rs`：
 * - `discovery_beacon(..., discovering=true)`  → 0x50 0x02 + 2 字节 token + option3 + option8
 * - `discovery_beacon(..., discovering=false)` → 0x42 0x02 + 4 字节 token + 0xBD/0x11
 * - `advertisement_query_harmony(...)`         → 同上，但 JSON 里带 coapUri、mode=1
 * - `discovery_ack(msg_id)`                    → 4 字节 [0x60, 0x00, mid0, mid1]
 */
class DiscoveryTest {

    private val ip = "192.168.1.5"
    private val identity = "AABBCCDDEEFF0011"
    private val name = "我的安卓设备"

    private fun parse(data: ByteArray) = Discovery.parseBeacon(data, ip)

    @Test
    fun discoveringBeaconRoundTrip() {
        val data = Discovery.beacon(ip, 41234, identity, name, true)
        assertEquals(0x50, data[0].toInt() and 0xFF)
        assertEquals(0x02, data[1].toInt() and 0xFF)

        val parsed = parse(data)
        assertNotNull("发现态 beacon 应当能解析", parsed)
        val peer = parsed!!.first
        assertEquals(name, peer.name)
        assertEquals(41234, peer.port)
        assertEquals("computer", peer.kind)
        assertEquals(ip, peer.host)
        assertTrue("能力位 256 表示可接收", peer.accepts)
        // 首字节 0x50 → message type 1（NON），不是 CON，不需要回 ACK
        assertTrue("0x50 报文是非确认型", !parsed.second)
    }

    @Test
    fun answerBeaconRoundTrip() {
        val data = Discovery.beacon(ip, 41234, identity, name, false)
        assertEquals(0x42, data[0].toInt() and 0xFF)
        assertEquals(0x02, data[1].toInt() and 0xFF)

        val parsed = parse(data)
        assertNotNull("应答态 beacon 应当能解析", parsed)
        assertEquals(41234, parsed!!.first.port)
        assertEquals("computer", parsed.first.kind)
    }

    @Test
    fun harmonyQueryCarriesCoapUri() {
        val data = Discovery.harmonyQuery(ip, 41234, identity, name)
        assertEquals(0x42, data[0].toInt() and 0xFF)
        val text = String(data, Charsets.UTF_8)
        assertTrue("应带 coapUri 字段", text.contains("\"coapUri\":\"coap://$ip/device_discover\""))
        assertTrue("mode 应为 1", text.contains("\"mode\":1"))
        assertNotNull(parse(data))
    }

    @Test
    fun ackIsFourBytes() {
        val ack = Discovery.ack(byteArrayOf(0x12, 0x34))
        assertEquals(4, ack.size)
        assertEquals(0x60, ack[0].toInt() and 0xFF)
        assertEquals(0x00, ack[1].toInt() and 0xFF)
        assertEquals(0x12, ack[2].toInt() and 0xFF)
        assertEquals(0x34, ack[3].toInt() and 0xFF)
    }

    @Test
    fun unrelatedPacketIsRejected() {
        assertNull(parse(ByteArray(40)))
        assertNull(parse(byteArrayOf(0x50, 0x02, 0, 0, 0xFF.toByte())))
        assertTrue(Discovery.isDiscoveryPacket(Discovery.beacon(ip, 1, identity, name, true)))
    }
}
