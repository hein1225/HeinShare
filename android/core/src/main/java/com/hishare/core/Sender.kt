package com.hishare.core

import java.io.InputStream
import java.net.ServerSocket
import java.util.Base64

/** 待发送的文件（可以是 SAF 的 Uri，也可以是普通文件）。 */
interface FileSource {
    val name: String
    val size: Long
    /** 相对路径（文件夹内文件用「目录名/子路径」）。 */
    val relPath: String
    fun openInput(): InputStream
}

data class SendReport(val files: List<String>, val bytes: Long)

class SendException(message: String) : Exception(message)

/**
 * 发送方：完整走一遍「opening → 能力 → 公钥 → 挑战 → offer → byte 通道 → file 通道 → 数据」。
 *
 * 对应 Rust 版 `src/src/outbound/`。
 */
class Sender(
    private val identity: Identity,
    private val log: (String) -> Unit,
    private val progress: (sent: Long, total: Long) -> Unit = { _, _ -> }
) {

    @Volatile var cancelled = false

    private fun checkCancel() {
        if (cancelled) throw SendException("已取消发送")
    }

    fun send(peerIp: String, peerPort: Int, files: List<FileSource>, displayName: String): SendReport {
        if (files.isEmpty()) throw SendException("没有要发送的文件")
        val total = files.sumOf { it.size }
        val control = Channel.connect(peerIp, peerPort, 8000)
        control.label = "control"
        try {
            val hs = handshake(control, peerIp)
            val offer = buildOffer(files.map { it.name }, total, displayName, hs.isTablet, 0)
            control.transmit(Protocol.sealEncryptedFrame(identity.deviceHashBytes, hs.key, offer, 5))
            log("已发出分享请求：${files.size} 个文件，${"%.1f".format(total / 1048576.0)} MB")
            return runSession(control, hs, files, total, displayName)
        } finally {
            control.close()
        }
    }

    // ------------------------------------------------------------- 握手

    private class Handshake(
        val channel: Channel,
        val key: ByteArray,
        val local: String,
        val identity: String,
        val nodeId: String,
        val isTablet: Boolean
    )

    private fun handshake(control: Channel, peerIp: String): Handshake {
        val deviceHash = identity.deviceHashBytes
        val publicDer = identity.publicDer
        val local = Net.localIPv4For(peerIp)
        log("本机地址：$local")

        control.transmit(Protocol.openingPayload(identity.identityV2))
        val ack = control.tryReadFrame(10000) ?: throw SendException("等待对方确认超时")
        val header = Bytes.frameDecode(ack)
        if (header.module != Protocol.MODULE_OPENING || header.flags != 1) {
            throw SendException("对方拒绝建立通道（可能正忙）")
        }
        val ackText = String(ack, Bytes.FRAME_HEADER_SIZE, ack.size - Bytes.FRAME_HEADER_SIZE, Charsets.UTF_8)
        val routeType = Regex("\"ROUTE_TYPE\":").find(ackText)?.let {
            ackText.substring(it.range.last + 1).takeWhile { ch -> ch.isDigit() }.toIntOrNull()
        } ?: 0
        val isTablet = routeType == 2
        log(if (isTablet) "对端 ROUTE_TYPE=2，按 Apple 档处理" else "对端 ROUTE_TYPE=$routeType，按鸿蒙档处理")

        val capBytes = if (isTablet) {
            Protocol.capabilitiesPhone(deviceHash)
        } else {
            Protocol.localCapabilityFrame(deviceHash, "10.0", "ARM64")
        }
        control.transmit(capBytes)

        var capabilityResponse = control.tryReadFrame(12000) ?: throw SendException("对方未回应能力协商")
        val isProbe = Protocol.envelopeMode(capabilityResponse) == 2
        if (!isProbe) {
            val (mode, body) = Protocol.openEnvelope(capabilityResponse)
            val fields = Bytes.tlvList(body)
            val hasTag3 = fields.any { it.first == 3L }
            val isNextRecv = fields.any { it.first == 1L && it.second.containsSubsequence("next_recv") }
            if (mode != 0 || (!hasTag3 && !isNextRecv)) throw SendException("对方未回应能力协商")
            if (isNextRecv) {
                log("收到 next_recv，等预交换探针")
                val deadline = System.currentTimeMillis() + 8000
                var got = false
                while (System.currentTimeMillis() < deadline) {
                    val f = control.tryReadFrame(200) ?: continue
                    if (Protocol.envelopeMode(f) == 2) {
                        capabilityResponse = f
                        got = true
                        break
                    }
                }
                if (!got) throw SendException("对方正忙（未收到预交换探针）")
            }
        } else {
            log("收到 mode=2 探针，直接发公钥")
        }

        control.transmit(Protocol.sendPublicKey(deviceHash, publicDer))

        var peerResponse: ByteArray? = null
        while (peerResponse == null) {
            val f = control.tryReadFrame(15000) ?: throw SendException("等待对端公钥超时")
            if (Protocol.envelopeMode(f) == 2) continue
            peerResponse = f
        }
        val peerDer = Protocol.extractPeerPublic(peerResponse)
        val peerKey = Crypto.publicFromDer(peerDer)

        val mgfSha1 = if (isTablet) false else {
            try { Protocol.secureVersion(capabilityResponse) == 1 } catch (e: Exception) { true }
        }

        val nonce = Crypto.randomBytes(16)
        val sessionNonceKey = Crypto.randomBytes(32)
        val inner = Bytes.concat(nonce, sessionNonceKey)
        val plaintext = Bytes.tlvEncode(6, Bytes.tlvEncode(3, inner))
        val ciphertext = Crypto.oaepEncrypt(peerKey, plaintext, mgfSha1)
        val meta = Bytes.tlvEncode(1, Crypto.randomBytes(8))
        val envelope = Bytes.concat(
            deviceHash,
            byteArrayOf(0x81.toByte(), 0xE5.toByte(), 0x83.toByte()),
            Bytes.varintEncode(meta.size.toLong()),
            meta,
            Bytes.varintEncode(ciphertext.size.toLong()),
            ciphertext
        )
        control.transmit(Bytes.frameEncode(Protocol.MODULE_ENVELOPE, 4, 0, envelope))

        val response = control.tryReadFrame(10000) ?: throw SendException("等待 RSA 回应超时")
        val (mode, body) = Protocol.openEnvelope(response)
        if (mode != 3) throw SendException("期望 RSA 回应（mode=$mode）")
        val decrypted = Crypto.oaepDecrypt(identity.privateKey, body, mgfSha1)
            ?: throw SendException("RSA 回应解密失败")
        val encryptedSession = Bytes.requireTlv(Bytes.requireTlv(decrypted, 6), 4)
        val session = Crypto.aesGcmDecrypt(sessionNonceKey, nonce, encryptedSession)
            ?: throw SendException("会话密钥解密失败")
        require(session.size == 32) { "会话密钥长度异常" }
        log("握手完成（mgf_sha1=$mgfSha1）")

        return Handshake(control, session, local, identity.identityV2, identity.nodeId, isTablet)
    }

    // ------------------------------------------------------------- 主流程

    private fun runSession(
        control: Channel,
        hs: Handshake,
        files: List<FileSource>,
        total: Long,
        displayName: String
    ): SendReport {
        val key = hs.key
        val deviceHash = identity.deviceHashBytes
        var sequence = 6L
        var exchangeSent = false
        var metaSent = false
        var code3Sent = false
        var fileRequestSent = false
        var byteChannel: Channel? = null
        var byteKey: ByteArray? = null
        var fileKey: ByteArray? = null
        val deadline = System.currentTimeMillis() + 120_000

        while (System.currentTimeMillis() < deadline) {
            checkCancel()
            val frame = control.tryReadFrame(200) ?: continue
            val module = Bytes.u32le(frame, 4)

            if (module == Protocol.MODULE_ENVELOPE) {
                val body = try { Protocol.openChannelFrame(frame, key) } catch (e: Exception) { continue }
                for ((tag, value) in Bytes.tlvList(body)) {
                    when (tag) {
                        1L -> {
                            val inner = try { Bytes.tlvList(value) } catch (e: Exception) { continue }
                            for ((_, cmd) in inner) {
                                val text = String(cmd, Charsets.UTF_8)
                                if (text == "user_reject" || text == "receiverCancel") {
                                    throw SendException("对方拒绝了本次分享")
                                }
                                if (text == "user_confirm") {
                                    log("对方已接受，正在建立数据通道")
                                    if (!exchangeSent) {
                                        sendEncrypted(control, deviceHash, key, netInfoOffer(hs.local), sequence++)
                                        sendEncrypted(control, deviceHash, key, logicalAddr(hs.local), sequence++)
                                        exchangeSent = true
                                    }
                                    if (hs.isTablet && !metaSent) {
                                        sendMeta(control, key, displayName, hs.nodeId, hs, sequence, null)
                                        sequence += 2
                                        metaSent = true
                                    }
                                }
                            }
                        }
                        4L -> sendEncrypted(control, deviceHash, key, netInfoReply(hs.local), sequence++)
                        10L -> sendEncrypted(control, deviceHash, key, logicalAddr(hs.local), sequence++)
                    }
                }
                continue
            }

            val (header, plain) = try {
                Protocol.parseChannelFrame(frame, key)
            } catch (e: Exception) {
                continue
            }

            if (header.h0 == Protocol.PEER_META_FRAME) {
                val text = String(plain, Charsets.UTF_8).trimEnd('\u0000')
                val obj = Json.parseObject(text)
                val name = obj?.str("DEVICE_NAME")
                if (!name.isNullOrEmpty()) log("对端设备：$name")
                sendMeta(control, key, displayName, hs.nodeId, hs, sequence, header)
                sequence += 2
                continue
            }

            if (header.h0 == Protocol.PEER_META_ACK_FRAME && !code3Sent) {
                val listener = ServerSocket(0, 1, java.net.InetAddress.getByName(hs.local))
                val port = listener.localPort
                Thread {
                    try {
                        listener.soTimeout = 180_000
                        listener.accept().close()
                    } catch (_: Exception) {}
                }.apply { isDaemon = true }.start()
                val payload = "{\"CODE\":3,\"P2P_IP\":\"${hs.local}\",\"P2P_PORT\":$port,\"PROTOCOL_TYPE\":4}"
                val body = payload.toByteArray(Charsets.UTF_8) + 0
                control.transmit(
                    Protocol.sealChannelFrame(
                        key,
                        Protocol.ChannelHeader(Protocol.CHANNEL_BYTES, 16, randomSeq(), 0, 0),
                        body,
                        sequence++
                    )
                )
                code3Sent = true
                continue
            }

            val text = String(plain, Charsets.UTF_8).trimEnd('\u0000', '\u0000')
            val obj = Json.parseObject(text) ?: continue

            if (header.h0 == Protocol.CHANNEL_BYTES && header.h1 == 16 && header.h3 == 1) {
                val ip = obj.str("P2P_IP") ?: ""
                val port = obj.num("P2P_PORT")?.toInt() ?: 0
                if (!Net.isPrivateIpv4(ip) || port == 0) throw SendException("对端 byte 通道地址不可用")
                val chan = Channel.connect(ip, port, 8000)
                chan.label = "byte"
                val bkey = Crypto.randomBytes(32)
                chan.transmit(byteHandshake(key, hs.identity, hs.nodeId, bkey, hs.isTablet))
                val raw = chan.tryReadFrame(8000) ?: throw SendException("byte 通道握手超时")
                val wrapped = Bytes.frameEncode(Protocol.MODULE_CHANNEL, 0, 0, raw)
                val (ackHeader, ackPlain) = Protocol.parseChannelFrame(wrapped, key)
                val ack = Json.parseObject(String(ackPlain, Charsets.UTF_8).trimEnd('\u0000', '\u0000'))
                    ?: throw SendException("byte 通道握手应答不是 JSON")
                if (ackHeader.h1 != 6 || (ackHeader.h3 != 0x95 && ackHeader.h3 != 0x91) ||
                    (ack.num("CODE") ?: 0L) != 1L) {
                    throw SendException("byte 通道握手被拒绝（flags=0x%02X）".format(ackHeader.h3))
                }
                log("预览通道已建立")
                byteChannel = chan
                byteKey = bkey

                val fkey = Crypto.randomBytes(32)
                val request = fileChannelRequest(hs.local, hs.identity, hs.nodeId, fkey, hs.isTablet)
                control.transmit(
                    Protocol.sealChannelFrame(
                        key,
                        Protocol.ChannelHeader(Protocol.CHANNEL_BYTES, 17, randomSeq(), 0, 0),
                        request,
                        sequence++
                    )
                )
                fileKey = fkey
                fileRequestSent = true
                continue
            }

            if (header.h0 == Protocol.CHANNEL_BYTES && header.h1 == 17 && header.h3 == 1 && fileRequestSent) {
                val ip = obj.str("MY_IP") ?: ""
                val port = obj.num("P2P_PORT")?.toInt() ?: 0
                if (!Net.isPrivateIpv4(ip) || port == 0) throw SendException("file 通道地址不可用")
                val fkey = fileKey ?: throw SendException("file 通道密钥缺失")
                val chan = Channel.connect(ip, port, 8000)
                chan.label = "file#1"

                val chunk = Protocol.DFILE_MAX_CONTENT
                val frameSize = (chunk + Protocol.DFRAME_OVERHEAD).coerceAtMost(65535)
                val profile = if (hs.isTablet) {
                    Protocol.phoneExact(frameSize)
                } else {
                    Protocol.senderBaseline(1472, frameSize)
                }
                chan.transmit(profile.encode())

                val reply = chan.tryReadDfile(10000) ?: throw SendException("对端未回应 DFile settings")
                val rh = Protocol.parseDfileHeader(reply)
                if (rh.kind != 8) throw SendException("DFile settings 应答异常 kind=${rh.kind}")
                val payload = reply.copyOfRange(8, 8 + rh.size)
                if (payload.size < 100 || Bytes.u16be(payload, 2) != 2) {
                    throw SendException("对端 DFile settings 档位不兼容")
                }
                if (!hs.isTablet && (Bytes.u32be(payload, 96).toLong() and 1L) == 0L) {
                    throw SendException("对端不支持加密传输")
                }
                val peerCaps = Protocol.settingsCipherCaps(payload)
                val aead = Protocol.Aead(Protocol.chooseCipher(peerCaps, hs.isTablet), hs.isTablet)
                log("文件通道已建立（密码：${aead.cipher}）")

                // 预览（可选，鸿蒙端要求 previewOK）
                val bchan = byteChannel
                val bkey = byteKey
                if (bchan != null && bkey != null) {
                    try {
                        sendPreview(bchan, bkey, key, deviceHash, files, hs)
                    } catch (e: Exception) {
                        log("预览未完成（不影响文件传输）：${e.message}")
                    }
                }

                val written = sendBatches(chan, fkey, aead, hs.isTablet, files, total)
                chan.gracefulClose(1200)
                return SendReport(files.map { it.name }, written)
            }
        }
        throw SendException(if (!code3Sent) "等待手机准备通道超时（对方可能未点接受）" else "通道协商超时")
    }

    // ------------------------------------------------------------- 数据面

    private fun sendBatches(
        chan: Channel,
        key: ByteArray,
        aead: Protocol.Aead,
        isApple: Boolean,
        files: List<FileSource>,
        total: Long
    ): Long {
        val maxPerBatch = 100
        var written = 0L
        var groupNo = 0
        var group = files
        while (group.isNotEmpty()) {
            checkCancel()
            groupNo++
            if (files.size > maxPerBatch) log("第 $groupNo 批：${group.size} 个文件（事务 $groupNo）")
            written += sendBatch(chan, key, aead, isApple, group, groupNo, total)
            group = files.drop(groupNo * maxPerBatch)
        }
        return written
    }

    private fun sendBatch(
        chan: Channel,
        key: ByteArray,
        aead: Protocol.Aead,
        isApple: Boolean,
        files: List<FileSource>,
        trans: Int,
        total: Long
    ): Long {
        if (isApple) {
            val congest = ByteArray(8 + 24)
            congest[0] = 11
            congest[7] = 24
            val body = byteArrayOf(
                0x00, 0x00, 0x01, 0xb0.toByte(), 0x00, 0x00, 0x01, 0xb0.toByte(),
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xea.toByte()
            )
            System.arraycopy(body, 0, congest, 8, body.size)
            chan.transmit(congest)
        }

        val entries = files.mapIndexed { i, f ->
            Protocol.ManifestEntry(i + 1, f.size, f.relPath.toByteArray(Charsets.UTF_8))
        }
        for (packet in Protocol.buildHeaders(entries, trans)) {
            chan.transmit(Protocol.dfileSealControl(aead, key, packet))
        }
        log("已发送文件清单：${files.size} 个")

        val sent = HashSet<Int>()
        val done = HashSet<Int>()
        var written = 0L
        val deadline = System.currentTimeMillis() + 3_600_000
        var lastActivity = System.currentTimeMillis()

        while (System.currentTimeMillis() < deadline) {
            checkCancel()
            var handled = false
            var rx = chan.tryReadDfile(1)
            while (rx != null) {
                handled = true
                val h = Protocol.parseDfileHeader(rx)
                val payload = rx.copyOfRange(8, 8 + h.size)
                when (h.kind) {
                    11 -> {}
                    2, 3 -> {
                        val inner = Protocol.openControlBlockAny(key, h, payload, aead.cipher)
                        for (id in parseIds(inner)) {
                            if (!sent.add(id)) continue
                            val file = files.getOrNull(id - 1) ?: continue
                            written += sendFileChunks(chan, key, aead, file, id, trans, total)
                            if (done.size >= files.size) {
                                finishBatch(chan, key, aead, trans)
                                return written
                            }
                        }
                    }
                    6 -> {
                        val inner = Protocol.openControlBlockAny(key, h, payload, aead.cipher)
                        for (id in parseIds(inner)) done.add(id)
                        log("[DONE] 收到 ${parseIds(inner).size} 个 id，累计 ${done.size}/${files.size}")
                        if (done.size >= files.size) {
                            finishBatch(chan, key, aead, trans)
                            return written
                        }
                        chan.transmit(buildDoneAck(key, aead, trans))
                    }
                    9 -> throw SendException("接收端中止了本次传输")
                    5 -> { }
                    22 -> Thread.sleep(20)
                    13 -> Thread.sleep(50)
                    else -> log("[未知帧] kind=${h.kind}，已忽略")
                }
                rx = chan.tryReadDfile(1)
            }
            if (handled) lastActivity = System.currentTimeMillis()
            if (System.currentTimeMillis() - lastActivity > 60_000) {
                throw SendException("等待接收端响应超时（已发 $written 字节）")
            }
            if (!handled) Thread.sleep(5)
        }
        throw SendException("批量传输超时")
    }

    private fun sendFileChunks(
        chan: Channel,
        key: ByteArray,
        aead: Protocol.Aead,
        file: FileSource,
        fileId: Int,
        trans: Int,
        total: Long
    ): Long {
        val chunk = Protocol.DFILE_MAX_CONTENT
        var sequence = 0
        var offset = 0L
        var written = 0L
        val batch = java.io.ByteArrayOutputStream()
        var batchContent = 0L
        val buffer = ByteArray(chunk)

        file.openInput().use { input ->
            while (true) {
                checkCancel()
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) break
                offset += filled
                val flag = if (offset >= file.size) 3 else if (sequence == 0) 0 else 1
                val frame = Protocol.buildDataBlock(aead, key, buffer.copyOf(filled), sequence, flag, fileId, trans)
                batch.write(frame)
                batchContent += filled
                written += filled
                progress(written, total)
                if (batch.size() >= 256 * 1024) {
                    chan.transmit(batch.toByteArray())
                    batch.reset()
                    batchContent = 0
                }
                sequence++
                if (offset >= file.size) break
            }
        }
        if (batch.size() > 0) chan.transmit(batch.toByteArray())
        return written
    }

    private fun buildDoneAck(key: ByteArray, aead: Protocol.Aead, trans: Int): ByteArray =
        Protocol.dfileSealControl(aead, key, Protocol.assembleDfilePacket(7, 0, 0, trans, ByteArray(0)))

    private fun finishBatch(chan: Channel, key: ByteArray, aead: Protocol.Aead, trans: Int) {
        val ack = buildDoneAck(key, aead, trans)
        repeat(3) {
            try { chan.transmit(ack) } catch (e: Exception) { return }
            Thread.sleep(15)
        }
    }

    private fun parseIds(inner: ByteArray): List<Int> {
        if (inner.size < 8) return emptyList()
        val data = inner.copyOfRange(8, inner.size)
        if (data.size % 2 != 0) return emptyList()
        val out = ArrayList<Int>(data.size / 2)
        var i = 0
        while (i + 2 <= data.size) {
            out.add(Bytes.u16be(data, i))
            i += 2
        }
        return out
    }

    // ------------------------------------------------------------- 辅助

    private fun sendEncrypted(control: Channel, deviceHash: ByteArray, key: ByteArray, body: ByteArray, seq: Long) {
        control.transmit(Protocol.sealEncryptedFrame(deviceHash, key, body, seq))
    }

    private fun netInfoOffer(local: String): ByteArray {
        val fields = Bytes.concat(
            Bytes.tlvEncode(1, ByteArray(0)),
            Bytes.tlvEncode(2, ByteArray(0)),
            Bytes.tlvEncode(3, ByteArray(0)),
            Bytes.tlvEncode(4, local.toByteArray(Charsets.UTF_8))
        )
        return Bytes.tlvEncode(4, fields)
    }

    private fun netInfoReply(local: String): ByteArray {
        val fields = Bytes.concat(
            Bytes.tlvEncode(1, ByteArray(0)),
            Bytes.tlvEncode(2, ByteArray(0)),
            Bytes.tlvEncode(3, "true".toByteArray()),
            Bytes.tlvEncode(4, local.toByteArray(Charsets.UTF_8))
        )
        return Bytes.tlvEncode(5, fields)
    }

    private fun logicalAddr(local: String): ByteArray =
        Bytes.tlvEncode(10, Bytes.tlvEncode(1, local.toByteArray(Charsets.UTF_8)))

    private fun byteHandshake(
        sessionKey: ByteArray,
        identity: String,
        nodeId: String,
        byteKey: ByteArray,
        isApple: Boolean
    ): ByteArray {
        val deviceId = if (isApple) nodeId else identity
        val clientBus: String
        val tc: Int
        val bt: Int
        val flags: Int
        if (isApple) {
            clientBus = "IShareSenderBytesSession_$identity"
            tc = 19
            bt = 2
            flags = 0x90
        } else {
            clientBus = "IShareSenderBytesSession"
            tc = 0
            bt = 1
            flags = Protocol.BYTES_FLAG_REQUEST
        }
        val payload = "{\"CODE\":1,\"API_VERSION\":2,\"BUS_NAME\":\"IShareReceiverBytesSession\"," +
            "\"CLIENT_BUS_NAME\":\"$clientBus\",\"PKG_NAME\":\"${Protocol.PKG_NAME}\",\"MTU_SIZE\":4194304," +
            "\"TRANS_CAPABILITY\":$tc,\"ROUTE_TYPE\":2,\"DEVICE_ID\":\"$deviceId\",\"BUSINESS_TYPE\":$bt," +
            "\"TRANS_FLAGS\":2,\"SESSION_KEY\":\"${Base64.getEncoder().encodeToString(byteKey)}\"}"
        val body = payload.toByteArray(Charsets.UTF_8) + 0
        val frame = Protocol.sealChannelFrame(
            sessionKey,
            Protocol.ChannelHeader(Bytes.FRAME_MAGIC_WORD, 6, randomSeq(), flags, 0),
            body,
            0
        )
        // byte 通道上传的是「内层」结构（24 字节头 + 载荷），与外层帧同形。
        return frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size)
    }

    private fun fileChannelRequest(
        local: String,
        identity: String,
        nodeId: String,
        fileKey: ByteArray,
        isApple: Boolean
    ): ByteArray {
        val key = Base64.getEncoder().encodeToString(fileKey)
        val deviceId = if (isApple) nodeId else identity
        val payload = if (isApple) {
            "{\"MY_CHANNEL_ID\":2,\"MY_IP\":\"$local\",\"DEVICE_ID\":\"$deviceId\",\"CODE\":1538,\"API_VERSION\":2," +
                "\"CHANNEL_TYPE\":1,\"STREAM_TYPE\":0,\"UDP_CONN_TYPE\":1,\"BUS_NAME\":\"IShareReceiverFileSession\"," +
                "\"BUSINESS_TYPE\":3,\"CLIENT_BUS_NAME\":\"IShareSenderFileSession_$identity\"," +
                "\"PKG_NAME\":\"${Protocol.PKG_NAME}\",\"TRANS_CAPABILITY\":19,\"UDP_CHANNEL_CAPABILITY\":0," +
                "\"SESSION_KEY\":\"$key\"}"
        } else {
            "{\"MY_CHANNEL_ID\":1,\"MY_IP\":\"$local\",\"DEVICE_ID\":\"$deviceId\",\"CODE\":1538,\"API_VERSION\":2," +
                "\"BUSINESS_TYPE\":3,\"STREAM_TYPE\":255,\"CHANNEL_TYPE\":1,\"UDP_CONN_TYPE\":1," +
                "\"BUS_NAME\":\"IShareReceiverFileSession\",\"CLIENT_BUS_NAME\":\"IShareSenderFileSession\"," +
                "\"PKG_NAME\":\"${Protocol.PKG_NAME}\",\"TRANS_CAPABILITY\":0,\"SESSION_KEY\":\"$key\"}"
        }
        return payload.toByteArray(Charsets.UTF_8) + 0
    }

    private fun sendMeta(
        control: Channel,
        key: ByteArray,
        displayName: String,
        nodeId: String,
        hs: Handshake,
        sequence: Long,
        peerHeader: Protocol.ChannelHeader?
    ) {
        val innerSeq = if (!hs.isTablet && peerHeader != null) peerHeader.h2 else randomSeq()
        val infoHeader = if (!hs.isTablet && peerHeader != null) peerHeader
        else Protocol.ChannelHeader(Protocol.PEER_META_FRAME, 5, innerSeq, 0, 0)
        val info = if (hs.isTablet) {
            "{\"DEVICE_NAME\":\"$displayName\",\"DEVICE_TYPE_ID\":12,\"FLAG\":100,\"META_AUTH_CAPA\":1," +
                "\"META_NODE_ID\":\"$nodeId\",\"TOKEN_ID\":\"0000000000000000000000000000000000000000000000000000000000000001\"}"
        } else {
            "{\"DEVICE_NAME\":\"$displayName\",\"DEVICE_TYPE_ID\":12,\"META_NODE_ID\":\"$nodeId\",\"FLAG\":100}"
        }
        control.transmit(Protocol.sealChannelFrame(key, infoHeader, info.toByteArray(Charsets.UTF_8), sequence))
        control.transmit(
            Protocol.sealChannelFrame(
                key,
                Protocol.ChannelHeader(Protocol.PEER_META_ACK_FRAME, 5, innerSeq, 0, 0),
                Protocol.PEER_META_ACK_JSON,
                sequence + 1
            )
        )
    }

    private fun sendPreview(
        chan: Channel,
        byteKey: ByteArray,
        sessionKey: ByteArray,
        deviceHash: ByteArray,
        files: List<FileSource>,
        hs: Handshake
    ) {
        val names = files.joinToString("|") { it.name }
        val stamps = files.joinToString(",") { System.currentTimeMillis().toString() }
        val details = files.joinToString(",") { "\"${formatUnixMs(System.currentTimeMillis())}\"" }
        val metadata = "{\"date_added\":[$stamps],\"date_taken\":[$stamps],\"detail_time\":[$details]}"
        val inner = Bytes.concat(
            Bytes.tlvEncode(11, names.toByteArray(Charsets.UTF_8)),
            Bytes.tlvEncode(13, metadata.toByteArray(Charsets.UTF_8))
        )
        val body = Bytes.tlvEncode(8, inner)
        val envelope = Protocol.encryptedEnvelope(deviceHash, sessionKey, body)
        chan.transmit(Protocol.bytesFrame(byteKey, envelope, 0))

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            checkCancel()
            val rx = chan.tryReadBytesFrame(byteKey, 200) ?: continue
            val wrapped = Bytes.frameEncode(Protocol.MODULE_ENVELOPE, 0, 0, rx.second)
            val plain = try { Protocol.openChannelFrame(wrapped, sessionKey) } catch (e: Exception) { continue }
            for ((tag, value) in Bytes.tlvList(plain)) {
                if (tag != 1L) continue
                val innerFields = try { Bytes.tlvList(value) } catch (e: Exception) { continue }
                for ((field, data) in innerFields) {
                    if (field == 8L && String(data, Charsets.UTF_8) == "previewOK") {
                        log("预览已确认")
                        return
                    }
                }
            }
        }
        log("对端未回 previewOK（不影响文件传输）")
    }

    private fun formatUnixMs(ms: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getDefault())
        cal.timeInMillis = ms
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND)
        )
    }

    private fun randomSeq(): Long = java.lang.Long.reverse(java.lang.Long.MIN_VALUE) + (System.nanoTime() and 0x7FFFFFFF)

    private fun buildOffer(
        names: List<String>,
        total: Long,
        displayName: String,
        isTablet: Boolean,
        folders: Int
    ): ByteArray {
        val count = names.size
        val sendType = when {
            folders > 0 -> "4"
            names.all { isGallery(it) } -> "0"
            else -> "3"
        }
        val histogram = LinkedHashMap<String, Int>()
        for (n in names) {
            val ext = n.substringAfterLast('.', "").lowercase()
            histogram[ext] = (histogram[ext] ?: 0) + 1
        }
        val summary = if (!isTablet) {
            histogram.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        } else {
            histogram.entries.joinToString(", ", "{  ", "}") {
                if (it.key.isEmpty()) "\"\": ${it.value}" else "\".${it.key}\" : ${it.value}"
            }
        }
        val fields = Bytes.concat(
            Bytes.tlvEncode(1, sendType.toByteArray()),
            Bytes.tlvEncode(2, displayName.toByteArray(Charsets.UTF_8)),
            Bytes.tlvEncode(3, count.toString().toByteArray()),
            Bytes.tlvEncode(4, total.toString().toByteArray()),
            Bytes.tlvEncode(5, folders.toString().toByteArray()),
            Bytes.tlvEncode(6, count.toString().toByteArray()),
            Bytes.tlvEncode(7, summary.toByteArray(Charsets.UTF_8)),
            Bytes.tlvEncode(16, if (isTablet) "1".toByteArray() else "0".toByteArray())
        )
        return Bytes.tlvEncode(7, fields)
    }

    private fun isGallery(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return GALLERY_EXTENSIONS.contains(ext)
    }

    companion object {
        private val GALLERY_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "tif", "tiff", "avif",
            "mp4", "mov", "mkv", "avi", "3gp", "m4v", "webm", "flv", "wmv", "mpg", "mpeg", "ts"
        )
    }
}

private fun ByteArray.containsSubsequence(needle: String): Boolean =
    containsSubsequence(needle.toByteArray(Charsets.UTF_8))

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    outer@ for (i in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}
