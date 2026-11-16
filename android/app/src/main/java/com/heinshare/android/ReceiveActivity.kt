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

    /** 自动关闭的定时任务：新请求到达时要撤销，否则第二个请求的提示刚出现页面就关了。 */
    private var autoFinish: Runnable? = null

    /** 当前页面对应的待确认请求，用来判断「是不是换了一个新请求」。 */
    private var currentRequest: ShareHub.Incoming? = null

    /** 滑动窗口速率计：实时显示当前接收速率。 */
    private val speedMeter = SpeedMeter()

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
            binding.recvDoneIllus.visibility = View.GONE
            binding.recvPeerIcon.visibility = View.VISIBLE
            return
        }
        // 换成了一个新的请求（同一台设备发的第二个文件）：
        // 撤销上一次的自动关闭，并把界面切回「待确认」，
        // 否则用户只会看到上一个文件接收完成的弹窗。
        if (currentRequest !== request) {
            val isNew = currentRequest != null
            currentRequest = request
            cancelAutoFinish()
            finishScheduled = false
            if (isNew) {
                binding.recvProgressPanel.visibility = View.GONE
                binding.recvDoneButton.visibility = View.GONE
                binding.recvDoneIllus.visibility = View.GONE
                binding.recvPeerIcon.visibility = View.VISIBLE
                binding.recvProgressBar.progress = 0
                binding.recvProgressText.text = "0%"
            }
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
                binding.recvDoneButton.visibility = View.GONE
                binding.recvProgressBar.progress = pct
                binding.recvProgressText.text = "$pct%"
                binding.recvStatus.text =
                    getString(R.string.recv_progress_fmt, Fmt.size(received), Fmt.size(total))
                // 实时速度：滑动窗口 3 秒，读写两端都能实时看到当前速率
                val speed = speedMeter.update(received)
                binding.recvSpeedText.visibility = if (speed > 0) View.VISIBLE else View.GONE
                if (speed > 0) {
                    binding.recvSpeedText.text = "速度 ${Fmt.size(speed)}/s"
                }
                // 同时来了一个新的待确认请求：别把按钮藏掉，
                // 否则用户看不到「接收/拒绝」。
                if (ShareHub.incoming != null) binding.recvButtons.visibility = View.VISIBLE
            }

            ShareHub.ReceiveState.DONE ->
                // 还有待确认的新请求时不要显示完成态：
                // 那会把「接收/拒绝」按钮藏掉，用户以为没收到。
                if (ShareHub.incoming == null) showDone()

            ShareHub.ReceiveState.IDLE -> {
                // 没在接收：保留确认按钮区，进度面板藏起来
                binding.recvProgressPanel.visibility = View.GONE
                binding.recvDoneButton.visibility = View.GONE
                binding.recvSpeedText.visibility = View.GONE
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
        // 完成态：换成「已完成」插画，藏掉对端手机图标
        binding.recvDoneIllus.visibility = View.VISIBLE
        binding.recvPeerIcon.visibility = View.GONE

        // 3 秒后自动关页（用户已经看到「完成」了，不需要手动点）
        if (!finishScheduled) {
            finishScheduled = true
            val task = Runnable { if (!isFinishing) finish() }
            autoFinish = task
            binding.root.postDelayed(task, 2600)
        }
    }

    /** 撤销自动关闭：新的分享请求到达时用。 */
    private fun cancelAutoFinish() {
        val task = autoFinish ?: return
        autoFinish = null
        binding.root.removeCallbacks(task)
    }
}
