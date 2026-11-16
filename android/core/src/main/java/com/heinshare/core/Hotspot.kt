package com.heinshare.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager

/**
 * 本机热点（SoftAP）**状态检测**。
 *
 * 用途：让没有局域网的两台设备也能互传。
 *
 * 原理：手机开热点 → 对方连上这个热点 → 两台设备就在同一个 IP 网段
 * （Android 默认 192.168.43.x / 192.168.42.x），现有的 UDP 5684 组播/广播
 * 发现协议**零改动**就能用。
 *
 * 与华为的差别：华为走 HarmonyOS 分布式软总线（BLE 引导 + WLAN P2P 直连），
 * 不需要中间热点；本项目只做到「本机当热点」这一层。
 *
 * 设计取舍：**本模块不开热点、不关热点**，只负责「看」。热点由用户在系统
 * 设置里自己开。原因是 Android 10 以后程序化开启 SoftAP 基本上做不到：
 *
 *   - 官方 [WifiManager.startLocalOnlyHotspot] 需要 API 30（Android 11），
 *     且只能开「本地热点」：SSID 固定 AndroidAP、密码系统随机、不广播 SSID；
 *   - 隐藏 API `setWifiApEnabled` 在 Android 10+ 被非 SDK 接口限制
 *     **静默无效**（不抛异常、什么也不发生）。
 *
 * 所以真正的价值在这里：用户手动开了热点后，本模块能识别出热点地址，
 * 让本机对外地址切到热点网段、发现包从热点网卡发出去。
 */
object Hotspot {

    /** 热点状态。 */
    enum class State {
        /** 未开启。 */
        OFF,
        /** 已开启（由用户在系统设置里手动开启，本模块检测到）。 */
        ON
    }

    @Volatile
    var state: State = State.OFF
        private set

    /** 状态变化回调（服务用它重建发现套接字）。 */
    @Volatile
    var onChanged: ((State) -> Unit)? = null

    // ------------------------------------------------------------ 对外接口

    /**
     * 检测系统热点是否已开（用户在系统设置里手动开的情况）。
     *
     * 服务每隔几秒调一次：检测到热点地址就切到 [State.ON]，
     * 这样手动开热点也能被本应用正确使用。
     */
    @SuppressLint("MissingPermission")
    fun detect(context: Context) {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val up = apEnabledBySystem(wifi) || Net.hotspotIPv4() != null
        if (up) {
            if (state != State.ON) {
                state = State.ON
                ShareHub.log("检测到本机热点已开启")
                notifyChanged()
            }
        } else if (state == State.ON) {
            // 热点被关掉了
            state = State.OFF
            notifyChanged()
        }
    }

    // ------------------------------------------------------------ 内部实现

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

    private fun notifyChanged() {
        // 同步给 Net：本机地址要不要优先取热点地址，取决于这个。
        Net.hotspotActive = state == State.ON
        try { onChanged?.invoke(state) } catch (_: Exception) {}
    }
}
