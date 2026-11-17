package xb.link

import rx.protocol.SdoPort

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xb.tune.Convert
import xb.tune.TableDef
import xb.tune.Tune

/**
 * 即時調校：編輯器裡改了表，停手 [debounceMs] 之後把改過的列寫進 ECU，再讀回確認。
 * 改一格就標那一列待寫，停手 1.2 秒才送，中途再改就重新計時（同 XTune）。
 * 另有 [writeTable]（「全寫」：整張每一列都送）、[readTable]（「全讀」：從 ECU 重讀整張）與 [restore]（寫回備份）。
 * 前提：開始時 ECU 與 [tune] 已經一致（見 [EcuSync]），而且呼叫的人已經存好整份備份。
 * 這裡記著「ECU 上目前是什麼」（[ecuRows]），每次只送跟它不同的列。只有 [snapshot] 裡讀得到的表能寫。
 * - 寫的是 ECU 的 RAM：馬上生效。ECU 關電門時會自己把校正存進 flash（有條件），要確定留下來得整包寫入。
 * - ECU 拒絕的表標成 [TableState.Refused]，之後不再送。
 * - 讀回跟寫的不一樣就標 [TableState.Mismatch]，ECU 上的值以讀回的為準。韌體可能過一陣子才把不收的值改回去，
 *   所以寫完 [verifyMs] 再讀一次。
 * - 改過的格子超出定義檔的 Min～Max 就不送（[TableState.OutOfRange]）；原本就超出、這次沒改的格子不管。
 * - 一次只送一張表；同一張表在送的時候又被改，送完會再排一次。
 * 執行緒：[onEdited] 由畫面呼叫（在畫面的執行緒讀 [tune]，把要送的內容複製一份）；其餘狀態只在這個物件自己的
 * 單執行緒排程上改，對外只透過 [states]、[ecu] 這些 StateFlow 公開。[close] 之後不再接受任何工作。
 */
