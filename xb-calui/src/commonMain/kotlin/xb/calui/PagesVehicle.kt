package xb.calui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xb.tune.Cell
import xb.tune.Convert
import xb.tune.EditResult
import kotlin.math.PI

/** 表名是不是 [name]（SxS 的控制檔同一個表號對到別的表，官方只看機型；這裡多檢查表名）。 */
fun CalHost.named(id: Int, name: String): Boolean = tune.definition(id)?.name == name && has(id)

/**
 * 一列（D2）的曲線編輯：每格一根長條，點選後 −／＋ 一個步進；[hiOf] 給每格自己的上限（eBoost 扭力的 1568）。
 * 改了寫進調校檔，停手後由宿主整列送出。
 */
@Composable
private fun RowCurve(host: CalHost, id: Int, labels: List<String>, step: Double, hiOf: ((Int) -> Double?)? = null, msg: PageMsg) {
    val c = LocalCalColors.current
    val def = host.tune.definition(id)
    val row = host.rowsOf(id)?.firstOrNull()
    if (def == null || row == null || !host.has(id)) { MissingRow(host, host.tableTitle(id), id); return }
    var sel by remember { mutableStateOf(0) }
    val en = host.writable(id) || !host.online
    val vals = row.map { Convert.rawToPhysical(def, it) }
    val max = maxOf(def.max, def.min); val min = minOf(def.max, def.min)
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        vals.forEachIndexed { i, v ->
            Column(Modifier.weight(1f).clickable { sel = i }.testTag("curve.$id.$i"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(num(v, def.decimals.coerceIn(0, 3)), fontSize = 10.sp, color = c.text)
                Box(Modifier.fillMaxWidth().height((110 * ((v - min) / (max - min)).coerceIn(0.02, 1.0)).dp).background(if (i == sel) c.brand else c.line, RoundedCornerShape(3.dp)))
                Text(labels.getOrElse(i) { "$i" }, fontSize = 9.sp, color = c.muted, maxLines = 1)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val hi = hiOf?.invoke(sel)?.let { minOf(it, max) } ?: max
        fun set(x: Double) {
            val r = host.tune.setValues(id, mapOf(Cell(0, sel) to x.coerceIn(min, hi)))
            if (r is EditResult.Done && r.changed > 0) host.edited(id)
            report(host, msg, r)
        }
        Text(labels.getOrElse(sel) { "$sel" }, color = c.muted, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Pill("−", en, Modifier.testTag("curve.$id.dec")) { set(vals[sel] - step) }
        Text(num(vals[sel], def.decimals.coerceIn(0, 3)), fontFamily = c.digits, fontSize = 20.sp, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Pill("＋", en, Modifier.testTag("curve.$id.inc")) { set(vals[sel] + step) }
    }
    TableState(host, id)
}

/**
 * eBoost：表 1230 低 4 位元（功能、賽道／街道、指示燈、電瓶種類）、
 * 1202 作動時間、1418 作動模式、1391 觸發靈敏度。C 版本 <12（有連線）用功能／模式兩個開關，≥12 用模式與靈敏度兩個數值；Mini 沒有模式開關。
 */
@Composable
internal fun EBoostPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    fun s(k: String) = CalText.get(k, host.lang)
    val old = host.cCode?.let { it < 12 } == true
    CalFrame(host, CalText.get("CalTitle_eBoost", host.lang).takeIf { CalText.has("CalTitle_eBoost") } ?: title, listOf(1230, 1202, 1418, 1391), listOf(26, 34, 55, 1398), onBack, msg) {
        val opt = D1(host, 1230)
        Card {
            if (!opt.present) MissingRow(host, host.tableTitle(1230), 1230)
            else {
                val en = opt.writable || !host.online
                if (old) SwitchRow(host, s("eB_eBoostFunc"), opt.bit(0), en, onText = s("eB_On"), offText = s("eB_Off"), tag = "eb.b0") { report(host, msg, opt.toggleBit(0)) }
                if (old && !host.isMini) SwitchRow(host, s("eB_eBoostMode"), opt.bit(1), en, onText = s("eB_Race"), offText = s("eB_Street"), tag = "eb.b1") { report(host, msg, opt.toggleBit(1)) }
                SwitchRow(host, s("eB_eBoostIndicator"), opt.bit(2), en, onText = s("eB_On"), offText = s("eB_Off"), tag = "eb.b2") { report(host, msg, opt.toggleBit(2)) }
                SwitchRow(host, s("eB_eBoostBatteryType"), opt.bit(3), en, onText = s("eB_LiFe"), offText = s("eB_LeadAcid"), tag = "eb.b3") { report(host, msg, opt.toggleBit(3)) }
                TableState(host, 1230)
            }
        }
        if (!old) Card { ValueControl(host, D1(host, 1418), msg, hi = if (host.isMini) 2.0 else null) }
        Card { AdjustRow(host, D1(host, 1202), bar = false, msg = msg, tag = "eb.1202") }
        if (!old) Card { AdjustRow(host, D1(host, 1391), bar = false, msg = msg, tag = "eb.1391") }
    }
}

/**
 * eBoost：分頁 1：指示燈、電瓶種類（1230 bit2／bit3）、模式 1418、
 * 時間 1202、靈敏度 1391、充電能量 1566（60–100%，步進 10；車型 62 沒有）；分頁 2：輸出扭力曲線 1567（9 點 0–8 秒、步進 10），
 * Mini XX 車型 60–62 每格上限是 1568 同格。SxS 的控制檔同號是別的表，所以看表名。
 */
@Composable
internal fun EBoostX2Page(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    fun s(k: String) = CalText.get(k, host.lang)
    var tab by remember { mutableStateOf(0) }
    val model = host.tune.file.modelId
    val ids = listOf(1230, 1202, 1418, 1391, 1566, 1567)
    CalFrame(host, title, ids, listOf(26, 34, 55, 1398), onBack, msg, header = {
        val names = listOf(CalText.get("CalTitle_eBoost", host.lang).takeIf { CalText.has("CalTitle_eBoost") } ?: title) + (if (model != 62) listOf(s("YEB_Torque_Title")) else emptyList())
        TabBar(names, tab) { tab = it.coerceIn(0, names.size - 1) }
    }) {
        if (!host.named(1230, "D1MGU_eBoost_Option")) { Warn(CalText.format("cal_missing", host.lang, 1230)); return@CalFrame }
        if (tab == 0) {
            val opt = D1(host, 1230)
            Card {
                val en = opt.writable || !host.online
                SwitchRow(host, s("eB_eBoostIndicator"), opt.bit(2), en, onText = s("eB_On"), offText = s("eB_Off"), tag = "ebx.b2") { report(host, msg, opt.toggleBit(2)) }
                SwitchRow(host, s("eB_eBoostBatteryType"), opt.bit(3), en, onText = s("eB_LiFe"), offText = s("eB_LeadAcid"), tag = "ebx.b3") { report(host, msg, opt.toggleBit(3)) }
                TableState(host, 1230)
            }
            Card { ValueControl(host, D1(host, 1418), msg, hi = if (host.isMini) 2.0 else null) }
            Card { AdjustRow(host, D1(host, 1202), bar = false, msg = msg, tag = "ebx.1202") }
            Card { AdjustRow(host, D1(host, 1391), bar = false, msg = msg, tag = "ebx.1391") }
            if (model != 62 && host.named(1566, "D1MGU_ChargePower")) Card { AdjustRow(host, D1(host, 1566), step = 10.0, lo = 60.0, bar = false, msg = msg, tag = "ebx.1566") }
        } else {
            Card(title = s("YEB_Torque_Title")) {
                val limit = host.isMini && model in 60..62 && host.has(1568)
                RowCurve(host, 1567, (0..8).map { "${it}s" }, 10.0, if (limit) { i -> D1(host, 1568, 0, i).value } else null, msg)
                Note(s("YEB_HeadCol0_Title"))
            }
        }
    }
}

/**
 * EXUP 排氣閥門：1401 作動方式（關閉／開啟／自動 RPM／自動 MAP），
 * 1402 啟動暖機秒數（1401>0 才能改），1403 作動轉速（自動 RPM 才顯示，步進 50），1405 9×9 開關圖（自動 MAP 才顯示，格子只有 0／1）。
 */
@Composable
internal fun ExupPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val mode = D1(host, 1401)
    CalFrame(host, title, listOf(1401, 1402, 1403), MONITOR_DEFAULT, onBack, msg, scroll = mode.raw != 3L) {
        Card { ValueControl(host, mode, msg) }
        if (mode.raw > 0) Card { AdjustRow(host, D1(host, 1402), bar = false, msg = msg, tag = "exup.1402") }
        if (mode.raw == 2L) Card { AdjustRow(host, D1(host, 1403), step = 50.0, msg = msg, tag = "exup.1403") }
        if (mode.raw == 3L) host.TableEditor(listOf(1405).filter { host.has(it) }, Modifier.fillMaxWidth().weight(1f))
    }
}

/**
 * 自動風鏡：1009 開關、1133 升起車速（開關開才能改；下限 11，0 會被洗成 11，同官方）。
 */
@Composable
internal fun WindShieldPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    CalFrame(host, title, listOf(1009, 1133), MONITOR_DEFAULT, onBack, msg) {
        if (!host.named(1009, "D1WindShield_Option")) { Warn(CalText.format("cal_missing", host.lang, 1009)); return@CalFrame }
        val sw = D1(host, 1009)
        Card { ValueControl(host, sw, msg, label = CalText.get("WindShield_Option", host.lang)) }
        if (sw.raw != 0L) Card { AdjustRow(host, D1(host, 1133), CalText.get("WindShield_UpSpeed", host.lang), msg = msg, tag = "ws.1133") }
    }
}

/** ECU 燈效的調色盤。 */
private val PALETTE = listOf(
    Triple(255, 16, 0), Triple(255, 62, 0), Triple(255, 124, 0), Triple(255, 187, 0), Triple(255, 249, 0),
    Triple(193, 255, 0), Triple(127, 255, 0), Triple(61, 255, 0), Triple(0, 255, 4), Triple(0, 255, 67),
    Triple(0, 255, 130), Triple(0, 255, 192), Triple(0, 255, 255), Triple(0, 192, 255), Triple(0, 129, 255),
    Triple(0, 67, 255), Triple(0, 5, 255), Triple(61, 0, 255), Triple(127, 0, 255), Triple(193, 0, 255),
    Triple(255, 0, 249), Triple(255, 0, 187), Triple(255, 0, 125), Triple(255, 0, 62), Triple(255, 0, 0),
)

/**
 * ECU 燈效：不是表，是 CAN 0x689 的廠商訊框：
 * `F0 dev` 讀狀態（ECU 在 0x6A9 回 `F1 dev mode 亮度 R G B`）、`F2 dev mode 亮度 R G B` 設定。dev 0＝ECU 動態指示燈（有色彩）、1＝aRacer Power LED。
 * 模式 1 關、2 恆亮、3 閃爍、4 快閃、5 呼吸、6 變色（燈 1）、7 同步（燈 2；送 wire 6）。亮度 0–100；色彩 25 色。Mini XX 只有燈 1。
 */
@Composable
internal fun RgbLedPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    fun s(k: String) = CalText.get(k, host.lang)
    var dev by remember { mutableStateOf(1) }
    // 每個燈的狀態：UI 模式 1..7、亮度、RGB
    val state = remember { mutableStateListOf(listOf(5, 100, 255, 0, 0), listOf(2, 100, 0, 0, 0)) }
    var known by remember { mutableStateOf(setOf<Int>()) }
    val single = host.node == 33
    fun wire(m: Int) = if (m == 7) 6 else m - 1
    fun ui(w: Int, d: Int) = if (d == 2 && w == 6) 7 else w + 1
    suspend fun read(d: Int) {
        val r = host.raw(0x689, byteArrayOf(0xF0.toByte(), (d - 1).toByte()), reply = 0x6A9, count = 1, timeoutMs = 1500) {
            it.size >= 7 && (it[0].toInt() and 0xFF) == 0xF1 && (it[1].toInt() and 0xFF) == d - 1
        }
        r.onSuccess { f -> val b = f.first().map { it.toInt() and 0xFF }; state[d - 1] = listOf(ui(b[2], d), b[3], b[4], b[5], b[6]); known = known + d }
            .onFailure { msg.text = it.message }
    }
    var lastSend by remember { mutableStateOf(0L) }
    fun send(d: Int) = scope.launch {
        val v = state[d - 1]
        val r = host.raw(0x689, byteArrayOf(0xF2.toByte(), (d - 1).toByte(), wire(v[0]).toByte(), v[1].toByte(), v[2].toByte(), v[3].toByte(), v[4].toByte()), reply = null)
        r.onFailure { msg.text = it.message }
    }
    LaunchedEffect(host.online) { if (host.online) { read(1); if (!single) read(2) } }
    CalFrame(host, title, emptyList(), listOf(34, 27, 33, 29), onBack, msg, header = {
        val names = listOf(s("RGB_WiFi_Title")) + if (single) emptyList() else listOf(s("RGB_Logo_Title"))
        TabBar(names, dev - 1) { dev = (it + 1).coerceIn(1, names.size) }
    }) {
        if (!host.online) { Warn(s("cal_need_online")); return@CalFrame }
        val v = state[dev - 1]
        Card {
            val modes = (if (dev == 1) listOf(1, 2, 3, 4, 5, 6) else listOf(1, 2, 3, 4, 5, 7))
            ChoiceRow(s("LED_ModeSet"), modes.map { it.toLong() to s(listOf("", "LED_MODE_OFF", "LED_MODE_ON", "LED_MODE_Flash", "LED_MODE_DualFlash", "LED_MODE_Brightness", "LED_MODE_Multi_Color", "LED_MODE_Sync")[it]) }, v[0].toLong(), true, tag = "rgb.mode") { m ->
                state[dev - 1] = v.toMutableList().also { it[0] = m.toInt() }; send(dev)
            }
            LabeledRow(s("LED_Lightness")) { Text("${v[1]}", modifier = Modifier.testTag("rgb.bright")) }
            Slider(value = v[1].toFloat(), valueRange = 0f..100f, onValueChange = { x ->
                state[dev - 1] = state[dev - 1].toMutableList().also { it[1] = x.toInt() }
                val now = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
                // 官方拖動時每 200 ms 送一次；放開再送一次
                if (now - lastSend >= 200) { lastSend = now; send(dev) }
            }, onValueChangeFinished = { send(dev) })
            if (dev == 1) {
                LabeledRow(s("LED_Color")) {
                    Box(Modifier.width(40.dp).height(20.dp).background(Color(v[2], v[3], v[4]), RoundedCornerShape(4.dp)))
                }
                Row(Modifier.fillMaxWidth()) {
                    PALETTE.forEachIndexed { i, (r, g, b) ->
                        Box(Modifier.weight(1f).height(28.dp).background(Color(r, g, b)).clickable {
                            state[0] = state[0].toMutableList().also { it[2] = r; it[3] = g; it[4] = b }; send(1)
                        }.testTag("rgb.color.$i"))
                    }
                }
            }
        }
        Pill(s("LED_Reset").takeIf { CalText.has("LED_Reset") } ?: s("cal_reset_default"), modifier = Modifier.testTag("rgb.reset")) {
            state[dev - 1] = if (dev == 1) listOf(5, 100, 255, 0, 0) else listOf(2, 100, 0, 0, 0); send(dev)
        }
        Note(s("LED_Message_1")); Note(s("LED_Message_2"))
        if (dev !in known) Note(s("cal_rgb_unknown"))
    }
}

