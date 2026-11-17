package xb.calui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xb.link.LiveTuning
import xb.tune.CalItems
import xb.tune.Cell
import xb.tune.Convert

/** 條件燈（XTune 的 AT Running／Malf.／T_Eng／WBO2）。 */
@Composable
private fun Lamp(label: String, on: Boolean?, modifier: Modifier = Modifier) {
    val c = LocalCalColors.current
    Text(
        label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1,
        color = if (on == true) Color.White else c.muted,
        modifier = modifier.background(if (on == true) Color(0xFF2E9E6B) else c.card, RoundedCornerShape(6.dp))
            .border(1.dp, if (on == null) c.line else if (on) Color(0xFF2E9E6B) else c.danger, RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
    )
}

/** 每 [ms] 毫秒加一，讓即時值重畫。 */
@Composable
private fun ticker(ms: Long): Int {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(ms) { while (true) { delay(ms); tick++ } }
    return tick
}

private fun CalHost.bitVar(id: Int): Boolean? = live(id)?.let { it != 0.0 }

/** AT 執行中與三個條件（XTune 負數 id＝位元變數：−41 Fuel5.bit1、−2287 Fuel7.bit7、−2272 Fuel6.bit2、−40 Fuel5.bit0）。 */
@Composable
private fun AtLamps(host: CalHost) {
    val tick = ticker(250)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        remember(tick) { listOf("AT Running" to host.bitVar(-41), "Malf." to host.bitVar(-2287), "T_Eng" to host.bitVar(-2272), "WBO2" to host.bitVar(-40)) }
            .forEach { (l, v) -> Lamp(l, v, Modifier.weight(1f)) }
    }
}

/** 散點軌跡：X＝RPM、Y＝TPS%，顏色＝寬域 AFR。 */
@Composable
private fun AtChart(host: CalHost, points: MutableList<Triple<Float, Float, Float>>, rpmMax: Float, modifier: Modifier) {
    val c = LocalCalColors.current
    val tick = ticker(100)
    LaunchedEffect(tick) {
        val rpm = host.live(26); val tps = host.live(34); val afr = host.live(55)
        if (rpm != null && tps != null && afr != null && rpm > 0) { points += Triple(rpm.toFloat(), tps.toFloat(), afr.toFloat()); while (points.size > 1800) points.removeAt(0) }
    }
    Box(modifier.background(c.card, RoundedCornerShape(8.dp))) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val w = size.width; val h = size.height
            for (i in 0..4) { val y = h * i / 4; drawLine(c.line, Offset(0f, y), Offset(w, y)) }
            fun col(afr: Float): Color {
                val t = ((afr - 11f) / 5f).coerceIn(0f, 1f)
                return Color(red = 1f - t * 0.8f, green = 0.3f + 0.5f * (1 - kotlin.math.abs(t - 0.5f) * 2), blue = t)
            }
            for (p in points) drawCircle(col(p.third), 3f, Offset(p.first / rpmMax * w, h - p.second / 100f * h))
            points.lastOrNull()?.let { drawCircle(c.text, 6f, Offset(it.first / rpmMax * w, h - it.second / 100f * h), style = Stroke(2f)) }
        }
    }
}

/**
 * 自動調校：開關＝表 14（停手 1.2 秒才送；開了照 XTune 關掉寬域／窄域閉迴路與
 * 怠速音效，[Interlock]）、AP／TPS 燃油表切換（表 986，非 Mini、要先確認）、AT 執行中與三個條件燈、
 * 三個分頁：軌跡圖、AFR 表（表 5）、燃油表（986＝0 表 1、1 表 4）。開著時分頁每 5 秒從 ECU 重讀那張表（ECU 就地修正燃油表）。
 * 官方沒有「清除學習」；CLEAR 只清圖。
 */
