package rx.protocol

/**
 * ProLink WiFi-CAN 幀（雙向同構）。
 *
 * ```
 * F8 01 C0 | LEN | CAN_ID[4] BE | FLAGS | DLC | DATA[8] | CHK
 * CHK = (256 − Σ bytes[2 .. LEN+3]) & 0xFF
 * ```
 * 實車 36,720/36,720 幀 CHK 驗證。
 */
class Frame(
    val canId: Int,
    data: ByteArray,
    val flags: Int = 0,
) {
    val data: ByteArray = data.copyOf()

    init {
        require(data.size <= 8) { "DLC ${data.size} > 8" }
        require(flags in 0..0xFF)
    }

    val dlc: Int get() = data.size

    /** 第 i 個資料位元組（無號）。 */
    fun u8(i: Int): Int = data[i].toInt() and 0xFF

    fun encode(): ByteArray {
        val f = ByteArray(FRAME_LEN)
        f[0] = 0xF8.toByte(); f[1] = 0x01; f[2] = 0xC0.toByte()
        f[3] = LEN_FIELD.toByte()
        f[4] = (canId ushr 24).toByte(); f[5] = (canId ushr 16).toByte()
        f[6] = (canId ushr 8).toByte(); f[7] = canId.toByte()
        f[8] = flags.toByte()
        f[9] = dlc.toByte()
        data.copyInto(f, 10)
        f[FRAME_LEN - 1] = checksum(f, 2, FRAME_LEN - 1).toByte()
        return f
    }

    fun hex(): String = encode().toHex()

    override fun toString(): String =
        "Frame(0x${canId.hex(3)} dlc=$dlc ${data.toHex()})"

    override fun equals(other: Any?): Boolean =
        other is Frame && other.canId == canId && other.flags == flags && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * (31 * canId + flags) + data.contentHashCode()

    companion object {
        const val LEN_FIELD = 0x0E
        const val FRAME_LEN = LEN_FIELD + 5 // 19
        val SYNC = byteArrayOf(0xF8.toByte(), 0x01, 0xC0.toByte())

        /** (256 − Σ buf[from until to]) & 0xFF */
        fun checksum(buf: ByteArray, from: Int, to: Int): Int {
            var s = 0
            for (i in from until to) s += buf[i].toInt() and 0xFF
            return (256 - (s and 0xFF)) and 0xFF
        }
    }
}

fun ByteArray.toHex(sep: String = " "): String =
    hexLower(sep)

fun hexBytes(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
