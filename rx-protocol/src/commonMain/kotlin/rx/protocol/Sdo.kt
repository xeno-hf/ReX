package rx.protocol

/**
 * ECU 的 RPC 就是標準 CANopen SDO（CiA 301），2026-10-15 定案。
 *
 * 證據：
 * - XTune  命令位元組 = `c.b(v) << 5`，：1→1、2→2、3→5、4→6
 *   → 小寫 0x2X（expedited download）、小讀 0x40（initiate upload）、區塊讀 0xA4（block upload, CRC 位）、區塊寫 0xC6
 * - XTune （讀狀態機）：0xA4 → 等 init 回應（bit1＝帶長度）→ 送 0xA3 → 收 seq 片段（bit7＝末片）
 *   → 送 0xA2 ackseq 0x7F → 收 end（`(b>>2)&7`＝末片無效位元組、`b&3==1`）→ 送 0xA1
 * - 2026-09-20 實車：0x1008 區塊讀回 `c2 08 10 00 0a …`＝block upload init 回應（s=1、10 bytes）——當時誤記成「addr echo」；
 *
 * 這裡只做讀（upload）。下載（寫）仍走 [Rpc.smallWrite] 並受寫入閘門管制。
 */
object Sdo {
    const val CCS_DOWNLOAD = 1
    const val CCS_UPLOAD_INIT = 2
    const val CCS_UPLOAD_SEG = 3
    const val CS_ABORT = 4
    const val CCS_BLOCK_UPLOAD = 5
    const val CCS_BLOCK_DOWNLOAD = 6

    fun ccs(b0: Int) = (b0 ushr 5) and 7

    /** CiA 301 SDO abort code 表（常見項）。 */
    fun abortText(code: Long): String = when (code) {
        0x05030000L -> "toggle bit mismatch"
        0x05040000L -> "SDO timeout"
        0x05040001L -> "invalid or unknown command"
        0x05040002L -> "invalid block size"
        0x05040003L -> "invalid sequence number"
        0x05040004L -> "CRC error"
        0x05040005L -> "out of memory"
        0x06010000L -> "unsupported access"
        0x06010001L -> "write-only object"
        0x06010002L -> "read-only object"
        0x06020000L -> "object does not exist"
        0x06040041L -> "cannot map to PDO"
        0x06040047L -> "general internal incompatibility"
        0x06060000L -> "hardware error"
        0x06070010L -> "type/length mismatch"
        0x06070012L -> "length too high"
        0x06070013L -> "length too low"
        0x06090011L -> "sub-index does not exist"
        0x06090030L -> "value out of range"
        0x08000000L -> "general error"
        0x08000020L -> "data cannot be transferred or stored"
        0x08000021L -> "refused by local control"
        0x08000022L -> "refused by device state"
        else -> "unknown"
    }

    data class Abort(val index: Int, val sub: Int, val code: Long) {
        override fun toString() = "Abort 0x${index.hex(4)}/$sub：0x${code.hex(8)} ${abortText(code)}"
    }

    fun parseAbort(f: Frame): Abort? {
        if (f.dlc < 8 || f.u8(0) != 0x80) return null
        val code = f.u8(4).toLong() or (f.u8(5).toLong() shl 8) or (f.u8(6).toLong() shl 16) or (f.u8(7).toLong() shl 24)
        return Abort(f.u8(1) or (f.u8(2) shl 8), f.u8(3), code)
    }

    fun abortFrame(rpcId: Int, index: Int, sub: Int, code: Long): Frame = Frame(rpcId, byteArrayOf(
        0x80.toByte(), index.toByte(), (index ushr 8).toByte(), sub.toByte(),
        code.toByte(), (code ushr 8).toByte(), (code ushr 16).toByte(), (code ushr 24).toByte(),
    ))

    /** CRC-16/XMODEM（CiA 301 block transfer 用的 CRC）。 */
    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }
}

/**
 * 一次 SDO 讀取（upload）的純狀態機：不碰 I/O，好測試。
 *
 * 用法：送 [start]，之後每收到一幀 RPC 回應（0x580|type）就 [onReply]，照回傳的 [Step] 送幀或收尾。
 * [block]=true 走 XTune 讀表的區塊路徑（0xA4）；false 走一般 upload（0x40，可 expedited 或 segmented）。
 */
