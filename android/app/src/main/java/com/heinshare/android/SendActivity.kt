package com.heinshare.app

import com.heinshare.app.databinding.ActivitySendBinding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.heinshare.core.Discovery
import com.heinshare.core.FileSource
import com.heinshare.core.Identity
import com.heinshare.core.ShareHub
import com.heinshare.core.Sender
import java.io.File
import java.io.InputStream

/**
 * 发送页：先确认「要发哪些文件、发给谁」，确认后才真正开始传。
 *
 * 文件来自系统文件选择器（ACTION_OPEN_DOCUMENT）或别的文件管理器分享过来的 Uri。
 * 目标设备：进入时指定的 peer > 当前在线设备里第一个可发送的。
 */
class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding
    private lateinit var fileAdapter: SendFileAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var sender: Sender? = null
    private var startedAt = 0L
    /** 第一个数据字节到达的时刻，用来算平均速度。 */
    private var firstByteAt = 0L
    private var totalBytes = 0L
    private var done = false
    private var sending = false
    private var peer: Discovery.Peer? = null
    private var hasProgressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uris = intent.getStringArrayListExtra("uris") ?: ArrayList()
        val names = intent.getStringArrayListExtra("names") ?: ArrayList()
        val sizes = intent.getStringArrayListExtra("sizes") ?: ArrayList()

        val sources = ArrayList<UriSource>()
        for (i in uris.indices) {
            val uri = Uri.parse(uris[i])
            val name = names.getOrNull(i) ?: MediaStoreRepo.queryName(this, uri)
            val size = sizes.getOrNull(i)?.toLongOrNull() ?: MediaStoreRepo.querySize(this, uri)
            sources.add(UriSource(this, uri, name, name, size))
        }
        totalBytes = sources.sumOf { it.size }

        fileAdapter = SendFileAdapter(this, sources)
        fileAdapter.submit(binding.sendFileList)
        binding.sendFileSummary.text = if (sources.isEmpty()) {
            "没有选择文件"
        } else {
            "共 ${sources.size} 个文件 · ${Fmt.size(totalBytes)}"
        }

        binding.sendBack.setOnClickListener {
            if (sending) {
                sender?.cancelled = true
                Toast.makeText(this, "已取消发送", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
        binding.cancelButton.setOnClickListener {
            if (sending) {
                sender?.cancelled = true
                Toast.makeText(this, "已取消发送", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                finish()
            }
        }
        binding.backgroundButton.setOnClickListener {
            when {
                sending -> {
                    // 传输中：转入后台继续
                    Toast.makeText(this, getString(R.string.send_to_background), Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                }
                done -> finish()
                else -> startTransfer()
            }
        }

        peer = resolvePeer()
        binding.sendTarget.text = getString(R.string.send_to, peer?.name ?: "未选择设备")

        if (sources.isEmpty()) {
            Toast.makeText(this, "没有选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (peer == null) {
            binding.progressText.text = "—"
            binding.speedText.text = "没有可用的目标设备"
            binding.etaText.text = ""
            binding.backgroundButton.isEnabled = false
        }
    }

    private fun resolvePeer(): Discovery.Peer? {
        val host = intent.getStringExtra("host")
        if (host != null) {
            // 指定了设备就必须精确匹配：匹配不上就报错，绝不悄悄发给别的设备。
            // 历史设备的 IP 可能已经变了，所以放宽「能否接收」这一条：
            // 只要地址对得上就用，失败时再提示重新扫描。
            val candidates = ShareHub.peers.filter { it.host == host }
            return candidates.firstOrNull { it.accepts } ?: candidates.firstOrNull()
        }
        val name = intent.getStringExtra("name")
        if (name != null) {
            return ShareHub.peers.firstOrNull { it.name == name && it.accepts }
        }
        return ShareHub.peers.firstOrNull { it.accepts }
    }

    private fun startTransfer() {
        val target = peer ?: run {
            Toast.makeText(this, "没有可用的目标设备", Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        binding.progressPanel.visibility = View.VISIBLE
        binding.backgroundButton.setText(R.string.send_background)
        binding.cancelButton.setText(R.string.send_cancel)
        startSend(target, fileAdapter.sources)
    }

    private fun startSend(peer: Discovery.Peer, sources: List<FileSource>) {
        startedAt = System.currentTimeMillis()
        firstByteAt = 0L
        val identity = Identity(File(filesDir, "identity.pem"))
        val s = Sender(
            identity = identity,
            // 协议日志只进 logcat / 记录页，不能直接盖掉速度显示 ——
            // 否则用户看到的就是一行行「预览通道…」「文件通道…」，像出错的样子。
            log = { msg ->
                ShareHub.log(msg)
                runOnUiThread { if (!hasProgressed) binding.speedText.text = msg }
            },
            progress = { sent, total ->
                runOnUiThread { onProgress(sent, total) }
            }
        )
        sender = s
        Thread {
            try {
                val report = s.send(peer.host, peer.port, sources, ShareHub.displayName)
                runOnUiThread {
                    done = true
                    sending = false
                    binding.progressBar.progress = 100
                    binding.progressText.text = "100%"
                    binding.speedText.text =
                        getString(R.string.send_done_fmt, report.files.size, Fmt.size(report.bytes))
                    binding.etaText.text = ""
                    binding.backgroundButton.setText(R.string.send_done_button)
                    binding.backgroundButton.isEnabled = true
                    binding.cancelButton.setText(R.string.send_close)
                    ShareHub.log("发送完成：${report.files.size} 个文件，${report.bytes} 字节")
                    // 传过了 → 记住这台设备，下次不用扫描也能在列表里看到它
                    ShareHub.rememberPeer(peer)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    sending = false
                    binding.progressText.text = "—"
                    // 历史设备的 IP 可能已经变了：这时要提示重新扫描，而不是只报连接失败
                    val msg = if (peer.fromHistory) {
                        "设备地址可能已变化，请重新扫描后再试（${e.message ?: ""}）"
                    } else {
                        e.message ?: ""
                    }
                    binding.speedText.text = getString(R.string.send_failed_fmt, msg)
                    binding.etaText.text = getString(R.string.send_failed_hint)
                    binding.backgroundButton.setText(R.string.send_retry)
                    binding.backgroundButton.isEnabled = true
                    binding.cancelButton.setText(R.string.send_close)
                    ShareHub.log("发送失败：${e.message}")
                }
            }
        }.start()
    }

    private fun onProgress(sent: Long, total: Long) {
        hasProgressed = true
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        binding.progressBar.progress = pct
        binding.progressText.text = getString(R.string.send_progress, pct)

        // 用「从第一个字节到现在的平均速度」：
        // 按最近一次刷新的瞬时速度算，抖动很大（一个数据块 64KB，几个毫秒就走完），
        // 界面上会一会儿几 MB/s 一会儿几十 KB/s，看着像坏了。
        val now = System.currentTimeMillis()
        if (firstByteAt == 0L) firstByteAt = now
        val dt = (now - firstByteAt) / 1000.0
        if (dt > 0.3) {
            val speed = sent / dt
            binding.speedText.text = "速度 ${Fmt.size(speed.toLong())}/s"
            if (speed > 0 && total > sent) {
                val remain = (total - sent) / speed
                binding.etaText.text = "预计 ${remain.toInt()} 秒"
            }
        }
    }

}

/** 用 SAF 的 Uri 作为发送源。 */
class UriSource(
    private val context: Context,
    val uri: Uri,
    override val relPath: String,
    override val name: String,
    override val size: Long
) : FileSource {
    override fun openInput(): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取文件：$name")
}

/**
 * 待发送文件列表：显示真实图标（图片/视频取缩略图，其余按 MIME 选图标）。
 */
class SendFileAdapter(
    private val context: Context,
    val sources: List<UriSource>
) {

    /**
     * 把待发送文件渲染到容器里。
     *
     * 容器是 ScrollView 里的 LinearLayout，不是 RecyclerView：
     * RecyclerView 嵌在 ScrollView 里只能量出一屏高度，页面滑不到底。
     */
    fun submit(container: LinearLayout) {
        val inflater = LayoutInflater.from(container.context)
        while (container.childCount > sources.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < sources.size) {
            container.addView(inflater.inflate(R.layout.item_send_file, container, false))
        }
        for (i in sources.indices) {
            val src = sources[i]
            val row = container.getChildAt(i)
            row.findViewById<TextView>(R.id.fileName).text = src.name
            row.findViewById<TextView>(R.id.fileSize).text = Fmt.size(src.size)
            val icon = row.findViewById<ImageView>(R.id.fileIcon)
            icon.setImageResource(iconFor(src))
            loadThumbnail(icon, src)
        }
    }

    /** 按 MIME / 后缀挑一个合适的图标。 */
    private fun iconFor(src: UriSource): Int {
        val type = MediaStoreRepo.queryMime(context, src.uri, src.name)
        return when {
            type.startsWith("image/") -> R.drawable.ic_image
            type.startsWith("video/") -> R.drawable.ic_video
            type.startsWith("audio/") -> R.drawable.ic_share
            else -> R.drawable.ic_doc
        }
    }

    /**
     * 图片与视频取真实缩略图（后台线程解码，回主线程刷新）。
     *
     * 缩略图加载是 ContentResolver 的同步调用，放到子线程里做，避免卡住列表滚动。
     */
    private fun loadThumbnail(icon: ImageView, src: UriSource) {
        val type = MediaStoreRepo.queryMime(context, src.uri, src.name)
        val wantThumb = type.startsWith("image/") || type.startsWith("video/")
        if (!wantThumb) return
        Thread {
            val bmp: Bitmap? = try {
                context.contentResolver.loadThumbnail(src.uri, Size(128, 128), null)
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                icon.post {
                    try {
                        icon.setImageBitmap(bmp)
                    } catch (_: Exception) {
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
