package com.heinshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已接收文件记录的编解码测试。
 */
class ReceivedFileCodecTest {

    @Test
    fun roundTripKeepsAllFields() {
        val files = listOf(
            ReceivedFile("a.txt", "/storage/下载/a.txt", "下载/heinshare/a.txt", "text/plain", 1000L),
            ReceivedFile("b.bin", "content://media/1", "下载/heinshare/b.bin", "application/octet-stream", 2000L)
        )
        val decoded = ReceivedFileCodec.decode(ReceivedFileCodec.encode(files))
        assertEquals(2, decoded.size)
        assertEquals(files[0], decoded[0])
        assertEquals(files[1], decoded[1])
    }

    @Test
    fun tabsAndSpacesInNamesSurvive() {
        val f = ReceivedFile("我的 文件.txt", "/tmp/x y/z.txt", "下载/x y/z.txt", "text/plain", 5L)
        val decoded = ReceivedFileCodec.decode(ReceivedFileCodec.encode(listOf(f)))
        assertEquals(1, decoded.size)
        assertEquals("我的 文件.txt", decoded[0].name)
        assertEquals("/tmp/x y/z.txt", decoded[0].target)
    }

    @Test
    fun emptyTextDecodesToEmptyList() {
        assertTrue(ReceivedFileCodec.decode("").isEmpty())
    }

    @Test
    fun brokenLineIsSkipped() {
        val good = ReceivedFile("a.txt", "/t/a.txt", "d/a.txt", "text/plain", 1L)
        val text = ReceivedFileCodec.encode(listOf(good)) + "\n坏行\t缺字段"
        val decoded = ReceivedFileCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("a.txt", decoded[0].name)
    }
}
