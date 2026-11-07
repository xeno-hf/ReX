package rx.protocol

/**
 * 增量式 RX 幀解析器（TCP 串流不保證幀對齊）。
 *
 * 驗 CHK；通過即吐幀並跳過整幀，否則前進 1 byte 重新找同步。
 * 統計項目全部留著，給診斷頁用。
 */
class FrameParser(private val onFrame: (Frame) -> Unit) {

    class Stats {
        var frames = 0L; internal set
        var checksumErrors = 0L; internal set
        /** 不屬於任何合法幀而被丟棄的位元組。 */
        var skippedBytes = 0L; internal set
        /** CHK 正確但 LEN/DLC 不是 19B 標準幀的封包。 */
        var oddPackets = 0L; internal set
        override fun toString() =
            "frames=$frames chkErr=$checksumErrors skipped=$skippedBytes odd=$oddPackets"
    }

    val stats = Stats()
    private var buf = ByteArray(8192)
    private var len = 0

    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset) {
        ensure(len + length)
        chunk.copyInto(buf, len, offset, offset + length)
        len += length
        drain()
    }

    private fun ensure(cap: Int) {
        if (cap > buf.size) buf = buf.copyOf(maxOf(cap, buf.size * 2))
    }

    private fun drain() {
        var i = 0
        while (true) {
            // 至少要看得到 LEN 欄位
            if (len - i < 4) break
            if (buf[i] != Frame.SYNC[0] || buf[i + 1] != Frame.SYNC[1] || buf[i + 2] != Frame.SYNC[2]) {
                i++; stats.skippedBytes++; continue
            }
            val total = (buf[i + 3].toInt() and 0xFF) + 5
            if (len - i < total) break // 等更多資料
            val chk = Frame.checksum(buf, i + 2, i + total - 1)
            if (chk != (buf[i + total - 1].toInt() and 0xFF)) {
                stats.checksumErrors++
                i++; stats.skippedBytes++; continue
            }
            emit(i, total)
            i += total
        }
        if (i > 0) {
            buf.copyInto(buf, 0, i, len)
            len -= i
        }
    }

    private fun emit(at: Int, total: Int) {
        val dlc = if (total > 9) buf[at + 9].toInt() and 0xFF else -1
        if (total != Frame.FRAME_LEN || dlc !in 0..8) {
            stats.oddPackets++
            return
        }
        val canId = ((buf[at + 4].toInt() and 0xFF) shl 24) or ((buf[at + 5].toInt() and 0xFF) shl 16) or
            ((buf[at + 6].toInt() and 0xFF) shl 8) or (buf[at + 7].toInt() and 0xFF)
        val flags = buf[at + 8].toInt() and 0xFF
        val data = buf.copyOfRange(at + 10, at + 10 + dlc)
        stats.frames++
        onFrame(Frame(canId, data, flags))
    }

    companion object {
        /** 一次解析整段位元組（重放、測試用）。 */
        fun parseAll(bytes: ByteArray): Pair<List<Frame>, Stats> {
            val out = ArrayList<Frame>()
            val p = FrameParser { out += it }
            p.feed(bytes)
            return out to p.stats
        }
    }
}
