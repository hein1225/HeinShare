package com.hishare.core

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 本机身份：RSA-2048 私钥持久化 + 由公钥派生的 device_hash / identity / node_id。
 *
 * 与 Rust 版 `src/src/lib.rs` 的派生方式一致，否则对端校验会失败。
 */
class Identity(private val keyFile: File) {

    /** 密钥对（懒加载：首次访问时才读盘或生成）。 */
    val keyPair: KeyPair by lazy { loadOrCreate() }

    val privateKey: PrivateKey get() = keyPair.private

    val publicDer: ByteArray by lazy { Crypto.publicDer(keyPair.public) }

    /** 16 个 ASCII 字符（十六进制），信封里按这 16 字节传输。 */
    val deviceHash: String by lazy {
        val digest = Crypto.hmacSha256(publicDer.copyOfRange(0, 16), publicDer)
        Bytes.hexUpper(digest.copyOfRange(0, 8))
    }

    /** 信封用的 16 字节 ASCII。 */
    val deviceHashBytes: ByteArray by lazy { deviceHash.toByteArray(Charsets.US_ASCII) }

    /** 12 位十六进制。 */
    val identityV2: String by lazy {
        Bytes.hexUpper(Crypto.hmacSha256(publicDer.copyOfRange(0, 16), publicDer).copyOfRange(0, 6))
    }

    /** 64 位十六进制小写。 */
    val nodeId: String by lazy {
        val masked = ByteArray(META_PREFIX_MASK.size)
        for (i in masked.indices) masked[i] = (META_PREFIX_MASK[i].toInt() xor 0x5A).toByte()
        Bytes.hexLower(Crypto.hmacSha256(publicDer.copyOfRange(0, 16), Bytes.concat(masked, publicDer)))
    }

    private fun loadOrCreate(): KeyPair {
        if (keyFile.exists() && keyFile.length() > 0) {
            try {
                val text = keyFile.readText()
                val privB64 = text.substringAfter("-----BEGIN PRIVATE KEY-----", "")
                    .substringBefore("-----END PRIVATE KEY-----")
                    .filter { !it.isWhitespace() }
                val pubB64 = text.substringAfter("-----BEGIN PUBLIC KEY-----", "")
                    .substringBefore("-----END PUBLIC KEY-----")
                    .filter { !it.isWhitespace() }
                val privDer = Base64.decode(privB64, Base64.DEFAULT)
                val pubDer = Base64.decode(pubB64, Base64.DEFAULT)
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privDer))
                val publicKey = Crypto.publicFromDer(pubDer)
                return KeyPair(publicKey, privateKey)
            } catch (e: Exception) {
                // 私钥损坏：重新生成，旧身份作废
            }
        }
        val pair = Crypto.generateRsa()
        val pem = buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            append(Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            append("\n-----END PRIVATE KEY-----\n")
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            append("\n-----END PUBLIC KEY-----\n")
        }
        keyFile.parentFile?.mkdirs()
        keyFile.writeText(pem)
        return pair
    }

    companion object {
        /** 与 Rust 版一致的 meta 前缀掩码。 */
        private val META_PREFIX_MASK = byteArrayOf(
            0x3b, 0x34, 0x1e, 0x28, 0x35, 0x2a, 0x7a, 0x17, 0x1f, 0x0e,
            0x1b, 0x05, 0x14, 0x15, 0x1e, 0x1f, 0x05, 0x13, 0x1e, 0x5a
        )
    }
}