/**
 * 保養提醒：不是表，是物件 0x200F（6 項 × 5 個 32 位元字：
 * flags〔bit0 時數模式、bit2 啟用、bit8 重置〕、間隔公尺、間隔秒、上次歸零總里程 km、上次歸零引擎秒）＋總里程（0x466E）、引擎累積秒（0x466F）。
 * 設定寫前 3 字（沒用的那個寫最小值：里程 1000 m、時數 3600 s）。
 */
@Composable
internal fun ServicePage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val scope = rememberCoroutineScope()
    val c = LocalCalColors.current
    fun s(k: String) = CalText.get(k, host.lang)
    val names = listOf("SI_Oil", "SI_GearOil", "SI_TransSys", "SI_SparkPlug", "SI_Coolant", "SI_BrakeFluid")
    val items = remember { mutableStateListOf<LongArray?>(null, null, null, null, null, null) }
    var mileage by remember { mutableStateOf<Long?>(null) }
    var engSec by remember { mutableStateOf<Long?>(null) }
    var edit by remember { mutableStateOf<Int?>(null) }
    fun le(b: ByteArray): Long = b.take(4).foldIndexed(0L) { i, acc, x -> acc or ((x.toLong() and 0xFF) shl (8 * i)) }
    suspend fun load() {
        mileage = host.readObject(0x466E, 0).getOrNull()?.let(::le)
        engSec = host.readObject(0x466F, 0).getOrNull()?.let(::le)
        for (i in 0 until 6) {
            val w = LongArray(5)
            for (k in 0 until 5) w[k] = host.readObject(0x200F, i * 5 + k).getOrElse { e -> msg.text = e.message; return }.let(::le)
            items[i] = w
        }
    }
    LaunchedEffect(host.online) { if (host.online) load() }
    CalFrame(host, s("SI_PageTitle"), emptyList(), emptyList(), onBack, msg) {
        if (!host.online) { Warn(s("cal_need_online")); return@CalFrame }
        names.forEachIndexed { i, key ->
            val w = items[i]
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s(key), color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Pill(s("SI_SetValue"), w != null, Modifier.testTag("si.set.$i")) { edit = i }
                }
                if (w == null) Note("…")
                else if (w[0] and 4L == 0L) Note(s("SI_NoSetting"))
                else {
                    val hours = w[0] and 1L == 1L
                    val (used, total, unit) = if (hours) Triple(((engSec ?: 0) - w[4]) / 3600.0, w[2] / 3600.0, "h") else Triple(((mileage ?: 0) - w[3]).toDouble(), w[1] / 1000.0, "km")
                    val left = maxOf(total - used, 0.0)
                    val pct = if (total > 0) (used / total).coerceIn(0.0, 1.0) else 0.0
                    Text("${num(left, 1)} $unit / ${num(total, 1)} $unit", color = c.text, fontSize = 13.sp)
                    Box(Modifier.fillMaxWidth().height(8.dp).background(c.line, RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(pct.toFloat()).height(8.dp).background(if (pct >= 1.0) c.danger else c.brand, RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
        Note("ODO ${mileage ?: "—"} km · ${engSec?.let { num(it / 3600.0, 1) } ?: "—"} h")
    }
    edit?.let { i ->
        val w = items[i] ?: return@let
        var hours by remember(i) { mutableStateOf(w[0] and 1L == 1L) }
        var enabled by remember(i) { mutableStateOf(w[0] and 4L != 0L) }
        var reset by remember(i) { mutableStateOf(false) }
        var text by remember(i) { mutableStateOf(if (w[0] and 1L == 1L) (w[2] / 3600).toString() else (w[1] / 1000).toString()) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(CalText.format("SI_DialogTitle", host.lang, s(names[i]))) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("km", on = !hours) { hours = false }; Pill("hours", on = hours) { hours = true }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled, { enabled = it }); Text(s("SI_Dialog_EnSet")) }
                    OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.testTag("si.value"))
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(reset, { reset = it }); Text(s("SI_Dialog_Reset")) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().toDoubleOrNull() ?: return@TextButton
                    val flags = (if (hours) 1L else 0L) or (if (enabled) 4L else 0L) or (if (reset) 256L else 0L)
                    val km = if (hours) 1000L else maxOf(kotlin.math.round(v).toLong() * 1000, 1000)
                    val sec = if (hours) maxOf(kotlin.math.round(v).toLong() * 3600, 3600) else 3600L
                    edit = null
                    scope.launch {
                        for ((k, value) in listOf(flags, km, sec).withIndex()) {
                            host.command(0x200F, i * 5 + k, value, 4)?.let { msg.text = it; return@launch }
                        }
                        msg.text = s("cal_cmd_sent"); load()
                    }
                }, modifier = Modifier.testTag("si.ok")) { Text(s("SI_Set")) }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text(s("SI_Exit")) } },
        )
    }
}

