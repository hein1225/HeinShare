package com.heinshare.core

import java.io.ByteArrayOutputStream

/**
 * 字节级工具：十六进制、LE/BE 数值、varint、TLV、帧头。
 *
 * 对应 Rust 版 `src/src/lib.rs` 中的同名能力，格式必须逐字节一致。
 */
object Bytes {
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val HEX_LOWER = "0123456789abcdef".toCharArray()

    const val FRAME_MAGIC_WORD = 0xBABEFACE.toInt()
    const val FRAME_HEADER_SIZE = 24
    const val FRAME_MAX_PAYLOAD = 65536
    const val FRAME_MAX_ENVELOPE = 16 * 1024 * 1024
    const val ENVELOPE_MODULE = 9
    const val VARINT_MAX = (1L shl 28) - 1

    fun hexUpper(data: ByteArray): String = hex(data, HEX)

    fun hexLower(data: ByteArray): String = hex(data, HEX_LOWER)

    // ------------------------------------------------------------- Base64
    //
    // 自己实现而不是用 android.util.Base64 / java.util.Base64：
    //  * android.util.Base64 在 JVM 单元测试里不可用；
    //  * java.util.Base64 要 API 26，而本项目 minSdk 是 24。

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(B64[(n ushr 18) and 0x3F])
            sb.append(B64[(n ushr 12) and 0x3F])
            sb.append(B64[(n ushr 6) and 0x3F])
            sb.append(B64[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n ushr 18) and 0x3F])
                sb.append(B64[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n ushr 18) and 0x3F])
                sb.append(B64[(n ushr 12) and 0x3F])
                sb.append(B64[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /** 标准 Base64 解码，忽略空白与换行；遇到非法字符抛异常。 */
    fun base64Decode(text: String): ByteArray {
        val clean = StringBuilder(text.length)
        for (c in text) if (!c.isWhitespace()) clean.append(c)
        val s = clean.toString()
        require(s.length % 4 == 0) { "Base64 长度非法" }
        val out = ByteArray(s.length / 4 * 3)
        var o = 0
        var i = 0
        while (i < s.length) {
            var acc = 0
            var bits = 0
            var pad = 0
            for (j in 0 until 4) {
                val c = s[i + j]
                if (c == '=') {
                    pad++
                    acc = acc shl 6
                } else {
                    val v = B64.indexOf(c)
                    require(v >= 0) { "Base64 含非法字符：$c" }
                    acc = (acc shl 6) or v
                    bits += 6
                }
            }
            i += 4
            when (bits) {
                24 -> {
                    out[o++] = (acc ushr 16).toByte()
                    out[o++] = (acc ushr 8).toByte()
                    out[o++] = acc.toByte()
                }
                18 -> {
                    out[o++] = (acc ushr 16).toByte()
                    out[o++] = (acc ushr 8).toByte()
                }
                12 -> out[o++] = (acc ushr 16).toByte()
                else -> require(pad >= 2) { "Base64 填充非法" }
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    private fun hex(data: ByteArray, table: CharArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(table[v ushr 4])
            sb.append(table[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0) { "十六进制长度必须是偶数" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    fun u32le(data: ByteArray, offset: Int): Int {
        require(offset + 4 <= data.size) { "读取 u32 越界" }
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun u64le(data: ByteArray, offset: Int): Long {
        require(offset + 8 <= data.size) { "读取 u64 越界" }
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }

    fun u64be(data: ByteArray, offset: Int): Long {
        require(offset + 8 <= data.size) { "读取 u64 越界" }
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }

    fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun u32be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    fun putU32le(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    fun putU64le(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 8) {
            out.write(((value ushr (i * 8)) and 0xFF).toInt())
        }
    }

    fun putU16be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** 往定长数组的指定偏移写 2 字节大端值。 */
    fun putU16be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    /** 往定长数组的指定偏移写 4 字节大端值。 */
    fun putU32be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun putU32be(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /**
     * varint（LEB128 变体）：低位在前，**最后一个字节的 bit7 = 1** 表示结束。
     * 与 Rust 版 `varint_encode` 完全一致。
     */
    fun varintEncode(value: Long): ByteArray {
        require(value in 0..VARINT_MAX) { "varint 超出范围: $value" }
        val out = ByteArrayOutputStream()
        var v = value
        while (v >= 128) {
            out.write((v and 0x7F).toInt())
            v = v ushr 7
        }
        out.write((v or 0x80).toInt())
        return out.toByteArray()
    }

    class VarintReader(private val data: ByteArray, var pos: Int = 0) {
        fun read(): Long {
            var acc = 0L
            var shift = 0
            while (shift < 28) {
                require(pos < data.size) { "varint 数据不完整" }
                val b = data[pos].toInt() and 0xFF
                pos++
                acc = acc or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 != 0) return acc
                shift += 7
            }
            throw IllegalArgumentException("varint 格式非法")
        }
    }

    fun tlvEncode(tag: Long, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varintEncode(tag))
        out.write(varintEncode(value.size.toLong()))
        out.write(value)
        return out.toByteArray()
    }

    fun tlvList(data: ByteArray): List<Pair<Long, ByteArray>> {
        val items = ArrayList<Pair<Long, ByteArray>>()
        val reader = VarintReader(data)
        while (reader.pos < data.size) {
            if (items.size >= 64) throw IllegalArgumentException("TLV 条目过多")
            val tag = reader.read()
            val size = reader.read().toInt()
            require(size >= 0 && reader.pos + size <= data.size) { "TLV 长度非法" }
            items.add(tag to data.copyOfRange(reader.pos, reader.pos + size))
            reader.pos += size
        }
        return items
    }

    fun tlvGet(data: ByteArray, tag: Long): ByteArray? =
        tlvList(data).firstOrNull { it.first == tag }?.second

    /** 要求整段数据恰好是一个指定 tag 的 TLV。 */
    fun requireTlv(data: ByteArray, tag: Long): ByteArray {
        val reader = VarintReader(data)
        val t = reader.read()
        val size = reader.read().toInt()
        require(t == tag && reader.pos + size == data.size) { "TLV 结构不符合预期（tag=$tag）" }
        return data.copyOfRange(reader.pos, reader.pos + size)
    }

    fun frameEncode(module: Int, sequence: Long, flags: Int, payload: ByteArray): ByteArray {
        require(payload.size <= FRAME_MAX_ENVELOPE) { "帧载荷过大" }
        val out = ByteArrayOutputStream()
        putU32le(out, FRAME_MAGIC_WORD)
        putU32le(out, module)
        putU64le(out, sequence)
        putU32le(out, flags)
        putU32le(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    data class FrameHeader(val magic: Int, val module: Int, val sequence: Long, val flags: Int, val length: Int)

    fun frameDecode(data: ByteArray): FrameHeader {
        require(data.size >= FRAME_HEADER_SIZE) { "帧头不完整" }
        val magic = u32le(data, 0)
        require(magic == FRAME_MAGIC_WORD) { "帧魔数错误" }
        val module = u32le(data, 4)
        val sequence = u64le(data, 8)
        val flags = u32le(data, 16)
        val length = u32le(data, 20)
        if (length > FRAME_MAX_PAYLOAD && module != ENVELOPE_MODULE) {
            throw IllegalArgumentException("非信封通道的超大帧")
        }
        if (length > FRAME_MAX_ENVELOPE) throw IllegalArgumentException("帧长度超出上限")
        return FrameHeader(magic, module, sequence, flags, length)
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    /** JSON 字符串转义，字段里不能出现裸引号。 */
    fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 按字符（不是字节）截断。 */
    fun clipChars(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max)
}
