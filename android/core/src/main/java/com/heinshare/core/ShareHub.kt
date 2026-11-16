package com.heinshare.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * 展示用的设备列表 = 历史设备 ∪ 本次扫描到的设备。
     *
     * 用户要求：不自动扫描，平时只显示之前传输过的设备；
     * 手动扫描时把扫到的设备并进来（[livePeers]）。
     */
    val peers = CopyOnWriteArrayList<Discovery.Peer>()

    /** 之前传输过的设备（持久化）。 */
    private val historyPeers = CopyOnWriteArrayList<Discovery.Peer>()

    /** 最近一次扫描扫到的设备（不持久化）。 */
    private val livePeers = CopyOnWriteArrayList<Discovery.Peer>()

    /** 最近一次见到某台设备的时间（host → 毫秒），用于判断在线/离线。 */
    private val seenAt = ConcurrentHashMap<String, Long>()

    /** 已经判定为「在线」的对端（host → true），用来只在状态翻转时通知界面。 */
    private val onlineHosts = ConcurrentHashMap<String, Boolean>()

    /** 附近设备列表：本次扫描扫到的设备。 */
    val live: List<Discovery.Peer>
        get() = livePeers.toList()

    /** 连接历史：之前传输过的设备。 */
    val history: List<Discovery.Peer>
        get() = historyPeers.toList()

    /**
     * 在线判定的有效期。
     *
     * 平时不扫描，没有心跳可依赖，只能靠「最近多久见过它」来近似：
     * 三分钟内见过就算在线，超过就算离线。
     */
    const val ONLINE_WINDOW_MS = 3 * 60 * 1000L

    /**
     * 见到对端时调用（收到它的发现包，或它主动来连我们）。
     *
     * 与扫描窗口无关 —— 平时不扫描，但别人的包照样会来，
     * 靠它把「在线/离线」这个状态做出来。
     */
    fun noteSeen(peer: Discovery.Peer) {
        if (peer.host.isBlank()) return
        val now = System.currentTimeMillis()
        seenAt[peer.host] = now
        // 只在「离线 → 在线」这一瞬间通知界面，剩下的一直在线不必反复刷
        if (onlineHosts[peer.host] != true) {
            onlineHosts[peer.host] = true
            for (l in peersListeners) {
                try { l() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 这台设备现在是不是在线。
     *
     * 判据：最近 [ONLINE_WINDOW_MS] 内见过它，或者它就在本次扫描结果里。
     */
    fun isOnline(peer: Discovery.Peer): Boolean {
        if (peer.host.isBlank()) return false
        val t = seenAt[peer.host] ?: 0L
        if (t > 0 && System.currentTimeMillis() - t < ONLINE_WINDOW_MS) return true
        return livePeers.any { it.host == peer.host }
    }

    /** 已知设备的落盘器（App 层注入）。 */
    @Volatile var peerStore: PeerStore? = null

    /** 已接收文件的落盘器（App 层注入）。 */
    @Volatile var receivedFileStore: ReceivedFileStore? = null

    /** 历史接收文件（最新的在前），记录页点开就能用。 */
    private val receivedFiles = CopyOnWriteArrayList<ReceivedFile>()

    /** 读历史接收文件。 */
    val received: List<ReceivedFile>
        get() = receivedFiles.toList()

    private val receivedListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 注册「已接收文件变化」监听。 */
    fun addReceivedListener(l: () -> Unit): () -> Unit {
        receivedListeners.add(l)
        return { receivedListeners.remove(l) }
    }

    /** 载入历史接收文件（App 启动时调）。 */
    fun loadReceivedFiles() {
        val store = receivedFileStore ?: return
        val loaded = try {
            store.load()
        } catch (e: Exception) {
            emptyList()
        }
        if (loaded.isEmpty()) return
        receivedFiles.clear()
        receivedFiles.addAll(loaded)
    }

    /** 记下一批刚接收到的文件。 */
    fun noteReceivedFiles(saved: List<SavedFile>) {
        if (saved.isEmpty()) return
        val now = System.currentTimeMillis()
        val entries = saved.map {
            ReceivedFile(it.name, it.target, it.display, it.mime, now)
        }
        receivedFiles.addAll(0, entries)
        while (receivedFiles.size > 200) receivedFiles.removeAt(receivedFiles.size - 1)
        val store = receivedFileStore
        if (store != null) {
            try {
                store.save(receivedFiles.toList())
            } catch (e: Exception) {
            }
        }
        for (l in receivedListeners) {
            try { l() } catch (_: Exception) {}
        }
    }

    val logs = CopyOnWriteArrayList<String>()

    @Volatile var incoming: Incoming? = null
    @Volatile var incomingPeerName: String = ""
    @Volatile var busy: Boolean = false

    /**
     * 清掉待确认请求 —— **只在还是这一个请求时才清**。
     *
     * 华为手机每隔几秒就会连一次做账号认证探测，那条连接结束时也会走到这里。
     * 如果无条件清空，正在等用户确认的分享请求就会被探测连接顺手抹掉，
     * 表现为「第二个文件收不到提示」。所以必须按请求对象比对。
     */
    fun clearIncoming(request: Incoming?) {
        if (request == null) return
        if (incoming !== request) return
        incoming = null
        incomingChanged()
    }

    /**
     * 新的分享请求到达。
     *
     * 要把上一次的「已完成」态清掉 —— 否则界面还停在完成面板，
     * 新请求既没有提示，按钮也被藏起来。
     */
    fun incomingRequestStarted() {
        if (receiveState == ReceiveState.DONE) {
            receiveState = ReceiveState.IDLE
            receivedBytes = 0
            totalBytes = 0
            lastSaved = ArrayList()
            progressChanged()
        }
    }

    /** 前台服务是否在跑（服务启停时更新）。 */
    @Volatile var serviceRunning: Boolean = false

    /** 传输进度。 */
    @Volatile var receivedBytes: Long = 0
    @Volatile var totalBytes: Long = 0
    @Volatile var sentBytes: Long = 0
    @Volatile var lastSaved: MutableList<SavedFile> = ArrayList()

    /**
     * 接收状态机。
     *
     * 界面必须能区分「传完了」和「还在传」，否则进度条到了 100% 但页面一直不走，
     * 看起来像卡死。三态：IDLE 没在接收 / RUNNING 正在接收 / DONE 已完成。
     */
    enum class ReceiveState { IDLE, RUNNING, DONE }

    @Volatile var receiveState: ReceiveState = ReceiveState.IDLE

    /** 上一次接收的结果摘要（DONE 时给界面用）。 */
    @Volatile var lastReceiveFiles: Int = 0
    @Volatile var lastReceiveBytes: Long = 0

    fun receiveStarted() {
        receiveState = ReceiveState.RUNNING
        receivedBytes = 0
        totalBytes = 0
        lastSaved = ArrayList()
        progressChanged()
    }

    fun receiveFinished(files: Int, bytes: Long, savedPaths: List<SavedFile>) {
        receiveState = ReceiveState.DONE
        lastReceiveFiles = files
        lastReceiveBytes = bytes
        receivedBytes = bytes
        totalBytes = if (bytes > 0) bytes else totalBytes
        lastSaved = savedPaths.toMutableList()
        progressChanged()
    }

    fun receiveFailed() {
        receiveState = ReceiveState.IDLE
        progressChanged()
    }

    fun receiveReset() {
        receiveState = ReceiveState.IDLE
        receivedBytes = 0
        totalBytes = 0
        progressChanged()
    }

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
        // 同时写进 logcat，真机上排查收发问题直接抓 `adb logcat -s Heinshare`
        try {
            android.util.Log.i("Heinshare", line)
        } catch (_: Throwable) {
            // 单元测试里 android.util.Log 不可用，忽略即可
        }
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

    /**
     * 更新「本次扫描到的设备」。
     *
     * 只影响列表里的临时部分：历史设备（之前传输过的）始终保留。
     */
    fun updateLivePeers(list: List<Discovery.Peer>) {
        livePeers.clear()
        livePeers.addAll(list)
        rebuildPeers()
    }

    /** 清掉扫描结果（服务停了 / 又开始新一轮扫描）。 */
    fun clearLivePeers() {
        if (livePeers.isEmpty()) return
        livePeers.clear()
        rebuildPeers()
    }

    /**
     * 记住一台设备（传输过之后调用），下次不用扫描也能看到它。
     *
     * 去重键有两个：设备 id 和 IP。
     * 只按 id 去重不够 —— 接收方向我们可能拿不到对端的 UDID（只能用名字当 id），
     * 而发送方向拿到的是 UDID，两边对不上就会把同一台设备记成两条。
     */
    fun rememberPeer(peer: Discovery.Peer) {
        val merged = peer.copy(
            fromHistory = true,
            lastSeen = if (peer.lastSeen > 0) peer.lastSeen else System.currentTimeMillis()
        )
        val existing = historyPeers.indexOfFirst {
            it.id == peer.id || (peer.host.isNotBlank() && it.host == peer.host)
        }
        if (existing >= 0) historyPeers[existing] = merged else historyPeers.add(merged)
        // 历史按最近使用排序，列表里最上面就是刚用过的
        val sorted = historyPeers.sortedByDescending { it.lastSeen }
        historyPeers.clear()
        historyPeers.addAll(sorted)
        saveHistory()
        rebuildPeers()
    }

    /** 从落盘器载入历史设备。 */
    fun loadHistory() {
        val store = peerStore ?: return
        val loaded = try {
            store.load()
        } catch (e: Exception) {
            emptyList()
        }
        if (loaded.isEmpty()) return
        historyPeers.clear()
        historyPeers.addAll(loaded.sortedByDescending { it.lastSeen })
        rebuildPeers()
    }

    private fun saveHistory() {
        val store = peerStore ?: return
        try {
            store.save(historyPeers.toList())
        } catch (e: Exception) {
        }
    }

    /**
     * 合并历史与扫描结果。
     *
     * 同 id 以扫描结果为准（它的地址、能力都是最新的），
     * 历史设备标上 [Discovery.Peer.fromHistory]，界面据此区分。
     */
    private fun rebuildPeers() {
        val merged = LinkedHashMap<String, Discovery.Peer>()
        for (p in historyPeers) merged[p.id] = p.copy(fromHistory = true)
        for (p in livePeers) merged[p.id] = p.copy(fromHistory = false)
        val list = merged.values.sortedByDescending { it.lastSeen }
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
    @Volatile var displayName: String = "海因分享"
}
