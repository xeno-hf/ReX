package rx.session

import rx.protocol.hex
import rx.protocol.CanId
import rx.protocol.DashFn
import rx.protocol.DashTable
import rx.protocol.Frame
import rx.protocol.Rpc
import rx.protocol.Sdo

/**
 * Every outgoing frame passes this allowlist before transmission.
 * Monitoring setup, login, heartbeat and read-only SDO uploads are permitted.
 * Dashboard controls are limited to MAP / AT / Pit / LC / TC / EB with validated sizes and values.
 * Calibration writes are blocked unless live tuning is started ([calibration]): then only the loaded
 * definition's tables (expedited or block download) and the listed function-page commands pass.
 * Password changes, firmware flashing, whole-image flash writes and other objects are always blocked.
 */
class WriteGate(private val deviceType: Int, initialRefusals: Long = 0, calibration: Set<Int>? = null) {
    class Refused(msg: String) : RuntimeException(msg)

    companion object {
        /** 一列表格最大的區塊寫入（最大的表一列約 100 格 × 4 bytes）。 */
        const val MAX_BLOCK_BYTES = 4096L
    }

    /**
     * 調校模式（ReX 1.0 即時調校）：載入定義包、使用者開啟調校後才有值。
     * 只准寫這些物件（＝定義包裡的表，0x3000＋表號），寫法只有 XTune 改表用的兩種：
     * expedited（4 bytes 以內）與區塊寫入（0xC6 起始 → 分段 → 0xC1 結束，見 [rx.protocol.SdoDownload]）。
     * 改密碼、刷韌體、整包寫 flash、其他物件照舊全擋。null＝關閉（預設）。
     */
    var calibration: Set<Int>? = calibration
        set(v) { field = v; block = null }

    /**
     * 調校模式下另外放行的命令幀（功能專頁：自動暖車、怠速調整、TPS 學習、噴嘴校正、保養資訊的 SDO 寫入，
     * 以及 ECU 燈效 0x689、每缸 AFR 讀取 0x680 `62`）。清單在 xb-tune 的 CalCommands，由 App 層接進來；
     * 只在 [calibration] 開著時有效。null＝不放行任何命令（預設）。
     */
    var commands: ((Frame) -> Boolean)? = null

    private fun commandAllows(f: Frame) = calibration != null && commands?.invoke(f) == true

    /**
     * 進行中的區塊寫入（0xC6 宣告了物件、sub、長度）。之後只接受這一筆的分段（序號連續、總段數與末段長度照宣告）、
     * 結束幀（0xC1|n<<2，n 對得上末段長度）、或同一物件的 abort；其他 RPC 幀一律擋。
     */
    private class Download(val addr: Int, val sub: Int, val size: Int) {
        val total = (size + 6) / 7
        val lastLen = size - (total - 1) * 7
        var sent = 0
        var seq = 0
        /** ECU 確認了目前這個區塊（0xA0／0xA2），下一個區塊才能從序號 1 重來。 */
        var acked = false
        /** 結束幀送出了，等 ECU 最後確認；這段時間只放同一物件的 abort。 */
        var ended = false
        val segmentsDone get() = sent == total
    }
    private var block: Download? = null

    /** 區塊寫入結束（成功、失敗或逾時）時由送的人呼叫；之後的分段幀一律不放。 */
    fun endBlock() { block = null }

    /** 送的人收到 ECU 對區塊的確認（0xA0 起始回應、0xA2 區塊確認）時呼叫。 */
    fun blockAcknowledged() { block?.acked = true }

    /** 有沒有沒結束的區塊寫入（送的人中途放棄時要先送 abort）。 */
    val blockOpen: Boolean get() = block != null

    private fun calibrationAllows(addr: Int) = calibration?.contains(addr) == true

    /**
     * 儀表鈕寫入（MAP／AT／Pit／LC／TC／EB）只放行已逐平台驗證的「平台×物件」，見 [DashTable]。
     * [what] 是 [Rpc.MAP_SWITCH] 或該平台的 [Rpc.dashIndex]。
     */
    fun dashVerified(what: Int): Boolean = DashTable.verified(deviceType, what)

