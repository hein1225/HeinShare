package com.heinshare.core

import java.io.ByteArrayOutputStream

/**
 * 协议原语：信封（module 9）、通道帧（module 21）、DFile 数据面。
 *
 * 与 Rust 版 `src/src/auth.rs`、`src/src/transfer.rs` 一一对应，字节级兼容。
 */
object Protocol {
    const val PKG_NAME = "ohos.InterConnection.iShare"
    const val SRC_BUS = "IShareEcologyAuthSession"

    /** 0xFFFF0004（按 Int 位模式保存）。 */
    const val CHANNEL_BYTES: Int = -65532
    /** 0xFFFF0002。 */
    const val PEER_META_FRAME: Int = -65534
    /** 0xFFFF0008。 */
    const val PEER_META_ACK_FRAME: Int = -65528
    val PEER_META_ACK_JSON: ByteArray = "META_DEVICE_INFO_ACK\u0000".toByteArray(Charsets.UTF_8)
    const val BYTES_FLAG_REQUEST = 0x94

    const val MODULE_AUTH = 1
    const val MODULE_OPENING = 8
    const val MODULE_ENVELOPE = 9
    const val MODULE_CHANNEL = 21

    // ------------------------------------------------------------- 信封

    fun openingPayload(identity: String): ByteArray {
        val body = buildString {
            append("{\"CODE\":4,\"DEVICE_ID\":\"").append(identity)
            append("\",\"PEER_NETWORK_ID\":\"").append(identity)
            append("\",\"PKG_NAME\":\"").append(PKG_NAME)
            append("\",\"SRC_BUS_NAME\":\"").append(SRC_BUS)
            append("\",\"DST_BUS_NAME\":\"").append(SRC_BUS)
            append("\",\"REQ_ID\":\"0\",\"API_VERSION\":2,\"MTU_SIZE\":39968,\"ROUTE_TYPE\":2}")
        }
        val payload = body.toByteArray(Charsets.UTF_8) + 0
        return Bytes.frameEncode(MODULE_OPENING, 1, 0, payload)
    }

    /**
     * opening ACK。
     *
     * 与 Rust 版 `receive/opening_payload.rs::build_opening_ack` 一致：
     * 这里回给对方的 DEVICE_ID / PEER_NETWORK_ID 必须是 **小写** 十六进制，
     * 而 opening 请求里用的是大写（identity_v2 = hex_upper）。大小写不一致会被对端判定非法。
     */
    fun openingAck(identity: String): ByteArray {
        val id = identity.lowercase()
        val body = buildString {
            append("{\"CODE\":4,\"DEVICE_ID\":\"").append(id)
            append("\",\"PEER_NETWORK_ID\":\"").append(id)
            append("\",\"API_VERSION\":2,\"PKG_NAME\":\"").append(PKG_NAME)
            append("\",\"SRC_BUS_NAME\":\"ohos.InterConnection.iShare\"")
            append(",\"DST_BUS_NAME\":\"IShareEcologyAuthSession\"")
            append(",\"REQ_ID\":\"0\",\"MTU_SIZE\":39968,\"ROUTE_TYPE\":2}")
        }
        val payload = body.toByteArray(Charsets.UTF_8) + 0
        return Bytes.frameEncode(MODULE_OPENING, 0, 1, payload)
    }

    fun localCapabilityFrame(deviceHash: ByteArray, osVersion: String, machine: String): ByteArray {
        val fields = Bytes.concat(
            Bytes.tlvEncode(1, "1".toByteArray()),
            Bytes.tlvEncode(2, "1.0.0".toByteArray()),
            Bytes.tlvEncode(3, osVersion.toByteArray()),
            Bytes.tlvEncode(4, machine.toByteArray())
        )
        return sealHandshakeEnvelope(deviceHash, Bytes.tlvEncode(2, fields), 2)
    }

    fun capabilitiesPhone(deviceHash: ByteArray): ByteArray {
        val fields = Bytes.concat(
            Bytes.tlvEncode(1, ByteArray(0)),
            Bytes.tlvEncode(5, "TRUE".toByteArray()),
            Bytes.tlvEncode(6, "ENABLE".toByteArray())
        )
        return sealHandshakeEnvelope(deviceHash, Bytes.tlvEncode(2, fields), 2)
    }

