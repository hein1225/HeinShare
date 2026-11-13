package com.heinshare.app

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.heinshare.core.Discovery
import com.heinshare.core.ShareHub

/**
 * 设备条目渲染。
 *
 * 同一套条目布局用在两个地方：
 *  - **附近设备**：本次扫描扫到的，右侧是「发送」按钮；
 *  - **连接历史**：之前传输过的，右侧是「在线 / 离线」标记。
 */
class DeviceAdapter(
    private val onPick: (Discovery.Peer) -> Unit
) {

    /** 附近设备：显示「可发送 / 仅接收」。 */
    fun bindNearby(view: View, peer: Discovery.Peer) {
        val ctx = view.context
        view.findViewById<TextView>(R.id.deviceName).text = peer.name
        view.findViewById<TextView>(R.id.deviceMeta).text =
            "${peer.host}:${peer.port} · " + if (peer.accepts) "可发送" else "仅接收"

        val badge = view.findViewById<TextView>(R.id.deviceSend)
        badge.text = ctx.getString(R.string.send_action)
        badge.setBackgroundResource(R.drawable.bg_pill)
        badge.setTextColor(ContextCompat.getColor(ctx, R.color.blue_700))
        badge.alpha = if (peer.accepts) 1f else 0.4f
        badge.setOnClickListener { if (peer.accepts) onPick(peer) }

        view.findViewById<ImageView>(R.id.deviceIcon).setImageResource(iconFor(peer))
        view.setOnClickListener { if (peer.accepts) onPick(peer) }
    }

    /** 连接历史：上次传输时间 + 在线/离线。 */
    fun bindHistory(view: View, peer: Discovery.Peer) {
        val ctx = view.context
        val online = ShareHub.isOnline(peer)

        view.findViewById<TextView>(R.id.deviceName).text = peer.name
        view.findViewById<TextView>(R.id.deviceMeta).text =
            "${peer.host}:${peer.port} · 上次传输 ${Fmt.time(peer.lastSeen)}"

        val badge = view.findViewById<TextView>(R.id.deviceSend)
        badge.text = ctx.getString(if (online) R.string.state_online else R.string.state_offline)
        badge.setBackgroundResource(if (online) R.drawable.bg_pill_green else R.drawable.bg_pill_gray)
        badge.setTextColor(
            ContextCompat.getColor(ctx, if (online) R.color.green_ok else R.color.ink_400)
        )
        badge.alpha = 1f
        // 历史设备点整行都能选：地址可能变了，选了才知道能不能发
        badge.setOnClickListener { onPick(peer) }

        view.findViewById<ImageView>(R.id.deviceIcon).setImageResource(iconFor(peer))
        view.setOnClickListener { onPick(peer) }
    }

    private fun iconFor(peer: Discovery.Peer): Int =
        if (peer.kind.contains("pad", true) || peer.kind.contains("tablet", true)) {
            R.drawable.ic_peer_tablet
        } else {
            R.drawable.ic_peer_phone
        }
}
