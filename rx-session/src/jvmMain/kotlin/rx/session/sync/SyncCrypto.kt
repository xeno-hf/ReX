package rx.session.sync

import xb.crypto.XbCrypto
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** 同步用的密碼學小工具：P-256、HMAC-SHA256、AES-256-GCM（[XbCrypto]：JVM／Android＝JDK，iOS＝CryptoKit）。 */
object SyncCrypto {
    private val random = XbCrypto.secureRandom

    fun randomBytes(n: Int): ByteArray = XbCrypto.randomBytes(n)

    /** 每段前面加長度（u32 大端序），避免「ab|c」跟「a|bc」算出一樣的值。 */
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        for (p in parts) {
            val n = p.size
            buf.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            buf.write(p)
        }
        return XbCrypto.hmacSha256(key, buf.toByteArray())
    }

    fun sha256(b: ByteArray): ByteArray = XbCrypto.sha256(b)

    fun same(a: ByteArray, b: ByteArray): Boolean = XbCrypto.constantTimeEquals(a, b)

    /** 一次性的 P-256 金鑰對；公鑰用 [XbCrypto.EcdhKeyPair.publicDer]（X.509 DER，跟舊版 JDK 的 `public.encoded` 相同）。 */
    fun ecKeyPair(): XbCrypto.EcdhKeyPair = XbCrypto.ecdhP256KeyPair()

    /** [theirsDer]：對方的 X.509 DER 公鑰。 */
    fun ecdh(mine: XbCrypto.EcdhKeyPair, theirsDer: ByteArray): ByteArray = XbCrypto.ecdhP256(mine, theirsDer)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid hex" }
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** 配對碼：16 個字，去掉容易看錯的 0/O、1/I/L（Crockford 字母表少一些）。每 4 個字一組顯示（格式見 [rx.session.SyncCode]）。 */
    private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** 配對碼長度（[rx.session.SyncCode.LENGTH]）。 */
    const val CODE_LENGTH = rx.session.SyncCode.LENGTH

    fun newPairingCode(): String = (1..CODE_LENGTH).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")

    /** 使用者輸入的配對碼：去空白、橫線，轉大寫，O→0 這類常見打錯不猜。 */
    fun normalizeCode(text: String): String = rx.session.SyncCode.normalize(text)

    fun showCode(code: String): String = code.chunked(4).joinToString("-")
}

/**
 * 加密的單向串流：每次 flush（或緩衝滿 [CHUNK]）送出一筆 `len u32 LE | AES-256-GCM(資料)`。
 * nonce＝方向固定的 4 bytes 0＋8 bytes 序號；兩個方向用不同金鑰，所以不會重複。
 */
class SealedOutputStream(private val out: OutputStream, key: ByteArray) : OutputStream() {
    private val k = key.copyOf()
    private val buf = java.io.ByteArrayOutputStream(CHUNK)
    private var seq = 0L

    override fun write(b: Int) { buf.write(b); if (buf.size() >= CHUNK) emit() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off; var n = len
        while (n > 0) {
            val take = minOf(n, CHUNK - buf.size())
            buf.write(b, o, take); o += take; n -= take
            if (buf.size() >= CHUNK) emit()
        }
    }

    override fun flush() { if (buf.size() > 0) emit(); out.flush() }
    override fun close() { flush(); out.close() }

    private fun emit() {
        val ct = XbCrypto.aesGcmEncrypt(k, nonce(seq++), buf.toByteArray())
        buf.reset()
        val n = ct.size
        out.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()))
        out.write(ct)
    }

    companion object {
        const val CHUNK = 1 shl 15
        fun nonce(seq: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (seq ushr (56 - 8 * i)).toByte() }
    }
}

/** [SealedOutputStream] 的另一頭。被竄改、截斷或順序不對就丟例外。 */
class SealedInputStream(private val inp: InputStream, key: ByteArray) : InputStream() {
    private val k = key.copyOf()
    private var cur = ByteArray(0)
    private var pos = 0
    private var seq = 0L

    /** 從底層實際讀進來的位元組數（包括還沒收完的這一筆）：分得出「對方一個字都沒回」跟「回到一半斷掉」。 */
    var rawBytesRead = 0L
        private set

    private fun fill(): Boolean {
        while (pos >= cur.size) {
            val h = ByteArray(4)
            var got = 0
            while (got < 4) {
                val r = inp.read(h, got, 4 - got)
                if (r < 0) { if (got == 0) return false else throw EOFException("Truncated sync record") }
                got += r; rawBytesRead += r
            }
            val n = (h[0].toInt() and 255) or ((h[1].toInt() and 255) shl 8) or ((h[2].toInt() and 255) shl 16) or ((h[3].toInt() and 255) shl 24)
            require(n in 16..(SealedOutputStream.CHUNK + 16)) { "Invalid sync record size" }
            val ct = ByteArray(n)
            var off = 0
            while (off < n) { val r = inp.read(ct, off, n - off); if (r < 0) throw EOFException("Truncated sync record"); off += r; rawBytesRead += r }
            cur = XbCrypto.aesGcmDecrypt(k, SealedOutputStream.nonce(seq++), ct)
            pos = 0
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else cur[pos++].toInt() and 255

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, cur.size - pos)
        cur.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    override fun available(): Int = cur.size - pos
    override fun close() = inp.close()
}
