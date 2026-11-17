package io.github.xeno_hf.rex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import rx.session.Session
import rx.session.SessionState
import rx.session.WallClock
import xb.link.EcuSync
import xb.link.LiveTuning
import xb.link.Snapshot
import xb.tune.CalCommands
import xb.tune.Tune
import xb.tune.TunePack
import io.github.xeno_hf.rex.res.R
import io.github.xeno_hf.rex.ui.Strings

/**
 * 功能專頁的命令（xb-tune [CalCommands]）：RPC 上的 SDO 命令幀、0x680／0x689 的廠商訊框。
 * 寫入閘門只在調校模式開著時問這裡。
 */
internal fun calCommandAllowed(f: rx.protocol.Frame): Boolean =
    if (f.canId == 0x680 || f.canId == 0x689) CalCommands.rawAllowed(f.canId, f.data) else CalCommands.frameAllowed(f.data)

/**
 * ReX 1.0 即時調校：跟 XB 桌面版用同一份程式（xb-tune 的 [Tune]、xb-link 的 [EcuSync]／[LiveTuning]），
 * 寫入走 ReX 的 [Session]（同一條監控連線、每一幀過寫入閘門的調校模式）。
 *
 * 流程照 XB：
 * 1. 調校包（XB 同步來的 [TunePack]）的清單與裡面的範本要一致，而且跟這台 ECU 的平台、V、C、車種代號（Table ID）
 *    全部對上（[EcuSync.compatibility]），否則不開始。
 * 2. 整份讀回 ECU，備份（未加密的調校檔 XML，XB 打得開）存好並讀回確認之後，才開調校模式；
 *    只准寫讀得到、已備份的表。讀不到的表鎖住。備份存不起來就不開始。
 * 3. 改格子停手 1.2 秒送整列並讀回（同 XTune）；ECU 拒絕的表鎖住、讀回不同照 ECU 為準。
 * 4. 還原＝把開始時讀回的值一列一列重寫並讀回確認（[LiveTuning.restore]，不整包寫 flash）。官方不送存檔命令，ECU 關電門自己存。
 *
 * 連線斷了、換了平台：[Session] 立刻收回寫入授權，這裡也停止調校；重連後要重新開始（重新讀回、重新備份）。
 */
class Tuning(private val repo: Repo, private val scope: CoroutineScope) {
    class Work(
        val opened: TunePack.Opened,
        val session: Session,
        val live: LiveTuning,
        val snapshot: Snapshot,
        val backup: Path,
        /** ECU 上讀不到的表（不能改）。 */
        val unreadable: Set<Int>,
        /** 開始時的連線代號（[Session.generation]）。 */
        val generation: Long,
    ) {
        val tune: Tune get() = opened.tune
    }

    sealed interface State {
        data object Idle : State
        /** 沒有這台 ECU 的調校包：要先在 XB 那邊同步（或匯入）。 */
        class NoPack(val mapType: String?, val tableId: String?) : State
        data object Opening : State
        class Reading(val done: Int, val total: Int) : State
        class Ready(val work: Work) : State
        class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 編輯器內容變了（改格、全讀、還原）：畫面重畫用。 */
    val revision = MutableStateFlow(0)

    val work: Work? get() = (_state.value as? State.Ready)?.work

    private var job: Job? = null
    private var watch: Job? = null

    private val packDir: Path get() = Path(repo.platform.filesDir, "tunepacks")
    private val backupDir: Path get() = Path(repo.platform.filesDir, "ecu-backups")

    private fun packFile(mapType: String, tableId: String) = Path(packDir, "${mapType.uppercase()}_${tableId.trim()}.zip")

    /** 存下 XB 給的調校包（ZIP）。成功回 null，否則回原因。 */
    fun savePack(zip: ByteArray): String? {
        val p = try { unzip(zip) } catch (e: Exception) { return e.message ?: "invalid" }
        if (p.mapType.isBlank() || p.tableId.isBlank() || (p.mapType + p.tableId).any { !it.isLetterOrDigit() && it != '_' }) return "invalid model"
        Fs.writeBytes(packFile(p.mapType, p.tableId), zip)
        repo.log("Tune pack: ${p.platform.definition} ${p.mapType} C${p.cCode ?: "?"} ${p.tableId} ${p.vehicle ?: ""} (${p.manifest["app"] ?: ""})")
        return null
    }

    /** 手機上有哪些車種的調校包（map type＋Table ID）。 */
    fun packs(): List<String> = Fs.list(packDir).filter { it.name.endsWith(".zip") }.map { it.name.removeSuffix(".zip") }

