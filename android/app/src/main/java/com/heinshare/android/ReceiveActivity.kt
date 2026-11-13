package com.heinshare.app

import com.heinshare.app.databinding.ActivityReceiveBinding

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.heinshare.core.ShareHub

/**
 * 接收页：确认对端的分享请求，并显示接收进度。
 *
 * 三种状态必须区分清楚，否则会出现「进度条 100% 但页面一直卡着」：
 *   IDLE    没有进行中的接收
 *   RUNNING 正在接收（显示进度条）
 *   DONE    已完成（进度条收尾 + 显示「完成」，页面停留几秒后自动关闭）
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding
    private var unregister: List<() -> Unit> = emptyList()

    /** 完成后的收尾动作只跑一次。 */
    private var finishScheduled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recvBack.setOnClickListener { finish() }
        binding.acceptButton.setOnClickListener {
            ShareHub.incoming?.accept()
            binding.recvButtons.visibility = View.GONE
            binding.recvProgressPanel.visibility = View.VISIBLE
            binding.recvStatus.text = getString(R.string.recv_accepting)
            binding.recvProgressBar.progress = 0
            binding.recvProgressText.text = "0%"
        }
        binding.rejectButton.setOnClickListener {
            ShareHub.incoming?.reject()
            finish()
        }
        // 完成后用户可以点这里直接走（不用等自动关闭）
        binding.recvDoneButton.setOnClickListener { finish() }

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
            if (ShareHub.receiveState == ShareHub.ReceiveState.DONE) {
                // 已经结束了：保留完成态，不要再把界面切回「没有待确认请求」
                showDone()
                return
            }
            binding.recvPrompt.text = getString(R.string.recv_no_request)
            binding.acceptButton.isEnabled = false
            binding.rejectButton.isEnabled = false
            binding.recvButtons.visibility = View.VISIBLE
            binding.recvProgressPanel.visibility = View.GONE
            return
        }
        val s = request.session
        binding.recvPrompt.text = getString(
            R.string.recv_prompt_fmt,
            request.peerName,
            s.fileCount,
            Fmt.size(s.totalBytes)
        )
        binding.acceptButton.isEnabled = true
        binding.rejectButton.isEnabled = true
        binding.recvButtons.visibility = View.VISIBLE
    }

    private fun refreshProgress() {
        val state = ShareHub.receiveState
        val total = ShareHub.totalBytes
        val received = ShareHub.receivedBytes
        val pct = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0

        when (state) {
            ShareHub.ReceiveState.RUNNING -> {
                binding.recvProgressPanel.visibility = View.VISIBLE
                binding.recvButtons.visibility = View.GONE
                binding.recvDoneButton.visibility = View.GONE
                binding.recvProgressBar.progress = pct
                binding.recvProgressText.text = "$pct%"
                binding.recvStatus.text =
                    getString(R.string.recv_progress_fmt, Fmt.size(received), Fmt.size(total))
            }

            ShareHub.ReceiveState.DONE -> showDone()

            ShareHub.ReceiveState.IDLE -> {
                // 没在接收：保留确认按钮区，进度面板藏起来
                binding.recvProgressPanel.visibility = View.GONE
                binding.recvDoneButton.visibility = View.GONE
                if (ShareHub.incoming != null) binding.recvButtons.visibility = View.VISIBLE
            }
        }
    }

    /** 完成态：进度拉满、状态文案改成「完成」、按钮换成「完成」。 */
    private fun showDone() {
        binding.recvProgressPanel.visibility = View.VISIBLE
        binding.recvButtons.visibility = View.GONE
        binding.recvProgressBar.progress = 100
        binding.recvProgressText.text = "100%"
        binding.recvPrompt.text = getString(
            R.string.recv_done_fmt,
            ShareHub.lastReceiveFiles,
            Fmt.size(ShareHub.lastReceiveBytes)
        )
        binding.recvStatus.text = getString(R.string.recv_done_status)
        binding.recvDoneButton.visibility = View.VISIBLE

        // 3 秒后自动关页（用户已经看到「完成」了，不需要手动点）
        if (!finishScheduled) {
            finishScheduled = true
            binding.root.postDelayed({ if (!isFinishing) finish() }, 2600)
        }
    }
}
