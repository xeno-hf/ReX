package rx.protocol

/** CAN ID 常數（deviceType=1 MX/MiniX；RPC 基底 0x600|type）。 */
object CanId {
    const val BROADCAST = 0x181      // ECU 監控串流分片
    const val SESSION = 0x681        // session 開/結束；ECU 也用它踢人
    const val HANDSHAKE = 0x682      // SN 分片 D1×3 → C1 硬體碼 → B1
    const val SYS = 0x680            // 9A、登入 0x88、韌體鏈
    const val LOGIN_REPLY = 0x6A0    // {0x89, status}
    const val HEARTBEAT_APP = 0x70A  // App → ECU {0x05}，約 1Hz
    /** ECU 的 CANopen 心跳（0x700|node，payload 05＝Operational）；XTune 看門狗以它重置。 */
    const val HEARTBEAT_BRIDGE = 0x701
    /** CANopen NMT（node 0＝全體）。 */
    const val NMT = 0x000

    fun rpc(deviceType: Int) = 0x600 or deviceType
    fun rpcReply(deviceType: Int) = 0x580 or deviceType
    /** 監控串流（CANopen TPDO，0x180|node）：MX 0x181、SX 0x182、MiniXX 0x1A1、SuperXX 0x1A2。 */
    fun broadcast(node: Int) = 0x180 or node
    /** ECU 心跳（0x700|node）。 */
    fun ecuHeartbeat(node: Int) = 0x700 or node
}

/** Recognized communication nodes. Hardware compatibility is documented separately. */
enum class EcuPlatform(val node: Int, val label: String, val controlFile: String) {
    MINI_X(1, "Mini X", "S3"),
    SUPER_X(2, "Super X", "S3"),
    SUPER_X_PRO_CYL4(4, "Super X Pro (4-cyl)", "CYL4"),
    SUPER_X_PRO_RZR(5, "Super X Pro (RZR)", "RZR"),
    MINI_XX(0x21, "Mini XX", "X2"),
    SUPER_XX(0x22, "Super XX", "X2");

    companion object {
        fun ofNode(n: Int) = entries.firstOrNull { it.node == n }
        /** 從 ECU 心跳 CAN ID 判平台；App 自己的 0x70A 與未知 node 回 null。 */
        fun ofHeartbeat(canId: Int) = if (canId in 0x701..0x77F) ofNode(canId - 0x700) else null
    }
}

/** Frame construction only; transmission is controlled by the session write gate. */
class Rpc(val deviceType: Int = 1) {
    val rpcId = CanId.rpc(deviceType)

    /** 小讀＝SDO initiate upload `[0x40, addrLE16, sub]`。實車對試過的位址回 Abort；讀表用 [SdoUpload] 區塊路徑。 */
    fun smallRead(addr: Int, sub: Int = 0, flags: Int = 0x40): Frame =
        Frame(rpcId, byteArrayOf(flags.toByte(), addr.toByte(), (addr ushr 8).toByte(), sub.toByte()))

    /** 小寫 ≤4B：`[((4−n)<<2)|(flags<<5)|3, addrLE16, sub, valueLE…]`，DLC 固定 8。 */
    fun smallWrite(addr: Int, sub: Int, value: Long, nBytes: Int, flags: Int = 1): Frame {
        require(nBytes in 1..4)
        require(addr in 0..0xFFFF && sub in 0..0xFF)
        require(value >= 0 && value < (1L shl (8 * nBytes))) { "value $value exceeds $nBytes bytes" }
        val d = ByteArray(8)
        d[0] = (((4 - nBytes) shl 2) or (flags shl 5) or 3).toByte()
        d[1] = addr.toByte(); d[2] = (addr ushr 8).toByte()
        d[3] = sub.toByte()
        for (k in 0 until nBytes) d[4 + k] = (value ushr (8 * k)).toByte()
        return Frame(rpcId, d)
    }

