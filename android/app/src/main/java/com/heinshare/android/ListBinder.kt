package com.heinshare.app

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout

/**
 * 把一个列表渲染到 LinearLayout 容器里。
 *
 * 为什么不用 RecyclerView：
 * RecyclerView 放在 ScrollView 里时，只能量出「一屏」的高度，
 * 外层 ScrollView 就以为内容已经到底了 —— 页面拖不动、列表也被截断，
 * 横屏（屏幕矮）时尤其明显。
 *
 * 这两个列表都很短（附近设备几台、记录上限 100 条），
 * 直接建 View 完全够用；而且复用已有子 View，不反复 inflate。
 */
class ListBinder<T>(
    private val container: LinearLayout,
    private val itemLayout: Int,
    private val bind: (View, T) -> Unit
) {

    fun submit(items: List<T>) {
        val inflater = LayoutInflater.from(container.context)
        // 多余的子 View 收起来
        while (container.childCount > items.size) {
            container.removeViewAt(container.childCount - 1)
        }
        // 不够的补上
        while (container.childCount < items.size) {
            container.addView(inflater.inflate(itemLayout, container, false))
        }
        for (i in items.indices) {
            bind(container.getChildAt(i), items[i])
        }
    }
}
