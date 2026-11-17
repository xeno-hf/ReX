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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import rx.session.MapDefs
import rx.session.MapScope
import rx.session.SessionState

/**
 * 油圖頁（只看不改）：定義從 XenoBrex 同步來（或手動匯入），表的內容連上 ECU 讀，
 * 有準星的表即時標出引擎目前的位置（算法同 XB／XTune）。
 */
@Composable
fun MapScreen() {
    val repo = LocalRepo.current
    val all by repo.mapDefs.collectAsState()
    val state by repo.state.collectAsState()
    val loggedIn by repo.ecuLoggedIn.collectAsState()
    val defs = remember(all, state) { repo.currentMapDefs() }
    val scope = rememberCoroutineScope()
    var importErr by remember { mutableStateOf<String?>(null) }
    val pick = rememberFilePicker { _, bytes -> scope.launch { importErr = repo.saveMapDefs(bytes) } }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (defs == null || defs.tables.isEmpty()) {
            Text(stringResource(R.string.map_none), color = RxColors.muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { pick() }) { Text(stringResource(R.string.map_import)) }
            importErr?.let { Text(stringResource(R.string.map_import_bad, it), color = RxColors.danger, fontSize = 12.sp) }
            return@Column
        }
        var sel by remember(defs) { mutableStateOf(defs.tables.firstOrNull()?.id) }
        var choosing by remember { mutableStateOf(false) }
        val t = defs.table(sel ?: -1) ?: defs.tables.first()
        val lang = when (Strings.lang) { Strings.Lang.ZH_HANT -> "zh-TW"; Strings.Lang.ZH_HANS -> "zh-CN"; Strings.Lang.JA -> "ja-JP"; else -> "en-US" }
        val at = defs.tables.indexOf(t)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = at > 0, onClick = { sel = defs.tables[at - 1].id }) { Text("‹", fontSize = 20.sp) }
            Column(Modifier.weight(1f).clickable { choosing = true }) {
                Text(t.title(lang), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.name}  #${t.id}" + (if (t.unit.isNotBlank() && t.unit != "NA") "  ${t.unit}" else ""), color = RxColors.muted, fontSize = 11.sp)
            }
            TextButton(enabled = at < defs.tables.size - 1, onClick = { sel = defs.tables[at + 1].id }) { Text("›", fontSize = 20.sp) }
        }
        Spacer(Modifier.height(6.dp))
        // 讀表不必先登入（唯讀，同 ReX 讀型號）；讀不到會顯示 ECU 的回覆
        MapTable(defs, t, state == SessionState.Streaming || (loggedIn && state !is SessionState.Idle && state !is SessionState.Stopped))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.map_source, defs.mapType.ifEmpty { defs.platform }, defs.c?.toString() ?: "*", defs.tables.size), color = RxColors.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { pick() }) { Text(stringResource(R.string.map_import), fontSize = 12.sp) }
        }
        importErr?.let { Text(stringResource(R.string.map_import_bad, it), color = RxColors.danger, fontSize = 12.sp) }
        if (choosing) TablePicker(defs, lang, { sel = it; choosing = false }) { choosing = false }
    }
}

