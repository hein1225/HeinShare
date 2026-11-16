package com.heinshare.app

/**
 * 传输速率计算器：滑动窗口。
 *
 * 为什么不用「累计字节 / 总时间」：
 *  * 全程平均速度在传输刚起步时严重偏小（握手、鉴权、首块延迟都被算进去），
 *    用户看到的数字会长时间不动然后突然跳到真实值；
 *  * 只用「最近一次的瞬时速度」则会剧烈抖动（一个数据块 64KB，几毫秒就走完），
 *    界面上会一会儿几十 MB/s 一会儿几十 KB/s，看着像坏了。
 *
 * 滑动窗口取最近几秒的样本算平均，既稳定又能反映当前真实速率。
 */
class SpeedMeter(
    private val windowMs: Long = 3000L,
    private val maxSamples: Int = 16
) {
    private val times = LongArray(maxSamples)
    private val bytesAt = LongArray(maxSamples)
    private var count = 0
    private var head = 0

    /**
     * 记一次采样，返回当前速率（字节/秒）。
     *
     * @param totalBytes 迄今累计传输的字节数（必须单调递增）
     */
    fun update(totalBytes: Long, now: Long = System.currentTimeMillis()): Long {
        times[head] = now
        bytesAt[head] = totalBytes
        head = (head + 1) % maxSamples
        if (count < maxSamples) count++

        val latest = (head - 1 + maxSamples) % maxSamples
        // 从最新往回找，落在窗口内的最老样本
        var oldest = latest
        for (i in 0 until count) {
            val j = (head - 1 - i + maxSamples * 2) % maxSamples
            if (now - times[j] > windowMs) break
            oldest = j
        }
        if (oldest == latest) return 0L
        val dt = (now - times[oldest]) / 1000.0
        if (dt <= 0.05) return 0L
        val delta = totalBytes - bytesAt[oldest]
        if (delta <= 0) return 0L
        return (delta / dt).toLong()
    }

    /** 传输重新开始时清空。 */
    fun reset() {
        count = 0
        head = 0
    }
}
