package com.heinshare.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 本机热点（SoftAP）管理：让没有局域网的两台设备也能互传。
 *
 * 原理：手机开热点 → 对方连上这个热点 → 两台设备就在同一个 IP 网段
 * （Android 默认 192.168.43.x / 192.168.42.x），现有的 UDP 5684 组播/广播
 * 发现协议**零改动**就能用。
 *
 * 与华为的差别：华为走 HarmonyOS 分布式软总线（BLE 引导 + WLAN P2P 直连），
 * 不需要中间热点；本项目只做到「本机当热点」这一层。
 *
 * 现实约束：Android 10 以后，程序化开启 SoftAP 需要系统签名权限或厂商开放，
 * 普通应用调 [WifiManager.startLocalOnlyHotspot] 会被拒；隐藏 API
 * `setWifiApEnabled` 在新系统上会**静默无效**。所以这里的策略是：
 *
 *   1. 先试隐藏 API 指定 SSID（Android 9 及以下多数 ROM 可用）；
 *   2. 再试官方本地热点（Android 11+，SSID 固定 AndroidAP、密码随机）；
 *   3. 都不行 → **不假装成功**，改为「自动检测系统热点」：
 *      用户自己在系统设置里开了热点，本模块照样能识别出热点地址并正常工作，
 *      只是提示语变成「请在系统设置中开启热点」。
 */
object Hotspot {

    /** 热点状态。 */
    enum class State {
        /** 未开启。 */
        OFF,
        /** 正在开启（系统回调还没回来）。 */
        STARTING,
        /** 已开启。 */
        ON,
        /** 开启失败（不支持或被系统拒绝）。 */
        FAILED
    }

    /** 期望的热点名（对方要连的就是它）。 */
    const val SSID = "Heinshare"

    @Volatile
    var state: State = State.OFF
        private set

    /** 最近一次失败原因（给界面显示）。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 当前生效的热点名。 */
    @Volatile
    var activeSsid: String = SSID
        private set

    /** 是否由用户在系统设置里手动开启（程序化开不起来时的兜底路径）。 */
    @Volatile
    var manualMode: Boolean = false
        private set

    /** 状态变化回调（服务用它重建发现套接字）。 */
    @Volatile
    var onChanged: ((State) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 开启/检测时的探测任务。 */
    private var probe: Runnable? = null

    /** 官方本地热点的句柄（用于关闭）。 */
    private var reservation: Any? = null

    @Volatile
    private var currentContext: Context? = null

    // ------------------------------------------------------------ 对外接口

    /**
     * 开启热点。
     *
     * @param onResult true=已开启，false=失败（原因见 [lastError]）
     */
    @SuppressLint("MissingPermission")
    fun start(context: Context, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        currentContext = app
        manualMode = false
        if (state == State.ON) {
            onResult(true)
            return
        }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            fail("无法获取 Wi-Fi 服务")
            onResult(false)
            return
        }
        state = State.STARTING
        lastError = ""
        notifyChanged()

        // 路径 1：隐藏 API 指定 SSID
        legacyEnabled(wifi, true)
        awaitHotspotUp(wifi, onResult) {
            // 路径 2：官方本地热点（Android 11+）
            startLocalOnly(wifi, onResult)
        }
    }

    /** 关闭热点。 */
    @SuppressLint("MissingPermission")
    fun stop(context: Context, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        probe?.let { handler.removeCallbacks(it) }
        probe = null
        if (wifi == null) {
            onResult(false)
            return
        }
        // 官方本地热点：用 reservation.close() 关
        try {
            val r = reservation
            reservation = null
            if (r != null) {
                val m = r.javaClass.getMethod("close")
                m.invoke(r)
            }
        } catch (e: Exception) {
            ShareHub.log("关闭本地热点失败：${e.message}")
        }
        // 隐藏 API 关热点（用户手动开的那种关不了，只能提示）
        legacyEnabled(wifi, false)
        state = State.OFF
        activeSsid = SSID
        manualMode = false
        notifyChanged()
        onResult(true)
    }

    /**
     * 检测系统热点是否已开（用户手动在系统设置里开的情况）。
     *
     * 服务每隔几秒调一次：检测到热点地址就切到 [State.ON]，
     * 这样即使程序化开不起来，手动开热点也能被本应用正确使用。
     */
    @SuppressLint("MissingPermission")
    fun detect(context: Context) {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val up = apEnabledBySystem(wifi) || Net.hotspotIPv4() != null
        if (up) {
            if (state != State.ON) {
                state = State.ON
                activeSsid = SSID
                lastError = ""
                if (!manualMode) {
                    manualMode = true
                    ShareHub.log("检测到本机热点已开启")
                }
                notifyChanged()
            }
        } else if (state == State.ON && manualMode) {
            // 手动开的热点被关掉了
            state = State.OFF
            activeSsid = SSID
            manualMode = false
            notifyChanged()
        }
    }

