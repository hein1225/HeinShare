package com.heinshare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 滑动窗口速率计的行为约束。 */
class SpeedMeterTest {

    @Test
    fun `单个样本不产出速率`() {
        val m = SpeedMeter()
        // 只记了一次采样，窗口内没有第二个点，算不出速率
        assertEquals(0L, m.update(0L, now = 1000L))
    }

    @Test
    fun `窗口内匀速传输得到正确速率`() {
        val m = SpeedMeter()
        m.update(0L, now = 1000L)
        // 2 秒后累计 2MB → 1MB/s
        assertEquals(1024L * 1024L, m.update(2L * 1024 * 1024, now = 3000L))
    }

    @Test
    fun `超出窗口的旧样本被丢弃`() {
        val m = SpeedMeter(windowMs = 1000L)
        m.update(0L, now = 0L)
        // 10 秒后：窗口内只剩最后一个点，算不出速率
        assertEquals(0L, m.update(10L * 1024 * 1024, now = 10_000L))
    }

    @Test
    fun `累计字节不变时速率为零`() {
        val m = SpeedMeter()
        m.update(1000L, now = 1000L)
        assertEquals(0L, m.update(1000L, now = 2000L))
    }

    @Test
    fun `reset 后重新计速`() {
        val m = SpeedMeter()
        m.update(0L, now = 1000L)
        m.reset()
        // reset 后历史样本没了，第一个样本算不出速率
        assertEquals(0L, m.update(5L * 1024 * 1024, now = 2000L))
        val speed = m.update(6L * 1024 * 1024, now = 3000L)
        assertTrue("应能算出速率", speed > 0)
    }
}