/** 一張表：讀 ECU 的內容、畫格子、標準星。 */
@Composable
private fun MapTable(defs: MapDefs, t: MapDefs.Table, connected: Boolean) {
    val repo = LocalRepo.current
    var rows by remember(t.id) { mutableStateOf<List<LongArray>?>(null) }
    var axisRows by remember(t.id) { mutableStateOf<Map<Int, LongArray>>(emptyMap()) }
    var err by remember(t.id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val loggedIn by repo.ecuLoggedIn.collectAsState()
    LaunchedEffect(t.id, connected, reload) {
        if (!connected) return@LaunchedEffect
        err = null
        val ax = HashMap<Int, LongArray>()
        for (a in listOfNotNull(t.x, t.y).filterIsInstance<MapDefs.Axis.Dynamic>()) {
            val at = defs.axes[a.table] ?: continue
            repo.readMapRows(at.id, 1, at.cols, at.bytes).onSuccess { ax[at.id] = it.first() }.onFailure { err = it.message }
        }
        axisRows = ax
        repo.readMapRows(t.id, t.rows, t.cols, t.bytes).onSuccess { rows = it }.onFailure { err = it.message ?: "read failed" }
    }
    val xAxis = t.x?.let { defs.axisValues(it) { id -> axisRows[id] } }
    val yAxis = t.y?.let { defs.axisValues(it) { id -> axisRows[id] } }
    val r = rows
    if (r == null) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            Text(
                when { !connected -> stringResource(R.string.map_connect); err != null && !loggedIn -> stringResource(R.string.map_login_failed); err != null -> stringResource(R.string.map_read_failed, err ?: ""); else -> stringResource(R.string.map_reading) },
                color = if (err != null) RxColors.danger else RxColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
        if (err != null) OutlinedButton(onClick = { reload++ }) { Text(stringResource(R.string.map_retry)) }
        return
    }
    // 顯示的格數＝軸的刻度數（同 XB：動態軸取到不再遞增為止）；沒有軸的方向照存的格數
    val cols = (xAxis?.size ?: t.cols).coerceIn(1, t.cols)
    val nRows = (yAxis?.size ?: t.rows).coerceIn(1, t.rows)
    val values = remember(r, cols, nRows) { List(nRows) { y -> DoubleArray(cols) { x -> t.scale.physical(r[y][x]) } } }
    val lo = values.minOf { it.min() }
    val hi = values.maxOf { it.max() }

    val live by repo.live.collectAsState()
    val l = live
    val vx = t.scopeX?.let { l?.v(it) }
    val vy = t.scopeY?.let { l?.v(it) }
    val px = if (vx != null && xAxis != null) MapScope.locate(vx, xAxis.copyOf(cols)) else null
    val py = if (vy != null && yAxis != null) MapScope.locate(vy, yAxis.copyOf(nRows)) else null

    val cw = 46.dp; val ch = 24.dp; val head = 46.dp
    Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
        Box {
            Column {
                Row {
                    Box(Modifier.size(head, ch))
                    for (x in 0 until cols) Cell(xAxis?.getOrNull(x)?.let { fmtAxis(it) } ?: "${x + 1}", cw, ch, RxColors.card, RxColors.muted, bold = px?.nearest == x)
                }
                for (y in 0 until nRows) Row {
                    Cell(yAxis?.getOrNull(y)?.let { fmtAxis(it) } ?: "${y + 1}", head, ch, RxColors.card, RxColors.muted, bold = py?.nearest == y)
                    for (x in 0 until cols) {
                        val v = values[y][x]
                        val f = if (hi > lo) ((v - lo) / (hi - lo)).toFloat() else 0.5f
                        val here = (px == null || px.nearest == x) && (py == null || py.nearest == y) && (px != null || py != null)
                        Cell(fmt(v, t.scale.dec), cw, ch, heat(f), RxColors.text, bold = here, outline = here)
                    }
                }
            }
            if (px != null || py != null) {
                Canvas(Modifier.matchParentSize()) {
                    val cwPx = cw.toPx(); val chPx = ch.toPx(); val headPx = head.toPx()
                    val cx = px?.let { headPx + (it.pos * cwPx).toFloat() }
                    val cy = py?.let { chPx + (it.pos * chPx).toFloat() }
                    val c = Color(0xFFFF3B30)
                    if (cx != null) drawLine(c, Offset(cx, chPx), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
                    if (cy != null) drawLine(c, Offset(headPx, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx())
                    if (cx != null && cy != null) drawCircle(c, radius = 6.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    if (t.scopeX != null || t.scopeY != null) {
        val reg = l?.layout?.registry
        val parts = listOfNotNull(t.scopeX?.let { it to vx }, t.scopeY?.let { it to vy }).joinToString("  ×  ") { (id, v) ->
            "${reg?.values?.get(id)?.label ?: "#$id"} ${v?.let { fmt(it, 1) } ?: "—"}"
        }
        Text(if (l == null) stringResource(R.string.map_no_live) else parts, color = RxColors.muted, fontSize = 11.5.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.map_shape, cols, nRows) + if (cols != t.cols || nRows != t.rows) "  " + stringResource(R.string.map_stored, t.cols, t.rows) else "",
            color = RxColors.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { rows = null; reload++ }) { Text(stringResource(R.string.map_reread), fontSize = 12.sp) }
    }
}

@Composable
private fun Cell(text: String, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, bg: Color, fg: Color, bold: Boolean = false, outline: Boolean = false) {
    Box(
        Modifier.size(w, h).background(bg).border(if (outline) 2.dp else 0.5.dp, if (outline) Color(0xFFFF3B30) else RxColors.line),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.5.sp, fontFamily = Digits, color = fg, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** 低＝藍、中＝綠、高＝紅，疊在卡片底色上（淡一點，字才看得清）。 */
private fun heat(f: Float): Color {
    val c = when {
        f < 0.5f -> lerp(Color(0xFF2F6BFF), Color(0xFF2BB673), f * 2)
        else -> lerp(Color(0xFF2BB673), Color(0xFFE5484D), (f - 0.5f) * 2)
    }
    return lerp(RxColors.card, c, 0.42f)
}

private fun fmt(v: Double, dec: Int): String = rx.protocol.Fmt.fixed(v, dec.coerceIn(0, 4))
private fun fmtAxis(v: Float): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else rx.protocol.Fmt.fixed(v.toDouble(), 2).trimEnd('0').trimEnd('.')

@Composable
private fun TablePicker(defs: MapDefs, lang: String, onPick: (Int) -> Unit, onClose: () -> Unit) {
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
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (t in defs.tables.filter { tb -> needle.isEmpty() || tb.title(lang).lowercase().contains(needle) || tb.name.lowercase().contains(needle) || tb.id.toString() == needle }) {
                        Row(Modifier.fillMaxWidth().clickable { onPick(t.id) }.background(RxColors.card, RoundedCornerShape(6.dp)).padding(8.dp)) {
                            Text("#${t.id}", color = RxColors.muted, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                            Text(t.title(lang), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
    )
}
