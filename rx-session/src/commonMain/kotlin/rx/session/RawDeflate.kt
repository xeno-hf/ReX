package rx.session

/**
 * 原始 deflate（ZIP 用的，沒有 zlib 標頭）。邊餵邊壓，記憶體用量跟檔案大小無關。
 * JVM／Android 用 java.util.zip.Deflater，iOS 用系統的 zlib。
 */
expect class RawDeflate(level: Int = 6) {
    /** 餵資料；壓出來的位元組交給 [out]（可能是 0 次或多次）。 */
    fun update(input: ByteArray, off: Int, len: Int, out: (ByteArray, Int) -> Unit)
    /** 收尾，把剩下的壓出來。之後不能再用。 */
    fun finish(out: (ByteArray, Int) -> Unit)
    /** 放掉原生資源；finish 之後或中途失敗都要呼叫。 */
    fun end()
}
