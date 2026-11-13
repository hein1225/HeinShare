package com.heinshare.core

import java.io.InputStream
import java.net.ServerSocket

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

    /** 上一次上报进度的时刻（用于节流）。 */
    private var lastProgressAt = 0L

    /** 对端已确认预览（previewOK）。由控制通道监视线程置位。 */
    @Volatile private var previewOk = false

    /** 控制通道监视线程的停止标记。 */
    @Volatile private var controlWatchStop = false

    /** 对端上报的接收进度（百分比），-1 表示还没收到。 */
    @Volatile private var peerPercent = -1.0


    private fun checkCancel() {
        if (cancelled) throw SendException("已取消发送")
    }

    /**
     * 发送入口。
     *
     * 带自动重试：华为手机在「华为分享」页面停留时间有限，偶尔会先回一个忙/超时，
     * 紧接着又能正常握手。Rust 版同样是 MAX_RETRY=2、退避 2 秒。
     * 只在**还没发出任何数据**时重试，避免对端收到半截文件。
     */
    fun send(peerIp: String, peerPort: Int, files: List<FileSource>, displayName: String): SendReport {
        if (files.isEmpty()) throw SendException("没有要发送的文件")
        var attempt = 0
        while (true) {
            try {
                return sendOnce(peerIp, peerPort, files, displayName)
            } catch (e: SendException) {
                if (cancelled) throw e
                if (e.message?.contains("已取消") == true) throw e
                if (sentAny || attempt >= MAX_RETRY) throw e
                attempt++
                log("${e.message}；2 秒后自动重试 $attempt/$MAX_RETRY")
                try { Thread.sleep(2000) } catch (_: InterruptedException) { throw e }
            }
        }
    }

    /** 这一次发送是否真的往对端写过文件数据（决定还能不能安全重试）。 */
    @Volatile private var sentAny = false

    private fun sendOnce(
        peerIp: String,
        peerPort: Int,
        files: List<FileSource>,
        displayName: String
    ): SendReport {
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
            // machine 与 Rust 版 outbound/session.rs 的 MACHINE 常量保持一致（"AMD64"）
            Protocol.localCapabilityFrame(deviceHash, "10.0", "AMD64")
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
            if (frame.size < Bytes.FRAME_HEADER_SIZE) {
                log("[控制帧] 长度不足：${frame.size} 字节，已忽略")
                continue
            }
            val module = Bytes.u32le(frame, 4)

            if (module == Protocol.MODULE_ENVELOPE) {
                val body = try { Protocol.openChannelFrame(frame, key) } catch (e: Exception) {
                    log("[控制帧] 信封无法解析：${e.message}")
                    continue
                }
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
                                    // 与 Rust 版一致：**只有 Apple（iPad）档**在收到 user_confirm 时
                                    // 就主动上报 META_INFO；鸿蒙档等对端先发 META 再回。
                                    if (hs.isTablet && !metaSent) {
                                        sendMeta(control, key, displayName, hs.nodeId, hs, sequence, null)
                                        sequence += 2
                                        metaSent = true
                                        log("已主动上报 META_INFO（iPad 后发，必须我们先）")
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
                log("[控制帧] 通道帧无法解析：${e.message}")
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
                log("收到 byte 通道地址：$ip:$port")
                // 预览通道（byte 通道）是**可选的**：鸿蒙端用它先看缩略图，
                // 但文件本体走的是 file 通道。所以这里握手失败不能让整次发送失败，
                // 否则对端实现稍有差异就整单发不出去。
                try {
                    val c = Channel.connect(ip, port, 8000)
                    c.label = "byte"
                    val bkey = Crypto.randomBytes(32)
                    c.transmit(byteHandshake(key, hs.identity, hs.nodeId, bkey, hs.isTablet))
                    val raw = c.tryReadRawChannelFrame(12000)
                    if (raw == null) {
                        log("预览通道无应答（已跳过，直接走文件通道）")
                        try { c.close() } catch (_: Exception) {}
                    } else {
                        // 对端可能回完整外层帧（带 module 头），也可能只回裸内层帧。
                        // 注意：两种形态首 4 字节都是 0xBABEFACE，不能只靠魔数判别。
                        val isOuter = raw.size >= Bytes.FRAME_HEADER_SIZE &&
                            Bytes.u32le(raw, 0) == Bytes.FRAME_MAGIC_WORD &&
                            Bytes.u32le(raw, 4) == Protocol.MODULE_CHANNEL
                        val (ackHeader, ackPlain) = if (isOuter) {
                            Protocol.parseChannelFrame(raw, key)
                        } else {
                            Protocol.parseInnerChannelFrame(raw, key)
                        }
                        val ackText = String(ackPlain, Charsets.UTF_8).trimEnd('\u0000')
                        val ack = Json.parseObject(ackText)
                        if (ackHeader.h1 != 6 ||
                            (ackHeader.h3 != 0x95 && ackHeader.h3 != 0x91) ||
                            ack == null || (ack.num("CODE") ?: 0L) != 1L
                        ) {
                            log("预览通道握手异常（h1=${ackHeader.h1} flags=0x%02X，已跳过）"
                                .format(ackHeader.h3))
                            try { c.close() } catch (_: Exception) {}
                        } else {
                            log("预览通道已建立")
                            byteChannel = c
                            byteKey = bkey
                        }
                    }
                } catch (e: Exception) {
                    log("预览通道建立失败（已跳过，不影响发送）：${e.message}")
                }

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
                log("收到 file 通道地址：$ip:$port")
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
                log("文件通道已建立（密码：${aead.cipher}，帧长 $frameSize）")

                // 【关键】从这里开始，控制通道交给监视线程。
                // Rust 版 spawn_control_watch 就是在同一位置起线程的：
                // 对端的 previewOK、取消命令、net_info 询问都在控制通道上，
                // 传输期间没人读控制通道，对端就会判定异常并中止。
                val watch = startControlWatch(control, key, deviceHash, hs.local, sequence)
                try {
                    // 预览（可选）：名单帧走 byte 通道，previewOK 走控制通道
                    val bchan = byteChannel
                    val bkey = byteKey
                    if (bchan != null && bkey != null) {
                        try {
                            sendPreview(bchan, bkey, key, deviceHash, files, hs)
                        } catch (e: Exception) {
                            log("预览未完成（不影响文件传输）：${e.message}")
                        }
                    }

                    // byte 通道监视：吸收对端的进度帧，避免对端写阻塞
                    val byteWatch = if (bchan != null && bkey != null) {
                        startByteWatch(bchan, bkey, key)
                    } else null

                    val written = sendBatches(chan, fkey, aead, hs.isTablet, files, total)
                    byteWatch?.close()
                    chan.gracefulClose(1200)
                    return SendReport(files.map { it.name }, written)
                } finally {
                    watch.close()
                }
            }
        }
        throw SendException(if (!code3Sent) "等待手机准备通道超时（对方可能未点接受）" else "通道协商超时")
    }

    // ------------------------------------------------------------- 数据面

    /**
     * 控制通道监视线程（对应 Rust 版 `outbound/cancel.rs::spawn_control_watch`）。
     *
     * 传输期间控制通道必须一直有人读：
     *   * 对端的 previewOK 走控制通道（不是 byte 通道）；
     *   * 对端的 user_reject / receiverCancel 也在控制通道；
     *   * 对端会问 net_info（tag 4）与逻辑地址（tag 10），要回；
     *   * module 0x15 的内层 0xFFFF0004 要回一个 24 字节空帧。
     * 少了这个线程，华为手机会等不到回应而中止传输。
     */
    /**
     * 控制通道监视线程（对应 Rust 版 `outbound/cancel.rs::spawn_control_watch`）。
     *
     * 传输期间控制通道必须一直有人读：
     *   * 对端的 previewOK 走控制通道（不是 byte 通道）；
     *   * 对端的 user_reject / receiverCancel 也在控制通道；
     *   * 对端会问 net_info（tag 4）与逻辑地址（tag 10），要回；
     *   * module 0x15 的内层 0xFFFF0004 要回一个 24 字节空帧。
     * 少了这个线程，华为手机会等不到回应而中止传输。
     */
    private fun startControlWatch(
        control: Channel,
        key: ByteArray,
        deviceHash: ByteArray,
        local: String,
        startSequence: Long
    ): AutoCloseable {
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            var sequence = startSequence
            while (!stopFlag.get()) {
                try {
                    val frame = control.tryReadFrame(200) ?: continue
                    if (frame.size < Bytes.FRAME_HEADER_SIZE) continue
                    val module = Bytes.u32le(frame, 4)

                    // module 0x15：内层是 0xFFFF0004 的探测帧，回一个 24 字节空帧
                    if (module == Protocol.MODULE_CHANNEL) {
                        val innerMod = if (frame.size >= Bytes.FRAME_HEADER_SIZE + 4) {
                            Bytes.u32le(frame, Bytes.FRAME_HEADER_SIZE)
                        } else 0
                        if (innerMod == Protocol.CHANNEL_BYTES) {
                            try {
                                control.transmit(
                                    Bytes.frameEncode(Protocol.MODULE_CHANNEL, sequence++, 0, ByteArray(0))
                                )
                            } catch (_: Exception) {}
                        }
                        continue
                    }
                    if (module != Protocol.MODULE_ENVELOPE) continue

                    val body = try { Protocol.openChannelFrame(frame, key) } catch (_: Exception) { continue }
                    val fields = try { Bytes.tlvList(body) } catch (_: Exception) { continue }
                    for ((tag, value) in fields) {
                        when (tag) {
                            1L -> {
                                val cmds = try { Bytes.tlvList(value) } catch (_: Exception) { continue }
                                for ((_, cmd) in cmds) {
                                    val text = String(cmd, Charsets.UTF_8)
                                    if (text == "previewOK") {
                                        previewOk = true
                                        log("对端已确认预览")
                                    } else if (text == "user_reject" || text == "receiverCancel" ||
                                        text == "senderCancel"
                                    ) {
                                        cancelled = true
                                    }
                                }
                            }
                            4L -> {
                                try {
                                    control.transmit(
                                        Protocol.sealEncryptedFrame(deviceHash, key, netInfoReply(local), sequence++)
                                    )
                                } catch (_: Exception) {}
                            }
                            10L -> {
                                try {
                                    control.transmit(
                                        Protocol.sealEncryptedFrame(deviceHash, key, logicalAddr(local), sequence++)
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        thread.isDaemon = true
        thread.name = "heinshare-control-watch"
        thread.start()
        return AutoCloseable { stopFlag.set(true) }
    }

    /**
     * byte 通道监视线程（对应 Rust 版 `spawn_byte_watch`）。
     *
     * 传输期间对端会在 byte 通道上发进度帧（dfileRecvPercent#…）。
     * 没人读的话，对端的写缓冲会满，进而拖死整条传输。
     */
    private fun startByteWatch(chan: Channel, byteKey: ByteArray, sessionKey: ByteArray): AutoCloseable {
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            while (!stopFlag.get()) {
                try {
                    val rx = chan.tryReadBytesFrame(byteKey, 200) ?: continue
                    val wrapped = Bytes.frameEncode(Protocol.MODULE_ENVELOPE, 0, 0, rx.second)
                    val body = try { Protocol.openChannelFrame(wrapped, sessionKey) } catch (_: Exception) { continue }
                    for ((tag, value) in Bytes.tlvList(body)) {
                        if (tag != 1L) continue
                        for ((field, data) in Bytes.tlvList(value)) {
                            if (field != 9L) continue
                            val text = String(data, Charsets.UTF_8)
                            val pct = text.removePrefix("dfileRecvPercent#")
                                .removePrefix("fileRecvPercent#")
                                .trim().toDoubleOrNull()
                            if (pct != null) peerPercent = pct
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        thread.isDaemon = true
        thread.name = "heinshare-byte-watch"
        thread.start()
        return AutoCloseable { stopFlag.set(true) }
    }

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
                            // 事务号要用**请求帧**里的那个（与 Rust 版一致），
                            // 否则数据块的事务号对不上，对端会丢弃。
                            written += sendFileChunks(
                                chan, key, aead, file, id, h.transaction, total, done
                            )
                            if (done.size >= files.size) {
                                finishBatch(chan, key, aead, h.transaction)
                                return written
                            }
                        }
                    }
                    6 -> {
                        val inner = Protocol.openControlBlockAny(key, h, payload, aead.cipher)
                        for (id in parseIds(inner)) done.add(id)
                        log("[DONE] 收到 ${parseIds(inner).size} 个 id，累计 ${done.size}/${files.size}")
                        if (done.size >= files.size) {
                            finishBatch(chan, key, aead, h.transaction)
                            return written
                        }
                        chan.transmit(buildDoneAck(key, aead, h.transaction))
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
        total: Long,
        done: MutableSet<Int>
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
                if (written > 0) sentAny = true
                // 进度回调要节流：加解密提速后一个数据块只要几毫秒，
                // 每块都往主线程抛一次会把 UI 线程淹掉，反而拖慢传输。
                val nowP = System.currentTimeMillis()
                if (nowP - lastProgressAt >= 80) {
                    lastProgressAt = nowP
                    progress(written, total)
                }
                var flushed = false
                if (batch.size() >= 256 * 1024) {
                    chan.transmit(batch.toByteArray())
                    batch.reset()
                    batchContent = 0
                    flushed = true
                }
                sequence++
                // 大文件传输中也要及时收 DONE / 流控，不能等整文件发完才读。
                if (flushed || sequence % 64 == 0) {
                    drainFileReplies(chan, key, aead, done)
                }
                if (offset >= file.size) break
            }
        }
        if (batch.size() > 0) chan.transmit(batch.toByteArray())
        drainFileReplies(chan, key, aead, done)
        lastProgressAt = 0L
        progress(written, total)
        return written
    }

    /**
     * 在写文件的间隙处理对端回执。
     *
     * 与 Rust 版 `send_file_chunks` 里的那段读取一致：只处理 DONE / 数据 ACK /
     * 流控 / 中止，其余忽略。DONE 回执必须及时确认，否则对端会反复收尾。
     * 结束判定交回外层循环（和 Rust 版一样）。
     */
    private fun drainFileReplies(
        chan: Channel,
        key: ByteArray,
        aead: Protocol.Aead,
        done: MutableSet<Int>
    ) {
        while (true) {
            val rx = chan.tryReadDfile(1) ?: break
            val h = Protocol.parseDfileHeader(rx)
            val payload = rx.copyOfRange(8, 8 + h.size)
            when (h.kind) {
                6 -> {
                    val ids = parseIds(Protocol.openControlBlockAny(key, h, payload, aead.cipher))
                    done.addAll(ids)
                    chan.transmit(buildDoneAck(key, aead, h.transaction))
                    log("[DONE] 收到 ${ids.size} 个 id，累计 ${done.size}")
                }
                9 -> throw SendException("接收端中止了本次传输")
                22 -> Thread.sleep(20)
                13 -> { }
                5 -> { }
                else -> log("[发送中回执] kind=${h.kind}，已忽略")
            }
        }
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
            "\"TRANS_FLAGS\":2,\"SESSION_KEY\":\"${Bytes.base64Encode(byteKey)}\"}"
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
        val key = Bytes.base64Encode(fileKey)
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

    /**
     * 发预览（可选）。
     *
     * 名单帧走 byte 通道；而 **previewOK 是从控制通道回来的**（Rust 版
     * `spawn_control_watch` 收到控制通道的 previewOK 后置位，
     * `send_preview` 再轮询这个标志）。
     * 之前这里只盯着 byte 通道等 previewOK，永远等不到，白白拖 15 秒，
     * 而且这期间没人读控制通道，对端会判定异常。
     */
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
        log("已发送预览名单帧")

        // 等 previewOK：控制通道（监视线程置位）与 byte 通道都可能带来。
        // 上限 15 秒（与 Rust 版鸿蒙档一致）；超时只告警，不阻断文件本体。
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            checkCancel()
            if (previewOk) {
                log("预览已确认")
                return
            }
            val rx = try { chan.tryReadBytesFrame(byteKey, 200) } catch (e: Exception) { null }
            if (rx == null) continue
            val wrapped = Bytes.frameEncode(Protocol.MODULE_ENVELOPE, 0, 0, rx.second)
            val plain = try { Protocol.openChannelFrame(wrapped, sessionKey) } catch (e: Exception) { continue }
            for ((tag, value) in Bytes.tlvList(plain)) {
                if (tag != 1L) continue
                val innerFields = try { Bytes.tlvList(value) } catch (e: Exception) { continue }
                for ((field, data) in innerFields) {
                    if (field == 8L && String(data, Charsets.UTF_8) == "previewOK") {
                        previewOk = true
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
        /** 最多重试次数（与 Rust 版 MAX_RETRY 一致）。 */
        private const val MAX_RETRY = 2

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