/** 輪胎與齒比算出「CVT 比值 1.0 時每 RPM 幾 km/h」（1.12 是官方常數）。 */
private fun kmhPerRpm(host: CalHost): Double? {
    val w = D1(host, 142).takeIf { it.present }?.value ?: return null
    val a = D1(host, 143).takeIf { it.present }?.value ?: return null
    val d = D1(host, 144).takeIf { it.present }?.value ?: return null
    val gain = D1(host, 33).takeIf { it.present }?.value ?: return null
    val fd = D1(host, 1542).takeIf { it.present }?.value?.takeIf { it > 0 } ?: return null
    val circ = ((a / 100 * w) * 2 + d * 25.4) * PI / 1000
    return circ * 1.12 * gain * 60 / 1000 / fd
}

/** 等比例重映射：改頭或尾，中間照原本的相對位置跟著移。 */
private fun remap(a: DoubleArray, index: Int, v: Double): DoubleArray {
    val n = a.size - 1
    val s0 = a[0]; val e0 = a[n]
    val s1 = if (index == 0) v else s0; val e1 = if (index == n) v else e0
    val out = DoubleArray(a.size); out[0] = s1; out[n] = e1
    val span = e0 - s0
    for (i in 1 until n) out[i] = if (kotlin.math.abs(span) < 1e-12) (e1 - s1) * i / n + s1 else (e1 - s1) * ((a[i] - s0) / span) + s1
    return out
}