class LiveTuning(
    private val link: SdoPort,
    private val tune: Tune,
    snapshot: Snapshot,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 1200,
    private val verifyMs: Long = 1000,
    private val log: (String) -> Unit = {},
) {
    sealed interface TableState {
        /** 跟 ECU 一致。 */
        data object Synced : TableState
        /** 改了，等停手。 */
        data object Pending : TableState
        data object Writing : TableState
        /** ECU 拒絕寫這張表；不再送。 */
        data class Refused(val reason: String) : TableState
        /** 讀回跟寫的不同（[cells] 格）。 */
        data class Mismatch(val cells: Int) : TableState
        /** 改過的格子有 [cells] 格超出定義檔的範圍，沒送。 */
        data class OutOfRange(val cells: Int) : TableState
        /** 連線出錯，沒確定寫進去。下次改這張表會再送。 */
        data class Failed(val reason: String) : TableState
    }

    /** 寫回備份的結果：[restored]＝重寫後讀回跟備份一致；[failed]＝原因；[unchanged]＝重讀 ECU 本來就是備份的值。 */
    class RestoreReport(val restored: List<Int>, val failed: Map<Int, String>, val unchanged: List<Int>) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /** 所有內部狀態只在這個排程上改（一次一個協程）。 */
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private val _ecu = MutableStateFlow<Map<Int, List<LongArray>>>(snapshot.ok.associate { it.def.absId to it.rows.map { r -> r.copyOf() } })
    /** ECU 上每張表目前的值（最後一次確認的）。只有讀得到的表。 */
    val ecu: StateFlow<Map<Int, List<LongArray>>> = _ecu

    private val _states = MutableStateFlow<Map<Int, TableState>>(emptyMap())
    /** 有動過的表的狀態（沒列出的＝沒動過、跟 ECU 一致）。 */
    val states: StateFlow<Map<Int, TableState>> = _states

    /** 這次連線寫了幾列、幾格。 */
    private val _written = MutableStateFlow(0 to 0)
    val written: StateFlow<Pair<Int, Int>> = _written

    /** 這次連線送過寫入幀的表（不管成功與否）：[restore] 一定要重寫的範圍。 */
    private val _touched = MutableStateFlow<Set<Int>>(emptySet())
    val touched: StateFlow<Set<Int>> = _touched

    private val _closed = MutableStateFlow(false)
    val closed: Boolean get() = _closed.value

    /** 等著送的內容（[onEdited] 時從編輯器複製的）。只在 [serial] 上動。 */
    private val desired = HashMap<Int, List<LongArray>>()
    /**
     * 送過寫入、但沒確定 ECU 上是什麼的表（寫了沒讀回、中途出錯）。快取的 ECU 值對這些表不算數：
     * 編輯器改回跟快取一樣也照樣整張重寫；要等整張寫完讀回一致、或重新全讀，才拿掉。只在 [serial] 上動。
     */
    private val uncertain = HashSet<Int>()
    private val timers = HashMap<Int, Job>()
    /** 這個物件開的協程（計時、延遲複查）；[close] 一起收掉。 */
    private val jobs = ArrayList<Job>()
    private val sendLock = Mutex()

    private fun spawn(block: suspend CoroutineScope.() -> Unit): Job? {
        if (_closed.value) return null
        return scope.launch(serial, block = block).also { j -> jobs.removeAll { it.isCompleted }; jobs += j }
    }

    /** 這張表能即時寫：ECU 上讀得到、沒被拒絕、還沒關閉。 */
    fun writable(absId: Int): Boolean = !_closed.value && absId in _ecu.value && _states.value[absId] !is TableState.Refused

    /** ECU 上這張表目前的值（最後一次確認的）。 */
    fun ecuRows(absId: Int): List<LongArray>? = _ecu.value[absId]?.map { it.copyOf() }

    private fun copyRows(absId: Int): List<LongArray>? = tune.rows(absId)?.map { it.copyOf() }

    /** 編輯器改了 [absId]（改值、復原、整張複製都算）。在畫面的執行緒呼叫；停手後送出。 */
    fun onEdited(absId: Int) {
        if (!writable(absId)) return
        val rows = copyRows(absId) ?: return
        spawn { schedule(absId, rows) }
    }

    private fun schedule(absId: Int, rows: List<LongArray>) {
        if (!writable(absId)) return
        desired[absId] = rows
        timers.remove(absId)?.cancel()
        if (absId !in uncertain && same(rows, _ecu.value[absId])) { desired.remove(absId); setState(absId, null); return }
        setState(absId, TableState.Pending)
        timers[absId] = spawn { delay(debounceMs); flush(absId) } ?: return
    }

    /** 不等停手，馬上送出所有還沒送的表，並等正在送的寫完（例如要整包寫入或斷線前）。 */
    suspend fun flushAll() = withContext(serial) {
        val pending = (desired.keys + _states.value.filterValues { it is TableState.Failed }.keys).toSet()
        for (id in pending) { timers.remove(id)?.cancel(); flush(id) }
        sendLock.withLock { }
    }

    private fun same(a: List<LongArray>?, b: List<LongArray>?): Boolean =
        a != null && b != null && a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    private fun setState(absId: Int, s: TableState?) = _states.update { if (s == null) it - absId else it + (absId to s) }
    private fun setEcu(absId: Int, rows: List<LongArray>) = _ecu.update { it + (absId to rows.map { r -> r.copyOf() }) }

    /**
     * 全寫：不等停手，整張表每一列都送一次，跟 ECU 上一樣的列也送。在畫面的執行緒呼叫（會讀 [tune]）。
     * 範圍檢查照舊：有格子超出定義檔範圍就整張不送。
     */
    suspend fun writeTable(absId: Int) {
        if (!writable(absId)) return
        val rows = copyRows(absId) ?: return
        withContext(serial) {
            timers.remove(absId)?.cancel()
            desired[absId] = rows
            flush(absId, everyRowAsked = true)
        }
    }

    /**
     * 全讀：從 ECU 重讀整張表，回傳讀到的列，ECU 現值以它為準。
     * 沒在等的改動會被呼叫端用讀到的值蓋掉（XTune 也是）。
     */
    suspend fun readTable(absId: Int): Result<List<LongArray>> = withContext(serial) {
        if (_closed.value) return@withContext Result.failure(IllegalStateException("live tuning closed"))
        sendLock.withLock {
            timers.remove(absId)?.cancel(); desired.remove(absId)
            val def = tune.definition(absId) ?: return@withLock Result.failure(IllegalArgumentException("table $absId not in definitions"))
            when (val r = EcuSync.readTable(link, def)) {
                is TableRead.Ok -> {
                    if (absId in _ecu.value) setEcu(absId, r.rows)
                    uncertain.remove(absId)
                    if (_states.value[absId] !is TableState.Refused) setState(absId, null)
                    Result.success(r.rows.map { it.copyOf() })
                }
                is TableRead.Failed -> Result.failure(IllegalStateException(r.reason))
            }
        }
    }

    private fun refusal(code: Long) = code == 0x06010004L || code == 0x06010002L || code == 0x06010003L

    private suspend fun flush(absId: Int, everyRowAsked: Boolean = false) {
        sendLock.withLock {
            // 不確定的表：每一列都重寫，讀回一致才算解決
            val wasUncertain = absId in uncertain
            val everyRow = everyRowAsked || wasUncertain
            timers.remove(absId)
            if (!writable(absId)) { desired.remove(absId); return }
            val def = tune.definition(absId) ?: return
            val mine = desired.remove(absId) ?: return
            val ecu = _ecu.value.getValue(absId)
            if (mine.size != ecu.size || mine.indices.any { mine[it].size != ecu[it].size }) {
                setState(absId, TableState.Failed("table size differs from the ECU")); return
            }
            val rows = mine.indices.filter { everyRow || !mine[it].contentEquals(ecu[it]) }
            if (rows.isEmpty()) { setState(absId, null); return }
            val bad = rows.sumOf { r -> mine[r].indices.count { c -> (everyRow || mine[r][c] != ecu[r][c]) && Convert.isOutOfRange(def, mine[r][c]) } }
            if (bad > 0) { setState(absId, TableState.OutOfRange(bad)); return }
            setState(absId, TableState.Writing)
            // 送出去之前就算不確定：送的途中編輯器改回快取的值不能當成已同步，取消或出錯也留著
            uncertain += absId
            val confirmed = ecu.map { it.copyOf() }.toMutableList()
            var mismatched = 0
            val small = EcuSync.rowBytes(def) <= 4
            try {
                for (r in rows) {
                    _touched.update { it + absId }
                    link.write(EcuSync.index(def), r, EcuSync.encodeRow(def, mine[r]))
                    val back = EcuSync.decodeRow(def, link.read(EcuSync.index(def), r, block = !small))
                    confirmed[r] = back
                    mismatched += back.indices.count { back[it] != mine[r][it] }
                    _written.update { (a, b) -> (a + 1) to (b + mine[r].indices.count { mine[r][it] != ecu[r][it] }) }
                }
            } catch (e: SdoAbortException) {
                setEcu(absId, confirmed)
                uncertain += absId
                log("${def.name}: ECU refused (${e.abort})")
                setState(absId, if (refusal(e.abort.code)) TableState.Refused(e.abort.toString()) else TableState.Failed(e.abort.toString()))
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // 取消（例如離開頁面）：讀回到的列記下來，表維持不確定
                    setEcu(absId, confirmed)
                    setState(absId, TableState.Failed("cancelled"))
                    throw e
                }
                // 寫了但沒讀回：ECU 上那一列是什麼不確定（下次改整張再送、讀回才算；還原一定重寫）
                setEcu(absId, confirmed)
                log("${def.name}: ${e.message}")
                setState(absId, TableState.Failed(e.message ?: e::class.simpleName ?: "error"))
                return
            }
            setEcu(absId, confirmed)
            // 寫的列都讀回了；原本就不確定的表要整張重寫過才算確定
            if (mismatched == 0 && (everyRow || !wasUncertain)) uncertain.remove(absId)
            log("${def.name}: wrote ${rows.size} row(s)" + if (mismatched > 0) ", $mismatched cell(s) read back different" else "")
            setState(absId, if (mismatched > 0) TableState.Mismatch(mismatched) else TableState.Synced)
            if (mismatched == 0) spawn { delay(verifyMs); verify(def, rows) }
        }
        // 送的時候又改了：跟讀回的 ECU 值比，不同就再排一次，相同就不用送
        desired[absId]?.let {
            if (absId in uncertain || !same(it, _ecu.value[absId])) schedule(absId, it)
            else { desired.remove(absId); timers.remove(absId)?.cancel(); if (_states.value[absId] == TableState.Pending) setState(absId, TableState.Synced) }
        }
    }

    /** 寫完一陣子再讀一次那幾列：韌體把值改掉了就標 Mismatch，ECU 的值以讀回為準。 */
    private suspend fun verify(def: TableDef, rows: List<Int>) = sendLock.withLock {
        val absId = def.absId
        if (_closed.value || _states.value[absId] != TableState.Synced) return@withLock
        val ecu = _ecu.value[absId]?.map { it.copyOf() }?.toMutableList() ?: return@withLock
        var changed = 0
        try {
            for (r in rows) {
                val back = EcuSync.decodeRow(def, link.read(EcuSync.index(def), r, block = EcuSync.rowBytes(def) > 4))
                changed += back.indices.count { back[it] != ecu[r][it] }
                ecu[r] = back
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("${def.name}: verify read failed: ${e.message}")
            return@withLock
        }
        if (changed > 0) {
            setEcu(absId, ecu)
            log("${def.name}: $changed cell(s) changed by the ECU after writing")
            setState(absId, TableState.Mismatch(changed))
        }
    }

    /**
     * 寫回備份：[original]（開始時讀回的 ECU 值）每一張都處理，不信快取：這次連線寫過或不確定的表直接重寫；
     * 其他表先重讀 ECU，跟備份不同才重寫。重寫是每一列照備份原值寫（不經過編輯器、不看範圍），再一列一列讀回比對；
     * 只有讀回跟備份一致才算還原成功。任何一張出錯都記在報告裡，接著做下一張。
     * ECU 開著學習（[learning]，見 [Learning.tables]）時會自己改油表，還原途中已確認的列也可能被改掉，所以：
     * 1. 定義檔裡有的學習開關全部確認關掉（開著就寫 0 並讀回）——包括備份時讀不到、不在 [original] 裡的；
     * 2. 還原其他表：[recheck]（儀表鈕等別的路會改的開關表）先，其餘照順序；
     * 3. 學習還關著時，再把還原過、本來就一樣的表整份重讀一次跟備份比（後面的表動到前面的也抓得到），學習開關也再確認沒被打開；
     * 4. 最後才把學習開關寫回備份的值（原本開著的才會再開）。備份裡沒有的開關無從寫回，維持關著並記成失敗。
     * 1 或 3 確認不了學習關著：其他表就算讀回一致也不算還原成功，全部記成失敗（學習開關本身照樣寫回備份）。
     */
    suspend fun restore(original: Map<Int, List<LongArray>>, recheck: Set<Int> = emptySet(), learning: List<Int> = emptyList()): RestoreReport = withContext(serial) {
        if (_closed.value) return@withContext RestoreReport(emptyList(), original.keys.associateWith { "live tuning closed" }, emptyList())
        for (t in timers.values) t.cancel()
        timers.clear(); desired.clear()
        sendLock.withLock {
            val restored = ArrayList<Int>(); val failed = LinkedHashMap<Int, String>(); val unchanged = ArrayList<Int>()
            // 定義檔裡沒有的開關＝這台不適用；有定義就一定要確認關了，不管備份時讀不讀得到
            val learn = learning.distinct().filter { tune.definition(it) != null }
            // 1. 關學習
            var learningOff = true
            for (absId in learn) {
                val reason = switchOff(absId)
                if (reason != null) { learningOff = false; failed[absId] = "could not turn learning off: $reason" }
            }
            // 2. 其他表
            val order = original.keys.filter { it !in learn }.sortedWith(compareBy<Int>({ it !in recheck }, { it }))
            for (absId in order) when (val r = restoreTable(absId, original.getValue(absId))) {
                Done.Restored -> restored += absId
                Done.Same -> unchanged += absId
                is Done.Failed -> failed[absId] = r.why
            }
            // 3. 學習還關著：整份再比一次，學習開關也再確認
            if (learningOff) {
                for (absId in restored + unchanged) {
                    val why = recheckTable(absId, original.getValue(absId)) ?: continue
                    failed[absId] = why
                }
                restored.removeAll(failed.keys); unchanged.removeAll(failed.keys)
                for (absId in learn) {
                    val reason = switchOff(absId, writeIfOn = false)
                    if (reason != null) { learningOff = false; failed[absId] = "learning was on during restore: $reason" }
                }
            }
            if (!learningOff) {
                val why = "learning not confirmed off during restore"
                for (id in restored + unchanged) failed[id] = why
                restored.clear(); unchanged.clear()
            }
            // 4. 學習開關寫回備份
            for (absId in learn) {
                val rows = original[absId]
                if (rows == null) { failed[absId] = failed[absId]?.let { "$it; not in the backup, left off" } ?: "not in the backup, left off"; continue }
                when (val r = restoreTable(absId, rows)) {
                    Done.Restored -> if (absId !in failed) restored += absId
                    Done.Same -> if (absId !in failed) unchanged += absId
                    is Done.Failed -> failed[absId] = failed[absId]?.let { "$it; ${r.why}" } ?: r.why
                }
            }
            log("Restore: ${restored.size} restored, ${failed.size} failed, ${unchanged.size} unchanged" + if (learningOff) "" else " (learning not confirmed off)")
            RestoreReport(restored, failed, unchanged)
        }
    }

    /** 還原後再讀一次整張跟備份比；一樣回 null，不一樣或讀不到回原因。只在 [restore] 裡呼叫。 */
    private suspend fun recheckTable(absId: Int, rows: List<LongArray>): String? {
        val def = tune.definition(absId) ?: return "not in definitions"
        return try {
            when (val r = EcuSync.readTable(link, def)) {
                is TableRead.Ok -> {
                    setEcu(absId, r.rows)
                    if (same(rows, r.rows)) null else {
                        val cells = rows.indices.sumOf { i -> r.rows.getOrNull(i)?.let { got -> rows[i].indices.count { c -> got.getOrNull(c) != rows[i][c] } } ?: rows[i].size }
                        uncertain += absId; setState(absId, TableState.Mismatch(cells))
                        "$cells cell(s) changed after the table was restored"
                    }
                }
                is TableRead.Failed -> "final read failed: ${r.reason}"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "final read failed: ${e.message ?: e::class.simpleName}"
        }
    }

    /**
     * 把學習開關 [absId] 關掉（每格 0）並讀回確認。[writeIfOn]＝false 時只讀、不寫：讀到開著就回原因。
     * 回 null＝確認關了。只在 [restore] 裡（拿著 [sendLock]）呼叫。
     */
    private suspend fun switchOff(absId: Int, writeIfOn: Boolean = true): String? {
        val def = tune.definition(absId) ?: return "not in definitions"
        val small = EcuSync.rowBytes(def) <= 4
        return try {
            val now = when (val r = EcuSync.readTable(link, def)) {
                is TableRead.Ok -> r.rows
                is TableRead.Failed -> return r.reason
            }
            setEcu(absId, now)
            if (now.all { row -> row.all { it == 0L } }) return null
            if (!writeIfOn) return "switched on"
            val off = now.map { LongArray(it.size) }
            val back = now.map { it.copyOf() }.toMutableList()
            uncertain += absId
            for (r in off.indices) {
                _touched.update { it + absId }
                link.write(EcuSync.index(def), r, EcuSync.encodeRow(def, off[r]))
            }
            for (r in off.indices) back[r] = EcuSync.decodeRow(def, link.read(EcuSync.index(def), r, block = !small))
            setEcu(absId, back)
            if (back.all { row -> row.all { it == 0L } }) null else "read back on"
        } catch (e: SdoAbortException) {
            setState(absId, if (refusal(e.abort.code)) TableState.Refused(e.abort.toString()) else TableState.Failed(e.abort.toString()))
            e.abort.toString()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.message ?: e::class.simpleName ?: "error"
        }
    }

    /**
     * 還原一張表（見 [restore]）：[Done.Restored]＝重寫並讀回一致；[Done.Same]＝重讀 ECU 本來就是備份的值。
     * 只在 [restore] 裡（拿著 [sendLock]）呼叫。
     */
    private sealed interface Done {
        data object Restored : Done
        data object Same : Done
        class Failed(val why: String) : Done
    }

    private suspend fun restoreTable(absId: Int, rows: List<LongArray>): Done {
        val def = tune.definition(absId) ?: return Done.Failed("not in definitions")
        val small = EcuSync.rowBytes(def) <= 4
        return try {
            val sure = absId in _touched.value || absId in uncertain
            if (!sure) {
                when (val r = EcuSync.readTable(link, def)) {
                    is TableRead.Ok -> { setEcu(absId, r.rows); if (same(rows, r.rows)) return Done.Same }
                    is TableRead.Failed -> Unit // 讀不到就當不同，照樣重寫
                }
            }
            val now = (_ecu.value[absId] ?: rows).map { it.copyOf() }.toMutableList()
            uncertain += absId
            for (r in rows.indices) {
                _touched.update { it + absId }
                link.write(EcuSync.index(def), r, EcuSync.encodeRow(def, rows[r]))
            }
            var diff = 0
            for (r in rows.indices) {
                val back = EcuSync.decodeRow(def, link.read(EcuSync.index(def), r, block = !small))
                now[r] = back
                diff += back.indices.count { back[it] != rows[r][it] }
            }
            setEcu(absId, now)
            if (diff == 0) { uncertain.remove(absId); setState(absId, if (_states.value[absId] is TableState.Refused) _states.value[absId] else null); Done.Restored }
            else { setState(absId, TableState.Mismatch(diff)); Done.Failed("$diff cell(s) read back different") }
        } catch (e: SdoAbortException) {
            uncertain += absId
            setState(absId, if (refusal(e.abort.code)) TableState.Refused(e.abort.toString()) else TableState.Failed(e.abort.toString()))
            Done.Failed(e.abort.toString())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            uncertain += absId
            val why = e.message ?: e::class.simpleName ?: "error"
            setState(absId, TableState.Failed(why))
            Done.Failed(why)
        }
    }

    /**
     * 獨占：先把還沒送的送完、等正在送的結束，之後在 [block] 跑完之前不送任何東西（整包寫入用）。
     * [block] 期間畫面的修改照樣記下，結束後才排程。
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        flushAll()
        return withContext(serial) {
            for (t in timers.values) t.cancel()
            timers.clear()
            sendLock.withLock { block() }
        }
    }

    /**
     * 整包寫進 ECU（[Amc]）時要改用 ECU 現值的表：ECU 拒絕、被 ECU 改掉、超出範圍沒送、沒確定寫進去的。
     * ECU 不收的值不該換一條路寫進 flash。
     */
    fun ecuOverrides(): Map<Int, List<LongArray>> = _states.value.filterValues {
        it is TableState.Refused || it is TableState.Mismatch || it is TableState.OutOfRange || it is TableState.Failed
    }.keys.mapNotNull { id -> _ecu.value[id]?.let { id to it.map { r -> r.copyOf() } } }.toMap()

    /**
     * 整包寫進 ECU 並讀回確認之後：ECU 現值換成 [verified]（整份讀回的結果）。被拒的表維持被拒，其他狀態清掉。
     */
    fun afterFullWrite(verified: Map<Int, List<LongArray>>) {
        _ecu.update { m -> m.mapValues { (id, rows) -> verified[id]?.map { it.copyOf() } ?: rows } }
        scope.launch(serial) { uncertain.removeAll(verified.keys) }
        _states.update { m -> m.filterValues { it is TableState.Refused } }
    }

    /** 停掉計時與複查，之後不再接受任何工作（不送還沒送的；要送先 [flushAll]）。 */
    suspend fun close() {
        _closed.value = true
        val all = withContext(serial + NonCancellable) {
            timers.clear(); desired.clear()
            jobs.toList().also { jobs.clear() }
        }
        all.forEach { it.cancel() }
        all.joinAll()
    }

    /** 這張表在 ECU 上被拒絕時，提示用的文字。 */
    fun refusal(def: TableDef): String? = (_states.value[def.absId] as? TableState.Refused)?.reason
}