    var refusals = initialRefusals; private set

    fun check(f: Frame) {
        val why = reason(f)
        if (why != null) { refusals++; throw Refused("Write gate refused ${f}: $why") }
        track(f)
    }

    /** 只問、不改狀態（區塊寫入的分段要在 [check] 時才會記進度）。 */
    fun allows(f: Frame): Boolean = reason(f) == null

    /** 放行之後記區塊寫入進度。 */
    private fun track(f: Frame) {
        if (f.canId != CanId.rpc(deviceType) || f.dlc < 1) return
        val b0 = f.data[0].toInt() and 0xFF
        val bl = block
        when {
            b0 == 0x80 -> block = null
            bl != null && !bl.segmentsDone -> { val s = b0 and 0x7F; if (s == 1) bl.acked = false; bl.seq = s; bl.sent++ }
            bl != null -> if ((b0 and 0xE3) == 0xC1) bl.ended = true
            b0 == 0xC6 && f.dlc == 8 -> block = Download(
                (f.data[1].toInt() and 0xFF) or ((f.data[2].toInt() and 0xFF) shl 8), f.data[3].toInt() and 0xFF,
                (0 until 4).fold(0) { a, i -> a or ((f.data[4 + i].toInt() and 0xFF) shl (8 * i)) })
        }
    }

    /** 區塊寫入進行中，這一幀能不能送。 */
    private fun blockReason(bl: Download, f: Frame): String? {
        val d = f.data
        fun u(i: Int) = d[i].toInt() and 0xFF
        if (f.dlc != 8) return "block download in progress"
        val b0 = u(0)
        if (b0 == 0x80) return if ((u(1) or (u(2) shl 8)) == bl.addr && u(3) == bl.sub) null else "abort for another object during block download"
        if (bl.ended) return "waiting for the ECU to confirm the block download"
        if (!bl.segmentsDone) {
            val s = b0 and 0x7F
            val last = b0 and 0x80 != 0
            // 序號從 1 起連續；ECU 確認一個區塊後下一個區塊從 1 重新開始
            if (s != bl.seq + 1 && !(s == 1 && bl.seq > 0 && bl.acked)) return "block segment out of sequence"
            if (s !in 1..127) return "bad block segment number"
            val isLast = bl.sent + 1 == bl.total
            if (last != isLast) return "block segment count differs from the declared size"
            if (isLast) for (i in 1 + bl.lastLen..7) if (u(i) != 0) return "padding after the last segment must be zero"
            return null
        }
        val n = 7 - bl.lastLen
        if (b0 != (0xC1 or (n shl 2))) return "only the block end frame may follow the last segment"
        for (i in 3..7) if (u(i) != 0) return "bad block end frame"
        return null
    }

    private fun dash(addr: Int): DashFn? = DashTable.fnOf(deviceType, addr)

