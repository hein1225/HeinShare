package com.heinshare.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 前台服务：保活 + 多播锁 + 设备发现 + 监听 5684 端口接收分享。
 */
class HeinshareService : Service() {

    companion object {
        const val CHANNEL_ID = "heinshare_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.heinshare.app.START"
        const val ACTION_STOP = "com.heinshare.app.STOP"

        private const val NOTIFY_TITLE = "Heinshare"
        private const val NOTIFY_TEXT = "正在监听 · 可与华为设备互传"

        /**
         * 用户主动点「关闭」后的进程级标记。
         *
         * 放在 companion 里而不是实例字段：Service 实例可能被销毁重建，
         * 实例字段会丢，用户就会发现「关了又自己活了」。
         * START_STICKY 在进程被杀后也会重建服务，必须靠这个标记挡住。
         */
        @Volatile private var userStopped = false

        /** 串行化 start/stop，避免连续点击时命令交叉。 */
        private val cmdLock = Any()

        /** 最近一次「启动」指令的时间，用来判断服务是否还在创建中。 */
        @Volatile private var lastStartAt = 0L

        /**
         * 用户的**期望**状态（开关打开=开）。
         *
         * 界面不能用「服务当前是否在跑」来翻转开关：连续点的时候服务启停有延迟，
         * 用实际状态算下一次点击会得到相反的结果（点了没反应 / 连点两次等于没点）。
         * 这里记录期望值，界面直接对它取反。
         */
        @Volatile private var desiredOn: Boolean? = null

        /** 期望状态；没设置过时退回偏好里的值。 */
        fun isDesiredOn(context: Context): Boolean {
            desiredOn?.let { return it }
            return try {
                context.getSharedPreferences("heinshare_prefs", Context.MODE_PRIVATE)
                    .getBoolean("heinshare.enabled", true)
            } catch (_: Exception) {
                true
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, HeinshareService::class.java).setAction(ACTION_START)
            synchronized(cmdLock) {
                desiredOn = true
                lastStartAt = System.currentTimeMillis()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    ShareHub.log("启动快传服务失败：${e.message}")
                }
            }
        }

