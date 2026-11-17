package rx.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rx.protocol.BroadcastReassembler
import rx.protocol.CanId
import rx.protocol.ChannelTable
import rx.protocol.DashFn
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.FrameParser
import rx.protocol.Rpc
import rx.protocol.RpcReply
import rx.protocol.RxRaw
import rx.protocol.Sdo
import rx.protocol.SdoAbortException
import rx.protocol.SdoDownload
import rx.protocol.SdoPort
import rx.protocol.SdoUpload
import rx.protocol.IOException
import rx.protocol.hex
import rx.protocol.hexLower
import rx.protocol.monotonicNanos
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

sealed interface SessionState {
    data object Idle : SessionState
    data class Connecting(val attempt: Int) : SessionState
    data class Arming(val step: String) : SessionState
    data object Streaming : SessionState
    data class NoBroadcast(val hint: String) : SessionState
    data class Reconnecting(val reason: String) : SessionState
    data object Stopped : SessionState
}

interface SessionListener {
    fun onFrame(tNanos: Long, dir: Int, frame: Frame) {}
    fun onRound(tNanos: Long, raw: LongArray) {}
    fun onEvent(tNanos: Long, text: String) {}
    /** 串流中換了頻道表（[Session.requestTable]）：之後的 [onRound] 是新表的欄位。在新表寫進 ECU 之前呼叫。 */
    fun onTableChanged(table: ChannelTable) {}
}

/**
 * 一條 ECU 連線的完整生命週期（唯讀＋W0）。
 *
 * 0x681{00} → 0x682{D1×3,C1,B1} → 0x680{9A} → 登入 → 頻道表 → 0x1805=64。
 * 每條寫入等 ACK 才送下一條（實車：ECU 只能單發 in-flight）。
 *
 * 看門狗：
 * - 2.5s 沒有監控分片 → 偵測停流並重拉；不能單憑停流判定引擎／ECU 重啟
 * - 連續 3 輪 payload 長度不符 → 頻道表遺失 → 重寫表
 * - 6s 沒有任何幀 → 判定斷線 → 重連
 */
private const val LOGIN_NO_REPLY = "no reply"

