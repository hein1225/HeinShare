package com.heinshare.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 发送方 ↔ 接收方环回测试。
 *
 * 在一台机器上起两个协议栈实例（Sender / Receiver），走完整条链路：
 * opening → 能力 → 公钥 → RSA 挑战 → offer → user_confirm →
 * META → CODE=3 P2P → byte 通道 → file 通道 → DFile settings →
 * 清单 → 数据块 → 落盘。
 *
 * 作用有两个：
 *  1. 复现「Windows 版能跑、安卓版跑不通」的差异（清单布局、opening ACK 大小写等）；
 *  2. 以后改协议层时有个不用真机就能跑的回归网。
 */
class LoopbackTransferTest {

    private val logs = Collections.synchronizedList(ArrayList<String>())

    private fun log(tag: String, text: String) {
        logs.add("$tag $text")
    }

    @Test
    fun manifestUnitLayoutMatchesSpec() {
        // 单元 = id(2) + size(8 BE) + name_len(2 BE) + name，共 12 字节头
        val entries = listOf(
            Protocol.ManifestEntry(1, 0x0102030405060708L, "a.txt".toByteArray())
        )
        val frames = Protocol.buildHeaders(entries, 1)
        assertEquals(1, frames.size)
        val payload = frames[0].copyOfRange(8, frames[0].size)
        // count(2) + id(2) + size(8) + nameLen(2) + name(5)
        assertEquals(2 + 2 + 8 + 2 + 5, payload.size)
        assertEquals(1, Bytes.u16be(payload, 0).toInt())
        assertEquals(1, Bytes.u16be(payload, 2).toInt())
        assertEquals(0x0102030405060708L, {
            var s = 0L
            for (i in 4 until 12) s = (s shl 8) or (payload[i].toLong() and 0xFF)
            s
        }())
        assertEquals(5, Bytes.u16be(payload, 12).toInt())

        // 解析端必须能原样还原
        val expected = ArrayList<Protocol.IncomingFile>()
        val empty = ArrayList<Pair<Int, String>>()
        Protocol.mergeManifest(expected, empty, payload)
        assertEquals(1, expected.size)
        assertEquals("a.txt", expected[0].name)
        assertEquals(0x0102030405060708L, expected[0].size)
    }

    @Test
    fun openingAckUsesLowercaseIdentity() {
        val ack = String(
            Protocol.openingAck("A1B2C3D4E5F6"),
            Bytes.FRAME_HEADER_SIZE,
            Protocol.openingAck("A1B2C3D4E5F6").size - Bytes.FRAME_HEADER_SIZE,
            Charsets.UTF_8
        )
        assertTrue(ack.contains("\"DEVICE_ID\":\"a1b2c3d4e5f6\""))
    }

    @Test
    fun settingsPayloadMatchesRustProfile() {
        // Rust: SettingsProfile::receiver(true, 1472, 1472).encode() → 帧长 108，载荷 100
        val frame = Protocol.receiverSettings(true, 1472, 1472).encode()
        assertEquals(108, frame.size)
        assertEquals(8, frame[0].toInt())
        val payload = frame.copyOfRange(8, frame.size)
        assertEquals(100, payload.size)
        assertEquals(1472, Bytes.u16be(payload, 0).toInt())
        assertEquals(2, Bytes.u16be(payload, 2).toInt())
        // caps 在载荷尾部（大端，因为载荷 100 < 103）
        assertEquals(Protocol.OUR_CIPHER_CAPS.toLong(), Bytes.u32be(payload, 96).toLong())
        assertEquals(Protocol.OUR_CIPHER_CAPS.toLong(), Protocol.settingsCipherCaps(payload)!!.toLong())
    }

    @Test
    fun base64RoundTrip() {
        for (n in 0..64) {
            val data = ByteArray(n) { (it * 7 + 3).toByte() }
            val text = Bytes.base64Encode(data)
            assertArrayEquals(data, Bytes.base64Decode(text))
        }
        assertArrayEquals("hi".toByteArray(), Bytes.base64Decode("aGk="))
    }