@Composable
internal fun AutoTunePage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    var tab by remember { mutableStateOf(0) }
    var askSwitch by remember { mutableStateOf<Long?>(null) }
    val points = remember { mutableStateListOf<Triple<Float, Float, Float>>() }
    val at = D1(host, 14); val sw = D1(host, 986)
    val fuel = if (sw.present && sw.raw == 1L) 4 else 1
    val shown = when (tab) { 1 -> 5; 2 -> fuel; else -> null }
    LaunchedEffect(shown, at.raw, host.online) {
        while (shown != null && host.online && at.raw == 1L) { delay(5000); host.readTables(listOf(shown))?.let { msg.text = it } }
    }
    CalFrame(host, title, listOf(14, 986), listOf(26, 20, 55, 34), onBack, msg, scroll = false, watch = listOf(-41, -2287, -2272, -40), header = {
        Card {
            ValueControl(host, at, msg, label = CalText.get("CalTitle_AutoTune", host.lang))
            if (sw.present && !host.isMini && host.online) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Eng_AP", on = sw.raw == 0L, modifier = Modifier.testTag("at.ap")) { if (sw.raw != 0L) askSwitch = 0 }
                Pill("TPS", on = sw.raw == 1L, modifier = Modifier.testTag("at.tps")) { if (sw.raw != 1L) askSwitch = 1 }
            }
        }
        if (host.online) AtLamps(host)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            listOf("AT", "AFR", "Fuel").forEachIndexed { i, n -> Pill(n, on = tab == i, modifier = Modifier.testTag("at.tab$i")) { tab = i } }
            if (tab == 0) Pill(CalText.get("AFR_Clear", host.lang)) { points.clear() }
        }
    }) {
        when (tab) {
            0 -> AtChart(host, points, if (host.node == 33 || host.node == 37) 16000f else 12000f, Modifier.fillMaxWidth().weight(1f))
            else -> host.TableEditor(listOfNotNull(shown).filter { host.has(it) }, Modifier.fillMaxWidth().weight(1f))
        }
    }
    askSwitch?.let { v ->
        ConfirmDialog(host, CalText.get("AT_TabSwitch", host.lang), CalText.get(if (v == 1L) "AT_TabTPS" else "AT_TabEngAP", host.lang), {
            report(host, msg, sw.setRaw(v))
        }) { askSwitch = null }
    }
}

/**
 * 超級自動調校：Super XX 與 SxS。
 * 設定分頁三個互鎖開關（Pro-AT 1500／SxS 1580、寬域閉迴路 1059、多缸修正 1505／1584），開了立刻寫（官方無去抖）；
 * 開 Pro-AT 關 1059 並照自動調校關掉窄域閉迴路與怠速音效；開 1059 關 Pro-AT。
 * 燃油基準 986 只在多缸修正與寬域閉迴路都關時能換。多缸修正開時看每缸的修正表（% ＝ raw÷1.28）。
 * AFR 分頁讀每缸 AFR（0x680 `62 缸 列`，56×26）；每缸狀態讀 0x2000/1（每缸 1 byte：(b&0x7F+8)/32＝0 黃燈，bit7＝AF3）。
 */
