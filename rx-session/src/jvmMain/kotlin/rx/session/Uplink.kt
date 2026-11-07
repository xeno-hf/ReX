package rx.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rx.protocol.ChannelTable
import rx.protocol.Frame
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.DeflaterOutputStream

/**
 * 鏡射回報（分身模式）：把 Session 收到的幀／輪／事件即時轉送到遠端（電腦，走 tailnet）。
 * 遠端 [UplinkServer] 原樣餵給 [Recorder]，所以 電腦 收到的跟手機本機錄製同格式。
 *
 * 盡力而為：斷線時在記憶體排隊（上限 [capacity] 筆，滿了丟最舊並計數），重連後先補 hello 再續傳。
 * 正本仍是手機本機錄製；這條只是讓遠端能即時看、順便留副本。
 *
 * 串流（整條 deflate，約每 200ms sync flush 一次）：
 * ```
 * "RXLINK1\n"，之後每筆  type u8 | len u32 LE | payload
 *   'H' hello：UTF-8 key=value 每行一筆（id、table=absid:cell,…、info.<鍵>=<值>）；每次連上都送
 *   'F' t_ns u64 LE | dir u8 | frame[FRAME_LEN]
 *   'R' t_ns u64 LE | n u16 LE | raw i64 LE × n
 *   'E' t_ns u64 LE | UTF-8 文字
 *   'X' 手機端正常結束這次連線
 * ```
 * 遠端每 3 秒回 1 byte 當心跳；15 秒收不到就當線路死了、斷開重連（行動網路斷線時 write 不一定會報錯）。
 */
class Uplink(
    private val host: String,
    private val port: Int,
    private val hello: () -> String,
    private val scope: CoroutineScope,
    capacity: Int = 20_000,
    private val nanoTime: () -> Long = System::nanoTime,
) : UplinkLink {
    @Volatile override var connected = false; private set
    @Volatile override var sent = 0L; private set
    @Volatile override var dropped = 0L; private set
    @Volatile override var reconnects = 0; private set
    @Volatile override var lastError: String? = null; private set

    private val queue = Channel<ByteArray>(capacity, BufferOverflow.DROP_OLDEST) { dropped++ }
    @Volatile private var socket: Socket? = null
    @Volatile private var closing = false

    private val job = scope.launch(Dispatchers.IO) {
        var backoff = 1_000L
        while (isActive && !closing) {
            try {
                Socket().use { s ->
                    socket = s
                    s.tcpNoDelay = true
                    s.soTimeout = 15_000
                    s.connect(InetSocketAddress(host, port), 5_000)
                    // 心跳讀取：收不到就關 socket，讓卡住的 write 拋例外
                    val reader = launch {
                        val inp = s.getInputStream()
                        runCatching { while (inp.read() >= 0) Unit }
                        runCatching { s.close() }
                    }
                    val z = DeflaterOutputStream(s.getOutputStream().buffered(1 shl 15), true)
                    z.write(MAGIC)
                    z.write(record(T_HELLO, hello().toByteArray(Charsets.UTF_8)))
                    z.flush()
                    connected = true; backoff = 1_000L; lastError = null
                    var lastFlush = nanoTime()
                    while (isActive) {
                        val item = withTimeoutOrNull(200) { queue.receive() }
                        if (item != null) { z.write(item); sent++ }
                        if (item == null || nanoTime() - lastFlush > 200_000_000L) { z.flush(); lastFlush = nanoTime() }
                        if (item != null && item[0] == T_END) { z.finish(); z.flush(); closing = true; break }
                    }
                    reader.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                socket = null
                if (connected) reconnects++
                connected = false
            }
            if (closing) break
            delay(backoff); backoff = (backoff * 2).coerceAtMost(10_000L)
        }
    }

    override fun onFrame(tNanos: Long, dir: Int, frame: Frame) {
        val b = ByteArray(9 + Frame.FRAME_LEN)
        putLong(b, 0, tNanos); b[8] = dir.toByte()
        frame.encode().copyInto(b, 9)
        queue.trySend(record(T_FRAME, b))
    }

    override fun onRound(tNanos: Long, raw: LongArray) {
        val b = ByteArray(10 + 8 * raw.size)
        putLong(b, 0, tNanos)
        b[8] = raw.size.toByte(); b[9] = (raw.size ushr 8).toByte()
        raw.forEachIndexed { i, v -> putLong(b, 10 + 8 * i, v) }
        queue.trySend(record(T_ROUND, b))
    }

    override fun onEvent(tNanos: Long, text: String) {
        val t = text.toByteArray(Charsets.UTF_8)
        val b = ByteArray(8 + t.size)
        putLong(b, 0, tNanos); t.copyInto(b, 8)
        queue.trySend(record(T_EVENT, b))
    }

    /** 送出結束記號，給它 [graceMs] 把排隊的送完，再收線。 */
    override suspend fun close(graceMs: Long) {
        queue.trySend(record(T_END, ByteArray(0)))
        // 連著的話寫完 'X' 迴圈會自己結束；沒連上就不等了
        if (connected) withTimeoutOrNull(graceMs) { job.join() }
        closing = true
        runCatching { socket?.close() }
        job.cancel()
    }

    companion object {
        val MAGIC = "RXLINK1\n".toByteArray(Charsets.US_ASCII)
        const val DEFAULT_PORT = 6680
        const val T_HELLO = 'H'.code.toByte()
        const val T_FRAME = 'F'.code.toByte()
        const val T_ROUND = 'R'.code.toByte()
        const val T_EVENT = 'E'.code.toByte()
        const val T_END = 'X'.code.toByte()

        fun record(type: Byte, payload: ByteArray): ByteArray {
            val b = ByteArray(5 + payload.size)
            b[0] = type
            for (k in 0 until 4) b[1 + k] = (payload.size ushr (8 * k)).toByte()
            payload.copyInto(b, 5)
            return b
        }

        fun putLong(b: ByteArray, at: Int, v: Long) { for (k in 0 until 8) b[at + k] = (v ushr (8 * k)).toByte() }

        fun getLong(b: ByteArray, at: Int): Long {
            var v = 0L
            for (k in 7 downTo 0) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
            return v
        }

        /** hello 內容：值裡的換行會被換成空白。 */
        fun helloText(id: String, table: ChannelTable, info: Map<String, String>): String = uplinkHello(id, table, info)

        fun parseHello(text: String): Triple<String, ChannelTable, Map<String, String>> {
            val kv = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val table = ChannelTable(kv["table"].orEmpty().split(',').filter { ':' in it }.map {
                ChannelTable.Entry(it.substringBefore(':').toInt(), it.substringAfter(':').toInt())
            })
            val info = kv.filterKeys { it.startsWith("info.") }.mapKeys { it.key.removePrefix("info.") }
            return Triple(kv["id"] ?: "unknown", table, info)
        }
    }
}
