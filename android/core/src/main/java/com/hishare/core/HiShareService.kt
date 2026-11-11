package com.hishare.core

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
class HiShareService : Service() {

    companion object {
        const val CHANNEL_ID = "hishare_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.hishare.android.START"
        const val ACTION_STOP = "com.hishare.android.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HiShareService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HiShareService::class.java))
        }
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discovery: DiscoverySocket? = null
    private var server: ServerSocket? = null
    private var identity: Identity? = null
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = false

    /**
     * 网络变化回调。
     *
     * 发现套接字是**绑定到具体本机 IP** 的（华为分享要求，见 port-spec/01-discovery.md A1）。
     * 一旦 Wi-Fi 换了网络、IP 变了，旧 socket 就收不到华为的 query —— 表现为「手机突然扫不到本机」。
     * 所以网络可用性/地址变化时必须重建发现与监听。
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        identity = Identity(File(filesDir, "identity.pem"))
        ShareHub.log("服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            ShareHub.serviceRunning = true
            startDiscovery()
            startListener()
            registerNetworkCallback()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ShareHub.serviceRunning = false
        unregisterNetworkCallback()
        try { discovery?.stop() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        ShareHub.log("服务已停止")
        super.onDestroy()
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
                "HiShare 分享服务",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持局域网发现与接收通道"
            manager?.createNotificationChannel(channel)
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("HiShare 正在监听")
            .setContentText("可与华为设备互传文件")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
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
                ShareHub.updatePeers(discovery?.peers?.values?.toList() ?: emptyList())
            },
            onLog = { ShareHub.log(it) }
        ).also { it.start() }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifi.createMulticastLock("hishare-multicast")
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

    /** 接收目录名（来自设置页，默认 HiShare）。 */
    private fun saveDirName(): String {
        val pref = getSharedPreferences("hishare_prefs", MODE_PRIVATE)
        val name = pref.getString("hishare.save_dir", "HiShare") ?: "HiShare"
        return name.ifBlank { "HiShare" }
    }

    /**
     * 落盘目标。
     *
     * `hishare.save_target` 取值：
     *   - `private`（默认）应用私有目录
     *   - `downloads`     系统「下载」目录
     *   - `custom`        用户通过系统目录选择器指定的目录（SAF）
     */
    private fun buildSaveTarget(): SaveTarget {
        val pref = getSharedPreferences("hishare_prefs", MODE_PRIVATE)
        when (pref.getString("hishare.save_target", "private")) {
            "downloads" -> {
                val target = DownloadsSaveTarget(this, saveDirName())
                if (target.writableLegacy()) return target
                ShareHub.log("系统「下载」目录不可写，改用应用私有目录")
            }
            "custom" -> {
                val uriStr = pref.getString("hishare.save_tree_uri", "") ?: ""
                if (uriStr.isNotBlank()) {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        val label = pref.getString("hishare.save_tree_label", "") ?: "所选目录"
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
        try {
            ShareHub.log("收到来自 $peer 的连接")
            val session = receiver.acceptConnection(control, true, 25000)
            val name = ShareHub.peers.firstOrNull { peer.startsWith(it.host) }?.name ?: peer
            val request = ShareHub.Incoming(name, peer, session, control)
            ShareHub.incoming = request
            ShareHub.incomingPeerName = name
            ShareHub.incomingChanged()
            ShareHub.log("等待确认来自 $name 的分享请求")
            val decided = request.await(60_000)
            ShareHub.incoming = null
            ShareHub.incomingChanged()
            if (!decided) {
                ShareHub.log("已拒绝该分享")
                control.close()
                return
            }
            ShareHub.busy = true
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
            ShareHub.lastSaved = result.saved.toMutableList()
        } catch (e: Exception) {
            ShareHub.log("接收失败：${e.message}")
        } finally {
            ShareHub.busy = false
            try { control.close() } catch (_: Exception) {}
            ShareHub.progressChanged()
        }
    }
}
