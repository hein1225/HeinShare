package com.hishare.android

import com.hishare.android.databinding.ActivitySendBinding

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hishare.core.Discovery
import com.hishare.core.FileSource
import com.hishare.core.Identity
import com.hishare.core.ShareHub
import com.hishare.core.Sender
import java.io.File
import java.io.InputStream

/**
 * 发送页：展示进度、速度、剩余时间，可取消或转后台。
 *
 * 目标设备优先级：进入时指定的 peer > 当前在线设备里第一个可发送的。
 */
class SendActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding
    private val handler = Handler(Looper.getMainLooper())
    private var sender: Sender? = null
    private var startedAt = 0L
    private var lastSent = 0L
    private var lastAt = 0L
    private var totalBytes = 0L
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sendBack.setOnClickListener { finish() }
        binding.cancelButton.setOnClickListener {
            sender?.cancelled = true
            Toast.makeText(this, "已取消发送", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.backgroundButton.setOnClickListener {
            // 后台传输：保持服务存活，退到后台继续
            Toast.makeText(this, "已转入后台传输", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        val uris = intent.getStringArrayListExtra("uris") ?: ArrayList()
        val names = intent.getStringArrayListExtra("names") ?: ArrayList()
        val sizes = intent.getStringArrayListExtra("sizes") ?: ArrayList()

        val sources = ArrayList<UriSource>()
        for (i in uris.indices) {
            val uri = Uri.parse(uris[i])
            val name = names.getOrNull(i) ?: "file-${i + 1}"
            val size = sizes.getOrNull(i)?.toLongOrNull() ?: 0L
            sources.add(UriSource(this, uri, name, name, size))
        }
        totalBytes = sources.sumOf { it.size }

        binding.sendFileName.text = if (sources.size == 1) sources[0].name else "${sources.size} 个文件"
        binding.sendFileSize.text = Fmt.size(totalBytes)
        binding.sendFileIcon.setImageResource(
            if (sources.size == 1 && sources[0].name.endsWith(".mp4", true)) R.drawable.ic_video
            else R.drawable.ic_doc
        )

        val peer = resolvePeer()
        binding.sendTarget.text = getString(R.string.send_to, peer?.name ?: "未选择设备")

        if (peer == null) {
            binding.progressText.text = "—"
            binding.speedText.text = "没有可用的目标设备"
            binding.etaText.text = ""
            return
        }

        startSend(peer, sources)
    }

    private fun resolvePeer(): Discovery.Peer? {
        val host = intent.getStringExtra("host")
        if (host != null) {
            ShareHub.peers.firstOrNull { it.host == host && it.accepts }?.let { return it }
        }
        return ShareHub.peers.firstOrNull { it.accepts }
    }

    private fun startSend(peer: Discovery.Peer, sources: List<FileSource>) {
        startedAt = System.currentTimeMillis()
        lastAt = startedAt
        val identity = Identity(File(filesDir, "identity.pem"))
        val s = Sender(
            identity = identity,
            log = { msg -> runOnUiThread { binding.speedText.text = msg } },
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
                    binding.progressBar.progress = 100
                    binding.progressText.text = "完成"
                    binding.speedText.text = "已发送 ${report.files.size} 个文件 · ${Fmt.size(report.bytes)}"
                    binding.etaText.text = ""
                    ShareHub.log("发送完成：${report.files.size} 个文件，${report.bytes} 字节")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.speedText.text = "发送失败：${e.message}"
                    binding.etaText.text = ""
                    ShareHub.log("发送失败：${e.message}")
                }
            }
        }.start()
    }

    private fun onProgress(sent: Long, total: Long) {
        val pct = if (total > 0) (sent * 100 / total).toInt() else 0
        binding.progressBar.progress = pct
        binding.progressText.text = getString(R.string.send_progress, pct)

        val now = System.currentTimeMillis()
        val delta = sent - lastSent
        val dt = (now - lastAt) / 1000.0
        if (dt > 0.2) {
            val speed = delta / dt
            binding.speedText.text = "速度 ${Fmt.size(speed.toLong())}/s"
            if (speed > 0) {
                val remain = (total - sent) / speed
                binding.etaText.text = "预计 ${remain.toInt()} 秒"
            }
            lastSent = sent
            lastAt = now
        }
    }

}

/** 用 SAF 的 Uri 作为发送源。 */
class UriSource(
    private val context: Context,
    private val uri: Uri,
    override val relPath: String,
    override val name: String,
    override val size: Long
) : FileSource {
    override fun openInput(): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取文件：$name")
}
