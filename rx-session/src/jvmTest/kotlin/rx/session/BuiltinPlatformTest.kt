package rx.session

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import rx.defs.BuiltinDefs
import rx.protocol.EcuPlatform
import rx.protocol.Rpc
import kotlin.test.Test
import kotlin.test.assertEquals

/** Built-in definitions follow the ECU platform detected on connection; no vendor file needed. */
class BuiltinPlatformTest {
    @Test fun tableFollowsDetectedPlatformBeforeArming() = runTest {
        val s3 = BuiltinDefs.forKey("S3")
        val x2 = BuiltinDefs.forKey("X2")
        val seen = ArrayList<EcuPlatform?>()
        val ecu = LogReplayTransport(backgroundScope, { _, _, _ -> 0L }, deviceType = EcuPlatform.MINI_XX.node,
            nowMs = { testScheduler.currentTime })
        val start = Presets.table(Presets.ALL_CHANNELS.copy(absids = Presets.allAbsids(s3)), s3)
        val want = Presets.table(Presets.ALL_CHANNELS.copy(absids = Presets.allAbsids(x2)), x2)
        val s = Session({ ecu }, start, 888888, backgroundScope, nanoTime = { testScheduler.currentTime * 1_000_000 },
            tableFor = { p -> seen += p; if (p?.controlFile == "X2") want else start })
        var rounds = 0
        s.addListener(object : SessionListener { override fun onRound(tNanos: Long, raw: LongArray) { if (raw.size == want.entries.size) rounds++ } })
        s.start()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(EcuPlatform.MINI_XX, s.platform.value)
        assertEquals(listOf<EcuPlatform?>(EcuPlatform.MINI_XX), seen)
        assertEquals(want.entries, s.table.entries)
        assertEquals(SessionState.Streaming, s.state.value)
        kotlin.test.assertTrue(rounds > 10, "rounds $rounds")
        s.stop()
    }

    @Test fun importedDefinitionsResolveTheFullTableByName() {
        val s3 = BuiltinDefs.forKey("S3")
        // An imported file without a default list: same names, shifted absids.
        val shifted = rx.defs.ChannelRegistry(null, s3.channels.map { it.copy(absid = it.absid + 1) })
        val all = Presets.allAbsids(shifted)
        val expected = Presets.XTUNE_151.absids.mapNotNull { s3.values[it] }.map { it.absid + 1 } + (1148 + 1)
        assertEquals(expected, all)
    }

    @Test fun fullTableAppendsSecondInjectorAfterXtuneChannels() {
        for (k in listOf("S3", "X2")) {
            val reg = BuiltinDefs.forKey(k)
            val all = Presets.allAbsids(reg)
            assertEquals(reg.defaults, all.dropLast(1), k)
            assertEquals("Cyl1s_Comn_FuelPW", reg.values[all.last()]?.name, k)
        }
    }

    private class Rig(val s: Session, val tx: MutableList<rx.protocol.Frame>)

    private fun kotlinx.coroutines.test.TestScope.rig(vararg nodes: Int): Rig {
        val ecus = nodes.map { LogReplayTransport(backgroundScope, { _, _, _ -> 0L }, deviceType = it, nowMs = { testScheduler.currentTime }) }
        var next = 0
        val defs = BuiltinDefs.forKey(if (EcuPlatform.ofNode(nodes[0])?.controlFile == "X2") "X2" else "S3")
        val table = Presets.table(Presets.ALL_CHANNELS.copy(absids = Presets.allAbsids(defs)), defs)
        val s = Session({ ecus[minOf(next++, ecus.lastIndex)] }, table, 888888, backgroundScope, nanoTime = { testScheduler.currentTime * 1_000_000 })
        val tx = java.util.Collections.synchronizedList(ArrayList<rx.protocol.Frame>())
        s.addListener(object : SessionListener {
            override fun onFrame(tNanos: Long, dir: Int, f: rx.protocol.Frame) { if (dir == rx.protocol.RxRaw.DIR_TX) tx += f }
        })
        return Rig(s, tx)
    }

