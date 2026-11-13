package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 子网广播地址计算：发现包要走它才能从 Wi-Fi 出去。 */
class NetTest {

    @Test
    fun subnetBroadcastAddress() {
        assertEquals("192.168.8.255", Net.broadcastAddress("192.168.8.39", 24))
        assertEquals("192.168.255.255", Net.broadcastAddress("192.168.8.39", 16))
        assertEquals("10.0.0.255", Net.broadcastAddress("10.0.0.7", 24))
        assertEquals("172.16.31.255", Net.broadcastAddress("172.16.31.2", 24))
        // /23：网络部分 192.168.8.0/23，主机位全 1 → 192.168.9.255
        assertEquals("192.168.9.255", Net.broadcastAddress("192.168.8.39", 23))
        assertEquals("192.168.11.255", Net.broadcastAddress("192.168.8.39", 22))
    }
}
