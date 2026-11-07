package rx.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** `.rxraw` 寫入（JVM 串流）。格式見 [RxRaw]。 */
class RxRawWriter(private val out: OutputStream) : AutoCloseable {
    private val rec = ByteArray(RxRaw.RECORD_LEN)
    init { out.write(RxRaw.MAGIC) }

    fun write(tNanos: Long, dir: Int, frame: Frame) { out.write(RxRaw.encodeRecord(tNanos, dir, frame, rec)) }
    fun flush() = out.flush()
    override fun close() = out.close()
}

/** 逐筆讀回；幀會重新驗 CHK（壞記錄拋例外，而不是靜默跳過）。 */
fun RxRaw.read(input: InputStream): Sequence<RxRaw.Record> = sequence {
    val din = DataInputStream(input)
    val head = ByteArray(MAGIC.size)
    din.readFully(head)
    require(head.contentEquals(MAGIC)) { "not rxraw" }
    val t = ByteArray(8)
    while (true) {
        try { din.readFully(t) } catch (_: EOFException) { break }
        var ts = 0L
        for (k in 7 downTo 0) ts = (ts shl 8) or (t[k].toLong() and 0xFF)
        val dir = din.readUnsignedByte()
        val len = din.readUnsignedByte()
        val fb = ByteArray(len).also(din::readFully)
        val (frames, st) = FrameParser.parseAll(fb)
        require(frames.size == 1 && st.checksumErrors == 0L) { "record @t=$ts corrupted" }
        yield(RxRaw.Record(ts, dir, frames[0]))
    }
}
