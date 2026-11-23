package xb.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** 跟原本的 javax 寫法逐 byte 對照：隨機的長度與內容（固定種子，失敗可以重現）。 */
class JdkParityTest {
    private val rnd = Random(20261123)
    private fun bytes(n: Int) = rnd.nextBytes(n)
    private fun lengths() = listOf(0, 1, 15, 16, 17, 31, 32, 33, 1000, 65_537) + List(20) { rnd.nextInt(0, 5000) }

    /** 原本 TuneContainer 手寫的 PBKDF2-HMAC-SHA1，照搬來對照。 */
    private fun legacyPbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(password, "HmacSHA1"))
        val out = ByteArray(dkLen); var block = 1; var pos = 0
        while (pos < dkLen) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            repeat(iterations - 1) { u = mac.doFinal(u); for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte() }
            val take = minOf(t.size, dkLen - pos); System.arraycopy(t, 0, out, pos, take); pos += take; block++
        }
        return out
    }

    @Test fun digestsAndHmac() {
        for (n in lengths()) {
            val d = bytes(n); val k = bytes(rnd.nextInt(1, 100))
            assertContentEquals(MessageDigest.getInstance("MD5").digest(d), XbCrypto.md5(d), "md5 $n")
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(d), XbCrypto.sha256(d), "sha256 $n")
            assertContentEquals(Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(k, "HmacSHA1")) }.doFinal(d), XbCrypto.hmacSha1(k, d), "hmac1 $n")
            assertContentEquals(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")) }.doFinal(d), XbCrypto.hmacSha256(k, d), "hmac256 $n")
        }
    }

    @Test fun pbkdf2MatchesTheLegacyLoop() {
        repeat(10) {
            val p = bytes(rnd.nextInt(1, 64)); val s = bytes(rnd.nextInt(0, 64))
            assertContentEquals(legacyPbkdf2(p, s, 1000, 32), XbCrypto.pbkdf2Sha1(p, s, 1000, 32))
        }
        // 不是合法 UTF-8 的密碼（函式庫的 JDK 版 PBKDF2 在這裡會失敗）、空 salt、長密碼（超過 HMAC 區塊要先雜湊）
        for ((p, s) in listOf(byteArrayOf(-1, -2, 0, -128) to byteArrayOf(-1, 0, 127, -128), "XenoBrex-キー".encodeToByteArray() to ByteArray(0), bytes(200) to bytes(16)))
            assertContentEquals(legacyPbkdf2(p, s, 1000, 32), XbCrypto.pbkdf2Sha1(p, s, 1000, 32))
    }

    @Test fun aesCbcBothPaddings() {
        for (n in lengths()) {
            val key = bytes(16); val iv = bytes(16); val d = bytes(n)
            fun jdk(mode: Int, pad: String, b: ByteArray) = Cipher.getInstance("AES/CBC/$pad").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(b)
            val enc = jdk(Cipher.ENCRYPT_MODE, "PKCS5Padding", d)
            assertContentEquals(enc, XbCrypto.aesCbcEncrypt(key, iv, d), "enc $n")
            assertContentEquals(d, XbCrypto.aesCbcDecrypt(key, iv, enc), "dec $n")
            val whole = d.copyOf(n / 16 * 16)
            val raw = jdk(Cipher.ENCRYPT_MODE, "NoPadding", whole)
            assertContentEquals(raw, XbCrypto.aesCbcEncrypt(key, iv, whole, padding = false), "enc nopad $n")
            assertContentEquals(jdk(Cipher.DECRYPT_MODE, "NoPadding", raw), XbCrypto.aesCbcDecrypt(key, iv, raw, padding = false), "dec nopad $n")
        }
    }

    @Test fun aesGcmMatchesJdk() {
        for (n in lengths()) {
            val key = bytes(32); val nonce = bytes(12); val d = bytes(n)
            fun jdk(mode: Int, b: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(b)
            val sealed = jdk(Cipher.ENCRYPT_MODE, d)
            val mine = XbCrypto.aesGcmEncrypt(key, nonce, d)
            assertContentEquals(sealed, mine, "enc $n")
            assertContentEquals(d, XbCrypto.aesGcmDecrypt(key, nonce, sealed), "dec $n")
            assertContentEquals(d, jdk(Cipher.DECRYPT_MODE, mine), "jdk dec $n")
        }
    }

    /** 跟舊版 ReX／XB（JDK 的 EC＋KeyAgreement）配對：兩邊互換 DER 公鑰，算出同一個秘密。 */
    @Test fun ecdhInteroperatesWithJdk() {
        repeat(10) {
            val jdkPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.genKeyPair()
            val mine = XbCrypto.ecdhP256KeyPair()
            val jdkSecret = KeyAgreement.getInstance("ECDH").apply {
                init(jdkPair.private); doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(mine.publicDer)), true)
            }.generateSecret()
            assertContentEquals(jdkSecret, XbCrypto.ecdhP256(mine, jdkPair.public.encoded))
        }
    }
}
