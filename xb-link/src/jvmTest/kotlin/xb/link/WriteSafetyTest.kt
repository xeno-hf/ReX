package xb.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

/** 連線（EcuLink）與整包寫入（Amc）的寫入安全：合成的單格表與記憶體管線，不連裝置。只在 XB 的建置跑（ReX 不編 EcuLink／Amc）。 */
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

    /** 整包寫入的時間參數不合理：一個 byte 都不送就擋下（不能寫完整份才在讀回時發現）。 */
    @Test fun amcWriteRejectsNonsenseTimingBeforeSending() = runBlocking {
        val wire = Wire(); val link = EcuLink(wire, this, timing)
        try {
            assertEquals("ok", link.connect(888888))
            val p = Amc.Packed(ByteArray(16), 7, 1, emptyList(), tune())
            for (bad in listOf<suspend () -> Unit>(
                { Amc.write(link, p, readAttempts = 0) },
                { Amc.write(link, p, retryDelayMs = -1) },
                { Amc.write(link, p, settleMs = -1) },
                { Amc.write(link, p, paceMs = -1) },
            )) assertFailsWith<IllegalArgumentException> { bad() }
            assertEquals(0, wire.writes())
        } finally { link.close() }
    }
}