@Composable
internal fun AutoTuneProPage(host: CalHost, item: CalItems.Item, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    val sxs = host.node == 37
    val proId = if (sxs) 1580 else 1500
    val multiId = if (sxs) 1584 else 1505
    val factors = if (sxs) listOf(1362, 159, 1360, 1361) else listOf(1504, 159, 1364, 1365)
    val cyls = when (host.fwCode) { 40, 74 -> 4; 30, 73 -> 3; 20, 21, 72 -> 2; else -> 1 }
    var tab by remember { mutableStateOf(0) }
    var cyl by remember { mutableStateOf(1) }
    var ask by remember { mutableStateOf<Long?>(null) }
    val points = remember { mutableStateListOf<Triple<Float, Float, Float>>() }
    val pro = D1(host, proId); val wb = D1(host, 1059); val multi = D1(host, multiId); val base = D1(host, 986)
    val fuelTable = when {
        multi.present && multi.raw != 0L -> factors[cyl - 1]
        base.present && base.raw == 1L -> 4
        else -> 1
    }
    fun flip(d: D1, on: Boolean) {
        // 開：先關掉互斥的功能、ECU 收下了才開（[Interlock]）；關：直接送
        if (on && (d.id == proId || d.id == 1059)) { Interlock.set(host, d, 1, msg, scope); return }
        report(host, msg, d.setRaw(if (on) 1 else 0))
        scope.launch { host.writeTables(listOf(d.id).filter { host.has(it) && host.writable(it) })?.let { msg.text = it } }
    }
    val status = remember { mutableStateListOf<Int>() }
    LaunchedEffect(host.online) {
        while (host.online) {
            host.readObject(0x2000, 1).getOrNull()?.let { b -> status.clear(); status.addAll(b.take(4).map { it.toInt() and 0xFF }) }
            delay(1000)
        }
    }
    val afr = remember { mutableStateOf<Array<FloatArray>?>(null) }
    LaunchedEffect(tab, cyl, host.online) {
        while (tab == 2 && host.online) { afr.value = readCylAfr(host, cyl) ?: afr.value; delay(3000) }
    }
    LaunchedEffect(tab, fuelTable, pro.raw, host.online) {
        while (tab == 3 && host.online && (pro.raw == 1L || wb.raw == 1L)) { delay(5000); host.readTables(listOf(fuelTable))?.let { msg.text = it } }
    }
    val headTitle = when {
        pro.raw == 0L && wb.raw == 0L -> "Pro-AT: Disable"
        wb.raw == 1L -> if (multi.raw == 1L) (if (cyl == 1) "Fuel_CL" else "FuelCL$cyl") else "Fuel_CL"
        multi.raw == 1L -> "Pro-AT: D3Cyl${cyl}_VM_Factor"
        base.raw == 1L -> "Pro-AT: D3Cyl1_VM_TPS_F"
        else -> "Pro-AT: D3Cyl1_VM"
    }
    CalFrame(host, title, listOf(proId, 1059, multiId, 986), listOf(26, 20, 55, 34), onBack, msg, scroll = tab == 0, watch = listOf(-41, -2287, -2272, -40), header = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headTitle, color = LocalCalColors.current.danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (cyls > 1 && multi.raw == 1L) Pill("Cyl$cyl", modifier = Modifier.testTag("pat.cyl")) { cyl = cyl % cyls + 1 }
        }
        if (host.online) AtLamps(host)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            listOf(CalText.get("cal_settings", host.lang), "AT", "AFR", "Fuel").forEachIndexed { i, n -> Pill(n, on = tab == i, modifier = Modifier.testTag("pat.tab$i")) { tab = i } }
            if (tab == 1) Pill(CalText.get("AFR_Clear", host.lang)) { points.clear() }
        }
    }) {
        when (tab) {
            0 -> {
                Card {
                    for ((d, key) in listOf(pro to "ProAT_SeuprProAT", wb to "ProAT_WBO2CLC", multi to "ProAT_MultiCylinder")) {
                        if (d === multi && cyls <= 1) continue
                        if (!d.present) { MissingRow(host, CalText.get(key, host.lang), d.id); continue }
                        SwitchRow(host, CalText.get(key, host.lang), d.raw != 0L, d.writable || !host.online, tag = "pat.${d.id}") { on -> flip(d, on) }
                        TableState(host, d.id)
                    }
                }
                Card(title = CalText.get("AT_TabSwitch", host.lang)) {
                    val can = base.present && multi.raw == 0L && wb.raw == 0L
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (wb.raw == 1L) "Fuel_CL" else "D3Cyl1_VM", can, on = base.raw == 0L, modifier = Modifier.testTag("pat.vm")) { if (base.raw != 0L) ask = 0 }
                        Pill(if (wb.raw == 1L) "Fuel_CL" else "D3Cyl1VM_TPS_F", can, on = base.raw == 1L, modifier = Modifier.testTag("pat.tpsf")) { if (base.raw != 1L) ask = 1 }
                    }
                    if (multi.raw == 1L) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..cyls).forEach { i ->
                            val b = status.getOrNull(i - 1)
                            val level = b?.let { ((it and 0x7F) + 8) / 32 }
                            Lamp((if (b != null && b and 0x80 != 0) "AF3_" else "AF2_") + "ABCD"[i - 1], level?.let { it > 0 }, Modifier.weight(1f))
                        }
                    }
                }
            }
            1 -> AtChart(host, points, 16000f, Modifier.fillMaxWidth().weight(1f))
            2 -> {
                Text("AFR · Cyl$cyl", color = LocalCalColors.current.muted, fontSize = 12.sp)
                HeatGrid(afr.value, Modifier.fillMaxWidth().weight(1f))
            }
            else -> host.TableEditor(listOf(fuelTable).filter { host.has(it) }, Modifier.fillMaxWidth().weight(1f))
        }
    }
    ask?.let { v ->
        ConfirmDialog(host, CalText.get("AT_TabSwitch", host.lang), CalText.get(if (v == 1L) "AT_TabTPS" else "AT_TabEngAP", host.lang), {
            report(host, msg, base.setRaw(v))
            scope.launch { host.writeTables(listOf(986))?.let { msg.text = it } }
        }) { ask = null }
    }
}

