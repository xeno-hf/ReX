package rx.session

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.asSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.protocol.ChannelTable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 收檔韌性：注入的 sink 在 flush／close 丟錯時，其他檔照樣收、第一個錯照樣回報，錄製完不完整看得出來。 */
class RecorderCloseTest {
    private fun layout() = DecodedLayout(
        ChannelRegistry(null, listOf(ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap()))),
        ChannelTable(listOf(ChannelTable.Entry(26, 2))),
    )

    /** 記下每個動作；可以指定在哪一個動作丟錯。 */
    private class ProbeSink(
        private val name: String,
        private val inner: RawSink,
        private val events: MutableList<String>,
        private val failFlush: Boolean = false,
        private val failClose: Boolean = false,
    ) : RawSink {
        override fun write(source: Buffer, byteCount: Long) = inner.write(source, byteCount)

        override fun flush() {
            events += "flush:$name"
            if (failFlush) throw IOException("flush failed: $name")
            inner.flush()
        }

        override fun close() {
            events += "close:$name"
            if (failClose) throw IOException("close failed: $name")
            inner.close()
        }
    }

    @Test
    fun closeStillClosesEverySinkAndKeepsTheFirstError() {
        val dir = Files.createTempDirectory("rex-close-fail").toFile()
        val events = ArrayList<String>()
        try {
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                ProbeSink(p.name, SystemFileSystem.sink(p), events,
                    failFlush = p.name == "frames.rxraw",
                    failClose = p.name == "events.jsonl")
            })
            rec.onRound(1_000_000L, longArrayOf(1200))
            val err = assertFailsWith<IOException> { rec.close() }
            assertEquals("flush failed: frames.rxraw", err.message, "第一個錯要原樣丟出來")
            assertTrue(rec.closed)
            assertFalse(rec.finalizationComplete, "有檔沒收好：錄製不完整")
            assertEquals(err, rec.finalizationError)
            // 其他檔照樣收；frames 的 flush 失敗了還是要試 close
            assertTrue("close:frames.rxraw" in events, events.toString())
            assertTrue("close:events.jsonl" in events, events.toString())
            assertTrue("close:decoded.loga" in events, events.toString())
            // meta 照樣寫，而且看得出中斷
            val meta = File(dir, "meta.json").readText()
            assertTrue("\"stopped_wall_ms\"" in meta, meta)
            assertTrue("\"rounds\": 1" in meta, meta)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun constructionFailureClosesSinksThatWereAlreadyOpened() {
        val dir = Files.createTempDirectory("rex-open-fail").toFile()
        val closed = ArrayList<String>()
        try {
            val err = assertFailsWith<IOException> {
                Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                    if (p.name == "decoded.loga") throw IOException("no space")
                    ProbeSink(p.name, SystemFileSystem.sink(p), closed)
                })
            }
            assertEquals("no space", err.message)
            assertEquals(listOf("frames.rxraw", "events.jsonl"), closed.map { it.removePrefix("close:") })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun constructionFailureOnTheFirstMetaClosesAllDataSinks() {
        val dir = Files.createTempDirectory("rex-meta-fail").toFile()
        val closed = ArrayList<String>()
        try {
            assertFailsWith<IOException> {
                Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                    if (p.name == ".meta.json.tmp") throw IOException("no space")
                    ProbeSink(p.name, SystemFileSystem.sink(p), closed)
                })
            }
            assertEquals(listOf("frames.rxraw", "events.jsonl", "decoded.loga"), closed.map { it.removePrefix("close:") })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun failedFinalMetaKeepsThePreviousOneAndCanBeRetried() {
        val dir = Files.createTempDirectory("rex-meta-retry").toFile()
        var failFinalMeta = false
        try {
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                if (p.name == ".meta.json.tmp" && failFinalMeta) throw IOException("budget")
                ProbeSink(p.name, SystemFileSystem.sink(p), ArrayList())
            })
            rec.onRound(1_000_000L, longArrayOf(1200))
            val before = File(dir, "meta.json").readText()
            assertFalse("\"stopped_wall_ms\"" in before, before)

            failFinalMeta = true
            val err = assertFailsWith<IOException> { rec.close() }
            assertEquals("budget", err.message)
            assertFalse(rec.finalizationComplete)
            assertEquals(before, File(dir, "meta.json").readText(), "新的 meta 沒寫成，舊的不能動")

            // closed 了還是可以補收：只重寫還沒完成的 meta
            failFinalMeta = false
            rec.close()
            assertTrue(rec.finalizationComplete)
            val after = File(dir, "meta.json").readText()
            assertTrue("\"stopped_wall_ms\"" in after, after)
            assertTrue("\"sha256\": {\"frames.rxraw\":\"" in after, after)
            assertTrue("\"events.jsonl\":\"" in after, after)
            assertTrue("\"decoded.loga\":\"" in after, after)
            assertFalse(File(dir, ".meta.json.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    /** 上限：寫入前先檢查，超過就一個位元組都不收（同 XB 的 CappedSink）；[open] 之後才能用保留量。 */
    private class Budget(var limit: Long) { var used = 0L }
    private class Capped(private val inner: RawSink, private val b: Budget) : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            if (b.used + byteCount > b.limit) throw WriteRejectedException("limit")
            b.used += byteCount
            inner.write(source, byteCount)
        }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    /** raw 幀寫到撞上限：打開保留量收檔後，檔裡每一筆都完整、筆數跟 meta 一致、雜湊對得上（被擋下的那段不能掉）。 */
    @Test
    fun cappedRawRecordingKeepsEveryAcceptedRecord() {
        val dir = Files.createTempDirectory("rex-raw-cap").toFile()
        try {
            val budget = Budget(Long.MAX_VALUE)
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                if (p.name == "frames.rxraw") Capped(SystemFileSystem.sink(p), budget) else SystemFileSystem.sink(p)
            })
            budget.limit = 40_000
            var n = 0
            assertFailsWith<IOException> {
                while (true) { rec.onFrame(n * 1000L, 0, rx.protocol.Frame(0x181, byteArrayOf(1, 2, 3, n.toByte()))); n++; check(n < 100_000) }
            }
            budget.limit = Long.MAX_VALUE // 收檔保留量
            rec.close()
            assertTrue(rec.finalizationComplete)
            val bytes = File(dir, "frames.rxraw").readBytes()
            val records = (bytes.size - rx.protocol.RxRaw.MAGIC.size).toLong()
            assertEquals(0L, records % rx.protocol.RxRaw.RECORD_LEN, "最後一筆被截斷")
            assertEquals(rec.frames, records / rx.protocol.RxRaw.RECORD_LEN)
            val meta = File(dir, "meta.json").readText()
            assertTrue("\"frames\": ${rec.frames}," in meta, meta)
            val hex = Regex("\"frames.rxraw\":\"([0-9a-f]{64})\"").find(meta)!!.groupValues[1]
            assertEquals(hex, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        } finally { dir.deleteRecursively() }
    }

    /** 有一個檔第一次關不起來：再收一次之後 meta 要補上三個檔的雜湊，跟檔案內容一致，才算完成。 */
    @Test
    fun retriedFinalizationRewritesMetaWithEveryHash() {
        val dir = Files.createTempDirectory("rex-close-retry").toFile()
        var failOnce = true
        try {
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                val inner = SystemFileSystem.sink(p)
                if (p.name != "decoded.loga") inner else object : RawSink {
                    override fun write(source: Buffer, byteCount: Long) = inner.write(source, byteCount)
                    override fun flush() = inner.flush()
                    override fun close() { if (failOnce) { failOnce = false; throw IOException("close once") }; inner.close() }
                }
            })
            rec.onRound(1_000_000L, longArrayOf(1200))
            assertFailsWith<IOException> { rec.close() }
            assertFalse(rec.finalizationComplete)
            assertFalse("\"decoded.loga\":\"" in File(dir, "meta.json").readText())
            rec.close()
            assertTrue(rec.finalizationComplete)
            val meta = File(dir, "meta.json").readText()
            for (name in listOf("frames.rxraw", "events.jsonl", "decoded.loga")) {
                val hex = Regex("\"$name\":\"([0-9a-f]{64})\"").find(meta)?.groupValues?.get(1)
                assertTrue(hex != null, "$name 沒有雜湊：$meta")
                assertEquals(hex, java.security.MessageDigest.getInstance("SHA-256").digest(File(dir, name).readBytes()).joinToString("") { "%02x".format(it) })
            }
        } finally { dir.deleteRecursively() }
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 第一次收檔時上限還沒放寬：不能關檔丟資料；放寬後再收一次，每一筆都在、雜湊對得上。 */
    @Test
    fun capHitDuringTheFirstCloseIsRecoveredByTheNextClose() {
        val dir = Files.createTempDirectory("rex-raw-cap2").toFile()
        try {
            val budget = Budget(Long.MAX_VALUE)
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                if (p.name == "frames.rxraw") Capped(SystemFileSystem.sink(p), budget) else SystemFileSystem.sink(p)
            })
            budget.limit = 40_000
            var n = 0
            assertFailsWith<IOException> {
                while (true) { rec.onFrame(n * 1000L, 0, rx.protocol.Frame(0x181, byteArrayOf(1, 2, 3, n.toByte()))); n++; check(n < 100_000) }
            }
            assertFailsWith<IOException> { rec.close() } // 上限還在：送不出去，資料留著
            assertFalse(rec.finalizationComplete)
            budget.limit = Long.MAX_VALUE
            rec.close()
            assertTrue(rec.finalizationComplete)
            val bytes = File(dir, "frames.rxraw").readBytes()
            val records = (bytes.size - rx.protocol.RxRaw.MAGIC.size).toLong()
            assertEquals(0L, records % rx.protocol.RxRaw.RECORD_LEN)
            assertEquals(rec.frames, records / rx.protocol.RxRaw.RECORD_LEN)
            val meta = File(dir, "meta.json").readText()
            assertEquals(sha(bytes), Regex("\"frames.rxraw\":\"([0-9a-f]{64})\"").find(meta)!!.groupValues[1])
        } finally { dir.deleteRecursively() }
    }

    /** 下游收了一部分才出錯：不能重送那一段（檔案會多出重複的位元組），錄製要一直算不完整。 */
    @Test
    fun partialWriteFailureIsNeverReplayedAndStaysIncomplete() {
        val dir = Files.createTempDirectory("rex-partial").toFile()
        var failNext = false
        try {
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                val inner = SystemFileSystem.sink(p)
                if (p.name != "frames.rxraw") inner else object : RawSink {
                    override fun write(source: Buffer, byteCount: Long) {
                        if (failNext && byteCount > 3) { failNext = false; inner.write(source, 3); throw IOException("partial") }
                        inner.write(source, byteCount)
                    }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            })
            failNext = true
            var n = 0
            assertFailsWith<IOException> {
                while (true) { rec.onFrame(n * 1000L, 0, rx.protocol.Frame(0x181, byteArrayOf(1, 2, 3, n.toByte()))); n++; check(n < 100_000) }
            }
            val before = File(dir, "frames.rxraw").length()
            assertFailsWith<IOException> { rec.close() }
            assertFailsWith<IOException> { rec.close() }
            assertFalse(rec.finalizationComplete)
            rec.abandon()
            assertEquals(before, File(dir, "frames.rxraw").length(), "壞掉之後不能再寫任何東西")
            assertFalse("\"frames.rxraw\":\"" in File(dir, "meta.json").readText())
        } finally { dir.deleteRecursively() }
    }

    /** 透過 JVM 的 OutputStream 寫到一半出錯（緩衝看起來沒動、其實寫出了前 3 個位元組）：也不能重送。 */
    @Test
    fun outputStreamPartialWriteIsNeverReplayed() {
        val dir = Files.createTempDirectory("rex-partial-os").toFile()
        try {
            var failNext = false
            val rec = Recorder(Path(dir.path), layout(), emptyMap(), wallClock = { 0L }, openSink = { p ->
                if (p.name != "frames.rxraw") SystemFileSystem.sink(p) else {
                    val file = java.io.FileOutputStream(File(p.toString()))
                    object : java.io.OutputStream() {
                        override fun write(b: Int) = file.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (failNext && len > 3) { failNext = false; file.write(b, off, 3); throw IOException("disk") }
                            file.write(b, off, len)
                        }
                        override fun close() = file.close()
                    }.asSink()
                }
            })
            failNext = true
            var n = 0
            assertFailsWith<IOException> {
                while (true) { rec.onFrame(n * 1000L, 0, rx.protocol.Frame(0x181, byteArrayOf(1, 2, 3, n.toByte()))); n++; check(n < 100_000) }
            }
            val before = File(dir, "frames.rxraw").length()
            assertFailsWith<IOException> { rec.close() }
            assertFalse(rec.finalizationComplete)
            rec.abandon()
            assertEquals(before, File(dir, "frames.rxraw").length(), "不能重送已經寫出的那一段")
            // 放棄之後再收檔（例如空間恢復了）也不能變成完整
            assertFailsWith<IllegalStateException> { rec.close() }
            assertFalse(rec.finalizationComplete)
            assertFalse("\"frames.rxraw\":\"" in File(dir, "meta.json").readText())
        } finally { dir.deleteRecursively() }
    }

    /** 開檔時檔頭就被上限擋下：建構失敗，每個已開的檔都要關（沒有 Recorder 可以 abandon）。 */
    @Test
    fun constructionRejectedByTheCapLeavesNoOpenFile() {
        val dir = Files.createTempDirectory("rex-open-cap").toFile()
        val open = HashSet<String>()
        try {
            val budget = Budget(1_000)
            val big = ChannelRegistry(null, (1..100).map { ChannelDef("Channel_" + "x".repeat(150) + "_$it", 1000 + it, 2, 1.0, 0.0, 1.0, 0.0, "Para", "u", "Stage_1", 0, emptyMap()) })
            val lay = DecodedLayout(big, ChannelTable((1..100).map { ChannelTable.Entry(1000 + it, 2) }))
            assertFailsWith<IOException> {
                Recorder(Path(dir.path), lay, emptyMap(), wallClock = { 0L }, openSink = { p ->
                    val inner = Capped(SystemFileSystem.sink(p), budget)
                    open += p.name
                    object : RawSink {
                        override fun write(source: Buffer, byteCount: Long) = inner.write(source, byteCount)
                        override fun flush() = inner.flush()
                        override fun close() { inner.close(); open -= p.name }
                    }
                })
            }
            assertTrue(open.isEmpty(), "還開著：$open")
        } finally { dir.deleteRecursively() }
    }
}
