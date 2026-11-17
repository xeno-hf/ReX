package rx.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.io.RawSink
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
 * <dir>/meta.json       錄製資訊＋各檔 SHA256（收檔時寫；雜湊是邊寫邊算的，收檔不用重讀檔案）
 *      /frames.rxraw    TX＋RX 全幀＋單調時戳 ← 真相
 *      /events.jsonl    連線／重拉／使用者標記
 *      /decoded.loga    XTune 相容格式，原生速率（每一輪一列）
 * ```
 * loga 最後一欄是 `Mark`（VarID [MARK_VAR_ID]）：到那一列為止打過幾個標記，值變大的那一列就是標記的位置。
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
    /** 開檔用；預設直接寫檔案，呼叫端可以換成逐次檢查上限的實作。 */
    private val openSink: (Path) -> RawSink = { SystemFileSystem.sink(it) },
) : SessionListener {

    /** 非 ECU 的欄位：每一輪呼叫 [value] 取當下值；null 寫 0（XTune 沒定位時也是 0）。 */
    class ExtraColumn(val varId: Int, val group: String, val name: String, val decimals: Int, val value: (LongArray) -> Double?)

    /** 使用者標記。[n] 每筆錄製從 1 編號；[tMs] 在 loga 的 Time 軸上（第一幀起算的毫秒）。 */
    class Mark(val n: Int, val tMs: Double) {
        /** 3:42、1:03:42。 */
        val clock: String get() {
            val sec = (tMs / 1000).toLong().coerceAtLeast(0)
            val mmss = "${sec / 60 % 60}:${(sec % 60).toString().padStart(2, '0')}"
            return if (sec < 3600) mmss else "${sec / 3600}:${mmss.padStart(5, '0')}"
        }
    }

    private val hashes = LinkedHashMap<String, HashingSink>()
    /** 收好的檔的 SHA256；`hex` 只能取一次，所以每個檔收好就記下來。 */
    private val digests = LinkedHashMap<String, String>()
    private val opened = ArrayList<Sink>()

    /** 開一個檔；包緩衝失敗時把底層關掉，不留半個。 */
    private fun open(name: String): Sink {
        val hashing = HashingSink(openSink(Path(dir, name)))
        return try {
            hashing.buffered().also { hashes[name] = hashing; opened += it }
        } catch (t: Throwable) {
            runCatching { hashing.close() }
            throw t
        }
    }

    private val rawOut: Sink
    private val rec = ByteArray(RxRaw.RECORD_LEN)
    private val events: Sink
    private val loga: Sink
    private val startedMono: Long
    private val startedWall: Long
    private var t0: Long = -1
    @Volatile var frames = 0L; private set
    @Volatile var rounds = 0L; private set
    @Volatile var closed = false; private set

    /** 收檔是否全部做完（每個檔都關好、meta 也寫好）；false 表示這筆錄製不完整。 */
    @Volatile var finalizationComplete = false; private set

    /** 收檔時的第一個錯誤；[close] 會在全部試過之後照原樣丟出去。 */
    @Volatile var finalizationError: Throwable? = null; private set

    private var rawClosed = false
    private var eventsClosed = false
    private var logaClosed = false
    private var metaWritten = false
    /** close 成功的檔、試過 close 的檔。 */
    private val sinkClosed = HashSet<String>()
    private val closeTried = HashSet<String>()
    private var abandoned = false
    /** 寫入途中出過錯的檔：邊寫邊算的雜湊可能跟檔案不一致，收檔時要重算。 */
    private val dirty = HashSet<String>()
    private val lock = SynchronizedObject()
    private val marked = ArrayList<Mark>()
    val marks: List<Mark> get() = synchronized(lock) { marked.toList() }

    init {
        try {
            SystemFileSystem.createDirectories(dir)
            startedMono = nanoTime()
            startedWall = wallClock()
            rawOut = open("frames.rxraw").also { it.write(RxRaw.MAGIC) }
            events = open("events.jsonl")
            loga = open("decoded.loga")
            writeLogaHeader()
            writeMeta(final = false)
        } catch (t: Throwable) {
            // 開檔中途失敗：已經開的把緩衝寫完關掉，不留半套
            for (s in opened) runCatching { s.close() }
            // 緩衝層關不掉（資料送不出去時 HashingSink 不關下層）：直接放棄，不留開著的檔
            for (h in hashes.values) h.abandon()
            throw t
        }
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
            writeString((listOf("-1") + cols.map { it.def.logVarId.toString() } + extras.map { it.varId.toString() } + MARK_VAR_ID.toString()).joinToString(",") + "\n")
            writeString("<VAR GROUP>\n")
            writeString((listOf("Stage_1") + cols.map { it.def.group.ifEmpty { "_" } } + extras.map { it.group } + MARK_GROUP).joinToString(",") + "\n")
            writeString("<VAR NAME>\n")
            writeString((listOf("Time") + cols.map { it.def.name } + extras.map { it.name } + MARK_NAME).joinToString(",") + "\n")
            writeString("<DATA START>\n")
        }
    }

    override fun onFrame(tNanos: Long, dir: Int, frame: Frame): Unit = synchronized(lock) {
        if (closed) return@synchronized
        if (t0 < 0) t0 = tNanos
        // 先算：緩衝的 sink 先收進緩衝才往下寫，往下寫被擋（例：上限）時這筆仍在緩衝裡，收檔時會寫出去
        frames++
        try {
            rawOut.write(RxRaw.encodeRecord(tNanos, dir, ShareExport.redactSecrets(frame), rec))
        } catch (t: Throwable) {
            dirty += "frames.rxraw"
            throw t
        }
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
        sb.append(',').append(marked.size)
        rounds++ // 同 onFrame：先算
        try {
            loga.writeString(sb.append('\n').toString())
        } catch (t: Throwable) {
            dirty += "decoded.loga"
            throw t
        }
        // 約每秒落盤一次：程式被系統殺掉時最多丟 1 秒
        if (rounds % 16 == 0L) {
            flush("frames.rxraw", rawOut)
            flush("decoded.loga", loga)
            flush("events.jsonl", events)
        }
    }

    override fun onEvent(tNanos: Long, text: String): Unit = synchronized(lock) {
        if (closed) return@synchronized
        try {
            events.writeString("{\"t_ns\":$tNanos,\"wall\":${wallClock()},\"text\":${jsonStr(clip(text))}}\n")
        } catch (t: Throwable) {
            dirty += "events.jsonl"
            throw t
        }
    }

    /** 打一個標記；[label] 拿到編號、回傳要寫進事件的文字。已收檔回 null。 */
    fun mark(label: (Int) -> String): Mark? = synchronized(lock) {
        if (closed) return@synchronized null
        val t = nanoTime()
        val m = Mark(marked.size + 1, if (t0 < 0) 0.0 else ((t - t0) / 1_000_000.0).coerceAtLeast(0.0))
        marked += m
        try {
            events.writeString("{\"t_ns\":$t,\"wall\":${wallClock()},\"mark\":${m.n},\"t_ms\":${Fmt.fixed(m.tMs, 1)},\"text\":${jsonStr(clip("Mark: ${label(m.n)}"))}}\n")
        } catch (e: Throwable) {
            dirty += "events.jsonl"
            throw e
        }
        m
    }

    fun mark(text: String): Mark? = mark { text }

    /**
     * 收檔：把還在緩衝的寫完、關檔、寫 meta.json。花的時間跟錄了多久無關。
     *
     * 每個檔各自收：一個 flush／close 失敗不會擋住其他檔，全部試過才把第一個錯誤丟出去；
     * 結果留在 [finalizationComplete] 與 [finalizationError]，呼叫端才知道這筆錄製完不完整。
     * 收不完時再呼叫一次只補沒做完的部分。
     */
    fun close() {
        var first: Throwable? = null
        synchronized(lock) {
            if (closed && finalizationComplete) return
            // 放棄過（丟了沒寫出去的資料）：永遠不完整，不再寫收檔的 meta
            if (abandoned) throw IllegalStateException("recording was abandoned before it finalized")
            closed = true
            /** 收一個檔：flush 失敗仍會試 close；關好之後才算雜湊。回傳這個檔是否關好且有雜湊（下次只補沒做完的那一步）。 */
            fun finish(name: String, sink: Sink): Boolean {
                if (name !in sinkClosed) {
                    if (name !in closeTried) {
                        // 先 flush：資料送不出去（例：上限還沒放寬）就先不關，緩衝裡的資料留著，下次收檔再送；
                        // 資料都交下去了、只是下層 flush 本身出錯，照樣關檔
                        try { sink.flush() } catch (t: Throwable) {
                            if (first == null) first = t
                            if (hashes.getValue(name).hasPending) return false
                            dirty += name
                        }
                        // 緩衝層 close 一次就算關了（之後不能再 flush／close）；flush 過了，緩衝是空的
                        closeTried += name
                        try { sink.close() } catch (t: Throwable) { if (first == null) first = t; return false }
                    } else {
                        try { hashes.getValue(name).close() } catch (t: Throwable) { if (first == null) first = t; return false }
                    }
                    sinkClosed += name
                }
                // 寫入途中出過錯時，邊寫邊算的雜湊不可靠，關好後從檔案重算。
                // 重算失敗只影響這一個檔，不能擋住後面的檔。
                if (name !in digests) {
                    try {
                        digests[name] = if (name in dirty) digestOfFile(name) else hashes.getValue(name).sha.hex()
                    } catch (t: Throwable) {
                        dirty += name // sha.hex 只能取一次：下次從檔案算
                        if (first == null) first = t
                        return false
                    }
                }
                return true
            }
            if (!rawClosed) rawClosed = finish("frames.rxraw", rawOut)
            if (!eventsClosed) eventsClosed = finish("events.jsonl", events)
            if (!logaClosed) logaClosed = finish("decoded.loga", loga)
            // 每個檔都收好、雜湊都有了，meta 才算寫完；還有檔沒收好就照樣寫一份（缺的雜湊不列），下次收檔再重寫。
            if (!metaWritten) {
                val all = rawClosed && eventsClosed && logaClosed
                try { writeMeta(final = true); metaWritten = all } catch (t: Throwable) { if (first == null) first = t }
            }
            finalizationError = first
            finalizationComplete = first == null
        }
        first?.let { throw it }
    }

    /**
     * 收檔一直失敗、不再重試時：放棄還沒寫出去的資料，把檔案都關掉（不寫 meta）。之後 [finalizationComplete] 維持 false。
     */
    fun abandon() {
        synchronized(lock) {
            closed = true
            if (finalizationComplete) return
            abandoned = true
            finalizationError = finalizationError ?: IllegalStateException("recording abandoned")
            for ((name, h) in hashes) if (name !in sinkClosed) { h.abandon(); sinkClosed += name }
            runCatching { SystemFileSystem.delete(Path(dir, ".meta.json.tmp")) }
        }
    }

    private fun flush(name: String, sink: Sink) {
        try { sink.flush() } catch (t: Throwable) { dirty += name; throw t }
    }

    /** 寫入出過錯時用：關好的檔案從頭算一次 SHA256。 */
    private fun digestOfFile(name: String): String {
        val sha = Sha256()
        val chunk = ByteArray(8192)
        SystemFileSystem.source(Path(dir, name)).buffered().use { input ->
            while (true) {
                val n = input.readAtMostTo(chunk, 0, chunk.size)
                if (n <= 0) break
                sha.update(chunk, 0, n)
            }
        }
        return sha.hex()
    }

    private fun writeMeta(final: Boolean) {
        val files: Map<String, String> = if (final) digests else emptyMap()
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
        val text = sb.toString()
        val target = Path(dir, "meta.json")
        // 整份先寫進暫存檔（收檔的 meta 也要計入呼叫端的上限），成功才換掉舊的；
        // 上限不足或寫到一半失敗時，上一份 meta 不會被截斷。
        val tmp = Path(dir, ".meta.json.tmp")
        try {
            openSink(tmp).buffered().use { it.writeString(text) }
        } catch (t: Throwable) {
            runCatching { SystemFileSystem.delete(tmp) }
            throw t
        }
        try {
            SystemFileSystem.atomicMove(tmp, target)
        } catch (first: Throwable) {
            // 有的檔案系統不能直接換掉既有檔：舊的先改名留著，新的搬進去才刪舊的；搬不進去就把舊的放回來。
            // 任何時候至少有一份完整的 meta（target 或 .bak）。
            val bak = Path(dir, ".meta.json.bak")
            val hadOld = SystemFileSystem.exists(target)
            try {
                if (hadOld) SystemFileSystem.atomicMove(target, bak)
            } catch (t: Throwable) {
                runCatching { SystemFileSystem.delete(tmp) }
                throw first
            }
            try {
                SystemFileSystem.atomicMove(tmp, target)
            } catch (second: Throwable) {
                if (hadOld) runCatching { SystemFileSystem.atomicMove(bak, target) }
                runCatching { SystemFileSystem.delete(tmp) }
                throw second
            }
            if (hadOld) runCatching { SystemFileSystem.delete(bak) }
        }
    }

    companion object {
        /**
         * 收檔時寫的 meta.json 最多幾個位元組（呼叫端預留收檔空間用）：[info] 每個字元以最壞的 JSON 跳脫（6 位元組）計，
         * 另加表格欄位與固定欄位。資料檔緩衝中的位元組不在內。
         */
        fun finalMetaBound(info: Map<String, String>, layout: DecodedLayout): Long =
            4096L + 6L * (info.entries.sumOf { it.key.length.toLong() + it.value.length + 4 } + (layout.registry.serial?.length ?: 0)) +
                32L * layout.table.entries.size

        /**
         * 收檔時最多還要寫多少（呼叫端在資料上限之外預留）：最後的 meta（[finalMetaBound]），
         * 加上撞到上限那一刻可能還在緩衝的資料——每個檔各一段緩衝（緩衝層＋[HashingSink] 各 8 KiB 以內），
         * 再加最長的一筆事件（[MAX_EVENT_CHARS] 字照最壞的跳脫）與最長的一列 loga。
         */
        fun finalizeBound(info: Map<String, String>, layout: DecodedLayout, extraColumns: Int = 0): Long =
            finalMetaBound(info, layout) + 3L * (32L shl 10) + (6L * MAX_EVENT_CHARS + 256) + 40L * (layout.columns.size + extraColumns + 2)

        /** 一筆事件／標記最多幾個字（更長的截掉）：錄檔與收檔保留量都有上限。 */
        const val MAX_EVENT_CHARS = 4096

        internal fun clip(text: String): String = if (text.length <= MAX_EVENT_CHARS) text else text.take(MAX_EVENT_CHARS - 1) + "…"

        /** ReX 自己的欄位，XTune 沒用到這個 VarID。 */
        const val MARK_VAR_ID = -9102
        const val MARK_GROUP = "ReX"
        const val MARK_NAME = "Mark"

        /**
         * 從 events.jsonl 的內容讀回標記（錄製清單用）。舊版寫的標記沒有編號與 t_ms：
         * 依出現順序編號，時間用第一筆事件當起點估算。只認整行是合法 JSON 物件的事件：寫到一半斷掉、語法壞掉的行不算（[JsonLine]）。
         */
        fun parseMarks(events: String): List<Mark> {
            var base = -1L
            val out = ArrayList<Mark>()
            for (raw in events.lineSequence()) {
                val o = JsonLine.parseObject(raw) ?: continue
                val t = (o["t_ns"] as? JsonLine.Num)?.text?.toLongOrNull() ?: continue
                if (base < 0) base = t
                if ((o["text"] as? String)?.startsWith("Mark: ") != true) continue
                val n = (o["mark"] as? JsonLine.Num)?.text?.toIntOrNull() ?: (out.size + 1)
                val ms = (o["t_ms"] as? JsonLine.Num)?.text?.toDoubleOrNull() ?: ((t - base) / 1_000_000.0)
                out += Mark(n, ms)
            }
            return out
        }

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

        /** 同一秒內第二筆（停了馬上再錄）加 _2、_3…，不蓋掉前一筆。 */
        fun newSessionDir(root: Path, label: String, wall: Long = WallClock.nowMs()): Path {
            val base = "${label}_${WallClock.format(wall, "yyyyMMdd_HHmmss")}"
            var dir = Path(root, base)
            var n = 2
            while (SystemFileSystem.exists(dir)) dir = Path(root, "${base}_${n++}")
            return dir
        }
    }
}