/**
 * 讀一缸的 AFR（56×26，每格 raw÷10）：每列送 0x680 `62 缸 列`，ECU 回 10 筆 `63 序號 …`（第 1 筆 3 格、2–9 各 6 格、第 10 筆 5 格）。
 * 回應的 CAN id XTune 沒檢查；這裡收 0x6A0（0x680 的回應通道）。
 */
private suspend fun readCylAfr(host: CalHost, cyl: Int): Array<FloatArray>? {
    val out = Array(26) { FloatArray(56) }
    for (row in 0 until 26) {
        val frames = host.raw(0x680, byteArrayOf(0x62, cyl.toByte(), row.toByte()), reply = 0x6A0, count = 10, timeoutMs = 1500) {
            it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0x63
        }.getOrNull() ?: return null
        var col = 0
        for (f in frames.sortedBy { it.getOrNull(1)?.toInt() ?: 0 }) for (i in 2 until f.size) if (col < 56) out[row][col++] = (f[i].toInt() and 0xFF) / 10f
    }
    return out
}

/** 唯讀熱圖（列×欄）。 */
@Composable
fun HeatGrid(values: Array<FloatArray>?, modifier: Modifier) {
    val c = LocalCalColors.current
    Box(modifier.background(c.card, RoundedCornerShape(8.dp))) {
        if (values == null) { Text("—", color = c.muted, modifier = Modifier.align(Alignment.Center)); return@Box }
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val rows = values.size; val cols = values.firstOrNull()?.size ?: 0
            if (rows == 0 || cols == 0) return@Canvas
            val cw = size.width / cols; val ch = size.height / rows
            val all = values.flatMap { it.toList() }.filter { it > 0 }
            val lo = all.minOrNull() ?: 0f; val hi = (all.maxOrNull() ?: 1f).coerceAtLeast(lo + 0.1f)
            for (r in 0 until rows) for (cc in 0 until cols) {
                val v = values[r][cc]
                val t = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                drawRect(if (v <= 0f) c.bg else Color(red = t, green = 0.4f, blue = 1f - t), Offset(cc * cw, (rows - 1 - r) * ch), Size(cw - 1, ch - 1))
            }
        }
    }
}

/**
 * 怠速空氣量設定：沒有表。開關＝命令物件 0x2000/0（寫 1 進入、0 離開；
 * 進頁讀一次目前狀態），之後照即時值調怠速螺絲：圓錶 1000–2500 rpm、綠帶＝目標（var 78）±100、ISC 空氣量條（var 77）、
 * 狀態字（啟動引擎／增加／減少／空氣量正確）。官方切換立刻送、沒有回饋；這裡開啟前先確認，並顯示 ECU 有沒有收下。
 */
