package xb.calui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xb.link.LiveTuning
import xb.tune.EditResult

/** 頁面共用的狀態：最後一則訊息（寫入被拒、讀寫結果）。 */
class PageMsg { var text by mutableStateOf<String?>(null) }

@Composable
fun t(host: CalHost, key: String): String = CalText.get(key, host.lang)

/**
 * 功能專頁的外框（XTune寫入／讀取鈕）：
 * 返回、標題、全讀、全寫、監控列、訊息列；[tables] 是這頁的表（全讀／全寫的對象，也用來顯示寫入狀態）。
 */
@Composable
fun CalFrame(
    host: CalHost,
    title: String,
    tables: List<Int>,
    monitor: List<Int>,
    onBack: () -> Unit,
    msg: PageMsg = remember { PageMsg() },
    scroll: Boolean = true,
    /** 頁面除了監控列另外要用的即時變數。 */
    watch: List<Int> = emptyList(),
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalCalColors.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val present = tables.filter { host.has(it) }
    val vars = (monitor + watch).distinct()
    androidx.compose.runtime.DisposableEffect(vars) { host.watch(vars); onDispose { host.watch(emptyList()) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("calBack")) { Text("‹", fontSize = 22.sp, color = c.brand) }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (host.online && present.isNotEmpty()) {
                Pill(t(host, "cal_readall"), !busy, Modifier.testTag("calReadAll")) {
                    busy = true
                    scope.launch { msg.text = host.readTables(present) ?: CalText.format("cal_read_done", host.lang, present.size); busy = false }
                }
                Spacer(Modifier.width(6.dp))
                Pill(t(host, "cal_writeall"), !busy, Modifier.testTag("calWriteAll")) {
                    busy = true
                    scope.launch { msg.text = host.writeTables(present.filter { host.writable(it) }) ?: CalText.get("cal_write_done", host.lang); busy = false }
                }
            }
        }
        if (!host.online) Text(t(host, "cal_offline"), color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp))
        if (monitor.isNotEmpty() && host.online) MonitorStrip(host, monitor)
        header()
        val body = Modifier.weight(1f).fillMaxWidth()
        Column(if (scroll) body.verticalScroll(rememberScrollState()) else body, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(2.dp))
            content()
            Spacer(Modifier.height(8.dp))
        }
        msg.text?.let { Text(it, color = c.muted, fontSize = 11.5.sp, maxLines = 3, modifier = Modifier.padding(vertical = 4.dp)) }
    }
}

/** XTune 頁頂的 4 格即時值。 */
@Composable
fun MonitorStrip(host: CalHost, vars: List<Int>) {
    val c = LocalCalColors.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(200); tick++ } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in vars) {
            val value = remember(tick) { host.live(v) }
            Column(Modifier.weight(1f).background(c.card, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(host.varLabel(v), color = c.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value?.let { num(it, 1) } ?: "—", color = c.text, fontSize = 16.sp, fontFamily = c.digits, maxLines = 1)
            }
        }
    }
}

@Composable
fun Pill(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, on: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    val c = LocalCalColors.current
    val shape = RoundedCornerShape(16.dp)
    val bg = when { on -> color ?: c.brand; else -> c.card }
    Box(
        modifier.heightIn(min = 34.dp).background(bg, shape).border(1.dp, if (on) bg else c.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = when { !enabled -> c.muted; on -> c.onBrand; else -> c.text }, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** 一張卡片（XTune 頁裡的一個區塊）。 */
@Composable
fun Card(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalCalColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().background(c.card, shape).border(1.dp, c.line, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, color = c.brand, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        content()
    }
}

/** 表的寫入狀態小字（停手後送出／寫入中／被拒…）。 */
@Composable
fun TableState(host: CalHost, id: Int) {
    val c = LocalCalColors.current
    if (!host.online) return
    val (text, color) = when (val s = host.status(id)) {
        null, LiveTuning.TableState.Synced -> return
        LiveTuning.TableState.Pending -> t(host, "cal_pending") to c.warn
        LiveTuning.TableState.Writing -> t(host, "cal_writing") to c.warn
        is LiveTuning.TableState.Refused -> CalText.format("cal_refused", host.lang, s.reason) to c.danger
        is LiveTuning.TableState.Mismatch -> CalText.format("cal_mismatch", host.lang, s.cells) to c.danger
        is LiveTuning.TableState.OutOfRange -> CalText.format("cal_outofrange", host.lang, s.cells) to c.danger
        is LiveTuning.TableState.Failed -> CalText.format("cal_failed", host.lang, s.reason) to c.danger
    }
    Text(text, color = color, fontSize = 11.sp, maxLines = 2)
}

fun report(host: CalHost, msg: PageMsg, r: EditResult) {
    if (r is EditResult.Refused) msg.text = CalText.format("cal_edit_refused", host.lang, r.reason)
}

/** 一列：左標籤、右內容。 */
@Composable
fun LabeledRow(label: String, sub: String? = null, content: @Composable RowScope.() -> Unit) {
    val c = LocalCalColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = c.text, fontSize = 14.sp)
            if (sub != null) Text(sub, color = c.muted, fontSize = 11.sp)
        }
        content()
    }
}

