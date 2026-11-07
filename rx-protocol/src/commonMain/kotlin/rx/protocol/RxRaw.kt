package rx.protocol

/**
 * `.rxraw`：RX 錄製的唯一真相。解碼值、報告全是它的衍生物。
 *
 * ```
 * 檔頭  "RXRAW1\n"
 * 記錄  t_ns u64 LE | dir u8 (0=ECU→App, 1=App→ECU) | len u8 | frame[len]
 * ```
 * 時戳用 Session 的單調時鐘（Android `elapsedRealtimeNanos`，JVM 預設為 process-relative）；
 * 牆鐘／時鐘域錨點記在 meta.json（舊檔可用 events 的 t_ns + wall 對齊）。
 */
object RxRaw {
    val MAGIC = "RXRAW1\n".encodeToByteArray()
    const val DIR_RX = 0
    const val DIR_TX = 1

    data class Record(val tNanos: Long, val dir: Int, val frame: Frame)

    /** 一筆記錄的位元組（Writer 與其他平台的寫入端共用）。 */
    fun encodeRecord(tNanos: Long, dir: Int, frame: Frame, into: ByteArray = ByteArray(RECORD_LEN)): ByteArray {
        for (k in 0 until 8) into[k] = (tNanos ushr (8 * k)).toByte()
        into[8] = dir.toByte()
        into[9] = Frame.FRAME_LEN.toByte()
        frame.encode().copyInto(into, 10)
        return into
    }

    const val RECORD_LEN = 8 + 2 + Frame.FRAME_LEN
}