@Composable
internal fun IdleAdjPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    val c = LocalCalColors.current
    var on by remember { mutableStateOf<Boolean?>(null) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(host.online) {
        if (host.online) on = host.readObject(0x2000, 0).fold({ b -> (b.firstOrNull()?.toInt() ?: 0) == 1 }, { e -> msg.text = e.message; null })
    }
    fun send(v: Boolean) = scope.launch {
        val r = host.command(0x2000, 0, if (v) 1 else 0, 1)
        if (r == null) on = v
        msg.text = r ?: CalText.get("cal_cmd_sent", host.lang)
    }
    val tick = ticker(200)
    CalFrame(host, CalText.get("CalTitle_IdleAdj", host.lang), emptyList(), listOf(26, 78, 77, 55), onBack, msg) {
        if (!host.online) { Warn(CalText.get("cal_need_online", host.lang)); return@CalFrame }
        SwitchRow(host, CalText.get("CalTitle_IdleAdj", host.lang), on == true, on != null, tag = "idle.sw") { v -> if (v) ask = true else send(false) }
        val rpm = remember(tick) { host.live(26) }; val target = remember(tick) { host.live(78) }; val isc = remember(tick) { host.live(77) }
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(c.card, RoundedCornerShape(10.dp))) {
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                fun ang(x: Double) = ((x.coerceIn(1000.0, 2500.0) - 1000) * 75 / 1500 + 195).toFloat()
                val d = minOf(size.width, size.height * 1.6f); val tl = Offset((size.width - d) / 2, 0f); val sz = Size(d, d)
                drawArc(c.line, 195f, 75f, false, tl, sz, style = Stroke(24f))
                if (target != null) drawArc(Color(0xFF43A047), ang(target - 100), ang(minOf(target, 2400.0) + 100) - ang(target - 100), false, tl, sz, style = Stroke(24f))
                if (on == true && rpm != null) drawArc(c.brand, 195f, ang(rpm) - 195f, false, tl, sz, style = Stroke(10f))
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TARGET " + (target?.let { num(it, 0) } ?: "—"), color = Color(0xFF1976D2), fontSize = 20.sp, fontFamily = c.digits)
                Text(rpm?.let { num(it, 0) } ?: "—", color = c.text, fontSize = 34.sp, fontFamily = c.digits)
            }
        }
        Box(Modifier.fillMaxWidth().height(28.dp).background(c.card, RoundedCornerShape(6.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val x = ((0.05f + (isc ?: 0.0).toFloat() / 100f * 0.9f) * size.width)
                drawLine(c.line, Offset(size.width * 0.05f, size.height / 2), Offset(size.width * 0.95f, size.height / 2), 6f)
                if (on == true && isc != null) drawLine(c.accent, Offset(x, 2f), Offset(x, size.height - 2f), 6f)
            }
        }
        if (on == true && rpm != null && target != null) {
            val (key, col) = when {
                rpm == 0.0 -> "IDLE_StartEng" to Color(0xFFFBC02D)
                rpm < target - 100 -> "IDLE_MoreAir" to Color(0xFF0288D1)
                rpm > target + 100 -> "IDLE_LessAir" to Color(0xFFD32F2F)
                else -> "IDLE_MeetTarget" to Color(0xFF9CCC65)
            }
            Text(CalText.get(key, host.lang), color = col, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("idle.state"))
            Note(CalText.get(key + "_Note", host.lang))
        }
    }
    if (ask) ConfirmDialog(host, CalText.get("CalTitle_IdleAdj", host.lang), CalText.get("cal_idle_confirm", host.lang), { send(true) }) { ask = false }
}

/**
 * TPS 學習：按開始送命令 0x2001/0＝1，之後照官方計時：
 * 學習中（請保持油門全開）5.2 秒→學習完成 2 秒→回待機；學習中記 TPS 電壓最大／最小（TPS_AD×5/1024）。
 * 跟官方不同：ECU 沒回（逾時）就不當成功，顯示原因。
 */
@Composable
internal fun TpsLearnPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    val c = LocalCalColors.current
    var phase by remember { mutableStateOf(0) } // 0 待機、1 學習中、2 完成
    var vMax by remember { mutableStateOf(0.0) }
    var vMin by remember { mutableStateOf(5.0) }
    var ask by remember { mutableStateOf(false) }
    val tick = ticker(100)
    val tps = remember(tick) { host.live(34) }
    val ad = remember(tick) { host.live(27) }
    LaunchedEffect(tick) { if (phase == 1 && ad != null) { val v = ad * 0.0048828125; vMax = maxOf(vMax, v); vMin = minOf(vMin, v) } }
    CalFrame(host, CalText.get("CalTitle_TPSLearn", host.lang), emptyList(), listOf(34, 27, 33, 29), onBack, msg) {
        if (!host.online) { Warn(CalText.get("cal_need_online", host.lang)); return@CalFrame }
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(c.card, RoundedCornerShape(10.dp))) {
            Canvas(Modifier.fillMaxSize().padding(20.dp)) {
                val d = minOf(size.width, size.height); val tl = Offset((size.width - d) / 2, (size.height - d) / 2); val sz = Size(d, d)
                drawArc(c.line, 119f, 300f, false, tl, sz, style = Stroke(18f))
                drawArc(c.brand, 119f, 300f * ((tps ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), false, tl, sz, style = Stroke(18f))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text((tps?.let { fixed(it, 1) } ?: "—") + "%", color = Color(0xFF4FC3F7), fontSize = 40.sp, fontFamily = c.digits, modifier = Modifier.testTag("tps.value"))
                if (phase >= 1) Text("MAX ${fixed(vMax, 1)}v  MIN ${fixed(vMin, 1)}v", color = c.muted, fontSize = 13.sp)
            }
        }
        if (phase == 1) Text(CalText.get("TPSLearn_WOT", host.lang), color = Color(0xFFC62828), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Pill(CalText.get(when (phase) { 0 -> "TPSStartLearning"; 1 -> "TPSLearning"; else -> "TPSLearn_Complete" }, host.lang), phase == 0, Modifier.fillMaxWidth().testTag("tps.start")) { ask = true }
    }
    if (ask) ConfirmDialog(host, CalText.get("CalTitle_TPSLearn", host.lang), CalText.get("cal_tps_confirm", host.lang), {
        scope.launch {
            val r = host.command(0x2001, 0, 1, 1)
            if (r != null) { msg.text = r; return@launch }
            vMin = (ad ?: 0.0) * 0.0048828125; vMax = 0.0
            phase = 1; delay(5200); phase = 2; delay(2000); phase = 0
        }
    }) { ask = false }
}

