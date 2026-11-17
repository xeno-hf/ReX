package rx.session

import rx.protocol.Frame
import rx.protocol.FrameParser
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/** 收 [Uplink] 鏡射串流的那一頭：解壓、檢查每一筆的長度，交給 [Handler]。開發用接收端與 XB 共用。 */
object UplinkStream {
    interface Handler {
        fun onHello(text: String)
        fun onFrame(tNanos: Long, dir: Int, frame: Frame)
        fun onRound(tNanos: Long, raw: LongArray)
        fun onEvent(tNanos: Long, text: String)
    }

    /** 讀到手機送 'X'（正常結束）回 true；對方直接斷線回 false。格式不對丟例外。 */
    fun read(input: InputStream, h: Handler): Boolean {
        val din = DataInputStream(InflaterInputStream(input).buffered(1 shl 15))
        val magic = ByteArray(Uplink.MAGIC.size).also(din::readFully)
        require(magic.contentEquals(Uplink.MAGIC)) { "not RXLINK1" }
        var hello = false
        while (true) {
            val type = try { din.readByte() } catch (_: EOFException) { return false }
            val len = Integer.reverseBytes(din.readInt())
            require(len in 0..65536) { "Invalid sync message size" }
            val p = ByteArray(len).also(din::readFully)
            when (type) {
                Uplink.T_FRAME -> require(len == 9 + Frame.FRAME_LEN)
                Uplink.T_ROUND -> {
                    require(len >= 10)
                    val count = (p[8].toInt() and 255) or ((p[9].toInt() and 255) shl 8)
                    require(count <= 4096 && len == 10 + count * 8)
                }
                Uplink.T_EVENT -> require(len >= 8)
                Uplink.T_HELLO -> require(len in 1..32768)
                Uplink.T_END -> require(len == 0)
                else -> error("Unknown sync message")
            }
            when (type) {
                Uplink.T_HELLO -> { hello = true; h.onHello(String(p, Charsets.UTF_8)) }
                Uplink.T_FRAME -> {
                    if (!hello) continue
                    val (frames, st) = FrameParser.parseAll(p.copyOfRange(9, 9 + Frame.FRAME_LEN))
                    if (frames.size == 1 && st.checksumErrors == 0L) h.onFrame(Uplink.getLong(p, 0), p[8].toInt(), frames[0])
                }
                Uplink.T_ROUND -> {
                    if (!hello) continue
                    val n = (p[8].toInt() and 0xFF) or ((p[9].toInt() and 0xFF) shl 8)
                    h.onRound(Uplink.getLong(p, 0), LongArray(n) { Uplink.getLong(p, 10 + 8 * it) })
                }
                Uplink.T_EVENT -> if (hello) h.onEvent(Uplink.getLong(p, 0), String(p, 8, p.size - 8, Charsets.UTF_8))
                Uplink.T_END -> return true
            }
        }
    }
}
