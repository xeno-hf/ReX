package rx.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import rx.protocol.Fmt
import rx.protocol.Frame
import rx.protocol.RxRaw
import rx.protocol.monotonicNanos
import kotlin.concurrent.Volatile

/**
 * 雙軌錄製：
 * ```
 * <dir>/meta.json       錄製資訊＋各檔 SHA256（收檔時寫）
 *      /frames.rxraw    TX＋RX 全幀＋單調時戳 ← 真相
 *      /events.jsonl    連線／重拉／使用者標記
 *      /decoded.loga    XTune 相容格式，原生速率（每一輪一列）
 * ```
 */
class Recorder(
    val dir: Path,
    private val layout: DecodedLayout,
    private val info: Map<String, String>,
    private val wallClock: () -> Long = { WallClock.nowMs() },
    /** 接在 ECU 欄位後面的額外欄（例：手機 GPS，XTune 同樣接在最後）。 */
    private val extras: List<ExtraColumn> = emptyList(),
    /** 必須與 Session 的 frame/round/event 使用相同時鐘。Android 是 elapsedRealtimeNanos。 */
    private val nanoTime: () -> Long = ::monotonicNanos,
    private val clockDomain: String = "process-monotonic",
) : SessionListener {

    /** 非 ECU 的欄位：每一輪呼叫 [value] 取當下值；null 寫 0（XTune 沒定位時也是 0）。 */
    class ExtraColumn(val varId: Int, val group: String, val name: String, val decimals: Int, val value: (LongArray) -> Double?)

    init { SystemFileSystem.createDirectories(dir) }
    private val rawOut: Sink = SystemFileSystem.sink(Path(dir, "frames.rxraw")).buffered().also { it.write(RxRaw.MAGIC) }
    private val rec = ByteArray(RxRaw.RECORD_LEN)
    private val events: Sink = SystemFileSystem.sink(Path(dir, "events.jsonl")).buffered()
    private val loga: Sink = SystemFileSystem.sink(Path(dir, "decoded.loga")).buffered()
    private val startedMono = nanoTime()
    private val startedWall = wallClock()
    private var t0: Long = -1
    @Volatile var frames = 0L; private set
    @Volatile var rounds = 0L; private set
    @Volatile var closed = false; private set
    private val lock = SynchronizedObject()

    init {
        writeLogaHeader()
        writeMeta(final = false)
    }

    private fun writeLogaHeader() {
        val cols = layout.columns
        with(loga) {
            writeString("<aRacer MX APP Log File>\n")
            writeString("Created Date:${WallClock.format(startedWall, "yyyy-MM-dd HH:mm:ss")}\n")
            for (k in listOf("Product ID", "Table ID", "VSCA", "ECU SN", "Hardware ID", "Func. Code", "Upgrade Code", "Original Code")) {
                writeString("$k:${info[k] ?: ""}\n")
            }
            writeString("\n<VAR ID>\n")
            writeString((listOf("-1") + cols.map { it.def.logVarId.toString() } + extras.map { it.varId.toString() }).joinToString(",") + "\n")
            writeString("<VAR GROUP>\n")
            writeString((listOf("Stage_1") + cols.map { it.def.group.ifEmpty { "_" } } + extras.map { it.group }).joinToString(",") + "\n")
            writeString("<VAR NAME>\n")
            writeString((listOf("Time") + cols.map { it.def.name } + extras.map { it.name }).joinToString(",") + "\n")
            writeString("<DATA START>\n")
        }
    }

    override fun onFrame(tNanos: Long, dir: Int, frame: Frame): Unit = synchronized(lock) {
        if (closed) return@synchronized
        if (t0 < 0) t0 = tNanos
        rawOut.write(RxRaw.encodeRecord(tNanos, dir, ShareExport.redactSecrets(frame), rec))
        frames++
    }

    override fun onRound(tNanos: Long, raw: LongArray): Unit = synchronized(lock) {
        if (closed) return@synchronized
        if (t0 < 0) t0 = tNanos
        val sb = StringBuilder(1024)
        sb.append(Fmt.fixed((tNanos - t0) / 1_000_000.0, 1))
        for (c in layout.columns) {
            sb.append(',').append(fmt(layout.value(c, raw), c.def.decimals))
        }
        for (e in extras) sb.append(',').append(fmt(e.value(raw) ?: 0.0, e.decimals))
        loga.writeString(sb.append('\n').toString())
        rounds++
        // 約每秒落盤一次：程式被系統殺掉時最多丟 1 秒
        if (rounds % 16 == 0L) { rawOut.flush(); loga.flush(); events.flush() }
    }

    override fun onEvent(tNanos: Long, text: String): Unit = synchronized(lock) {
        if (closed) return@synchronized
        events.writeString("{\"t_ns\":$tNanos,\"wall\":${wallClock()},\"text\":${jsonStr(text)}}\n")
    }

    fun mark(text: String) = onEvent(nanoTime(), "Mark: $text")

    fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        rawOut.close(); events.close(); loga.close()
        writeMeta(final = true)
    }

    private fun writeMeta(final: Boolean) {
        val files = if (final) listOf("frames.rxraw", "events.jsonl", "decoded.loga").associateWith { sha256Hex(Path(dir, it)) } else emptyMap()
        val sb = StringBuilder("{\n  \"login_payload_redacted\": true,\n")
        sb.append("  \"format\": \"rx-session-1\",\n")
        sb.append("  \"started_wall_ms\": $startedWall,\n")
        if (final) sb.append("  \"stopped_wall_ms\": ${wallClock()},\n  \"frames\": $frames,\n  \"rounds\": $rounds,\n")
        sb.append("  \"table\": [${layout.table.entries.joinToString(",") { "[${it.absid},${it.cellSize}]" }}],\n")
        sb.append("  \"payload_bytes\": ${layout.table.payloadLength},\n")
        sb.append("  \"controlfile_serial\": ${jsonStr(layout.registry.serial ?: "")},\n")
        sb.append("  \"clock\": {\"domain\":${jsonStr(clockDomain)},\"started_monotonic_ns\":$startedMono,\"started_wall_ms\":$startedWall},\n")
        sb.append("  \"info\": {${info.entries.joinToString(",") { "${jsonStr(it.key)}:${jsonStr(it.value)}" }}},\n")
        sb.append("  \"sha256\": {${files.entries.joinToString(",") { "${jsonStr(it.key)}:${jsonStr(it.value)}" }}}\n")
        sb.append("}\n")
        SystemFileSystem.sink(Path(dir, "meta.json")).buffered().use { it.writeString(sb.toString()) }
    }

    companion object {
        fun fmt(v: Double, decimals: Int?): String {
            val d = (decimals ?: 3).coerceIn(0, 6)
            val s = Fmt.fixed(v, d)
            val t = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
            return if (t == "-0" || t.isEmpty()) "0" else t
        }

        fun jsonStr(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
            append('"')
        }

        fun newSessionDir(root: Path, label: String, wall: Long = WallClock.nowMs()): Path =
            Path(root, "${label}_${WallClock.format(wall, "yyyyMMdd_HHmmss")}")
    }
}