/** 開關列（XTune 的 ON／OFF 列）。[onText]／[offText] 給像「燃油／點火」這種二選一。 */
@Composable
fun SwitchRow(host: CalHost, label: String, on: Boolean, enabled: Boolean, onText: String? = null, offText: String? = null, tag: String? = null, onChange: (Boolean) -> Unit) {
    val c = LocalCalColors.current
    LabeledRow(label) {
        if (onText != null && offText != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(offText, enabled, on = !on, modifier = tag?.let { Modifier.testTag("$it.off") } ?: Modifier) { if (on) onChange(false) }
                Pill(onText, enabled, on = on, modifier = tag?.let { Modifier.testTag("$it.on") } ?: Modifier) { if (!on) onChange(true) }
            }
        } else {
            Text(t(host, if (on) "cal_on" else "cal_off"), color = if (on) c.brand else c.muted, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
            Switch(
                checked = on, enabled = enabled, onCheckedChange = onChange,
                modifier = tag?.let { Modifier.testTag(it) } ?: Modifier,
                colors = SwitchDefaults.colors(checkedTrackColor = c.brand, checkedThumbColor = c.onBrand),
            )
        }
    }
}

/** 多選一（XTune 的循環按鈕或幾顆彩色鈕）。 */
@Composable
fun ChoiceRow(label: String, options: List<Pair<Long, String>>, current: Long, enabled: Boolean, tag: String? = null, sub: String? = null, onPick: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LabeledRow(label, sub) {}
        FlowRowSimple {
            for ((v, name) in options) Pill(name, enabled, on = v == current, modifier = tag?.let { Modifier.testTag("$it.$v") } ?: Modifier) { if (v != current) onPick(v) }
        }
    }
}

/** 簡單的換行排列（不用 ExperimentalLayoutApi，XB 與 ReX 的 Compose 版本不同）。 */
@Composable
fun FlowRowSimple(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val ps = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0; var y = 0; var lineH = 0
        val pos = ps.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) { x = 0; y += lineH + gap; lineH = 0 }
            val at = x to y
            x += p.width + gap; lineH = maxOf(lineH, p.height)
            at
        }
        layout(constraints.maxWidth, y + lineH) { ps.forEachIndexed { i, p -> p.place(pos[i].first, pos[i].second) } }
    }
}

/**
 * XTune：標籤、−、數值（點了可直接打）、＋、可拖的長條。改了就寫進調校檔，由宿主停手後送。
 * [step]：加減一次的量（顯示值）；[lo]／[hi]：比定義檔更窄的範圍（例如快排兩個門檻互鎖）；[text]：特殊顯示（列舉表）。
 */
