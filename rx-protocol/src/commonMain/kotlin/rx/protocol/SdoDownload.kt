package rx.protocol


/**
 * 一次 SDO 寫入（download，CiA 301）的純狀態機：不碰 I/O，好測試。用法同 ReX 的 `SdoUpload`：
 * 送 [start]，之後每收到一幀 RPC 回應（0x580|node）就 [onReply]，照回傳的 [Step] 送幀或收尾。
 *
 * - 4 bytes 以內：expedited `0x23|(4−n)<<2`，ECU 回 `0x60`。
 * - 超過 4 bytes：區塊寫入。`0xC6`（宣告支援 CRC、帶長度）→ ECU 回 `0xA0|sc<<2`＋區塊大小 →
 *   送分段（序號 1 起，末段 bit7）→ ECU 回 `0xA2 ackseq blksize` → 送 `0xC1|n<<2`（n＝末段空位數，ECU 要 CRC 時帶 CRC）→ ECU 回 `0xA1`。
 *   XTune 只送一個區塊（分段數超過區塊大小就失敗）；這裡照 CiA 301 分多個區塊送。
 * TCP 橋不掉幀，ECU 確認的序號比送出的少就直接失敗，不做部分重送。
 */
class SdoDownload(
    val rpcId: Int,
    val index: Int,
    val sub: Int,
    data: ByteArray,
) {
    val data: ByteArray = data.copyOf()

    sealed interface Step {
        data class Send(val frames: List<Frame>) : Step
        data object Wait : Step
        data object Done : Step
        data class Failed(val reason: String, val abort: Sdo.Abort? = null) : Step
    }

    init {
        require(index in 0..0xFFFF && sub in 0..0xFF)
        require(data.isNotEmpty()) { "nothing to write" }
    }

    val expedited: Boolean get() = data.size <= 4

    private enum class S { INIT, BLOCK, END, DONE }
    private var s = S.INIT
    private val segments: List<ByteArray> = data.toList().chunked(7).map { it.toByteArray() }
    private var next = 0          // 下一個要送的分段（在 segments 裡的位置）
    private var blockStart = 0    // 這個區塊第一段的位置
    private var blkSize = 0
    private var serverCrc = false

    private val replyId = (rpcId and 0x7F) or 0x580

    private fun f(vararg b: Int) = Frame(rpcId, ByteArray(8).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } })

    fun start(): Frame = if (expedited) {
        val d = ByteArray(8)
        d[0] = (((4 - data.size) shl 2) or 0x23).toByte()
        d[1] = index.toByte(); d[2] = (index ushr 8).toByte(); d[3] = sub.toByte()
        data.copyInto(d, 4)
        Frame(rpcId, d)
    } else {
        val n = data.size
        f(0xC6, index and 0xFF, index ushr 8, sub, n and 0xFF, (n ushr 8) and 0xFF, (n ushr 16) and 0xFF, n ushr 24)
    }

    private fun fail(r: String, abort: Sdo.Abort? = null) = Step.Failed(r, abort).also { s = S.DONE }

    private fun idxMatches(fr: Frame) = fr.dlc >= 4 && (fr.u8(1) or (fr.u8(2) shl 8)) == index && fr.u8(3) == sub

    fun onReply(fr: Frame): Step {
        if (fr.canId != replyId || s == S.DONE) return Step.Wait
        Sdo.parseAbort(fr)?.let { return if (it.index == index && it.sub == sub) fail(it.toString(), it) else Step.Wait }
        val b0 = fr.u8(0)
        return when (s) {
            S.INIT -> if (expedited) onExpedited(fr, b0) else onBlockInit(fr, b0)
            S.BLOCK -> onBlockAck(fr, b0)
            S.END -> onBlockEnd(b0)
            S.DONE -> Step.Wait
        }
    }

    private fun onExpedited(fr: Frame, b0: Int): Step {
        if (!idxMatches(fr)) return Step.Wait
        if (b0 != 0x60) return fail("unexpected reply 0x${b0.hex(2)}")
        s = S.DONE
        return Step.Done
    }

    private fun onBlockInit(fr: Frame, b0: Int): Step {
        if (!idxMatches(fr)) return Step.Wait
        if (Sdo.ccs(b0) != 5 || b0 and 3 != 0) return fail("unexpected block reply 0x${b0.hex(2)}")
        serverCrc = (b0 shr 2) and 1 == 1
        blkSize = fr.u8(4)
        if (blkSize !in 1..127) return fail("invalid block size $blkSize")
        s = S.BLOCK
        return Step.Send(nextBlock())
    }

    private fun nextBlock(): List<Frame> {
        blockStart = next
        val out = ArrayList<Frame>()
        var seq = 1
        while (next < segments.size && seq <= blkSize) {
            val seg = segments[next]
            val last = next == segments.size - 1
            val d = ByteArray(8)
            d[0] = (seq or if (last) 0x80 else 0).toByte()
            seg.copyInto(d, 1)
            out += Frame(rpcId, d)
            next++; seq++
        }
        return out
    }

    private fun onBlockAck(fr: Frame, b0: Int): Step {
        if (Sdo.ccs(b0) != 5 || b0 and 3 != 2) return fail("unexpected block ack 0x${b0.hex(2)}")
        val ackSeq = fr.u8(1)
        val sent = next - blockStart
        if (ackSeq != sent) return fail("ECU confirmed $ackSeq of $sent segments")
        blkSize = fr.u8(2).takeIf { it in 1..127 } ?: blkSize
        if (next < segments.size) return Step.Send(nextBlock())
        s = S.END
        val n = 7 - segments.last().size
        val crc = if (serverCrc) Sdo.crc16(data) else 0
        return Step.Send(listOf(f(0xC1 or (n shl 2), crc and 0xFF, crc ushr 8)))
    }

    private fun onBlockEnd(b0: Int): Step {
        if (Sdo.ccs(b0) != 5 || b0 and 3 != 1) return fail("unexpected block end reply 0x${b0.hex(2)}")
        s = S.DONE
        return Step.Done
    }
}
