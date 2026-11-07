package rx.session

import rx.protocol.hex
import rx.protocol.CanId
import rx.protocol.DashFn
import rx.protocol.Frame
import rx.protocol.Rpc
import rx.protocol.Sdo

/**
 * Every outgoing frame passes this allowlist before transmission.
 * Monitoring setup, login, heartbeat and read-only SDO uploads are permitted.
 * Dashboard controls are limited to MAP / AT / Pit / LC / TC / EB with validated sizes and values.
 * Other calibration writes, password changes, block downloads and maintenance commands remain blocked.
 */
class WriteGate(private val deviceType: Int, initialRefusals: Long = 0) {
    class Refused(msg: String) : RuntimeException(msg)

    var refusals = initialRefusals; private set

    fun check(f: Frame) {
        val why = reason(f)
        if (why != null) { refusals++; throw Refused("Write gate refused ${f}: $why") }
    }

    fun allows(f: Frame): Boolean = reason(f) == null

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
                when (Sdo.ccs(b0).let { if (it == 0) Sdo.CCS_DOWNLOAD else it }) {
                    Sdo.CCS_DOWNLOAD -> when {
                        b0 and 0x03 != 0x03 || f.dlc != 8 -> "only expedited writes allowed"
                        addr == Rpc.MON_ADDR && sub == Rpc.MON_SUB -> null
                        addr == Rpc.CHAN_TABLE_ADDR -> null
                        // 儀表運行期控制（W0'，2026-10-20）：**只有儀表上的功能**，就算登入也不開放其他校正。
                        sub == 0 && (4 - ((b0 ushr 2) and 3)) == 1 && addr == Rpc.MAP_SWITCH -> if (u(4) in 1..3) null else "MAP must be 1-3"
                        // EB 是位元欄位，閘門無法驗前值；只讀改寫 bit0 的紀律由 Session.setDash 保證，這裡只放行物件與寬度。
                        sub == 0 && (4 - ((b0 ushr 2) and 3)) == 1 && addr == DashFn.EB.index -> null
                        // AT／Pit／LC／TC：XTune 只寫 0/1
                        sub == 0 && (4 - ((b0 ushr 2) and 3)) == 1 && DashFn.ofIndex(addr) != null ->
                            if (u(4) in 0..1) null else "${DashFn.ofIndex(addr)!!.name} must be 0/1"
                        else -> "write to 0x${addr.hex(4)}/$sub not allowed"
                    }
                    Sdo.CCS_UPLOAD_INIT -> if (b0 == 0x40) null else "bad upload command 0x${b0.hex(2)}"
                    Sdo.CCS_UPLOAD_SEG -> if (b0 == 0x60 || b0 == 0x70) null else "bad segment command 0x${b0.hex(2)}"
                    // block upload：A0/A4 起始、A3 開始、A2 確認、A1 結束——全是讀
                    Sdo.CCS_BLOCK_UPLOAD -> if (b0 in setOf(0xA0, 0xA4, 0xA3, 0xA2, 0xA1)) null else "bad block upload command 0x${b0.hex(2)}"
                    Sdo.CS_ABORT -> if (b0 == 0x80) null else "bad abort command"
                    Sdo.CCS_BLOCK_DOWNLOAD -> "block download blocked"
                    else -> "unknown RPC type 0x${b0.hex(2)}"
                }
            }
            CanId.SESSION -> if (f.dlc == 1 && u(0) == 0) null else "session frame must be {00}"
            CanId.HANDSHAKE -> if (f.dlc == 1 && u(0) in setOf(0xD1, 0xC1, 0xB1)) null else "handshake frame must be D1/C1/B1"
            CanId.SYS -> when {
                f.dlc == 1 && u(0) == 0x9A -> null
                f.dlc == 5 && u(0) == 0x88 && u(1) == 0x00 -> null // 登入；0x88 0x01 改密碼不准
                else -> "0x680 only allows 9A and login (firmware/password commands blocked)"
            }
            // 只放 XTune 每次連線都送的那一句（Start, node 0）；Stop/Reset 等 NMT 一律擋
            CanId.NMT -> if (f.dlc == 2 && u(0) == 0x01 && u(1) == 0x00) null else "NMT only allows Start (01 00)"
            CanId.HEARTBEAT_APP -> if (f.dlc == 1 && u(0) == 0x05) null else "heartbeat must be {05}"
            else -> "CAN ID 0x${f.canId.hex(3)} not allowed"
        }
    }
}
