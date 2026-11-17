package xb.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import rx.protocol.CanId
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.FrameParser
import rx.protocol.Rpc
import rx.protocol.SdoPort
import xb.tune.Cell
import xb.tune.Definitions
import xb.tune.ScaleData
import xb.tune.Tune
import xb.tune.TuneFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 即時調校（LiveTuning、EcuSync，只走 SdoPort）的寫入安全：合成的表與記憶體端點，不連裝置。XB 與 ReX 的建置都跑。 */
class LiveTuningSafetyTest {
    private fun tune(): Tune {
        val defs = Definitions.parse("""<ECUControlFile><Parameters>
            <Tab name="Synthetic" absid="1"><Tinf>1,0,0,1,0,0,0,0,255,0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_</Tinf><Ver c_version="7" status="En"/></Tab>
            </Parameters></ECUControlFile>""")
        val f = TuneFile.parse("""<AMC_File><MAP_Type>AMX</MAP_Type><V_Code>1</V_Code><C_Code>7</C_Code><A_Code>1</A_Code><Table_ID>00_00_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue><Table name="Synthetic" absid="1">10</Table></TableValue></AMC_File>""")
        return Tune(defs, ScaleData("test", emptyMap()), f)
    }

    @Test fun compatibilityFailsClosedOnIdentityPlatformAndModel() {
        val t = tune(); val id = EcuIdentity(1, 1, 7, 1, "synthetic", "00_00_10_01")
        assertNull(EcuSync.compatibility(id, EcuPlatform.MINI_X, t))
        assertNotNull(EcuSync.compatibility(id, null, t))
        assertNotNull(EcuSync.compatibility(id, EcuPlatform.SUPER_X, t))
        assertNotNull(EcuSync.compatibility(id.copy(v = null), EcuPlatform.MINI_X, t))
        assertNotNull(EcuSync.compatibility(id.copy(c = 8), EcuPlatform.MINI_X, t))
        assertNotNull(EcuSync.compatibility(id.copy(tableId = null), EcuPlatform.MINI_X, t))
        assertNotNull(EcuSync.compatibility(id.copy(tableId = "00_00_11_01"), EcuPlatform.MINI_X, t))
    }

