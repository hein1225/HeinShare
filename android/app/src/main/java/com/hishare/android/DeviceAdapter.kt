package com.hishare.android

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hishare.core.Discovery

/** 附近设备列表。 */
class DeviceAdapter(
    private val onPick: (Discovery.Peer) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val items = ArrayList<Discovery.Peer>()

    fun submit(list: List<Discovery.Peer>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.deviceIcon)
        val name: android.widget.TextView = view.findViewById(R.id.deviceName)
        val meta: android.widget.TextView = view.findViewById(R.id.deviceMeta)
        val send: android.widget.TextView = view.findViewById(R.id.deviceSend)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val peer = items[position]
        holder.name.text = peer.name
        holder.meta.text = "${peer.host}:${peer.port} · ${if (peer.accepts) "可发送" else "仅接收"}"
        holder.icon.setImageResource(
            if (peer.kind.contains("pad", true) || peer.kind.contains("tablet", true)) R.drawable.ic_tablet
            else R.drawable.ic_phone
        )
        holder.send.alpha = if (peer.accepts) 1f else 0.4f
        holder.itemView.setOnClickListener {
            if (peer.accepts) onPick(peer)
        }
    }
}
