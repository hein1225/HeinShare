package com.hishare.android

import com.hishare.android.databinding.ActivityPickerBinding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.InputStream

/**
 * 选文件页：按「图片 / 视频 / 文档 / 全部」浏览本机内容，点选后下一步进发送页。
 *
 * 图片与视频走 MediaStore；文档走系统文件选择器（SAF），因为文档没有可枚举的公共库。
 */
class PickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPickerBinding
    private lateinit var adapter: MediaAdapter
    private val selected = ArrayList<MediaItem>()
    private var tab = TAB_IMAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MediaAdapter { item -> toggle(item) }
        binding.mediaGrid.layoutManager = GridLayoutManager(this, 3)
        binding.mediaGrid.adapter = adapter

        binding.pickerBack.setOnClickListener { finish() }
        binding.tabImage.setOnClickListener { selectTab(TAB_IMAGE) }
        binding.tabVideo.setOnClickListener { selectTab(TAB_VIDEO) }
        binding.tabDoc.setOnClickListener { selectTab(TAB_DOC) }
        binding.tabAll.setOnClickListener { selectTab(TAB_ALL) }
        binding.nextButton.setOnClickListener { goNext() }

        selectTab(TAB_IMAGE)
    }

    private fun selectTab(next: Int) {
        tab = next
        val active = androidx.core.content.ContextCompat.getColor(this, R.color.blue_600)
        val normal = androidx.core.content.ContextCompat.getColor(this, R.color.ink_500)
        val selBg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
        val plainBg = androidx.core.content.ContextCompat.getDrawable(this, android.R.color.transparent)

        listOf(
            binding.tabImage to TAB_IMAGE,
            binding.tabVideo to TAB_VIDEO,
            binding.tabDoc to TAB_DOC,
            binding.tabAll to TAB_ALL
        ).forEach { (view, id) ->
            val on = id == next
            view.setTextColor(if (on) active else normal)
            view.background = if (on) selBg else plainBg
        }

        when (next) {
            TAB_IMAGE -> adapter.submit(MediaStoreRepo.queryImages(this))
            TAB_VIDEO -> adapter.submit(MediaStoreRepo.queryVideos(this))
            TAB_ALL -> adapter.submit(MediaStoreRepo.queryAll(this))
            TAB_DOC -> pickDocument()
        }
    }

    /** 文档没有公共媒体库，直接调系统选择器。 */
    private fun pickDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_DOC)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "系统没有可用的文件选择器", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DOC || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.data?.let { uris.add(it) }
        @Suppress("DEPRECATION")
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        val items = uris.map {
            MediaItem(
                uri = it,
                name = MediaStoreRepo.queryName(this, it),
                size = MediaStoreRepo.querySize(this, it),
                isVideo = false
            )
        }
        items.forEach { selected.add(it) }
        adapter.submit(items)
        updateCount()
    }

    private fun toggle(item: MediaItem) {
        val existing = selected.indexOfFirst { it.uri == item.uri }
        if (existing >= 0) selected.removeAt(existing) else selected.add(item)
        adapter.setSelected(selected.map { it.uri })
        updateCount()
    }

    private fun updateCount() {
        binding.selectedCount.text = getString(R.string.picker_selected, selected.size)
        binding.nextButton.alpha = if (selected.isEmpty()) 0.5f else 1f
    }

    private fun goNext() {
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, "请先选择文件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList(selected.map { it.uri.toString() })
        val names = ArrayList(selected.map { it.name })
        val sizes = ArrayList(selected.map { it.size.toString() })
        val intent = Intent(this, SendActivity::class.java)
        intent.putStringArrayListExtra("uris", uris)
        intent.putStringArrayListExtra("names", names)
        intent.putStringArrayListExtra("sizes", sizes)
        intent.getStringExtra("host")?.let { intent.putExtra("host", it) }
        intent.getStringExtra("port")?.let { intent.putExtra("port", it) }
        intent.getStringExtra("name")?.let { intent.putExtra("name", it) }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAB_IMAGE = 0
        private const val TAB_VIDEO = 1
        private const val TAB_DOC = 2
        private const val TAB_ALL = 3
        private const val REQ_DOC = 21
    }
}

/** 媒体网格适配器。 */
class MediaAdapter(
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.Holder>() {

    private val items = ArrayList<MediaItem>()
    private val selected = HashSet<Uri>()

    fun submit(list: List<MediaItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelected(uris: List<Uri>) {
        selected.clear()
        selected.addAll(uris)
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.mediaThumb)
        val count: TextView = view.findViewById(R.id.mediaCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.thumb.setImageURI(item.uri)
        val isSel = selected.contains(item.uri)
        holder.count.visibility = if (isSel) View.VISIBLE else View.GONE
        holder.count.text = "✓"
        holder.itemView.alpha = if (isSel) 0.85f else 1f
        holder.itemView.setOnClickListener { onClick(item) }
    }
}