    private fun idx(f: rx.protocol.Frame) = f.u8(1) or (f.u8(2) shl 8)
    private fun isDownload(f: rx.protocol.Frame) = (f.u8(0) ushr 5) == 1 || (f.u8(0) ushr 5) == 0

    @Test fun superXxDashboardUsesItsOwnAutoTuneObject() = runTest {
        val (s, tx) = rig(EcuPlatform.SUPER_XX.node).let { it.s to it.tx }
        s.start()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(EcuPlatform.SUPER_XX, s.platform.value)
        assertEquals(SessionState.Streaming, s.state.value)
        val rpc = rx.protocol.CanId.rpc(EcuPlatform.SUPER_XX.node)
        val before = tx.size
        assertEquals(Session.WriteResult.Ack, s.setMap(2))
        kotlin.test.assertTrue(tx.drop(before).any { it.canId == rpc && idx(it) == Rpc.MAP_SWITCH && it.u8(0) == 0x2F }, "MAP is sent")
        // 這顆 ECU 不回儀表物件：每顆鈕都先讀，讀被拒就照實回報、一筆都不寫
        for (fn in rx.protocol.DashFn.entries) {
            val mark = tx.size
            val r = s.setDash(fn, true)
            kotlin.test.assertTrue(r is Session.WriteResult.Aborted, "${fn.name}: $r")
            val sent = tx.drop(mark).filter { it.canId == rpc }
            assertEquals(listOf(s.rpc.dashIndex(fn)), sent.map { idx(it) }, fn.name)
            kotlin.test.assertTrue(sent.all { it.u8(0) == 0x40 }, "${fn.name}: read only")
        }
        val sent = tx.drop(before).filter { it.canId == rpc }
        kotlin.test.assertTrue(sent.any { idx(it) == rx.protocol.DashTable.AT_PRO }, "AT reads a1500")
        kotlin.test.assertTrue(sent.none { idx(it) == rx.protocol.DashFn.AT.index }, "a14 is not the Super XX auto-tune object")
        kotlin.test.assertTrue(sent.none { isDownload(it) && idx(it) != Rpc.MAP_SWITCH }, "no dashboard download after a refused read")
        assertEquals(0, s.gate.refusals.toInt())
        s.stop()
    }

    @Test fun dashboardControlsSendNothingOnUnverifiedPlatform() = runTest {
        for (p in listOf(EcuPlatform.SUPER_X_PRO_CYL4, EcuPlatform.SXS)) {
            val (s, tx) = rig(p.node).let { it.s to it.tx }
            s.start()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(p, s.platform.value)
            val rpc = rx.protocol.CanId.rpc(p.node)
            val before = tx.size
            kotlin.test.assertTrue(s.setMap(2) is Session.WriteResult.Unverified, p.label)
            for (fn in rx.protocol.DashFn.entries) {
                kotlin.test.assertTrue(s.setDash(fn, true) is Session.WriteResult.Unverified, "${p.label} ${fn.name}")
                assertEquals(null, s.readDash(fn), "${p.label} ${fn.name}")
            }
            // 沒驗證的平台：儀表相關一幀都不送，讀也不讀
            assertEquals(emptyList(), tx.drop(before).filter { it.canId == rpc }, p.label)
            assertEquals(0, s.gate.refusals.toInt())
            s.stop()
        }
    }

