package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import rx.session.SessionState
import io.github.xeno_hf.rex.Tuning
import xb.link.LiveTuning
import xb.tune.AdjustOp
import xb.tune.AxisLookup
import xb.tune.CalItems
import xb.tune.CalStep
import xb.tune.Cell
import xb.tune.Convert
import xb.tune.EditResult
import xb.tune.Scope
import xb.tune.TableDef
import xb.tune.TableKind
import xb.tune.TableList
import xb.tune.Tune
import xb.calui.usable

/**
 * 調校頁（ReX 1.0 即時調校）。表、操作、寫入時機都照 XTune 3.7.2 的油圖頁，程式跟 XB 桌面版共用：
 * ＋步進／−步進（步進每張表各自記住）、×倍數、＝設定、平滑、線性、全讀、全寫、復原、準星。
 * 改完停手 1.2 秒把改過的列寫進 ECU 並讀回。開始時整份讀回並備份，可以一鍵寫回。
 *
 * 還沒有這台車的調校包時，照舊可以只看油圖（[MapScreen]，XB 給的 41 張有準星的表）。
 */
@Composable
fun TuneScreen() {
    val repo = LocalRepo.current
    val st by repo.tuning.state.collectAsState()
    val conn by repo.state.collectAsState()
    var viewOnly by remember { mutableStateOf(false) }
    val s = st
    if (s is Tuning.State.Ready) { TuneEditor(s.work); return }
    if (viewOnly) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { viewOnly = false }, modifier = Modifier.padding(horizontal = 6.dp)) { Text("‹ " + stringResource(R.string.tune_title), fontSize = 13.sp) }
            Box(Modifier.weight(1f)) { MapScreen() }
        }
        return
    }
    val connected = conn == SessionState.Streaming || conn is SessionState.NoBroadcast || conn is SessionState.Arming
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tune_title), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(stringResource(R.string.tune_intro), color = RxColors.muted, fontSize = 13.sp, lineHeight = 19.sp)
        when (s) {
            Tuning.State.Opening -> { Text(stringResource(R.string.tune_opening), fontSize = 13.sp); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is Tuning.State.Reading -> {
                Text(stringResource(R.string.tune_reading, s.done, s.total), fontSize = 13.sp)
                LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
            }
            is Tuning.State.NoPack -> {
                Text(stringResource(R.string.tune_no_pack, s.mapType ?: "?", s.tableId ?: "?"), color = RxColors.warn, fontSize = 13.sp, lineHeight = 19.sp)
                val pc by repo.syncComputer.collectAsState()
                val sync by repo.syncStatus.collectAsState()
                if (pc != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = sync !is io.github.xeno_hf.rex.Repo.SyncStatus.Running, onClick = { repo.syncNow() }) { Text(stringResource(R.string.sync_now)) }
                    when (val x = sync) {
                        is io.github.xeno_hf.rex.Repo.SyncStatus.Running -> Text(stringResource(R.string.sync_running, x.done, x.total), color = RxColors.muted, fontSize = 12.sp)
                        is io.github.xeno_hf.rex.Repo.SyncStatus.Done -> Text(if (x.tunePackSaved) stringResource(R.string.sync_tunepack_ok) else stringResource(R.string.sync_tunepack_none, x.outcome.tunePackError?.let { tunePackReason(it, x.outcome.tunePackErrorLocal) } ?: "—"), color = RxColors.muted, fontSize = 12.sp)
                        is io.github.xeno_hf.rex.Repo.SyncStatus.Failed -> Text(syncReason(x.reason), color = RxColors.danger, fontSize = 12.sp)
                        else -> Unit
                    }
                }
            }
            is Tuning.State.Failed -> Text(stringResource(R.string.tune_failed, s.reason), color = RxColors.danger, fontSize = 13.sp, lineHeight = 19.sp)
            else -> Unit
        }
        val busy = s == Tuning.State.Opening || s is Tuning.State.Reading
        Button(onClick = { repo.tuning.start() }, enabled = connected && !busy) { Text(stringResource(R.string.tune_start)) }
        if (!connected) Text(stringResource(R.string.tune_connect_first), color = RxColors.muted, fontSize = 12.sp)
        val packs = remember(s) { repo.tuning.packs() }
        Text(if (packs.isEmpty()) stringResource(R.string.tune_packs_none) else stringResource(R.string.tune_packs, packs.joinToString("、")), color = RxColors.muted, fontSize = 12.sp)
        OutlinedButton(onClick = { viewOnly = true }) { Text(stringResource(R.string.tune_view_only)) }
    }
}

