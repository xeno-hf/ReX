package rx.protocol

import java.io.File

/**
 * 重放來源：把既有擷取當成「離線重播」，不上車就能開發整個 App。
 *
 * 支援三種素材：
 * - `.rxraw`（RX 自己的錄製，帶時戳）
 * - 帶時戳的片段：`[u32 ms][u16 len][chunk]…`
 * - 純位元組串流（早期 live_*.bin，無時戳 → 依 [fallbackFrameNanos] 等距補時戳）
 */
object Replay {
    data class Timed(val tNanos: Long, val frame: Frame)

    fun load(file: File, fallbackFrameNanos: Long = 3_268_000L /* ≈306 幀/s */): List<Timed> {
        val bytes = file.readBytes()
        if (bytes.size >= RxRaw.MAGIC.size && bytes.copyOf(RxRaw.MAGIC.size).contentEquals(RxRaw.MAGIC)) {
            return RxRaw.read(bytes.inputStream()).filter { it.dir == RxRaw.DIR_RX }.map { Timed(it.tNanos, it.frame) }.toList()
        }
        chunkedCapture(bytes)?.let { return it }
        return FrameParser.parseAll(bytes).first.mapIndexed { i, f -> Timed(i * fallbackFrameNanos, f) }
    }

    /** 嘗試以 `[u32 ms][u16 len][chunk]` 解讀；結構對不上就回 null。 */
    private fun chunkedCapture(b: ByteArray): List<Timed>? {
        if (b.size < 6 || (b[0] == 0xF8.toByte() && b[1] == 0x01.toByte())) return null
        val out = ArrayList<Timed>()
        var i = 0
        var lastMs = -1L
        // chunk 不保證幀對齊 → 整份共用一個解析器，幀時戳取「收完該幀的 chunk」
        val p = FrameParser { out += Timed(lastMs * 1_000_000L, it) }
        while (i < b.size) {
            if (b.size - i < 6) return null
            val ms = (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)
            val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8)
            if (len == 0 || i + 6 + len > b.size || ms < lastMs) return null
            lastMs = ms
            p.feed(b, i + 6, len)
            i += 6 + len
        }
        return out
    }
}
