package com.heinshare.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider

/**
 * 更新包的下载与安装。
 *
 * 用系统 DownloadManager 下载到公共「下载」目录：
 *   * 不需要自己写网络栈，也不需要存储权限（DownloadManager 走的是它自己的通道）；
 *   * 装的时候直接用 DownloadManager 给的 `content://` URI，
 *     不需要给应用私有目录开 FileProvider。
 */
object ApkDownloader {

    private const val DOWNLOAD_ID_KEY = "heinshare_update_download_id"

    /** 下载中的任务 id，-1 表示没有。 */
    @Volatile
    private var pendingId: Long = -1L

    /**
     * 下载 APK。
     *
     * @param onDone 下载完成回调（参数是安装用的 content:// URI）。
     * @param onFail 失败回调。
     */
    fun download(context: Context, url: String, fileName: String, onDone: (Uri) -> Unit, onFail: (String) -> Unit) {
        val app = context.applicationContext
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            request.setTitle("正在下载海因分享更新包")
            request.setDescription("下载完成后会提示安装")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            pendingId = id
            app.getSharedPreferences("heinshare_update", Context.MODE_PRIVATE)
                .edit().putLong(DOWNLOAD_ID_KEY, id).apply()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val doneId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (doneId != id) return
                    val status = manager.query(DownloadManager.Query().setFilterById(id))
                    val row = status?.use { if (it.moveToFirst()) it else null }
                    val code = row?.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ?: -1
                    val uri = manager.getUriForDownloadedFile(id)
                    if (code == DownloadManager.STATUS_SUCCESSFUL && uri != null) {
                        onDone(uri)
                    } else {
                        onFail("下载失败")
                    }
                    pendingId = -1L
                    try {
                        app.unregisterReceiver(this)
                    } catch (_: Exception) {}
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            onFail(e.message ?: "下载失败")
        }
    }

    /** 拉起系统安装界面。 */
    fun install(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    /**
     * 把内部保存的路径转成可分享的 content:// URI。
     *
     * 私有目录里的文件（应用私有 files / external-files）要用 FileProvider 才能给别的应用读。
     */
    fun shareableUri(context: Context, path: String): Uri? {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return null
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }
}
