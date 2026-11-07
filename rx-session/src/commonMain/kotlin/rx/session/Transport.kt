package rx.session

/** 位元組管線。TCP 6666 與 ECU 離線重播都實作它。 */
interface Transport {
    suspend fun send(bytes: ByteArray)
    /** 收一塊資料；null = 對端關閉。 */
    suspend fun receive(): ByteArray?
    fun close()
}