/** 選取：左上到右下（含）。 */
private data class Sel(val r0: Int, val c0: Int, val r1: Int, val c1: Int) {
    val top get() = minOf(r0, r1); val bottom get() = maxOf(r0, r1)
    val left get() = minOf(c0, c1); val right get() = maxOf(c0, c1)
    fun has(r: Int, c: Int) = r in top..bottom && c in left..right
    fun cells() = (top..bottom).flatMap { r -> (left..right).map { c -> Cell(r, c) } }
    val count get() = (bottom - top + 1) * (right - left + 1)
}

private fun langTag() = when (Strings.lang) { Strings.Lang.ZH_HANT -> "zh-TW"; Strings.Lang.ZH_HANS -> "zh-CN"; Strings.Lang.JA -> "ja-JP"; else -> "en-US" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuneEditor(work: Tuning.Work) {
    val repo = LocalRepo.current
    // 「功能」＝照 XTune 調校選單的功能專頁；「全部表」＝XB 那種依群組列出所有表
    var features by remember { mutableStateOf(repo.platform.kv.getBoolean("tuneFeatures", true)) }
    var item by remember { mutableStateOf<CalItems.Item?>(null) }
    val host = rememberRxCalHost(work)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool(stringResource(R.string.tune_mode_features), true, on = features) { features = true; repo.platform.kv.putBoolean("tuneFeatures", true) }
            Tool(stringResource(R.string.tune_mode_tables), true, on = !features) { features = false; item = null; repo.platform.kv.putBoolean("tuneFeatures", false) }
        }
        Box(Modifier.weight(1f)) {
            val it = item
            when {
                !features -> TablesEditor(work, null, null)
                it == null -> FeatureMenu(work, host) { picked -> item = picked }
                else -> xb.calui.CalPage(host, it, rxCalColors()) { item = null }
            }
        }
    }
}

/** 一個功能專頁可以用的表：調校包裡有、ECU 上讀得到。 */
private fun itemTables(work: Tuning.Work, item: CalItems.Item): List<Int> =
    item.tables.filter { work.tune.definition(it) != null && work.tune.rows(it) != null && it !in work.unreadable }

