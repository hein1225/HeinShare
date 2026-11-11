package com.hishare.android

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** 传输记录。 */
class RecordAdapter : RecyclerView.Adapter<RecordAdapter.Holder>() {

    class Entry(
        val title: String,
        val subtitle: String,
        val badge: String,
        val outgoing: Boolean
    )

    private val items = ArrayList<Entry>()
    private var unread = false

    fun hasUnread(): Boolean = unread

    fun clearUnread() {
        unread = false
    }

    fun addIncoming(text: String) {
        add(Entry("来自 $text", "对方请求分享文件", "待确认", outgoing = false))
    }

    fun addDone(text: String, incoming: Boolean) {
        add(
            Entry(
                if (incoming) "接收完成" else "发送完成",
                text,
                "已完成",
                outgoing = !incoming
            )
        )
    }

    fun addSending(text: String) {
        add(Entry("发送中", text, "传输中", outgoing = true))
    }

    private fun add(entry: Entry) {
        items.add(0, entry)
        while (items.size > 100) items.removeAt(items.size - 1)
        unread = true
        notifyDataSetChanged()
    }

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.recordIcon)
        val title: android.widget.TextView = view.findViewById(R.id.recordTitle)
        val subtitle: android.widget.TextView = view.findViewById(R.id.recordSubtitle)
        val badge: android.widget.TextView = view.findViewById(R.id.recordBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_record, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        holder.title.text = entry.title
        holder.subtitle.text = entry.subtitle
        holder.badge.text = entry.badge
        holder.icon.setImageResource(if (entry.outgoing) R.drawable.ic_upload else R.drawable.ic_download)
    }
}
