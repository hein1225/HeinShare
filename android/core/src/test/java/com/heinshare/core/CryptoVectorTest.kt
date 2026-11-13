package com.heinshare.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 与 Python `cryptography` 生成的固定向量逐字节比对。
 *
 * 向量文件：core/src/test/resources/crypto_vectors.json
 */
class CryptoVectorTest {

    private fun vectors(): Map<String, Map<String, Any?>> {
        val text = javaClass.classLoader!!.getResourceAsStream("crypto_vectors.json")
            ?.readBytes()?.toString(Charsets.UTF_8)
        requireNotNull(text) { "找不到测试向量文件" }
        val root = Json.parseObject(text) ?: error("测试向量解析失败")
        val out = HashMap<String, Map<String, Any?>>()
        for ((k, v) in root.asMap()) {
            if (v is Map<*, *>) {
                val m = LinkedHashMap<String, Any?>()
                for ((kk, vv) in v) m[kk.toString()] = vv
                out[k] = m
            }
        }
        return out
    }

    private fun Map<String, Any?>.b(key: String): ByteArray =
        Bytes.unhex(this.getValue(key) as String)

    @Test
    fun aesGcmMatchesReference() {
        val v = vectors()["aes_gcm"]!!
        val sealed = Crypto.aesGcmEncrypt(v.b("key"), v.b("nonce"), v.b("plain"), v.b("aad"))
        assertArrayEquals(v.b("sealed"), sealed)
        assertArrayEquals(v.b("plain"), Crypto.aesGcmDecrypt(v.b("key"), v.b("nonce"), sealed, v.b("aad")))
    }

    @Test
    fun aesGcm128MatchesReference() {
        // 数据面用的是 AES-128（取 32 字节会话密钥的前 16 字节），与 Rust 版
        // `aes128gcm_encrypt_aad` 的 key[..16] 语义一致。
        val v = vectors()["aes_gcm128"]!!
        val sealed = Crypto.aesGcmEncrypt(v.b("key"), v.b("nonce"), v.b("plain"), v.b("aad"))
        assertArrayEquals(v.b("sealed"), sealed)
        assertArrayEquals(v.b("plain"), Crypto.aesGcmDecrypt(v.b("key"), v.b("nonce"), sealed, v.b("aad")))
    }

    @Test
    fun aesGcmWith16ByteNonceMatchesReference() {
        val v = vectors()["aes_gcm16"]!!
        val sealed = Crypto.aesGcmEncrypt(v.b("key"), v.b("nonce"), v.b("plain"))
        assertArrayEquals(v.b("sealed"), sealed)
        assertArrayEquals(v.b("plain"), Crypto.aesGcmDecrypt(v.b("key"), v.b("nonce"), sealed))
    }

    @Test
    fun platformGcmAgreesWithManualImplementation() {
        // 平台实现（走硬件 AES）必须与手写实现逐字节一致，
        // 否则安卓端与 Rust 端对同一个数据块会算出两套密文。
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val aad = ByteArray(64) { 'A'.code.toByte() }
        for (size in intArrayOf(0, 1, 15, 16, 17, 4096, 65535)) {
            val plain = ByteArray(size) { (it % 251).toByte() }
            val manual = Crypto.aesGcmEncryptManual(key, nonce, plain, aad)
            val platform = Crypto.aesGcmEncrypt(key, nonce, plain, aad)
            assertArrayEquals("size=$size", manual, platform)
            assertArrayEquals("size=$size", plain, Crypto.aesGcmDecrypt(key, nonce, platform, aad))
            assertArrayEquals("size=$size", plain, Crypto.aesGcmDecryptManual(key, nonce, manual, aad))
        }
    }

    @Test
    fun bigPayloadRoundTrip() {
        // 数据面一次就是一个 64KB 的数据块，必须能一次性加解密。
        val key = ByteArray(16) { (it * 3).toByte() }
        val nonce = ByteArray(12) { (it * 5).toByte() }
        val aad = ByteArray(64) { 'A'.code.toByte() }
        val plain = ByteArray(65535) { (it % 253).toByte() }
        val sealed = Crypto.aesGcmEncrypt(key, nonce, plain, aad)
        assertArrayEquals(plain, Crypto.aesGcmDecrypt(key, nonce, sealed, aad))
    }

    @Test
    fun chacha20Poly1305MatchesReference() {
        val v = vectors()["chacha"]!!
        val sealed = Crypto.chachaAeadEncrypt(v.b("key"), v.b("nonce"), v.b("aad"), v.b("plain"))
        assertArrayEquals(v.b("sealed"), sealed)
        assertArrayEquals(
            v.b("plain"),
            Crypto.chachaAeadDecrypt(v.b("key"), v.b("nonce"), v.b("aad"), sealed)
        )
    }

    @Test
    fun rsaOaepMatchesReference() {
        val v = vectors()["oaep"]!!
        val privateKey = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(v.b("priv_der")))
        val publicKey = Crypto.publicFromDer(v.b("pub_der"))
        // 参考实现给出的密文必须能解开
        assertArrayEquals(v.b("plain"), Crypto.oaepDecrypt(privateKey, v.b("cipher"), true))
        // 自己加密后再解开（往返一致）
        val mine = Crypto.oaepEncrypt(publicKey, v.b("plain"), true)
        assertArrayEquals(v.b("plain"), Crypto.oaepDecrypt(privateKey, mine, true))
        // MGF1 换成 SHA-256 时同样自洽
        val mine2 = Crypto.oaepEncrypt(publicKey, v.b("plain"), false)
        assertArrayEquals(v.b("plain"), Crypto.oaepDecrypt(privateKey, mine2, false))
        assertNotNull(Crypto.publicDer(publicKey))
    }

    @Test
    fun shaAndHmacMatchKnownVectors() {
        // RFC 6231 / 常见向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Bytes.hexLower(Crypto.sha256("abc".toByteArray(Charsets.UTF_8)))
        )
        // RFC 4231 测试用例 1
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Bytes.hexLower(Crypto.hmacSha256(Bytes.unhex("0b".repeat(20)), "Hi There".toByteArray(Charsets.UTF_8)))
        )
        // RFC 4231 测试用例 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Bytes.hexLower(
                Crypto.hmacSha256(
                    "Jefe".toByteArray(Charsets.UTF_8),
                    "what do ya want for nothing?".toByteArray(Charsets.UTF_8)
                )
            )
        )
    }

    @Test
    fun varintMatchesRustLayout() {
        // 与 Rust 版 varint_encode（低位在前，最后一字节 bit7 = 1）一致
        assertEquals("80", Bytes.hexLower(Bytes.varintEncode(0)))
        assertEquals("ff", Bytes.hexLower(Bytes.varintEncode(127)))
        assertEquals("0081", Bytes.hexLower(Bytes.varintEncode(128)))
        assertEquals("2c82", Bytes.hexLower(Bytes.varintEncode(300)))
        assertEquals("7f7f7fff", Bytes.hexLower(Bytes.varintEncode(0xFFFFFFF)))
    }

    @Test
    fun tlvRoundTrip() {
        val value = "hello".toByteArray(Charsets.UTF_8)
        val encoded = Bytes.tlvEncode(7, value)
        assertArrayEquals(value, Bytes.requireTlv(encoded, 7))
        assertArrayEquals(value, Bytes.tlvGet(encoded, 7))
    }
}