    /** 同一個 Session 換接到另一款 ECU：幀的目的地、AT 物件與閘門都跟著新平台走。 */
    @Test fun reconnectToAnotherPlatformSwitchesTheDashboardTable() = runTest {
        val ecus = listOf(
            LogReplayTransport(backgroundScope, { _, _, _ -> 0L }, deviceType = EcuPlatform.MINI_X.node, nowMs = { testScheduler.currentTime }),
            LogReplayTransport(backgroundScope, { _, _, _ -> 0L }, deviceType = EcuPlatform.SUPER_XX.node, nowMs = { testScheduler.currentTime }),
        )
        var next = 0
        val s3 = BuiltinDefs.forKey("S3")
        val table = Presets.table(Presets.ALL_CHANNELS.copy(absids = Presets.allAbsids(s3)), s3)
        val s = Session({ ecus[minOf(next++, ecus.lastIndex)] }, table, 888888, backgroundScope, nanoTime = { testScheduler.currentTime * 1_000_000 })
        val tx = java.util.Collections.synchronizedList(ArrayList<rx.protocol.Frame>())
        s.addListener(object : SessionListener {
            override fun onFrame(tNanos: Long, dir: Int, f: rx.protocol.Frame) { if (dir == rx.protocol.RxRaw.DIR_TX) tx += f }
        })
        s.start()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(EcuPlatform.MINI_X, s.platform.value)
        assertEquals(rx.protocol.DashFn.AT.index, s.rpc.dashIndex(rx.protocol.DashFn.AT))
        // 第一顆 ECU 停止回應 → 重連拿到第二顆
        ecus[0].rpcSilent = true; ecus[0].asleep = true
        advanceTimeBy(60_000); runCurrent()
        assertEquals(EcuPlatform.SUPER_XX, s.platform.value)
        assertEquals(EcuPlatform.SUPER_XX.node, s.node)
        assertEquals(rx.protocol.DashTable.AT_PRO, s.rpc.dashIndex(rx.protocol.DashFn.AT))
        kotlin.test.assertTrue(s.gate.dashVerified(rx.protocol.DashTable.AT_PRO))
        kotlin.test.assertFalse(s.gate.dashVerified(rx.protocol.DashFn.AT.index))
        val mark = tx.size
        s.setDash(rx.protocol.DashFn.AT, true)
        val sent = tx.drop(mark).filter { it.canId == rx.protocol.CanId.rpc(EcuPlatform.SUPER_XX.node) }
        assertEquals(listOf(rx.protocol.DashTable.AT_PRO), sent.map { idx(it) })
        kotlin.test.assertTrue(tx.drop(mark).none { it.canId == rx.protocol.CanId.rpc(EcuPlatform.MINI_X.node) }, "nothing to the old node")
        assertEquals(0, s.gate.refusals.toInt())
        s.stop()
    }

    /**
     * 包在連線外面的一層：[od] 裡的儀表物件讀寫由這裡回答，其他幀照常轉給 [inner]。
     * [od]：物件→值（null＝空物件）；不在表裡的照轉。[silent]：這些物件讀寫都不回（逾時）。
     */
    private class DashFixture(scope: kotlinx.coroutines.CoroutineScope, val inner: Transport, val node: Int) : Transport {
        val od = HashMap<Int, Int?>()
        val writes = ArrayList<Pair<Int, Int>>()
        @Volatile var silent = false
        private val inbox = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        private var emptySeg: Int? = null
        init { scope.launch { while (true) { val b = inner.receive() ?: break; inbox.send(b) } } }
        private fun reply(d: ByteArray) { inbox.trySend(rx.protocol.Frame(rx.protocol.CanId.rpcReply(node), d).encode()) }
        override suspend fun send(bytes: ByteArray) {
            for (f in rx.protocol.FrameParser.parseAll(bytes).first) {
                if (f.canId != rx.protocol.CanId.rpc(node) || f.dlc < 4) { inner.send(f.encode()); continue }
                val idx = f.u8(1) or (f.u8(2) shl 8)
                val mine = idx in od || (f.u8(0) == 0x60 && emptySeg != null)
                if (!mine) { inner.send(f.encode()); continue }
                if (silent) continue
                val head = byteArrayOf(0, f.u8(1).toByte(), f.u8(2).toByte(), f.u8(3).toByte())
                when {
                    f.u8(0) == 0x60 -> { emptySeg = null; reply(byteArrayOf(0x0F, 0, 0, 0, 0, 0, 0, 0)) }
                    f.u8(0) == 0x40 -> when (val v = od[idx]) {
                        null -> { emptySeg = idx; reply(head.also { it[0] = 0x41 } + byteArrayOf(0, 0, 0, 0)) }
                        else -> reply(head.also { it[0] = 0x4F } + byteArrayOf(v.toByte(), 0, 0, 0))
                    }
                    (f.u8(0) ushr 5) == 1 -> { writes += idx to f.u8(4); od[idx] = f.u8(4); reply(head.also { it[0] = 0x60 } + byteArrayOf(0, 0, 0, 0)) }
                }
            }
        }
        override suspend fun receive(): ByteArray? = inbox.receiveCatching().getOrNull()
        override fun close() { inner.close() }
    }

