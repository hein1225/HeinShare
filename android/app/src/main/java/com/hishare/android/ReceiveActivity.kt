package com.hishare.android

import com.hishare.android.databinding.ActivityReceiveBinding

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.hishare.core.ShareHub

/**
 * 接收页：确认对端的分享请求，并显示接收进度。
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding
    private var unregister: List<() -> Unit> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recvBack.setOnClickListener { finish() }
        binding.acceptButton.setOnClickListener {
            ShareHub.incoming?.accept()
            binding.recvButtons.visibility = View.GONE
            binding.recvProgressPanel.visibility = View.VISIBLE
            binding.recvStatus.text = "已接受，正在接收…"
        }
        binding.rejectButton.setOnClickListener {
            ShareHub.incoming?.reject()
            finish()
        }

        unregister = listOf(
            ShareHub.addIncomingListener { runOnUiThread { refresh() } },
            ShareHub.addProgressListener { runOnUiThread { refreshProgress() } }
        )

        refresh()
        refreshProgress()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregister.forEach { it() }
        unregister = emptyList()
    }

    private fun refresh() {
        val request = ShareHub.incoming
        if (request == null) {
            binding.recvPrompt.text = "当前没有待确认的分享请求"
            binding.acceptButton.isEnabled = false
            binding.rejectButton.isEnabled = false
            return
        }
        val s = request.session
        binding.recvPrompt.text =
            "${request.peerName} 想发送 ${s.fileCount} 个文件（${Fmt.size(s.totalBytes)}）"
        binding.acceptButton.isEnabled = true
        binding.rejectButton.isEnabled = true
    }

    private fun refreshProgress() {
        val total = ShareHub.totalBytes
        val pct = if (total > 0) (ShareHub.receivedBytes * 100 / total).toInt() else 0
        binding.recvProgressBar.progress = pct
        binding.recvProgressText.text = "$pct%"
        val saved = ShareHub.lastSaved
        binding.recvStatus.text = if (saved.isEmpty()) {
            "已接收 ${Fmt.size(ShareHub.receivedBytes)}"
        } else {
            "已接收 ${Fmt.size(ShareHub.receivedBytes)}，共 ${saved.size} 个文件"
        }
    }
}