        fun stop(context: Context) {
            // 先立「用户主动停止」标记，再停服务：即使系统稍后因 START_STICKY 想拉起，
            // onStartCommand 也会被标记挡住。
            userStopped = true
            desiredOn = false
            try {
                context.getSharedPreferences("heinshare_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("heinshare.enabled", false).apply()
            } catch (_: Exception) {}
            synchronized(cmdLock) {
                // 【闪退修复】不要在「服务刚启动、还没来得及 startForeground」时直接 stopService：
                // 服务是用 startForegroundService 起的，系统要求几秒内必须调用 startForeground，
                // 否则会抛 ForegroundServiceDidNotStartInTimeException 把进程杀掉。
                // 连续点开关时「刚启动就被停掉」正好命中这个窗口。
                // 所以改成发一个停止指令，让服务自己走「startForeground → 撤通知 → stopSelf」。
                val starting = System.currentTimeMillis() - lastStartAt < 5_000
                if (instance != null || starting) {
                    try {
                        context.startService(
                            Intent(context, HeinshareService::class.java).setAction(ACTION_STOP)
                        )
                        return
                    } catch (e: Exception) {
                        ShareHub.log("停止指令发送失败，改为直接停止：${e.message}")
                    }
                }
                try {
                    context.stopService(Intent(context, HeinshareService::class.java))
                } catch (e: Exception) {
                    ShareHub.log("停止快传服务失败：${e.message}")
                }
            }
        }

        /** 服务是否处于「用户主动关闭」状态（界面据此把开关置灰/关闭）。 */
        fun isUserStopped(): Boolean = userStopped

        /** 当前活着的服务实例（界面要触发扫描时用）。 */
        @Volatile private var instance: HeinshareService? = null

        /**
         * 手动触发一次扫描。
         *
         * 返回 false 表示服务没在跑 —— 调用方应提示用户先开启快传，
         * 而不是让用户对着一动不动的列表干等。
         */
        fun requestScan(context: Context): Boolean {
            val svc = instance ?: return false
            return try {
                svc.requestScan()
            } catch (_: Exception) {
                false
            }
        }
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discovery: DiscoverySocket? = null
    private var server: ServerSocket? = null
    private var identity: Identity? = null
    private val workers = Executors.newCachedThreadPool()

    /** 定时任务执行器（热点探测等）。 */
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false


    /**
     * 网络变化回调。
     *
     * 发现套接字是**绑定到具体本机 IP** 的（华为分享要求，见 port-spec/01-discovery.md A1）。
     * 一旦 Wi-Fi 换了网络、IP 变了，旧 socket 就收不到华为的 query —— 表现为「手机突然扫不到本机」。
     * 所以网络可用性/地址变化时必须重建发现与监听。
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 热点状态轮询任务。 */
    @Volatile private var hotspotWatcher: java.util.concurrent.ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = Identity(File(filesDir, "identity.pem"))
        // 已知设备（之前传输过的）从本地读出来：
        // 平时不扫描局域网，列表靠这份历史撑着。
        ShareHub.peerStore = PrefsPeerStore(applicationContext)
        ShareHub.loadHistory()
        // 热点开/关后本机 IP 与出口网卡都变了，发现套接字必须重建，
        // 否则发现包还从旧网卡发出去，对方扫不到。
        Hotspot.onChanged = { state ->
            ShareHub.log("热点状态变化：$state")
            if (running) rebuildTransport()
        }
        // 热点由用户在系统设置里手动开，这里定时检测一下：
        // 检测到热点已开就把本机地址切到热点网段，发现包从热点网卡发出。
        startHotspotWatcher()
        ShareHub.log("服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // 【闪退修复】必须先无条件 startForeground：
        // 用 startForegroundService 启动后，系统要求几秒内必须调用 startForeground，
        // 否则直接抛 ForegroundServiceDidNotStartInTimeException 把进程杀掉。
        // 用户连续点开关时，STOP/START 两个命令会挨着进来，
        // 旧写法在「准备停止」的分支里不调 startForeground，正好踩中这个坑。
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            ShareHub.log("前台通知启动失败：${e.message}")
        }

        // 通知栏里的「关闭」动作：彻底停掉服务，不留后台。
        if (action == ACTION_STOP) {
            ShareHub.log("收到关闭指令，停止服务")
            userStopped = true
            stopEverything()
            stopForegroundQuietly()
            stopSelf()
            return START_NOT_STICKY
        }
        // 显式点开关启动时清掉停止标记，让服务可以重新工作。
        if (action == ACTION_START) {
            userStopped = false
            desiredOn = true
        }

        // 用户点过「关闭」之后，系统重启服务（START_STICKY 的后遗症）一律不再拉起。
        if (userStopped) {
            ShareHub.log("服务已被用户关闭，忽略本次启动")
            stopForegroundQuietly()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            ShareHub.serviceRunning = true
            // 【闪退修复】发现套接字绑定 5684 端口，端口没释放干净时会抛
            // BindException；这在快速开关时很容易发生。异常必须在这里吃掉，
            // 否则会从 onStartCommand 一路抛到主线程，App 直接闪退。
            try {
                startDiscovery()
                startListener()
                registerNetworkCallback()
            } catch (e: Exception) {
                ShareHub.log("启动监听失败：${e.message}")
            }
        }
        return START_STICKY
    }

