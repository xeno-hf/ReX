package rx.protocol

/**
 *
 * - `data[0] & 0x7F` = 片序（從 1 起算）；片序 1 = 清緩衝重新開始
 * - `data[0] & 0x80` = 末片 → 這一輪完整，按頻道表順序解包
 * - payload = `data[1 until dlc]`（含 DLC4 短幀變體 `81 7e 00 00`）
 *
 * XTune 不檢查漏片；RX 會檢查片序連續性與總長，有問題的輪次標記為不完整而不是靜默吞掉。
 */
class BroadcastReassembler(
    /** 預期 payload 長度（頻道表總 cell 數）；未知時給 null，只檢查片序。 */
    var expectedLength: Int? = null,
    private val onRound: (Round) -> Unit,
) {
    /** 串流 CAN ID（0x180|node）；預設 MX 0x181，平台判別後由 Session 改。 */
    var canId: Int = CanId.BROADCAST
    data class Round(
        val payload: ByteArray,
        val fragments: Int,
        /** 片序連續且（若已知）長度相符。 */
        val complete: Boolean,
        val reason: String?,
        /** 首片與末片的時戳（呼叫端提供，單位不拘；RX 用 elapsedRealtimeNanos）。 */
        val tFirst: Long,
        val tLast: Long,
    )

    class Stats {
        var rounds = 0L; internal set
        var incomplete = 0L; internal set
        /** 未收到片序 1 就出現的片（例如剛連上時的半輪）。 */
        var orphanFragments = 0L; internal set
    }

    val stats = Stats()
    private val buf = ByteSink(256)
    private var nextSlot = -1     // -1 = 尚未同步
    private var fragments = 0
    private var broken: String? = null
    private var tFirst = 0L

    fun feed(frame: Frame, t: Long = 0L) {
        if (frame.canId != canId || frame.dlc < 1) return
        val head = frame.u8(0)
        val slot = head and 0x7F
        val last = head and 0x80 != 0

        if (slot == 1) {
            buf.reset(); fragments = 0; broken = null; tFirst = t; nextSlot = 1
        } else if (nextSlot < 0) {
            stats.orphanFragments++
            return
        } else if (slot != nextSlot && broken == null) {
            broken = "slot skipped: expected $nextSlot, got $slot"
        }
        buf.write(frame.data, 1, frame.dlc - 1)
        fragments++
        nextSlot = slot + 1

        if (last) {
            val payload = buf.toByteArray()
            var reason = broken
            val exp = expectedLength
            if (reason == null && exp != null && payload.size != exp) {
                reason = "length ${payload.size} B != table ${exp} B"
            }
            stats.rounds++
            if (reason != null) stats.incomplete++
            onRound(Round(payload, fragments, reason == null, reason, tFirst, t))
            buf.reset(); fragments = 0; broken = null; nextSlot = -1
        }
    }
}
