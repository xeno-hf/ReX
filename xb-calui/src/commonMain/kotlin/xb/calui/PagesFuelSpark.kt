package xb.calui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xb.tune.CalItems
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * 回火設定：5 個分頁，每頁一個開關＋幾個數值。
 * 開關在非 X2 是表 42 的一個位元（分頁序號＝位元），X2 是 1569–1573 五張獨立 0／1 表。
 * 關閉油門那頁 C 版本 ≥12 多一張 1416。XTune 用雷達圖拖球改值；這裡每個值一列（−／＋、拖條），寫入一樣停手 1.2 秒才送。
 * Mini XX 的鎖頭（功能授權）不在這裡判斷：沒授權 ECU 會拒寫，表會鎖住並顯示原因。
 */
@Composable
internal fun BackfirePage(host: CalHost, title: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val msg = remember { PageMsg() }
    var tab by remember { mutableStateOf(0) }
    // 分頁順序照選單陣列：Open、Close、IdleLope、IdleLopePlus、QuickShift（bit 0、1、2、4、3）
    val bits = listOf(0, 1, 2, 4, 3)
    val titles = listOf("CalTitle_OpenTps_BackFire", "CalTitle_CloseTps_BackFire", "CalTitle_IdleLope_BackFire", "CalTitle_IdleLopePlus_BackFire", "CalTitle_QS_BackFire")
    val x2Option = mapOf(0 to 1569, 1 to 1570, 2 to 1571, 4 to 1572, 3 to 1573)
    val closeTables = if ((host.cCode ?: 99) < 12 && !host.isX2) listOf(45, 46, 894) else listOf(45, 46, 894, 1416)
    val values = listOf(listOf(43, 44, 51, 52, 903), closeTables, listOf(47, 48, 49), listOf(1140, 1136, 1141, 1134), emptyList())
    val bit = bits[tab]
    val optionId = if (host.isX2) x2Option.getValue(bit) else 42
    val all = (listOf(optionId) + values[tab])
    CalFrame(host, title, all, MONITOR_DEFAULT, onBack, msg, header = {
        TabBar(titles.map { CalText.get(it, host.lang) }, tab) { tab = it.coerceIn(0, 4) }
    }) {
        val opt = D1(host, optionId)
        Card {
            if (!opt.present) MissingRow(host, CalText.get(titles[tab], host.lang), optionId)
            else {
                val on = if (host.isX2) opt.raw != 0L else opt.bit(bit)
                SwitchRow(host, CalText.get(titles[tab], host.lang), on, opt.writable || !host.online, tag = "bf.option") { want ->
                    if (host.isX2) report(host, msg, opt.setRaw(if (want) 1 else 0))
                    else Interlock.set(host, opt, if (want) opt.raw or (1L shl bit) else opt.raw and (1L shl bit).inv(), msg, scope)
                }
                TableState(host, optionId)
            }
        }
        for (id in values[tab]) {
            Card { AdjustRow(host, D1(host, id), msg = msg, tag = "bf.$id") }
        }
    }
}

/**
 * 自動暖車：表 1065 選模式（0 一般、1–6 曲目），
 * START／STOP 不是寫表，是寫命令物件 0x200E（值 1＝開始、0x80＝停止）。XTune 按了就送、沒有回饋；
 * 這裡先確認一次，並顯示 ECU 有沒有收下。
 */
@Composable
internal fun AutoWarmupPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Long?>(null) }
    val c = LocalCalColors.current
    CalFrame(host, CalText.get("Warmup_PageTitle", host.lang).takeIf { CalText.has("Warmup_PageTitle") } ?: title, listOf(1065), listOf(26, 34, 45, 33), onBack, msg) {
        Card { ValueControl(host, D1(host, 1065), msg, label = host.tableTitle(1065)) }
        if (host.online) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(CalText.get("Warmup_Start", host.lang), modifier = Modifier.weight(1f).testTag("warmup.start"), on = true, color = c.ok) { ask = 1L }
                Pill(CalText.get("Warmup_Stop", host.lang), modifier = Modifier.weight(1f).testTag("warmup.stop"), on = true, color = c.danger) {
                    scope.launch { msg.text = host.command(0x200E, 0, 0x80, 1) ?: CalText.get("cal_cmd_sent", host.lang) }
                }
            }
            Note(CalText.get("cal_warmup_note", host.lang))
        }
    }
    ask?.let { v ->
        ConfirmDialog(host, CalText.get("Warmup_Start", host.lang), CalText.get("cal_warmup_confirm", host.lang), {
            scope.launch { msg.text = host.command(0x200E, 0, v, 1) ?: CalText.get("cal_cmd_sent", host.lang) }
        }) { ask = null }
    }
}

/**
 * 窄域閉迴路：表 60 開關、表 842 供油狀態（0 濃、1 適中、2 稀）、
 * 「閉迴路作用中」燈＝即時變數 1 的 bit1（Fuel.NBO2_CL_En）。開 60 時照 XTune 關掉寬域閉迴路與自動調校。
 */