class SdoUpload(
    val rpcId: Int,
    val index: Int,
    val sub: Int,
    val block: Boolean = true,
    val blockSize: Int = 0x7F,
    /** 送 0xA4 時是否宣告支援 CRC（XTune 是送 0xA4＝宣告支援）。 */
    val crc: Boolean = true,
) {
    sealed interface Step {
        data class Send(val frames: List<Frame>) : Step
        data object Wait : Step
        data class Done(val data: ByteArray, val note: String) : Step
        data class Failed(val reason: String, val abort: Sdo.Abort? = null) : Step
    }

    private enum class S { INIT, SEG, BLOCK, END, DONE }
    private var s = S.INIT
    private var size: Long? = null
    private val buf = ByteSink()
    private var toggle = 0
    private var expectSeq = 1
    private var serverCrc = false
    private var lastSeg = ByteArray(0)

    private fun f(vararg b: Int) = Frame(rpcId, ByteArray(8).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } })

    fun start(): Frame = if (block)
        f(0xA0 or (if (crc) 0x04 else 0), index and 0xFF, index ushr 8, sub, blockSize, 0)
    else
        f(0x40, index and 0xFF, index ushr 8, sub)

    private fun le32(fr: Frame, at: Int) = fr.u8(at).toLong() or (fr.u8(at + 1).toLong() shl 8) or
        (fr.u8(at + 2).toLong() shl 16) or (fr.u8(at + 3).toLong() shl 24)

    private fun fail(r: String) = Step.Failed(r).also { s = S.DONE }

    fun onReply(fr: Frame): Step {
        if (fr.canId != ((rpcId and 0x7F) or 0x580)) return Step.Wait
        if (s == S.DONE) return Step.Wait
        Sdo.parseAbort(fr)?.let { s = S.DONE; return Step.Failed(it.toString(), it) }
        val b0 = fr.u8(0)
        return when (s) {
            S.INIT -> onInit(fr, b0)
            S.SEG -> onSegment(fr, b0)
            S.BLOCK -> onBlockSeg(fr, b0)
            S.END -> onBlockEnd(fr, b0)
            S.DONE -> Step.Wait
        }
    }

    private fun idxMatches(fr: Frame) = (fr.u8(1) or (fr.u8(2) shl 8)) == index && fr.u8(3) == sub

    private fun onInit(fr: Frame, b0: Int): Step {
        if (fr.dlc < 8 || !idxMatches(fr)) return Step.Wait
        return when (Sdo.ccs(b0)) {
            // 一般 upload 回應：expedited（e=1）或 segmented
            2 -> {
                val e = (b0 shr 1) and 1; val sz = b0 and 1
                if (e == 1) {
                    val n = if (sz == 1) (b0 shr 2) and 3 else 0
                    s = S.DONE
                    Step.Done(fr.data.copyOfRange(4, 8 - n), "expedited")
                } else {
                    if (sz == 1) size = le32(fr, 4)
                    s = S.SEG; toggle = 0
                    Step.Send(listOf(f(0x60)))
                }
            }
            // block upload init 回應：0xC0 | cc<<2 | s<<1
            6 -> {
                if (b0 and 1 != 0) return fail("unexpected block reply 0x${b0.hex(2)}")
                serverCrc = (b0 shr 2) and 1 == 1
                if ((b0 shr 1) and 1 == 1) size = le32(fr, 4)
                if (size == 0L) { s = S.DONE; return Step.Done(ByteArray(0), "empty object") }
                s = S.BLOCK; expectSeq = 1
                Step.Send(listOf(f(0xA3)))
            }
            else -> fail("unexpected reply 0x${b0.hex(2)}")
        }
    }

    private fun onSegment(fr: Frame, b0: Int): Step {
        if (Sdo.ccs(b0) != 0) return fail("bad segment reply 0x${b0.hex(2)}")
        if ((b0 shr 4) and 1 != toggle) return fail("toggle mismatch")
        val n = (b0 shr 1) and 7
        buf.write(fr.data, 1, 7 - n)
        if (b0 and 1 == 1) { s = S.DONE; return Step.Done(trimmed(), "segmented") }
        toggle = toggle xor 1
        return Step.Send(listOf(f(0x60 or (toggle shl 4))))
    }

    private fun onBlockSeg(fr: Frame, b0: Int): Step {
        val seq = b0 and 0x7F
        val last = b0 and 0x80 != 0
        // TCP 橋不掉幀；seq 錯就直接放棄，不做 CiA 301 的部分重送
        if (seq != expectSeq) return fail("bad sequence (expected $expectSeq, got $seq)")
        if (lastSeg.isNotEmpty()) buf.write(lastSeg)
        lastSeg = fr.data.copyOfRange(1, 8)
        expectSeq++
        return when {
            last -> { s = S.END; Step.Send(listOf(f(0xA2, seq, blockSize))) }
            seq >= blockSize -> { expectSeq = 1; Step.Send(listOf(f(0xA2, seq, blockSize))) }
            else -> Step.Wait
        }
    }

    private fun onBlockEnd(fr: Frame, b0: Int): Step {
        if (Sdo.ccs(b0) != 6 || b0 and 3 != 1) return fail("bad block end 0x${b0.hex(2)}")
        val n = (b0 shr 2) and 7
        buf.write(lastSeg, 0, 7 - n)
        val data = trimmed()
        s = S.DONE
        var note = "block"
        if (serverCrc && crc) {
            val got = fr.u8(1) or (fr.u8(2) shl 8)
            val want = Sdo.crc16(data)
            if (got != want) return Step.Failed("CRC mismatch (ECU 0x${got.hex(4)} / computed 0x${want.hex(4)})")
            note += "+CRC"
        }
        return Step.Done(data, note).also { pendingEnd = f(0xA1) }
    }

    /** block upload 收尾要送的 0xA1（[Step.Done] 之後送）。 */
    var pendingEnd: Frame? = null; private set

    private fun trimmed(): ByteArray {
        val all = buf.toByteArray()
        val sz = size ?: return all
        return if (sz in 0..all.size.toLong()) all.copyOf(sz.toInt()) else all
    }
}
