package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 热点网段识别。
 *
 * Android 各版本 SoftAP 默认网段不同，热点开启时本机地址必须优先取热点地址，
 * 否则发现包会从错误的网卡发出去。
 */
class HotspotSubnetTest {

    @Test
    fun android10HotspotSubnet() {
        assertTrue(Net.isHotspotAddress("192.168.43.1"))
        assertTrue(Net.isHotspotAddress("192.168.43.137"))
    }

    @Test
    fun android13HotspotSubnet() {
        assertTrue(Net.isHotspotAddress("192.168.42.1"))
    }

    @Test
    fun someRomHotspotSubnet() {
        assertTrue(Net.isHotspotAddress("192.168.49.1"))
    }

    @Test
    fun normalLanIsNotHotspot() {
        assertFalse(Net.isHotspotAddress("192.168.8.39"))
        assertFalse(Net.isHotspotAddress("10.0.0.7"))
        assertFalse(Net.isHotspotAddress("172.16.31.2"))
    }

    @Test
    fun linkLocalIsNotHotspot() {
        assertFalse(Net.isHotspotAddress("169.254.10.10"))
        assertFalse(Net.isHotspotAddress("127.0.0.1"))
    }

    @Test
    fun hotspotSubnetBroadcast() {
        // 热点常见网段是 /24，子网广播即 x.x.x.255
        assertEquals("192.168.43.255", Net.broadcastAddress("192.168.43.1", 24))
        assertEquals("192.168.42.255", Net.broadcastAddress("192.168.42.1", 24))
        assertEquals("192.168.49.255", Net.broadcastAddress("192.168.49.1", 24))
    }
}
