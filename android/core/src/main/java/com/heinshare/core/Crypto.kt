package com.heinshare.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 密码学工具。
 *
 * Android 的 JCE 不保证提供 16 字节 IV 的 AES-GCM，也不保证 OAEP 的 MGF1 摘要可切换，
 * 所以 GCM 与 OAEP 都自己实现（块加密仍走 JCE 的 AES/ECB，性能由系统优化）。
 *
 * 对应 Rust 版：`src/src/crypto.rs`、`src/src/chacha.rs`、`src/src/lib.rs`。
 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ------------------------------------------------------------------ AES

    private fun aesBlock(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    private fun xorInto(dst: ByteArray, src: ByteArray): ByteArray {
        for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
        return dst
    }

    /** GF(2^128) 乘法，GHASH 用。 */
    private fun gfMul(xHi: Long, xLo: Long, vHi: Long, vLo: Long): Pair<Long, Long> {
        var zHi = 0L
        var zLo = 0L
        var wHi = vHi
        var wLo = vLo
        for (i in 0 until 128) {
            val bit = if (i < 64) {
                (xHi ushr (63 - i)) and 1L
            } else {
                (xLo ushr (127 - i)) and 1L
            }
            if (bit != 0L) {
                zHi = zHi xor wHi
                zLo = zLo xor wLo
            }
            val lsb = wLo and 1L
            wLo = (wLo ushr 1) or (wHi shl 63)
            wHi = wHi ushr 1
            if (lsb != 0L) wHi = wHi xor -0x1F00000000000000L // 0xE100000000000000
        }
        return zHi to zLo
    }

    private fun ghash(hHi: Long, hLo: Long, data: ByteArray): Pair<Long, Long> {
        var xHi = 0L
        var xLo = 0L
        var i = 0
        while (i + 16 <= data.size) {
            var bHi = 0L
            var bLo = 0L
            for (j in 0 until 8) bHi = (bHi shl 8) or (data[i + j].toLong() and 0xFF)
            for (j in 8 until 16) bLo = (bLo shl 8) or (data[i + j].toLong() and 0xFF)
            xHi = xHi xor bHi
            xLo = xLo xor bLo
            val (nHi, nLo) = gfMul(xHi, xLo, hHi, hLo)
            xHi = nHi
            xLo = nLo
            i += 16
        }
        return xHi to xLo
    }

    private fun inc32(block: ByteArray): ByteArray {
        val out = block.copyOf()
        var c = 1
        for (i in 15 downTo 12) {
            c += out[i].toInt() and 0xFF
            out[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        return out
    }

    private fun gcmCounter(nonce: ByteArray, hHi: Long, hLo: Long): ByteArray {
        val j0: ByteArray = if (nonce.size == 12) {
            Bytes.concat(nonce, byteArrayOf(0, 0, 0, 1))
        } else {
            val padded = Bytes.concat(nonce, ByteArray((16 - nonce.size % 16) % 16))
            val lenBlock = ByteArray(16)
            val bits = nonce.size.toLong() * 8
            for (i in 0 until 8) lenBlock[8 + i] = ((bits ushr (56 - i * 8)) and 0xFF).toByte()
            val (gh, gl) = ghash(hHi, hLo, Bytes.concat(padded, lenBlock))
            Bytes.concat(
                byteArrayOf(
                    ((gh ushr 56) and 0xFF).toByte(), ((gh ushr 48) and 0xFF).toByte(),
                    ((gh ushr 40) and 0xFF).toByte(), ((gh ushr 32) and 0xFF).toByte(),
                    ((gh ushr 24) and 0xFF).toByte(), ((gh ushr 16) and 0xFF).toByte(),
                    ((gh ushr 8) and 0xFF).toByte(), (gh and 0xFF).toByte(),
                    ((gl ushr 56) and 0xFF).toByte(), ((gl ushr 48) and 0xFF).toByte(),
                    ((gl ushr 40) and 0xFF).toByte(), ((gl ushr 32) and 0xFF).toByte(),
                    ((gl ushr 24) and 0xFF).toByte(), ((gl ushr 16) and 0xFF).toByte(),
                    ((gl ushr 8) and 0xFF).toByte(), (gl and 0xFF).toByte()
                )
            )
        }
        return j0
    }

    /**
     * 复用的 AES-GCM Cipher 实例。
     *
     * `Cipher.getInstance()` 每次都要走一遍 provider 查找与对象构造，实测单次开销在
     * 几十微秒量级；数据通道每 64KB 就加密一块，1GB 文件要调 1.6 万次，
     * 累积起来是几百毫秒到数秒的纯浪费。
     *
     * Cipher 对象本身可以重复 init 复用（GCM 的内部状态在 doFinal 后作废，
     * 下次 init 会重置），所以这里按线程缓存两个实例：
     *   * 下标 0 —— 用 GCMParameterSpec 的现代写法；
     *   * 下标 1 —— 用 IvParameterSpec 的老设备兼容写法。
     */
    private val gcmCiphers = ThreadLocal.withInitial { arrayOfNulls<Cipher>(2) }

    /** 取（并按需创建）本线程可用的 Cipher；指定 init 方式。 */
    private fun gcmCipher(init: Int): Cipher {
        val holder = gcmCiphers.get()
        holder[init]?.let { return it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        holder[init] = cipher
        return cipher
    }

    /**
     * 平台 AES-GCM（只支持 96 位 nonce）。
     *
     * 为什么优先用它：手写 GCM 每 16 字节就要 new 一次 `Cipher` 做一次 ECB，
     * 64KB 的数据块要跑 4096 次 JCE 调用，实测把吞吐压到 1~2 MB/s。
     * 平台实现（Conscrypt / SunJCE）走硬件 AES，一次 `doFinal` 就能吞下整个数据块。
     *
     * 返回 null 表示平台实现不可用（例如老设备不接受 GCMParameterSpec），调用方回落到手写实现。
     */
    private fun platformGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plain: ByteArray,
        aad: ByteArray
    ): ByteArray? {
        if (nonce.size != 12) return null
        for (init in 0..1) {
            try {
                val cipher = gcmCipher(init)
                if (init == 0) {
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                } else {
                    // 老版本 Conscrypt 只认 IvParameterSpec
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce))
                }
                if (aad.isNotEmpty()) cipher.updateAAD(aad)
                return cipher.doFinal(plain)
            } catch (_: Exception) {
                // 换下一种 init 方式再试
            }
        }
        return null
    }

    /** 平台 AES-GCM 解密；tag 不匹配或平台不可用时返回 null。 */
    private fun platformGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        aad: ByteArray
    ): ByteArray? {
        if (nonce.size != 12) return null
        for (init in 0..1) {
            try {
                val cipher = gcmCipher(init)
                if (init == 0) {
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                } else {
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce))
                }
                if (aad.isNotEmpty()) cipher.updateAAD(aad)
                return cipher.doFinal(sealed)
            } catch (_: Exception) {
                // 换下一种 init 方式再试
            }
        }
        return null
    }

    /** AES-GCM 加密，输出密文 + 16 字节 tag。支持 12 / 16 字节 IV，可带 AAD。 */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 16 || key.size == 32) { "AES-GCM 需要 16 字节（128 位）或 32 字节（256 位）密钥" }
        platformGcmEncrypt(key, nonce, plain, aad)?.let { return it }
        return aesGcmEncryptManual(key, nonce, plain, aad)
    }

    /** 手写 GCM（保留作兜底）：只有平台实现不可用、或 nonce 不是 12 字节时才会走到这里。 */
    internal fun aesGcmEncryptManual(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val h = aesBlock(key, ByteArray(16))
        val hHi = Bytes.u64be(h, 0)
        val hLo = Bytes.u64be(h, 8)
        val j0 = gcmCounter(nonce, hHi, hLo)

        val ct = ByteArray(plain.size)
        var counter = j0
        var off = 0
        while (off < plain.size) {
            counter = inc32(counter)
            val ks = aesBlock(key, counter)
            val n = minOf(16, plain.size - off)
            for (i in 0 until n) ct[off + i] = (plain[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }

        val aadPad = Bytes.concat(aad, ByteArray((16 - aad.size % 16) % 16))
        val ctPad = Bytes.concat(ct, ByteArray((16 - ct.size % 16) % 16))
        val lenBlock = ByteArray(16)
        val aBits = aad.size.toLong() * 8
        val cBits = ct.size.toLong() * 8
        for (i in 0 until 8) lenBlock[i] = ((aBits ushr (56 - i * 8)) and 0xFF).toByte()
        for (i in 0 until 8) lenBlock[8 + i] = ((cBits ushr (56 - i * 8)) and 0xFF).toByte()
        val (gHi, gLo) = ghash(hHi, hLo, Bytes.concat(aadPad, ctPad, lenBlock))
        val s = aesBlock(key, j0)
        val tag = ByteArray(16)
        for (i in 0 until 8) {
            tag[i] = (((gHi ushr (56 - i * 8)) and 0xFF).toInt() xor (s[i].toInt() and 0xFF)).toByte()
            tag[8 + i] = (((gLo ushr (56 - i * 8)) and 0xFF).toInt() xor (s[8 + i].toInt() and 0xFF)).toByte()
        }
        return Bytes.concat(ct, tag)
    }

    /** AES-GCM 解密，校验 tag。 */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(key.size == 16 || key.size == 32) { "AES-GCM 需要 16 字节（128 位）或 32 字节（256 位）密钥" }
        if (nonce.size == 12) {
            val plain = platformGcmDecrypt(key, nonce, sealed, aad)
            if (plain != null) return plain
        }
        return aesGcmDecryptManual(key, nonce, sealed, aad)
    }

    /** 手写 GCM 解密（兜底）。 */
    internal fun aesGcmDecryptManual(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val ct = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        val h = aesBlock(key, ByteArray(16))
        val hHi = Bytes.u64be(h, 0)
        val hLo = Bytes.u64be(h, 8)
        val j0 = gcmCounter(nonce, hHi, hLo)

        val aadPad = Bytes.concat(aad, ByteArray((16 - aad.size % 16) % 16))
        val ctPad = Bytes.concat(ct, ByteArray((16 - ct.size % 16) % 16))
        val lenBlock = ByteArray(16)
        val aBits = aad.size.toLong() * 8
        val cBits = ct.size.toLong() * 8
        for (i in 0 until 8) lenBlock[i] = ((aBits ushr (56 - i * 8)) and 0xFF).toByte()
        for (i in 0 until 8) lenBlock[8 + i] = ((cBits ushr (56 - i * 8)) and 0xFF).toByte()
        val (gHi, gLo) = ghash(hHi, hLo, Bytes.concat(aadPad, ctPad, lenBlock))
        val s = aesBlock(key, j0)
        for (i in 0 until 16) {
            val expect = ((((if (i < 8) gHi else gLo) ushr (56 - (i % 8) * 8)) and 0xFF).toInt()
                xor (s[i].toInt() and 0xFF))
            if (expect != (tag[i].toInt() and 0xFF)) return null
        }

        val pt = ByteArray(ct.size)
        var counter = j0
        var off = 0
        while (off < ct.size) {
            counter = inc32(counter)
            val ks = aesBlock(key, counter)
            val n = minOf(16, ct.size - off)
            for (i in 0 until n) pt[off + i] = (ct[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return pt
    }

    // -------------------------------------------------------------- ChaCha20

    private fun chachaQuarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] = x[a] + x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] = x[c] + x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] = x[a] + x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] = x[c] + x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun chachaBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = Bytes.u32le(key, i * 4)
        s[12] = counter
        s[13] = Bytes.u32le(nonce, 0)
        s[14] = Bytes.u32le(nonce, 4)
        s[15] = Bytes.u32le(nonce, 8)
        val x = s.copyOf()
        repeat(10) {
            chachaQuarter(x, 0, 4, 8, 12); chachaQuarter(x, 1, 5, 9, 13)
            chachaQuarter(x, 2, 6, 10, 14); chachaQuarter(x, 3, 7, 11, 15)
            chachaQuarter(x, 0, 5, 10, 15); chachaQuarter(x, 1, 6, 11, 12)
            chachaQuarter(x, 2, 7, 8, 13); chachaQuarter(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun chacha20Xor(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray) {
        var ctr = counter
        var off = 0
        while (off < data.size) {
            val ks = chachaBlock(key, nonce, ctr)
            val n = minOf(64, data.size - off)
            for (i in 0 until n) data[off + i] = (data[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
            ctr++
        }
    }

    private fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        fun ld(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (i * 8))
            return v
        }
        val r0 = ld(key, 0) and 0x3ffffffL
        val r1 = (ld(key, 3) ushr 2) and 0x3ffff03L
        val r2 = (ld(key, 6) ushr 4) and 0x3ffc0ffL
        val r3 = (ld(key, 9) ushr 6) and 0x3f03fffL
        val r4 = (ld(key, 12) ushr 8) and 0x00fffffL
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5

        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val hibit = 1L shl 24
        var off = 0
        while (off < msg.size) {
            val take = minOf(16, msg.size - off)
            val block = ByteArray(16)
            System.arraycopy(msg, off, block, 0, take)
            if (take < 16) block[take] = 1
            val b = block
            h0 += (ld(b, 0)) and 0x3ffffffL
            h1 += (ld(b, 3) ushr 2) and 0x3ffffffL
            h2 += (ld(b, 6) ushr 4) and 0x3ffffffL
            h3 += (ld(b, 9) ushr 6) and 0x3ffffffL
            // 与 Rust 版一致：每个块都带 2^128 位（hibit）
            h4 += (ld(b, 12) ushr 8) or hibit
            off += take

            var d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and 0x3ffffffL
            d1 += c
            c = d1 ushr 26
            h1 = d1 and 0x3ffffffL
            d2 += c
            c = d2 ushr 26
            h2 = d2 and 0x3ffffffL
            d3 += c
            c = d3 ushr 26
            h3 = d3 and 0x3ffffffL
            d4 += c
            c = d4 ushr 26
            h4 = d4 and 0x3ffffffL
            h0 += c * 5
            h1 += h0 ushr 26
            h0 = h0 and 0x3ffffffL
        }

        var c = h1 ushr 26
        h1 = h1 and 0x3ffffffL
        h2 += c
        c = h2 ushr 26
        h2 = h2 and 0x3ffffffL
        h3 += c
        c = h3 ushr 26
        h3 = h3 and 0x3ffffffL
        h4 += c
        c = h4 ushr 26
        h4 = h4 and 0x3ffffffL
        h0 += c * 5
        c = h0 ushr 26
        h0 = h0 and 0x3ffffffL
        h1 += c

        var g0 = h0 + 5
        c = g0 ushr 26
        g0 = g0 and 0x3ffffffL
        var g1 = h1 + c
        c = g1 ushr 26
        g1 = g1 and 0x3ffffffL
        var g2 = h2 + c
        c = g2 ushr 26
        g2 = g2 and 0x3ffffffL
        var g3 = h3 + c
        c = g3 ushr 26
        g3 = g3 and 0x3ffffffL
        val g4 = h4 + c - (1L shl 26)

        if (g4 and 0x80000000L == 0L) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4
        }

        // 与 Rust 版一致：这里按 u32 语义截断（Rust 里 h0..h3 是 u32）
        val f0 = (h0 or (h1 shl 26)) and 0xFFFFFFFFL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL
        val s0 = ld(key, 16); val s1v = ld(key, 20); val s2v = ld(key, 24); val s3v = ld(key, 28)

        var f = f0 + s0
        val o0 = f
        f = f1 + s1v + (f ushr 32)
        val o1 = f
        f = f2 + s2v + (f ushr 32)
        val o2 = f
        f = f3 + s3v + (f ushr 32)
        val o3 = f

        val out = ByteArray(16)
        for (i in 0 until 4) {
            out[i] = (o0 ushr (i * 8) and 0xFF).toByte()
            out[4 + i] = (o1 ushr (i * 8) and 0xFF).toByte()
            out[8 + i] = (o2 ushr (i * 8) and 0xFF).toByte()
            out[12 + i] = (o3 ushr (i * 8) and 0xFF).toByte()
        }
        return out
    }

    private fun polyKey(key: ByteArray, nonce: ByteArray): ByteArray =
        chachaBlock(key, nonce, 0).copyOfRange(0, 32)

    private fun polyInput(aad: ByteArray, ct: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(aad)
        repeat((16 - aad.size % 16) % 16) { out.write(0) }
        out.write(ct)
        repeat((16 - ct.size % 16) % 16) { out.write(0) }
        // 长度是「字节数」的 64 位小端（与 Rust 版 poly_input 一致）
        val aLen = aad.size.toLong()
        val cLen = ct.size.toLong()
        for (i in 0 until 8) out.write(((aLen ushr (i * 8)) and 0xFF).toInt())
        for (i in 0 until 8) out.write(((cLen ushr (i * 8)) and 0xFF).toInt())
        return out.toByteArray()
    }

    /** ChaCha20-Poly1305（RFC 8439，12 字节 nonce），输出密文 + 16 字节 tag。 */
    fun chachaAeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "ChaCha20-Poly1305 参数不对" }
        val pk = polyKey(key, nonce)
        val ct = plain.copyOf()
        chacha20Xor(key, nonce, 1, ct)
        val tag = poly1305(pk, polyInput(aad, ct))
        return Bytes.concat(ct, tag)
    }

    fun chachaAeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < 16) return null
        val ct = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        val pk = polyKey(key, nonce)
        val want = poly1305(pk, polyInput(aad, ct))
        for (i in 0 until 16) if (want[i] != tag[i]) return null
        val pt = ct.copyOf()
        chacha20Xor(key, nonce, 1, pt)
        return pt
    }

    // ------------------------------------------------------------------ RSA

    fun generateRsa(): java.security.KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    fun publicDer(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun publicFromDer(der: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        require((key as RSAPublicKey).modulus.bitLength() == 2048) { "对端公钥必须是 RSA-2048" }
        return key
    }

    private fun mgf1(seed: ByteArray, length: Int, useSha1: Boolean): ByteArray {
        val digest: (ByteArray) -> ByteArray = if (useSha1) { { d -> sha1(d) } } else { { d -> sha256(d) } }
        val out = ByteArrayOutputStream()
        var counter = 0
        while (out.size() < length) {
            val block = digest(Bytes.concat(seed, byteArrayOf(((counter ushr 24) and 0xFF).toByte(),
                ((counter ushr 16) and 0xFF).toByte(), ((counter ushr 8) and 0xFF).toByte(),
                (counter and 0xFF).toByte())))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOfRange(0, length)
    }

    /**
     * RSA-OAEP 加密。
     *
     * @param mgfSha1 true 时 MGF1 用 SHA-1（摘要仍是 SHA-256），与 Rust 的
     *   `Oaep::new_with_mgf_hash::<Sha256, Sha1>()` 一致；false 时两者都是 SHA-256。
     */
    fun oaepEncrypt(publicKey: PublicKey, plain: ByteArray, mgfSha1: Boolean): ByteArray {
        val pub = publicKey as RSAPublicKey
        val k = (pub.modulus.bitLength() + 7) / 8
        val hLen = 32
        require(plain.size <= k - 2 * hLen - 2) { "RSA-OAEP 明文过长" }
        val lHash = sha256(ByteArray(0))
        val ps = ByteArray(k - plain.size - 2 * hLen - 2)
        val db = Bytes.concat(lHash, ps, byteArrayOf(1), plain)
        val seed = randomBytes(hLen)
        val maskedDb = xorInto(db.copyOf(), mgf1(seed, k - hLen - 1, mgfSha1))
        val maskedSeed = xorInto(seed.copyOf(), mgf1(maskedDb, hLen, mgfSha1))
        val em = Bytes.concat(byteArrayOf(0), maskedSeed, maskedDb)
        val c = BigInteger(1, em).modPow(pub.publicExponent, pub.modulus)
        return toFixed(c, k)
    }

    /** RSA-OAEP 解密。 */
    fun oaepDecrypt(privateKey: java.security.PrivateKey, cipher: ByteArray, mgfSha1: Boolean): ByteArray? {
        val priv = privateKey as RSAPrivateCrtKey
        val n = priv.modulus
        val k = (n.bitLength() + 7) / 8
        if (cipher.size != k) return null
        val c = BigInteger(1, cipher)
        val m = if (priv.primeExponentP != null && priv.primeExponentQ != null && priv.crtCoefficient != null) {
            val m1 = c.modPow(priv.primeExponentP, priv.primeP)
            val m2 = c.modPow(priv.primeExponentQ, priv.primeQ)
            // h = qInv * (m1 - m2) mod p；m = m2 + h*q mod pq
            val h = m1.subtract(m2).multiply(priv.crtCoefficient).mod(priv.primeP)
            m2.add(h.multiply(priv.primeQ)).mod(priv.primeP.multiply(priv.primeQ))
        } else {
            c.modPow(priv.privateExponent, n)
        }
        val em = toFixed(m, k)
        val hLen = 32
        if (em[0].toInt() != 0) return null
        val maskedSeed = em.copyOfRange(1, 1 + hLen)
        val maskedDb = em.copyOfRange(1 + hLen, k)
        val seed = xorInto(maskedSeed.copyOf(), mgf1(maskedDb, hLen, mgfSha1))
        val db = xorInto(maskedDb.copyOf(), mgf1(seed, k - hLen - 1, mgfSha1))
        val lHash = sha256(ByteArray(0))
        for (i in 0 until hLen) if (db[i] != lHash[i]) return null
        var i = hLen
        while (i < db.size && db[i].toInt() == 0) i++
        if (i >= db.size || db[i].toInt() != 1) return null
        return db.copyOfRange(i + 1, db.size)
    }

    private fun toFixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(size)
        if (raw.size > size) {
            System.arraycopy(raw, raw.size - size, out, 0, size)
        } else {
            System.arraycopy(raw, 0, out, size - raw.size, raw.size)
        }
        return out
    }
}