    @Test fun superXxAutoTuneAndEBoostWritesAndFailedReads() = runTest {
        val node = EcuPlatform.SUPER_XX.node
        val ecu = LogReplayTransport(backgroundScope, { _, _, _ -> 0L }, deviceType = node, nowMs = { testScheduler.currentTime })
        val fx = DashFixture(backgroundScope, ecu, node)
        val at = rx.protocol.DashTable.AT_PRO; val eb = rx.protocol.DashFn.EB.index
        fx.od[at] = 0; fx.od[eb] = 0xA4
        val x2 = BuiltinDefs.forKey("X2")
        val table = Presets.table(Presets.ALL_CHANNELS.copy(absids = Presets.allAbsids(x2)), x2)
        val s = Session({ fx }, table, 888888, backgroundScope, nanoTime = { testScheduler.currentTime * 1_000_000 })
        val tx = java.util.Collections.synchronizedList(ArrayList<rx.protocol.Frame>())
        val ev = java.util.Collections.synchronizedList(ArrayList<String>())
        s.addListener(object : SessionListener {
            override fun onFrame(tNanos: Long, dir: Int, f: rx.protocol.Frame) { if (dir == rx.protocol.RxRaw.DIR_TX) tx += f }
            override fun onEvent(tNanos: Long, text: String) { ev += text }
        })
        s.start()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(EcuPlatform.SUPER_XX, s.platform.value)
        assertEquals(SessionState.Streaming, s.state.value, ev.toString())
        val rpc = rx.protocol.CanId.rpc(node)
        fun downloads(from: Int) = tx.drop(from).filter { it.canId == rpc && isDownload(it) }

        var mark = tx.size
        assertEquals(Session.WriteResult.Ack, s.setDash(rx.protocol.DashFn.AT, true))
        assertEquals(true, s.readDash(rx.protocol.DashFn.AT))
        assertEquals(Session.WriteResult.Ack, s.setDash(rx.protocol.DashFn.AT, false))
        assertEquals(listOf(at to 1, at to 0), fx.writes)
        kotlin.test.assertTrue(downloads(mark).all { it.u8(0) == 0x2F && it.u8(3) == 0 && idx(it) == at }, "AT: 0x2F to a1500 sub 0")
        kotlin.test.assertTrue(tx.none { it.canId == rpc && idx(it) == rx.protocol.DashFn.AT.index }, "a14 never touched")

        fx.writes.clear()
        assertEquals(Session.WriteResult.Ack, s.setDash(rx.protocol.DashFn.EB, true))
        assertEquals(Session.WriteResult.Ack, s.setDash(rx.protocol.DashFn.EB, false))
        assertEquals(listOf(eb to 0xA5, eb to 0xA4), fx.writes, "EB keeps every bit except bit0")

        // 讀不到就不寫：被拒、逾時、空物件
        fx.writes.clear()
        fx.od.remove(at)
        mark = tx.size
        kotlin.test.assertTrue(s.setDash(rx.protocol.DashFn.AT, true) is Session.WriteResult.Aborted)
        fx.od[at] = 0; fx.silent = true
        kotlin.test.assertTrue(s.setDash(rx.protocol.DashFn.AT, true) is Session.WriteResult.Error)
        fx.silent = false; fx.od[at] = null
        kotlin.test.assertTrue(s.setDash(rx.protocol.DashFn.AT, true) is Session.WriteResult.Error)
        assertEquals(emptyList(), downloads(mark))
        assertEquals(emptyList(), fx.writes)
        assertEquals(0, s.gate.refusals.toInt())
        s.stop()
    }
}
