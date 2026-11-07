package rx.protocol

import kotlin.time.TimeSource

/** 跨平台小工具：取代 JVM 專用的 String.format、ByteArrayOutputStream、IOException、System.nanoTime。 */

/** 大寫十六進位、補零到 [width] 位（`"%04X".format(x)` 的替代）。 */
fun Int.hex(width: Int): String = toLong().and(0xFFFFFFFFL).toString(16).uppercase().padStart(width, '0')
fun Long.hex(width: Int): String = toULong().toString(16).uppercase().padStart(width, '0')

/** 位元組陣列轉小寫十六進位（每位元組兩位）。 */
fun ByteArray.hexLower(sep: String = ""): String =
    joinToString(sep) { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** 可增長的位元組緩衝（ByteArrayOutputStream 的最小替代）。 */
class ByteSink(initial: Int = 64) {
    private var data = ByteArray(initial)
    var size = 0; private set

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun write(b: Int) { ensure(1); data[size++] = b.toByte() }
    fun write(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        ensure(len); src.copyInto(data, size, off, off + len); size += len
    }
    fun reset() { size = 0 }
    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** I/O 錯誤（JVM 上就是 java.io.IOException）。 */
expect open class IOException(message: String?) : Exception

private val monoOrigin = TimeSource.Monotonic.markNow()

/** 單調時鐘（奈秒，起點不定）；JVM 上與 System.nanoTime 同性質。 */
fun monotonicNanos(): Long = monoOrigin.elapsedNow().inWholeNanoseconds
