package com.hishare.android

import com.hishare.android.databinding.ActivityMainBinding

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hishare.core.Discovery
import com.hishare.core.HiShareService
import com.hishare.core.ShareHub

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

    private var currentTab = TAB_HOME
    private var unregister: List<() -> Unit> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        loadDisplayName()

        deviceAdapter = DeviceAdapter { peer -> openPicker(peer) }
        binding.homePage.deviceList.layoutManager = LinearLayoutManager(this)
        binding.homePage.deviceList.adapter = deviceAdapter

        recordAdapter = RecordAdapter()
        binding.recordsPage.recordList.layoutManager = LinearLayoutManager(this)
        binding.recordsPage.recordList.adapter = recordAdapter

        binding.navHome.setOnClickListener { switchTab(TAB_HOME) }
        binding.navRecords.setOnClickListener { switchTab(TAB_RECORDS) }
        binding.navSettings.setOnClickListener { switchTab(TAB_SETTINGS) }

        binding.homePage.switchBox.setOnClickListener { toggleService() }
        binding.recordsPage.recordsSwitchBox.setOnClickListener { toggleService() }
        binding.settingsPage.settingsSwitchBox.setOnClickListener { toggleService() }

        binding.homePage.quickMedia.setOnClickListener { openPicker(null) }
        binding.homePage.quickDocs.setOnClickListener { openPicker(null) }

        binding.settingsPage.rowDeviceName.setOnClickListener { editDeviceName() }
        binding.settingsPage.rowBackgroundTransfer.setOnClickListener { toggleBackground() }
        binding.settingsPage.rowSaveDir.setOnClickListener { editSaveDir() }

        unregister = listOf(
            ShareHub.addPeersListener { runOnUiThread { refreshDevices() } },
            ShareHub.addProgressListener { runOnUiThread { refreshStatus() } },
            ShareHub.addIncomingListener { runOnUiThread { onIncomingChanged() } },
            ShareHub.addLogListener { line -> runOnUiThread { onLogLine(line) } }
        )

        switchTab(TAB_HOME)
        refreshDevices()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        refreshStatus()
        syncSwitchUi()
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
        val enabled = !isServiceOn()
        enabledPref = enabled
        if (enabled) {
            HiShareService.start(this)
            Toast.makeText(this, "快传已开启，正在广播本机", Toast.LENGTH_SHORT).show()
        } else {
            HiShareService.stop(this)
            Toast.makeText(this, "快传已关闭", Toast.LENGTH_SHORT).show()
        }
        syncSwitchUi()
        refreshStatus()
    }

    private var isServiceOnCache: Boolean? = null

    private fun isServiceOn(): Boolean {
        isServiceOnCache?.let { return it }
        // 以「服务是否在运行」为准：ShareHub.peers 有数据说明发现线程活着
        val on = ShareHub.peers.isNotEmpty() || enabledPref
        isServiceOnCache = on
        return on
    }

    private var enabledPref: Boolean
        get() = getSharedPreferences("hishare_prefs", MODE_PRIVATE).getBoolean("hishare.enabled", true)
        set(value) {
            getSharedPreferences("hishare_prefs", MODE_PRIVATE).edit().putBoolean("hishare.enabled", value).apply()
            isServiceOnCache = value
        }

    private fun syncSwitchUi() {
        val on = isServiceOn()
        setSwitch(binding.homePage.switchTrack, binding.homePage.switchKnob, on, 64, 30)
        setSwitch(binding.recordsPage.recordsSwitchTrack, binding.recordsPage.recordsSwitchKnob, on, 52, 24)
        setSwitch(binding.settingsPage.settingsSwitchTrack, binding.settingsPage.settingsSwitchKnob, on, 52, 24)
        binding.homePage.discoverablePill.text = getString(if (on) R.string.switch_state_on else R.string.switch_state_off)
        binding.recordsPage.recordsSwitchState.text =
            getString(if (on) R.string.switch_state_on else R.string.switch_state_off)
        val wifi = isWifiConnected()
        binding.homePage.wifiHint.visibility = if (wifi) View.GONE else View.VISIBLE
        binding.homePage.wifiHint.text = if (on) {
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
        val peers = ShareHub.peers
        deviceAdapter.submit(peers)
        binding.homePage.scanningText.text = if (peers.isEmpty()) {
            getString(R.string.scanning_empty)
        } else {
            getString(R.string.scanning)
        }
        binding.navRecordsDot.visibility =
            if (currentTab == TAB_RECORDS || !recordAdapter.hasUnread()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val ip = Fmt.localIp()
        val wifi = isWifiConnected()
        binding.settingsPage.connectionValue.text =
            if (wifi) "$ip · 已连接" else "$ip · 未连接 Wi-Fi"
        binding.settingsPage.connectionBadge.text = if (wifi) "在线" else "离线"
        binding.settingsPage.connectionBadge.setTextColor(
            ContextCompat.getColor(this, if (wifi) R.color.green_ok else R.color.ink_400)
        )
        binding.settingsPage.discoverValue.text = discoverStatusText()
        binding.settingsPage.deviceNameValue.text =
            ShareHub.displayName.ifBlank { getString(R.string.app_name) }
        binding.settingsPage.saveDirValue.text = saveTargetDesc()
    }

    /** 「可被发现」状态的精确描述。 */
    private fun discoverStatusText(): String {
        if (!isServiceOn()) return "快传未开启"
        if (!isWifiConnected()) return "未连接 Wi-Fi，无法被扫描"
        if (!ShareHub.serviceRunning) return "服务未运行"
        val n = ShareHub.peers.size
        return if (n == 0) "正在广播，等待华为设备扫描…" else "已应答扫描 · 共 $n 台设备"
    }

    /** 当前落盘位置的可读描述。 */
    private fun saveTargetDesc(): String = when (saveTargetPref) {
        "downloads" -> "系统「下载」/${saveDirPref}/"
        "custom" -> if (treeLabelPref.isNotBlank()) "$treeLabelPref/" else "所选目录/"
        else -> "应用私有目录/${saveDirPref}/"
    }

    /** 是否连着 Wi-Fi。 */
    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private var saveDirPref: String
        get() = getSharedPreferences("hishare_prefs", MODE_PRIVATE).getString("hishare.save_dir", "HiShare") ?: "HiShare"
        set(value) {
            getSharedPreferences("hishare_prefs", MODE_PRIVATE).edit().putString("hishare.save_dir", value).apply()
        }

    /** 落盘位置类型：private / downloads / custom。 */
    private var saveTargetPref: String
        get() = getSharedPreferences("hishare_prefs", MODE_PRIVATE).getString("hishare.save_target", "private") ?: "private"
        set(value) {
            getSharedPreferences("hishare_prefs", MODE_PRIVATE).edit().putString("hishare.save_target", value).apply()
        }

    /** SAF 目录 Uri。 */
    private var treeUriPref: String
        get() = getSharedPreferences("hishare_prefs", MODE_PRIVATE).getString("hishare.save_tree_uri", "") ?: ""
        set(value) {
            getSharedPreferences("hishare_prefs", MODE_PRIVATE).edit().putString("hishare.save_tree_uri", value).apply()
        }

    /** SAF 目录可读名。 */
    private var treeLabelPref: String
        get() = getSharedPreferences("hishare_prefs", MODE_PRIVATE).getString("hishare.save_tree_label", "") ?: ""
        set(value) {
            getSharedPreferences("hishare_prefs", MODE_PRIVATE).edit().putString("hishare.save_tree_label", value).apply()
        }

    // ------------------------------------------------------------ 记录

    private fun onLogLine(line: String) {
        val lower = line
        if (lower.contains("收到来自")) {
            recordAdapter.addIncoming(line.substringAfter("收到来自").trim())
        } else if (lower.contains("接收完成")) {
            recordAdapter.addDone(line.substringAfter("接收完成").trim(), incoming = false)
        } else if (lower.contains("已发出分享请求")) {
            recordAdapter.addSending(line.substringAfter("已发出分享请求").trim())
        }
    }

    // ------------------------------------------------------------ 接收请求

    private fun onIncomingChanged() {
        if (ShareHub.incoming != null) {
            startActivity(
                Intent(this, ReceiveActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    // ------------------------------------------------------------ 交互

    private fun openPicker(peer: Discovery.Peer?) {
        val intent = Intent(this, PickerActivity::class.java)
        peer?.let {
            intent.putExtra("host", it.host)
            intent.putExtra("port", it.port)
            intent.putExtra("name", it.name)
        }
        startActivity(intent)
    }

    /** 从偏好里恢复设备名（首次运行给个默认）。 */
    private fun loadDisplayName() {
        val pref = getSharedPreferences("hishare_prefs", MODE_PRIVATE)
        val saved = pref.getString("hishare.device_name", "") ?: ""
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
                    getSharedPreferences("hishare_prefs", MODE_PRIVATE)
                        .edit().putString("hishare.device_name", name).apply()
                    refreshStatus()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleBackground() {
        val ctx = this
        val pref = ctx.getSharedPreferences("hishare_prefs", MODE_PRIVATE)
        val newValue = !pref.getBoolean("hishare.background_transfer", true)
        pref.edit().putBoolean("hishare.background_transfer", newValue).apply()
        Toast.makeText(this, if (newValue) "退到后台继续发送" else "后台传输已关闭", Toast.LENGTH_SHORT).show()
    }

    private fun editSaveDir() {
        val options = arrayOf(
            "应用私有目录（无需权限）",
            "系统「下载」目录",
            "选择其他位置…"
        )
        val current = when (saveTargetPref) {
            "downloads" -> 1
            "custom" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("接收目录")
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> {
                        saveTargetPref = "private"
                        refreshStatus()
                        Toast.makeText(this, "已切换到应用私有目录", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (!requestLegacyWritePermissionIfNeeded()) {
                            Toast.makeText(this, "需要存储权限才能写入系统「下载」目录", Toast.LENGTH_SHORT).show()
                            return@setSingleChoiceItems
                        }
                        saveTargetPref = "downloads"
                        refreshStatus()
                        Toast.makeText(this, "已切换到系统「下载」目录", Toast.LENGTH_SHORT).show()
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

    @Deprecated("兼容旧版 Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
     * Android 9 及以下写系统「下载」目录需要存储写权限。
     * Android 10+ 用 MediaStore 写入，不需要任何权限，所以只在旧版本上申请。
     */
    private fun requestLegacyWritePermissionIfNeeded(): Boolean {
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        return false
    }

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_RECORDS = 1
        private const val TAB_SETTINGS = 2
        private const val REQ_TREE = 3001
    }
}
