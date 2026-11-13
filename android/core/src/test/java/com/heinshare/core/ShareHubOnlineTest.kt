package com.heinshare.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线/离线判定。
 *
 * 平时不扫描，没有心跳可依赖，只能靠「最近多久见过它」近似：
 * 三分钟内见过就算在线，超过就算离线。
 */
class ShareHubOnlineTest {

    private fun peer(id: String, host: String) =
        Discovery.Peer(id, host, 5684, "设备-$id", "phone", true, System.currentTimeMillis())

    @Test
    fun unseenPeerIsOffline() {
        val p = peer("online-test-unseen", "192.168.9.99")
        assertFalse("没见过的设备应离线", ShareHub.isOnline(p))
    }

    @Test
    fun seenPeerIsOnline() {
        val p = peer("online-test-seen", "192.168.9.10")
        ShareHub.noteSeen(p)
        assertTrue("刚见过的设备应在线", ShareHub.isOnline(p))
    }

    @Test
    fun livePeerIsOnlineEvenWithoutSeen() {
        val p = peer("online-test-live", "192.168.9.20")
        ShareHub.updateLivePeers(listOf(p))
        assertTrue("本次扫到的设备应在线", ShareHub.isOnline(p))
        ShareHub.clearLivePeers()
    }

    @Test
    fun historyPeerOfflineWithoutAnySignal() {
        val p = peer("online-test-history", "192.168.9.30")
        ShareHub.rememberPeer(p)
        assertFalse("只有历史记录、没有实时信号时应离线", ShareHub.isOnline(p))
    }
}
