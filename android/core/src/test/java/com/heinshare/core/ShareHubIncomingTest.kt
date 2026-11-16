package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * 同一台设备连续发第二个文件时的接收请求行为。
 *
 * 回归的问题：第二个文件收不到「接收请求」提示，页面只显示上一个文件的完成弹窗。
 * 两个成因：
 *   1. 华为的账号认证探测连接结束时无条件把 `ShareHub.incoming` 清空，
 *      把正在等确认的分享请求一起抹掉；
 *   2. 上一次接收完成后 `receiveState` 停在 DONE，新请求到达时界面仍停在完成态。
 */
class ShareHubIncomingTest {

    private fun incoming(id: String): ShareHub.Incoming {
        // 只测状态机，不需要真的握手，构造一个占位 session 即可。
        val session = Receiver.Session(
            fileCount = 1,
            folders = 0,
            totalBytes = 1024L,
            key = ByteArray(16),
            deviceHash = ByteArray(16),
            peer = id,
            accepted = true,
            peerApple = false
        )
        return ShareHub.Incoming("设备-$id", "$id:5684", session, Channel(Socket(), "test"))
    }

    @Test
    fun clearIncomingOnlyClearsItsOwnRequest() {
        val real = incoming("real")
        ShareHub.incoming = real

        // 认证探测连接结束：它没有请求对象，不能清掉正在等确认的请求
        ShareHub.clearIncoming(null)
        assertSame("探测连接不应清掉待确认请求", real, ShareHub.incoming)

        // 别的请求也不能误清
        ShareHub.clearIncoming(incoming("other"))
        assertSame("别的请求不应清掉待确认请求", real, ShareHub.incoming)

        // 自己结束时才清
        ShareHub.clearIncoming(real)
        assertNull("自己的请求结束时应清空", ShareHub.incoming)
    }

    @Test
    fun newRequestResetsDoneState() {
        ShareHub.receiveState = ShareHub.ReceiveState.DONE
        ShareHub.lastReceiveFiles = 3
        ShareHub.lastReceiveBytes = 1024
        ShareHub.receivedBytes = 1024
        ShareHub.totalBytes = 1024

        // 新请求到达
        ShareHub.incomingRequestStarted()

        assertEquals(
            "新请求到达后不能停在完成态",
            ShareHub.ReceiveState.IDLE,
            ShareHub.receiveState
        )
        assertEquals(0L, ShareHub.totalBytes)
        assertEquals(0L, ShareHub.receivedBytes)
        assertTrue(ShareHub.lastSaved.isEmpty())
    }

    @Test
    fun newRequestKeepsRunningState() {
        // 正在接收时来了新请求，不该把进度清零（进度还要继续显示）
        ShareHub.receiveState = ShareHub.ReceiveState.RUNNING
        ShareHub.receivedBytes = 100
        ShareHub.totalBytes = 500

        ShareHub.incomingRequestStarted()

        assertEquals(ShareHub.ReceiveState.RUNNING, ShareHub.receiveState)
        assertEquals(100L, ShareHub.receivedBytes)
        assertEquals(500L, ShareHub.totalBytes)
    }
}