class Session(
    private val connect: suspend () -> Transport,
    table: ChannelTable,
    private val password: Int?,
    private val scope: CoroutineScope,
    val deviceType: Int = 1,
    private val nanoTime: () -> Long = ::monotonicNanos,
    private val timing: Timing = Timing(),
    /**
     * 判別平台後（每次連線、寫頻道表前）呼叫：回傳這個平台要用的頻道表。
     * 內建定義依平台換名錄時用；null＝一律用建構時的表。
     */
    private val tableFor: ((EcuPlatform?) -> ChannelTable)? = null,
    /**
     * 每次武裝完成且已登入時，要從 ECU 讀回開關狀態的儀表鈕（一顆一次 SDO 讀取，不輪詢）。
     * 即時通道不一定反映開關：實車 TC 開著、靜止時 TCStatus.TCEnable 仍是 0。
     */
    private val dashSwitches: Set<DashFn> = emptySet(),
) : SdoPort {
    /** 目前的頻道表；[tableFor] 可能在連線判別平台後換掉。 */
    @Volatile var table: ChannelTable = table; private set

    @Volatile private var pendingTable: ChannelTable? = null

    /**
     * 串流中換頻道表（下一個監看周期生效：先 [SessionListener.onTableChanged]，再重寫頻道表、重新武裝）。
     * 沒連線時直接換，下次武裝就用它。
     */
    fun requestTable(next: ChannelTable) = kotlinx.atomicfu.locks.synchronized(tableLock) { requestLocked(next) }

    private fun requestLocked(next: ChannelTable) {
        if (transport == null) { table = next; return }
        pendingTable = next
    }

    /** [pendingTable]、[table] 的請求與生效在同一把鎖下：監看周期取走請求的同時又來一個請求，不會被蓋掉。 */
    private val tableLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** 接下來要用的頻道表：還沒生效的請求，沒有就是目前的。 */
    val requestedTable: ChannelTable get() = kotlinx.atomicfu.locks.synchronized(tableLock) { pendingTable ?: table }

    /**
     * 在 [requestedTable] 後面加上 [extra] 裡還沒有的通道（放不下的略過）並請求換表；回傳實際加進去的。
     * 兩次請求在同一個監看周期之前連著來，第二次會接在第一次後面，不會把第一次蓋掉。
     */
    fun requestMoreChannels(extra: List<ChannelTable.Entry>): List<ChannelTable.Entry> = kotlinx.atomicfu.locks.synchronized(tableLock) {
        val base = pendingTable ?: table
        val have = base.entries.map { it.absid }.toHashSet()
        val add = extra.filter { have.add(it.absid) }.take((ChannelTable.MAX_ENTRIES - base.entries.size).coerceAtLeast(0))
        if (add.isNotEmpty()) requestLocked(ChannelTable(base.entries + add))
        add
    }

    data class Timing(
        val stepMs: Long = 80,
        val sessionOpenMs: Long = 200,
        val ackTimeoutMs: Long = 300,
        val ackRetries: Int = 2,
        val firstRoundTimeoutMs: Long = 3000,
        val broadcastLossMs: Long = 2500,
        val linkDeadMs: Long = 6000,
        val heartbeatMs: Long = 1000,
        val rearmIntervalMs: Long = 8000,
        val reconnectMs: Long = 3000,
        val superviseTickMs: Long = 250,
        /** 登入後送 NMT Start（XTune 同序）。10-16 凌晨排查時曾關閉對照（非 ACK 失敗根因）。 */
        val sendNmtStart: Boolean = true,
        /** 調校寫入每一步等 ECU 回應的時間（同 XB 的 EcuLink）。 */
        val calWriteTimeoutMs: Long = 1500,
    )

    class Stats {
        @Volatile var rxFrames = 0L
        @Volatile var txFrames = 0L
        @Volatile var rounds = 0L
        @Volatile var incompleteRounds = 0L
        @Volatile var ackMisses = 0L
        @Volatile var rearms = 0L
        @Volatile var reconnects = 0L
        /** 實測整輪刷新率（EMA）。 */
        @Volatile var roundHz = 0.0
        @Volatile var lastLogin: String? = null
        /** 目前這條連線的解析統計（重連時換新）。 */
        @Volatile var parser = FrameParser.Stats()
    }

    /**
     * 目前 ECU 的 CANopen node（＝XTune deviceType）。連線時從 ECU 心跳 0x700|node 自動判別（[EcuPlatform]），
     * 判不出來就沿用 [deviceType]（預設 1＝Mini X，維持舊行為）。RPC、串流、寫入閘門都跟著它走。
     */
    @Volatile var node: Int = deviceType; private set
    @Volatile var rpc = Rpc(deviceType); private set
    @Volatile var gate = WriteGate(deviceType); private set
    private val _platform = MutableStateFlow<EcuPlatform?>(EcuPlatform.ofNode(deviceType))
    /** 判別到的平台（未知 node 為 null）。 */
    val platform: StateFlow<EcuPlatform?> = _platform
    @Volatile private var platformSeen = false

    private fun adoptNode(n: Int) {
        if (n == node) { platformSeen = true; return }
        val refused = gate.refusals
        // 換了 ECU 平台：調校授權不帶過去（新閘門預設關），要使用者重新開始調校
        newGeneration("ECU platform changed")
        node = n; rpc = Rpc(n); gate = WriteGate(n, refused)
        _platform.value = EcuPlatform.ofNode(n)
        platformSeen = true
        event("ECU platform: ${_platform.value?.label ?: "node $n"} (node 0x${n.hex(2)})")
    }
    val stats = Stats()
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state

    /** 這次連線是否登入成功（0x6A0 `89 00`）。斷線、重新武裝時歸 false。儀表鈕除 MAP 外都依此決定能不能送。 */
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    /** 儀表鈕在 ECU 上的開關（物件字典讀回值）。沒讀到的不在表裡；換一條 TCP 連線就清空重讀。 */
    private val _dashSwitch = MutableStateFlow<Map<DashFn, Boolean>>(emptyMap())
    val dashSwitch: StateFlow<Map<DashFn, Boolean>> = _dashSwitch
    private fun DashFn.isOn(byte: Int) = if (bit0) byte and 1 == 1 else byte == 1
    private fun noteSwitch(fn: DashFn, byte: Int) = _dashSwitch.update { it + (fn to fn.isOn(byte)) }
    // 寫時複製（CopyOnWriteArrayList 的跨平台替代）：遍歷拿到的永遠是一份不變的快照
    private val listenerList = MutableStateFlow<List<SessionListener>>(emptyList())
    private val listeners: List<SessionListener> get() = listenerList.value

    private var job: Job? = null
    private val sendLock = Mutex()
    @Volatile private var transport: Transport? = null
    private var replies = Channel<Frame>(Channel.UNLIMITED)
    @Volatile private var lastAnyRx = 0L
    @Volatile private var lastFragment = 0L
    @Volatile private var lastRound = 0L
    @Volatile private var badLengthStreak = 0
    @Volatile private var streaming = false

    fun addListener(l: SessionListener) { listenerList.update { it + l } }
    fun removeListener(l: SessionListener) { listenerList.update { it - l } }

    private fun nowMs() = nanoTime() / 1_000_000

    fun start() {
        check(job == null) { "already started" }
        job = scope.launch { runLoop() }
    }

    suspend fun stop() {
        revokeCalibration("session stopped")
        job?.cancelAndJoin()
        job = null
        kotlinx.atomicfu.locks.synchronized(calLock) { transport }?.close()
        kotlinx.atomicfu.locks.synchronized(calLock) { transport = null }
        streaming = false
        _loggedIn.value = false
        _dashSwitch.value = emptyMap()
        _state.value = SessionState.Stopped
        event("Session ended")
    }

    private fun event(text: String) {
        val t = nanoTime()
        listeners.forEach { it.onEvent(t, text) }
    }

    // ---------------- 連線迴圈 ----------------

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            attempt++
            _loggedIn.value = false; _state.value = SessionState.Connecting(attempt)
            val t = try {
                connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("Connect failed: ${e.message ?: e::class.simpleName}")
                _state.value = SessionState.Reconnecting("Connect failed, retrying in ${timing.reconnectMs / 1000} s")
                delay(timing.reconnectMs)
                continue
            }
            newGeneration(null)
            event("TCP connected")
            platformSeen = false
            _dashSwitch.value = emptyMap()
            try {
                coroutineScope { runConnected(t) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("Connection lost: ${e.message ?: e::class.simpleName}")
            } finally {
                // 斷線：調校授權立刻收回；重連後是不是同一台、ECU 有沒有重開都不知道，要重新讀回
                newGeneration("connection lost", clearTransport = true)
                t.close()
                streaming = false
            }
            stats.reconnects++
            _state.value = SessionState.Reconnecting("Disconnected, reconnecting in ${timing.reconnectMs / 1000} s")
            delay(timing.reconnectMs)
        }
    }

    private suspend fun CoroutineScope.runConnected(t: Transport) {
        kotlinx.atomicfu.locks.synchronized(calLock) { transport = t }
        replies = Channel(Channel.UNLIMITED)
        val now0 = nowMs()
        lastAnyRx = now0; lastFragment = 0; lastRound = 0; badLengthStreak = 0; streaming = false

        var reassembler = BroadcastReassembler(table.payloadLength) { r -> onRound(r) }
        val parser = FrameParser { f -> onRx(f, reassembler) }
        stats.parser = parser.stats
        var readerDone = false
        val reader = launch {
            while (true) {
                val chunk = t.receive() ?: break
                parser.feed(chunk)
            }
            readerDone = true
        }
        val heartbeat = launch {
            while (true) { send(rpc.heartbeat()); delay(timing.heartbeatMs) }
        }

        var lastArm = nowMs()
        // 2026-10-16 實車：ISS 停車時 ECU RPC 全無回應（登入、寫入 0 幀），要等橋端 reset TCP 才恢復（多等約 40 s）。
        // 連續 2 次登入無回應就主動斷線重連。
        var silentLogins = 0
        suspend fun armChecked(withTable: Boolean): Boolean {
            val ok = arm(withTable)
            if (password != null) {
                silentLogins = if (stats.lastLogin == LOGIN_NO_REPLY) silentLogins + 1 else 0
                if (silentLogins >= 2) throw IOException("$silentLogins logins without reply, reconnecting")
            }
            return ok
        }
        // 平台判別：ECU 開機／常態心跳 0x700|node。最多等 1.5 s，沒聽到就沿用 deviceType（MX 舊行為）。
        if (!platformSeen) withTimeoutOrNull(1500) { while (!platformSeen) delay(50) }
        if (!platformSeen) event("No ECU heartbeat yet; assuming node 0x${node.hex(2)}")
        tableFor?.invoke(_platform.value)?.let { next ->
            if (kotlinx.atomicfu.locks.synchronized(tableLock) { (next.entries != table.entries).also { if (it) table = next } }) {
                reassembler = BroadcastReassembler(next.payloadLength) { r -> onRound(r) }
                event("Channel table for ${_platform.value?.label ?: "node 0x${node.hex(2)}"}: ${next.entries.size} ch")
            }
        }
        armChecked(withTable = true)
        try {
            while (true) {
                delay(timing.superviseTickMs)
                // 串流中換頻道表（功能專頁要的即時變數）：取走請求並生效在同一把鎖裡；換解碼、通知、重寫頻道表在鎖外
                val changed = kotlinx.atomicfu.locks.synchronized(tableLock) {
                    pendingTable?.also { pendingTable = null }?.takeIf { it.entries != table.entries }?.also { table = it }
                }
                changed?.let { next ->
                    reassembler = BroadcastReassembler(next.payloadLength) { r -> onRound(r) }
                    listeners.forEach { it.onTableChanged(next) }
                    event("Channel table changed: ${next.entries.size} ch")
                    stats.rearms++; lastArm = nowMs()
                    armChecked(withTable = true)
                }
                val n = nowMs()
                if (readerDone) throw IOException("ECU closed the connection")
                if (n - lastAnyRx > timing.linkDeadMs) throw IOException("No frames for ${timing.linkDeadMs / 1000} s")
                when {
                    streaming && badLengthStreak >= 3 -> {
                        event("3 rounds with wrong length, channel table lost, rewriting")
                        stats.rearms++; lastArm = n
                        armChecked(withTable = true)
                    }
                    streaming && n - lastFragment > timing.broadcastLossMs -> {
                        event("0x${CanId.broadcast(node).hex(3)} stream lost >${timing.broadcastLossMs} ms; cause unknown, re-arming")
                        stats.rearms++; lastArm = n
                        if (!armChecked(withTable = false)) armChecked(withTable = true)
                    }
                    !streaming && n - lastArm > timing.rearmIntervalMs -> {
                        stats.rearms++; lastArm = n
                        armChecked(withTable = true)
                    }
                }
            }
        } finally {
            heartbeat.cancel(); reader.cancel()
        }
    }

    // ---------------- 收 ----------------

    private fun onRx(f: Frame, reassembler: BroadcastReassembler) {
        val t = nanoTime()
        lastAnyRx = t / 1_000_000
        stats.rxFrames++
        listeners.forEach { it.onFrame(t, RxRaw.DIR_RX, f) }
        EcuPlatform.ofHeartbeat(f.canId)?.let { p ->
            // 只在武裝前採用（避免串流中途換 node）；之後同 node 的心跳就是一般心跳
            if (!platformSeen) adoptNode(p.node)
            if (p.node == node) return
        }
        when (f.canId) {
            CanId.broadcast(node) -> { lastFragment = lastAnyRx; reassembler.canId = f.canId; reassembler.feed(f, t) }
            CanId.HEARTBEAT_APP -> Unit
            else -> replies.trySend(f)
        }
    }

    private fun onRound(r: BroadcastReassembler.Round) {
        val raw = if (r.complete) table.unpack(r.payload) else null
        if (raw == null) {
            stats.incompleteRounds++
            if (r.payload.size != table.payloadLength) badLengthStreak++
            return
        }
        badLengthStreak = 0
        val nowMs = r.tLast / 1_000_000
        if (lastRound > 0) {
            val dt = (nowMs - lastRound).coerceAtLeast(1)
            val hz = 1000.0 / dt
            stats.roundHz = if (stats.roundHz == 0.0) hz else stats.roundHz * 0.9 + hz * 0.1
        }
        lastRound = nowMs
        stats.rounds++
        if (!streaming) {
            streaming = true
            _state.value = SessionState.Streaming
        }
        listeners.forEach { it.onRound(r.tLast, raw) }
    }

    // ---------------- 送 ----------------

    /**
     * 所有送出的唯一出口：先過寫入閘門。
     * 心跳和 arm 在不同協程送，要串行化：不然 TCP 寫入可能交錯，離線重播 的 FrameParser 也會被兩條執行緒同時餵而炸掉。
     */
    private suspend fun send(f: Frame) = sendLock.withLock {
        gate.check(f)
        val t = transport ?: throw IOException("not connected")
        t.send(f.encode())
        stats.txFrames++
        val ts = nanoTime()
        val logged = ShareExport.redactSecrets(f) // 錄製／同步不保存登入密碼
        listeners.forEach { it.onFrame(ts, RxRaw.DIR_TX, logged) }
    }

    /** RPC 單發 in-flight：arm 與讀物件互斥。 */
    private val rpcLock = Mutex()

    /**
     * 讀 ECU 物件字典一個項目（SDO upload，唯讀）。只由使用者手動觸發，不自動輪詢。
     * 表格位址＝表 ID＋0x3000（XTune 讀表走 block）；0x1008 是裝置名稱。
     */
    suspend fun readObject(index: Int, sub: Int, block: Boolean = true, timeoutMs: Long = 1500): Result<Pair<ByteArray, String>> =
        rpcLock.withLock { readObjectLocked(index, sub, block, timeoutMs) }

    /** [readObject] 的本體，呼叫者須已持有 [rpcLock]。 */
    private suspend fun readObjectLocked(index: Int, sub: Int, block: Boolean = true, timeoutMs: Long = 1500): Result<Pair<ByteArray, String>> =
        run {
            if (transport == null) return@run Result.failure(IOException("not connected"))
            val up = SdoUpload(rpc.rpcId, index, sub, block)
            drainReplies()
            send(up.start())
            while (true) {
                val f = awaitReply(timeoutMs) { it.canId == CanId.rpcReply(node) }
                    ?: return@run Result.failure<Pair<ByteArray, String>>(IOException("timeout ($timeoutMs ms without reply)")).also {
                        if (block) runCatching { send(Sdo.abortFrame(rpc.rpcId, index, sub, 0x05040000L)) }
                    }
                when (val st = up.onReply(f)) {
                    is SdoUpload.Step.Send -> st.frames.forEach { send(it) }
                    SdoUpload.Step.Wait -> Unit
                    is SdoUpload.Step.Done -> {
                        up.pendingEnd?.let { send(it) }
                        event("Read 0x${index.hex(4)}/$sub: ${st.data.size} B (${st.note})")
                        return@run Result.success(st.data to st.note)
                    }
                    is SdoUpload.Step.Failed -> {
                        event("Read 0x${index.hex(4)}/$sub failed: ${st.reason}")
                        return@run Result.failure(st.abort?.let { SdoAbortException(it) } ?: IOException(st.reason))
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException())
        }

    /**
     * 儀表運行期控制寫入（W0'，2026-10-20）。只放 MAP 與 eBoost 兩項，經 [WriteGate] 二次把關。
     * 回傳 ACK/Abort/逾時；abort 原文如實顯示（06010003 需登入、06010004 需 license），不繞過。
     */
    sealed interface WriteResult {
        data object Ack : WriteResult
        data class Aborted(val abort: Sdo.Abort) : WriteResult
        data class Error(val reason: String) : WriteResult
        data class Unverified(val platform: String) : WriteResult
    }

    /** 切 MAP 檔位（1-3）。免登入。 */
    suspend fun setMap(mapNo: Int): WriteResult = rpcLock.withLock {
        if (transport == null) return@withLock WriteResult.Error("not connected")
        unverifiedDash("MAP", Rpc.MAP_SWITCH)?.let { return@withLock it }
        sendWriteLocked(rpc.mapSwitch(mapNo)).also { event("MAP→$mapNo: ${it.describe()}") }
    }

    /**
     * 儀表多功能鈕（AT／Pit／LC／TC／EB）。一律**先讀現值、再決定寫什麼**，讀不到就不寫。
     * - AT：開關看 bit0；TC／Pit／LC：看值＝1。這四顆照 XTune 整 byte 寫 0/1。
     * - 物件依平台而定（Super XX 的 AT 是 a1500），見 [rx.protocol.DashTable]。
     * [on]=null＝依 ECU 讀回的狀態反轉（不拿畫面上的即時通道推，避免狀態對不上時切反）。
     */
    suspend fun setDash(fn: DashFn, on: Boolean?): WriteResult = rpcLock.withLock {
        if (transport == null) return@withLock WriteResult.Error("not connected")
        unverifiedDash(fn.name, rpc.dashIndex(fn))?.let { return@withLock it }
        val cur = readObjectLocked(rpc.dashIndex(fn), 0, block = false).getOrElse { e ->
            val r = if (e is SdoAbortException) WriteResult.Aborted(e.abort) else WriteResult.Error("read before write failed: ${e.message}")
            event("${fn.name}: read refused, nothing written (${r.describe()})")
            return@withLock r
        }
        val old = cur.first.firstOrNull()?.toInt()?.and(0xFF)
            ?: return@withLock WriteResult.Error("read returned 0 bytes, nothing written")
        noteSwitch(fn, old)
        val isOn = fn.isOn(old)
        val want = on ?: !isOn
        val next = when {
            fn == DashFn.EB -> if (want) old or 0x01 else old and 0xFE
            else -> if (want) 1 else 0
        }
        if (next == old) return@withLock WriteResult.Ack.also { event("${fn.name} already ${if (want) "on" else "off"} (0x${old.hex(2)})") }
        sendWriteLocked(rpc.dashWrite(fn, next)).also {
            event("${fn.name} ${if (want) "on" else "off"} (0x${old.hex(2)}→0x${next.hex(2)}): ${it.describe()}")
        }
    }

    /** 平台未驗證就不讀也不寫，直接回 [WriteResult.Unverified]。 */
    private fun unverifiedDash(what: String, index: Int): WriteResult? {
        if (gate.dashVerified(index)) return null
        val p = _platform.value?.label ?: "node 0x${node.hex(2)}"
        event("$what: dashboard control not verified on $p, nothing sent")
        return WriteResult.Unverified(p)
    }

    /** 讀 ECU 上某顆儀表鈕目前是開或關（讀不到、或平台未驗證回 null；未驗證時不送任何幀）。 */
    suspend fun readDash(fn: DashFn): Boolean? = rpcLock.withLock {
        if (!gate.dashVerified(rpc.dashIndex(fn))) return@withLock null
        val b = readObjectLocked(rpc.dashIndex(fn), 0, block = false).getOrNull()?.first?.firstOrNull()?.toInt()?.and(0xFF) ?: return@withLock null
        noteSwitch(fn, b)
        fn.isOn(b)
    }

    private fun WriteResult.describe() = when (this) {
        WriteResult.Ack -> "ACK"
        is WriteResult.Aborted -> abort.toString()
        is WriteResult.Error -> "error: $reason"
        is WriteResult.Unverified -> "not verified on $platform"
    }

    /** 送一個已過閘門的 expedited download，等 WriteAck(0x60) 或 Abort(0x80)。須在 rpcLock 內呼叫。 */
    private suspend fun sendWriteLocked(f: Frame, op: CalOp? = null): WriteResult {
        val addr = f.u8(1) or (f.u8(2) shl 8); val sub = f.u8(3)
        drainReplies()
        if (op != null) sendCal(f, op) else send(f)
        val reply = awaitReply(timing.ackTimeoutMs) {
            when (val r = RpcReply.classify(it)) {
                is RpcReply.WriteAck -> r.addr == addr && r.sub == sub
                is RpcReply.Nack -> r.abort.index == addr && r.abort.sub == sub
                else -> false
            }
        } ?: return WriteResult.Error("timeout (${timing.ackTimeoutMs} ms without reply)")
        return when (val r = RpcReply.classify(reply)) {
            is RpcReply.WriteAck -> WriteResult.Ack
            is RpcReply.Nack -> WriteResult.Aborted(r.abort)
            else -> WriteResult.Error("unexpected reply ${reply.data.hexLower()}")
        }
    }

    // ---------------- 即時調校 ----------------

    /** 調校模式准寫的物件（定義包裡讀得到、已備份的表，0x3000＋表號）；null＝關閉。 */
    @Volatile private var calibrationObjects: Set<Int>? = null
    /** 調校模式下放行的命令幀（[WriteGate.commands]）。 */
    @Volatile private var calibrationCommands: ((Frame) -> Boolean)? = null

    /**
     * 連線代號：每次 TCP 連上、斷線、換平台都會變。開調校模式時要帶開始讀回時的代號，
     * 代號不同（中間斷過線或換了 ECU）就不開。
     */
    @Volatile var generation = 0L; private set

    /**
     * 開調校模式。開了以後 [write] 才會送，而且只送 [objects] 裡的物件（[WriteGate.calibration]）。
     * [expectGeneration] 必須等於目前的 [generation]，而且要連著、登入成功；否則不開，回 false。
     * 斷線、換平台、[stop] 都會自動關掉，不會帶到下一條連線。
     */
    fun enableCalibration(objects: Set<Int>, commands: ((Frame) -> Boolean)?, expectGeneration: Long): Boolean {
        // 檢查與開啟要跟斷線／換平台的收回互斥：不然檢查完、開之前斷了線，授權會留到下一條連線
        val ok = kotlinx.atomicfu.locks.synchronized(calLock) {
            if (expectGeneration != generation || transport == null || !_loggedIn.value) false
            else {
                calibrationObjects = objects.toSet()
                calibrationCommands = commands
                gate.calibration = calibrationObjects
                gate.commands = calibrationCommands
                true
            }
        }
        if (!ok) {
            event("Calibration writes not enabled: the connection changed")
            return false
        }
        event("Calibration writes on (${objects.size} objects)")
        return true
    }

    /** 關調校模式。 */
    fun disableCalibration() = revokeCalibration("turned off")

    private val calLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** 連線換了（連上、斷線、換平台）：代號加一，同時收回調校授權（兩件事一起做，[enableCalibration] 不會插在中間）。 */
    private fun newGeneration(why: String?, clearTransport: Boolean = false) {
        kotlinx.atomicfu.locks.synchronized(calLock) {
            generation++
            if (clearTransport) transport = null
            if (why != null) revokeLocked(why)
        }
    }

    /** 一筆調校操作開始時綁定的連線：之後每一幀只送到這條連線，代號變了就不送。 */
    private class CalOp(val generation: Long, val transport: Transport)

    /** 調校模式開著、連著、登入成功時，綁定目前的連線；否則回 null。 */
    private fun captureCal(): CalOp? = kotlinx.atomicfu.locks.synchronized(calLock) {
        val t = transport
        if (t == null || calibrationObjects == null || !_loggedIn.value) null else CalOp(generation, t)
    }

    /**
     * 調校操作的送出口：檢查授權、過閘門、選連線三件事跟斷線／換平台／收回互斥，只送到 [op] 綁定的那條連線。
     * [cleanup]＝true（結束未完成的區塊寫入用）時不要求授權還開著，但連線換了就不送。
     */
    private suspend fun sendCal(f: Frame, op: CalOp, cleanup: Boolean = false) = sendLock.withLock {
        kotlinx.atomicfu.locks.synchronized(calLock) {
            if (generation != op.generation || transport !== op.transport) throw IOException("the connection changed")
            if (!cleanup && calibrationObjects == null) throw WriteGate.Refused("calibration writes are off")
            gate.check(f)
        }
        op.transport.send(f.encode())
        stats.txFrames++
        val ts = nanoTime()
        listeners.forEach { it.onFrame(ts, RxRaw.DIR_TX, ShareExport.redactSecrets(f)) }
    }

    private fun revokeCalibration(why: String) = kotlinx.atomicfu.locks.synchronized(calLock) { revokeLocked(why) }

    private fun revokeLocked(why: String) {
        val was = calibrationObjects != null
        calibrationObjects = null
        calibrationCommands = null
        gate.calibration = null
        gate.commands = null
        if (was) event("Calibration writes off ($why)")
    }

    val calibrationEnabled: Boolean get() = calibrationObjects != null

    /** [SdoPort]：讀一個物件（同 [readObject]），失敗丟例外。 */
    override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray =
        readObject(index, sub, block).getOrThrow().first

    /**
     * [SdoPort]：寫一個物件（SDO download）。4 bytes 以內 expedited，其餘區塊寫入（[SdoDownload]，同 XTune、同 XB）。
     * 只在調校模式、物件在准寫清單裡才送；每一幀照樣過 [WriteGate]。ECU abort 丟 [SdoAbortException]，逾時丟 [IOException]。
     * 不自動讀回；要確認就再 [read]（xb-link 的 LiveTuning 會讀回）。
     */
    override suspend fun write(index: Int, sub: Int, data: ByteArray): Unit = rpcLock.withLock {
        val op = captureCal() ?: throw if (transport == null) IOException("not connected") else WriteGate.Refused("calibration writes are off")
        val allowed = calibrationObjects ?: throw WriteGate.Refused("calibration writes are off")
        if (index !in allowed) throw WriteGate.Refused("object 0x${index.hex(4)} is not in the calibration set")
        val gateNow = gate
        val dl = SdoDownload(rpc.rpcId, index, sub, data)
        drainReplies()
        var finished = false
        try {
            sendCal(dl.start(), op)
            while (true) {
                val f = awaitReply(timing.calWriteTimeoutMs) { it.canId == CanId.rpcReply(node) }
                if (f == null) {
                    event("Write 0x${index.hex(4)}/$sub: no reply")
                    throw IOException("write 0x${index.hex(4)}/$sub: no reply in ${timing.calWriteTimeoutMs} ms")
                }
                when (val st = dl.onReply(f)) {
                    is SdoDownload.Step.Send -> { gateNow.blockAcknowledged(); st.frames.forEach { sendCal(it, op) } }
                    SdoDownload.Step.Wait -> Unit
                    SdoDownload.Step.Done -> { finished = true; event("Write 0x${index.hex(4)}/$sub: ${data.size} B"); return@withLock }
                    is SdoDownload.Step.Failed -> {
                        finished = st.abort != null // ECU 自己 abort 的：交易已經結束
                        event("Write 0x${index.hex(4)}/$sub failed: ${st.reason}")
                        throw st.abort?.let { SdoAbortException(it) } ?: IOException("write 0x${index.hex(4)}/$sub: ${st.reason}")
                    }
                }
            }
        } finally {
            // 區塊寫入沒正常結束（逾時、取消、協定錯）：先送 abort 讓 ECU 結束這筆交易；送不出去就斷線，
            // 重連後一切重來（調校授權也會收回）
            // 結束幀送出後閘門已不算「進行中」，但 ECU 的最後確認還沒到也算沒結束
            // 只動這筆交易綁定的那條連線，不碰換上來的新連線
            if (!finished && !dl.expedited) withContext(NonCancellable) {
                runCatching { sendCal(Sdo.abortFrame(rpc.rpcId, index, sub, 0x05040000L), op, cleanup = true) }
                    .onFailure { event("Abort not sent after an unfinished block write; dropping the connection"); runCatching { op.transport.close() } }
            }
            gateNow.endBlock()
        }
    }

    /**
     * 功能專頁的命令：送一個組好的 SDO expedited 寫入幀（8 bytes，見 xb-tune CalCommands.sdoFrame），等 0x60 或 abort。
     * 只在調校模式、幀在命令清單裡才送（[WriteGate]）。回 null＝ECU 收下，否則原因。不重送。
     */
    suspend fun calCommand(data: ByteArray): String? = rpcLock.withLock {
        val op = captureCal() ?: return@withLock if (transport == null) "not connected" else "calibration writes are off"
        val f = Frame(rpc.rpcId, data)
        if (!gate.allows(f)) return@withLock "command not allowed"
        when (val r = try { sendWriteLocked(f, op) } catch (e: WriteGate.Refused) { return@withLock "command not allowed" }) {
            WriteResult.Ack -> null.also { event("Cal command ${data.hexLower()}: ACK") }
            is WriteResult.Aborted -> r.abort.toString().also { event("Cal command ${data.hexLower()}: $it") }
            is WriteResult.Error -> r.reason.also { event("Cal command ${data.hexLower()}: $it") }
            is WriteResult.Unverified -> "not verified"
        }
    }

    /**
     * 功能專頁的廠商訊框（ECU 燈效 0x689、每缸 AFR 0x680 `62`）：過閘門後送出，[reply]≠null 就收 [count] 筆該 CAN id、
     * 符合 [match] 的回應（收齊回傳；逾時丟例外）。
     */
    suspend fun calRaw(canId: Int, data: ByteArray, reply: Int?, count: Int, timeoutMs: Long, match: (ByteArray) -> Boolean): List<ByteArray> = rpcLock.withLock {
        val op = captureCal() ?: throw if (transport == null) IOException("not connected") else WriteGate.Refused("calibration writes are off")
        val f = Frame(canId, data)
        if (!gate.allows(f)) throw WriteGate.Refused("frame 0x${canId.hex(3)} ${data.hexLower()} not allowed")
        drainReplies()
        sendCal(f, op)
        if (reply == null) return@withLock emptyList()
        val out = ArrayList<ByteArray>()
        withTimeoutOrNull(timeoutMs) { while (out.size < count) replies.receive().let { if (it.canId == reply && match(it.data)) out += it.data } }
        if (out.size < count) throw IOException("0x${canId.hex(3)}: ${out.size}/$count replies in $timeoutMs ms")
        out
    }

    private fun drainReplies(): List<Frame> = buildList { while (true) add(replies.tryReceive().getOrNull() ?: break) }

    /** 等第一個符合條件的回應幀；逾時回 null。 */
    private suspend fun awaitReply(timeoutMs: Long, match: (Frame) -> Boolean): Frame? =
        withTimeoutOrNull(timeoutMs) {
            var hit: Frame? = null
            while (hit == null) replies.receive().let { if (match(it)) hit = it }
            hit
        }

    private suspend fun sendAwaitAck(f: Frame): Boolean {
        val addr = f.u8(1) or (f.u8(2) shl 8)
        val sub = f.u8(3)
        val seen = mutableListOf<Frame>()
        repeat(timing.ackRetries + 1) {
            drainReplies()
            send(f)
            val ack = awaitReply(timing.ackTimeoutMs) {
                val r = RpcReply.classify(it)
                (r is RpcReply.WriteAck && r.addr == addr && r.sub == sub).also { ok -> if (!ok && seen.size < 4) seen += it }
            }
            if (ack != null) return true
        }
        // 診斷：前幾次漏 ACK 時記下收到了什麼（沒收到也記）
        if (stats.ackMisses < 3) event("No ACK ${addr.hex(4)}/$sub sent ${f.data.hexLower()} | got " +
            if (seen.isEmpty()) "none" else seen.joinToString(" ") { "${it.canId.hex(3)}:${it.data.hexLower()}" })
        stats.ackMisses++
        return false
    }

    /** 武裝廣播。回傳是否在時限內收到完整一輪。 */
    private suspend fun arm(withTable: Boolean): Boolean = rpcLock.withLock { armLocked(withTable) }

    private suspend fun armLocked(withTable: Boolean): Boolean {
        streaming = false
        _loggedIn.value = false
        _state.value = SessionState.Arming("handshake")
        send(rpc.sessionOpen()); delay(timing.sessionOpenMs)
        for (f in rpc.handshake()) { send(f); delay(timing.stepMs) }
        val hs = drainReplies()
        if (hs.isNotEmpty()) event("Handshake reply ${hs.size} frames: " + hs.take(6).joinToString(" | ") { "${it.canId.hex(3)}:${it.data.hexLower()}" })

        if (password != null) {
            _state.value = SessionState.Arming("login")
            send(rpc.login(password))
            val reply = awaitReply(600) { it.canId == CanId.LOGIN_REPLY }
            stats.lastLogin = when {
                reply == null -> LOGIN_NO_REPLY
                reply.dlc >= 2 && reply.u8(1) == 0 -> "ok"
                else -> "rejected (status ${if (reply.dlc >= 2) reply.u8(1) else -1})"
            }
            _loggedIn.value = stats.lastLogin == "ok"
            event("Login: ${stats.lastLogin}")
            // ECU 連登入都不回時寫表只會 78×3 次逾時（實車多卡約 70 s），直接判失敗交給重連邏輯。
            // 廣播要是早就在跑（重連後、握手途中已收到一輪），onRound 不會再設狀態，這裡不收尾就會卡在 Arming
            //（2026-11-14 23:33 實車：畫面顯示「連線中」，資料照跑）。
            if (reply == null) {
                if (streaming) _state.value = SessionState.Streaming
                return false
            }
        }
        if (timing.sendNmtStart) { send(rpc.nmtStart()); delay(timing.stepMs) }

        if (withTable) {
            _state.value = SessionState.Arming("channel table ${table.entries.size} ch")
            var miss = 0
            for (f in table.writeSequence(rpc)) if (!sendAwaitAck(f)) miss++
            event("Channel table written: ${table.entries.size} ch, ${table.payloadLength} B/round, no ACK $miss")
        } else {
            _state.value = SessionState.Arming("resend startmon")
            sendAwaitAck(rpc.monitorStart())
        }

        val before = stats.rounds
        val got = withTimeoutOrNull(timing.firstRoundTimeoutMs) {
            while (stats.rounds == before) delay(50)
            true
        } ?: false
        // ECU 本來就在廣播時，第一輪會在握手途中就到，onRound 先設了 Streaming 又被後面的 Arming 蓋掉，
        // 而 streaming 旗標已是 true 不會再設一次 → 畫面卡「連線中」但資料在跑。收到就明確收尾成 Streaming。
        if (got) { streaming = true; _state.value = SessionState.Streaming }
        // 讀不到（被拒、逾時）就保留同一條連線上次讀到的值；從沒讀到過的不在表裡，畫面自己退回看即時通道
        if (got && _loggedIn.value) for (fn in dashSwitches) if (gate.dashVerified(rpc.dashIndex(fn))) {
            readObjectLocked(rpc.dashIndex(fn), 0, block = false, timeoutMs = 600).getOrNull()?.first?.firstOrNull()?.let { noteSwitch(fn, it.toInt() and 0xFF) }
        }
        if (!got) {
            _state.value = SessionState.NoBroadcast(
                "ECU is not streaming yet; retrying in ${timing.rearmIntervalMs / 1000} s. Make sure the ignition is on."
            )
            event("No complete broadcast within ${timing.firstRoundTimeoutMs} ms after arming")
        }
        return got
    }
}
