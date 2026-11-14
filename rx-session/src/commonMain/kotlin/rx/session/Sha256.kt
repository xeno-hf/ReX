package rx.session

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readTo

/** 邊餵邊算的 SHA-256（純 Kotlin，兩個平台同一份）。錄製時邊寫邊算，收檔不必把整個檔再讀一遍。 */
class Sha256 {
    private val h = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    private val block = ByteArray(64)
    private val w = IntArray(64)
    private var fill = 0
    private var length = 0L
    private var done = false

    fun update(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        check(!done) { "digest already taken" }
        length += len
        absorb(b, off, len)
    }

    /** 十六進位摘要。只能取一次，取完不能再 [update]。 */
    fun hex(): String {
        check(!done) { "digest already taken" }
        done = true
        val bits = length * 8
        val pad = ByteArray((if (fill < 56) 56 - fill else 120 - fill) + 8)
        pad[0] = 0x80.toByte()
        for (i in 0 until 8) pad[pad.size - 8 + i] = (bits ushr (56 - 8 * i)).toByte()
        absorb(pad, 0, pad.size)
        return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun absorb(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            val n = minOf(64 - fill, end - i)
            b.copyInto(block, fill, i, i + n)
            fill += n; i += n
            if (fill == 64) { compress(); fill = 0 }
        }
    }

    private fun compress() {
        for (t in 0 until 16) {
            val j = t * 4
            w[t] = (block[j].toInt() and 0xFF shl 24) or (block[j + 1].toInt() and 0xFF shl 16) or
                (block[j + 2].toInt() and 0xFF shl 8) or (block[j + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    private companion object {
        val K = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).let { k -> IntArray(64) { k[it].toInt() } }
    }
}

/** 寫出去的每個位元組都順手餵給 [sha]。 */
internal class HashingSink(private val out: RawSink) : RawSink {
    val sha = Sha256()
    private val chunk = ByteArray(8192)
    private val pass = Buffer()

    override fun write(source: Buffer, byteCount: Long) {
        var left = byteCount
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            source.readTo(chunk, 0, n)
            sha.update(chunk, 0, n)
            pass.write(chunk, 0, n)
            out.write(pass, n.toLong())
            left -= n
        }
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}
