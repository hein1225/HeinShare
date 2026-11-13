package com.heinshare.core

import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket

class ReceiveException(message: String) : Exception(message)

/**
 * 华为手机的账号认证探测超时。
 *
 * 华为手机会周期性地连上 5684 端口做一次账号认证（不是分享请求），
 * 认证帧我们按 Rust 参照实现一样忽略掉，之后它就不会再发分享请求了。
 * 这个异常专门标记这种情况，让上层不把它当成「接收失败」记进记录页。
 */
class ProbeTimeoutException : Exception("等待对方连接超时")

data class ReceiveResult(val saved: List<SavedFile>, val bytes: Long)

/**
 * 接收方：opening 握手 → 三路轮询（control / byte / file#1）→ 落盘。
 *
 * 对应 Rust 版 `src/src/receive/`。
 */
class Receiver(
    private val identity: Identity,
    private val log: (String) -> Unit,
    private val progress: (received: Long, total: Long) -> Unit = { _, _ -> }
) {

    class Session(
        val fileCount: Int,
        val folders: Int,
        val totalBytes: Long,
        val key: ByteArray,
        val deviceHash: ByteArray,
        val peer: String,
        val accepted: Boolean,
        val peerApple: Boolean
    )

    @Volatile var cancelled = false

    /** 数据面密码：与对端协商后确定，读取时还会逐个套件兜底尝试。 */
    private var dfileCipher: Protocol.Cipher = Protocol.Cipher.AES128_GCM
    private var peerIsApple: Boolean = false

    // ------------------------------------------------------------- 握手

    fun acceptConnection(control: Channel, accept: Boolean, timeoutMs: Int): Session {
        control.label = "control"
        val deviceHash = identity.deviceHashBytes
        val publicDer = identity.publicDer

        var frame = control.tryReadFrame(timeoutMs) ?: throw ReceiveException("等待对方连接超时")
        var authRounds = 0
        var request: Json
        while (true) {
            if (frame.size < Bytes.FRAME_HEADER_SIZE) throw ReceiveException("开场帧异常")
            val module = Bytes.u32le(frame, 4)
            val body = frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size)
            val text = String(body, Charsets.UTF_8).trimEnd('\u0000')
            val obj = Json.parseObject(text) ?: throw ReceiveException("开场不是合法 JSON")
            if ((obj.num("CODE") ?: 0L) == 4L) {
                request = obj
                break
            }
            if (module == Protocol.MODULE_AUTH || obj.str("AUTH_START_STATE") != null) {
                authRounds++
                if (authRounds > 6) throw ReceiveException("认证帧连发过多，断开等待重连")
                log("收到账号认证帧，已忽略（第 $authRounds 次）")
                // 注意：华为手机连上来只是为了做账号认证探测，它**不会**再发分享请求。
                // 这里用专门的异常类型标记，让上层能把「探测」和「真实失败」区分开，
                // 不然记录页会被这些探测刷满（上一轮刚修过同类问题）。
                //
                // 探测后面不会再有分享请求，所以只等一小会儿（5 秒）就够了 ——
                // 用整个 timeoutMs（25 秒）会让每个探测白占一个线程 25 秒。
                frame = control.tryReadFrame(minOf(timeoutMs, 5000)) ?: throw ProbeTimeoutException()
                continue
            }
            throw ReceiveException("不支持的开场请求")
        }

        val peerApple = request.str("PKG_NAME") == "com.huawei.nearby"
        peerIsApple = peerApple
        log(if (peerApple) "对端是 Apple 客户端" else "对端是鸿蒙设备")

        control.transmit(Protocol.openingAck(identity.identityV2))

        val capability = control.tryReadFrame(timeoutMs) ?: throw ReceiveException("等待能力协商超时")
        val (capMode, capBody) = Protocol.openEnvelope(capability)
        if (capMode != 0) throw ReceiveException("能力帧格式异常")
        Bytes.requireTlv(capBody, 2)
        control.transmit(
            Protocol.sealHandshakeEnvelope(deviceHash, Bytes.tlvEncode(3, Bytes.tlvEncode(1, ByteArray(0))), 0)
        )

        val publicRequest = control.tryReadFrame(timeoutMs) ?: throw ReceiveException("等待对端公钥超时")
        val (pubMode, pubBody) = Protocol.openEnvelope(publicRequest)
        if (pubMode != 0) throw ReceiveException("公钥帧格式异常")
        val peerDer = Bytes.requireTlv(Bytes.requireTlv(pubBody, 6), 1)
        val peerKey = Crypto.publicFromDer(peerDer)

        control.transmit(
            Protocol.sealHandshakeEnvelope(deviceHash, Bytes.tlvEncode(6, Bytes.tlvEncode(2, publicDer)), 0)
        )

        val challengeFrame = control.tryReadFrame(timeoutMs) ?: throw ReceiveException("等待挑战超时")
        val (mode, ciphertext) = Protocol.openEnvelope(challengeFrame)
        if (mode != 3) throw ReceiveException("挑战帧格式异常 mode=$mode")
        val decrypted = Crypto.oaepDecrypt(identity.privateKey, ciphertext, false)
            ?: Crypto.oaepDecrypt(identity.privateKey, ciphertext, true)
            ?: throw ReceiveException("挑战解密失败")
        val challenge = Bytes.requireTlv(Bytes.requireTlv(decrypted, 6), 3)
        if (challenge.size != 48) throw ReceiveException("挑战负载长度异常")

        val sessionKey = Crypto.randomBytes(32)
        val encrypted = Crypto.aesGcmEncrypt(challenge.copyOfRange(16, 48), challenge.copyOfRange(0, 16), sessionKey)
            ?: throw ReceiveException("会话密钥加密失败")
        val wrapped = Crypto.oaepEncrypt(peerKey, Bytes.tlvEncode(6, Bytes.tlvEncode(4, encrypted)), false)
        val meta = Bytes.tlvEncode(1, Crypto.randomBytes(8))
        val envelope = Bytes.concat(
            deviceHash,
            byteArrayOf(0x81.toByte(), 0xE5.toByte(), 0x83.toByte()),
            Bytes.varintEncode(meta.size.toLong()),
            meta,
            Bytes.varintEncode(wrapped.size.toLong()),
            wrapped
        )
        control.transmit(Bytes.frameEncode(Protocol.MODULE_ENVELOPE, 0, 0, envelope))
        log("握手完成")

        val offerFrame = control.tryReadFrame(24000) ?: throw ReceiveException("等待分享请求超时")
        val plain = Protocol.openChannelFrame(offerFrame, sessionKey)
        val offerTlv = Bytes.requireTlv(plain, 7)
        var count = 0
        var folders = 0
        var total = 0L
        for ((tag, value) in Bytes.tlvList(offerTlv)) {
            val num = String(value, Charsets.UTF_8).trim().toIntOrNull() ?: 0
            when (tag) {
                3L -> if (num > count) count = num
                6L -> if (num > count) count = num
                4L -> total = String(value, Charsets.UTF_8).trim().toLongOrNull() ?: 0L
                5L -> folders = num
            }
        }
        log("对方请求：$count 个文件，$folders 个目录，共 ${total / 1048576} MB")

        return Session(count, folders, total, sessionKey, deviceHash, control.peerAddress, accept, peerApple)
    }

    // ------------------------------------------------------------- 会话

    fun runSession(
        control: Channel,
        session: Session,
        destination: SaveTarget,
        displayName: String,
        timeoutMs: Int
    ): ReceiveResult {
        val key = session.key
        val deviceHash = session.deviceHash
        val localIp = Net.localIPv4()

        val verdict = if (session.accepted) "user_confirm" else "user_reject"
        control.transmit(
            Protocol.sealEncryptedFrame(
                deviceHash,
                key,
                Bytes.tlvEncode(1, Bytes.tlvEncode(1, verdict.toByteArray())),
                6
            )
        )
        if (!session.accepted) return ReceiveResult(emptyList(), 0L)
        log("已回 user_confirm，进入三路轮询")

        var byteListener: ServerSocket? = null
        var fileListener: ServerSocket? = null
        var byteChannel: Channel? = null
        var fileChannel: Channel? = null
        var byteKey: ByteArray? = null
        var fileKey: ByteArray? = null
        var filePort = 0

        var settingsSeen = false
        var headerSeen = false
        var transaction = -1
        var manifestCount = 0
        val expectedFiles = ArrayList<Protocol.IncomingFile>()
        val emptyFiles = ArrayList<Pair<Int, String>>()
        var totalExpected = session.fileCount
        var baseDone = 0
        var pendingMore = false
        var doneSent = false
        var doneAcked = false
        var doneIds = ArrayList<Int>()
        var doneCount = 0
        var totalReceived = 0L
        val saved = ArrayList<SavedFile>()
        var activeId = -1
        var activeDeclared = 0L
        var activeName = ""
        var activeStream: OutputStream? = null
        var activeHandle: SaveHandle? = null
        var written = 0L

        var metaSent = false
        var myMetaSeq = 0L
        var peerMetaSeq = -1L
        var tag10Replied = false
        var previewAcked = false
        var ecoByteSeq = 1

        val idleLimitMs = 30_000L
        var lastProgressAt = System.currentTimeMillis()
        var deadline = System.currentTimeMillis() + timeoutMs
        // 数据块计数：只用来控制日志频率
        var chunkCount = 0
        var chunkBytes = 0L

        while (System.currentTimeMillis() < deadline) {
            if (cancelled) throw ReceiveException("已取消接收")
            var progressed = false

            if (totalReceived != lastBytes) {
                lastBytes = totalReceived
                lastProgressAt = System.currentTimeMillis()
                deadline = System.currentTimeMillis() + timeoutMs
                progress(totalReceived, session.totalBytes)
            }
            if (System.currentTimeMillis() - lastProgressAt > idleLimitMs) {
                throw ReceiveException("传输中断：长时间没有收到数据，对方可能已锁屏或离开")
            }

            // 接进后来连上的 byte / file 通道
            byteListener?.let { l ->
                if (l.soTimeout == 0) l.soTimeout = 50
                val s = try { l.accept() } catch (e: Exception) { null }
                if (s != null) {
                    s.tcpNoDelay = true
                    byteChannel = Channel(s, "byte")
                    byteListener = null
                    progressed = true
                    log("byte 通道已接入")
                }
            }
            fileListener?.let { l ->
                if (l.soTimeout == 0) l.soTimeout = 50
                val s = try { l.accept() } catch (e: Exception) { null }
                if (s != null) {
                    s.tcpNoDelay = true
                    fileChannel = Channel(s, "file#1")
                    fileListener = null
                    progressed = true
                    log("file 通道已接入")
                }
            }

            // ── 文件通道 ─────────────────────────────────────────────
            val fchan = fileChannel
            val fkey = fileKey
            if (fchan != null && fkey != null) {
                var drained = 0
                while (drained < 64) {
                    val wire = fchan.tryReadDfile(if (drained == 0) 200 else 1) ?: break
                    drained++
                    progressed = true
                    val header = try { Protocol.parseDfileHeader(wire) } catch (e: Exception) {
                        log("[DIAG] 非法帧 前24=" + wire.copyOfRange(0, minOf(24, wire.size)).joinToString(" ") { "%02X".format(it) }); throw e }
                    // 逐帧打日志会明显拖慢接收（64KB 一帧，一个 40MB 文件就是 600 多行），
                    // 这里只打第一帧和每隔一段打一次，中间靠字节数累计。
                    if (header.kind == 4) {
                        chunkCount++
                        chunkBytes += header.size
                        if (chunkCount == 1 || chunkCount % 64 == 0) {
                            log("[DIAG] 数据块 #$chunkCount kind=4 size=${header.size} 已读=${fchan.readTotal}")
                        }
                    } else {
                        log("[DIAG] 读到 kind=${header.kind} size=${header.size} total=${wire.size} 已读=${fchan.readTotal} 头8=" + wire.copyOfRange(0,8).joinToString(" ") { "%02X".format(it) })
                    }
                    val payload = wire.copyOfRange(8, 8 + header.size)
                    when (header.kind) {
                        8 -> if (!settingsSeen) {
                            logSettings(payload)
                            dfileCipher = negotiateCipher(payload, session.peerApple)
                            val mine = Protocol.receiverSettings(true, 1472, 65485)
                            fchan.transmit(mine.encode())
                            fchan.transmit(buildCongestFrame())
                            settingsSeen = true
                            log("已回 DFile settings（数据面密码：$dfileCipher）")
                        }

                        11 -> {}

                        1 -> {
                            val inner = Protocol.openControlBlockAny(fkey, header, payload, dfileCipher)
                            val data = if (inner.size >= 8) inner.copyOfRange(8, inner.size) else ByteArray(0)
                            if (!headerSeen) {
                                pendingMore = false
                                doneAcked = false
                                doneSent = false
                                baseDone = doneCount
                                Protocol.mergeManifest(expectedFiles, emptyFiles, data)
                                if (expectedFiles.isEmpty() && emptyFiles.isEmpty()) {
                                    throw ReceiveException("对端发来的文件清单为空")
                                }
                                manifestCount = if (data.size >= 2) Bytes.u16be(data, 0) else 0
                                transaction = header.transaction
                                headerSeen = true
                            } else {
                                // 清单续帧
                                Protocol.mergeManifest(expectedFiles, emptyFiles, data)
                            }
                            val got = expectedFiles.size + emptyFiles.size
                            if (manifestCount == 0 || got >= manifestCount) {
                                ackManifest(fchan, fkey, header.transaction, expectedFiles, emptyFiles)
                                log("已回清单 ACK（$got 个）")
                                if (expectedFiles.isEmpty() && emptyFiles.isNotEmpty()) {
                                    doneCount += materializeEmpty(destination, emptyFiles, doneIds, saved)
                                    sendDone(fchan, fkey, header.transaction, doneIds)
                                    doneSent = true
                                }
                            }
                        }

                        4 -> {
                            if (!headerSeen || header.transaction != transaction) continue
                            val (fid, _, content) = Protocol.dfileParseChunkAny(fkey, payload, dfileCipher)
                            if (activeId != fid) {
                                activeId = fid
                                val entry = expectedFiles.firstOrNull { it.id == fid }
                                activeDeclared = entry?.size ?: 0L
                                activeName = entry?.name ?: "file-$fid.bin"
                                val handle = openTarget(destination, activeName)
                                activeHandle = handle
                                activeStream = handle.output
                                written = 0L
                            }
                            val out = activeStream ?: throw ReceiveException("目标文件未打开")
                            out.write(content)
                            written += content.size
                            totalReceived += content.size
                            val complete = activeDeclared > 0 && written >= activeDeclared
                            if (header.flags == 3 || complete) {
                                if (header.flags == 3 && activeDeclared > 0 && written < activeDeclared) {
                                    throw ReceiveException("文件不完整：$activeName")
                                }
                                activeStream?.flush()
                                activeStream?.close()
                                activeStream = null
                                val handle = activeHandle
                                activeHandle = null
                                if (handle != null) {
                                    saved.add(handle.commit())
                                    log("已保存 $activeName（$written 字节）")
                                }
                                doneCount++
                                doneIds.add(fid)
                                activeId = -1
                                activeDeclared = 0
                                if (doneCount - baseDone >= expectedFiles.size.coerceAtLeast(1)) {
                                    doneCount += materializeEmpty(destination, emptyFiles, doneIds, saved)
                                    sendDone(fchan, fkey, header.transaction, doneIds)
                                    doneSent = true
                                }
                            }
                        }

                        9 -> throw ReceiveException("对方中止了传输")

                        7 -> if (doneSent) {
                            doneAcked = true
                            log("收到 DONE ACK")
                        }
                    }
                }
            }

            if (doneAcked && !pendingMore && expectedFiles.isNotEmpty() &&
                doneCount - baseDone >= expectedFiles.size
            ) break

            // ── byte 通道 ───────────────────────────────────────────
            val bchan = byteChannel
            val bkey = byteKey
            if (bchan != null) {
                if (bkey == null) {
                    val raw = bchan.tryReadFrame(200)
                    if (raw != null) {
                        progressed = true
                        val wrapped = Bytes.frameEncode(Protocol.MODULE_CHANNEL, 0, 0, raw)
                        val (bheader, plain) = Protocol.parseChannelFrame(wrapped, key)
                        val text = String(plain, Charsets.UTF_8).trimEnd('\u0000')
                        val value = Json.parseObject(text) ?: throw ReceiveException("byte 握手帧不是 JSON")
                        if ((value.num("CODE") ?: 0L) != 1L ||
                            value.str("BUS_NAME") != "IShareReceiverBytesSession"
                        ) {
                            throw ReceiveException("不支持的 byte 通道请求")
                        }
                        val sk = Bytes.base64Decode(value.str("SESSION_KEY") ?: "")
                        require(sk.size == 32) { "byte 会话密钥长度异常" }
                        byteKey = sk
                        val node = Bytes.hexLower(Crypto.sha256(deviceHash))
                        val body = buildByteHandshakeAck(session.peerApple, node)
                        val frame = Protocol.sealChannelFrame(
                            key,
                            Protocol.ChannelHeader(bheader.h0, 6, bheader.h2, bheader.h3 or 1, 0),
                            body,
                            0
                        )
                        bchan.transmit(frame.copyOfRange(Bytes.FRAME_HEADER_SIZE, frame.size))
                        log("byte 握手完成")
                    }
                } else {
                    val rx = try { bchan.tryReadBytesFrame(bkey, 200) } catch (e: Exception) { null }
                    if (rx != null) {
                        progressed = true
                        val plain = rx.second
                        var handled = false
                        val wrapped = Bytes.frameEncode(Protocol.MODULE_ENVELOPE, 0, 0, plain)
                        val body = try { Protocol.openChannelFrame(wrapped, key) } catch (e: Exception) { null }
                        if (body != null && isNameListFrame(body)) {
                            handled = true
                            previewAcked = true
                            control.transmit(
                                Protocol.sealEncryptedFrame(
                                    deviceHash, key,
                                    Bytes.tlvEncode(1, Bytes.tlvEncode(8, "previewOK".toByteArray())), 0
                                )
                            )
                            log("名单帧 → previewOK")
                        }
                        if (!handled && !previewAcked) {
                            val reply = Protocol.sealEncryptedFrame(
                                deviceHash, key,
                                Bytes.tlvEncode(1, Bytes.tlvEncode(8, "previewOK".toByteArray())), 0
                            )
                            bchan.transmit(Protocol.bytesFrame(bkey, reply.copyOfRange(Bytes.FRAME_HEADER_SIZE, reply.size), 0))
                            previewAcked = true
                            log("已回 previewOK")
                        }
                    }
                }
                // 进度回报
                if (previewAcked && bkey != null && System.currentTimeMillis() - lastEcoAt > 1000) {
                    lastEcoAt = System.currentTimeMillis()
                    val total = session.totalBytes.coerceAtLeast(1)
                    val pct = totalReceived * 100.0 / total
                    val plain = Bytes.tlvEncode(1, Bytes.tlvEncode(9, "dfileRecvPercent#%.6f".format(pct).toByteArray()))
                    val env = Protocol.sealEncryptedFrame(deviceHash, key, plain, ecoByteSeq.toLong())
                    try {
                        bchan.transmit(
                            Protocol.bytesFrame(bkey, env.copyOfRange(Bytes.FRAME_HEADER_SIZE, env.size), ecoByteSeq)
                        )
                        ecoByteSeq++
                    } catch (e: Exception) {
                    }
                }
            }

            // ── 控制通道 ────────────────────────────────────────────
            val ctl = control.tryReadFrame(200)
            if (ctl != null) {
                progressed = true
                val module = Bytes.u32le(ctl, 4)
                if (module == Protocol.MODULE_ENVELOPE) {
                    val body = Protocol.openChannelFrame(ctl, key)
                    for ((tag, value) in Bytes.tlvList(body)) {
                        when (tag) {
                            4L -> {
                                val reply = buildTag5Reply(localIp)
                                control.transmit(Protocol.sealEncryptedFrame(deviceHash, key, reply, 0))
                            }
                            10L -> if (!tag10Replied) {
                                tag10Replied = true
                                val addr = Bytes.tlvGet(value, 1) ?: ByteArray(0)
                                control.transmit(
                                    Protocol.sealEncryptedFrame(
                                        deviceHash, key,
                                        Bytes.tlvEncode(10, Bytes.tlvEncode(1, addr)), 0
                                    )
                                )
                                if (!metaSent && session.peerApple) {
                                    metaSent = true
                                    myMetaSeq = randomSeq()
                                    control.transmit(
                                        Protocol.sealChannelFrame(
                                            key,
                                            Protocol.ChannelHeader(Protocol.PEER_META_FRAME, 5, myMetaSeq, 0, 0),
                                            buildMetaInfoJson(displayName).toByteArray(Charsets.UTF_8),
                                            0
                                        )
                                    )
                                    control.transmit(
                                        Protocol.sealChannelFrame(
                                            key,
                                            Protocol.ChannelHeader(Protocol.PEER_META_ACK_FRAME, 5, myMetaSeq, 0, 0),
                                            Protocol.PEER_META_ACK_JSON,
                                            0
                                        )
                                    )
                                }
                            }
                            1L -> {
                                for ((_, field) in Bytes.tlvList(value)) {
                                    val text = String(field, Charsets.UTF_8)
                                    if (text == "senderCancel" || text == "receiverCancel" || text == "user_reject") {
                                        throw ReceiveException("对方取消了本次传输")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val parsed = try { Protocol.parseChannelFrame(ctl, key) } catch (e: Exception) { null }
                    if (parsed != null) {
                        val (header, plain) = parsed
                        val text = String(plain, Charsets.UTF_8).trimEnd('\u0000')
                        if (header.h0 == Protocol.PEER_META_FRAME) {
                            val repeated = peerMetaSeq == header.h2
                            peerMetaSeq = header.h2
                            if (myMetaSeq != 0L) {
                                if (repeated) {
                                    control.transmit(
                                        Protocol.sealChannelFrame(
                                            key,
                                            Protocol.ChannelHeader(Protocol.PEER_META_ACK_FRAME, 5, myMetaSeq, 0, 0),
                                            Protocol.PEER_META_ACK_JSON,
                                            0
                                        )
                                    )
                                }
                            } else {
                                myMetaSeq = randomSeq()
                                metaSent = true
                                control.transmit(
                                    Protocol.sealChannelFrame(
                                        key,
                                        Protocol.ChannelHeader(Protocol.PEER_META_FRAME, 5, myMetaSeq, 0, 0),
                                        buildMetaInfoJson(displayName).toByteArray(Charsets.UTF_8),
                                        0
                                    )
                                )
                                control.transmit(
                                    Protocol.sealChannelFrame(
                                        key,
                                        Protocol.ChannelHeader(Protocol.PEER_META_ACK_FRAME, 5, myMetaSeq, 0, 0),
                                        Protocol.PEER_META_ACK_JSON,
                                        0
                                    )
                                )
                            }
                            continue
                        }
                        if (header.h0 == Protocol.PEER_META_ACK_FRAME) continue

                        val obj = Json.parseObject(text)
                        if (obj == null) {
                            if (text.uppercase().contains("CANCEL") || text.contains("取消")) {
                                throw ReceiveException("对方取消了本次传输")
                            }
                            continue
                        }
                        if (header.h0 == Protocol.CHANNEL_BYTES && header.h1 == 16 && header.h3 == 0) {
                            if ((obj.num("CODE") ?: 0L) != 3L) throw ReceiveException("P2P 请求格式异常")
                            val listener = ServerSocket(0, 1, InetAddress.getByName(localIp))
                            val port = listener.localPort
                            val body = buildP2pReply(localIp, port)
                            control.transmit(
                                Protocol.sealChannelFrame(
                                    key,
                                    Protocol.ChannelHeader(header.h0, 16, header.h2, 1, 0),
                                    body,
                                    0
                                )
                            )
                            byteListener = listener
                            continue
                        }
                        if (header.h0 == Protocol.CHANNEL_BYTES && header.h1 == 17 && header.h3 == 0) {
                            if ((obj.num("CODE") ?: 0L) != 1538L) throw ReceiveException("file 通道请求格式异常")
                            if (fileChannel != null || fileListener != null) {
                                if (filePort != 0) {
                                    val peerCh = obj.num("MY_CHANNEL_ID") ?: 0L
                                    val reply = fileChannelReply(filePort, localIp, peerCh)
                                    control.transmit(
                                        Protocol.sealChannelFrame(
                                            key,
                                            Protocol.ChannelHeader(header.h0, 17, header.h2, 1, 0),
                                            reply.toByteArray(Charsets.UTF_8) + 0,
                                            0
                                        )
                                    )
                                }
                                continue
                            }
                            val sk = Bytes.base64Decode(obj.str("SESSION_KEY") ?: "")
                            require(sk.size == 32) { "file 会话密钥长度异常" }
                            fileKey = sk
                            val listener = ServerSocket(0, 1, InetAddress.getByName(localIp))
                            val port = listener.localPort
                            filePort = port
                            val reply = fileChannelReply(port, localIp, null)
                            control.transmit(
                                Protocol.sealChannelFrame(
                                    key,
                                    Protocol.ChannelHeader(header.h0, 17, header.h2, 1, 0),
                                    reply.toByteArray(Charsets.UTF_8) + 0,
                                    0
                                )
                            )
                            fileListener = listener
                            continue
                        }
                    }
                }
            }

            if (!progressed) Thread.sleep(10)
            if (!pendingMore && doneCount > 0 && doneCount - baseDone >= expectedFiles.size.coerceAtLeast(1) && activeId < 0) {
                break
            }
        }

        if (doneCount == 0) {
            throw ReceiveException("传输超时：没有收到完整文件")
        }
        byteChannel?.close()
        fileChannel?.close()
        byteListener?.close()
        fileListener?.close()
        return ReceiveResult(saved, totalReceived)
    }

    // ------------------------------------------------------------- 辅助

    private var lastBytes = 0L
    private var lastEcoAt = 0L

    private fun randomSeq(): Long = System.nanoTime() shl 16

    /**
     * 按对端 settings 里声明的能力位挑数据面密码。
     *
     * 注意：只声明「明文」位（caps=0x1）的老实现，实际发出来的数据仍是 AES，
     * 所以这里不能真按明文处理。
     */
    private fun negotiateCipher(settingsPayload: ByteArray, apple: Boolean): Protocol.Cipher {
        val caps = Protocol.settingsCipherCaps(settingsPayload)
        if (caps == null) {
            return if (apple) Protocol.Cipher.CHACHA20_POLY1305 else Protocol.Cipher.AES128_GCM
        }
        val hasAes = caps and Protocol.CIPHER_AES != 0
        val hasChacha = caps and Protocol.CIPHER_CHACHA != 0
        return when {
            hasAes -> Protocol.Cipher.AES128_GCM
            hasChacha -> Protocol.Cipher.CHACHA20_POLY1305
            else -> Protocol.Cipher.AES128_GCM
        }
    }

    private fun logSettings(payload: ByteArray) {        if (payload.size < 100) return
        val mtu = Bytes.u16be(payload, 0)
        val ctype = Bytes.u16be(payload, 2)
        val dfs = Bytes.u32be(payload, 16)
        val caps = Bytes.u32be(payload, 20)
        log("对端 settings：mtu=$mtu ctype=$ctype dfs=$dfs caps=0x${caps.toString(16)}")
    }

    private fun ackManifest(
        chan: Channel,
        key: ByteArray,
        trans: Int,
        expected: List<Protocol.IncomingFile>,
        empty: List<Pair<Int, String>>
    ) {
        val ids = ArrayList<Byte>()
        for (e in expected) {
            ids.add(((e.id shr 8) and 0xFF).toByte())
            ids.add((e.id and 0xFF).toByte())
        }
        for (e in empty) {
            ids.add(((e.first shr 8) and 0xFF).toByte())
            ids.add((e.first and 0xFF).toByte())
        }
        val ack = Protocol.assembleDfilePacket(2, 0, 0, trans, ids.toByteArray())
        val aead = Protocol.Aead(dfileCipher, peerIsApple)
        chan.transmit(Protocol.dfileSealControl(aead, key, ack))
    }

    private fun sendDone(chan: Channel, key: ByteArray, trans: Int, ids: List<Int>) {
        val payload = ByteArray(ids.size * 2)
        for ((i, id) in ids.withIndex()) {
            payload[i * 2] = ((id shr 8) and 0xFF).toByte()
            payload[i * 2 + 1] = (id and 0xFF).toByte()
        }
        val done = Protocol.assembleDfilePacket(6, 0, 0, trans, payload)
        val aead = Protocol.Aead(dfileCipher, peerIsApple)
        chan.transmit(Protocol.dfileSealControl(aead, key, done))
    }

    private fun materializeEmpty(
        destination: SaveTarget,
        empty: List<Pair<Int, String>>,
        doneIds: MutableList<Int>,
        saved: MutableList<SavedFile>
    ): Int {
        var made = 0
        for ((id, name) in empty) {
            try {
                val handle = destination.open(sanitize(name))
                handle.output.flush()
                handle.output.close()
                saved.add(handle.commit())
                doneIds.add(id)
                made++
            } catch (e: Exception) {
                log("创建空文件失败：$name")
            }
        }
        return made
    }

    private fun openTarget(destination: SaveTarget, name: String): SaveHandle {
        val segments = safeRelSegments(name)
        val relPath = if (segments.isEmpty()) "received.bin" else segments.joinToString("/")
        return destination.open(relPath)
    }

    companion object {
        fun sanitize(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
            val cleaned = base.map { ch ->
                if (ch.isISOControl() || ch in "<>:\"|?*") '_' else ch
            }.joinToString("").trimEnd('.', ' ')
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "received.bin"
            if (isReservedName(cleaned)) return "_$cleaned".take(120)
            return cleaned.take(120)
        }

        private fun isReservedName(name: String): Boolean {
            val stem = name.substringBefore('.').trim().uppercase()
            if (stem in setOf("CON", "PRN", "AUX", "NUL")) return true
            return stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) && stem[3].isDigit()
        }

        fun safeRelSegments(rel: String): List<String> =
            rel.split('/', '\\')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .filter { !(it.length == 2 && it.endsWith(":")) }
                .map { sanitize(it) }
                .filter { it.isNotEmpty() }

        fun uniquePath(dir: File, name: String): File {
            val first = File(dir, name)
            if (!first.exists()) return first
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (n in 1..9999) {
                val candidate = File(dir, "$stem ($n)$ext")
                if (!candidate.exists()) return candidate
            }
            return File(dir, "$stem-${System.currentTimeMillis()}$ext")
        }
    }

    private fun buildCongestFrame(): ByteArray {
        val congest = ByteArray(32)
        congest[0] = 11
        congest[7] = 24
        val body = byteArrayOf(
            0x00, 0x00, 0x01, 0xb0.toByte(), 0x00, 0x00, 0x01, 0xb0.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xea.toByte()
        )
        System.arraycopy(body, 0, congest, 8, body.size)
        return congest
    }

    private fun buildByteHandshakeAck(peerApple: Boolean, node: String): ByteArray {
        val text = if (peerApple) {
            "{\"API_VERSION\":2,\"CODE\":1,\"DEVICE_ID\":\"\",\"MTU_SIZE\":4194304,\"PKG_NAME\":\"${Protocol.PKG_NAME}\",\"TRANS_CAPABILITY\":0}"
        } else {
            "{\"CODE\":1,\"API_VERSION\":2,\"MTU_SIZE\":1048576,\"DEVICE_ID\":\"$node\",\"PKG_NAME\":\"${Protocol.PKG_NAME}\",\"TRANS_CAPABILITY\":0,\"AUTH_STATE\":\"\"}"
        }
        return text.toByteArray(Charsets.UTF_8) + 0
    }

    private fun buildMetaInfoJson(displayName: String): String {
        val node = Bytes.hexLower(Crypto.sha256(identity.deviceHashBytes))
        return "{\"DEVICE_NAME\":\"$displayName\",\"DEVICE_TYPE_ID\":12,\"FLAG\":100,\"META_AUTH_CAPA\":1," +
            "\"META_NODE_ID\":\"$node\",\"TOKEN_ID\":\"0000000000000000000000000000000000000000000000000000000000000001\"}"
    }

    private fun buildTag5Reply(localIp: String): ByteArray {
        val fields = Bytes.concat(
            Bytes.tlvEncode(1, ByteArray(0)),
            Bytes.tlvEncode(2, ByteArray(0)),
            Bytes.tlvEncode(3, "true".toByteArray()),
            Bytes.tlvEncode(4, localIp.toByteArray(Charsets.UTF_8))
        )
        return Bytes.tlvEncode(5, fields)
    }

    private fun buildP2pReply(localIp: String, port: Int): ByteArray =
        ("{\"CODE\":3,\"P2P_IP\":\"$localIp\",\"P2P_PORT\":$port,\"PROTOCOL_TYPE\":4}").toByteArray(Charsets.UTF_8) + 0

    private fun fileChannelReply(port: Int, localIp: String, peerChannelId: Long?): String {
        val peer = if (peerChannelId != null) "\"PEER_CHANNEL_ID\":$peerChannelId," else ""
        return "{\"MY_CHANNEL_ID\":1,$peer\"P2P_PORT\":$port,\"MY_IP\":\"$localIp\",\"CODE\":1538," +
            "\"STREAM_TYPE\":255,\"PKG_NAME\":\"${Protocol.PKG_NAME}\",\"BUSINESS_TYPE\":3,\"API_VERSION\":0," +
            "\"TRANS_CAPABILITY\":0,\"UDP_CHANNEL_CAPABILITY\":0}"
    }

    private fun isNameListFrame(body: ByteArray): Boolean {
        if (body.size < 1000) return false
        val fields = Bytes.tlvList(body)
        return fields.size == 1 && fields[0].first == 8L && fields[0].second.size > 500
    }
}
