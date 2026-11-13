package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备列表的合并规则：
 *   平时 = 之前传输过的设备（历史）
 *   扫描时 = 历史 + 本次扫到的设备
 */
class ShareHubPeerListTest {

    private fun peer(id: String, host: String, seen: Long) =
        Discovery.Peer(id, host, 5684, "设备-$id", "phone", true, seen)

    @Test
    fun historyAndLiveAreMerged() {
        val historyId = "merge-test-history"
        val liveId = "merge-test-live"
        ShareHub.rememberPeer(peer(historyId, "192.168.8.10", 1000L))
        ShareHub.updateLivePeers(listOf(peer(liveId, "192.168.8.20", 2000L)))

        val byId = ShareHub.peers.associateBy { it.id }
        assertNotNull("历史设备应留在列表里", byId[historyId])
        assertTrue("历史设备要标 fromHistory", byId[historyId]!!.fromHistory)
        assertNotNull("扫描到的设备应进列表", byId[liveId])
        assertTrue("扫描到的设备不是历史", !byId[liveId]!!.fromHistory)
    }

    @Test
    fun clearingLiveKeepsHistory() {
        val historyId = "merge-test-keep"
        ShareHub.rememberPeer(peer(historyId, "192.168.8.11", 1100L))
        ShareHub.updateLivePeers(listOf(peer("merge-test-tmp", "192.168.8.21", 2100L)))
        ShareHub.clearLivePeers()

        assertNotNull(ShareHub.peers.firstOrNull { it.id == historyId })
        assertNull(ShareHub.peers.firstOrNull { it.id == "merge-test-tmp" })
    }

    @Test
    fun livePeerOverridesHistorySameId() {
        val id = "merge-test-same"
        ShareHub.rememberPeer(peer(id, "192.168.8.30", 1200L))
        ShareHub.updateLivePeers(listOf(peer(id, "192.168.8.31", 2200L)))

        val same = ShareHub.peers.filter { it.id == id }
        assertEquals("同一个 id 只应有一条", 1, same.size)
        assertEquals("192.168.8.31", same[0].host)
        assertTrue("扫到的应覆盖历史", !same[0].fromHistory)
    }
}