/** 功能選單（XTune 的調校選單：群組 → 項目）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeatureMenu(work: Tuning.Work, host: xb.calui.CalHost, onPick: (CalItems.Item) -> Unit) {
    val lang = langTag()
    val items = remember(work) { CalItems.items(work.session.node, work.tune.file.modelId, work.tune.file.tableId.trim()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
        for (g in CalItems.Group.entries) {
            val inGroup = items.filter { it.group == g }
            if (inGroup.isEmpty()) continue
            Text(CalItems.name(g.key, lang), color = RxColors.brand, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (it in inGroup) {
                    val ready = host.usable(it)
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier.width(104.dp).background(RxColors.card, shape).border(1.dp, RxColors.line, shape)
                            .clickable(enabled = ready) { onPick(it) }.padding(10.dp),
                    ) {
                        Text(CalItems.name(it.key, lang), fontSize = 13.sp, color = if (ready) RxColors.text else RxColors.muted, maxLines = 2, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

/**
 * 表編輯器。[item]＝null 是「全部表」（依群組列出全部、可以選表）；有值就只在這個功能專頁的幾張表之間切換（XTune 的分頁）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TablesEditor(work: Tuning.Work, item: CalItems.Item?, onBack: (() -> Unit)?, ids: List<Int>? = null) {
    val repo = LocalRepo.current
    val tuning = repo.tuning
    val tune = work.tune
    val rev by tuning.revision.collectAsState()
    val states by work.live.states.collectAsState()
    // 鎖著等重讀的表（清除學習值之後）：復原可能動到它們，先不給按
    val held by work.live.held.collectAsState()
    val scope = rememberCoroutineScope()
    val lang = langTag()
    var showAll by remember { mutableStateOf(repo.platform.kv.getBoolean("tuneShowAll", false)) }
    val groups = remember(rev, showAll, lang) { TableList.groups(tune, work.opened.labels, lang, showAll, "") { it.absId !in work.unreadable } }
    val order = remember(groups, item, ids) { ids ?: item?.let { itemTables(work, it) } ?: groups.flatMap { g -> g.tables.map { it.def.absId } } }
    var selTable by remember(item, ids) { mutableStateOf(order.firstOrNull() ?: 1) }
    val def = tune.definition(selTable) ?: tune.definition(order.firstOrNull() ?: -1)
    var sel by remember(selTable) { mutableStateOf(Sel(0, 0, 0, 0)) }
    var range by remember { mutableStateOf(false) }
    var msg by remember(selTable) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }   // "set" "mul" "step" "restore" "stop"
    if (def == null) { Text(stringResource(R.string.tune_no_tables), modifier = Modifier.padding(16.dp)); return }
    val title = work.opened.labels.shortName(def.absId, lang) ?: def.name
    val writable = work.live.writable(def.absId)
    val rows = tune.rows(def.absId)!!

    fun done(r: EditResult, what: String) {
        when (r) {
            is EditResult.Done -> { if (r.changed > 0) tuning.edited(def.absId); msg = Strings.get(R.string.tune_edit_done, what, r.changed) }
            is EditResult.Refused -> msg = Strings.get(R.string.tune_edit_refused, what, r.reason)
        }
    }
    val stepKey = CalStep.key(def)
    val step = repo.platform.kv.getString(stepKey, null)?.toDoubleOrNull()?.takeIf { it > 0 } ?: CalStep.default(def.absId)

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (item != null && onBack != null) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ " + CalItems.name(item.key, lang), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        // 標題列：上一張／下一張、點標題選表
        val at = order.indexOf(def.absId)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = at > 0, onClick = { selTable = order[at - 1] }) { Text("‹", fontSize = 20.sp) }
            Column(Modifier.weight(1f).clickable(enabled = item == null && ids == null) { picking = true }) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${def.name}  #${def.absId}" + (if (def.unit.isNotBlank() && def.unit != "NA") "  ${def.unit}" else ""), color = RxColors.muted, fontSize = 11.sp, maxLines = 1)
            }
            TextButton(enabled = at in 0 until order.size - 1, onClick = { selTable = order[at + 1] }) { Text("›", fontSize = 20.sp) }
        }
        LiveBadge(states[def.absId], writable)

        // 格子（或開關、位元選項）
        val raw0 = rows[0][0]
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp)) {
            when {
                def.mark == "Option" && def.kind == TableKind.D1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    def.optionBits.split('-').forEachIndexed { bit, name ->
                        if (name.isBlank() || name == "_") return@forEachIndexed
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = (raw0 shr bit) and 1L == 1L, enabled = writable, onCheckedChange = { on ->
                                done(tune.setRaw(def.absId, Cell(0, 0), if (on) raw0 or (1L shl bit) else raw0 and (1L shl bit).inv()), name)
                            })
                            Text(name, fontSize = 13.sp)
                        }
                    }
                }
                def.adjustDisabled && def.kind == TableKind.D1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = raw0 != 0L, enabled = writable, onCheckedChange = { on -> done(tune.setRaw(def.absId, Cell(0, 0), if (on) 1 else 0), title) })
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (raw0 != 0L) R.string.tune_on else R.string.tune_off), fontSize = 14.sp)
                }
                else -> TuneGrid(tune, def, rev, sel, onSelect = { sel = it }) { r, c ->
                    sel = if (range) Sel(sel.r0, sel.c0, r, c) else Sel(r, c, r, c)
                }
            }
        }
        // 選取資訊與訊息
        val selText = remember(rev, sel, def.absId) {
            if (sel.bottom >= rows.size || sel.right >= rows[0].size) "" else {
                val raw = rows[sel.r1][sel.c1]
                (if (sel.count > 1) Strings.get(R.string.tune_sel_count, sel.count) + " · " else "") + cellText(def, raw) + " (raw $raw)"
            }
        }
        Text(msg ?: selText, color = if (msg != null) RxColors.text else RxColors.muted, fontSize = 11.5.sp, maxLines = 2)

        // 工具列（照 XTune 油圖頁）
        if (!(def.adjustDisabled && def.kind == TableKind.D1) && def.gain != 0f) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 步進：直接打數字（每張表各自記住，同 XTune），－／＋ 加減這麼多
                Tool("−", writable) { done(tune.adjust(def.absId, sel.cells(), AdjustOp.Add, -step), "−" + fmtNum(step)) }
                StepField(stepKey, fmtNum(step)) { v -> repo.platform.kv.putString(stepKey, v.toString()); tuning.revision.value++ }
                Tool("＋", writable) { done(tune.adjust(def.absId, sel.cells(), AdjustOp.Add, step), "＋" + fmtNum(step)) }
                Tool("×", writable) { dialog = "mul" }
                Tool("＝", writable) { dialog = "set" }
                if (def.kind != TableKind.D1) {
                    Tool("SMOOTH", writable) { done(tune.smooth(def.absId, sel.cells()), "SMOOTH") }
                    Tool("LINEAR", writable) { done(tune.linear(def.absId, sel.cells()), "LINEAR") }
                    Tool(stringResource(R.string.tune_range), true, on = range) { range = !range }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tool(stringResource(R.string.tune_undo), tune.canUndo && held.isEmpty()) { tune.undo()?.let { id -> tuning.edited(id); msg = Strings.get(R.string.tune_undone) } }
            Tool(stringResource(R.string.tune_read_all), writable || def.absId in held) {
                scope.launch {
                    msg = runCatching { tuning.readTable(def.absId) }.fold(
                        { ch -> Strings.get(if (ch) R.string.tune_read_changed else R.string.tune_read_same) },
                        { Strings.get(R.string.tune_read_failed, it.message ?: "") })
                }
            }
            Tool(stringResource(R.string.tune_write_all), writable) { scope.launch { tuning.writeTable(def.absId); msg = Strings.get(R.string.tune_write_sent) } }
            if (item == null && ids == null) Tool(stringResource(if (showAll) R.string.tune_hide_advanced else R.string.tune_show_advanced), true) {
                showAll = !showAll; repo.platform.kv.putBoolean("tuneShowAll", showAll)
            }
            // 嵌在功能專頁裡的表格只管這幾張表；還原整份、結束調校留在「全部表」
            if (ids == null) {
                Tool(stringResource(R.string.tune_restore), true) { dialog = "restore" }
                Tool(stringResource(R.string.tune_stop), true) { dialog = "stop" }
            }
        }
    }

    if (picking) TunePicker(groups, { selTable = it; picking = false }) { picking = false }
    when (dialog) {
        "mul" -> NumberDialog(stringResource(R.string.tune_mul_title), repo.platform.kv.getString("tuneMul", "1.05") ?: "1.05", { v ->
            repo.platform.kv.putString("tuneMul", fmtNum(v)); done(tune.adjust(def.absId, sel.cells(), AdjustOp.Multiply, v), "×" + fmtNum(v)); dialog = null
        }) { dialog = null }
        "set" -> NumberDialog(stringResource(R.string.tune_set_title), if (sel.bottom < rows.size && sel.right < rows[0].size) cellText(def, rows[sel.r1][sel.c1]) else "", { v ->
            done(tune.adjust(def.absId, sel.cells(), AdjustOp.Set, v), "＝" + fmtNum(v)); dialog = null
        }) { dialog = null }
        "restore" -> Confirm(stringResource(R.string.tune_restore), stringResource(R.string.tune_restore_confirm, work.backup?.name ?: "—"), {
            dialog = null; scope.launch {
                val r = tuning.restore()
                msg = when {
                    r == null -> ""
                    r.ok -> Strings.get(R.string.tune_restored, r.restored.size)
                    else -> Strings.get(R.string.tune_restore_failed, r.restored.size, r.failed.size, r.failed.entries.take(3).joinToString { "#${it.key} ${it.value}" })
                }
            }
        }) { dialog = null }
        "stop" -> Confirm(stringResource(R.string.tune_stop), stringResource(R.string.tune_stop_confirm), { dialog = null; tuning.stop() }) { dialog = null }
    }
}

@Composable
private fun LiveBadge(st: LiveTuning.TableState?, writable: Boolean) {
    val (text, color) = when {
        !writable && st !is LiveTuning.TableState.Refused -> stringResource(R.string.tune_state_locked) to RxColors.muted
        st == null || st == LiveTuning.TableState.Synced -> stringResource(R.string.tune_state_synced) to RxColors.accent
        st == LiveTuning.TableState.Pending -> stringResource(R.string.tune_state_pending) to RxColors.warn
        st == LiveTuning.TableState.Writing -> stringResource(R.string.tune_state_writing) to RxColors.warn
        st is LiveTuning.TableState.Refused -> stringResource(R.string.tune_state_refused, st.reason) to RxColors.danger
        st is LiveTuning.TableState.Mismatch -> stringResource(R.string.tune_state_mismatch, st.cells) to RxColors.danger
        st is LiveTuning.TableState.OutOfRange -> stringResource(R.string.tune_state_range, st.cells) to RxColors.danger
        st is LiveTuning.TableState.Failed -> stringResource(R.string.tune_state_failed, st.reason) to RxColors.danger
        else -> "" to RxColors.muted
    }
    Text(text, color = color, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
}

/** 格子：XTune 那種熱度色、軸、準星。點格子選取；開「範圍」再點就拉出一片。 */
@Composable
private fun TuneGrid(tune: Tune, def: TableDef, rev: Int, sel: Sel, onSelect: (Sel) -> Unit, onTap: (Int, Int) -> Unit) {
    val repo = LocalRepo.current
    val rows = tune.rows(def.absId)!!
    val ax = (tune.axisX(def) as? AxisLookup.Found)?.axis
    val ay = (tune.axisY(def) as? AxisLookup.Found)?.axis
    val nx = minOf(tune.effectiveColumns(def), def.storedColumns)
    val ny = minOf(tune.effectiveRows(def), def.storedRows)
    val phys = remember(rev, def.absId) { Array(ny) { r -> DoubleArray(nx) { c -> Convert.rawToPhysical(def, rows[r][c]) } } }
    val lo = phys.minOf { it.min() }; val hi = phys.maxOf { it.max() }

    val live by repo.live.collectAsState()
    val vars = Scope.vars(def)
    val px = vars?.first?.let { v -> live?.v(v) }?.let { v -> ax?.let { Scope.locate(v, it.values.copyOf(nx)) } }
    val py = vars?.second?.let { v -> live?.v(v) }?.let { v -> ay?.let { Scope.locate(v, it.values.copyOf(ny)) } }

    val cw = 50.dp; val ch = 28.dp; val head = 48.dp
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Box {
            Column {
                if (ax != null || nx > 1) Row {
                    Box(Modifier.size(head, ch))
                    for (x in 0 until nx) GridCell(ax?.values?.getOrNull(x)?.let(::fmtAxis) ?: "${x + 1}", cw, ch, RxColors.card, RxColors.muted, bold = px?.nearest == x) { onSelect(Sel(0, x, ny - 1, x)) }
                }
                for (y in 0 until ny) Row {
                    if (ay != null || ny > 1) GridCell(ay?.values?.getOrNull(y)?.let(::fmtAxis) ?: "${y + 1}", head, ch, RxColors.card, RxColors.muted, bold = py?.nearest == y) { onSelect(Sel(y, 0, y, nx - 1)) }
                    for (x in 0 until nx) {
                        val f = if (hi > lo) ((phys[y][x] - lo) / (hi - lo)).toFloat() else 0.5f
                        val mod = tune.isCellModified(def.absId, y, x)
                        val selected = sel.has(y, x)
                        GridCell(cellText(def, rows[y][x]), cw, ch, if (selected) RxColors.brand else heat(f), if (selected) Color.White else RxColors.text,
                            bold = mod, outline = (px != null || py != null) && (px == null || px.nearest == x) && (py == null || py.nearest == y)) { onTap(y, x) }
                    }
                }
            }
            if (px != null || py != null) Canvas(Modifier.matchParentSize()) {
                val cwPx = cw.toPx(); val chPx = ch.toPx(); val headPx = if (ay != null || ny > 1) head.toPx() else 0f
                val top = if (ax != null || nx > 1) chPx else 0f
                val cx = px?.let { headPx + (it.pos * cwPx).toFloat() }
                val cy = py?.let { top + (it.pos * chPx).toFloat() }
                val c = Color(0xFFFF3B30)
                if (cx != null) drawLine(c, Offset(cx, top), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
                if (cy != null) drawLine(c, Offset(headPx, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx())
                if (cx != null && cy != null) drawCircle(c, radius = 6.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun GridCell(text: String, w: Dp, h: Dp, bg: Color, fg: Color, bold: Boolean = false, outline: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(w, h).background(bg).border(if (outline) 2.dp else 0.5.dp, if (outline) Color(0xFFFF3B30) else RxColors.line).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, fontFamily = Digits, color = fg, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun Tool(label: String, enabled: Boolean, on: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.background(if (on) RxColors.brand else RxColors.card, shape).border(1.dp, RxColors.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontFamily = Digits, color = if (!enabled) RxColors.muted else if (on) Color.White else RxColors.text, maxLines = 1)
    }
}

/** 步進欄：可以直接打數字；正數才記下來。 */
@Composable
private fun StepField(key: String, value: String, onValue: (Double) -> Unit) {
    // 換表才重設文字；打字中（例如「1.」）不被格式化後的值蓋掉
    var text by remember(key) { mutableStateOf(value) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.background(RxColors.card, shape).border(1.dp, RxColors.brand, shape).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.tune_step), color = RxColors.muted, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { t -> text = t.filter { it.isDigit() || it == '.' }.take(8); text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = androidx.compose.ui.text.TextStyle(color = RxColors.text, fontSize = 14.sp, fontFamily = Digits),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(RxColors.brand),
            modifier = Modifier.width(56.dp),
        )
    }
}

@Composable
private fun NumberDialog(title: String, initial: String, onOk: (Double) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val v = text.trim().toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }.take(14) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
        confirmButton = { TextButton(enabled = v != null && v.isFinite(), onClick = { onOk(v!!) }) { Text(stringResource(R.string.tune_ok)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.map_close)) } },
    )
}

@Composable
private fun Confirm(title: String, text: String, onGo: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text, fontSize = 13.sp, lineHeight = 19.sp) },
        confirmButton = { TextButton(onClick = onGo) { Text(stringResource(R.string.tune_ok)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.map_close)) } },
    )
}

