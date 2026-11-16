package com.heinshare.app

import com.heinshare.app.databinding.ActivityMainBinding

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.heinshare.core.Discovery
import com.heinshare.core.HeinshareService
import com.heinshare.core.Hotspot
import com.heinshare.core.ShareHub

/**
 * 主界面：首页 / 记录 / 设置 三页共用一个 Activity，用底部导航切换。
 *
 * 首页承担三件事：
 *   1. 开关「开启快传」→ 启停前台服务（服务里才有组播锁与 5684 端口监听）
 *   2. 展示附近设备（发现到的对端）
 *   3. 跳转到选文件页
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var recordAdapter: RecordAdapter
    private lateinit var nearbyBinder: ListBinder<Discovery.Peer>
    private lateinit var historyBinder: ListBinder<Discovery.Peer>

    private val handler = Handler(Looper.getMainLooper())

    /** 定期刷新在线/离线标记（在线状态靠「最近见过」推断，会过期）。 */
    private val onlineTicker = object : Runnable {
        override fun run() {
            refreshDevices()
            refreshHotspotUi()
            handler.postDelayed(this, ONLINE_TICK_MS)
        }
    }

    private var currentTab = TAB_HOME
    private var unregister: List<() -> Unit> = emptyList()

    /** 调系统文件选择器时要发给哪台设备。 */
    private var pendingPeer: Discovery.Peer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        ensureStorageAccess()
        loadDisplayName()
        // 已知设备（之前传输过的）从本地读出来。
        // 平时不扫描局域网，首页列表靠这份历史撑住。
        ShareHub.peerStore = com.heinshare.core.PrefsPeerStore(applicationContext)
        ShareHub.loadHistory()

        deviceAdapter = DeviceAdapter { peer -> onPeerClicked(peer) }
        // 列表直接渲染进 LinearLayout：RecyclerView 嵌在 ScrollView 里只能量出一屏，
        // 页面会滑不到底（横屏尤其明显）。
        nearbyBinder = ListBinder(
            binding.homePage.homeLists.deviceList, R.layout.item_device
        ) { view, peer -> deviceAdapter.bindNearby(view, peer) }
        historyBinder = ListBinder(
            binding.homePage.homeLists.historyList, R.layout.item_device
        ) { view, peer -> deviceAdapter.bindHistory(view, peer) }

        recordAdapter = RecordAdapter()
        recordAdapter.onChanged = { refreshRecords() }

        binding.navHome.setOnClickListener { switchTab(TAB_HOME) }
        binding.navRecords.setOnClickListener { switchTab(TAB_RECORDS) }
        binding.navSettings.setOnClickListener { switchTab(TAB_SETTINGS) }

        binding.homePage.homeHeader.switchBox.setOnClickListener { toggleService() }
        binding.recordsPage.recordsSwitchBox.setOnClickListener { toggleService() }
        binding.settingsPage.settingsSwitchBox.setOnClickListener { toggleService() }

        binding.homePage.homeLists.scanButton.setOnClickListener { manualScan() }

        binding.settingsPage.rowDeviceName.setOnClickListener { editDeviceName() }
        binding.settingsPage.rowBackgroundTransfer.setOnClickListener { toggleBackground() }
        binding.settingsPage.rowSaveDir.setOnClickListener { editSaveDir() }
        binding.settingsPage.rowCheckUpdate.setOnClickListener { manualCheckUpdate() }
        binding.settingsPage.rowRepo.setOnClickListener { openRepo() }
        binding.settingsPage.rowHeinPlay.setOnClickListener {
            showSisterAppDialog(
                "海因影视",
                "海因影视是本团队的影视播放与管理工具，支持本地片库整理、播放记录与多端访问。",
                "https://github.com/hein1225/HeinPlay",
                "hein1225/HeinPlay"
            )
        }
        binding.settingsPage.rowHeight4Kid.setOnClickListener {
            showSisterAppDialog(
                "海因成长助手",
                "海因成长助手用于记录孩子的成长过程，支持相册、身高体重与成长里程碑的持续留存。",
                "https://github.com/hein1225/Height4Kid",
                "hein1225/Height4Kid"
            )
        }

        // 已接收文件的历史（记录页点开就能用）
        ShareHub.receivedFileStore = com.heinshare.core.PrefsReceivedFileStore(applicationContext)
        ShareHub.loadReceivedFiles()

        unregister = listOf(
            ShareHub.addPeersListener { runOnUiThread { refreshDevices() } },
            ShareHub.addProgressListener { runOnUiThread { refreshStatus() } },
            ShareHub.addIncomingListener { runOnUiThread { onIncomingChanged() } },
            ShareHub.addLogListener { line -> runOnUiThread { onLogLine(line) } },
            ShareHub.addReceivedListener { runOnUiThread { refreshReceived() } }
        )

        switchTab(TAB_HOME)
        refreshDevices()
        refreshStatus()
        refreshReceived()
        handleShareIntent(intent)
        // 启动时自动检查更新：每天只查一次
        UpdateChecker.autoCheckIfNeeded(this) { result -> onUpdateResult(result, auto = true) }
    }

    /** 设备被点击：优先用「别的文件管理器分享过来」的文件，否则去系统选文件。 */
    private fun onPeerClicked(peer: Discovery.Peer) {
        if (sharedUris.isNotEmpty()) {
            val shared = sharedUris
            sharedUris = ArrayList()
            openConfirm(peer, shared)
        } else {
            openPicker(peer)
        }
    }

    /** 处理「被其它文件管理器选为分享目标」的入口。 */
    private fun handleShareIntent(data: Intent?) {
        val uris = readSharedUris(data)
        if (uris.isEmpty()) return
        sharedUris = uris
        Toast.makeText(
            this,
            getString(R.string.share_incoming, uris.size),
            Toast.LENGTH_LONG
        ).show()
        // 只有一台可用设备时直接发过去：优先用刚扫到的在线设备，
        // 没有在线设备才退回之前传输过的设备。
        val live = ShareHub.live.filter { it.accepts }
        if (live.size == 1) {
            openConfirm(live[0], uris)
            return
        }
        if (live.isEmpty()) {
            val known = ShareHub.history.filter { it.accepts }
            if (known.size == 1) {
                openConfirm(known[0], uris)
                return
            }
        }
        // 多台或没有 → 等用户在列表里点
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        refreshRecords()
        refreshStatus()
        refreshReceived()
        syncSwitchUi()
        // 在线状态是「最近三分钟内见过它」，会自己过期 —— 定时刷新一下标记
        handler.postDelayed(onlineTicker, ONLINE_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(onlineTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只注销自己注册的监听，不影响其它页面（接收页也在监听同一批状态）
        unregister.forEach { it() }
        unregister = emptyList()
    }

    // ------------------------------------------------------------ 导航

    private fun switchTab(tab: Int) {
        currentTab = tab
        binding.homePage.root.visibility = if (tab == TAB_HOME) View.VISIBLE else View.GONE
        binding.recordsPage.root.visibility = if (tab == TAB_RECORDS) View.VISIBLE else View.GONE
        binding.settingsPage.root.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE

        val homeOn = tab == TAB_HOME
        binding.navHomeIcon.setImageResource(if (homeOn) R.drawable.ic_tab_home else R.drawable.ic_tab_home_off)
        binding.navHomeText.setTextColor(ContextCompat.getColor(this, if (homeOn) R.color.blue_600 else R.color.ink_400))

        val recOn = tab == TAB_RECORDS
        binding.navRecordsIcon.setImageResource(if (recOn) R.drawable.ic_tab_records else R.drawable.ic_tab_records_off)
        binding.navRecordsText.setTextColor(ContextCompat.getColor(this, if (recOn) R.color.blue_600 else R.color.ink_400))

        val setOn = tab == TAB_SETTINGS
        binding.navSettingsIcon.setImageResource(if (setOn) R.drawable.ic_tab_settings else R.drawable.ic_tab_settings_off)
        binding.navSettingsText.setTextColor(ContextCompat.getColor(this, if (setOn) R.color.blue_600 else R.color.ink_400))

        binding.navRecordsDot.visibility = if (recOn || !recordAdapter.hasUnread()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ 开关 / 服务

    private fun toggleService() {
        // 【闪退修复】用「期望状态」取反，而不是用「服务当前是否在跑」：
        // 连续点开关时服务启停有延迟，按实际状态算会出现「点了没反应」甚至连点两次等于没点。
        val enabled = !HeinshareService.isDesiredOn(this)
        enabledPref = enabled
        if (enabled) {
            HeinshareService.start(this)
            Toast.makeText(this, "快传已开启，正在广播本机", Toast.LENGTH_SHORT).show()
        } else {
            HeinshareService.stop(this)
            Toast.makeText(this, "快传已关闭", Toast.LENGTH_SHORT).show()
        }
        syncSwitchUi()
        refreshStatus()
    }

    private fun isServiceOn(): Boolean {
        // 期望状态优先：它就是开关显示的东西。
        // 服务真的被系统杀掉时再退回实际状态。
        if (HeinshareService.isDesiredOn(this)) return true
        if (ShareHub.serviceRunning) return true
        return false
    }

    private var enabledPref: Boolean
        get() = getSharedPreferences("heinshare_prefs", MODE_PRIVATE).getBoolean("heinshare.enabled", true)
        set(value) {
            getSharedPreferences("heinshare_prefs", MODE_PRIVATE).edit().putBoolean("heinshare.enabled", value).apply()
        }

    private fun syncSwitchUi() {
        val on = isServiceOn()
        setSwitch(binding.homePage.homeHeader.switchTrack, binding.homePage.homeHeader.switchKnob, on, 64, 30)
        setSwitch(binding.recordsPage.recordsSwitchTrack, binding.recordsPage.recordsSwitchKnob, on, 52, 24)
        setSwitch(binding.settingsPage.settingsSwitchTrack, binding.settingsPage.settingsSwitchKnob, on, 52, 24)
        binding.homePage.homeHeader.discoverablePill.text = getString(if (on) R.string.switch_state_on else R.string.switch_state_off)
        binding.recordsPage.recordsSwitchState.text =
            getString(if (on) R.string.switch_state_on else R.string.switch_state_off)
        val wifi = isWifiConnected()
        binding.homePage.homeHeader.wifiHint.visibility = if (wifi) View.GONE else View.VISIBLE
        binding.homePage.homeHeader.wifiHint.text = if (on) {
            "未连接 Wi-Fi，华为分享无法扫描到本机"
        } else {
            "未连接 Wi-Fi，开关打开后也无法被扫描"
        }
    }

    /** 滑块靠右（开）/ 靠左（关）。用 translationX 平移，避免改布局。 */
    private fun setSwitch(track: View, knob: View, on: Boolean, widthDp: Int, knobDp: Int) {
        track.setBackgroundResource(if (on) R.drawable.bg_toggle_on else R.drawable.bg_toggle_off)
        val density = resources.displayMetrics.density
        val travel = (widthDp - knobDp - 6).toFloat() * density
        knob.translationX = if (on) 0f else -travel
    }

    // ------------------------------------------------------------ 设备列表

    private fun refreshDevices() {
        // 两张卡：附近设备（本次扫描扫到的）与连接历史（之前传输过的）。
        val live = ShareHub.live
        val history = ShareHub.history
        nearbyBinder.submit(live)
        historyBinder.submit(history)

        // 连接历史为空时给一句提示，别留个空卡片
        binding.homePage.homeLists.historyEmpty.visibility =
            if (history.isEmpty()) View.VISIBLE else View.GONE

        // 有设备就收起「正在扫描」空态，没有就显示雷达 + 提示
        val empty = live.isEmpty()
        binding.homePage.homeLists.scanningBox.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            binding.homePage.homeLists.scanningText.text = if (!isServiceOn()) {
                getString(R.string.scanning_off)
            } else if (!isWifiConnected()) {
                getString(R.string.scanning_no_wifi)
            } else {
                getString(R.string.scanning_idle)
            }
            binding.homePage.homeLists.scanningHint.text = if (!isServiceOn() || !isWifiConnected()) {
                getString(R.string.scanning_hint)
            } else {
                getString(R.string.scanning_idle_hint)
            }
        }
        binding.navRecordsDot.visibility =
            if (currentTab == TAB_RECORDS || !recordAdapter.hasUnread()) View.GONE else View.VISIBLE
    }

    /** 记录页刷新。 */
    private fun refreshRecords() {
        recordAdapter.submit(binding.recordsPage.recordList)
    }

    /**
     * 手动扫描。
     *
     * **平时不自动扫描**：设备列表默认只显示之前传输过的设备，
     * 用户点「扫描」才真的去局域网里找一遍。
     */
    private fun manualScan() {
        if (!isServiceOn() || !ShareHub.serviceRunning) {
            Toast.makeText(this, getString(R.string.scan_need_service), Toast.LENGTH_SHORT).show()
            return
        }
        if (!isWifiConnected()) {
            Toast.makeText(this, getString(R.string.scan_need_wifi), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = HeinshareService.requestScan(this)
        Toast.makeText(
            this,
            getString(if (ok) R.string.scan_started else R.string.scan_need_service),
            Toast.LENGTH_SHORT
        ).show()
        // 扫描窗口 6 秒，这期间设备会陆续出现在列表里
        binding.homePage.homeLists.scanButtonText.text = getString(R.string.scanning_short)
        binding.root.postDelayed({
            binding.homePage.homeLists.scanButtonText.text = getString(R.string.scan_now)
            refreshDevices()
            if (ShareHub.live.isEmpty()) {
                Toast.makeText(this, getString(R.string.scan_no_device), Toast.LENGTH_SHORT).show()
            }
        }, 4000)
    }

    // ------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val ip = Fmt.localIp()
        val wifi = isWifiConnected()
        val apOn = Hotspot.state == Hotspot.State.ON
        binding.settingsPage.connectionValue.text = when {
            apOn -> "$ip · 本机热点已开启"
            wifi -> "$ip · 已连接"
            else -> "$ip · 未连接 Wi-Fi"
        }
        binding.settingsPage.connectionBadge.text = if (wifi) "在线" else "离线"
        binding.settingsPage.connectionBadge.setTextColor(
            ContextCompat.getColor(this, if (wifi) R.color.green_ok else R.color.ink_400)
        )
        binding.settingsPage.discoverValue.text = discoverStatusText()
        binding.settingsPage.deviceNameValue.text =
            ShareHub.displayName.ifBlank { getString(R.string.app_name) }
        binding.settingsPage.saveDirValue.text = saveTargetDesc()
        binding.settingsPage.versionValue.text =
            "海因分享 ${UpdateChecker.currentVersion(this)}（兼容华为分享协议）"
        binding.settingsPage.repoUrl.text = "github.com/hein1225/HeinShare"
        refreshHotspotUi()
    }

    // ------------------------------------------------------------ 热点直连

    /**
     * 热点状态文案（只读）。
     *
     * 热点由用户在系统设置里自己开，本应用只负责检测：
     * 检测到本机热点已开，就把对外地址切到热点网段、发现包从热点网卡发出，
     * 这样对方连上本机热点后就能互相发现。
     */
    private fun refreshHotspotUi() {
        binding.settingsPage.hotspotValue.text = when (Hotspot.state) {
            Hotspot.State.ON -> getString(R.string.hotspot_state_on)
            else -> getString(R.string.hotspot_state_off)
        }
    }

    /** 「可被发现」状态的精确描述。 */
    private fun discoverStatusText(): String {
        if (!isServiceOn()) return "快传未开启"
        if (!isWifiConnected()) return "未连接 Wi-Fi，无法被扫描"
        if (!ShareHub.serviceRunning) return "服务未运行"
        val n = ShareHub.live.size
        return if (n == 0) "已开启广播 · 等待华为设备扫描" else "已扫描到 $n 台设备"
    }

    /** 当前落盘位置的可读描述。 */
    private fun saveTargetDesc(): String = when (saveTargetPref) {
        "downloads" -> getString(R.string.save_target_downloads, saveDirPref)
        "custom" -> if (treeLabelPref.isNotBlank()) "$treeLabelPref/" else getString(R.string.save_target_custom)
        else -> getString(R.string.save_target_private, saveDirPref)
    }

    /** 是否连着 Wi-Fi。开热点时本机就是 AP，仍算「有网」。 */
    private fun isWifiConnected(): Boolean {
        if (Hotspot.state == Hotspot.State.ON) return true
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private var saveDirPref: String
        get() = getSharedPreferences("heinshare_prefs", MODE_PRIVATE).getString("heinshare.save_dir", "Heinshare") ?: "Heinshare"
        set(value) {
            getSharedPreferences("heinshare_prefs", MODE_PRIVATE).edit().putString("heinshare.save_dir", value).apply()
        }

    /** 落盘位置类型：private / downloads / custom。 */
    private var saveTargetPref: String
        get() = getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
            .getString("heinshare.save_target", "downloads") ?: "downloads"
        set(value) {
            getSharedPreferences("heinshare_prefs", MODE_PRIVATE).edit().putString("heinshare.save_target", value).apply()
        }

    /** SAF 目录 Uri。 */
    private var treeUriPref: String
        get() = getSharedPreferences("heinshare_prefs", MODE_PRIVATE).getString("heinshare.save_tree_uri", "") ?: ""
        set(value) {
            getSharedPreferences("heinshare_prefs", MODE_PRIVATE).edit().putString("heinshare.save_tree_uri", value).apply()
        }

    /** SAF 目录可读名。 */
    private var treeLabelPref: String
        get() = getSharedPreferences("heinshare_prefs", MODE_PRIVATE).getString("heinshare.save_tree_label", "") ?: ""
        set(value) {
            getSharedPreferences("heinshare_prefs", MODE_PRIVATE).edit().putString("heinshare.save_tree_label", value).apply()
        }

    // ------------------------------------------------------------ 更新

    /** 设置页手动检查更新。 */
    private fun manualCheckUpdate() {
        binding.settingsPage.checkUpdateValue.text = "正在检查…"
        UpdateChecker.check(this) { result -> onUpdateResult(result, auto = false) }
    }

    /** 检查结果处理。自动检查只在真有新版本时才打扰用户。 */
    private fun onUpdateResult(result: UpdateChecker.Result, auto: Boolean) {
        when (result) {
            is UpdateChecker.Result.UpToDate -> {
                binding.settingsPage.checkUpdateValue.text = "已是最新版本（${UpdateChecker.currentVersion(this)}）"
                if (!auto) {
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
            is UpdateChecker.Result.Failed -> {
                binding.settingsPage.checkUpdateValue.text = "检查失败：${result.reason}"
                if (!auto) {
                    Toast.makeText(this, "检查更新失败：${result.reason}", Toast.LENGTH_LONG).show()
                }
            }
            is UpdateChecker.Result.Available -> {
                binding.settingsPage.checkUpdateValue.text = "发现新版本 ${result.version}"
                showUpdateDialog(result)
            }
        }
    }

    /** 提示有新版本。 */
    private fun showUpdateDialog(result: UpdateChecker.Result.Available) {
        val notes = result.notes?.trim()?.takeIf { it.isNotEmpty() }
        val message = buildString {
            append("发现新版本 ")
            append(result.version)
            append("\n当前版本：")
            append(UpdateChecker.currentVersion(this@MainActivity))
            if (notes != null) {
                append("\n\n更新说明：\n")
                append(notes.take(500))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("海因分享 有新版本")
            .setMessage(message)
            .setPositiveButton("下载安装") { _, _ ->
                val url = result.apkUrl
                if (url == null) {
                    // 没找到 apk，退到 Release 页面让用户自己下
                    openUrl(result.releaseUrl)
                } else {
                    val fileName = "HeinShare-${result.version}.apk"
                    Toast.makeText(this, "正在下载更新包…", Toast.LENGTH_SHORT).show()
                    ApkDownloader.download(
                        this, url, fileName,
                        onDone = { uri ->
                            runOnUiThread { ApkDownloader.install(this, uri) }
                        },
                        onFail = { msg ->
                            runOnUiThread {
                                Toast.makeText(this, "下载失败：$msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
            .setNeutralButton("查看发布页") { _, _ -> openUrl(result.releaseUrl) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 打开仓库主页（顺便求个 Star）。 */
    private fun openRepo() {
        openUrl(UpdateChecker.REPO_URL)
    }

    /**
     * 「海因软件分享」条目：展示简介与仓库地址，可一键跳转。
     *
     * 简介优先取该仓库在 GitHub 上的 About（仓库描述），取不到才用兜底文案；
     * 拿到真实描述后会刷新弹窗内容（先显示「正在读取…」）。
     */
    private fun showSisterAppDialog(name: String, intro: String, repo: String, slug: String) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("$intro\n\n仓库地址：\n$repo")
            .setPositiveButton("打开仓库") { _, _ -> openUrl(repo) }
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()

        GitHubRepoInfo.fetch(this, slug, intro) { info ->
            if (!dialog.isShowing) return@fetch
            val desc = info.description.ifBlank { intro }
            val stars = if (info.stars > 0) "\n\n★ ${info.stars}" else ""
            dialog.setMessage("$desc$stars\n\n仓库地址：\n$repo")
        }
    }

    /** 用系统浏览器打开一个网页。 */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ 已接收文件

    /** 记录页下半部分：已接收文件列表，点一下直接打开。 */
    private fun refreshReceived() {
        val files = ShareHub.received
        val container = binding.recordsPage.receivedList
        binding.recordsPage.receivedEmptyBox.visibility =
            if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.recordsPage.receivedEmpty.visibility =
            if (files.isEmpty()) View.VISIBLE else View.GONE

        val inflater = android.view.LayoutInflater.from(container.context)
        while (container.childCount > files.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < files.size) {
            container.addView(inflater.inflate(R.layout.item_received_file, container, false))
        }
        for (i in files.indices) {
            val file = files[i]
            val row = container.getChildAt(i)
            row.findViewById<TextView>(R.id.receivedName).text = file.name
            row.findViewById<TextView>(R.id.receivedSubtitle).text =
                "${Fmt.time(file.time)} · ${file.display}"
            row.setOnClickListener { openReceivedFile(file) }
        }
    }

    /** 打开一个已接收的文件。 */
    private fun openReceivedFile(file: com.heinshare.core.ReceivedFile) {
        val target = resolveOpenTarget(file)
        if (target == null) {
            Toast.makeText(this, "文件已不在原位置：${file.display}", Toast.LENGTH_LONG).show()
            return
        }
        // 依次尝试：真实类型 → 通配类型 → 交给用户自己选应用
        for (mime in listOf(target.mime, "*/*")) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(target.uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 带上 clipData，部分系统（华为等）只认 clipData 上的临时授权
            intent.clipData = android.content.ClipData.newRawUri("", target.uri)
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // 换下一种方式
            }
        }
        // 都没有能打开的：让用户自己挑一个应用
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(target.uri, target.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "选择应用打开"
        )
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(this, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 打开时用的目标：URI + 真实 MIME。 */
    private data class OpenTarget(val uri: Uri, val mime: String)

    /**
     * 把一条「已接收文件」记录换算成可打开的目标。
     *
     * 关键点：
     *   1. MIME 必须准确。落盘时如果文件名没有可识别的扩展名，会存成
     *      `application/octet-stream`，系统就会交给「文件管理」这类通用处理，
     *      结果点开变成「另存为」而不是直接打开。这里按真实文件名重新判定。
     *   2. 能定位到真实路径时改用 FileProvider 授权，第三方应用读起来最稳。
     */
    private fun resolveOpenTarget(file: com.heinshare.core.ReceivedFile): OpenTarget? {
        val target = file.target
        if (target.startsWith("content://")) {
            val uri = Uri.parse(target)
            var name = file.name
            var mime = ""
            var path: String? = null
            try {
                contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.MIME_TYPE,
                        android.provider.MediaStore.MediaColumns.DATA
                    ),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0) ?: name
                        if (!c.isNull(1)) mime = c.getString(1) ?: mime
                        if (!c.isNull(2)) path = c.getString(2)
                    }
                }
            } catch (_: Exception) {
                // 查不到就按记录里的信息来
            }
            val realMime = mime.ifBlank { mimeOfName(name) }
            // 有真实路径就换成 FileProvider
            val realPath = path ?: findInDownloads(name)
            if (realPath != null) {
                val fp = ApkDownloader.shareableUri(this, realPath)
                if (fp != null) return OpenTarget(fp, realMime)
            }
            return OpenTarget(uri, realMime)
        }

        // 绝对路径（应用私有目录 / 旧版公共下载目录）
        val f = java.io.File(target)
        if (!f.exists()) return null
        val fp = ApkDownloader.shareableUri(this, target)
        if (fp != null) return OpenTarget(fp, mimeOfName(f.name))
        // FileProvider 不覆盖的位置（SAF 之外的自定义目录）：按文件名去系统「下载」找
        val found = findInDownloads(f.name)
        if (found != null) {
            val fp2 = ApkDownloader.shareableUri(this, found)
            if (fp2 != null) return OpenTarget(fp2, mimeOfName(f.name))
        }
        return null
    }

    /** 在系统「下载」集合里按文件名找真实路径。找不到返回 null。 */
    private fun findInDownloads(name: String): String? {
        if (name.isBlank()) return null
        return try {
            contentResolver.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 按扩展名猜 MIME。跟协议层落盘时用的规则保持一致。 */
    private fun mimeOfName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "bmp" -> "image/bmp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "*/*"
        }
    }

    // ------------------------------------------------------------ 记录

    private fun onLogLine(line: String) {
        // 通知栏点「关闭」时服务会写这条日志：界面要立刻跟着变，
        // 否则用户回到 App 会看到开关还亮着、设备还在列表里。
        if (line.contains("服务已停止") || line.contains("服务已被用户关闭")) {
            syncSwitchUi()
            refreshDevices()
            refreshStatus()
        }
        // 只有真实发生的传输才进记录页。
        // 「收到来自 x.x.x.x 的连接」不算 —— 那是华为手机做的账号认证探测，
        // 我们的协议栈会直接忽略，用它建记录会把记录页刷满假条目。
        if (line.contains("等待确认来自")) {
            val name = line.substringAfter("等待确认来自").substringBefore("的分享请求").trim()
            recordAdapter.addIncoming(name)
        } else if (line.contains("接收完成")) {
            recordAdapter.resolveIncoming(
                line.substringAfter("接收完成：").trim().ifEmpty { "接收完成" },
                "已完成"
            )
        } else if (line.contains("已拒绝该分享")) {
            recordAdapter.resolveIncoming("对方已取消分享", "已取消")
        } else if (line.contains("接收失败")) {
            // 没有待确认请求时的「接收失败」是对端探测噪声，不进记录页
            if (recordAdapter.hasPendingIncoming()) {
                recordAdapter.resolveIncoming(line.substringAfter("接收失败：").trim(), "已失败")
            }
        } else if (line.contains("已发出分享请求")) {
            recordAdapter.addSending(line.substringAfter("已发出分享请求：").trim())
        } else if (line.contains("发送完成")) {
            recordAdapter.resolveSending(line.substringAfter("发送完成：").trim(), "已完成")
        } else if (line.contains("发送失败")) {
            recordAdapter.resolveSending(line.substringAfter("发送失败：").trim(), "已失败")
        }
    }

    // ------------------------------------------------------------ 接收请求

    private fun onIncomingChanged() {
        if (ShareHub.incoming == null) return
        // 页面正在退出或已经销毁时不能再拉起新页面，否则会抛 IllegalStateException 闪退。
        if (isFinishing || isDestroyed) return
        try {
            startActivity(
                Intent(this, ReceiveActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            ShareHub.log("拉起接收页失败：${e.message}")
        }
    }

    // ------------------------------------------------------------ 交互

    /**
     * 选文件 → 调系统默认的文件管理器（ACTION_OPEN_DOCUMENT），不再自建选文件页。
     *
     * 选完回到本 App，再进「待发送文件确认」页。
     */
    private fun openPicker(peer: Discovery.Peer?) {
        pendingPeer = peer
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_FILES)
        } catch (_: Exception) {
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从别的文件管理器分享过来的文件（ACTION_SEND / SEND_MULTIPLE），用过一次就清空。 */
    private var sharedUris: ArrayList<Uri> = ArrayList()

    private fun readSharedUris(data: Intent?): ArrayList<Uri> {
        val out = ArrayList<Uri>()
        if (data == null) return out
        if (data.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        } else if (data.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        return out
    }

    /** 收集系统选择器返回的 Uri（单个 / 多个都要）。 */
    private fun collectUris(data: Intent?): ArrayList<Uri> {
        val out = ArrayList<Uri>()
        data?.data?.let { out.add(it) }
        @Suppress("DEPRECATION")
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) out.add(clip.getItemAt(i).uri)
        }
        return out
    }

    /** 带文件进入「待发送文件确认」页。 */
    private fun openConfirm(peer: Discovery.Peer, uris: List<Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, SendActivity::class.java)
        intent.putStringArrayListExtra("uris", ArrayList(uris.map { it.toString() }))
        intent.putStringArrayListExtra(
            "names",
            ArrayList(uris.map { MediaStoreRepo.queryName(this, it) })
        )
        intent.putStringArrayListExtra(
            "sizes",
            ArrayList(uris.map { MediaStoreRepo.querySize(this, it).toString() })
        )
        intent.putExtra("host", peer.host)
        intent.putExtra("port", peer.port)
        intent.putExtra("name", peer.name)
        startActivity(intent)
    }

    /** 没有指定设备时（快捷入口），先让用户挑一台。 */
    private fun chooseDeviceThen(uris: List<Uri>) {
        val peers = ShareHub.peers.filter { it.accepts }
        if (peers.isEmpty()) {
            Toast.makeText(this, "附近没有可用设备，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("发送到")
            .setItems(peers.map { it.name }.toTypedArray()) { _, which ->
                openConfirm(peers[which], uris)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从偏好里恢复设备名（首次运行给个默认）。 */
    private fun loadDisplayName() {
        val pref = getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
        val saved = pref.getString("heinshare.device_name", "") ?: ""
        ShareHub.displayName = saved.ifBlank { getString(R.string.app_name) + " " + android.os.Build.MODEL }
    }

    private fun editDeviceName() {
        val input = EditText(this).apply {
            setText(ShareHub.displayName)
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("设备名称")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    ShareHub.displayName = name
                    getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
                        .edit().putString("heinshare.device_name", name).apply()
                    refreshStatus()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleBackground() {
        val ctx = this
        val pref = ctx.getSharedPreferences("heinshare_prefs", MODE_PRIVATE)
        val newValue = !pref.getBoolean("heinshare.background_transfer", true)
        pref.edit().putBoolean("heinshare.background_transfer", newValue).apply()
        Toast.makeText(this, if (newValue) "退到后台继续发送" else "后台传输已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun editSaveDir() {
        val options = arrayOf(
            getString(R.string.save_target_downloads_opt),
            getString(R.string.save_target_private_opt),
            getString(R.string.save_target_custom_opt)
        )
        val current = when (saveTargetPref) {
            "downloads" -> 0
            "custom" -> 2
            else -> 1
        }
        AlertDialog.Builder(this)
            .setTitle("接收目录")
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        if (!requestStoragePermissionIfNeeded()) {
                            Toast.makeText(
                                this,
                                getString(R.string.save_need_storage_permission),
                                Toast.LENGTH_LONG
                            ).show()
                            return@setSingleChoiceItems
                        }
                        saveTargetPref = "downloads"
                        refreshStatus()
                        Toast.makeText(
                            this,
                            getString(R.string.save_switched_downloads, saveDirPref),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> {
                        saveTargetPref = "private"
                        refreshStatus()
                        Toast.makeText(
                            this,
                            getString(R.string.save_switched_private),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> pickDirectory()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 调起系统目录选择器。 */
    private fun pickDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQ_TREE)
        } catch (e: Exception) {
            Toast.makeText(this, "系统未提供目录选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // 用户拒绝：退回私有目录，保证接收功能仍可用
            saveTargetPref = "private"
            refreshStatus()
            Toast.makeText(
                this,
                getString(R.string.save_need_storage_permission),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val target = com.heinshare.core.DownloadsSaveTarget(this, saveDirPref)
        if (!target.writableLegacy()) {
            saveTargetPref = "private"
            refreshStatus()
            Toast.makeText(this, getString(R.string.save_fallback_private), Toast.LENGTH_LONG).show()
            return
        }
        saveTargetPref = "downloads"
        refreshStatus()
        Toast.makeText(
            this,
            getString(R.string.save_switched_downloads, saveDirPref),
            Toast.LENGTH_SHORT
        ).show()
    }

    @Deprecated("兼容旧版 Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_FILES) {
            if (resultCode != RESULT_OK || data == null) return
            val uris = collectUris(data)
            if (uris.isEmpty()) return
            val peer = pendingPeer
            pendingPeer = null
            if (peer != null) {
                openConfirm(peer, uris)
            } else {
                chooseDeviceThen(uris)
            }
            return
        }

        if (requestCode != REQ_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        val label = queryTreeLabel(uri)
        treeUriPref = uri.toString()
        treeLabelPref = label
        saveTargetPref = "custom"
        refreshStatus()
        Toast.makeText(this, "已选择：$label", Toast.LENGTH_SHORT).show()
    }

    /** 取 SAF 目录的可读名。 */
    private fun queryTreeLabel(uri: Uri): String {
        return try {
            val doc = DocumentsContract.getTreeDocumentId(uri)
            val last = doc.substringAfterLast(':').substringAfterLast('/')
            if (last.isNotBlank()) last else "所选目录"
        } catch (_: Exception) {
            "所选目录"
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    /**
     * 首次进入时校验存储能力，并给出明确交代。
     *
     * 安卓的存储权限分三档，这里一次说清：
     *   * API 29+（Android 10 起）：写「下载」走 MediaStore，**不需要任何权限**；
     *     只有读别的 App 的媒体才要 READ_MEDIA_*，本 App 选文件用系统选择器也不需要。
     *   * API 26~28：写公共「下载」目录需要 WRITE_EXTERNAL_STORAGE。
     *   * 落盘位置默认是「下载/Heinshare」，不可写时自动回落到应用私有目录并提示。
     */
    private fun ensureStorageAccess() {
        if (saveTargetPref != "downloads") return
        if (!requestStoragePermissionIfNeeded()) return
        // 权限已有（或本就不需要），再实际探一次目录可写性
        val target = com.heinshare.core.DownloadsSaveTarget(this, saveDirPref)
        if (!target.writableLegacy()) {
            saveTargetPref = "private"
            refreshStatus()
            Toast.makeText(
                this,
                getString(R.string.save_fallback_private),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 申请写「下载」目录所需的权限。
     *
     * 返回 true 表示现在就能写（API 29+ 直接 true）；false 表示已发起申请，等回调。
     */
    private fun requestStoragePermissionIfNeeded(): Boolean {
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        return false
    }

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_RECORDS = 1
        private const val TAB_SETTINGS = 2
        private const val REQ_TREE = 3001
        private const val REQ_FILES = 3002
        private const val REQ_STORAGE = 3003
        private const val ONLINE_TICK_MS = 15000L
    }
}
