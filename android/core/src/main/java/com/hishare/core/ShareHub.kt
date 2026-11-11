package com.hishare.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 全局共享状态：设备列表、日志、待确认的接收请求。
 *
 * App 层（Activity）通过它读状态，通过回调刷新界面。
 *
 * 回调是**多监听者**的：首页与接收页都会关心同一批状态变更，
 * 单个回调槽会让其中一个页面在另一个页面销毁时失联。
 */
object ShareHub {

    /** 待确认的接收请求。 */
    class Incoming(
        val peerName: String,
        val peer: String,
        val session: Receiver.Session,
        val control: Channel
    ) {
        private val latch = CountDownLatch(1)
        @Volatile var accepted: Boolean = false
            private set

        fun accept() {
            accepted = true
            latch.countDown()
        }

        fun reject() {
            accepted = false
            latch.countDown()
        }

        /** 等待用户选择，最多 timeoutMs 毫秒。 */
        fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    val peers = CopyOnWriteArrayList<Discovery.Peer>()
    val logs = CopyOnWriteArrayList<String>()

    @Volatile var incoming: Incoming? = null
    @Volatile var incomingPeerName: String = ""
    @Volatile var busy: Boolean = false

    /** 前台服务是否在跑（服务启停时更新）。 */
    @Volatile var serviceRunning: Boolean = false

    /** 传输进度。 */
    @Volatile var receivedBytes: Long = 0
    @Volatile var totalBytes: Long = 0
    @Volatile var sentBytes: Long = 0
    @Volatile var lastSaved: MutableList<String> = ArrayList()

    // ------------------------------------------------------------ 监听者

    private val peersListeners = CopyOnWriteArrayList<() -> Unit>()
    private val incomingListeners = CopyOnWriteArrayList<() -> Unit>()
    private val logListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val progressListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 注册设备列表变化监听，返回注销句柄。 */
    fun addPeersListener(l: () -> Unit): () -> Unit {
        peersListeners.add(l)
        return { peersListeners.remove(l) }
    }

    /** 注册接收请求变化监听，返回注销句柄。 */
    fun addIncomingListener(l: () -> Unit): () -> Unit {
        incomingListeners.add(l)
        return { incomingListeners.remove(l) }
    }

    /** 注册日志监听，返回注销句柄。 */
    fun addLogListener(l: (String) -> Unit): () -> Unit {
        logListeners.add(l)
        return { logListeners.remove(l) }
    }

    /** 注册进度监听，返回注销句柄。 */
    fun addProgressListener(l: () -> Unit): () -> Unit {
        progressListeners.add(l)
        return { progressListeners.remove(l) }
    }

    fun log(message: String) {
        val line = "[${System.currentTimeMillis() % 100000}] $message"
        logs.add(line)
        while (logs.size > 500) logs.removeAt(0)
        for (l in logListeners) {
            try { l(line) } catch (_: Exception) {}
        }
    }

    fun updatePeers(list: List<Discovery.Peer>) {
        peers.clear()
        peers.addAll(list)
        for (l in peersListeners) {
            try { l() } catch (_: Exception) {}
        }
    }

    fun progressChanged() {
        for (l in progressListeners) {
            try { l() } catch (_: Exception) {}
        }
    }

    /** 接收请求状态变化时调用。 */
    fun incomingChanged() {
        for (l in incomingListeners) {
            try { l() } catch (_: Exception) {}
        }
    }

    /** 设备名（用户可改）。 */
    @Volatile var displayName: String = "HiShare Android"
}