@Composable
private fun TunePicker(groups: List<xb.tune.GroupEntry>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.map_close)) } },
        title = { Text(stringResource(R.string.map_pick)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.map_search)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val needle = q.trim().lowercase()
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (g in groups) {
                        val hits = g.tables.filter { t -> needle.isEmpty() || t.title.lowercase().contains(needle) || t.def.name.lowercase().contains(needle) || t.def.absId.toString() == needle }
                        if (hits.isEmpty()) continue
                        Text(g.title, color = RxColors.brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                        for (t in hits) Row(Modifier.fillMaxWidth().clickable { onPick(t.def.absId) }.background(RxColors.card, RoundedCornerShape(6.dp)).padding(8.dp)) {
                            Text("#${t.def.absId}", color = RxColors.muted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                            Text(t.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (t.level > 1) Text("Lv${t.level}", color = RxColors.warn, fontSize = 10.sp)
                        }
                    }
                }
            }
        },
    )
}

private fun cellText(def: TableDef, raw: Long): String =
    if (def.gain == 0f) raw.toString()
    else rx.protocol.Fmt.fixed(if (Convert.isOutOfRange(def, raw)) Convert.rawToPhysicalExact(def, raw) else Convert.rawToPhysical(def, raw), def.decimals.coerceIn(0, 6))
        .let { t -> if (t.startsWith("-") && t.drop(1).all { it == '0' || it == '.' }) t.drop(1) else t }  // 不顯示 -0

private fun fmtNum(v: Double): String = rx.protocol.Fmt.fixed(v, 4).trimEnd('0').trimEnd('.').ifEmpty { "0" }
private fun fmtAxis(v: Float): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else rx.protocol.Fmt.fixed(v.toDouble(), 2).trimEnd('0').trimEnd('.')

private fun heat(f: Float): Color {
    val c = when {
        f < 0.5f -> lerp(Color(0xFF2F6BFF), Color(0xFF2BB673), f * 2)
        else -> lerp(Color(0xFF2BB673), Color(0xFFE5484D), (f - 0.5f) * 2)
    }
    return lerp(RxColors.card, c, 0.42f)
}