    @Test fun restoreReportsFailedReadBackInsteadOfSuccess() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val snapshot = Snapshot(EcuIdentity(1, 1, 7, 1, "synthetic"), listOf(TableRead.Ok(def, listOf(longArrayOf(10)))))
        var readsFail = false
        var writes = 0
        val port = object : SdoPort {
            override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray {
                check(!readsFail) { "synthetic read-back failure" }
                return byteArrayOf(11)
            }
            override suspend fun write(index: Int, sub: Int, data: ByteArray) { writes++ }
        }
        val live = LiveTuning(port, t, snapshot, this)
        try {
            t.setRaw(1, Cell(0, 0), 11)
            live.writeTable(1)
            readsFail = true
            val result = live.restore(mapOf(1 to listOf(longArrayOf(10))))
            assertFalse(result.ok)
            assertTrue(1 in result.failed)
            assertTrue(result.restored.isEmpty())
            assertTrue(writes >= 2)
        } finally { live.close() }
    }

    /** 單格表的測試端點：記住寫進去的值；[readsFail] 時讀回失敗；[holdWrite]／[holdRead] 讓寫或讀停在半路。 */
    private class OneCell(var value: Long) : SdoPort {
        var readsFail = false
        var writes = 0
        var holdWrite: CompletableDeferred<Unit>? = null
        var holdRead: CompletableDeferred<Unit>? = null
        val writeStarted = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray {
            readStarted.complete(Unit)
            holdRead?.await()
            check(!readsFail) { "synthetic read-back failure" }
            return byteArrayOf(value.toByte())
        }
        override suspend fun write(index: Int, sub: Int, data: ByteArray) {
            writeStarted.complete(Unit)
            holdWrite?.await()
            writes++; value = data[0].toLong() and 0xFF
        }
    }

    /** 前 [timeouts] 次讀取逾時（像 AMC 剛寫完 ECU 還在忙），之後正常回值。 */
    private class BusyAfterWrite(val value: Long, var timeouts: Int) : SdoPort {
        var reads = 0
        override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray {
            reads++
            if (timeouts > 0) { timeouts--; throw rx.protocol.IOException("read 0x${index.toString(16)}/$sub: no reply in 1500 ms") }
            return byteArrayOf(value.toByte())
        }
        override suspend fun write(index: Int, sub: Int, data: ByteArray) = error("read only")
    }

    /** AMC 寫完的讀回：逾時要等一下重讀，不能第一次沒回就判失敗。 */
    @Test fun readBackRetriesTimeouts() = runBlocking {
        val def = tune().definition(1)!!
        val ecu = BusyAfterWrite(10, timeouts = 2)
        val logs = mutableListOf<String>()
        val r = EcuSync.readTableRetrying(ecu, def, attempts = 4, retryDelayMs = 1, log = { logs += it })
        assertTrue(r is TableRead.Ok)
        assertEquals(10L, r.rows[0][0])
        assertEquals(3, ecu.reads)
        assertEquals(2, logs.size)
        assertTrue(logs.all { def.name in it })
    }

    /** 重試用完還逾時：丟例外，訊息帶表名，不會一直讀下去。 */
    @Test fun readBackGivesUpWithTableName() = runBlocking {
        val def = tune().definition(1)!!
        val ecu = BusyAfterWrite(10, timeouts = 10)
        val e = assertFailsWith<java.io.IOException> { EcuSync.readTableRetrying(ecu, def, attempts = 3, retryDelayMs = 1) }
        assertTrue(def.name in (e.message ?: ""))
        assertEquals(3, ecu.reads)
    }

    private fun snap(def: xb.tune.TableDef, v: Long) = Snapshot(EcuIdentity(1, 1, 7, 1, "synthetic"), listOf(TableRead.Ok(def, listOf(longArrayOf(v)))))

    /** 寫 11 送到一半時使用者改回 10（跟快取一樣）：不能弄丟這次修改，寫完 11 要接著寫 10。 */
    @Test fun editingBackDuringAnInFlightWriteIsNotLost() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdWrite = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 30, verifyMs = 10_000)
        try {
            t.setRaw(1, Cell(0, 0), 11); live.onEdited(1)
            withTimeout(5_000) { ecu.writeStarted.await() }
            t.setRaw(1, Cell(0, 0), 10); live.onEdited(1)
            delay(100)
            ecu.holdWrite!!.complete(Unit)
            withTimeout(5_000) { while (ecu.value != 10L || live.states.value[1] != LiveTuning.TableState.Synced) delay(20) }
            assertEquals(10L, ecu.value)
            assertEquals(2, ecu.writes)
        } finally { live.close() }
    }

    /** 讀回時被取消（例如離開頁面）：表維持不確定，改回快取的值也要真的重寫。 */
    @Test fun cancelledReadBackKeepsTheTableUncertain() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdRead = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 30, verifyMs = 10_000)
        try {
            t.setRaw(1, Cell(0, 0), 11)
            val job = launch { live.writeTable(1) }
            withTimeout(5_000) { ecu.readStarted.await() }
            job.cancel(); job.join()
            assertEquals(11L, ecu.value)
            ecu.holdRead = null
            t.setRaw(1, Cell(0, 0), 10); live.onEdited(1)
            withTimeout(5_000) { while (live.states.value[1] != LiveTuning.TableState.Synced) delay(20) }
            assertEquals(10L, ecu.value)
        } finally { live.close() }
    }

    /** 還原時，就算重讀 ECU 本身出錯，也要回報告、記成失敗，不是整個中斷。 */
    @Test fun restoreReportsReadErrorsPerTable() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(3).apply { readsFail = true }
        val live = LiveTuning(ecu, t, snap(def, 0), this)
        try {
            val report = live.restore(mapOf(1 to listOf(longArrayOf(0))), recheck = setOf(1))
            assertFalse(report.ok)
            assertTrue(1 in report.failed)
        } finally { live.close() }
    }

    /** 寫了 11、讀回失敗；使用者改回 10：快取是 10 也不能當成同步，要真的寫 10 並讀回。 */
    @Test fun uncertainWriteIsResolvedOnlyByVerifiedRewrite() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10)
        val live = LiveTuning(ecu, t, Snapshot(EcuIdentity(1, 1, 7, 1, "synthetic"), listOf(TableRead.Ok(def, listOf(longArrayOf(10))))), this, debounceMs = 50, verifyMs = 10_000)
        try {
            t.setRaw(1, Cell(0, 0), 11)
            ecu.readsFail = true
            live.writeTable(1)
            assertEquals(11L, ecu.value)
            assertTrue(live.states.value[1] is LiveTuning.TableState.Failed)
            ecu.readsFail = false
            t.setRaw(1, Cell(0, 0), 10)
            live.onEdited(1)
            withTimeout(5_000) { while (live.states.value[1] != LiveTuning.TableState.Synced) delay(20) }
            assertEquals(10L, ecu.value)
        } finally { live.close() }
    }

    /** 別的路改了 ECU、沒經過即時寫入（儀表鈕開 AT、ECU 自己學習改油表）：還原每張都重讀，不同就重寫。 */
    @Test fun restoreRechecksTablesChangedOutsideLiveTuning() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(0)
        val live = LiveTuning(ecu, t, snap(def, 0), this)
        try {
            ecu.value = 1 // 沒經過這裡寫的改動
            val report = live.restore(mapOf(1 to listOf(longArrayOf(0))))
            assertEquals(listOf(1), report.restored)
            assertEquals(0L, ecu.value)
            // 另一張沒動過、ECU 也一樣的表：重讀確認一樣才算沒變
            val other = LiveTuning(OneCell(0), t, snap(def, 0), this)
            try { assertEquals(listOf(1), other.restore(mapOf(1 to listOf(longArrayOf(0))), recheck = setOf(1)).unchanged) } finally { other.close() }
        } finally { live.close() }
    }

    /** 清除學習值的合成命令：ECU 收下就把表填成中性 0x80（像韌體處理 0x2009）。 */
    private fun clearCommand(ecu: OneCell, reply: String? = null): suspend () -> String? = { ecu.value = 0x80; reply }

    private fun applyTo(t: Tune): suspend (Int, List<LongArray>) -> Unit = { id, rows -> check(t.loadRows(id, rows)) }

    /** 正在寫的表先寫完才送命令；還沒送的改動丟掉；做完編輯器是清過的值、可以再寫。 */
    @Test fun clearWaitsForInFlightWriteAndDropsPendingEdits() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdWrite = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 30, verifyMs = 10_000)
        try {
            t.setRaw(1, Cell(0, 0), 11); live.onEdited(1)
            withTimeout(5_000) { ecu.writeStarted.await() }
            val clear = async { live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = clearCommand(ecu), apply = applyTo(t)) }
            delay(50)
            assertFalse(clear.isCompleted) // 等寫入鎖
            // 等鎖的期間就不能寫：這時的改動不能在清除之後送出去
            assertFalse(live.writable(1))
            t.setRaw(1, Cell(0, 0), 13); live.onEdited(1)
            ecu.holdWrite!!.complete(Unit)
            val r = withTimeout(5_000) { clear.await() }
            delay(150)
            assertTrue(r.ok)
            assertEquals(listOf(1), r.read)
            assertEquals(0x80L, ecu.value)
            assertEquals(0x80L, t.rows(1)!![0][0])
            assertTrue(live.writable(1))
            assertNull(live.states.value[1])
            assertEquals(1, ecu.writes)
            // 清完之後的改動照常送
            t.setRaw(1, Cell(0, 0), 12); live.onEdited(1)
            withTimeout(5_000) { while (live.states.value[1] != LiveTuning.TableState.Synced) delay(20) }
            assertEquals(12L, ecu.value)
        } finally { live.close() }
    }

    /** 等鎖時排在前面的全讀解開了表：清除拿到鎖後要重新鎖住，那段期間的改動也不能在清除後送出。 */
    @Test fun readBackQueuedBeforeTheClearCannotUnlockIt() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdRead = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 30, verifyMs = 10_000)
        try {
            val read = async { live.readTable(1) { rows -> check(t.loadRows(1, rows)) } }
            withTimeout(5_000) { ecu.readStarted.await() }
            // 命令送到一半停住：這時全讀已經做完（解過鎖），清除要已經重新鎖住
            val inCommand = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val clear = async {
                live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = {
                    inCommand.complete(Unit); release.await(); ecu.value = 0x80; null
                }, apply = applyTo(t))
            }
            withTimeout(5_000) { live.held.first { 1 in it } }
            assertFalse(live.writable(1))
            ecu.holdRead!!.complete(Unit)
            assertTrue(withTimeout(5_000) { read.await() }.isSuccess)
            withTimeout(5_000) { inCommand.await() }
            assertFalse(live.writable(1))
            t.setRaw(1, Cell(0, 0), 13); live.onEdited(1)
            delay(100) // 超過停手時間：鎖著的話不會排程
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { clear.await() }.ok)
            delay(150)
            assertEquals(0, ecu.writes)
            assertEquals(0x80L, ecu.value)
            assertEquals(0x80L, t.rows(1)!![0][0])
        } finally { live.close() }
    }

    /**
     * 「全寫」在清除鎖表前一刻複製了舊值、排到清除之後才上排程：要丟掉，不能把清掉的學習值寫回去。
     * 用另一張表的清除在套用時同步卡住排程執行緒，讓清除先排上去、全寫的舊複本排在它後面。
     */
    @Test fun writeAllCopiedBeforeTheClearIsDropped() = runBlocking {
        val t = twoTables(2); val ecu = Learner(2, fuel = 10, learn = 0)
        val live = LiveTuning(ecu, t, snap2(t, 2, 10, 0), this, debounceMs = 30, verifyMs = 10_000)
        val entered = java.util.concurrent.CountDownLatch(1); val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val block = async(Dispatchers.IO) {
                live.commandThenRead(listOf(2), settleMs = 0, retryDelayMs = 1, command = { null }, apply = { id, rows ->
                    entered.countDown(); gate.await(); check(t.loadRows(id, rows))
                })
            }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            // 不分派地開始：回到這裡時清除已經排上排程（執行緒卡著，還沒鎖表）
            val clear = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = { ecu.fuel = 0x80; null }, apply = applyTo(t))
            }
            assertTrue(live.writable(1)) // 清除還沒鎖表：全寫照樣複製得到舊值
            // 同樣不分派：回到這裡時全寫已複製舊值、排在清除後面
            val write = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { live.writeTable(1) }
            gate.countDown()
            assertTrue(withTimeout(5_000) { block.await() }.ok)
            assertTrue(withTimeout(5_000) { clear.await() }.ok)
            withTimeout(5_000) { write.await() }
            delay(150)
            assertEquals(0x80L, ecu.fuel)
            assertTrue(ecu.log.none { it.startsWith("fuel=") }, ecu.log.toString())
            assertEquals(0x80L, t.rows(1)!![0][0])
        } finally { gate.countDown(); live.close() }
    }

    /**
     * 畫面的執行緒檢查完可寫、還沒複製時，清除剛好鎖表並送命令：這時複製到的是清除前的值，
     * 等清除做完（解鎖）才上排程也要丟掉——世代要在檢查可寫之前就記下。全寫與停手兩條路都測。
     */
    @Test fun copyBetweenTheWritableCheckAndTheClearIsDropped() = runBlocking {
        for (path in listOf("writeTable", "onEdited")) {
            val t = tune(); val def = t.definition(1)!!
            val ecu = OneCell(10)
            val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 30, verifyMs = 10_000)
            val inCommand = java.util.concurrent.CountDownLatch(1); val go = java.util.concurrent.CountDownLatch(1)
            try {
                var clear: kotlinx.coroutines.Deferred<LiveTuning.CommandRead>? = null
                live.testSeam = { at ->
                    when (at) {
                        // 檢查完可寫：清除鎖表、命令送到一半卡住排程執行緒
                        "checked" -> {
                            clear = async(Dispatchers.IO) {
                                live.commandThenRead(listOf(1), settleMs = 0, retryDelayMs = 1, command = {
                                    inCommand.countDown(); go.await(); ecu.value = 0x80; null
                                }, apply = applyTo(t))
                            }
                            assertTrue(inCommand.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            assertFalse(live.writable(1))
                        }
                        // 複製完（舊值 10）：放清除做完，複本排在它後面
                        "copied" -> { live.testSeam = null; go.countDown() }
                    }
                }
                if (path == "writeTable") withContext(Dispatchers.IO) { live.writeTable(1) }
                else withContext(Dispatchers.IO) { live.onEdited(1) }
                assertTrue(withTimeout(5_000) { clear!!.await() }.ok, path)
                delay(150)
                assertEquals(0x80L, ecu.value, path)
                assertEquals(0, ecu.writes, path)
                assertEquals(0x80L, t.rows(1)!![0][0], path)
            } finally { go.countDown(); live.close() }
        }
    }

    /** 停手計時中的改動：清除時丟掉，計時到了也不會把舊值寫回去。清除期間的改動不送。 */
    @Test fun clearCancelsDebouncedWritesAndIgnoresEditsWhileRunning() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdRead = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 200, verifyMs = 10_000)
        try {
            t.setRaw(1, Cell(0, 0), 11); live.onEdited(1)
            delay(20)
            val clear = async { live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = clearCommand(ecu), apply = applyTo(t)) }
            withTimeout(5_000) { ecu.readStarted.await() }
            assertFalse(live.writable(1))
            t.setRaw(1, Cell(0, 0), 12); live.onEdited(1)
            delay(300)
            ecu.holdRead!!.complete(Unit)
            assertTrue(withTimeout(5_000) { clear.await() }.ok)
            delay(300)
            assertEquals(0, ecu.writes)
            assertEquals(0x80L, ecu.value)
            assertEquals(0x80L, t.rows(1)!![0][0])
        } finally { live.close() }
    }

    /** 命令沒回（ECU 可能已經做了）：照樣重讀，編輯器換成 ECU 現值。 */
    @Test fun lostCommandAckStillRereads() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10)
        val live = LiveTuning(ecu, t, snap(def, 10), this, verifyMs = 10_000)
        try {
            val r = live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = clearCommand(ecu, reply = "no reply"), apply = applyTo(t))
            assertTrue(r.sent); assertFalse(r.ok)
            assertEquals("no reply", r.command)
            assertEquals(listOf(1), r.read)
            assertEquals(0x80L, t.rows(1)!![0][0])
            assertTrue(live.writable(1))
        } finally { live.close() }
    }

    /** 重讀失敗：表鎖住、標沒確定、整包寫入改用 ECU 現值；全讀成功並換進編輯器才解開。 */
    @Test fun failedRereadKeepsTablesLockedUntilReadBack() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10)
        val live = LiveTuning(ecu, t, snap(def, 10), this, debounceMs = 30, verifyMs = 10_000)
        try {
            val r = live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = { ecu.value = 0x80; ecu.readsFail = true; null }, apply = applyTo(t))
            assertTrue(r.sent); assertFalse(r.ok)
            assertTrue(1 in r.failed)
            assertTrue(live.states.value[1] is LiveTuning.TableState.Failed)
            assertFalse(live.writable(1))
            assertTrue(1 in live.ecuOverrides())
            assertEquals(10L, t.rows(1)!![0][0]) // 編輯器還是舊值，但不會送
            t.setRaw(1, Cell(0, 0), 11); live.onEdited(1)
            live.flushAll(); delay(100)
            assertEquals(0, ecu.writes)
            ecu.readsFail = false
            // 不帶 apply 的重讀：編輯器沒換，不解開
            assertTrue(live.readTable(1).isSuccess)
            assertFalse(live.writable(1))
            assertTrue(live.readTable(1) { rows -> check(t.loadRows(1, rows)) }.isSuccess)
            assertTrue(live.writable(1))
            assertEquals(0x80L, t.rows(1)!![0][0])
        } finally { live.close() }
    }

    /** 呼叫的頁面離開（協程被取消）：清除照樣做完、編輯器照樣換。 */
    @Test fun leavingThePageDoesNotAbortTheClear() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdRead = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, verifyMs = 10_000)
        try {
            val page = launch { live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = clearCommand(ecu), apply = applyTo(t)) }
            withTimeout(5_000) { ecu.readStarted.await() }
            page.cancel(); page.join()
            ecu.holdRead!!.complete(Unit)
            withTimeout(5_000) { while (!live.writable(1)) delay(20) }
            assertEquals(0x80L, t.rows(1)!![0][0])
            assertNull(live.states.value[1])
        } finally { live.close() }
    }

    /** 清除途中關掉即時調校（例如整包寫入前）：沒重讀的表標沒確定，整包寫入改用 ECU 現值。 */
    @Test fun closingDuringTheClearMarksTablesUnconfirmed() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10).apply { holdRead = CompletableDeferred() }
        val live = LiveTuning(ecu, t, snap(def, 10), this, verifyMs = 10_000)
        val clear = async { live.commandThenRead(listOf(1), settleMs = 1, retryDelayMs = 1, command = clearCommand(ecu), apply = applyTo(t)) }
        withTimeout(5_000) { ecu.readStarted.await() }
        live.close()
        val r = withTimeout(5_000) { clear.await() }
        assertTrue(r.sent); assertFalse(r.ok)
        assertTrue(1 in r.failed)
        assertTrue(live.states.value[1] is LiveTuning.TableState.Failed)
        assertTrue(1 in live.ecuOverrides())
        assertEquals(10L, t.rows(1)!![0][0])
    }

    /** 表沒讀到就不送命令。 */
    @Test fun clearNeedsEveryTableReadable() = runBlocking {
        val t = tune(); val def = t.definition(1)!!
        val ecu = OneCell(10)
        val live = LiveTuning(ecu, t, snap(def, 10), this)
        try {
            var sent = false
            val r = live.commandThenRead(listOf(1, 2), settleMs = 1, command = { sent = true; null }, apply = applyTo(t))
            assertFalse(r.sent); assertFalse(sent)
            assertTrue(live.writable(1))
        } finally { live.close() }
    }

    /** readAll 跟 readTableRetrying 一樣：次數、延遲不合理就不讀。 */
    @Test fun readAllRejectsNonsenseRetryArguments() = runBlocking {
        val t = tune()
        assertFailsWith<IllegalArgumentException> { EcuSync.readAll(OneCell(10), t, attempts = 0) }
        assertFailsWith<IllegalArgumentException> { EcuSync.readAll(OneCell(10), t, attempts = 2, retryDelayMs = -1) }
        Unit
    }

    /** 燃油表 1、學習開關 [learnId] 的合成 ECU：學習開著時，每次存取燃油表後 ECU 都把它加 1（像自動調校就地改油表）。 */
    private class Learner(val learnId: Int, var fuel: Long, var learn: Long) : SdoPort {
        val log = ArrayList<String>()
        var refuseLearnWrite = false
        private fun learning() { if (learn != 0L) fuel++ }
        override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray = when (index - EcuSync.TABLE_BASE) {
            1 -> byteArrayOf(fuel.toByte()).also { learning() }
            learnId -> byteArrayOf(learn.toByte())
            else -> error("no table $index")
        }
        override suspend fun write(index: Int, sub: Int, data: ByteArray) {
            val v = data[0].toLong() and 0xFF
            when (index - EcuSync.TABLE_BASE) {
                1 -> { fuel = v; log += "fuel=$v"; learning() }
                learnId -> { if (refuseLearnWrite) throw SdoAbortException(rx.protocol.Sdo.Abort(index, sub, 0x06010002L)); learn = v; log += "learn=$v" }
                else -> error("no table $index")
            }
        }
    }

    private fun twoTables(learnId: Int): Tune {
        val defs = Definitions.parse("""<ECUControlFile><Parameters>
            <Tab name="Fuel" absid="1"><Tinf>1,0,0,1,0,0,0,0,255,0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_</Tinf><Ver c_version="7" status="En"/></Tab>
            <Tab name="Learn" absid="$learnId"><Tinf>$learnId,0,0,1,0,0,0,0,255,0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_</Tinf><Ver c_version="7" status="En"/></Tab>
            </Parameters></ECUControlFile>""")
        val f = TuneFile.parse("""<AMC_File><MAP_Type>AMX</MAP_Type><V_Code>1</V_Code><C_Code>7</C_Code><A_Code>1</A_Code><Table_ID>00_00_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue><Table name="Fuel" absid="1">10</Table><Table name="Learn" absid="$learnId">1</Table></TableValue></AMC_File>""")
        return Tune(defs, ScaleData("test", emptyMap()), f)
    }

    private fun snap2(t: Tune, learnId: Int, fuel: Long, learn: Long) = Snapshot(EcuIdentity(1, 1, 7, 1, "synthetic"), listOf(
        TableRead.Ok(t.definition(1)!!, listOf(longArrayOf(fuel))), TableRead.Ok(t.definition(learnId)!!, listOf(longArrayOf(learn)))))

    /**
     * 備份裡學習開著（AT 14；SxS 的 Pro-AT 1580）：還原要先關學習、燃油表在學習關著時寫回並確認，最後才把學習開回來。
     * 學習開著時寫燃油表，ECU 馬上又改掉，報告卻會說成功。
     */
    @Test fun restoreStopsLearningBeforeFuelTablesAndResumesItLast() = runBlocking {
        for ((platform, learnId) in listOf(EcuPlatform.MINI_X to 14, EcuPlatform.SXS to 1580)) {
            val t = twoTables(learnId)
            val learning = Learning.tables(platform)
            assertTrue(learnId in learning)
            val ecu = Learner(learnId, fuel = 10, learn = 1)
            val live = LiveTuning(ecu, t, snap2(t, learnId, 10, 1), this)
            try {
                ecu.fuel = 25 // 騎了一段，自動調校改了油表
                val report = live.restore(mapOf(1 to listOf(longArrayOf(10)), learnId to listOf(longArrayOf(1))), learning = learning)
                assertTrue(report.ok, "$platform: ${report.failed}")
                assertEquals(listOf("learn=0", "fuel=10", "learn=1"), ecu.log, "$platform")
                assertEquals(1L, ecu.learn)
                assertEquals(setOf(1, learnId), (report.restored + report.unchanged).toSet())
            } finally { live.close() }
        }
    }

    /** 學習開關關不掉（ECU 拒絕）：燃油表就算讀回一致也不能算還原成功。 */
    @Test fun restoreFailsWhenLearningCannotBeStopped() = runBlocking {
        val t = twoTables(14)
        val ecu = Learner(14, fuel = 25, learn = 1).apply { refuseLearnWrite = true }
        val live = LiveTuning(ecu, t, snap2(t, 14, 10, 1), this)
        try {
            val report = live.restore(mapOf(1 to listOf(longArrayOf(10)), 14 to listOf(longArrayOf(1))), learning = Learning.tables(EcuPlatform.MINI_X))
            assertFalse(report.ok)
            assertTrue(1 in report.failed)
            assertTrue(14 in report.failed)
            assertTrue(report.restored.isEmpty())
        } finally { live.close() }
    }

    /**
     * 學習開關在備份時讀不到、不在備份裡（例：SxS 1580 讀失敗、油表讀得到）：還原照樣要先確認它關了；
     * 讀不到就確認不了，油表不能算還原成功。
     */
    @Test fun restoreChecksLearningSwitchesMissingFromTheBackup() = runBlocking {
        val t = twoTables(1580)
        val ecu = Learner(1580, fuel = 25, learn = 1)
        val snapshot = Snapshot(EcuIdentity(1, 1, 7, 1, "synthetic"), listOf(TableRead.Ok(t.definition(1)!!, listOf(longArrayOf(10)))))
        val live = LiveTuning(ecu, t, snapshot, this)
        try {
            // 現在讀得到：關掉、確認、油表還原；沒有備份值可寫回，記成失敗
            val report = live.restore(mapOf(1 to listOf(longArrayOf(10))), learning = Learning.tables(EcuPlatform.SXS))
            assertEquals(listOf("learn=0", "fuel=10"), ecu.log)
            assertEquals(listOf(1), report.restored)
            assertTrue(1580 in report.failed)
            assertEquals(0L, ecu.learn)
        } finally { live.close() }
        val broken = object : SdoPort {
            val inner = Learner(1580, fuel = 25, learn = 1)
            override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray {
                check(index - EcuSync.TABLE_BASE != 1580) { "synthetic read failure" }
                return inner.read(index, sub, block)
            }
            override suspend fun write(index: Int, sub: Int, data: ByteArray) = inner.write(index, sub, data)
        }
        val live2 = LiveTuning(broken, t, snapshot, this)
        try {
            val report = live2.restore(mapOf(1 to listOf(longArrayOf(10))), learning = Learning.tables(EcuPlatform.SXS))
            assertFalse(report.ok)
            assertTrue(1 in report.failed, "${report.failed}")
            assertTrue(report.restored.isEmpty())
        } finally { live2.close() }
    }

    /** 學習關著時，後面還原的表動到前面已確認的表（韌體連動）：最後整份重讀要抓到，不能報成功。 */
    @Test fun restoreRereadsEveryTableBeforeResumingLearning() = runBlocking {
        val t = twoTables(14)
        val ecu = object : SdoPort {
            var fuel = 25L; var learn = 0L; var readsAfterWrite = -1
            override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray = when (index - EcuSync.TABLE_BASE) {
                // 寫完第一次讀回是 10，之後被別的表連動改成 11
                1 -> { if (readsAfterWrite >= 0 && ++readsAfterWrite > 1) fuel = 11; byteArrayOf(fuel.toByte()) }
                14 -> byteArrayOf(learn.toByte())
                else -> error("no table")
            }
            override suspend fun write(index: Int, sub: Int, data: ByteArray) {
                val v = data[0].toLong() and 0xFF
                when (index - EcuSync.TABLE_BASE) { 1 -> { fuel = v; readsAfterWrite = 0 }; 14 -> learn = v }
            }
        }
        val live = LiveTuning(ecu, t, snap2(t, 14, 10, 0), this)
        try {
            val report = live.restore(mapOf(1 to listOf(longArrayOf(10)), 14 to listOf(longArrayOf(0))), learning = Learning.tables(EcuPlatform.MINI_X))
            assertFalse(report.ok)
            assertTrue(1 in report.failed, "${report.failed}")
            assertTrue(1 !in report.restored)
        } finally { live.close() }
    }
}
