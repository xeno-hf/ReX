package xb.calui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xb.tune.Cell
import xb.tune.Convert
import xb.tune.EditResult

private const val QS_AD = 1183

/** 快排 A/D 條：0–1000 對到 5%–95%，畫拉伸門檻、壓縮門檻、即時值三根針。 */
@Composable
private fun QsBar(ten: Double?, comp: Double?, ad: Double?, zero: Double? = null) {
    val c = LocalCalColors.current
    Canvas(Modifier.fillMaxWidth().height(36.dp).background(c.card, RoundedCornerShape(6.dp))) {
        val w = size.width; val h = size.height
        fun x(v: Double) = (0.05f + (v.coerceIn(0.0, 1023.0) / 1000.0).toFloat() * 0.9f) * w
        drawLine(c.line, Offset(x(0.0), h / 2), Offset(x(1000.0), h / 2), 6f)
        fun needle(v: Double?, col: Color, width: Float) { if (v != null) drawLine(col, Offset(x(v), 2f), Offset(x(v), h - 2f), width) }
        needle(zero, c.muted, 3f)
        needle(ten, Color(0xFF2196F3), 4f)
        needle(comp, Color(0xFFE53935), 4f)
        needle(ad, c.brand, 6f)
    }
}

/**
 * 快排設定：表 29 的 5 個位元（總開關、切斷方式、
 * 進檔受力方向、退檔、原廠快排；bit5 不碰）＋表 9／10 拉伸／壓縮門檻（兩者互鎖相差 ≥50，壓縮上限 1000）。
 */
@Composable
internal fun QsSettingPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(200); tick++ } }
    CalFrame(host, title, listOf(29, 9, 10), listOf(26, 34, 55, QS_AD), onBack, msg) {
        val opt = D1(host, 29)
        Card {
            if (!opt.present) MissingRow(host, host.tableTitle(29), 29)
            else {
                val en = opt.writable || !host.online
                fun s(k: String) = CalText.get(k, host.lang)
                fun flip(b: Int) = report(host, msg, opt.toggleBit(b))
                SwitchRow(host, s("QS_Function"), opt.bit(0), en, tag = "qs.b0") { flip(0) }
                SwitchRow(host, s("QS_CutBy"), opt.bit(1), en, onText = s("QS_Fuel"), offText = s("QS_Spark"), tag = "qs.b1") { flip(1) }
                SwitchRow(host, s("QS_ShifterDir"), opt.bit(2), en, onText = s("QS_Tension"), offText = s("QS_compress"), tag = "qs.b2") { flip(2) }
                SwitchRow(host, s("QS_ShihterDown_ETC"), opt.bit(3), en, tag = "qs.b3") { flip(3) }
                SwitchRow(host, s("QS_Bit4_Stock"), opt.bit(4), en, tag = "qs.b4") { flip(4) }
                TableState(host, 29)
            }
        }
        val ten = D1(host, 9); val comp = D1(host, 10)
        Card {
            val ad = remember(tick) { host.live(QS_AD) }
            QsBar(ten.value.takeIf { ten.present }, comp.value.takeIf { comp.present }, ad)
            AdjustRow(host, ten, CalText.get("TabDisplay_D1Quick_Shift_Ten_AD", host.lang), hi = if (comp.present) comp.value - 50 else null, msg = msg, tag = "qs.ten")
            AdjustRow(host, comp, CalText.get("TabDisplay_D1Quick_Shift_Com_AD", host.lang), lo = if (ten.present) ten.value + 50 else null, hi = 1000.0, msg = msg, tag = "qs.comp")
        }
    }
}

/**
 * 快排設定精靈：
 * 1 選切斷方式 → 2 退檔功能 → 3 量升檔門檻 →（退檔開）4 量降檔門檻 → 儲存寫 29／9／10。
 * 量法照官方：鬆開踏板取基準 A/D（變數 1183），之後跨過基準 ±50 那一刻的值就是門檻。
 * 官方一進步驟 3 就把快排總開關（29 bit0）打開寫進 ECU（荷重感測器才有值），這裡照做但會先說；
 * 官方沒擋的兩件事這裡擋：步驟 1 沒選不能下一步、沒取基準不能儲存。取消不還原（同官方），會提示。
 */