    @Test
    fun dataFrameSurvivesTcpRoundTrip() {
        val aead = Protocol.Aead(Protocol.Cipher.AES128_GCM, false)
        val key = ByteArray(32) { (it * 3).toByte() }
        val content = ByteArray(Protocol.DFILE_MAX_CONTENT) { (it % 251).toByte() }
        val frame = Protocol.buildDataBlock(aead, key, content, 0, 3, 1, 7)

        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val writer = Thread {
            val s = server.accept()
            Channel(s, "w").transmit(frame)
            Thread.sleep(500)
            s.close()
        }
        writer.isDaemon = true
        writer.start()

        val chan = Channel.connect("127.0.0.1", server.localPort, 5000)
        val got = chan.tryReadDfile(5000)
        assertNotNull("没读到数据帧", got)
        assertArrayEquals(frame, got)
        val h = Protocol.parseDfileHeader(got!!)
        assertEquals(4, h.kind)
        val (fid, seq, data) = Protocol.dfileParseChunk(aead, key, got.copyOfRange(8, 8 + h.size))
        assertEquals(1, fid)
        assertEquals(0, seq)
        assertArrayEquals(content, data)
    }

    @Test
    fun manyLargeFramesSurviveTcpRoundTrip() {
        val frames = ArrayList<ByteArray>()
        for (i in 0 until 6) {
            val content = ByteArray(if (i == 5) 22575 else 65485) { (it % 251).toByte() }
            val aead = Protocol.Aead(Protocol.Cipher.AES128_GCM, false)
            val key = ByteArray(32) { (it * 5).toByte() }
            frames.add(Protocol.buildDataBlock(aead, key, content, i, if (i == 5) 3 else 1, 1, 7))
        }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val writer = Thread {
            val s = server.accept()
            val out = s.getOutputStream()
            for (f in frames) { out.write(f); out.flush() }
            Thread.sleep(500)
            s.close()
        }
        writer.isDaemon = true
        writer.start()

        val chan = Channel.connect("127.0.0.1", server.localPort, 5000)
        var total = 0
        for (i in 0 until 6) {
            val got = chan.tryReadDfile(3000)
            assertNotNull("第 \$i 帧没读到", got)
            assertArrayEquals("第 \$i 帧内容不一致", frames[i], got)
            total += got!!.size
        }
        assertEquals(350300, total)
    }