    /** 登入：0x680 `{0x88, 0x00, pw BE24}`；pw 為十進位整數（例 888888）。 */
    fun login(password: Int): Frame {
        require(password in 0..0xFFFFFF)
        return Frame(CanId.SYS, byteArrayOf(0x88.toByte(), 0, (password ushr 16).toByte(), (password ushr 8).toByte(), password.toByte()))
    }

    fun sessionOpen(): Frame = Frame(CanId.SESSION, byteArrayOf(0))
    fun sessionClose(): Frame = Frame(CanId.SESSION, byteArrayOf(0))
    fun heartbeat(): Frame = Frame(CanId.HEARTBEAT_APP, byteArrayOf(0x05))

    /** NMT Start Remote Node（全體）`000 {01 00}`。登入後送出。 */
    fun nmtStart(): Frame = Frame(CanId.NMT, byteArrayOf(0x01, 0x00))

    /** 握手鏈 0x682 段＋0x680{9A}（不含 0x681 與登入）。 */
    fun handshake(): List<Frame> =
        listOf(0xD1, 0xD1, 0xD1, 0xC1, 0xB1).map { Frame(CanId.HANDSHAKE, byteArrayOf(it.toByte())) } +
            Frame(CanId.SYS, byteArrayOf(0x9A.toByte()))

    // ---- 監控控制（W0）----
    fun monitorStop(): Frame = smallWrite(MON_ADDR, MON_SUB, 0, 2)
    fun monitorStart(): Frame = smallWrite(MON_ADDR, MON_SUB, MON_RUN, 2)

    /** MAP 切換：專用 opcode 0x2003，值＝MAP 檔位 1-3。免登入、access_mask OPEN。 */
    fun mapSwitch(mapNo: Int): Frame { require(mapNo in 1..3); return smallWrite(MAP_SWITCH, 0, mapNo.toLong(), 1) }

    /** eBoost byte（a1230，0x34CE）寫回——只應由 Session 以「讀→翻 bit0→寫」呼叫。 */
    fun eBoostWrite(byteValue: Int): Frame = smallWrite(EBOOST_OPTION, 0, (byteValue and 0xFF).toLong(), 1)

    /** 儀表多功能鈕寫入（1 byte）。值域照 XTune：EB 為讀改寫後的整個 byte，其餘 0/1。 */
    fun dashWrite(fn: DashFn, value: Int): Frame {
        if (fn != DashFn.EB) require(value in 0..1) { "${fn.name} only accepts 0/1" }
        return smallWrite(fn.index, 0, (value and 0xFF).toLong(), 1)
    }

    companion object {
        const val MON_ADDR = 0x1800
        const val MON_SUB = 5        // 合稱「0x1805」
        const val MON_RUN = 64L
        const val CHAN_TABLE_ADDR = 0x1A00
        const val MAP_SWITCH = 0x2003
        const val EBOOST_OPTION = 0x34CE
    }
}

/**
 * [bit0]＝開關看 bit0（AT、EB）；否則看「值＝1」。MAP 另見 [Rpc.mapSwitch]。
 */
enum class DashFn(val index: Int, val bit0: Boolean) {
    AT(0x300E, true),
    PIT(0x2004, false),
    LC(0x2005, false),
    TC(0x342B, false),
    EB(0x34CE, true);

    companion object { fun ofIndex(i: Int) = entries.firstOrNull { it.index == i } }
}

/** 解析 RPC（SDO）回應的粗分類。完整讀取流程見 [SdoUpload]。 */
sealed interface RpcReply {
    val frame: Frame
    /** SDO download response：`60 idxLE sub`。 */
    data class WriteAck(override val frame: Frame, val addr: Int, val sub: Int) : RpcReply
    data class Nack(override val frame: Frame, val abort: Sdo.Abort) : RpcReply
    data class Other(override val frame: Frame) : RpcReply

    companion object {
        fun classify(f: Frame): RpcReply {
            if (f.dlc >= 4 && f.u8(0) == 0x60) return WriteAck(f, f.u8(1) or (f.u8(2) shl 8), f.u8(3))
            Sdo.parseAbort(f)?.let { return Nack(f, it) }
            return Other(f)
        }
    }
}