@Composable
internal fun QsWizardPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    fun s(k: String) = CalText.get(k, host.lang)
    var step by remember { mutableStateOf(1) }
    var fuel by remember { mutableStateOf<Boolean?>(null) }
    var down by remember { mutableStateOf<Boolean?>(null) }
    var zero by remember { mutableStateOf<Double?>(null) }
    var tension by remember { mutableStateOf<Boolean?>(null) }
    var ten by remember { mutableStateOf<Double?>(null) }
    var comp by remember { mutableStateOf<Double?>(null) }
    var done by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(100); tick++ } }
    val ad = remember(tick) { host.live(QS_AD) }
    // 即時判斷門檻
    LaunchedEffect(tick) {
        val z = zero ?: return@LaunchedEffect
        val a = ad ?: return@LaunchedEffect
        if (step == 3) when {
            a < z - 50 -> { tension = true; ten = a; comp = null }
            a > z + 50 -> { tension = false; comp = a; ten = null }
            else -> { tension = null; ten = null; comp = null }
        } else if (step == 4) {
            if (tension == true && a > z + 50) comp = a
            if (tension == false && a < z - 50) ten = a
        }
    }
    CalFrame(host, s("QS_Wizard"), listOf(29, 9, 10), listOf(26, 34, 55, QS_AD), onBack, msg) {
        if (!host.online) { Warn(s("cal_need_online")); return@CalFrame }
        if (step == 1) {
            Card(title = s("Step1_CutBy")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(s("QS_Fuel"), on = fuel == true, modifier = Modifier.weight(1f).testTag("qsw.fuel")) { fuel = true }
                    Pill(s("QS_Spark"), on = fuel == false, modifier = Modifier.weight(1f).testTag("qsw.spark")) { fuel = false }
                }
            }
            if (fuel != null) Card(title = s("Step2_ShifterDown")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(s("QS_Enable"), on = down == true, modifier = Modifier.weight(1f).testTag("qsw.down.on")) { down = true }
                    Pill(s("QS_Disable"), on = down == false, modifier = Modifier.weight(1f).testTag("qsw.down.off")) { down = false }
                }
            }
            Note(s("cal_qsw_enable_note"))
            Pill(s("Next_Arrow"), fuel != null && down != null, Modifier.testTag("qsw.next")) {
                step = 3
                // 進步驟 3：快排總開關打開寫進 ECU
                val opt = D1(host, 29)
                if (opt.present) report(host, msg, opt.setRaw(opt.raw or 1L))
            }
        } else {
            Card(title = s(if (step == 3) "Step3_GearUpThreshold" else "Step4_GearDownThreshold")) {
                QsBar(ten, comp, ad, zero)
                LabeledRow(s("Shift_Dir")) { Text(when (tension) { true -> s("QS_Tension"); false -> s("QS_compress"); null -> "--" }) }
                LabeledRow(s("Ten_Threshold")) { Text(ten?.let { num(it, 0) } ?: "--", modifier = Modifier.testTag("qsw.ten")) }
                LabeledRow(s("Comp_Threshold")) { Text(comp?.let { num(it, 0) } ?: "--", modifier = Modifier.testTag("qsw.comp")) }
                Note(s("Step2_Description"))
                if (zero == null) {
                    Warn(s("ReleaseShiftPedal"))
                    Pill(s("ReleasePedal"), ad != null, Modifier.testTag("qsw.released")) { zero = ad }
                } else {
                    Pill(s("SaveQBValue"), tension != null, Modifier.testTag("qsw.save")) {
                        if (step == 3 && down == true) { step = 4; return@Pill }
                        val opt = D1(host, 29)
                        var b = opt.raw
                        b = if (fuel == true) b or 0x03 else (b or 0x01) and 0x02L.inv()
                        b = if (tension == true) b or 0x04 else b and 0x04L.inv()
                        b = if (down == true) b or 0x08 else b and 0x08L.inv()
                        val rs = listOf(opt.setRaw(b), D1(host, 9).set(ten ?: 0.0), D1(host, 10).set(comp ?: 1000.0))
                        rs.forEach { report(host, msg, it) }
                        if (rs.none { it is EditResult.Refused }) done = true
                    }
                }
            }
        }
    }
    if (done) AlertDialog(
        onDismissRequest = { done = false; onBack() },
        title = { Text(s("QS_AlertDialog_Title")) },
        text = { Text(s("QS_AlertDialog_ShiftUp_OK")) },
        confirmButton = { TextButton(onClick = { done = false; onBack() }, modifier = Modifier.testTag("qsw.done")) { Text(s("QS_DONE")) } },
    )
}

