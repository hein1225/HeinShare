package com.hishare.android

import android.content.Context
import com.hishare.core.Net

/**
 * 安卓端运行时偏好：设备名称、后台传输开关、接收目录。
 *
 * 用 SharedPreferences 存，key 全部带 `hishare.` 前缀。
 */
object Prefs {

    private const val FILE = "hishare_prefs"
    private const val KEY_NAME = "hishare.device_name"
    private const val KEY_BACKGROUND = "hishare.background_transfer"
    private const val KEY_SAVE_DIR = "hishare.save_dir"
    private const val KEY_ENABLED = "hishare.enabled"

    /** 落盘位置类型：private / downloads / custom。 */
    private const val KEY_SAVE_TARGET = "hishare.save_target"

    /** SAF 目录 Uri。 */
    private const val KEY_TREE_URI = "hishare.save_tree_uri"

    /** SAF 目录的可读名。 */
    private const val KEY_TREE_LABEL = "hishare.save_tree_label"

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.deviceName: String
        get() = sp(this).getString(KEY_NAME, "") ?: ""
        set(value) = sp(this).edit().putString(KEY_NAME, value).apply()

    var Context.backgroundTransfer: Boolean
        get() = sp(this).getBoolean(KEY_BACKGROUND, true)
        set(value) = sp(this).edit().putBoolean(KEY_BACKGROUND, value).apply()

    var Context.saveDir: String
        get() = sp(this).getString(KEY_SAVE_DIR, "HiShare") ?: "HiShare"
        set(value) = sp(this).edit().putString(KEY_SAVE_DIR, value).apply()

    var Context.enabled: Boolean
        get() = sp(this).getBoolean(KEY_ENABLED, true)
        set(value) = sp(this).edit().putBoolean(KEY_ENABLED, value).apply()

    /** 落盘位置类型。 */
    var Context.saveTarget: String
        get() = sp(this).getString(KEY_SAVE_TARGET, "private") ?: "private"
        set(value) = sp(this).edit().putString(KEY_SAVE_TARGET, value).apply()

    /** SAF 目录 Uri（空串表示未选择）。 */
    var Context.treeUri: String
        get() = sp(this).getString(KEY_TREE_URI, "") ?: ""
        set(value) = sp(this).edit().putString(KEY_TREE_URI, value).apply()

    /** SAF 目录的可读名。 */
    var Context.treeLabel: String
        get() = sp(this).getString(KEY_TREE_LABEL, "") ?: ""
        set(value) = sp(this).edit().putString(KEY_TREE_LABEL, value).apply()
}

/** 界面通用工具。 */
object Fmt {
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return if (i == 0) "${value.toInt()} ${units[i]}"
        else "%.1f %s".format(value, units[i])
    }

    fun localIp(): String = Net.localIPv4()
}
