package xb.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.MD5
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * 跨平台的加密原語（雜湊、HMAC、PBKDF2、AES-CBC／GCM、ECDH P-256、亂數）。全部是同步呼叫（cryptography-kotlin 的 *Blocking 版本），可以直接替換原本的 javax 寫法。
 * 平台實作由 provider 決定：JVM／Android＝JDK，iOS＝CommonCrypto＋CryptoKit。
 * MD5、SHA-1、AES-CBC 被函式庫標成「不建議」：這裡是為了讀寫 XTune 的既有格式和官方清單的 md5，不是自選的設計。
 */
@OptIn(DelicateCryptographyApi::class)
object XbCrypto {
    private val provider get() = platformProvider

    fun md5(data: ByteArray): ByteArray = provider.get(MD5).hasher().hashBlocking(data)

    fun sha256(data: ByteArray): ByteArray = provider.get(SHA256).hasher().hashBlocking(data)

    /** 分段餵資料的 SHA-256（大檔案邊讀邊算）。用完要 [Sha256.close]。 */
    fun sha256Stream(): Sha256 = Sha256(provider.get(SHA256).hasher().createHashFunction())

    class Sha256 internal constructor(private val f: dev.whyoleg.cryptography.operations.HashFunction) : AutoCloseable {
        fun update(data: ByteArray, from: Int = 0, to: Int = data.size) = f.update(data, from, to)
        /** 算出結果並歸零，之後可以重新 [update] 下一份。 */
        fun digest(): ByteArray = f.hashToByteArray().also { f.reset() }
        override fun close() = f.close()
    }

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = hmac(SHA1, key, data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmac(SHA256, key, data)

    private fun hmac(digest: CryptographyAlgorithmId<Digest>, key: ByteArray, data: ByteArray): ByteArray =
        provider.get(HMAC).keyDecoder(digest).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator().generateSignatureBlocking(data)

    /**
     * PBKDF2-HMAC-SHA1（RFC 8018），輸出 [dkLen] 位元組。
     * 自己在 HMAC 上算，不用函式庫的 PBKDF2：JDK 版會先把密碼當 UTF-8 文字解碼，任意位元組的金鑰會失敗或算錯。
     * 空密碼一律拒絕（JDK 的 HMAC 不收空金鑰，各平台統一成同一個行為）。
     */
    fun pbkdf2Sha1(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(password.isNotEmpty()) { "PBKDF2 密碼不能是空的" }
        require(iterations >= 1 && dkLen >= 0)
        val key = provider.get(HMAC).keyDecoder(SHA1).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, password)
        return key.signatureGenerator().createSignFunction().use { mac ->
            fun sign(): ByteArray = mac.signToByteArray().also { mac.reset() }
            val out = ByteArray(dkLen)
            var block = 1
            var pos = 0
            while (pos < dkLen) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = sign()
                val t = u.copyOf()
                repeat(iterations - 1) {
                    mac.update(u)
                    u = sign()
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val take = minOf(t.size, dkLen - pos)
                t.copyInto(out, pos, 0, take)
                pos += take
                block++
            }
            out
        }
    }

    /** AES-CBC 解密；[padding]＝PKCS#7（JDK 叫 PKCS5Padding），false＝NoPadding（長度要是 16 的倍數）。 */
    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, padding: Boolean = true): ByteArray =
        cbc(key, padding).decryptWithIvBlocking(iv, data)

    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray, padding: Boolean = true): ByteArray =
        cbc(key, padding).encryptWithIvBlocking(iv, data)

    private fun cbc(key: ByteArray, padding: Boolean) =
        provider.get(AES.CBC).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key).cipher(padding)

    /** 密碼學等級的亂數產生器（JVM＝SecureRandom，iOS＝SecRandomCopyBytes），可當一般的 [kotlin.random.Random] 用（例如挑配對碼的字）。 */
    val secureRandom: kotlin.random.Random get() = CryptographyRandom.Default

    fun randomBytes(n: Int): ByteArray = CryptographyRandom.Default.nextBytes(n)

    /**
     * AES-GCM，128 位元 tag；輸出＝密文＋tag（跟 JDK 的 AES/GCM/NoPadding 同一個格式）。[nonce] 一定要 12 位元組，其他長度直接拒絕。
     * 同一把金鑰的 nonce 絕對不能重複（重複會洩漏內容），由呼叫的人負責：這裡每次呼叫是獨立的，不記得用過哪些 nonce。
     */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray = gcm(key, nonce).encryptWithIvBlocking(nonce, plain)

    /** [aesGcmEncrypt] 的反向；被竄改、金鑰或 nonce 不對就丟例外。 */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray): ByteArray = gcm(key, nonce).decryptWithIvBlocking(nonce, sealed)

    private fun gcm(key: ByteArray, nonce: ByteArray) =
        provider.get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .also { require(nonce.size == 12) { "AES-GCM nonce 要 12 位元組，拿到 ${nonce.size}" } }
            .cipher(128.bits)

    /** ECDH P-256 的一次性金鑰對。[publicDer]＝X.509 SubjectPublicKeyInfo（跟 JDK 的 `PublicKey.encoded` 同一個格式）。 */
    class EcdhKeyPair internal constructor(internal val pair: EcdhPair) {
        val publicDer: ByteArray get() = pair.publicDer
    }

    fun ecdhP256KeyPair(): EcdhKeyPair = EcdhKeyPair(newEcdhPair())

    /** 共享秘密（32 位元組的 x 座標，跟 JDK 的 KeyAgreement "ECDH" 一樣）。[theirsDer] 不是 P-256 的公鑰就丟例外；丟過之後下一把正確的公鑰照樣能用。 */
    fun ecdhP256(mine: EcdhKeyPair, theirsDer: ByteArray): ByteArray = mine.pair.agree(theirsDer)

    /** 比較兩段位元組，花的時間跟內容無關（比對 MAC 用）。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    fun hex(data: ByteArray, upper: Boolean = false): String {
        val digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        val sb = StringBuilder(data.size * 2)
        for (b in data) sb.append(digits[(b.toInt() shr 4) and 0xF]).append(digits[b.toInt() and 0xF])
        return sb.toString()
    }
}

internal expect val platformProvider: CryptographyProvider

/** 平台的 ECDH P-256：JVM／Android 直接用 JCA，iOS 走 cryptography-kotlin（CryptoKit）。 */
internal interface EcdhPair {
    val publicDer: ByteArray
    fun agree(theirsDer: ByteArray): ByteArray
}

internal expect fun newEcdhPair(): EcdhPair