    private fun unzip(zip: ByteArray): TunePack {
        val files = LinkedHashMap<String, String>()
        val names = listOf(TunePack.MANIFEST, TunePack.CONTROL, TunePack.SCALES, TunePack.GROUP, TunePack.SHORT, TunePack.DESC, TunePack.TEMPLATE) +
            listOf(TunePack.CONTROL, TunePack.GROUP, TunePack.SHORT, TunePack.DESC).map { TunePack.ATTACHED + it }
        for (n in names) repo.platform.unzipFirst(zip) { it == n }?.let { (_, b) -> files[n] = b.decodeToString() }
        return TunePack.of(files)
    }

    /** 開始調校：讀型號 → 找調校包 → 比平台／版本／車種 → 整份讀回、備份 → 開即時寫入。 */
    fun start() {
        if (job?.isActive == true || work != null) return
        val s = repo.session ?: run { _state.value = State.Failed("not connected"); return }
        job = scope.launch {
            _state.value = State.Opening
            var gen = -1L
            // 整個準備過程中斷線或換平台：不開始
            val dropped = launch {
                s.state.first { it is SessionState.Reconnecting || it is SessionState.Stopped || it is SessionState.Connecting }
                _state.value = State.Failed(Strings.get(R.string.tune_err_dropped))
                job?.cancel()
            }
            try {
                withTimeoutOrNull(8_000) { s.loggedIn.first { it } }
                    ?: run { _state.value = State.Failed(Strings.get(R.string.tune_err_login)); return@launch }
                gen = s.generation
                val info = repo.vehicleOf(s, fresh = true)
                val platform = s.platform.value
                val mapType = platform?.let(rx.session.XblPack::mapType)
                val tableId = info["Table ID"]
                if (mapType == null || tableId == null) { _state.value = State.NoPack(mapType, tableId); return@launch }
                val f = packFile(mapType, tableId)
                if (!Fs.exists(f)) { _state.value = State.NoPack(mapType, tableId); return@launch }
                val opened = withContext(Dispatchers.Default) { unzip(Fs.readBytes(f)).open() }
                val tune = opened.tune
                // 調校包的清單要跟裡面的範本一致（檔名只是清單算出來的，不能當證據）
                if (!opened.pack.mapType.equals(tune.file.mapType.trim(), ignoreCase = true) || opened.pack.tableId.trim() != tune.file.tableId.trim()) {
                    _state.value = State.Failed(Strings.get(R.string.tune_err_pack)); return@launch
                }
                val id = EcuSync.identity(s)
                EcuSync.compatibility(id, platform, tune)?.let { why ->
                    _state.value = State.Failed(Strings.get(R.string.tune_err_version, id.label(), tune.file.vCode, tune.file.cCode) + "\n" + why); return@launch
                }
                val snap = EcuSync.readAll(s, tune) { d, t -> _state.value = State.Reading(d, t) }
                // 備份一定要存好、讀回一致才往下
                val text = EcuSync.backup(snap, tune, WallClock.iso(WallClock.nowMs()))
                val name = "ECU_" + WallClock.format(WallClock.nowMs(), "yyyyMMdd_HHmmss") + "_${tableId}" + (if (snap.complete) "" else "_incomplete") + ".xml"
                val backup = Path(backupDir, name)
                val saved = runCatching { Fs.writeText(backup, text); Fs.readBytes(backup).contentEquals(text.encodeToByteArray()) }
                if (saved.getOrNull() != true) {
                    repo.log("Tuning: backup not saved: ${saved.exceptionOrNull()?.message ?: "read-back differs"}")
                    _state.value = State.Failed(Strings.get(R.string.tune_err_backup)); return@launch
                }
                for (r in snap.ok) check(tune.loadRows(r.def.absId, r.rows)) { "${r.def.name}: ECU rows do not fit the tune" }
                val live = LiveTuning(s, tune, snap, scope, log = { repo.log("Tuning: $it") })
                val unreadable = snap.failed.map { it.def.absId }.toSet()
                // 只准寫讀得到、已備份的表；連線代號要跟開始讀回時一樣
                if (!s.enableCalibration(snap.ok.map { EcuSync.index(it.def) }.toSet(), ::calCommandAllowed, gen)) {
                    live.close()
                    _state.value = State.Failed(Strings.get(R.string.tune_err_dropped)); return@launch
                }
                dropped.cancel()
                _state.value = State.Ready(Work(opened, s, live, snap, backup, unreadable, gen))
                revision.value++
                repo.log("Tuning on: ${snap.ok.size} tables read, ${unreadable.size} unreadable, backup ${backup.name}")
                watch = scope.launch {
                    // ECU 拒絕、或讀回跟寫的不同：編輯器換回 ECU 上的值（ReX 沒有調校檔，畫面就是 ECU）
                    val follow = launch {
                        live.states.collect { m ->
                            for ((id, st) in m) if (st is LiveTuning.TableState.Refused || st is LiveTuning.TableState.Mismatch) {
                                val ecu = live.ecuRows(id) ?: continue
                                val cur = tune.rows(id) ?: continue
                                if (cur.size == ecu.size && cur.indices.all { cur[it].contentEquals(ecu[it]) }) continue
                                tune.loadRows(id, ecu); revision.value++
                            }
                        }
                    }
                    s.state.first { it is SessionState.Reconnecting || it is SessionState.Stopped || it is SessionState.Connecting || s.generation != gen }
                    follow.cancel()
                    // 另開一個協程收尾：stopInternal 會取消這個 watch
                    this@Tuning.scope.launch { stopInternal(Strings.get(R.string.tune_err_dropped)) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                s.disableCalibration()
                _state.value = State.Failed(e.message ?: e::class.simpleName ?: "error")
            } finally {
                dropped.cancel()
            }
        }
    }

    /** 結束調校：還沒送的先送完，關掉調校模式。 */
    fun stop() { scope.launch { stopInternal(null) } }

    private suspend fun stopInternal(reason: String?) {
        watch?.cancel(); watch = null
        job?.cancel(); job = null
        val w = work
        if (w != null) {
            if (reason == null) runCatching { w.live.flushAll() }
            w.live.close()
            w.session.disableCalibration()
        } else repo.session?.disableCalibration()
        _state.value = if (reason != null) State.Failed(reason) else State.Idle
        revision.value++
    }

    /** 編輯器改了 [absId]（改值、復原、整張貼上都算）。 */
    fun edited(absId: Int) {
        work?.live?.onEdited(absId)
        revision.value++
    }

    /** 全寫（XTune 的「全寫」）：這張表每一列都送。 */
    suspend fun writeTable(absId: Int) { work?.live?.writeTable(absId); revision.value++ }

    /** 全讀（XTune 的「全讀」）：從 ECU 重讀這張表換進編輯器（可以復原）。回傳是否跟編輯器不同；失敗丟例外。 */
    suspend fun readTable(absId: Int): Boolean {
        val w = work ?: return false
        val rows = w.live.readTable(absId).getOrThrow()
        val cur = w.tune.rows(absId)!!
        val changed = !(cur.size == rows.size && cur.indices.all { cur[it].contentEquals(rows[it]) })
        check(w.tune.loadRows(absId, rows, undoable = true)) { "ECU rows do not fit the tune" }
        revision.value++
        return changed
    }

    /**
     * 還原：開始調校時讀回的 ECU 值，一列一列重寫並讀回確認（不經過編輯器的快取、不看範圍）。
     * 成功還原的表編輯器也換回原值（可以復原）。回傳結果；[LiveTuning.RestoreReport.ok] 才算全部還原。
     */
    suspend fun restore(): LiveTuning.RestoreReport? {
        val w = work ?: return null
        val original = w.snapshot.ok.associate { it.def.absId to it.rows }
        // 學習開關（AT、Pro-AT、寬域閉迴路）會讓 ECU 自己改油表：先關、還原完其他表才寫回（[xb.link.Learning]）。
        // 儀表鈕（TC／EB…）直接寫 ECU、不經過即時寫入：這幾張表先還原。
        val learning = xb.link.Learning.tables(rx.protocol.EcuPlatform.ofNode(w.session.node))
        val dash = rx.protocol.DashFn.entries.map { rx.protocol.DashTable.index(w.session.node, it) }
            .filter { it in 0x3000..0x3FFF }.map { it - 0x3000 }.toSet()
        val report = w.live.restore(original, recheck = dash, learning = learning)
        for (id in report.restored + report.unchanged) {
            val rows = original[id] ?: continue
            val cur = w.tune.rows(id) ?: continue
            if (cur.size == rows.size && cur.indices.all { cur[it].contentEquals(rows[it]) }) continue
            w.tune.loadRows(id, rows, undoable = true)
        }
        for (id in report.failed.keys) w.live.ecuRows(id)?.let { w.tune.loadRows(id, it, undoable = true) }
        revision.value++
        repo.log("Tuning restore: ${report.restored.size} restored, ${report.failed.size} failed" +
            if (report.failed.isEmpty()) "" else ": " + report.failed.entries.take(6).joinToString { "${it.key} ${it.value}" })
        return report
    }
}