/**
 * R.Button 設定：1388 大燈（0 原廠／1 手動）、
 * 1387 指示燈模式（0 關、1 TCS、2 換檔、4 過溫；官方點一下循環 0→1→2→4），模式 2 顯示 1389 換檔轉速（步進 50），
 * 模式 4 顯示 1390 過溫溫度。
 */
@Composable
internal fun RButtonPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    fun s(k: String) = CalText.get(k, host.lang)
    CalFrame(host, title, listOf(1388, 1387, 1389, 1390), MONITOR_DEFAULT, onBack, msg) {
        val head = D1(host, 1388)
        Card {
            if (!head.present) MissingRow(host, s("RIND_HeadLight"), 1388)
            else {
                SwitchRow(host, s("RIND_HeadLight"), head.raw > 0, head.writable || !host.online, onText = s("RIND_HeadManual"), offText = s("RIND_HeadStock"), tag = "rb.head") { on ->
                    report(host, msg, head.setRaw(if (on) 1 else 0))
                }
                if (head.raw > 0) Note(s("RIND_Notice_B").let { t -> t.substringBefore(".Press").let { if (it.length < t.length) "$it." else t } })
                TableState(host, 1388)
            }
        }
        val ind = D1(host, 1387)
        Card {
            if (!ind.present) MissingRow(host, s("RIND_Indicator"), 1387)
            else {
                ChoiceRow(s("RIND_Indicator"), listOf(0L to s("RIND_OFF"), 1L to s("RIND_TCSActive"), 2L to s("RIND_ShiftGear"), 4L to s("RIND_OverTemp")), ind.raw, ind.writable || !host.online, tag = "rb.ind", sub = s("RIND_Notice_A")) { v ->
                    report(host, msg, ind.setRaw(v))
                }
                TableState(host, 1387)
            }
        }
        if (ind.raw == 2L) Card { AdjustRow(host, D1(host, 1389), step = 50.0, msg = msg, tag = "rb.rpm") }
        if (ind.raw == 4L) Card { AdjustRow(host, D1(host, 1390), msg = msg, tag = "rb.temp") }
    }
}

/**
 * 騎乘動力陣列：列＝R 鈕可切的功能表（依機型、C 版本、車型），
 * 欄＝地圖槽（表 1040 開頭遞增的值），格＝那個槽位下這個功能用哪一檔。點格子選值，停手後整列（9 格）寫入。
 * 目前使用中的槽位＝即時變數 144（MapNum）－1，欄名標成主色。改 YECVT（1574）會先跳 eCVT 風險提醒。
 */