@Composable
fun AdjustRow(
    host: CalHost,
    d: D1,
    label: String = host.tableTitle(d.id),
    step: Double? = null,
    lo: Double? = null,
    hi: Double? = null,
    unit: String? = null,
    text: ((Double) -> String)? = null,
    bar: Boolean = true,
    msg: PageMsg? = null,
    tag: String? = null,
) {
    val c = LocalCalColors.current
    if (!d.present) { MissingRow(host, label, d.id); return }
    val def = d.def!!
    val min = maxOf(minOf(def.min, def.max), lo ?: Double.NEGATIVE_INFINITY)
    val max = minOf(maxOf(def.min, def.max), hi ?: Double.POSITIVE_INFINITY)
    // 步進＝表的 step，轉速限制類（absid 20、136）固定 50；至少一個 raw 單位，免得按了沒變
    val res = kotlin.math.abs(xb.tune.Convert.rawToPhysical(def, 1) - xb.tune.Convert.rawToPhysical(def, 0))
    val st = maxOf(step ?: if (d.id == 20 || d.id == 136) 50.0 else def.slew.takeIf { it > 0 } ?: 1.0, res)
    val digits = def.decimals.coerceIn(0, 4)
    val en = d.writable || !host.online
    var typing by remember { mutableStateOf(false) }
    val v = d.value
    fun set(x: Double) { val r = d.set(x.coerceIn(min, max)); msg?.let { report(host, it, r) } }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
            val u = unit ?: def.unit.takeIf { it.isNotBlank() && it != "NA" && it != "_" }
            if (u != null) Text(u, color = c.muted, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("−", en && v > min, tag?.let { Modifier.testTag("$it.dec") } ?: Modifier) { set(v - st) }
            Box(Modifier.widthIn(min = 96.dp).weight(1f).clickable(enabled = en) { typing = true }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(text?.invoke(v) ?: num(v, digits), fontFamily = c.digits, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = tag?.let { Modifier.testTag("$it.value") } ?: Modifier)
            }
            Pill("＋", en && v < max, tag?.let { Modifier.testTag("$it.inc") } ?: Modifier) { set(v + st) }
        }
        if (bar && max > min && max - min < 1e7) {
            Slider(
                value = v.coerceIn(min, max).toFloat(), enabled = en,
                onValueChange = { x -> set(Math2.snap(x.toDouble(), min, st)) },
                valueRange = min.toFloat()..max.toFloat(),
                colors = SliderDefaults.colors(thumbColor = c.brand, activeTrackColor = c.brand),
                modifier = Modifier.height(28.dp),
            )
        }
        Row {
            Text(CalText.format("cal_range", host.lang, text?.invoke(min) ?: num(min, digits), text?.invoke(max) ?: num(max, digits)), color = c.muted, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
            TableState(host, d.id)
        }
    }
    if (typing) NumberDialog(host, label, num(v, digits), { x -> set(x); typing = false }) { typing = false }
}

object Math2 {
    /** 拖條：照步進對齊（從下限算起）。 */
    fun snap(x: Double, min: Double, step: Double): Double = if (step <= 0) x else min + kotlin.math.round((x - min) / step) * step
}

@Composable
fun MissingRow(host: CalHost, label: String, id: Int) {
    val c = LocalCalColors.current
    LabeledRow(label) { Text(CalText.format("cal_missing", host.lang, id), color = c.muted, fontSize = 11.sp) }
}

@Composable
fun NumberDialog(host: CalHost, title: String, initial: String, onOk: (Double) -> Unit, onClose: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { OutlinedTextField(s, { s = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("calNumber")) },
        confirmButton = { TextButton(onClick = { s.trim().toDoubleOrNull()?.let(onOk) }, modifier = Modifier.testTag("calNumberOk")) { Text(t(host, "cal_ok")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(t(host, "cal_cancel")) } },
    )
}

/** 會讓 ECU 做事的按鈕（命令、學習）：先確認。 */
@Composable
fun ConfirmDialog(host: CalHost, title: String, text: String, onGo: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onGo(); onClose() }, modifier = Modifier.testTag("calConfirmGo")) { Text(t(host, "cal_ok")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(t(host, "cal_cancel")) } },
    )
}

/** 分頁列（XTune 的 ◀ 名稱 ▶）。 */
@Composable
fun TabBar(names: List<String>, index: Int, onPick: (Int) -> Unit) {
    val c = LocalCalColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPick(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("calTabPrev")) { Text("◀", color = if (index > 0) c.brand else c.line) }
        Text(names.getOrElse(index) { "" }, color = c.danger, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        TextButton(onClick = { onPick(index + 1) }, enabled = index < names.size - 1, modifier = Modifier.testTag("calTabNext")) { Text("▶", color = if (index < names.size - 1) c.brand else c.line) }
    }
    if (names.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        names.indices.forEach { i -> Box(Modifier.padding(2.dp).size(6.dp).background(if (i == index) c.brand else c.line, RoundedCornerShape(3.dp)).clickable { onPick(i) }) }
    }
}

/** 說明文字（灰色）。 */
@Composable
fun Note(text: String) {
    val c = LocalCalColors.current
    Text(text, color = c.muted, fontSize = 11.5.sp)
}

/** 警告文字（黃色框）。 */
@Composable
fun Warn(text: String) {
    val c = LocalCalColors.current
    Text(text, color = c.warn, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().border(1.dp, c.warn, RoundedCornerShape(8.dp)).padding(8.dp))
}
