package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已知设备（之前传输过的）的落盘格式。 */
class PeerStoreCodecTest {

    @Test
    fun roundTrip() {
        val peers = listOf(
            Discovery.Peer("id-1", "192.168.8.63", 5684, "我的手机", "phone", true, 1000L),
            Discovery.Peer("id-2", "192.168.8.21", 41234, "Huawei平板", "tablet", false, 2000L)
        )
        val text = PeerStoreCodec.encode(peers)
        val back = PeerStoreCodec.decode(text)
        assertEquals(2, back.size)
        assertEquals("id-1", back[0].id)
        assertEquals("我的手机", back[0].name)
        assertEquals("192.168.8.63", back[0].host)
        assertEquals(5684, back[0].port)
        assertEquals("phone", back[0].kind)
        assertTrue(back[0].accepts)
        assertEquals(1000L, back[0].lastSeen)
        assertTrue("历史设备应标记 fromHistory", back[0].fromHistory)
        assertEquals("id-2", back[1].id)
        assertEquals("Huawei平板", back[1].name)
        assertEquals(41234, back[1].port)
        assertEquals(false, back[1].accepts)
    }

    @Test
    fun nameWithSeparatorIsSafe() {
        // 设备名里带 TAB / 换行 / 空格都不能把格式搞坏
        val weird = listOf(
            Discovery.Peer("id-3", "10.0.0.9", 1, " 平板\tA\nB", "tablet", true, 5L)
        )
        val back = PeerStoreCodec.decode(PeerStoreCodec.encode(weird))
        assertEquals(1, back.size)
        assertEquals(" 平板\tA\nB", back[0].name)
        assertEquals("10.0.0.9", back[0].host)
    }

    @Test
    fun brokenLinesAreSkipped() {
        val text = "id-1\tname\t1.2.3.4\t1\tphone\t1\t10\n" +
            "这一行坏掉了\n" +
            "id-2\tname2\t1.2.3.5\t2\tphone\t0\t20"
        val back = PeerStoreCodec.decode(text)
        assertEquals(2, back.size)
        assertEquals("id-1", back[0].id)
        assertEquals("id-2", back[1].id)
    }

    @Test
    fun emptyTextGivesEmptyList() {
        assertEquals(0, PeerStoreCodec.decode("").size)
        assertEquals(0, PeerStoreCodec.decode("\n\n").size)
    }
}