@Composable
internal fun MatrixPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val c = LocalCalColors.current
    fun s(k: String) = CalText.get(k, host.lang)
    val rows = Matrix.rows(host).filter { host.has(it) }
    val cols = Matrix.columns(host)
    var pick by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var warnCvt by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(300); tick++ } }
    val curMap = remember(tick) { host.live(144)?.toInt()?.minus(1) }
    CalFrame(host, title, rows, listOf(26, 34, 55, 144), onBack, msg) {
        if (cols.isEmpty() || rows.isEmpty()) { Warn(CalText.format("cal_missing", host.lang, 1040)); return@CalFrame }
        val scroll = rememberScrollState()
        Column(Modifier.horizontalScroll(scroll)) {
            Row {
                Box(Modifier.width(150.dp).height(32.dp))
                cols.forEachIndexed { i, m ->
                    Box(Modifier.width(76.dp).height(32.dp).background(if (i == curMap) c.brand else c.card, RoundedCornerShape(6.dp)).padding(2.dp), contentAlignment = Alignment.Center) {
                        Text("MAP $m", color = if (i == curMap) c.onBrand else c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            for (id in rows) {
                val d = D1(host, id)
                val en = d.writable || !host.online
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(150.dp).padding(end = 4.dp)) {
                        Text(Matrix.rowName(host, id), color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        TableState(host, id)
                    }
                    for (col in cols.indices) {
                        val cell = D1(host, id, 0, col)
                        Box(
                            Modifier.width(76.dp).height(40.dp).padding(horizontal = 2.dp).background(c.card, RoundedCornerShape(6.dp))
                                .border(1.dp, if (pick == id to col) c.accent else c.line, RoundedCornerShape(6.dp))
                                .clickable(enabled = en) { pick = id to col }.testTag("mx.$id.$col"),
                            contentAlignment = Alignment.Center,
                        ) { Text(Matrix.text(host, id, cell.value), color = c.text, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Note(s("cal_matrix_note"))
    }
    pick?.let { (id, col) ->
        val opts = Matrix.options(host, id)
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(Matrix.rowName(host, id)) },
            text = {
                Column {
                    for ((v, name) in opts) Text(
                        name, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable {
                            report(host, msg, D1(host, id, 0, col).set(v))
                            pick = null
                            if (id == 1574) warnCvt = true
                        }.padding(vertical = 10.dp).testTag("mx.opt.$v"),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { pick = null }) { Text(s("cal_cancel")) } },
        )
    }
    if (warnCvt) AlertDialog(
        onDismissRequest = { warnCvt = false },
        title = { Text(s("CVT_Warning_Title")) },
        text = { Text((1..4).joinToString("\n\n") { s("CVT_Warning$it") }) },
        confirmButton = { TextButton(onClick = { warnCvt = false }) { Text(s("CVT_Risk_Confirm")) } },
    )
}

/** 騎乘動力陣列的列、欄、選項。 */
object Matrix {
    fun rows(host: CalHost): List<Int> {
        val base = listOf(1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053, 1054, 1056, 1267, 1293)
        val model = host.tune.file.modelId
        val cv = host.cCode
        return when (host.node) {
            37 -> base + listOf(1400, 1509)
            33, 34 -> base + 1400 + buildList {
                if (model in 60..62) add(1426)
                if (model in setOf(71, 72, 74)) add(1509)
                if (model == 61 && host.node == 34) add(1574)
            }
            4 -> base
            else -> listOf(1046, 1047, 1049, 1050, 1051, 1052, 1053, 1054, 1056) + buildList {
                if (cv == null) { addAll(listOf(1048, 1267, 1293, 1426, 1400)); return@buildList }
                if (cv >= 6) addAll(listOf(1048, 1267, 1293))
                if (cv >= 6 && model in 60..62) add(if (cv < 12) 1057 else 1426)
                if (cv >= 12) add(1400)
            }
        }
    }

    /** 表 1040 從第 0 格起遞增的值（遇到不再遞增就停）。 */
    fun columns(host: CalHost): List<Int> {
        val def = host.tune.definition(1040) ?: return emptyList()
        val row = host.rowsOf(1040)?.firstOrNull() ?: return emptyList()
        val out = ArrayList<Int>()
        for (raw in row) {
            val v = Convert.rawToPhysical(def, raw).toInt()
            if (out.isNotEmpty() && out.last() >= v) break
            if (v <= 0 && out.isEmpty()) break
            out += v
        }
        return out.take(9)
    }

    fun rowName(host: CalHost, id: Int): String {
        val k = when (id) {
            1046 -> "RBut_AFR"; 1047 -> "RBut_SA"; 1048 -> "RBut_EngBreak"; 1049 -> "RBut_BackFire_Open"; 1050 -> "RBut_BackFire_Close"
            1051 -> "RBut_BackFire_QS"; 1052 -> "RBut_IdleLope"; 1053 -> "RBut_TCS"; 1054 -> "RBut_LC"; 1056 -> "RBut_DFCO"
            1057 -> "RBut_eBoost"; 1267 -> "RBut_IdleOffset"; 1293 -> "RBut_HighIdle"; 1400 -> "RBut_EXUP"; 1426 -> "RBut_eBoostMode"
            1509 -> "RBut_ETC_Power"; 1574 -> "RBut_eCVT"; else -> null
        }
        return k?.let { CalText.get(it, host.lang) } ?: host.tableTitle(id)
    }

    /** 選項（顯示值, 字串），順序照 XTune 的對話框。 */
    fun options(host: CalHost, id: Int): List<Pair<Double, String>> {
        fun s(k: String) = CalText.get(k, host.lang)
        fun idx(vararg keys: String) = keys.mapIndexed { i, k -> i.toDouble() to s(k) }
        val lv = (1..9).map { "RBut_Lv$it" }.toTypedArray()
        return when (id) {
            1056, 1057, 1293, 1051 -> idx("RBut_OFF", "RBut_ON")
            1267 -> listOf(300, 200, 100, 50, 0, -50, -100, -200, -300).map { it.toDouble() to (if (it > 0) "+$it" else "$it") }
            1046 -> (1..3).map { it.toDouble() to s("RBut_AFRTarget$it") }
            1047 -> (1..3).map { it.toDouble() to s("RBut_SAOffset$it") }
            1048 -> (1..3).map { it.toDouble() to s("RBut_Lv$it") }
            1049, 1050 -> idx("RBut_OFF", *lv)
            1052 -> idx("RBut_OFF", *(1..9).map { "RBut_Sound$it" }.toTypedArray())
            1053, 1054 -> if (host.isMini) listOf(0.0 to s("RBut_OFF"), 1.0 to s("RBu_Min"), 4.0 to s("RBu_Med"), 7.0 to s("RBu_Max"))
                else (0..7).map { it.toDouble() to (if (it == 0) s(if (id == 1053) "RBut_OFF" else "RBut_Lv0") else s("RBut_Lv$it")) }
            1426 -> if (host.isMini) idx("eBoostM_Disable_M", "eBoostM_Normal_M", "eBoostM_Street_M") else idx("eBoostM_Disable_M", "eBoostM_Normal_M", "eBoostM_Street_M", "eBoostM_Track_M")
            1574 -> idx("YCVT_Auto", "YCVT_MT")
            1509 -> if (host.node == 37) idx("ETC_Default", "ETC_Power1", "ETC_Power2", "ETC_Power3") else idx("ETC_Default", "ETC_Power1", "ETC_Power2", "ETC_Power3", "ETC_Power4")
            1400 -> idx("EXUP_OFF", "EXUP_ON", "EXUP_RPM", "EXUP_MAP")
            else -> D1(host, id).let { d -> ((d.min.toInt())..(d.max.toInt())).map { it.toDouble() to it.toString() } }
        }
    }

    /** 格子裡的字。 */
    fun text(host: CalHost, id: Int, v: Double): String {
        fun s(k: String) = CalText.get(k, host.lang)
        return when (id) {
            1049, 1050, 1052 -> if (v == 0.0) "OFF" else num(v, 0)
            1053, 1054 -> if (host.isMini) s(listOf("RBut_OFF", "RBu_Min", "RBu_Med", "RBu_Max")[listOf(0.0, 1.0, 4.0, 7.0).indexOfFirst { v <= it }.let { if (it < 0) 3 else it }]) else if (v == 0.0) "OFF" else num(v, 0)
            1267 -> when { v >= 300 -> "+300"; v >= 192 -> "+200"; v >= 96 -> "+100"; v >= 48 -> "+50"; v >= 0 -> "0"; v <= -300 -> "-300"; v <= -192 -> "-200"; v <= -96 -> "-100"; else -> "-50" }
            1051 -> if (v != 0.0) "ON" else "OFF"
            1056, 1293, 1057 -> if (v == 1.0) "ON" else "OFF"
            else -> options(host, id).firstOrNull { it.first == v }?.second ?: num(v, 0)
        }
    }
}
