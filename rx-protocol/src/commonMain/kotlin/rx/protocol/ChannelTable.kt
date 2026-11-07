package rx.protocol

/**
 * ECU 監控頻道表（記憶體 0x1A00）。
 *
 * 每格 u32 = `((cellSize << 3) & 0xFF) | ((absid + 16384) << 16)`，slot 從 1 起；
 * slot 0 = 通道數（u8）。寫入序列：monitorStop → 各格 → 數量 → monitorStart。
 * 證據：XTune ；實車 73/73 ACK、流長 106B 分毫不差（2026-09-20）。
 *
 * 只能放「值通道」（母通道）；位元子通道由母通道 raw 展開，見 rx-defs。
 */
class ChannelTable(val entries: List<Entry>) {

    data class Entry(val absid: Int, val cellSize: Int) {
        init {
            require(cellSize in 1..4) { "absid $absid cellSize=$cellSize" }
            require(absid in 0 until 0xC000) { "absid $absid out of wire range" }
        }

        /** 寫進 0x1A00 的 u32。 */
        val encoded: Long get() = (((cellSize shl 3) and 0xFF).toLong()) or (((absid + 16384).toLong() shl 16) and 0xFFFF0000L)
    }

    init {
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "channel count ${entries.size}" }
    }

    /** 一輪 payload 的總長度（bytes）。 */
    val payloadLength: Int = entries.sumOf { it.cellSize }

    /** 每輪需要的 0x181 分片數（標準幀每片帶 7B）。 */
    val fragmentsPerRound: Int = (payloadLength + 6) / 7

    /** 各通道在 payload 中的起始 offset。 */
    val offsets: IntArray = IntArray(entries.size).also { o ->
        var acc = 0
        entries.forEachIndexed { i, e -> o[i] = acc; acc += e.cellSize }
    }

    /** 整張表的寫入序列（不含登入；寫入閘門負責送與等 ACK）。 */
    fun writeSequence(rpc: Rpc): List<Frame> = buildList {
        add(rpc.monitorStop())
        entries.forEachIndexed { i, e -> add(rpc.smallWrite(Rpc.CHAN_TABLE_ADDR, i + 1, e.encoded, 4)) }
        add(rpc.smallWrite(Rpc.CHAN_TABLE_ADDR, 0, entries.size.toLong(), 1))
        add(rpc.monitorStart())
    }

    /** 解包一輪 payload → 各通道 raw（BIG-ENDIAN 無號）。長度不符時回 null。 */
    fun unpack(payload: ByteArray): LongArray? {
        if (payload.size != payloadLength) return null
        return LongArray(entries.size) { i ->
            var v = 0L
            val off = offsets[i]
            for (k in 0 until entries[i].cellSize) v = (v shl 8) or (payload[off + k].toLong() and 0xFF)
            v
        }
    }

    /**
     * 預估整輪刷新率。實測出廠 19 片表約 16Hz/片、≈306 幀/s（2026-09-19）；
     * 若 ECU 是固定幀率輪播，則刷新率 ≈ framesPerSecond / 片數。
     */
    fun estimatedRoundHz(framesPerSecond: Double = 306.0): Double = framesPerSecond / fragmentsPerRound

    companion object {
        /** 0x1A00 slot 用 u8 子索引，slot0 被數量占用。實際上限待實車，先保守取 127。 */
        const val MAX_ENTRIES = 127
    }
}