    fun sealHandshakeEnvelope(deviceHash: ByteArray, payload: ByteArray, sequence: Long): ByteArray {
        val envelope = Bytes.concat(
            deviceHash,
            byteArrayOf(0x81.toByte(), 0xE5.toByte(), 0x80.toByte(), 0x80.toByte()),
            Bytes.varintEncode(payload.size.toLong()),
            payload
        )
        return Bytes.frameEncode(MODULE_ENVELOPE, sequence, 0, envelope)
    }

    fun envelopeMode(frame: ByteArray): Int? {
        if (frame.size < Bytes.FRAME_HEADER_SIZE + 16) return null
        if (Bytes.u32le(frame, 4) != MODULE_ENVELOPE) return null
        val payload = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size)
        val reader = Bytes.VarintReader(payload, 16)
        return try {
            val magic = reader.read()
            val version = reader.read()
            val mode = reader.read()
            if (magic != 1L || version != 101L) null else mode.toInt()
        } catch (e: Exception) {
            null
        }
    }

    /** 返回 (mode, body)。 */
    fun openEnvelope(frame: ByteArray): Pair<Int, ByteArray> {
        require(frame.size >= Bytes.FRAME_HEADER_SIZE) { "帧不完整" }
        require(Bytes.u32le(frame, 4) == MODULE_ENVELOPE) { "不是信封通道" }
        val payload = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size)
        require(payload.size >= 16) { "信封太短" }
        val reader = Bytes.VarintReader(payload, 16)
        val magic = reader.read()
        val version = reader.read()
        val mode = reader.read()
        val metaLen = reader.read()
        require(magic == 1L && version == 101L) { "信封 magic/version 不对" }
        var offset = reader.pos + metaLen.toInt()
        require(offset <= payload.size) { "信封 meta 越界" }
        reader.pos = offset
        val size = reader.read().toInt()
        require(reader.pos + size == payload.size) { "信封长度不自洽" }
        return mode.toInt() to payload.copyOfRange(reader.pos, reader.pos + size)
    }

    fun sendPublicKey(deviceHash: ByteArray, publicDer: ByteArray): ByteArray {
        val body = Bytes.tlvEncode(6, Bytes.tlvEncode(1, publicDer))
        return sealHandshakeEnvelope(deviceHash, body, 3)
    }

    fun extractPeerPublic(peerResponse: ByteArray): ByteArray {
        val (mode, body) = openEnvelope(peerResponse)
        require(mode == 0) { "对端公钥信封 mode=$mode" }
        return Bytes.requireTlv(Bytes.requireTlv(body, 6), 2)
    }

    /** 能力回应里的 secure_version（bit0=1 表示对端用 MGF1-SHA1）。 */
    fun secureVersion(capabilityResponse: ByteArray): Int {
        val (mode, body) = openEnvelope(capabilityResponse)
        require(mode == 0) { "能力回应 mode=$mode" }
        val fields = Bytes.requireTlv(body, 3)
        val reader = Bytes.VarintReader(fields, 0)
        val tag = reader.read()
        val size = reader.read().toInt()
        require(tag == 1L && size <= 10 && reader.pos + size <= fields.size) { "能力字段结构不对" }
        val text = String(fields, reader.pos, size, Charsets.UTF_8)
        val value = if (text.isEmpty()) 0 else text.trim().toIntOrNull() ?: 0
        return value and 1
    }

    // ------------------------------------------------- 加密信封（module 9, mode 2）

    /** AES-256-GCM，16 字节 nonce。 */
    fun encryptedEnvelope(deviceHash: ByteArray, key: ByteArray, body: ByteArray): ByteArray {
        val nonce = Crypto.randomBytes(16)
        val metadata = Bytes.tlvEncode(1, nonce)
        val ciphertext = Crypto.aesGcmEncrypt(key, nonce, body) ?: error("GCM 加密失败")
        return Bytes.concat(
            deviceHash,
            byteArrayOf(0x81.toByte(), 0xE5.toByte(), 0x82.toByte()),
            Bytes.varintEncode(metadata.size.toLong()),
            metadata,
            Bytes.varintEncode(ciphertext.size.toLong()),
            ciphertext
        )
    }

    fun sealEncryptedFrame(deviceHash: ByteArray, key: ByteArray, body: ByteArray, sequence: Long): ByteArray =
        Bytes.frameEncode(MODULE_ENVELOPE, sequence, 0, encryptedEnvelope(deviceHash, key, body))

    fun openChannelFrame(frame: ByteArray, key: ByteArray): ByteArray {
        val (mode, ciphertext) = openEnvelope(frame)
        require(mode == 2) { "不是加密信封（mode=$mode）" }
        val payload = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size)
        val reader = Bytes.VarintReader(payload, 16)
        var metaLen = 0L
        repeat(4) { metaLen = reader.read() }
        require(reader.pos + metaLen <= payload.size) { "信封 meta 越界" }
        val metadata = payload.copyOfRange(reader.pos, reader.pos + metaLen.toInt())
        val nonce = Bytes.requireTlv(metadata, 1)
        require(nonce.size == 12 || nonce.size == 16) { "nonce 长度非法" }
        return Crypto.aesGcmDecrypt(key, nonce, ciphertext)
            ?: throw IllegalArgumentException("信封解密失败（tag 不匹配）")
    }

    // -------------------------------------------------------- 通道帧（module 21）

    data class ChannelHeader(val h0: Int, val h1: Int, val h2: Long, val h3: Int, val h4: Int)

    fun sealChannelFrame(
        key: ByteArray,
        header: ChannelHeader,
        plain: ByteArray,
        sequence: Long
    ): ByteArray {
        val nonce = Crypto.randomBytes(12)
        val sealed = Crypto.aesGcmEncrypt(key, nonce, plain) ?: error("GCM 加密失败")
        val payload = Bytes.concat(nonce, sealed)
        val inner = ByteArrayOutputStream()
        Bytes.putU32le(inner, header.h0)
        Bytes.putU32le(inner, header.h1)
        Bytes.putU64le(inner, header.h2)
        Bytes.putU32le(inner, header.h3)
        Bytes.putU32le(inner, payload.size)
        inner.write(payload)
        return Bytes.frameEncode(MODULE_CHANNEL, sequence, 0, inner.toByteArray())
    }

    fun parseChannelFrame(frame: ByteArray, key: ByteArray): Pair<ChannelHeader, ByteArray> {
        require(frame.size >= 2 * Bytes.FRAME_HEADER_SIZE + 28) { "通道帧过短" }
        require(Bytes.u32le(frame, 4) == MODULE_CHANNEL) { "不是通道帧" }
        val inner = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size)
        val header = ChannelHeader(
            Bytes.u32le(inner, 0),
            Bytes.u32le(inner, 4),
            Bytes.u64le(inner, 8),
            Bytes.u32le(inner, 16),
            Bytes.u32le(inner, 20)
        )
        require(header.h4 == inner.size - Bytes.FRAME_HEADER_SIZE) { "通道帧长度不自洽" }
        val nonce = inner.copyOfRange(24, 36)
        val sealed = inner.copyOfRange(36, inner.size)
        val plain = Crypto.aesGcmDecrypt(key, nonce, sealed) ?: throw IllegalArgumentException("通道帧解密失败")
        return header to plain
    }

    /** 无内层头的通道帧（部分对端会这么发）。 */
    fun decodeChannelHeaderless(frame: ByteArray, key: ByteArray): Pair<ChannelHeader, ByteArray> {
        require(frame.size >= Bytes.FRAME_HEADER_SIZE + 12 + 17) { "通道帧过短" }
        require(Bytes.u32le(frame, 4) == MODULE_CHANNEL) { "不是通道帧" }
        val nonce = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, Bytes.FRAME_HEADER_SIZE + 12)
        val sealed = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE + 12, frame.size)
        val plain = Crypto.aesGcmDecrypt(key, nonce, sealed) ?: throw IllegalArgumentException("通道帧解密失败")
        return ChannelHeader(CHANNEL_BYTES, 0, 0L, 0, plain.size) to plain
    }

    /**
     * 解析「裸内层」通道帧：对端直接把 sealChannelFrame 的内层（24 字节头 + nonce + 密文）
     * 发出来，外面没有 MODULE_CHANNEL 的 24 字节外层头。
     *
     * byte 通道（预览会话）的握手应答就是这种形态，对应 Rust 版
     * `outbound/session.rs` 里的 `frame_encode(21, 0, 0, &raw)` 二次包裹技巧。
     */
    fun parseInnerChannelFrame(frame: ByteArray, key: ByteArray): Pair<ChannelHeader, ByteArray> {
        require(frame.size >= Bytes.FRAME_HEADER_SIZE + 12 + 17) { "内层帧过短" }
        val header = ChannelHeader(
            Bytes.u32le(frame, 0),
            Bytes.u32le(frame, 4),
            Bytes.u64le(frame, 8),
            Bytes.u32le(frame, 16),
            Bytes.u32le(frame, 20)
        )
        require(header.h4 == frame.size - Bytes.FRAME_HEADER_SIZE) {
            "内层帧长度不自洽（h4=${header.h4} 实际=${frame.size - Bytes.FRAME_HEADER_SIZE}）"
        }
        val nonce = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, Bytes.FRAME_HEADER_SIZE + 12)
        val sealed = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE + 12, frame.size)
        val plain = Crypto.aesGcmDecrypt(key, nonce, sealed)
            ?: throw IllegalArgumentException("内层帧解密失败")
        return header to plain
    }

    // ------------------------------------------------------------- DFile 数据面

    enum class Cipher { CLEAR, AES128_GCM, CHACHA20_POLY1305 }

    const val CIPHER_CLEAR = 0x1
    const val CIPHER_CHACHA = 0x2
    const val CIPHER_AES = 0x4
    const val OUR_CIPHER_CAPS = CIPHER_CLEAR or CIPHER_CHACHA or CIPHER_AES

    /** DFile_AAD：64 个 'A'。 */
    val DFILE_AAD: ByteArray = ByteArray(64) { 'A'.code.toByte() }

    const val DFRAME_OVERHEAD = 50
    const val DFILE_MAX_CONTENT = 65535 - DFRAME_OVERHEAD
    const val MAX_MANIFEST_ENTRIES = 4096

    fun chooseCipher(peerCaps: Int?, peerIsApple: Boolean): Cipher {
        if (peerCaps == null) return if (peerIsApple) Cipher.CHACHA20_POLY1305 else Cipher.AES128_GCM
        val hasAes = peerCaps and CIPHER_AES != 0
        val hasChacha = peerCaps and CIPHER_CHACHA != 0
        return when {
            !hasAes && hasChacha -> Cipher.CHACHA20_POLY1305
            hasAes -> Cipher.AES128_GCM
            hasChacha -> Cipher.CHACHA20_POLY1305
            else -> Cipher.CLEAR
        }
    }

    fun settingsCipherCaps(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        val t = payload.copyOfRange(payload.size - 4, payload.size)
        return if (payload.size >= 103) {
            Bytes.u32le(t, 0)
        } else {
            Bytes.u32be(t, 0)
        }
    }

    class Aead(var cipher: Cipher = Cipher.AES128_GCM, var peerIsApple: Boolean = false) {
        fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray = when (cipher) {
            Cipher.CLEAR -> plain
            Cipher.AES128_GCM ->
                Crypto.aesGcmEncrypt(key.copyOf(16), nonce, plain, DFILE_AAD) ?: error("GCM 失败")
            Cipher.CHACHA20_POLY1305 ->
                Crypto.chachaAeadEncrypt(key.copyOf(32), nonce, DFILE_AAD, plain)
        }

        fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray? = when (cipher) {
            Cipher.CLEAR -> sealed
            Cipher.AES128_GCM ->
                Crypto.aesGcmDecrypt(key.copyOf(16), nonce, sealed, DFILE_AAD)
            Cipher.CHACHA20_POLY1305 ->
                Crypto.chachaAeadDecrypt(key.copyOf(32), nonce, DFILE_AAD, sealed)
        }
    }

    fun assembleDfilePacket(kind: Int, flags: Int, session: Int, transaction: Int, payload: ByteArray): ByteArray {
        require(kind in 1..31) { "非法 DFile kind=$kind" }
        require(payload.size <= 65535) { "DFile 载荷过大" }
        val out = ByteArrayOutputStream()
        out.write(kind)
        out.write(flags)
        Bytes.putU16be(out, session)
        Bytes.putU16be(out, transaction)
        Bytes.putU16be(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    data class DfileHeader(val kind: Int, val flags: Int, val session: Int, val transaction: Int, val size: Int)

    fun parseDfileHeader(data: ByteArray): DfileHeader {
        require(data.size >= 8) { "DFile 帧过短" }
        val kind = data[0].toInt() and 0xFF
        require(kind in 1..31) { "非法 DFile kind=$kind" }
        return DfileHeader(
            kind,
            data[1].toInt() and 0xFF,
            Bytes.u16be(data, 2),
            Bytes.u16be(data, 4),
            Bytes.u16be(data, 6)
        )
    }

    /** DFile 时间戳：鸿蒙用 Unix 微秒，Apple 用开机微秒。 */
    fun dfileStamp(peerIsApple: Boolean): Long =
        if (peerIsApple) System.nanoTime() / 1000
        else System.currentTimeMillis() * 1000

    fun dfileSealControl(aead: Aead, key: ByteArray, packet: ByteArray): ByteArray {
        require(packet.size >= 8) { "控制帧过短" }
        val h = parseDfileHeader(packet)
        require(h.size == packet.size - 8) { "控制帧长度不自洽" }
        if (aead.cipher == Cipher.CLEAR) return packet
        val nonce = Crypto.randomBytes(12)
        val stamp = ByteArray(8)
        val stampValue = dfileStamp(aead.peerIsApple)
        for (i in 0 until 8) stamp[7 - i] = ((stampValue ushr (i * 8)) and 0xFF).toByte()
        val plainFull = Bytes.concat(stamp, packet)
        val sealed = aead.seal(key, nonce, plainFull)
        val out = ByteArrayOutputStream()
        out.write(h.kind)
        out.write(h.flags)
        Bytes.putU16be(out, h.session)
        Bytes.putU16be(out, h.transaction)
        Bytes.putU16be(out, sealed.size + 12)
        out.write(sealed)
        out.write(nonce)
        return out.toByteArray()
    }

    fun openControlBlock(aead: Aead, key: ByteArray, header: DfileHeader, payload: ByteArray): ByteArray {
        if (aead.cipher == Cipher.CLEAR) {
            val inner = parseDfileHeader(payload)
            require(inner.kind == header.kind && inner.flags == header.flags &&
                inner.session == header.session && inner.transaction == header.transaction) { "控制块头不匹配" }
            return payload
        }
        require(payload.size >= 44) { "控制块过短" }
        val split = payload.size - 12
        val nonce = payload.copyOfRange(split, payload.size)
        val sealed = payload.copyOfRange(0, split)
        val plain = aead.open(key, nonce, sealed) ?: throw IllegalArgumentException("控制块解密失败")
        require(plain.size >= 16) { "控制块明文过短" }
        val inner = parseDfileHeader(plain.copyOfRange(8, plain.size))
        require(inner.kind == header.kind && inner.flags == header.flags &&
            inner.session == header.session && inner.transaction == header.transaction &&
            inner.size == plain.size - 16) { "控制块内容不匹配" }
        return plain.copyOfRange(8, plain.size)
    }

    fun buildDataBlock(
        aead: Aead,
        key: ByteArray,
        content: ByteArray,
        sequence: Int,
        flags: Int,
        fileId: Int,
        trans: Int
    ): ByteArray {
        require(content.isNotEmpty() && content.size <= DFILE_MAX_CONTENT) { "数据块长度非法" }
        require(flags == 0 || flags == 1 || flags == 3) { "数据块 flags 非法" }
        require(fileId != 0) { "fileId 不能为 0" }
        val nonce = Crypto.randomBytes(12)
        val plain = ByteArrayOutputStream()
        Bytes.putU16be(plain, fileId)
        Bytes.putU32be(plain, sequence)
        Bytes.putU16be(plain, trans)
        plain.write(content)
        val sealed = aead.seal(key, nonce, plain.toByteArray())
        val payload = ByteArrayOutputStream()
        Bytes.putU16be(payload, fileId)
        Bytes.putU32be(payload, sequence)
        payload.write(sealed)
        payload.write(nonce)
        return assembleDfilePacket(4, flags, 0, trans, payload.toByteArray())
    }

    /** 返回 (fileId, sequence, content)。 */
    fun dfileParseChunk(aead: Aead, key: ByteArray, payload: ByteArray): Triple<Int, Int, ByteArray> {
        require(payload.size >= 6 + 16 + 12) { "数据块过短" }
        val fileId = Bytes.u16be(payload, 0)
        val sequence = Bytes.u32be(payload, 2)
        val rest = payload.copyOfRange(6, payload.size)
        val split = rest.size - 12
        val sealed = rest.copyOfRange(0, split)
        val nonce = rest.copyOfRange(split, rest.size)
        val plain = aead.open(key, nonce, sealed) ?: throw IllegalArgumentException("数据块解密失败")
        require(plain.size >= 8) { "数据块明文过短" }
        return Triple(fileId, sequence, plain.copyOfRange(8, plain.size))
    }

    /**
     * 依次尝试多种密码解密控制块。
     *
     * 对端挑密码的规则和本端不完全一致（它看的是「对端声明的能力位」），
     * 真实设备上两边偶尔会算出不同的套件，所以这里按优先级逐个试。
     */
    fun openControlBlockAny(
        key: ByteArray,
        header: DfileHeader,
        payload: ByteArray,
        preferred: Cipher
    ): ByteArray {
        val order = linkedSetOf(preferred, Cipher.AES128_GCM, Cipher.CHACHA20_POLY1305)
        for (c in order) {
            try {
                return openControlBlock(Aead(c, false), key, header, payload)
            } catch (e: Exception) {
                // 换下一个套件
            }
        }
        return openControlBlock(Aead(Cipher.CLEAR, false), key, header, payload)
    }

    /** 同上：数据块按多种密码依次尝试，最后按明文兜底。 */
    fun dfileParseChunkAny(
        key: ByteArray,
        payload: ByteArray,
        preferred: Cipher
    ): Triple<Int, Int, ByteArray> {
        val order = linkedSetOf(preferred, Cipher.AES128_GCM, Cipher.CHACHA20_POLY1305)
        for (c in order) {
            try {
                return dfileParseChunk(Aead(c, false), key, payload)
            } catch (e: Exception) {
            }
        }
        require(payload.size >= 6 + 8 + 12) { "数据块过短" }
        val fileId = Bytes.u16be(payload, 0)
        val sequence = Bytes.u32be(payload, 2)
        val body = payload.copyOfRange(6, payload.size - 12)
        return Triple(fileId, sequence, body.copyOfRange(8, body.size))
    }

    // ------------------------------------------------------------- Settings

    class SettingsProfile(
        val encryptHeaders: Boolean,
        val mtu: Int,
        val connectionType: Int,
        val version: Int,
        val abmCapability: Int,
        val capability: Int,
        val dataFrameSize: Int,
        val capsCheck: Int,
        val tail0: Int,
        val tail1: Int,
        val name: String = "",
        val exactTail: Triple<Int, Int, Int>? = null
    ) {
        fun encode(): ByteArray {
            val payload = ByteArrayOutputStream()
            Bytes.putU16be(payload, mtu)
            Bytes.putU16be(payload, connectionType)
            Bytes.putU32be(payload, version)
            Bytes.putU32be(payload, abmCapability)
            Bytes.putU32be(payload, capability)
            Bytes.putU32be(payload, dataFrameSize)
            Bytes.putU32be(payload, capsCheck)
            val exact = exactTail
            if (exact != null) {
                val nameBytes = ByteArray(65)
                val nb = name.toByteArray(Charsets.UTF_8)
                System.arraycopy(nb, 0, nameBytes, 0, minOf(nb.size, 65))
                payload.write(nameBytes)
                payload.write(if (encryptHeaders) 1 else 0)
                payload.write(0)
                Bytes.putU32le(payload, exact.first)
                Bytes.putU32le(payload, exact.second)
                Bytes.putU32le(payload, exact.third)
            } else {
                payload.write(ByteArray(67))
                payload.write(if (encryptHeaders) 1 else 0)
                Bytes.putU32be(payload, tail0)
                Bytes.putU32be(payload, tail1)
            }
            return assembleDfilePacket(8, 0, 0, 0, payload.toByteArray())
        }
    }

    fun senderBaseline(mtu: Int, dataFrameSize: Int) = SettingsProfile(
        encryptHeaders = true, mtu = mtu, connectionType = 2, version = 1,
        abmCapability = 0, capability = 0, dataFrameSize = dataFrameSize,
        capsCheck = 0x144, tail0 = 0, tail1 = 1
    )

    fun receiverSettings(encryptHeaders: Boolean, mtu: Int, dataFrameSize: Int) = SettingsProfile(
        encryptHeaders = encryptHeaders, mtu = mtu, connectionType = 2, version = 1,
        abmCapability = 0, capability = 0, dataFrameSize = dataFrameSize,
        capsCheck = if (encryptHeaders) 0x100 else 0, tail0 = 0, tail1 = OUR_CIPHER_CAPS
    )

    fun phoneExact(dataFrameSize: Int) = SettingsProfile(
        encryptHeaders = true, mtu = 1472, connectionType = 2, version = 1,
        abmCapability = 0, capability = 0x40, dataFrameSize = dataFrameSize,
        capsCheck = 0x36C, tail0 = 10, tail1 = 7, name = "unknown",
        exactTail = Triple(1, 10, OUR_CIPHER_CAPS)
    )

    // ------------------------------------------------------------- 文件清单

    data class ManifestEntry(val id: Int, val size: Long, val name: ByteArray)

    /** 按 1423 字节预算切帧，每帧 count 都是总文件数。 */
    fun buildHeaders(entries: List<ManifestEntry>, trans: Int): List<ByteArray> {
        require(entries.isNotEmpty() && entries.size <= MAX_MANIFEST_ENTRIES) { "清单条目数非法" }
        val count = entries.size
        val frames = ArrayList<ByteArray>()
        val budget = 1423
        var payload = ByteArrayOutputStream()
        Bytes.putU16be(payload, count)
        var inFrame = 0

        for (e in entries) {
            require(e.id != 0 && e.name.isNotEmpty() && e.name.size <= 255) { "清单条目非法" }
            val unit = ByteArrayOutputStream()
            // 单元 = id(2) + size(8 BE) + name_len(2 BE) + name，共 12 字节头
            Bytes.putU16be(unit, e.id)
            for (i in 7 downTo 0) unit.write(((e.size ushr (i * 8)) and 0xFF).toInt())
            Bytes.putU16be(unit, e.name.size)
            unit.write(e.name)
            val unitBytes = unit.toByteArray()
            if (payload.size() + unitBytes.size > budget && inFrame > 0) {
                frames.add(assembleDfilePacket(1, 0, 0, trans, payload.toByteArray()))
                payload = ByteArrayOutputStream()
                Bytes.putU16be(payload, count)
                inFrame = 0
            }
            if (payload.size() + unitBytes.size > budget) {
                throw IllegalArgumentException("文件名过长：${e.name.size} 字节")
            }
            payload.write(unitBytes)
            inFrame++
        }
        if (inFrame > 0) frames.add(assembleDfilePacket(1, 0, 0, trans, payload.toByteArray()))
        return frames
    }

    data class IncomingFile(val id: Int, val size: Long, val name: String)

    /** 合并对端发来的清单（重传时会分片到达）。 */
    fun mergeManifest(
        expected: MutableList<IncomingFile>,
        empty: MutableList<Pair<Int, String>>,
        data: ByteArray
    ): Int {
        if (data.size < 2) return 0
        val seen = HashSet<Int>()
        for (f in expected) seen.add(f.id)
        for (f in empty) seen.add(f.first)
        var offset = 2
        var added = 0
        while (offset + 12 <= data.size) {
            val id = Bytes.u16be(data, offset)
            var size = 0L
            for (i in 0 until 8) size = (size shl 8) or (data[offset + 2 + i].toLong() and 0xFF)
            val nlen = Bytes.u16be(data, offset + 10)
            offset += 12
            if (offset + nlen > data.size) break
            val name = String(data, offset, nlen, Charsets.UTF_8)
            offset += nlen
            if (!seen.add(id)) continue
            if (size == 0L) {
                empty.add(id to name)
                continue
            }
            if (name.isBlank()) continue
            if (expected.size + empty.size >= MAX_MANIFEST_ENTRIES) break
            expected.add(IncomingFile(id, size, name))
            added++
        }
        return added
    }

    // ------------------------------------------------------------- 字节通道帧

    fun bytesFrame(key: ByteArray, plain: ByteArray, sequence: Int): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 4) nonce[i] = ((sequence ushr (i * 8)) and 0xFF).toByte()
        val rest = Crypto.randomBytes(8)
        System.arraycopy(rest, 0, nonce, 4, 8)
        val sealed = Crypto.aesGcmEncrypt(key, nonce, plain) ?: error("GCM 失败")
        val payload = Bytes.concat(nonce, sealed)
        val out = ByteArrayOutputStream()
        Bytes.putU32le(out, Bytes.FRAME_MAGIC_WORD)
        Bytes.putU32le(out, sequence)
        Bytes.putU32le(out, 0)
        Bytes.putU32le(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }
}