/**
 * eCVT 自排：先看風險說明。分頁：基礎設定（1553 最高轉速、1537 換檔速度、
 * 1538 引擎煞車、1501 保護〔Super XX〕）、T-Mode（1534 起始／1536 結束）、S-Mode（1533／1535），每條 5 點（TPS 0／5／30／70／100%），
 * 圖＝RPM 對車速，起始點畫在 1550 比值線、結束點在 1549。加減一次 50（官方長按 100）；0% 改起點、100% 是最大馬力轉速（起止一起改），
 * 兩端改了中間等比例移；Mini XX 只能改 0% 與 100%。馬力機測試模式 1578 在看過風險後才出現。恢復預設＝1577 四列＋1553＝最大值＋1000。
 */
@Composable
internal fun ECvtAtPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    fun s(k: String) = CalText.get(k, host.lang)
    var tab by remember { mutableStateOf(0) }
    var risk by remember { mutableStateOf(false) }
    var pt by remember { mutableStateOf(-1) }
    val ids = listOf(1553, 1537, 1538, 1501, 1533, 1534, 1535, 1536, 1578)
    CalFrame(host, title, ids, listOf(26, 34, 55, 1831), onBack, msg, watch = listOf(51), header = {
        TabBar(listOf(s("CVT_Title_BaseSetting"), s("CVT_Title_TMode"), s("CVT_Title_SMode")), tab) { tab = it.coerceIn(0, 2); pt = -1 }
    }) {
        if (!risk) {
            Card(title = s("CVT_Warning_Title")) {
                (1..4).forEach { Note(s("CVT_Warning$it")) }
                Pill(s("CVT_Risk_Confirm"), modifier = Modifier.testTag("cvt.risk")) { risk = true }
            }
            return@CalFrame
        }
        val dyno = D1(host, 1578)
        if (dyno.present && (tab == 0 || dyno.raw == 1L)) Card {
            SwitchRow(host, s("CVT_Dyno_Mode"), dyno.raw == 1L, dyno.writable || !host.online, tag = "cvt.dyno") { on -> report(host, msg, dyno.setRaw(if (on) 1 else 0)) }
            TableState(host, 1578)
        }
        val locked = dyno.raw == 1L
        if (tab == 0) {
            Card { AdjustRow(host, D1(host, 1553), s("CVT_MaxEngRPM"), step = 10.0, bar = false, msg = msg, tag = "cvt.1553", hi = if (locked) D1(host, 1553).value else null, lo = if (locked) D1(host, 1553).value else null) }
            Card { AdjustRow(host, D1(host, 1537), s("CVT_TCMLevel"), bar = false, msg = msg, tag = "cvt.1537") }
            Card { AdjustRow(host, D1(host, 1538), s("CVT_EngBrakeLevel"), bar = false, msg = msg, tag = "cvt.1538") }
            if (host.node == 34) Card {
                val p = D1(host, 1501)
                if (!p.present) MissingRow(host, s("CVT_Protection"), 1501)
                else {
                    var warnOff by remember { mutableStateOf(false) }
                    SwitchRow(host, s("CVT_Protection"), p.raw != 0L, p.writable || !host.online, tag = "cvt.1501") { on -> report(host, msg, p.setRaw(if (on) 1 else 0)); if (!on) warnOff = true }
                    TableState(host, 1501)
                    if (warnOff) AlertDialog(onDismissRequest = { warnOff = false }, title = { Text(s("CVT_Protection_Title")) }, text = { Text(s("CVT_Protection_Warning")) },
                        confirmButton = { TextButton(onClick = { warnOff = false }) { Text(s("cal_ok")) } })
                }
            }
        } else {
            val (startId, endId) = if (tab == 1) 1534 to 1536 else 1533 to 1535
            val sd = host.tune.definition(startId); val ed = host.tune.definition(endId)
            val sr = host.rowsOf(startId)?.firstOrNull(); val er = host.rowsOf(endId)?.firstOrNull()
            if (sd == null || ed == null || sr == null || er == null || !host.has(startId) || !host.has(endId)) { Warn(CalText.format("cal_missing", host.lang, startId)); return@CalFrame }
            val start = DoubleArray(5) { Convert.rawToPhysical(sd, sr[it]) }
            val end = DoubleArray(5) { Convert.rawToPhysical(ed, er[it]) }
            val maxRpm = D1(host, 1553).value
            CvtChart(host, start, end, pt, Modifier.fillMaxWidth().aspectRatio(1.5f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("0%", "5%", "30%", "70%", "100%").forEachIndexed { i, n -> Pill(n, on = pt == i, modifier = Modifier.weight(1f).testTag("cvt.pt$i")) { pt = if (pt == i) -1 else i } }
            }
            fun apply(ns: DoubleArray, ne: DoubleArray) {
                val r1 = host.tune.setValues(startId, ns.indices.associate { Cell(0, it) to ns[it] })
                val r2 = host.tune.setValues(endId, ne.indices.associate { Cell(0, it) to ne[it] })
                if (r1 is EditResult.Done && r1.changed > 0) host.edited(startId)
                if (r2 is EditResult.Done && r2.changed > 0) host.edited(endId)
                report(host, msg, r1); report(host, msg, r2)
            }
            fun step(left: Boolean, d: Int) {
                when (pt) {
                    0 -> if (left) apply(remap(start, 0, (start[0] + d).coerceAtLeast(2000.0).coerceAtMost(start[4] - 100)), end)
                         else apply(start, remap(end, 0, (end[0] + d).coerceAtLeast(2000.0).coerceAtMost(start[4] - 100)))
                    4 -> { val y = (start[4] + d).coerceAtMost(maxRpm).coerceAtLeast(maxOf(start[0], end[0]) + 100); apply(remap(start, 4, y), remap(end, 4, y)) }
                    1, 2, 3 -> {
                        val a = (if (left) start else end).copyOf()
                        var v = (a[pt] + d).toInt()
                        if (v >= a[pt + 1].toInt()) v = a[pt + 1].toInt() - 10 else if (v <= a[pt - 1].toInt()) v = a[pt - 1].toInt() + 10
                        a[pt] = v.toDouble()
                        if (left) apply(a, end) else apply(start, a)
                    }
                }
            }
            val en = !locked && (host.writable(startId) || !host.online)
            if (pt in 0..4 && !(host.node == 33 && pt in 1..3)) Card {
                @Composable fun editor(label: String, v: Double, left: Boolean, tag: String) {
                    Text(label, color = LocalCalColors.current.muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("−", en, Modifier.testTag("$tag.dec")) { step(left, -50) }
                        Text(num(v, 0), fontFamily = LocalCalColors.current.digits, fontSize = 22.sp, color = LocalCalColors.current.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("$tag.value"))
                        Pill("＋", en, Modifier.testTag("$tag.inc")) { step(left, 50) }
                    }
                }
                if (pt == 4) editor(s("CVT_Max_HP_RPM"), start[4], true, "cvt.hp")
                else { editor(s("CVT_Start_RPM"), start[pt], true, "cvt.start"); editor(s("CVT_End_RPM"), end[pt], false, "cvt.end") }
                TableState(host, startId); TableState(host, endId)
            }
            if (host.node == 33) Note(s("cal_cvt_minixx"))
        }
        Pill(s("CVT_Reset"), !locked, Modifier.testTag("cvt.reset")) {
            val def = host.tune.definition(1577); val rows = host.rowsOf(1577)
            if (def == null || rows == null || rows.size < 4) { msg.text = CalText.format("cal_missing", host.lang, 1577); return@Pill }
            var top = 0.0
            listOf(1533, 1534, 1535, 1536).forEachIndexed { r, id ->
                val v = rows[r].map { Convert.rawToPhysical(def, it) }
                top = maxOf(top, v.last())
                val res = host.tune.setValues(id, v.indices.associate { Cell(0, it) to v[it] })
                if (res is EditResult.Done && res.changed > 0) host.edited(id)
                report(host, msg, res)
            }
            report(host, msg, D1(host, 1553).set(top + 1000))
        }
    }
}

