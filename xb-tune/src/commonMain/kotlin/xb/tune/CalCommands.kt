package xb.tune

/**
 * 功能專頁會送的「不是寫調校表」的東西，全部列在這裡；宿主（XB 的 EcuLink、ReX 的寫入閘門）只放行這張清單。
 * 每一項都照 XTune，值也照官方送的值，不多不少。
 */
object CalCommands {
    /** 可寫的命令物件：(index, sub) → 允許的值；null＝這個 sub 任何值（保養資訊的 32 位元字）。 */
    private val writes: Map<Pair<Int, Int>, Set<Long>?> = buildMap {
        put(0x200E to 0, setOf(1L, 0x80L))              // 自動暖車 START／STOP
        put(0x2000 to 0, setOf(0L, 1L))                  // 怠速空氣量調整模式 開／關
        put(0x2001 to 0, setOf(1L))                      // TPS 學習開始
        // 清除 ECU 學習值（「Set_ClearEcuLearn」→ case 6：a.p(8201, 0, 1, 1)）。
        // Mini X／Super X／Mini XX／Super XX 的 ECU 收到後把窄域閉迴路學習表 79、80 整張填 0x80，並重設怠速學習（SXS 未確認）。
        put(0x2009 to 0, setOf(1L))
        // 噴嘴校正測試命令：官方算 (f×128)<<2|step 存成 32 位元整數，宣告 2 bytes、4 個資料位元組照填，
        // 所以 160 那兩個值第 3 個資料位元組是 01（ECU 只看前 2 bytes＝0x4001／0x4002）。這裡照官方的 32 位元值送。
        put(0x2000 to 4, setOf(0x7801L, 0x14001L, 0x14002L, 0x0102L))
        for (s in 0..29) put(0x200F to s, null)          // 保養資訊：6 項 × 5 字，設定時寫前 3 字
    }

    /** 只准一種寬度的命令（官方宣告的 bytes）：(index, sub) → bytes。沒列的照 1～4。 */
    private val widths: Map<Pair<Int, Int>, Int> = mapOf(
        (0x2009 to 0) to 1,                              // 清除學習值：官方 a.p(8201, 0, 1, 1)，幀 2F
    )

    /** 可讀的命令物件（index → 允許的 sub；null＝任何 sub）。 */
    private val reads: Map<Int, Set<Int>?> = mapOf(
        0x2000 to setOf(0, 1),        // 怠速調整狀態、自動調校每缸狀態字
        0x200F to null,               // 保養資訊
        0x466E to setOf(0), 0x466F to setOf(0), // 總里程、引擎累積秒數（var 1646／1647＋0x4000）
    )

    /**
     * 命令的 SDO expedited download 幀內容（8 bytes），跟 XTune 一樣：
     * byte0＝(4−[bytes])<<2 | 0x23，index LE，sub，資料＝[value] 的 32 位元 LE 全 4 bytes（不管宣告幾個）。
     */
    fun sdoFrame(index: Int, sub: Int, value: Long, bytes: Int): ByteArray = byteArrayOf(
        (((4 - bytes) shl 2) or 0x23).toByte(), index.toByte(), (index shr 8).toByte(), sub.toByte(),
        value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte(),
    )

    /** [data] 是不是這張清單裡的命令幀（[sdoFrame] 的格式）。宿主的寫入閘門用。 */
    fun frameAllowed(data: ByteArray): Boolean {
        if (data.size != 8) return false
        val b = data.map { it.toInt() and 0xFF }
        if (b[0] and 0xF3 != 0x23) return false
        val bytes = 4 - ((b[0] shr 2) and 3)
        val index = b[1] or (b[2] shl 8)
        val v = (b[4].toLong()) or (b[5].toLong() shl 8) or (b[6].toLong() shl 16) or (b[7].toLong() shl 24)
        return writeAllowed(index, b[3], v, bytes)
    }

    fun writeAllowed(index: Int, sub: Int, value: Long, bytes: Int): Boolean {
        if (bytes !in 1..4) return false
        if (!writes.containsKey(index to sub)) return false
        if (widths[index to sub]?.let { it != bytes } == true) return false
        val ok = writes[index to sub] ?: return true
        return value in ok
    }

    fun readAllowed(index: Int, sub: Int): Boolean = reads.containsKey(index) && (reads[index]?.contains(sub) ?: true)

    /**
     * 可送的廠商訊框：
     * - 0x689 `F0 dev`（讀燈效狀態）、`F2 dev mode bright R G B`（設燈效），dev 0／1、mode 0–6、亮度 0–100；
     * - 0x680 `62 cyl row`（讀每缸 AFR 一列，cyl 1–4、row 0–25；超級自動調校）。
     */
    fun rawAllowed(canId: Int, data: ByteArray): Boolean {
        val b = data.map { it.toInt() and 0xFF }
        return when (canId) {
            0x689 -> when {
                b.size == 2 && b[0] == 0xF0 -> b[1] in 0..1
                b.size == 7 && b[0] == 0xF2 -> b[1] in 0..1 && b[2] in 0..6 && b[3] in 0..100
                else -> false
            }
            0x680 -> b.size == 3 && b[0] == 0x62 && b[1] in 1..4 && b[2] in 0..25
            else -> false
        }
    }
}
