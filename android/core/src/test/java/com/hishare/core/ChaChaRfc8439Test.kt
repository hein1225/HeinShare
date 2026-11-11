package com.hishare.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * RFC 8439 §2.8.2 的 ChaCha20-Poly1305 AEAD 官方测试向量。
 *
 * 与 Rust 版 `src/src/chacha.rs` 的 `rfc8439_aead_vector` 用例一一对应，
 * 保证 Android 端与 Windows 端在 Apple 通道上的密文/校验标签完全一致。
 */
class ChaChaRfc8439Test {

    @Test
    fun rfc8439AeadVector() {
        val key = Bytes.unhex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = Bytes.unhex("070000004041424344454647")
        val aad = Bytes.unhex("50515253c0c1c2c3c4c5c6c7")
        val plain = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only " +
                "one tip for the future, sunscreen would be it."
            ).toByteArray(Charsets.UTF_8)
        val wantCt = Bytes.unhex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116"
        )
        val wantTag = Bytes.unhex("1ae10b594f09e26a7e902ecbd0600691")

        val sealed = Crypto.chachaAeadEncrypt(key, nonce, aad, plain)
        assertArrayEquals(wantCt, sealed.copyOfRange(0, sealed.size - 16))
        assertArrayEquals(wantTag, sealed.copyOfRange(sealed.size - 16, sealed.size))
        assertArrayEquals(plain, Crypto.chachaAeadDecrypt(key, nonce, aad, sealed))
    }
}