/** eCVT 自排的圖：Y＝RPM、X＝車速；起始點在 1550 比值線、結束點在 1549，同一油門點的起止連線；即時游標（26 RPM、51 車速）。 */
@Composable
private fun CvtChart(host: CalHost, start: DoubleArray, end: DoubleArray, sel: Int, modifier: Modifier) {
    val c = LocalCalColors.current
    val k = kmhPerRpm(host)
    val lo = D1(host, 1550).value.takeIf { it > 0 } ?: 2.7
    val hi = D1(host, 1549).value.takeIf { it > 0 } ?: 0.75
    // 官方：yMax＝(int)(1553 ÷ (1549 − 0.02))、xMax＝係數 × yMax（兩軸等比例，比值線是斜直線）
    val yMax = (D1(host, 1553).value / (hi - 0.02)).toInt().toDouble().coerceAtLeast(1000.0)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(150); tick++ } }
    val rpm = remember(tick) { host.live(26) }; val spd = remember(tick) { host.live(51) }
    val colors = listOf(Color(0xFF00B0FF), Color(0xFF33C9E8), Color(0xFF66E280), Color(0xFFF2B84B), Color(0xFFFF6D00))
    Box(modifier.background(c.card, RoundedCornerShape(8.dp))) {
        if (k == null) { Text(CalText.format("cal_missing", host.lang, 142), color = c.muted, modifier = Modifier.align(Alignment.Center)); return@Box }
        Canvas(Modifier.fillMaxSize().padding(10.dp).clipToBounds()) {
            val xMax = k * yMax
            fun px(kmh: Double) = (kmh / xMax * size.width).toFloat()
            fun py(r: Double) = (size.height - r / yMax * size.height).toFloat()
            for (i in 0..6) drawLine(c.line, Offset(0f, size.height * i / 6), Offset(size.width, size.height * i / 6))
            drawLine(Color.White, Offset(0f, size.height), Offset(px(k * yMax / lo), py(yMax)), 3f)
            drawLine(Color.White, Offset(0f, size.height), Offset(px(k * yMax / hi), py(yMax)), 3f)
            for (i in 0 until 5) {
                val a = Offset(px(start[i] * k / lo), py(start[i])); val b = Offset(px(end[i] * k / hi), py(end[i]))
                val col = colors[i].copy(alpha = if (sel == -1 || sel == i) 1f else 0.45f)
                drawLine(col, a, b, if (sel == i) 6f else 4f)
                drawCircle(col, if (sel == i) 12f else 9f, a); drawCircle(col, if (sel == i) 12f else 9f, b)
            }
            if (rpm != null && spd != null) {
                val p = Offset(px(spd), py(rpm))
                drawLine(Color(0xD0E8E63E), Offset(p.x, 0f), Offset(p.x, size.height), 3f)
                drawLine(Color(0xD0E8E63E), Offset(0f, p.y), Offset(size.width, p.y), 3f)
                drawCircle(Color(0xFFFF4444), 8f, p)
            }
        }
        Text("RPM", color = c.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        Text("km/h", color = c.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
    }
}

/**
 * eCVT 手排：6 檔比值 1375–1380（顯示改成「N 檔最高車速」＝斷油轉速×係數÷比值），
 * 「>」比值變小（車速變高）、「<」比值變大；比值 ≥1.2 步進 0.025、<1.2 步進 0.01；相鄰檔至少差 0.025，1 檔上限 1550、6 檔下限 1549。
 * 恢復預設＝1550 到 1549 之間平均分 6 檔。
 */
@Composable
internal fun ECvtMtPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val c = LocalCalColors.current
    fun s(k: String) = CalText.get(k, host.lang)
    val gears = listOf(1375, 1376, 1377, 1378, 1379, 1380)
    var sel by remember { mutableStateOf(-1) }
    val k = kmhPerRpm(host)
    val os = D1(host, 8).value
    val lo = D1(host, 1550).value.takeIf { it > 0 } ?: 2.7
    val hi = D1(host, 1549).value.takeIf { it > 0 } ?: 0.75
    CalFrame(host, s("MTCVT_Title"), gears, listOf(26, 51, 34, 130), onBack, msg) {
        if (gears.any { !host.has(it) }) { Warn(CalText.format("cal_missing", host.lang, gears.first { !host.has(it) })); return@CalFrame }
        val ratios = gears.map { D1(host, it).value }
        val colors = listOf(Color(0xFF8C8CFF), Color(0xFF3DFFFF), Color(0xFF66FF99), Color(0xFFFFEE66), Color(0xFFFF8844), Color(0xFFFF4545))
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(c.card, RoundedCornerShape(8.dp))) {
            if (k != null && os > 0) Canvas(Modifier.fillMaxSize().padding(10.dp).clipToBounds()) {
                val yMax = (os / (hi - 0.02)).toInt().toDouble()
                val xMax = k * yMax
                fun px(kmh: Double) = (kmh / xMax * size.width).toFloat()
                fun py(r: Double) = (size.height - r / yMax * size.height).toFloat()
                drawLine(Color(0xFFA038F6), Offset(0f, py(os)), Offset(size.width, py(os)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                ratios.forEachIndexed { i, r ->
                    val top = Offset(px(os * k / r), py(os))
                    drawLine(colors[i].copy(alpha = if (sel == -1 || sel == i) 1f else 0.5f), Offset(0f, size.height), top, if (sel == i) 5f else 3f)
                    if (sel == i) drawLine(colors[i], top, Offset(top.x, size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                }
            }
            Text("OS_RPM ${num(os, 0)}", color = Color(0xFFA038F6), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..6).forEach { g -> Pill(listOf("1st", "2nd", "3rd", "4th", "5th", "6th")[g - 1], on = sel == g - 1, modifier = Modifier.weight(1f).testTag("mt.g$g")) { sel = if (sel == g - 1) -1 else g - 1 } }
        }
        if (sel >= 0) Card {
            val r = ratios[sel]
            val d = D1(host, gears[sel])
            val en = d.writable || !host.online
            val maxR = if (sel == 0) lo else ratios[sel - 1] - 0.025
            val minR = if (sel == 5) hi else ratios[sel + 1] + 0.025
            fun set(nr: Double) = report(host, msg, d.set(nr.coerceIn(minR, maxR)))
            val stepR = if (r >= 1.2) 0.025 else 0.01
            Text(CalText.format(when (sel) { 0 -> "MT_AdjTitle_1st"; 1 -> "MT_AdjTitle_2nd"; 2 -> "MT_AdjTitle_3rd"; else -> "MT_AdjTitle_nth" }, host.lang, sel + 1), color = c.muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("<", en, Modifier.testTag("mt.slower")) { set(r + stepR) }
                Text(if (k != null) num(os * k / r, 1) + " km/h" else "—", fontFamily = c.digits, fontSize = 22.sp, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("mt.speed"))
                Pill(">", en, Modifier.testTag("mt.faster")) { set(r - stepR) }
            }
            Note("ratio ${fixed(r, 3)}")
            TableState(host, gears[sel])
        }
        Pill(s("CVT_Reset"), modifier = Modifier.testTag("mt.reset")) {
            gears.forEachIndexed { i, id -> report(host, msg, D1(host, id).set(lo + (hi - lo) * i / 5)) }
        }
    }
}

/**
 * SxS 渦輪控制：三頁：1463 增壓閉迴路開關、1514 目標增壓（17×17 kPa）、
 * 1515 廢氣閥基礎值（17×17 %）。監控第 3 格：目標增壓頁顯示 1780 BoostTarget，其他顯示 1796 WasteGateDuty。
 */
@Composable
internal fun SxsBoostPage(host: CalHost, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    var tab by remember { mutableStateOf(0) }
    val names = listOf("SXS_D1Tb_BoostCLEn", "SXS_D3Tb_BoostTarget", "SXS_D3Tb_BoostDutyBase").map { CalText.get(it, host.lang) }
    CalFrame(host, title, if (tab == 0) listOf(1463) else emptyList(), listOf(26, 45, if (tab == 1) 1780 else 1796, 33), onBack, msg, scroll = tab == 0, header = {
        TabBar(names, tab) { tab = it.coerceIn(0, 2) }
    }) {
        when (tab) {
            0 -> Card { ValueControl(host, D1(host, 1463), msg, label = names[0]) }
            1 -> host.TableEditor(listOf(1514).filter { host.has(it) }, Modifier.fillMaxWidth().weight(1f))
            else -> host.TableEditor(listOf(1515).filter { host.has(it) }, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