/**
 * 清除 ECU 學習值（XTune 設定頁的「Set_ClearEcuLearn」，確認後送 0x2009 sub 0 ＝ 1，只送這一個命令）。
 * 韌體收到後把窄域閉迴路學習表 79／80（D3NBO2_BL_Warm／Cold_CELL）整張填 0x80（中性），並重設怠速學習。
 * 送命令、等韌體處理、重讀這兩張表換進編輯器，整段由宿主的即時寫入做（[CalHost.commandThenRead]）：
 * 期間這兩張表不能寫、別的寫入等它做完，離開頁面也會做完，畫面與之後的全寫不會把舊學習值寫回去。
 * 重讀失敗：兩張表維持鎖住，頁上的「重讀學習表」成功才解開。調校開始時的備份還原會連學習值一起寫回。
 */
@Composable
internal fun ClearLearnPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    val c = LocalCalColors.current
    var busy by remember { mutableStateOf<String?>(null) } // 進行中的工作的字串鍵（清除中／讀取中）
    var ask by remember { mutableStateOf(false) }
    val ids = CalItems.CLEAR_LEARN_TABLES
    val present = ids.all { host.has(it) }
    // 別的頁開著時送出、還在做（或剛離開又回來）：兩張表在寫入中也算忙
    val running = busy != null || ids.any { host.status(it) == LiveTuning.TableState.Writing }
    // 命令後沒重讀成功的表：鎖著，要重讀（不管狀態徽章是失敗、讀回不同還是拒絕）
    val locked = present && !running && ids.any { host.held(it) }
    fun s(k: String) = CalText.get(k, host.lang)
    fun go(label: String, block: suspend () -> String) {
        scope.launch {
            busy = label
            try { msg.text = block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { msg.text = CalText.format("cal_clearlearn_error", host.lang, e.message ?: e::class.simpleName ?: "error") }
            finally { busy = null }
        }
    }
    CalFrame(host, title, emptyList(), emptyList(), onBack, msg) {
        if (!host.online) { Warn(s("cal_need_online")); return@CalFrame }
        if (!present) { Warn(CalText.format("cal_missing", host.lang, ids.filter { !host.has(it) }.joinToString("、"))); return@CalFrame }
        Text(s("cal_clearlearn_desc"), color = c.text, fontSize = 15.sp)
        Pill(if (running) s(busy ?: "cal_clearlearn_busy") else title, !running && !locked, Modifier.fillMaxWidth().testTag("clearlearn.start")) { ask = true }
        if (locked) Pill(s("cal_clearlearn_reread"), true, Modifier.fillMaxWidth().testTag("clearlearn.reread")) {
            go("cal_clearlearn_reading") { host.readTables(ids) ?: s("cal_clearlearn_reread_done") }
        }
        host.TableEditor(ids, Modifier.fillMaxWidth().height(320.dp))
    }
    if (ask) ConfirmDialog(host, title, s("cal_clearlearn_confirm"), {
        go("cal_clearlearn_busy") {
            // 送之前再確認一次兩張表都在（清完要重讀它們）
            if (!host.online) return@go s("cal_need_online")
            if (!ids.all { host.has(it) }) return@go CalText.format("cal_missing", host.lang, ids.filter { !host.has(it) }.joinToString("、"))
            val r = host.commandThenRead(0x2009, 0, 1, 1, ids, settleMs = 300) // 韌體在下一輪主迴圈才處理旗標
            when {
                !r.sent -> r.command ?: s("cal_need_online")
                r.failed.isNotEmpty() -> CalText.format("cal_clearlearn_refresh_failed", host.lang,
                    r.failed.entries.joinToString("; ") { "${host.tableTitle(it.key)}: ${it.value}" })
                r.command != null -> CalText.format("cal_clearlearn_unconfirmed", host.lang, r.command.orEmpty())
                else -> s("cal_clearlearn_done")
            }
        }
    }) { ask = false }
}