    /** 撤掉前台通知；服务已经不在前台时调用也不会抛。 */
    private fun stopForegroundQuietly() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopEverything()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 释放所有资源：发现套接字、监听端口、组播锁、正在跑的连接。
     *
     * 必须做到「关了就是关了」：监听 accept 线程靠 [running] 退出，
     * 已接受的连接也要断开，否则对端还会看到本机在线。
     */
    private fun stopEverything() {
        running = false
        ShareHub.serviceRunning = false
        unregisterNetworkCallback()
        try { discovery?.stop() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        discovery = null
        server = null
        ShareHub.clearLivePeers()
        ShareHub.incoming = null
        ShareHub.incomingChanged()
        // 通知栏点「关闭」后，把 App 里的开关也置成关，退出软件不会残留后台。
        // 但如果只是被系统回收（不是用户主动关），开关要保持原样，
        // 否则用户会发现「什么都没干，开关自己变关了」。
        if (userStopped) {
            desiredOn = false
            try {
                getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
                    .edit().putBoolean("heinshare.enabled", false).apply()
            } catch (_: Exception) {}
        }
        ShareHub.log("服务已停止")
    }

    /**
     * 监听网络可用性：Wi-Fi 断开/重连/换网络后重建发现套接字与 TCP 监听。
     *
     * 只对 Wi-Fi 生效 —— 华为分享只在 Wi-Fi 承载上做局域网发现。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ShareHub.log("网络已就绪，重建发现套接字")
                rebuildTransport()
            }

            override fun onLost(network: Network) {
                ShareHub.log("Wi-Fi 已断开")
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            ShareHub.log("注册网络监听失败：${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = networkCallback ?: return
        networkCallback = null
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    /**
     * 手动触发一次扫描（首页「重新扫描」按钮）。
     *
     * 返回 false 表示服务没在跑，调用方应提示用户先打开快传。
     */
    /**
     * 手动触发一次扫描。
     *
     * 返回 false 表示服务没在跑 —— 调用方应提示用户先开启快传，
     * 而不是让用户对着一动不动的列表干等。
     */
    /**
     * 手动触发一次扫描（只有用户点「扫描」才走这里）。
     *
     * 扫描是**有时间窗口**的：窗口内才主动查询局域网，
     * 窗口过了就恢复成只广播自己 —— 用户要求不自动扫描。
     */
    fun requestScan(): Boolean {
        val d = discovery ?: return false
        // 上一次扫描的结果作废：这次扫到什么就显示什么。
        ShareHub.clearLivePeers()
        d.requestScan()
        return true
    }

    /** 重建发现套接字与 TCP 监听（网络地址可能已变）。 */
    private fun rebuildTransport() {
        if (!running) return
        workers.execute {
            try { discovery?.stop() } catch (_: Exception) {}
            try { server?.close() } catch (_: Exception) {}
            try {
                startDiscovery()
                startListener()
            } catch (e: Exception) {
                ShareHub.log("重建监听失败：${e.message}")
            }
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Heinshare",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持局域网发现与接收通道"
            manager?.createNotificationChannel(channel)
        }
        // getLaunchIntentForPackage 偶尔会返回 null（比如桌面被清理后），
        // 直接丢给 PendingIntent.getActivity 会抛 NPE —— 在 onStartCommand 里抛就是闪退。
        // 拿不到就干脆不设 contentIntent，不影响通知本身。
        val pending = try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) null else PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Exception) {
            null
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // 通知栏常驻，并带一个「关闭」动作：点了就彻底停服务（不再留后台）。
        val stopPending = PendingIntent.getService(
            this, 1, Intent(this, HeinshareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = builder
            .setContentTitle(NOTIFY_TITLE)
            .setContentText(NOTIFY_TEXT)
            // 静态小图标：不用系统自带的「上传/进度」类图标，那类在状态栏会被当成动画
            .setSmallIcon(R.drawable.ic_notify)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "关闭",
                stopPending
            )
        if (pending != null) b.setContentIntent(pending)
        return b.build()
    }

    private fun startDiscovery() {
        val id = identity ?: return
        acquireMulticastLock()
        val localIp = Net.localIPv4()
        ShareHub.log("本机地址：$localIp")
        discovery = DiscoverySocket(
            localIp = localIp,
            tcpPort = 5684,
            identity = id.identityV2,
            deviceName = ShareHub.displayName,
            onPeer = { peer ->
                ShareHub.updateLivePeers(discovery?.peers?.values?.toList() ?: emptyList())
            },
            onLog = { ShareHub.log(it) },
            // 平时不扫描，但别人的包照样会来：见到谁就记下来，
            // 首页的「连接历史」靠它判断在线/离线。
            onSeen = { peer -> ShareHub.noteSeen(peer) }
        ).also { it.start() }
    }

    /**
     * 定时检测本机热点状态。
     *
     * Android 10 以后程序化开 SoftAP 基本开不起来，用户会去系统设置里手动开。
     * 这个探测让「手动开的热点」也能被识别：本机地址切到热点网段，
     * 发现包从热点网卡发出去，对方连上后就能互相发现。
     */
    private fun startHotspotWatcher() {
        hotspotWatcher?.cancel(false)
        val task = object : Runnable {
            override fun run() {
                if (!running) return
                try { Hotspot.detect(applicationContext) } catch (_: Exception) {}
                try {
                    scheduler.schedule(this, 3, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {}
            }
        }
        try {
            hotspotWatcher = scheduler.scheduleWithFixedDelay(
                task, 2, 3, java.util.concurrent.TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            ShareHub.log("热点探测启动失败：${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifi.createMulticastLock("heinshare-multicast")
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock
    }

    private fun startListener() {
        workers.execute {
            try {
                val s = ServerSocket(5684)
                server = s
                ShareHub.log("已监听 TCP 5684，等待对端连接")
                while (running) {
                    val socket: Socket = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (running) ShareHub.log("监听出错：${e.message}")
                        break
                    }
                    workers.execute { handleIncoming(socket) }
                }
            } catch (e: Exception) {
                ShareHub.log("监听失败：${e.message}")
            }
        }
    }

    /**
     * 对方连上 TCP 就说明它在线 —— 记一笔，首页的「连接历史」据此显示在线。
     *
     * 不用管连上来的是分享请求还是华为的账号认证探测：
     * 两种情况都证明这台设备此刻在局域网里，而且能连到本机。
     */
    private fun notePeerOnline(socket: Socket) {
        val host = try {
            socket.inetAddress?.hostAddress
        } catch (e: Exception) {
            null
        } ?: return
        if (host.isBlank()) return
        ShareHub.noteSeen(
            Discovery.Peer(
                id = host,
                host = host,
                port = Discovery.PORT,
                name = host,
                kind = "phone",
                accepts = true,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    /** 接收目录名（来自设置页，默认 Heinshare）。 */
    private fun saveDirName(): String {
        val pref = getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
        val name = pref.getString("heinshare.save_dir", "Heinshare") ?: "Heinshare"
        return name.ifBlank { "Heinshare" }
    }

    /**
     * 落盘目标。
     *
     * `heinshare.save_target` 取值：
     *   - `private`（默认）应用私有目录
     *   - `downloads`     系统「下载」目录
     *   - `custom`        用户通过系统目录选择器指定的目录（SAF）
     */
    private fun buildSaveTarget(): SaveTarget {
        val pref = getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
        // 默认落到系统「下载」/HiShare（用户可改成私有目录或自选目录）
        when (pref.getString("heinshare.save_target", "downloads")) {
            "downloads" -> {
                val target = DownloadsSaveTarget(this, saveDirName())
                if (target.writableLegacy()) return target
                ShareHub.log("系统「下载」目录不可写，改用应用私有目录")
            }
            "custom" -> {
                val uriStr = pref.getString("heinshare.save_tree_uri", "") ?: ""
                if (uriStr.isNotBlank()) {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        val label = pref.getString("heinshare.save_tree_label", "") ?: "所选目录"
                        return SafSaveTarget(this, uri, label)
                    } catch (e: Exception) {
                        ShareHub.log("所选目录失效，改用应用私有目录：${e.message}")
                    }
                }
            }
        }
        val dir = File(getExternalFilesDir(null) ?: filesDir, saveDirName())
        dir.mkdirs()
        return FileSaveTarget(dir)
    }

    private fun handleIncoming(socket: Socket) {
        val id = identity ?: return
        // 对方此刻就在线（不管它连上来是要分享还是华为的认证探测）
        notePeerOnline(socket)
        val control = Channel.fromSocket(socket, "control")
        val peer = socket.remoteSocketAddress?.toString()?.removePrefix("/") ?: "未知设备"
        val receiver = Receiver(
            identity = id,
            log = { ShareHub.log(it) },
            progress = { received, total ->
                ShareHub.receivedBytes = received
                ShareHub.totalBytes = total
                ShareHub.progressChanged()
            }
        )
        // 本次连接对应的待确认请求（认证探测连接没有请求，为 null）。
        // 放到 try 外面，finally 里才能按对象比对后精确清理。
        var request: ShareHub.Incoming? = null
        try {
            ShareHub.log("收到来自 $peer 的连接")
            val session = receiver.acceptConnection(control, true, 25000)
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "5684").toIntOrNull() ?: 5684
            // 设备名 / 设备 id：优先用发现阶段记住的对端（平时不扫描，靠这个记住的）
            val known = discovery?.seenPeerFor(host)
            val name = known?.name ?: peer
            val req = ShareHub.Incoming(name, peer, session, control)
            request = req
            ShareHub.incoming = req
            ShareHub.incomingPeerName = name
            // 新请求到达：把上一次的「已完成」态清掉，
            // 否则接收页还停在完成面板，新请求既没提示也点不了「接收」。
            ShareHub.incomingRequestStarted()
            ShareHub.incomingChanged()
            ShareHub.log("等待确认来自 $name 的分享请求")
            val decided = req.await(60_000)
            // 只清「还是这一个」的请求：华为的认证探测连接同时结束时，
            // 不能把正在等确认的请求顺手抹掉。
            ShareHub.clearIncoming(req)
            if (!decided) {
                ShareHub.log("已拒绝该分享")
                control.close()
                return
            }
            // 真的开始传了 → 记住这台设备，下次不用扫描也能在列表里看到它
            ShareHub.rememberPeer(
                Discovery.Peer(
                    // 设备 id 用来去重：知道就用它的 UDID，不知道就用名字
                    id = known?.id ?: name,
                    host = host,
                    port = port,
                    name = name,
                    kind = known?.kind ?: "phone",
                    accepts = known?.accepts ?: true,
                    lastSeen = System.currentTimeMillis()
                )
            )
            ShareHub.busy = true
            ShareHub.receiveStarted()
            val destination = buildSaveTarget()
            ShareHub.log("接收目录：${destination.describe()}")
            val result = receiver.runSession(
                control = control,
                session = session,
                destination = destination,
                displayName = ShareHub.displayName,
                timeoutMs = 600_000
            )
            ShareHub.log("接收完成：${result.saved.size} 个文件，${result.bytes} 字节")
            // 记进「已接收文件」历史，记录页可以直接点开
            ShareHub.noteReceivedFiles(result.saved)
            // 结束后把状态显式置成 DONE：进度条到 100% 但页面不退出，
            // 就是因为之前只更新了字节数、没有「结束」这个状态。
            ShareHub.receiveFinished(result.saved.size, result.bytes, result.saved)
            ShareHub.clearIncoming(request)
        } catch (e: Exception) {
            // 华为手机每 10 秒会连一次做账号认证探测，我们的协议栈会忽略那种帧。
            // 这种「连接」不是失败，日志和记录页都不该出现它 —— 用专门的异常类型区分。
            if (e is ProbeTimeoutException) {
                ShareHub.log("对方只是来认证的，不是分享请求（已忽略）")
            } else {
                ShareHub.log("接收失败：${e.message}")
                ShareHub.receiveFailed()
            }
        } finally {
            ShareHub.busy = false
            // 只清自己那一个请求：认证探测连接结束时不能把别的请求抹掉
            // （否则第二个文件收不到提示）。
            ShareHub.clearIncoming(request)
            try { control.close() } catch (_: Exception) {}
            ShareHub.incomingChanged()
            ShareHub.progressChanged()
        }
    }
}