    private fun reason(f: Frame): String? {
        val d = f.data
        fun u(i: Int) = d[i].toInt() and 0xFF
        return when (f.canId) {
            CanId.rpc(deviceType) -> {
                if (f.dlc < 1) return "RPC frame too short"
                // 依 SDO 命令字（bit7-5）分類，見 rx.protocol.Sdo
                val b0 = u(0)
                val addr = if (f.dlc >= 3) u(1) or (u(2) shl 8) else -1
                val sub = if (f.dlc >= 4) u(3) else -1
                // 調校模式的區塊寫入進行中：只放這一筆的分段、結束幀與 abort
                block?.let { bl -> return blockReason(bl, f) }
                // 寫入只收標準 expedited download（0x2X，bit4＝0、e＝1、s＝1）；RX 自 0.3.2 起與 XTune 同送這種。
                when (Sdo.ccs(b0)) {
                    Sdo.CCS_DOWNLOAD -> when {
                        b0 and 0xF3 != 0x23 || f.dlc != 8 -> "only expedited writes allowed"
                        // 調校模式：定義包裡的表，expedited 寫任何 sub（一次一列）
                        calibrationAllows(addr) -> null
                        // 調校模式：功能專頁的命令（清單裡的物件與值）
                        commandAllows(f) -> null
                        addr == Rpc.MON_ADDR && sub == Rpc.MON_SUB -> null
                        addr == Rpc.CHAN_TABLE_ADDR -> null
                        // 儀表運行期控制：**只有儀表上的功能**，就算登入也不開放其他校正。
                        // 物件依平台而定（見 rx.protocol.DashTable），全部 1 byte、sub 0。
                        (addr == Rpc.MAP_SWITCH || dash(addr) != null) && !dashVerified(addr) ->
                            "dashboard control 0x${addr.hex(4)} not verified on node 0x${deviceType.hex(2)} firmware"
                        sub == 0 && (4 - ((b0 ushr 2) and 3)) == 1 && addr == Rpc.MAP_SWITCH -> if (u(4) in 1..3) null else "MAP must be 1-3"
                        // EB 是位元欄位，閘門無法驗前值；只讀改寫 bit0 的紀律由 Session.setDash 保證，這裡只放行物件與寬度。
                        sub == 0 && (4 - ((b0 ushr 2) and 3)) == 1 && dash(addr) == DashFn.EB -> null
                        // AT／Pit／LC／TC：XTune 只寫 0/1
                        sub == 0 && (4 - ((b0 ushr 2) and 3)) == 1 && dash(addr) != null ->
                            if (u(4) in 0..1) null else "${dash(addr)!!.name} must be 0/1"
                        else -> "write to 0x${addr.hex(4)}/$sub not allowed"
                    }
                    Sdo.CCS_UPLOAD_INIT -> if (b0 == 0x40) null else "bad upload command 0x${b0.hex(2)}"
                    Sdo.CCS_UPLOAD_SEG -> if (b0 == 0x60 || b0 == 0x70) null else "bad segment command 0x${b0.hex(2)}"
                    // block upload：A0/A4 起始、A3 開始、A2 確認、A1 結束——全是讀
                    Sdo.CCS_BLOCK_UPLOAD -> if (b0 in setOf(0xA0, 0xA4, 0xA3, 0xA2, 0xA1)) null else "bad block upload command 0x${b0.hex(2)}"
                    Sdo.CS_ABORT -> if (b0 == 0x80) null else "bad abort command"
                    Sdo.CCS_BLOCK_DOWNLOAD -> when {
                        b0 == 0xC6 && f.dlc == 8 && calibrationAllows(addr) -> {
                            val size = (0 until 4).fold(0L) { a, i -> a or (u(4 + i).toLong() shl (8 * i)) }
                            if (size in 5..MAX_BLOCK_BYTES) null else "block download size $size not allowed"
                        }
                        else -> "block download blocked"
                    }
                    else -> "unknown RPC type 0x${b0.hex(2)}"
                }
            }
            CanId.SESSION -> if (f.dlc == 1 && u(0) == 0) null else "session frame must be {00}"
            CanId.HANDSHAKE -> if (f.dlc == 1 && u(0) in setOf(0xD1, 0xC1, 0xB1)) null else "handshake frame must be D1/C1/B1"
            CanId.SYS -> when {
                commandAllows(f) -> null // 調校模式：每缸 AFR 讀取（62 缸 列）
                f.dlc == 1 && u(0) == 0x9A -> null
                f.dlc == 5 && u(0) == 0x88 && u(1) == 0x00 -> null // 登入；0x88 0x01 改密碼不准
                else -> "0x680 only allows 9A and login (firmware/password commands blocked)"
            }
            // 只放 XTune 每次連線都送的那一句（Start, node 0）；Stop/Reset 等 NMT 一律擋
            CanId.NMT -> if (f.dlc == 2 && u(0) == 0x01 && u(1) == 0x00) null else "NMT only allows Start (01 00)"
            CanId.HEARTBEAT_APP -> if (f.dlc == 1 && u(0) == 0x05) null else "heartbeat must be {05}"
            else -> if (commandAllows(f)) null else "CAN ID 0x${f.canId.hex(3)} not allowed"
        }
    }
}
