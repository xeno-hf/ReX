package rx.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import rx.protocol.CanId
import rx.protocol.ChannelTable
import rx.protocol.Frame
import rx.protocol.FrameParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 調校寫入跟著「開始時那條連線」走：交易中途斷線重連，剩下的幀不能再送到新連線。
 * 用一條腳本化連線（照連線序列回應，區塊寫入照 CiA 301 回 A0／A2／A1）把交易卡在區塊中間。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationFencingTest {
    private fun table() = ChannelTable(listOf(ChannelTable.Entry(26, 2)))

    /**
     * 腳本化連線：只回連線與區塊寫入必要的幀。
     * - 每秒一個 ECU 心跳、每 60 ms 一輪 2 bytes 廣播（讓 Session 進 Streaming、不觸發看門狗）
     * - 0x680 登入回 `89 00`
     * - 小寫回 `60 addr sub`
     * - 區塊寫入：0xC6 → 0xA0（區塊大小）；分段 → 0xA2；結束幀 → 0xA1
     */
    private class ScriptedLink(
        private val scope: CoroutineScope,
        private val table: ChannelTable,
        private val node: Int = 1,
        private val blockSize: Int = 127,
    ) : Transport {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val parser = FrameParser(::onFrame)
        private val ticker: Job
        val received = ArrayList<Frame>()
        var closed = false; private set
        var closeCount = 0; private set

        /** 送這一幀前先停住，等 [resume]；用來把交易卡在傳輸中途。 */
        var pauseOn: ((Frame) -> Boolean)? = null
        val paused = CompletableDeferred<Unit>()
        private val resumeSignal = CompletableDeferred<Unit>()
        fun resume() { resumeSignal.complete(Unit) }

        /** 第一條區塊確認先扣住，等 [releaseBlockAck]；用來檢查確認前不會偷送下一個區塊。 */
        var holdFirstAck = false
        private var ackHeld = false
        private var pendingAck: Frame? = null
        val ackReached = CompletableDeferred<Unit>()
        var segments = 0; private set
        var earlySegments = 0; private set

        private var inBlock = false
        private var blockSent = 0
        private var round = 0

        init {
            ticker = scope.launch {
                while (true) {
                    emit(Frame(CanId.ecuHeartbeat(node), byteArrayOf(5)))
                    repeat(16) {
                        val payload = ByteArray(table.payloadLength) { i -> ((round * 37 + i * 11) and 0xFF).toByte() }
                        emit(Frame(CanId.broadcast(node), byteArrayOf(0x81.toByte()) + payload))
                        round++
                        delay(60)
                    }
                }
            }
        }

        private fun emit(f: Frame) { if (!closed) inbox.trySend(f.encode()) }

        override suspend fun send(bytes: ByteArray) {
            last = null
            parser.feed(bytes)
            val f = last ?: return
            if (pauseOn?.invoke(f) == true) {
                pauseOn = null
                paused.complete(Unit)
                resumeSignal.await()
            }
        }

        override suspend fun receive(): ByteArray? = inbox.receiveCatching().getOrNull()

        override fun close() {
            closeCount++
            closed = true
            ticker.cancel()
            inbox.close()
        }

        /** 對端關閉（不呼叫 close）：像網路自己斷掉，Session 自己走斷線流程。 */
        fun endStream() { inbox.close() }

        fun releaseBlockAck() {
            ackHeld = false
            pendingAck?.let { emit(it) }
            pendingAck = null
        }

        private var last: Frame? = null

        private fun reply(vararg b: Int) = emit(Frame(CanId.rpcReply(node), ByteArray(8) { i -> if (i < b.size) b[i].toByte() else 0 }))

        private fun onFrame(f: Frame) {
            received += f
            last = f
            when (f.canId) {
                CanId.SYS -> if (f.dlc == 5 && f.u8(0) == 0x88) emit(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x89.toByte(), 0)))
                CanId.rpc(node) -> onRpc(f)
            }
        }

        private fun onRpc(f: Frame) {
            if (f.dlc < 1) return
            val b0 = f.u8(0)
            val addr = if (f.dlc >= 3) f.u8(1) or (f.u8(2) shl 8) else -1
            val sub = if (f.dlc >= 4) f.u8(3) else -1
            when {
                b0 == 0xC6 && f.dlc == 8 -> {
                    inBlock = true; blockSent = 0
                    reply(0xA0, addr and 0xFF, addr ushr 8, sub, blockSize)
                }
                inBlock && (b0 and 0xE3) == 0xC1 && f.dlc == 8 -> {
                    inBlock = false
                    reply(0xA1)
                }
                b0 == 0x80 -> inBlock = false
                inBlock && f.dlc == 8 -> {
                    if (ackHeld) earlySegments++
                    segments++
                    blockSent++
                    val lastSegment = b0 and 0x80 != 0
                    if (lastSegment || blockSent == blockSize) {
                        val ack = Frame(CanId.rpcReply(node), byteArrayOf(0xA2.toByte(), blockSent.toByte(), blockSize.toByte()))
                        blockSent = 0
                        if (holdFirstAck && !ackHeld && pendingAck == null && !ackReached.isCompleted) {
                            ackHeld = true
                            pendingAck = ack
                            ackReached.complete(Unit)
                        } else emit(ack)
                    }
                }
                b0 and 3 == 3 && f.dlc == 8 -> reply(0x60, addr and 0xFF, addr ushr 8, sub)
                b0 == 0x40 -> reply(0x80, addr and 0xFF, addr ushr 8, sub, 0, 0, 0, 0x08)
            }
        }
    }

    private fun TestScope.newSession(links: ArrayDeque<ScriptedLink>, t: ChannelTable = table()): Session =
        Session({ links.removeFirstOrNull() ?: ScriptedLink(backgroundScope, t) }, t, 888888, backgroundScope,
            nanoTime = { testScheduler.currentTime * 1_000_000 })

    private fun TestScope.arm(s: Session) {
        val ev = ArrayList<String>()
        s.addListener(object : SessionListener {
            override fun onEvent(tNanos: Long, text: String) { ev += text }
        })
        s.start()
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(s.loggedIn.value, "login did not complete; state=${s.state.value}; lastLogin=${s.stats.lastLogin}; rx=${s.stats.rxFrames} tx=${s.stats.txFrames}; events=${ev.takeLast(15)}")
        assertEquals(SessionState.Streaming, s.state.value)
    }

    /** 這幀是不是送往校正表 [addr] 的寫入流量（區塊分段不帶物件位址，按命令字判斷）。 */
    private fun Frame.isCalibrationFrame(addr: Int): Boolean {
        if (canId != CanId.rpc(1) || dlc < 1) return false
        val b0 = u8(0)
        val a = if (dlc >= 3) u8(1) or (u8(2) shl 8) else -1
        return when {
            b0 == 0xC6 || b0 == 0x80 -> a == addr              // 起始／abort
            b0 and 0xE3 == 0xC1 -> true                        // 區塊結束幀
            b0 in 0x01..0x1F -> dlc == 8                        // 分段（序號 1-31）
            b0 and 0xE3 == 0x23 -> a == addr                   // expedited 寫
            else -> false
        }
    }

    @Test
    fun framesStopAtTheConnectionTheyStartedOn() = runTest {
        val t = table()
        val old = ScriptedLink(backgroundScope, t)
        val new = ScriptedLink(backgroundScope, t)
        val links = ArrayDeque(listOf(old, new))
        val s = newSession(links, t)
        arm(s)
        val gen = s.generation
        assertTrue(s.enableCalibration(setOf(0x3001), null, gen))

        // 第一段分段送出時停住：交易停在區塊中間
        old.pauseOn = { it.canId == CanId.rpc(1) && it.dlc == 8 && it.u8(0) == 0x01 }
        val failure = CompletableDeferred<Throwable?>()
        backgroundScope.launch {
            failure.complete(runCatching { s.write(0x3001, 0, ByteArray(21) { it.toByte() }) }.exceptionOrNull())
        }
        runCurrent()
        assertTrue(old.paused.isCompleted, "block write did not reach the barrier")
        assertTrue(old.received.any { it.u8(0) == 0xC6 }, "block start was not sent")

        // 斷線：對端收掉連線，Session 自己重連成新的一代
        old.endStream()
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertTrue(s.generation != gen, "disconnect did not advance the generation")
        assertFalse(s.calibrationEnabled)
        advanceTimeBy(3_500)   // 重連
        runCurrent()
        assertTrue(s.generation > gen)

        // 放行卡住的那一幀：剩下的交易只能失敗，不能改送新連線
        old.resume()
        runCurrent()
        advanceTimeBy(2_500)   // 等寫入逾時、清理、重連武裝
        runCurrent()

        val err = failure.await()
        assertNotNull(err, "write must fail after the connection changed")
        assertFalse(s.calibrationEnabled, "calibration must stay revoked after the reconnect")
        assertFalse(s.gate.blockOpen, "the gate kept the unfinished block open")
        assertTrue(new.received.none { it.isCalibrationFrame(0x3001) }, "calibration frames reached the new connection")
        assertTrue(old.closeCount >= 1, "the old connection was not closed")
        assertEquals(0, new.closeCount, "cleanup touched the new connection")
        assertEquals(false, s.enableCalibration(setOf(0x3001), null, gen), "old generation must not re-enable calibration")
    }

    @Test
    fun writeAfterDisableIsRefused() = runTest {
        val t = table()
        val link = ScriptedLink(backgroundScope, t)
        val s = newSession(ArrayDeque(listOf(link)), t)
        arm(s)
        assertTrue(s.enableCalibration(setOf(0x3001), null, s.generation))
        s.disableCalibration()
        assertFalse(s.calibrationEnabled)
        assertFailsWith<WriteGate.Refused> { s.write(0x3001, 0, byteArrayOf(1, 2)) }
        assertEquals(0, s.gate.refusals, "the refusal happens before the gate, nothing was sent")
        assertTrue(link.received.none { it.isCalibrationFrame(0x3001) })
    }

    @Test
    fun nextBlockWaitsForTheEcuAcknowledgement() = runTest {
        val t = table()
        val link = ScriptedLink(backgroundScope, t, blockSize = 2)
        link.holdFirstAck = true
        val s = newSession(ArrayDeque(listOf(link)), t)
        arm(s)
        assertTrue(s.enableCalibration(setOf(0x3001), null, s.generation))

        val failure = CompletableDeferred<Throwable?>()
        backgroundScope.launch {
            failure.complete(runCatching { s.write(0x3001, 0, ByteArray(21) { it.toByte() }) }.exceptionOrNull())
        }
        runCurrent()
        assertTrue(link.ackReached.isCompleted, "the first block was not sent")
        assertEquals(2, link.segments, "a segment went out before the ECU confirmed the first block")
        assertFalse(failure.isCompleted)

        link.releaseBlockAck()
        runCurrent()
        runCurrent()
        assertTrue(failure.isCompleted, "the write did not finish after the acknowledgement")
        assertNull(failure.await(), "the write failed")
        assertFalse(s.gate.blockOpen, "the gate kept the finished block open")
        assertEquals(3, link.segments)
        assertEquals(0, link.earlySegments, "a segment was sent before the block acknowledgement")
        assertEquals(0, s.gate.refusals)
    }

    /** 連線串流中，功能頁連著要兩組通道（監看周期之前）：兩組都要生效，後一次不能蓋掉前一次。 */
    @Test
    fun channelRequestsDuringStreamingAccumulate() = runTest {
        val t = table()
        val s = newSession(ArrayDeque(listOf(ScriptedLink(backgroundScope, t))), t)
        arm(s)
        s.requestMoreChannels(listOf(ChannelTable.Entry(55, 2)))
        s.requestMoreChannels(listOf(ChannelTable.Entry(34, 1)))
        assertEquals(listOf(26, 55, 34), s.requestedTable.entries.map { it.absid })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(26, 55, 34), s.table.entries.map { it.absid })
        // 生效之後再要一組：接在生效的表後面
        s.requestMoreChannels(listOf(ChannelTable.Entry(33, 1), ChannelTable.Entry(55, 2)))
        assertEquals(listOf(26, 55, 34, 33), s.requestedTable.entries.map { it.absid })
        for (i in 0 until 50) { if (s.table.entries.size == 4) break; advanceTimeBy(200); runCurrent() }
        assertEquals(listOf(26, 55, 34, 33), s.table.entries.map { it.absid }, "state=${s.state.value}")
        s.stop()
    }
}
