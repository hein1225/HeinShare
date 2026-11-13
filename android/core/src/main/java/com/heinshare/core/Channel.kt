package com.heinshare.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 带缓冲的 TCP 通道。
 *
 * 对应 Rust 版 `src/src/channel.rs`：同一套字节流上既跑 24 字节头的「通道帧」，
 * 也跑 8 字节头的 DFile 数据包，还跑 16 字节头的 byte 通道帧。
 */
class Channel(private val socket: Socket, var label: String) {

    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var closed = false

    val peerAddress: String get() = socket.remoteSocketAddress?.toString() ?: ""
    val localAddress: String get() = socket.localSocketAddress?.toString() ?: ""

    private val input: InputStream get() = socket.getInputStream()
    private val output: OutputStream get() = socket.getOutputStream()

    /** 已从 socket 读入的总字节数（诊断用）。 */
    var readTotal: Long = 0
        private set

    /** 已写到 socket 的总字节数（诊断用）。 */
    var writeTotal: Long = 0
        private set

    fun transmit(data: ByteArray) {
        output.write(data)
        output.flush()
        writeTotal += data.size
    }

    fun close() {
        if (closed) return
        closed = true
        try { socket.close() } catch (_: Exception) {}
    }

    fun isClosed(): Boolean = closed || socket.isClosed

    private fun buffered(): Int = buffer.size - bufferPos

    private fun peek(n: Int): ByteArray {
        if (buffered() >= n) return buffer.copyOfRange(bufferPos, bufferPos + n)
        return ByteArray(0)
    }

    private fun consume(n: Int) {
        bufferPos += n
        if (bufferPos == buffer.size) {
            buffer = ByteArray(0)
            bufferPos = 0
        } else if (bufferPos > 64 * 1024) {
            buffer = buffer.copyOfRange(bufferPos, buffer.size)
            bufferPos = 0
        }
    }

    /**
     * 阻塞读取，直到缓冲里比现在多出至少 [extra] 字节；超时返回 false。
     *
     * 两条铁律：
     *  * extra 是「还需要多少」，不是「总共要多少」；
     *  * 只往缓冲尾部追加，绝不能把已有数据丢掉——bufferPos==0 不代表缓冲是空的
     *    （consume() 压缩过缓冲后就是「pos=0 但仍有残留」），否则大数据帧会错位。
     */
    private fun fill(extra: Int, timeoutMs: Int): Boolean {
        val target = buffered() + extra
        val deadline = System.currentTimeMillis() + timeoutMs
        while (buffered() < target) {
            val remain = (deadline - System.currentTimeMillis()).toInt()
            if (remain <= 0) return false
            try {
                socket.soTimeout = remain
            } catch (_: Exception) {}
            val tmp = ByteArray(65536)
            val got = try {
                input.read(tmp)
            } catch (e: SocketTimeoutException) {
                continue
            }
            if (got < 0) {
                closed = true
                return false
            }
            readTotal += got
            val old = buffer
            val merged = ByteArray(old.size + got)
            System.arraycopy(old, 0, merged, 0, old.size)
            System.arraycopy(tmp, 0, merged, old.size, got)
            buffer = merged
        }
        return true
    }