    @Test
    fun senderTransfersFilesToReceiver() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "heinshare-loopback-${System.nanoTime()}")
        tmp.mkdirs()
        try {
            val identityA = Identity(File(tmp, "a").apply { mkdirs() }.let { File(it, "id.pem") })
            val identityB = Identity(File(tmp, "b").apply { mkdirs() }.let { File(it, "id.pem") })

            val payload = ByteArray(350_000) { ((it * 31) % 251).toByte() }
            val outDir = File(tmp, "out").apply { mkdirs() }

            val server = ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))
            val port = server.localPort

            val receiverDone = CountDownLatch(1)
            var receivedBytes = 0L
            var receiverError: Throwable? = null

            val receiverThread = Thread {
                try {
                    val sock = server.accept()
                    val chan = Channel(sock, "control")
                    val receiver = Receiver(identityB, { log("R:", it) }, { r, _ -> })
                    val session = receiver.acceptConnection(chan, true, 20_000)
                    val result = receiver.runSession(
                        chan, session, FileSaveTarget(outDir), "测试设备", 60_000
                    )
                    receivedBytes = result.bytes
                } catch (t: Throwable) {
                    receiverError = t
                } finally {
                    receiverDone.countDown()
                    try { server.close() } catch (_: Exception) {}
                }
            }
            receiverThread.name = "RECV"
            receiverThread.isDaemon = true
            receiverThread.start()

            var senderError: Throwable? = null
            var sentBytes = 0L
            val senderThread = Thread {
                try {
                    val sender = Sender(identityA, { log("S:", it) }, { _, _ -> })
                    val files = listOf(object : FileSource {
                        override val name = "payload.bin"
                        override val size = payload.size.toLong()
                        override val relPath = "payload.bin"
                        override fun openInput(): ByteArrayInputStream = ByteArrayInputStream(payload)
                    })
                    val report = sender.send("127.0.0.1", port, files, "测试设备")
                    sentBytes = report.bytes
                } catch (t: Throwable) {
                    senderError = t
                }
            }
            senderThread.name = "SEND"
            senderThread.isDaemon = true
            senderThread.start()

            senderThread.join(90_000)
            receiverDone.await(30, TimeUnit.SECONDS)

            if (senderError != null || receiverError != null) {
                throw AssertionError(
                    "发送失败: ${senderError}\n接收失败: ${receiverError}\n日志:\n" + logs.joinToString("\n")
                )
            }

            assertEquals("发送字节数", payload.size.toLong(), sentBytes)
            assertEquals("接收字节数", payload.size.toLong(), receivedBytes)

            val saved = File(outDir, "payload.bin")
            assertTrue("落盘文件不存在: $outDir", saved.exists())
            assertArrayEquals(payload, saved.readBytes())
        } finally {
            logs.clear()
            tmp.deleteRecursively()
        }
    }

    /**
     * byte 通道的「裸内层帧」解析。
     *
     * 这是本轮真机「发送失败：byte 通道握手超时」的直接回归用例：
     * 对端回的是 sealChannelFrame 的内层（24 字节头 + nonce + 密文），
     * 外面没有 MODULE_CHANNEL 外层头。之前用 tryReadFrame 按外层帧读，
     * 首 4 字节不是魔数就被丢掉，最后读满 8 秒超时。
     */
    @Test
    fun innerChannelFrameParsesWithoutOuterHeader() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val body = """{"CODE":1,"API_VERSION":2,"MTU_SIZE":1048576}""".toByteArray() + 0
        val sealed = Protocol.sealChannelFrame(
            key,
            Protocol.ChannelHeader(Protocol.CHANNEL_BYTES, 6, 0x1234L, 0x95, 0),
            body,
            0
        )
        // 砍掉外层 24 字节头，只留内层
        val inner = sealed.copyOfRange(Bytes.FRAME_HEADER_SIZE, sealed.size)

        val (header, plain) = Protocol.parseInnerChannelFrame(inner, key)
        assertEquals("h0", Protocol.CHANNEL_BYTES, header.h0)
        assertEquals("h1", 6, header.h1)
        assertEquals("h3", 0x95, header.h3)
        assertArrayEquals("明文", body, plain)
    }

    /** 22 字节以下的内层帧要直接拒绝，不能越界读。 */
    @Test(expected = IllegalArgumentException::class)
    fun innerChannelFrameRejectsTruncatedInput() {
        Protocol.parseInnerChannelFrame(ByteArray(20), ByteArray(32))
    }

    /**
     * `Channel.tryReadRawChannelFrame` 必须同时接受「带外层头的完整帧」和「裸内层帧」。
     *
     * 不同对端（华为手机 / 其它实现）回的形态不一致，发送方不能只认一种。
     */
    @Test
    fun rawChannelFrameReaderAcceptsBothShapes() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val body = "hello".toByteArray() + 0

        // 形态一：完整外层帧
        val outer = Protocol.sealChannelFrame(
            key, Protocol.ChannelHeader(Protocol.CHANNEL_BYTES, 6, 1L, 0x95, 0), body, 0
        )
        // 形态二：裸内层帧
        val inner = outer.copyOfRange(Bytes.FRAME_HEADER_SIZE, outer.size)

        for ((label, wire) in listOf("outer" to outer, "inner" to inner)) {
            val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val port = server.localPort
            val received = arrayOfNulls<ByteArray>(1)
            val t = Thread {
                try {
                    val sock = java.net.Socket("127.0.0.1", port)
                    sock.getOutputStream().write(wire)
                    sock.getOutputStream().flush()
                    Thread.sleep(300)
                    sock.close()
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }
            t.start()
            server.soTimeout = 3000
            val accepted = server.accept()
            val ch = Channel.fromSocket(accepted, "test")
            val got = ch.tryReadRawChannelFrame(3000)
            ch.close()
            server.close()
            t.join(2000)
            received[0] = got
            assertArrayEquals("$label 形态读取结果", wire, got)
        }
    }
}
