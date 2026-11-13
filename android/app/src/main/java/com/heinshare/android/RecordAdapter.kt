package com.heinshare.app

import android.widget.ImageView
import android.widget.TextView

/**
 * 传输记录。
 *
 * 记录的是**真实发生的传输**：对端发起分享请求、传输完成、发送完成。
 *
 * 之前是拿「收到来自 x.x.x.x 的连接」这条协议日志直接建记录的，
 * 结果华为手机每次连上来做账号认证探测（我们的协议栈会忽略这种帧），
 * 记录页就被一堆「对方请求分享文件 / 待确认」刷满 —— 实际上并没有任何分享请求。
 */
class RecordAdapter {

    class Entry(
        var title: String,
        var subtitle: String,
        var badge: String,
        val outgoing: Boolean
    )

    private val items = ArrayList<Entry>()
    private var unread = false

    /** 数据变了就通知界面刷新（不再用 RecyclerView 的 notifyDataSetChanged）。 */
    var onChanged: (() -> Unit)? = null

    /** 正在等待确认的接收请求（对应列表里的那一条）。 */
    private var pendingIncoming: Entry? = null
    /** 正在进行的发送任务。 */
    private var pendingSending: Entry? = null

    fun hasUnread(): Boolean = unread

    fun clearUnread() {
        unread = false
    }

    /** 对端发起了真实的分享请求。 */
    fun addIncoming(name: String) {
        // 同一对端重复请求时，复用原来那条，不要刷一串重复行。
        val existing = pendingIncoming
        if (existing != null) {
            existing.title = "来自 $name"
            existing.subtitle = "对方请求分享文件"
            existing.badge = "待确认"
            onChanged?.invoke()
            return
        }
        val entry = Entry("来自 $name", "对方请求分享文件", "待确认", outgoing = false)
        items.add(0, entry)
        pendingIncoming = entry
        trim()
    }

    /** 接收请求有了结果（完成 / 拒绝 / 失败）。 */
    fun resolveIncoming(text: String, badge: String) {
        val entry = pendingIncoming
        if (entry != null) {
            entry.subtitle = text
            entry.badge = badge
            pendingIncoming = null
        } else {
            items.add(0, Entry("接收失败", text, badge, outgoing = false))
        }
        trim()
    }

    /** 当前是否有正在等待确认的接收请求。 */
    fun hasPendingIncoming(): Boolean = pendingIncoming != null

    /** 开始发送。 */
    fun addSending(text: String) {
        val entry = Entry("发送中", text, "传输中", outgoing = true)
        items.add(0, entry)
        pendingSending = entry
        trim()
    }

    /** 发送结束。 */
    fun resolveSending(text: String, badge: String) {
        val entry = pendingSending
        if (entry != null) {
            entry.title = "发送完成"
            entry.subtitle = text
            entry.badge = badge
            pendingSending = null
        } else {
            items.add(0, Entry("发送完成", text, badge, outgoing = true))
        }
        trim()
    }

    private fun trim() {
        while (items.size > 100) items.removeAt(items.size - 1)
        unread = true
        onChanged?.invoke()
    }

    /** 把记录渲染到容器里（容器是 ScrollView 里的 LinearLayout，不是 RecyclerView）。 */
    fun submit(container: android.widget.LinearLayout) {
        val inflater = android.view.LayoutInflater.from(container.context)
        while (container.childCount > items.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < items.size) {
            container.addView(inflater.inflate(R.layout.item_record, container, false))
        }
        for (i in items.indices) {
            val entry = items[i]
            val row = container.getChildAt(i)
            row.findViewById<TextView>(R.id.recordTitle).text = entry.title
            row.findViewById<TextView>(R.id.recordSubtitle).text = entry.subtitle
            row.findViewById<TextView>(R.id.recordBadge).text = entry.badge
            row.findViewById<ImageView>(R.id.recordIcon)
                .setImageResource(if (entry.outgoing) R.drawable.ic_upload else R.drawable.ic_download)
        }
    }
}
