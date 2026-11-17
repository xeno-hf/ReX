package xb.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

/** 全部使用合成的單格表與記憶體管線，不連裝置。 */
class WriteSafetyTest {
    private class Wire : Transport {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = ArrayList<Frame>()
        var node = 1
        init { input.trySend(Frame(CanId.ecuHeartbeat(node), byteArrayOf(5)).encode()) }
        val parser = FrameParser { f ->
            sent += f
            if (f.canId == CanId.SYS && f.dlc == 5 && f.u8(0) == 0x88) input.trySend(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x89.toByte(), 0)).encode())
            if (f.canId == CanId.rpc(node) && f.dlc == 8 && f.u8(0) in setOf(0x23, 0x27, 0x2B, 0x2F)) {
                input.trySend(Frame(CanId.rpcReply(node), byteArrayOf(0x60, f.data[1], f.data[2], f.data[3])).encode())
            }
        }
        override suspend fun send(bytes: ByteArray) { parser.feed(bytes) }
        override suspend fun receive(): ByteArray? = input.receiveCatching().getOrNull()
        override fun close() { input.close() }
        fun writes() = sent.count { it.canId == CanId.rpc(node) }
    }
    private val timing = EcuLink.Timing(stepMs = 0, sessionOpenMs = 0, heartbeatMs = 60_000, platformWaitMs = 300, loginTimeoutMs = 200, replyTimeoutMs = 200)

    @Test fun unauthorizedObjectsNeverReachTransport() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            assertEquals("ok", link.connect(888888))
            assertFailsWith<IllegalStateException> { link.write(0x3001, 0, byteArrayOf(1)) }
            assertTrue(link.enableCalibration(setOf(0x3001)))
            assertFailsWith<IllegalArgumentException> { link.write(0x1010, 1, byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException> { link.write(0x3002, 0, byteArrayOf(1)) }
            assertEquals(0, wire.writes())
            link.write(0x3001, 0, byteArrayOf(1))
            assertEquals(1, wire.writes())
        } finally { link.close() }
    }

    @Test fun authorizationIsClearedOnClose() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        link.connect(888888); assertTrue(link.enableCalibration(setOf(0x3001)))
        link.close()
        assertFalse(link.calibrationEnabled)
        assertFalse(link.enableCalibration(setOf(0x3001)))
        assertFailsWith<IllegalStateException> { link.write(0x3001, 0, byteArrayOf(1)) }
        assertEquals(0, wire.writes())
    }

    @Test fun authorizationIsClearedOnDisconnect() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            link.connect(888888); assertTrue(link.enableCalibration(setOf(0x3001)))
            wire.input.close()
            withTimeout(1000) { while (link.open.value) delay(1) }
            assertFalse(link.calibrationEnabled)
            assertFailsWith<IllegalStateException> { link.write(0x3001, 0, byteArrayOf(1)) }
            assertEquals(0, wire.writes())
        } finally { link.close() }
    }

    @Test fun nodeChangeInvalidatesBackupAuthorization(): Unit = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            link.connect(888888); val gen = link.generation
            assertTrue(link.enableCalibration(setOf(0x3001), expectGeneration = gen))
            wire.input.send(Frame(CanId.ecuHeartbeat(2), byteArrayOf(5)).encode())
            withTimeout(1000) { while (link.node.value != 2) delay(1) }
            assertFalse(link.calibrationEnabled)
            assertFalse(link.enableCalibration(setOf(0x3001), expectGeneration = gen))
            assertFailsWith<IllegalStateException> { link.write(0x3001, 0, byteArrayOf(1)) }
        } finally { link.close() }
    }

    @Test fun monitorAndSystemChannelsCannotBypassAllowlist() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            link.connect(888888); link.enableCalibration(setOf(0x3001), true)
            assertFailsWith<IllegalArgumentException> { link.ackedWrite(link.rpcFrames.smallWrite(0x3001, 0, 1, 1)) }
            assertFailsWith<IllegalArgumentException> { link.ackedWrite(link.rpcFrames.smallWrite(0x1800, 5, 65, 2)) }
            assertFailsWith<IllegalArgumentException> { link.ackedWrite(link.rpcFrames.smallWrite(0x1A00, 1, 0, 4)) }
            link.amcExclusive {
                for (data in listOf(byteArrayOf(0x88.toByte(), 1, 0, 0, 0), byteArrayOf(0x50), byteArrayOf(0x32, 1, 0, 16, 0, 0, 0, 0))) {
                    assertFailsWith<IllegalArgumentException> { link.sysExchange(listOf(Frame(CanId.SYS, data)), 0, 1) { true } }
                }
            }
            assertEquals(0, wire.writes())
            assertTrue(link.ackedWrite(link.rpcFrames.monitorStop()))
            assertTrue(link.ackedWrite(link.rpcFrames.monitorStart()))
        } finally { link.close() }
    }

    @Test fun incompleteSnapshotCannotAuthorizeSystemWrites() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            link.connect(888888); link.enableCalibration(setOf(0x3001))
            assertFalse(link.wholeImageEnabled)
            link.amcExclusive {
                assertFailsWith<IllegalStateException> { link.sysExchange(listOf(Frame(CanId.SYS, byteArrayOf(0x30, 0, 7, 0, 1, 0, 0))), 0, 1) { true } }
            }
            assertTrue(wire.sent.none { it.canId == CanId.SYS && it.u8(0) == 0x30 })
        } finally { link.close() }
    }

    @Test fun exclusiveOperationCoversVerificationAndRejectsQueuedWrites() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            link.connect(888888); link.enableCalibration(setOf(0x3001), true)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val transaction = launch { link.amcExclusive { entered.complete(Unit); release.await() } }
            entered.await()
            val pending = async { runCatching { link.write(0x3001, 0, byteArrayOf(1)) } }
            delay(30)
            assertFalse(pending.isCompleted)
            assertEquals(0, wire.writes())
            release.complete(Unit); transaction.join()
            assertTrue(pending.await().isFailure)
            assertEquals(0, wire.writes())
        } finally { link.close() }
    }

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