@Composable
internal fun NbClosedLoopPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val c = LocalCalColors.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(200); tick++ } }
    CalFrame(host, title, listOf(60, 842), listOf(26, 34, 46, 22), onBack, msg) {
        Card { ValueControl(host, D1(host, 60), msg) }
        if (host.online) {
            val active = remember(tick) { host.live(1)?.toLong()?.let { (it shr 1) and 1L == 1L } }
            Text(
                CalText.get("AFR_CloseLoopReady", host.lang),
                color = if (active == true) Color.White else c.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(if (active == true) Color(0xFF7BB241) else c.card, RoundedCornerShape(8.dp)).padding(8.dp),
            )
        }
        val d = D1(host, 842)
        Card(title = host.tableTitle(842)) {
            if (!d.present) MissingRow(host, host.tableTitle(842), 842)
            else {
                val en = d.writable || !host.online
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2L to ("AFR_Learn" to Color(0xFFE53935)), 1L to ("AFR_Medium" to Color(0xFFFB8C00)), 0L to ("AFR_Rich" to Color(0xFF43A047))).forEach { (v, p) ->
                        Pill(CalText.get(p.first, host.lang), en, Modifier.weight(1f).testTag("nb.afr.$v"), on = d.raw == v, color = p.second) { report(host, msg, d.setRaw(v)) }
                    }
                }
                TableState(host, 842)
            }
        }
    }
}

/**
 * 噴油正時：表 61 用一般表格編輯器，
 * 另有 720° 曲軸角示意圖（壓縮／進氣／排氣／動力四區、即時噴油脈衝位置、進排氣門開閉示意滑桿，滑桿不寫表）。
 * 差異：XTune 這頁改格子要按全寫才送；這裡跟其他表一樣停手 1.2 秒就送。
 */
@Composable
internal fun InjAnglePage(host: CalHost, item: CalItems.Item, title: String, onBack: () -> Unit) {
    var showAngle by remember { mutableStateOf(false) }
    CalFrame(host, title, emptyList(), listOf(26, 55, 35, 30), onBack, scroll = false, header = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Pill(CalText.get("cal_table", host.lang), on = !showAngle, modifier = Modifier.testTag("inj.table")) { showAngle = false }
            Pill(CalText.get("cal_angle_chart", host.lang), on = showAngle, modifier = Modifier.testTag("inj.angle")) { showAngle = true }
        }
    }) {
        if (showAngle) AngleChart(host, Modifier.fillMaxWidth().weight(1f))
        else host.TableEditor(listOf(61).filter { host.has(it) }, Modifier.fillMaxSize())
    }
}

@Composable
private fun AngleChart(host: CalHost, modifier: Modifier) {
    val c = LocalCalColors.current
    var ex by remember { mutableStateOf(280f..600f) }
    var intake by remember { mutableStateOf(130f..440f) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(200); tick++ } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val rpm = remember(tick) { host.live(26) }
        val pw = remember(tick) { host.live(35) }
        val angle = remember(tick) { host.live(30) }
        val single = remember(tick) { host.live(-195)?.let { it == 0.0 } ?: false }
        Box(Modifier.fillMaxWidth().height(220.dp).background(c.card, RoundedCornerShape(8.dp))) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val w = size.width; val h = size.height
                fun x(deg: Float) = deg / 720f * w
                val zones = listOf(Color(0x332196F3), Color(0x3343A047), Color(0x33FB8C00), Color(0x33E53935))
                zones.forEachIndexed { i, col -> drawRect(col, Offset(x(i * 180f), 0f), Size(x(180f), h)) }
                for (d in 0..720 step 180) drawLine(c.line, Offset(x(d.toFloat()), 0f), Offset(x(d.toFloat()), h))
                fun bell(r: ClosedFloatingPointRange<Float>, col: Color) {
                    val p = Path()
                    for (i in 0..60) {
                        val tt = i / 60f
                        val y = 480 * (1 - tt).pow(2) * tt.pow(2) * sin(PI * tt).toFloat() + 25
                        val px = x(r.start + (r.endInclusive - r.start) * tt); val py = h - y / 75f * h
                        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                    }
                    drawPath(p, col, style = Stroke(3f))
                }
                bell(ex, Color(0xFFD4A017)); bell(intake, Color(0xFF43A047))
                // 噴油脈衝：從噴射角往前畫脈寬（XTune：pulseDeg = PW ÷ (120000÷rpm) × 720）
                if (rpm != null && pw != null && angle != null && rpm > 0) {
                    val deg = (pw / (120000.0 / rpm) * 720.0).toFloat()
                    fun pulse(end: Float) {
                        var a = end; var s = end - deg
                        if (s < 0) { drawRect(c.danger, Offset(x(720f + s), h * 0.4f), Size(x(-s), h * 0.12f)); s = 0f }
                        a = a.coerceIn(0f, 720f)
                        drawRect(c.danger, Offset(x(s), h * 0.4f), Size(x(a - s), h * 0.12f))
                    }
                    pulse(angle.toFloat())
                    if (single) pulse(((angle + 360) % 720).toFloat())
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Compression", "Intake", "Exhaust", "Power").forEach { Text(it, color = c.muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        Text("Exhaust  Open:${ex.start.toInt()}°  Close:${ex.endInclusive.toInt()}°", color = Color(0xFFD4A017), fontSize = 12.sp)
        RangeSlider(value = ex, onValueChange = { r -> if (r.endInclusive - r.start >= 150) ex = r }, valueRange = 240f..640f)
        Text("Intake  Open:${intake.start.toInt()}°  Close:${intake.endInclusive.toInt()}°", color = Color(0xFF43A047), fontSize = 12.sp)
        RangeSlider(value = intake, onValueChange = { r -> if (r.endInclusive - r.start >= 150) intake = r }, valueRange = 90f..480f)
        Note(CalText.get("cal_angle_note", host.lang))
    }
}