    /** 读一个 24 字节头 + 载荷的通道帧；超时返回 null。 */
    fun tryReadFrame(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (buffered() >= Bytes.FRAME_HEADER_SIZE) {
                val header = Bytes.frameDecode(peek(Bytes.FRAME_HEADER_SIZE))
                val total = Bytes.FRAME_HEADER_SIZE + header.length
                if (buffered() >= total) {
                    val frame = peek(total)
                    consume(total)
                    return frame
                }
            }
            val remain = (deadline - System.currentTimeMillis()).toInt()
            if (remain <= 0) return null
            val want = if (buffered() < Bytes.FRAME_HEADER_SIZE) {
                Bytes.FRAME_HEADER_SIZE - buffered()
            } else {
                val header = Bytes.frameDecode(peek(Bytes.FRAME_HEADER_SIZE))
                (Bytes.FRAME_HEADER_SIZE + header.length - buffered()).coerceAtLeast(1)
            }
            if (!fill(want.coerceAtMost(65536), remain)) {
                if (closed && buffered() < Bytes.FRAME_HEADER_SIZE) return null
            }
        }
    }

    /** 读一个 8 字节头 + 载荷的 DFile 数据包；超时返回 null。 */
    fun tryReadDfile(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (buffered() >= 8) {
                val size = Bytes.u16be(peek(8), 6)
                val total = 8 + size
                if (buffered() >= total) {
                    val packet = peek(total)
                    consume(total)
                    return packet
                }
            }
            val remain = (deadline - System.currentTimeMillis()).toInt()
            if (remain <= 0) return null
            val want = if (buffered() < 8) 8 - buffered() else {
                val size = Bytes.u16be(peek(8), 6)
                (8 + size - buffered()).coerceAtLeast(1)
            }
            fill(want.coerceAtMost(65536), remain)
        }
    }

    /**
     * 读「可能是外层通道帧、也可能是裸内层帧」的原始字节。
     *
     * byte 通道握手应答在不同对端上形态不一：
     *   * 华为手机回的是**裸内层帧**（24 字节内层头 + nonce + 密文，没有外层 module 头）；
     *   * 某些端会回完整外层帧（首 4 字节是帧魔数）。
     *
     * 这里按首 4 字节自动判别，两种都收。
     */
    fun tryReadRawChannelFrame(timeoutMs: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (buffered() >= Bytes.FRAME_HEADER_SIZE) {
                val head = peek(Bytes.FRAME_HEADER_SIZE)
                val isOuter = Bytes.u32le(head, 0) == Bytes.FRAME_MAGIC_WORD
                val total = if (isOuter) {
                    Bytes.FRAME_HEADER_SIZE + Bytes.u32le(head, 20)
                } else {
                    // 裸内层帧：h4 在偏移 20，同样是「载荷长度」
                    val size = Bytes.u32le(head, 20)
                    if (size <= 0 || size > 8 * 1024 * 1024) {
                        throw IllegalArgumentException("内层帧长度异常：$size")
                    }
                    Bytes.FRAME_HEADER_SIZE + size
                }
                if (buffered() >= total) {
                    val frame = peek(total)
                    consume(total)
                    return frame
                }
            }
            val remain = (deadline - System.currentTimeMillis()).toInt()
            if (remain <= 0) return null
            val want = if (buffered() < Bytes.FRAME_HEADER_SIZE) {
                Bytes.FRAME_HEADER_SIZE - buffered()
            } else {
                (Bytes.FRAME_HEADER_SIZE + Bytes.u32le(peek(Bytes.FRAME_HEADER_SIZE), 20) - buffered())
                    .coerceAtLeast(1)
            }
            fill(want.coerceAtMost(65536), remain)
        }
    }

    /**
     * 读一个 16 字节头 + 载荷的 byte 通道帧；返回 (sequence, 明文)。
     *
     * byte 通道的数据帧头与 24 字节「通道帧」不同：只有 magic(4) + sequence(4) +
     * 保留(4) + 载荷长度(4)，没有 flags 字段。
     */
    fun tryReadBytesFrame(key: ByteArray, timeoutMs: Int): Pair<Int, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (buffered() >= 16) {
                val head = peek(16)
                val magic = Bytes.u32le(head, 0)
                val sequence = Bytes.u32le(head, 4)
                val size = Bytes.u32le(head, 12)
                if (magic != Bytes.FRAME_MAGIC_WORD || size < 13 || size > 8 * 1024 * 1024) {
                    throw IllegalArgumentException("byte 帧头异常")
                }
                if (buffered() >= 16 + size) {
                    val body = peek(16 + size)
                    consume(16 + size)
                    val nonce = body.copyOfRange(16, 28)
                    val sealed = body.copyOfRange(28, body.size)
                    val plain = Crypto.aesGcmDecrypt(key, nonce, sealed)
                        ?: throw IllegalArgumentException("byte 帧解密失败")
                    return sequence to plain
                }
            }
            val remain = (deadline - System.currentTimeMillis()).toInt()
            if (remain <= 0) return null
            val want = if (buffered() < 16) 16 - buffered() else {
                val size = Bytes.u32le(peek(16), 12)
                (16 + size - buffered()).coerceAtLeast(1)
            }
            fill(want.coerceAtMost(65536), remain)
        }
    }

    /** 关闭写方向并等待对端收尾。 */
    fun gracefulClose(idleMs: Int) {
        try { socket.shutdownOutput() } catch (_: Exception) {}
        val deadline = System.currentTimeMillis() + idleMs
        try { socket.soTimeout = 50 } catch (_: Exception) {}
        val tmp = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            try {
                if (input.read(tmp) < 0) break
            } catch (_: Exception) {
                break
            }
        }
        close()
    }

    companion object {
        fun connect(host: String, port: Int, timeoutMs: Int): Channel {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            return Channel(socket, "chan")
        }

        /** 包装已接受的连接。 */
        fun fromSocket(socket: Socket, label: String): Channel {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000
            return Channel(socket, label)
        }
    }
}