/**
 * 燃油噴嘴校正：自動流程，最後把表 72（死區補償 17 格）整列加一個修正量寫回 ECU。
 * 條件（怠速、寬域 O2 就緒、引擎 ≥60℃、無故障、行程識別）都 Ready 才能開始。流程照官方（每 100 ms 一步）：
 * 等穩定 → 收 50 筆 → 計算 新＝clamp((多噴CL−單噴CL)×單噴PW/100＋目前補償, 0.2, 0.8)，修正＝新−目前 → 每格加修正 → 收尾 0x0102 → 寫表 72。
 * 等待條件時每 45 秒送 0x7801 維持測試模式（官方值）。逾時 160 秒中斷。表 72 寫入走同一條路，調校開始時的整份備份可以一鍵還原。
 */
@Composable
internal fun InjectorCalPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val c = LocalCalColors.current
    fun s(k: String) = CalText.get(k, host.lang)
    var intro by remember { mutableStateOf(true) }
    val run = remember { InjCalRun() }
    LaunchedEffect(host.online) {
        if (host.online && host.has(72)) host.readTables(listOf(72))
        while (host.online) { delay(100); run.tick(host, msg) }
    }
    CalFrame(host, s("JCL_Title"), listOf(72), listOf(26, 25, 33, 29), onBack, msg, watch = listOf(-62, -40, -2287, -195, 789, 22, 35, 236)) {
        if (!host.online) { Warn(s("cal_need_online")); return@CalFrame }
        Card(title = s("JCL_Conditions")) {
            run.conditions.forEachIndexed { i, ok ->
                LabeledRow(s(listOf("JCL_Idle", "JCL_WBO2", "JCL_TEng", "JCL_Malf", "JCL_SeqMode")[i])) {
                    Text(if (ok) "Ready" else "NG", color = if (ok) c.ok else c.danger, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        LinearProgressIndicator(progress = { (run.progress / 160f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        run.stepText()?.let { Text(s(it), color = c.text, fontSize = 13.sp) }
        Pill(s(when (run.state) { 192 -> "JCL_WaitingConditions"; 193 -> "JCL_StartCal"; 196 -> "JCL_CalInterrupt"; else -> "JCL_OnCalibrating" }), run.state == 193, Modifier.fillMaxWidth().testTag("inj.start")) { run.start() }
        run.report?.let { r ->
            Card {
                Text(CalText.format("JCL_Report", host.lang, fixed(r.delta, 3)), color = c.text)
                Text(CalText.format("JCL_Avg_RPM", host.lang, r.rpm), color = c.muted)
                Text(CalText.format("JCL_Avg_TEng", host.lang, r.teng), color = c.muted)
                Text(CalText.format("JCL_Avg_Volt", host.lang, r.volt), color = c.muted)
            }
        }
        Card { AdjustRow(host, D1(host, 72), bar = false, msg = msg) }
        Warn(s("cal_injcal_note"))
    }
    if (intro) AlertDialog(
        onDismissRequest = { intro = false },
        title = { Text(s("JCL_Title")) },
        text = { Text(s("JCL_Note_1") + "\n\n" + s("JCL_Note_2")) },
        confirmButton = { TextButton(onClick = { intro = false }) { Text(s("Confirm").takeIf { CalText.has("Confirm") } ?: s("cal_ok")) } },
    )
}

/** 噴嘴校正的狀態機。 */
private class InjCalRun {
    class Report(val delta: Double, val rpm: Double, val teng: Double, val volt: Double)
    var state by mutableStateOf(192)
    var step by mutableStateOf(0)
    var progress by mutableStateOf(0f)
    var conditions by mutableStateOf(List(5) { false })
    var report by mutableStateOf<Report?>(null)
    private var kick = 2000
    private var ack = false
    private var stable = 0; private var rounds = 0
    private val cl = ArrayList<Double>(); private val pw = ArrayList<Double>()
    private var singleCl = 0.0; private var singlePw = 0.0
    private val rpms = ArrayList<Double>(); private val tengs = ArrayList<Double>(); private val volts = ArrayList<Double>()
    private var hold = 0
    private var busy = false

    fun start() { if (state == 193) { state = 194; step = 1; progress = 0f; report = null; cl.clear(); pw.clear(); rpms.clear(); tengs.clear(); volts.clear() } }

    fun stepText(): String? = when (step) {
        1, 2, 3, 4 -> "JCL_Msg_DataAcquisition"; 5 -> "JCL_Msg_FuelStable"; 6, 7, 8, 9 -> "JCL_Msg_FuelStable"; 10 -> "JCL_Msg_Calibrating"; 11 -> "JCL_Msg_WriteRow"; 12 -> "JCL_Msg_Completed"
        else -> null
    }

    private fun avg(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        if (v.size < 5) return v.average()
        val s = v.sorted(); val cut = (s.size * 0.2).toInt()
        return s.subList(cut, s.size - cut).average()
    }

    suspend fun tick(host: CalHost, msg: PageMsg) {
        if (busy) return
        val teng = host.live(33)
        conditions = listOf(
            host.live(-62)?.let { it != 0.0 } ?: false,
            host.live(-40)?.let { it != 0.0 } ?: false,
            teng != null && teng >= 60,
            host.live(-2287) == 1.0,
            state != 192 || (host.live(-195)?.let { it != 0.0 } ?: false),
        )
        val ready = conditions.all { it }
        when (state) {
            192, 193 -> {
                if (ready && state == 192) state = 193
                if (!ready && state == 193) state = 192
                kick -= 100
                if (kick <= 0) { kick = 45000; send(host, msg, 0x7801L) }
                return
            }
            195, 196 -> { if (++hold >= 30) { hold = 0; state = 192; step = 0 }; return }
        }
        // 194 校正中
        progress += 0.1f
        if (progress >= 160f) { state = 196; msg.text = CalText.get("JCL_Cal_Timeout", host.lang); return }
        host.live(26)?.let { rpms += it }; teng?.let { tengs += it }; host.live(29)?.let { volts += it }
        val dAfr = host.live(789)
        when (step) {
            1 -> { ack = false; if (send(host, msg, 0x14001L)) { ack = true }; step = 3 }
            3 -> if (ack) { step = 4; stable = 0; rounds = 0 } else { state = 196 }
            4 -> { if (dAfr == 0.0) stable++ ; if (stable >= 20) { stable = 0; rounds++ }; if (rounds >= 10) step = 5 }
            5 -> if (dAfr == 0.0) {
                host.live(22)?.let { cl += it }; host.live(35)?.let { pw += it }
                if (cl.size >= 50) { singleCl = avg(cl); singlePw = avg(pw); cl.clear(); step = 6 }
            }
            6 -> { ack = send(host, msg, 0x14002L) && send(host, msg, 0x14001L); progress = 80f; step = 7 }
            7 -> if (ack) { step = 8; stable = 0; rounds = 0 } else { state = 196 }
            8 -> { if (dAfr == 0.0) stable++; if (stable >= 35) { stable = 0; rounds++ }; if (rounds >= 10) step = 9 }
            9 -> if (dAfr == 0.0) { host.live(22)?.let { cl += it }; if (cl.size >= 50) step = 10 }
            10 -> {
                val doubleCl = avg(cl)
                val cur = host.live(236) ?: run { state = 196; return }
                val next = (((doubleCl - singleCl) * singlePw / 100) + cur).coerceIn(0.2, 0.8)
                val delta = next - cur
                step = 11
                busy = true
                try {
                    send(host, msg, 0x0102L)
                    val def = host.tune.definition(72); val row = host.rowsOf(72)?.firstOrNull()
                    if (def == null || row == null) { state = 196; return }
                    val values = row.indices.associate { i -> Cell(0, i) to (Convert.rawToPhysical(def, row[i]) + delta).coerceIn(def.min, def.max) }
                    val r = host.tune.setValues(72, values)
                    if (r is xb.tune.EditResult.Refused) { msg.text = r.reason; state = 196; return }
                    host.edited(72)
                    host.writeTables(listOf(72))?.let { msg.text = it; state = 196; return }
                    report = Report(delta, rpms.average(), tengs.average(), volts.average())
                    step = 12; state = 195
                } finally { busy = false }
            }
        }
    }

    private suspend fun send(host: CalHost, msg: PageMsg, v: Long): Boolean {
        busy = true
        try {
            val r = host.command(0x2000, 4, v, 2)
            if (r != null) msg.text = r
            return r == null
        } finally { busy = false }
    }
}
