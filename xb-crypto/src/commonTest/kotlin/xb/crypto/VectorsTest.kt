package xb.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 公開的標準測試向量（RFC 1321／FIPS 180-2／RFC 2202／RFC 4231／RFC 6070／NIST SP 800-38A／GCM 規格 Test Case 13–14）。各平台都要算出一樣的結果。 */
class VectorsTest {
    private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = XbCrypto.hex(b)

    @Test fun digests() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(XbCrypto.md5("abc".encodeToByteArray())))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(XbCrypto.md5(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(XbCrypto.sha256("abc".encodeToByteArray())))
        assertEquals("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855", XbCrypto.hex(XbCrypto.sha256(ByteArray(0)), upper = true))
    }

    @Test fun streamingSha256MatchesOneShotAndResets() {
        val data = ByteArray(200_000) { (it * 31 + 7).toByte() }
        XbCrypto.sha256Stream().use { s ->
            var i = 0
            while (i < data.size) { val n = minOf(8191, data.size - i); s.update(data, i, i + n); i += n }
            assertContentEquals(XbCrypto.sha256(data), s.digest())
            s.update("abc".encodeToByteArray())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(s.digest()))
        }
    }

    @Test fun hmac() {
        val data = "what do ya want for nothing?".encodeToByteArray()
        assertEquals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79", hex(XbCrypto.hmacSha1("Jefe".encodeToByteArray(), data)))
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", hex(XbCrypto.hmacSha256("Jefe".encodeToByteArray(), data)))
    }

    @Test fun pbkdf2Sha1() {
        val p = "password".encodeToByteArray(); val s = "salt".encodeToByteArray()
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", hex(XbCrypto.pbkdf2Sha1(p, s, 1, 20)))
        assertEquals("4b007901b765489abead49d926f721d065a429c1", hex(XbCrypto.pbkdf2Sha1(p, s, 4096, 20)))
        // 輸出比一個 SHA-1 區塊長（調校檔容器要 32 位元組）
        assertEquals("3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            hex(XbCrypto.pbkdf2Sha1("passwordPASSWORDpassword".encodeToByteArray(), "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray(), 4096, 25)))
    }

    @Test fun aesCbc() {
        val key = h("2b7e151628aed2a6abf7158809cf4f3c"); val iv = h("000102030405060708090a0b0c0d0e0f")
        val plain = h("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        val cipher = h("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")
        assertContentEquals(cipher, XbCrypto.aesCbcEncrypt(key, iv, plain, padding = false))
        assertContentEquals(plain, XbCrypto.aesCbcDecrypt(key, iv, cipher, padding = false))
        // 只解第一個區塊（快速篩金鑰用）
        assertContentEquals(plain.copyOf(16), XbCrypto.aesCbcDecrypt(key, iv, cipher.copyOf(16), padding = false))
        // PKCS#7：剛好整數個區塊時多補一整塊 0x10
        val padded = XbCrypto.aesCbcEncrypt(key, iv, plain)
        assertEquals(48, padded.size)
        assertContentEquals(cipher, padded.copyOf(32))
        assertContentEquals(plain, XbCrypto.aesCbcDecrypt(key, iv, padded))
        val odd = "<?xml version=\"1.0\"?>".encodeToByteArray()
        assertContentEquals(odd, XbCrypto.aesCbcDecrypt(key, iv, XbCrypto.aesCbcEncrypt(key, iv, odd)))
    }

    @Test fun aesGcm() {
        val key = ByteArray(32); val nonce = ByteArray(12)
        // GCM 規格（McGrew & Viega）Test Case 14：一個全 0 區塊。同一把金鑰配同一個 nonce 只加密一次（JDK 會拒絕重複）
        val sealed = XbCrypto.aesGcmEncrypt(key, nonce, ByteArray(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919", hex(sealed))
        assertContentEquals(ByteArray(16), XbCrypto.aesGcmDecrypt(key, nonce, sealed))
        // Test Case 13：空內容，只有 tag
        assertContentEquals(ByteArray(0), XbCrypto.aesGcmDecrypt(key, nonce, h("530f8afbc74536b9a963b4f1c4cb738b")))
        // 改一個位元、換 nonce、截掉 tag：都要拒絕
        assertFailsWith<Exception> { XbCrypto.aesGcmDecrypt(key, nonce, sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }) }
        assertFailsWith<Exception> { XbCrypto.aesGcmDecrypt(key, ByteArray(12).also { it[11] = 1 }, sealed) }
        assertFailsWith<Exception> { XbCrypto.aesGcmDecrypt(key, nonce, sealed.copyOf(sealed.size - 1)) }
    }

    @Test fun ecdhP256() {
        val a = XbCrypto.ecdhP256KeyPair(); val b = XbCrypto.ecdhP256KeyPair()
        val der = a.publicDer
        // X.509 SubjectPublicKeyInfo，未壓縮點：91 位元組，開頭是 SEQUENCE、id-ecPublicKey、prime256v1
        assertEquals(91, der.size)
        assertEquals("3059301306072a8648ce3d020106082a8648ce3d03010703420004", hex(der.copyOf(27)))
        val ab = XbCrypto.ecdhP256(a, b.publicDer); val ba = XbCrypto.ecdhP256(b, a.publicDer)
        assertEquals(32, ab.size)
        assertContentEquals(ab, ba)
        assertFalse(ab.contentEquals(XbCrypto.ecdhP256(a, XbCrypto.ecdhP256KeyPair().publicDer)))
        assertFailsWith<Exception> { XbCrypto.ecdhP256(a, der.copyOf(40)) }
        // 不在曲線上的點（y 最後一個 bit 翻掉）要拒絕，而且拒絕過之後正確的公鑰還是要能用（Android 上曾經會卡死到重開 App）
        val offCurve = b.publicDer.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { XbCrypto.ecdhP256(a, offCurve) }
        assertFailsWith<Exception> { XbCrypto.ecdhP256(a, byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)) }
        assertContentEquals(ab, XbCrypto.ecdhP256(a, b.publicDer))
    }

    @Test fun inputContracts() {
        val key = ByteArray(32) { 0x5a } // 不跟 aesGcm 的全 0 金鑰／nonce 重複（JDK 會拒絕重用同一組）
        for (n in listOf(0, 8, 11, 13, 16)) {
            assertFailsWith<IllegalArgumentException> { XbCrypto.aesGcmEncrypt(key, ByteArray(n), ByteArray(1)) }
            assertFailsWith<IllegalArgumentException> { XbCrypto.aesGcmDecrypt(key, ByteArray(n), ByteArray(17)) }
        }
        assertEquals(17, XbCrypto.aesGcmEncrypt(key, ByteArray(12) { 7 }, ByteArray(1)).size) // 128 位元 tag
        assertFailsWith<IllegalArgumentException> { XbCrypto.pbkdf2Sha1(ByteArray(0), ByteArray(8), 1, 20) }
    }

    @Test fun randomAndCompare() {
        val r = XbCrypto.randomBytes(32)
        assertEquals(32, r.size)
        assertFalse(r.contentEquals(XbCrypto.randomBytes(32)))
        assertTrue(XbCrypto.constantTimeEquals(r, r.copyOf()))
        assertFalse(XbCrypto.constantTimeEquals(r, r.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }))
        assertFalse(XbCrypto.constantTimeEquals(r, r.copyOf(31)))
    }
}