    /** 本机是否支持程序化开热点。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // ------------------------------------------------------------ 内部实现

    /** 官方本地热点（Android 11+）。SSID 固定 AndroidAP，密码系统随机生成。 */
    @SuppressLint("MissingPermission")
    private fun startLocalOnly(wifi: WifiManager, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fail("本机系统（Android ${Build.VERSION.RELEASE}）不支持程序化开启热点，请手动开启")
            onResult(false)
            return
        }
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        state = State.ON
                        activeSsid = "AndroidAP"
                        lastError = ""
                        ShareHub.log("本机热点已开启（系统本地热点，名称 AndroidAP）")
                        notifyChanged()
                        onResult(true)
                    }

                    override fun onFailed(reason: Int) {
                        fail("系统拒绝开启热点（code=$reason），请在系统设置里手动开启热点")
                        onResult(false)
                    }
                },
                handler
            )
        } catch (e: SecurityException) {
            fail("缺少权限，无法开启热点")
            onResult(false)
        } catch (e: Exception) {
            fail("开启热点失败：${e.message}")
            onResult(false)
        }
    }

    /**
     * 轮询等待热点起来（最多约 3 秒）。
     *
     * 判据两个：系统报告 AP 已开，或热点地址已经出现在网卡上。
     * 华为/小米等 ROM 热点起来后网卡名仍是 wlan0（不是 ap0），
     * 只认网卡名会漏判，所以以地址网段为主、系统状态为辅。
     */
    private fun awaitHotspotUp(wifi: WifiManager, onResult: (Boolean) -> Unit, onTimeout: () -> Unit) {
        var waited = 0
        val task = object : Runnable {
            override fun run() {
                val up = apEnabledBySystem(wifi) || Net.hotspotIPv4() != null
                if (up) {
                    state = State.ON
                    activeSsid = SSID
                    lastError = ""
                    ShareHub.log("本机热点已开启（名称 $SSID）")
                    notifyChanged()
                    onResult(true)
                    return
                }
                waited += 300
                if (waited >= 3000) {
                    onTimeout()
                    return
                }
                handler.postDelayed(this, 300)
            }
        }
        probe = task
        handler.postDelayed(task, 600)
    }

    /**
     * 系统是否报告「AP 已开启」。
     *
     * `getWifiApState()` 不是公开 API，只能反射；
     * WIFI_AP_STATE_ENABLED == 13。反射失败返回 false（只是判不出来，不代表没开）。
     */
    @SuppressLint("MissingPermission")
    fun apEnabledBySystem(wifi: WifiManager?): Boolean {
        if (wifi == null) return false
        try {
            val m = WifiManager::class.java.getMethod("getWifiApState")
            val s = m.invoke(wifi) as? Int ?: return false
            if (s == 13) return true
        } catch (_: Exception) {}
        return try {
            val f = WifiManager::class.java.getDeclaredField("mWifiApState")
            f.isAccessible = true
            (f.get(wifi) as? Int) == 13
        } catch (_: Exception) {
            false
        }
    }

    private fun fail(message: String) {
        state = State.FAILED
        lastError = message
        ShareHub.log(message)
        notifyChanged()
    }

    private fun notifyChanged() {
        // 同步给 Net：本机地址要不要优先取热点地址，取决于这个。
        Net.hotspotActive = state == State.ON
        try { onChanged?.invoke(state) } catch (_: Exception) {}
    }

    /**
     * 反射调用 `WifiManager.setWifiApEnabled(config, enabled)`。
     *
     * Android 10+ 上非 SDK 接口限制会让这个调用**静默无效**（不抛异常），
     * 所以调用方必须另外确认热点是不是真的起来了。
     */
    @Suppress("DEPRECATION")
    private fun legacyEnabled(wifi: WifiManager, enabled: Boolean = true): Boolean {
        return try {
            val configClass = Class.forName("android.net.wifi.WifiConfiguration")
            try {
                val ssidField = configClass.getDeclaredField("SSID")
                ssidField.isAccessible = true
                ssidField.set(configClass, "\"$SSID\"")
            } catch (_: Exception) {}
            val config = configClass.getDeclaredConstructor().newInstance()
            val method = WifiManager::class.java.getMethod(
                "setWifiApEnabled", configClass, java.lang.Boolean.TYPE
            )
            method.invoke(wifi, config, enabled)
            ShareHub.log(if (enabled) "已请求开启本机热点（名称 $SSID）" else "已请求关闭本机热点")
            true
        } catch (e: Exception) {
            if (enabled) {
                ShareHub.log("指定名称开启热点失败：${e.cause?.message ?: e.message}")
            } else {
                ShareHub.log("关闭热点失败：${e.message}")
            }
            false
        }
    }
}
