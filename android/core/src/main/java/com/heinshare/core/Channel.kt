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

    /**
     * 读缓冲。
     *
     * `buffer` 是底层数组，`bufferLen` 是有效数据长度，`bufferPos` 是已消费位置。
     * 原来每次 socket 读到一块数据都 `new ByteArray(old.size + got)` 再整体拷贝一遍，
     * 缓冲里残留 64KB 时每读一块就要搬 64KB，1GB 文件能搬出几百 MB 的无用拷贝。
     * 现在改成「容量按需增长、内容原地追加」，读进来的字节只搬一次。
     */
    private var buffer = ByteArray(INITIAL_BUFFER)
    private var bufferLen = 0
    private var bufferPos = 0

    /** 复用的临时读缓冲，避免每次 read 都 new。 */
    private val tmp = ByteArray(65536)

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

    private fun buffered(): Int = bufferLen - bufferPos

    private fun peek(n: Int): ByteArray {
        if (buffered() >= n) return buffer.copyOfRange(bufferPos, bufferPos + n)
        return ByteArray(0)
    }

    private fun consume(n: Int) {
        bufferPos += n
        if (bufferPos == bufferLen) {
            bufferLen = 0
            bufferPos = 0
        } else if (bufferPos > 64 * 1024) {
            // 把残留数据搬到头部，避免缓冲区无限增长
            val remain = bufferLen - bufferPos
            System.arraycopy(buffer, bufferPos, buffer, 0, remain)
            bufferLen = remain
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
            // 容量不够就按需增长（翻倍），然后把新数据追加到尾部
            val need = bufferLen + got
            if (buffer.size < need) {
                var cap = if (buffer.isEmpty()) INITIAL_BUFFER else buffer.size
                while (cap < need) cap *= 2
                val bigger = ByteArray(cap)
                System.arraycopy(buffer, 0, bigger, 0, bufferLen)
                buffer = bigger
            }
            System.arraycopy(tmp, 0, buffer, bufferLen, got)
            bufferLen += got
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
        /**
         * 读缓冲初始容量。
         *
         * 一个 DFile 数据块是 64KB，批量写是 256KB，起始给 256KB，
         * 绝大多数情况下不会触发扩容。
         */
        private const val INITIAL_BUFFER = 256 * 1024

        /**
         * socket 收发缓冲大小。
         *
         * 系统的默认 TCP 缓冲偏小（安卓上常见 16KB~64KB 起步），
         * 窗口一小就传不满 Wi-Fi 的带宽：内核要在对端确认前把数据都塞进
         * 发送缓冲 + 接收缓冲，缓冲不够就只能等 ACK，吞吐被卡在几十 MB/s。
         * 调到 1MB 后长距离（大文件）传输才能稳定跑满百兆以上的局域网带宽。
         *
         * 注意：这是**请求值**，内核可能按 `net.core.rmem_max` / `wmem_max` 打折，
         * 拿不到满 1MB 也不影响功能。
         */
        private const val SOCKET_BUFFER = 1024 * 1024

        /** 按需放大 socket 的收发缓冲。 */
        private fun tuneSocket(socket: Socket) {
            try { socket.receiveBufferSize = SOCKET_BUFFER } catch (_: Exception) {}
            try { socket.sendBufferSize = SOCKET_BUFFER } catch (_: Exception) {}
        }

        fun connect(host: String, port: Int, timeoutMs: Int): Channel {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            tuneSocket(socket)
            return Channel(socket, "chan")
        }

        /** 包装已接受的连接。 */
        fun fromSocket(socket: Socket, label: String): Channel {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000
            tuneSocket(socket)
            return Channel(socket, label)
        }
    }
}
