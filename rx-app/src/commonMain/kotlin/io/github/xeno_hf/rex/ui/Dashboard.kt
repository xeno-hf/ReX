package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.Repo
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import rx.defs.ChannelDef
import rx.session.Recorder
import io.github.xeno_hf.rex.Live

fun Live.bit(name: String): Boolean? =
    layout.columns.firstOrNull { it.def.name == name }?.let { layout.value(it, raw) > 0.5 }

/** 常用通道 absid（ControlFile_S3 #202601142）。 */
object Ch {
    const val AFR_WBO2 = 55
    const val RPM = 26
    const val SPEED = 51
    const val TPS = 34
    const val VBATT = 29
    const val T_ENG = 33
    const val MAP_NUM = 144
    const val MGU_A = 164
    const val GEAR = 130
}

/** 順序同 XTune 選單點按的輪替：進階 → 監控 → 巡航。 */
private enum class DashPage(val label: Int) { Race(R.string.dash_race), Monitor(R.string.dash_monitor), Cruise(R.string.dash_cruise) }

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun isLandscape(): Boolean = LocalWindowInfo.current.containerSize.let { it.width > it.height }

/** 儀表頁：進階／監控／巡航三頁左右滑（XTune 是點選單切換，RX 改成滑動＋頁籤）。 */
@Composable
fun DashboardScreen() {
    val repo = LocalRepo.current
    val pager = rememberPagerState(initialPage = repo.dashPage.coerceIn(0, 2)) { DashPage.entries.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { repo.dashPage = pager.currentPage }
    val land = isLandscape()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(top = if (land) 4.dp else 2.dp, bottom = 4.dp, start = if (land) 10.dp else 0.dp, end = if (land) 10.dp else 0.dp),
            horizontalArrangement = if (land) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DashPage.entries.forEachIndexed { i, p ->
                val sel = pager.currentPage == i
                Text(
                    stringResource(p.label), fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) RxColors.brandInk else RxColors.muted,
                    modifier = Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) RxColors.brand.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                )
            }
            if (land) { Spacer(Modifier.weight(1f)); LocalBarActions.current() }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 0) { page ->
            CompactScale {
                when (DashPage.entries[page]) {
                    DashPage.Race -> RaceDash()
                    DashPage.Monitor -> MonitorDash()
                    DashPage.Cruise -> CruiseDash()
                }
            }
        }
    }
}

/** 小螢幕直向：進階／巡航最多顯示 [SMALL_SLOT_CAP] 格參數，把空間讓給轉速盤（設定的格數不變，換回大螢幕照舊）。 */
private val LocalSmallDash = compositionLocalOf { false }
private const val SMALL_SLOT_CAP = 2

/**
 */
@Composable
private fun CompactScale(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val land = isLandscape()
        val small = !land && maxHeight < 560.dp
        val k = if (land) 1f else minOf(1f, maxWidth / 400.dp, maxOf(maxHeight / 640.dp, 0.75f))
        val d = LocalDensity.current
        CompositionLocalProvider(
            LocalSmallDash provides small,
            LocalDensity provides if (k >= 0.999f) d else Density(d.density * k, d.fontScale),
        ) { content() }
    }
}

/** 直向參數格要顯示幾格、還能不能再加（小螢幕上限 [SMALL_SLOT_CAP]）。 */
@Composable
private fun visibleSlots(group: String): Pair<Int, Boolean> {
    val n = LocalRepo.current.slotCount.collectAsState().value[group]!!
    val cap = if (LocalSmallDash.current) SMALL_SLOT_CAP else Repo.SLOT_MAX[group]!!
    return minOf(n, cap) to (n < cap)
}

// ---------------------------------------------------------------- 共用資料

/** 一次重組裡要用的即時值。 */
class DashData(val l: Live?, private val gpsSpeed: Double? = null, private val phoneGpsSpeed: Double? = gpsSpeed) {
    fun v(a: Int): Double? = if (a == io.github.xeno_hf.rex.PhoneGps.SPEED_ID) phoneGpsSpeed else DerivedChannels.value(a, l)
    fun bit(name: String): Boolean? = l?.bit(name)
    val rpm get() = v(Ch.RPM) ?: 0.0
    /** 設定「時速用手機 GPS」且有新鮮定位時用 GPS，否則用 ECU 車速。 */
    val speedIsGps get() = gpsSpeed != null
    val speed get() = gpsSpeed ?: v(Ch.SPEED)
    val speedUnit get() = if (speedIsGps) "km/h · GPS" else "km/h"
    /**
     * MGU_eBoost（原版 -1605）＝Running；沒在作動時能量 MGU_Energy_Percent #1398 滿 100％＝Ready；其餘 Charging。
     * 照使用者定的規則改成只看能量；沒收到能量通道時才退回看 Ready 位元。
     */
    val eBoostStatus: EbStatus?
        get() {
            val run = bit("MGU_Mode2.MGU_eBoost") ?: return null
            val full = v(1398)?.let { it >= 100.0 } ?: (bit("MGU_Mode4.MGU_eBoost_Ready") == true)
            return when { run -> EbStatus.Running; full -> EbStatus.Ready; else -> EbStatus.Charging }
        }
    /** 原版水溫圖示看 Mode1 bit0（Eng_Over_Heat）換紅色。 */
    val overheat get() = bit("Mode1.Eng_Over_Heat") == true
    /** IOModule_Indicator_Status #973 的第 [n] 位（原版 Pit＝bit4、LC＝bit2）。 */
    fun ioBit(n: Int): Boolean? = v(973)?.let { (it.toInt() shr n) and 1 == 1 }
    /** 原版引擎燈 icon_enginecheck 看 -160＝Malf8 #16 bit0（Malf_On，ECU 自己的總故障旗標）。 */
    val engineCheck get() = bit("Malf8.Malf_On") == true
}

@Composable
private fun rememberDash(): DashData {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    val useGps by repo.speedFromGps.collectAsState()
    val gpsOn by repo.gpsEnabled.collectAsState()
    repo.gps.fix.collectAsState().value // 定位更新時重組
    return DashData(live, if (useGps && gpsOn) repo.gps.fresh()?.speedKmh else null, if (gpsOn) repo.gps.fresh()?.speedKmh else null)
}

fun fmtVal(v: Double?, decimals: Int): String = v?.let { Recorder.fmt(it, decimals) } ?: "—"

/** 數值顏色分級：0 正常、1 注意、2 警告（只放確定有意義的門檻）。 */
fun levelOf(absid: Int, v: Double?): Int {
    v ?: return 0
    return when (absid) {
        Ch.T_ENG -> if (v >= 115) 2 else if (v >= 105) 1 else 0
        Ch.VBATT -> if (v < 11.5) 2 else if (v < 12.2 || v > 15.2) 1 else 0
        1435, 1436, 1437 -> if (v >= 70) 2 else if (v >= 60) 1 else 0
        165 -> if (v >= 100) 2 else if (v >= 85) 1 else 0
        72 -> if (v > 0) 1 else 0
        1434 -> if (v < 15) 2 else if (v < 30) 1 else 0
        else -> 0
    }
}

fun levelColor(level: Int, normal: Color = RxColors.text) = when (level) { 2 -> RxColors.danger; 1 -> RxColors.warn; else -> normal }

fun digits(size: TextUnit, weight: FontWeight = FontWeight.Bold, italic: Boolean = false) =
    TextStyle(fontFamily = Digits, fontWeight = weight, fontSize = size, lineHeight = size, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)

// ---------------------------------------------------------------- 進階

@Composable
private fun RaceDash() {
    val d = rememberDash()
    val peak = remember { PeakHold() }
    val pk = peak.update(d.rpm, d.l?.tNanos ?: 0L)
    if (isLandscape()) RaceLandscape(d, pk) else RacePortrait(d, pk)
}

@Composable
private fun rpmMaxK(): Int = LocalRepo.current.rpmMax.collectAsState().value

/**
 * 進階直向（XTune）：轉速盤 0 在正下、4 在正左、10 在正右，
 * 空出的右下象限放時速框；下方兩條長按可換（Gauge_Bar），底列水溫電瓶／兩顆多功能鈕／MAP。
 * 盤心留空，×1000 r/min 在 0 端點旁（同原版）。
 */
@Composable
private fun RacePortrait(d: DashData, peak: Double) {
    val slots by LocalRepo.current.slots.collectAsState()
    val maxK = rpmMaxK(); val scale = remember(maxK) { RpmScale.raceV(maxK) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side)) {
                RpmArcGauge(d.rpm, peak, scale, Modifier.fillMaxSize(), unit = "×1000 r/min", zoom = rememberLabelZoom(scale, d.rpm, 1.35f))
                // 圓心＝方框中心，半徑扣掉 RpmArcGauge 的 8dp 邊距
                val r = side / 2 - 8.dp
                SpeedBox(d.speed, d.speedUnit, Modifier.offset(x = side / 2 + r * 0.17f, y = side / 2 + r * 0.17f).width(r * 0.87f).height(r * 0.6f))
                // 原版：盤面正中偏上，高＝盤 10%、寬:高＝1:1.1、垂直 bias 0.42
                val gh = side * 0.10f
                GearBox(d, Modifier.offset(x = (side - gh / 1.1f) / 2, y = (side - gh) * 0.42f).width(gh / 1.1f).height(gh), (gh.value * 0.62f).sp)
                // 原版位置：時速框正下方靠右
                EngineCheck(d, Modifier.offset(x = side / 2 + r * 1.04f - 36.dp, y = side / 2 + r * 0.77f + 6.dp).size(36.dp))
            }
        }
        val (n, canAdd) = visibleSlots("race")
        for (i in 1..n) {
            BarSlot("race$i", slots["race$i"]!!, d)
            Spacer(Modifier.height(8.dp))
        }
        if (canAdd) AddSlot("race", Modifier.fillMaxWidth().height(30.dp))
        Spacer(Modifier.height(4.dp))
        BottomCluster(d)
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 進階橫向（XTune）：左下竄到右上的轉速斜帶，
 * 帶下方一條長按可換的斜條（Gauge_Arrc_Slop），右下 MAP 小直排＋兩顆多功能鈕。
 */
@Composable
private fun RaceLandscape(d: DashData, peak: Double) {
    val slots by LocalRepo.current.slots.collectAsState()
    val maxK = rpmMaxK(); val scale = remember(maxK) { RpmScale.raceH(maxK) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        val mw = maxWidth
        val mh = maxHeight
        RpmSweepGauge(d.rpm, peak, scale, Modifier.fillMaxSize(), zoom = rememberLabelZoom(scale, d.rpm, 1.6f))

        // 左上：車速（斜帶左段很低，上方空著）
        Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(d.speed?.let { "${it.toInt()}" } ?: "—", style = digits((mh.value * 0.30f).sp, FontWeight.ExtraBold, italic = true))
                Spacer(Modifier.width(6.dp))
                Text(d.speedUnit, color = RxColors.muted, style = digits(16.sp, FontWeight.SemiBold), modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.width(14.dp))
                GearNum(d, Modifier.padding(bottom = 6.dp), (mh.value * 0.14f).sp)
            }
            RpmReadout(d.rpm, scale.red)
        }
        // 上中：水溫／電瓶（斜帶中段的刻度字還在下方）
        Column(Modifier.offset(x = mw * 0.37f, y = 10.dp).width(mw * 0.22f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStat(StatGlyph.Coolant, Ch.T_ENG, d)
            IconStat(StatGlyph.Battery, Ch.VBATT, d)
        }
        // 帶下方：斜條（Sweep 幾何在 x=0.26w 處帶底約 0.67h，從 0.71h 往下都是空的）
        SlopSlot("raceH", slots["raceH"]!!, d, Modifier.offset(x = mw * 0.26f, y = mh * 0.71f).width(mw * 0.40f).height(mh * 0.25f))
        // 右下：MAP 小直排＋兩顆多功能鈕
        Row(
            Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapMini(d)
            FunButton(0, d, Modifier.size(96.dp, 72.dp), 33.sp)
            FunButton(1, d, Modifier.size(96.dp, 72.dp), 33.sp)
        }
        // 頂部水溫電瓶右側（斜帶在這段還很低）
        EngineCheck(d, Modifier.offset(x = mw * 0.62f, y = 12.dp).size(40.dp))
    }
}

/** 進階直向的時速框（XTune 右下象限的深色框）。 */
@Composable
private fun SpeedBox(speed: Double?, unit: String, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier.clip(shape)
            .background(Brush.linearGradient(listOf(RxColors.brand.copy(alpha = 0.08f), RxColors.brand.copy(alpha = 0.24f))))
            .border(1.dp, RxColors.brand.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        val h = maxHeight
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
            Text(speed?.let { "${it.toInt()}" } ?: "—", style = digits((h.value * 0.55f).sp, FontWeight.ExtraBold, italic = true), maxLines = 1)
            Text(unit, color = RxColors.muted, style = digits(13.sp, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun SpeedReadout(speed: Double?, size: TextUnit, unit: String = "km/h") {
    Text(speed?.let { "${it.toInt()}" } ?: "—", style = digits(size, FontWeight.ExtraBold, italic = true), textAlign = TextAlign.Center)
    Text(unit, color = RxColors.muted, style = digits(15.sp, FontWeight.SemiBold))
}

@Composable
private fun RpmReadout(rpm: Double, redAt: Double, size: TextUnit = 33.sp, modifier: Modifier = Modifier) {
    val red = rpm >= redAt
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(rx.protocol.Fmt.grouped(rpm.toLong()), style = digits(size, FontWeight.Bold), color = if (red) RxColors.danger else RxColors.brandInk)
        Spacer(Modifier.width(4.dp))
        Text("rpm", color = RxColors.muted, style = digits(15.sp, FontWeight.SemiBold), modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** 直向底列（XTune 進階／巡航同款）：左水溫電瓶、中兩顆多功能鈕、右 MAP 小直排。全部唯讀（閘門擋寫入）。 */
@Composable
private fun BottomCluster(d: DashData) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconStat(StatGlyph.Coolant, Ch.T_ENG, d)
            IconStat(StatGlyph.Battery, Ch.VBATT, d)
        }
        FunButton(0, d, Modifier.size(100.dp, 84.dp), 34.sp)
        Spacer(Modifier.width(10.dp))
        FunButton(1, d, Modifier.size(100.dp, 84.dp), 34.sp)
        Spacer(Modifier.width(12.dp))
        MapMini(d)
    }
}

/** 水溫／電瓶（XTune Gauge_IconText）：圖示＋數值；水溫在 Eng_Over_Heat 或超溫門檻時圖示與數字變色。 */
@Composable
private fun IconStat(kind: StatGlyph, absid: Int, d: DashData) {
    val def = d.l?.layout?.registry?.values?.get(absid)
    val v = d.v(absid)
    val level = if (kind == StatGlyph.Coolant && d.overheat) 2 else levelOf(absid, v)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatIcon(kind, if (level > 0) levelColor(level) else RxColors.muted, Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtVal(v, def?.decimals ?: 1), style = digits(30.sp), color = levelColor(level), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(3.dp))
            Text(unitOf(def), color = RxColors.muted, fontSize = 14.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

fun unitOf(def: ChannelDef?): String = def?.unit?.takeIf { it != "NA" }?.replace("℃", "°C") ?: ""

/** 值在通道 min→max 的位置（XTune Gauge_Bar／Gauge_Arrc_Slop 同算法）；定義檔沒給範圍就不填。 */
fun fracOf(def: ChannelDef?, v: Double?): Float {
    if (def == null || v == null) return 0f
    val lo = def.min
    val hi = def.max
    if (hi >= Double.MAX_VALUE || lo <= -Double.MAX_VALUE || hi <= lo) return 0f
    return ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun defOf(absid: Int, d: DashData): ChannelDef? {
    val registry by LocalRepo.current.registry.collectAsState()
    return DerivedChannels.def(absid) ?: d.l?.layout?.registry?.values?.get(absid) ?: registry?.values?.get(absid)
}

/** MGU_Energy_Percent：原版這格改顯示 eBoost 狀態字、填充條換色（barmask_charge／ready／running）。 */
private const val MGU_ENERGY = 1398

private fun slotLabel(absid: Int, def: ChannelDef?, d: DashData): String =
    (if (absid == MGU_ENERGY) d.eBoostStatus?.label else null) ?: def?.label ?: "#$absid"

private fun slotInk(absid: Int, d: DashData): Color =
    if (absid == MGU_ENERGY && (d.eBoostStatus == EbStatus.Ready || d.eBoostStatus == EbStatus.Running)) RxColors.accent else RxColors.brandInk

/** Ready／Running 綠色；其他通道 null＝用預設色。 */
private fun slotTint(absid: Int, d: DashData): Color? =
    if (absid == MGU_ENERGY && (d.eBoostStatus == EbStatus.Ready || d.eBoostStatus == EbStatus.Running)) RxColors.accent else null

/**
 * 儀表格的長按換通道（XTune 進階／巡航的格子都是長按跳「Select Var」）。
 * 回傳要掛上的 Modifier，對話框在這裡畫；選完存 [key]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun slotLongPress(key: String, absid: Int): Modifier {
    val repo = LocalRepo.current
    var pick by remember { mutableStateOf(false) }
    val counts by repo.slotCount.collectAsState()
    val group = key.trimEnd { it.isDigit() }
    val index = key.drop(group.length).toIntOrNull()
    val removable = index != null && (counts[group] ?: 0) > 1
    if (pick) ChannelPicker(
        title = stringResource(R.string.pick_channel_replace), current = absid,
        onPick = { repo.setSlot(key, it); pick = false }, onDismiss = { pick = false },
        onRemove = if (removable) ({ repo.removeSlot(group, index!!); pick = false }) else null,
    )
    return Modifier.combinedClickable(onClick = {}, onLongClick = { pick = true })
}

/** 「＋」格：還沒放滿時接在最後，點一下多一格（長按任一格可移除）。 */
@Composable
private fun AddSlot(group: String, modifier: Modifier) {
    val repo = LocalRepo.current
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.clip(shape).border(1.dp, RxColors.line, shape).clickable { repo.addSlot(group) }, contentAlignment = Alignment.Center) {
        Text("＋", color = RxColors.muted, fontSize = 18.sp)
    }
}

/** 進階直向的條格（XTune Gauge_Bar）：名稱、數值、min→max 填充條。 */
@Composable
private fun BarSlot(key: String, absid: Int, d: DashData, modifier: Modifier = Modifier) {
    val def = defOf(absid, d)
    val v = d.v(absid)
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(RxColors.cardHi, RxColors.card)))
            .border(1.dp, RxColors.line, shape)
            .then(slotLongPress(key, absid))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                slotLabel(absid, def, d), color = slotInk(absid, d), fontSize = 16.sp, fontStyle = FontStyle.Italic,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(bottom = 3.dp),
            )
            Text(fmtVal(v, def?.decimals ?: 1), style = digits(30.sp), color = if (DerivedChannels.isPower(absid)) powerColor(v) else levelColor(levelOf(absid, v)))
            Spacer(Modifier.width(4.dp))
            Text(unitOf(def), color = RxColors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(5.dp))
        if (DerivedChannels.isPower(absid)) PowerBar(v, Modifier.fillMaxWidth().height(5.dp))
        else FillBar(fracOf(def, v), Modifier.fillMaxWidth().height(5.dp), slotTint(absid, d))
    }
}

/** 進階橫向的斜條格（XTune Gauge_Arrc_Slop）：斜條＋右端數值與名稱。 */
@Composable
private fun SlopSlot(key: String, absid: Int, d: DashData, modifier: Modifier) {
    val def = defOf(absid, d)
    val v = d.v(absid)
    Row(modifier.then(slotLongPress(key, absid)), verticalAlignment = Alignment.Bottom) {
        if (DerivedChannels.isPower(absid)) PowerBar(v, Modifier.weight(1f).height(10.dp))
        else SlopBar(fracOf(def, v), Modifier.weight(1f).fillMaxHeight(), slotTint(absid, d))
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtVal(v, def?.decimals ?: 1), style = digits(30.sp), color = if (DerivedChannels.isPower(absid)) powerColor(v) else levelColor(levelOf(absid, v)))
                Spacer(Modifier.width(3.dp))
                Text(unitOf(def), color = RxColors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(slotLabel(absid, def, d), color = slotInk(absid, d), fontSize = 15.sp, fontStyle = FontStyle.Italic, maxLines = 1)
        }
    }
}

/** 巡航的數值格（XTune Gauge_Text）。 */
@Composable
private fun SlotCell(key: String, absid: Int, d: DashData, modifier: Modifier = Modifier) {
    val def = defOf(absid, d)
    val v = d.v(absid)
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(RxColors.cardHi, RxColors.card)))
            .border(1.dp, RxColors.line, shape)
            .then(slotLongPress(key, absid))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(slotLabel(absid, def, d), color = slotInk(absid, d), fontSize = 14.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtVal(v, def?.decimals ?: 1), style = digits(48.sp), color = if (DerivedChannels.isPower(absid)) powerColor(v) else levelColor(levelOf(absid, v)))
            Spacer(Modifier.width(4.dp))
            Text(unitOf(def), color = RxColors.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (DerivedChannels.isPower(absid)) PowerBar(v, Modifier.fillMaxWidth().height(5.dp))
        else {
        val tint = slotTint(absid, d)
        val line = tint ?: RxColors.brand.copy(alpha = 0.8f)
        Box(Modifier.fillMaxWidth().height(if (tint != null) 4.dp else 2.dp).padding(horizontal = 24.dp)
            .background(Brush.horizontalGradient(listOf(line.copy(alpha = 0f), line, line.copy(alpha = 0f)))))
        }
    }
}

// ---------------------------------------------------------------- 巡航

@Composable
private fun CruiseDash() {
    val d = rememberDash()
    val slots by LocalRepo.current.slots.collectAsState()
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                IconStat(StatGlyph.Coolant, Ch.T_ENG, d)
                IconStat(StatGlyph.Battery, Ch.VBATT, d)
                SlotCell("cruise1", slots["cruise1"]!!, d, Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxHeight().widthIn(max = 420.dp).weight(1.6f), contentAlignment = Alignment.Center) {
                CruiseRing(d)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FunButton(0, d, Modifier.size(88.dp, 66.dp), 30.sp)
                    FunButton(1, d, Modifier.size(88.dp, 66.dp), 30.sp)
                    MapMini(d)
                }
                SlotCell("cruise2", slots["cruise2"]!!, d, Modifier.fillMaxWidth())
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) { CruiseRing(d) }
            val (n, canAdd) = visibleSlots("cruise")
            val cells = (1..n).map { "cruise$it" } + if (canAdd) listOf("+") else emptyList()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cells.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                        row.forEach { k ->
                            if (k == "+") AddSlot("cruise", Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp))
                            else SlotCell(k, slots[k]!!, d, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            BottomCluster(d)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 巡航轉速環：240° 弧外框高約 0.75 寬，底部開口放數字轉速（RX 加的）。 */
@Composable
private fun CruiseRing(d: DashData) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val side = minOf(maxWidth, maxHeight / 0.84f)
        val maxK = rpmMaxK(); val scale = remember(maxK) { RpmScale.linear(maxK) }
        Box(Modifier.width(side).height(side * 0.84f)) {
            CombRingGauge(d.rpm, scale, Modifier.fillMaxWidth().height(side * 0.78f))
            Column(Modifier.align(Alignment.TopCenter).offset(y = side * 0.24f), horizontalAlignment = Alignment.CenterHorizontally) {
                SpeedReadout(d.speed, (side.value * 0.22f).sp, d.speedUnit)
            }
            // 檔位：放在環頂端的梯形小框裡（時速上方）
            // 梯形位置同 CombRingGauge：環寬受限時 r＝(side−12dp)/2，梯形 y＝10dp＋0.28r～0.38r；字收進框內不凸出
            val ringR = (side - 12.dp) / 2
            Box(Modifier.align(Alignment.TopCenter).offset(y = 10.dp + ringR * 0.28f).width(ringR * 0.22f).height(ringR * 0.10f), contentAlignment = Alignment.Center) {
                GearNum(d, Modifier, (ringR.value * 0.085f).sp)
            }
            RpmReadout(d.rpm, scale.red, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.02f))
            // 原版位置：km/h 下方偏右
            EngineCheck(d, Modifier.align(Alignment.TopCenter).offset(x = side * 0.14f, y = side * 0.55f).size(side * 0.09f))
        }
    }
}

/** 檔位（XTune：GearNum #130，0 顯示 N）；設定可關。 */
@Composable
private fun GearNum(d: DashData, modifier: Modifier, size: TextUnit) {
    val show by LocalRepo.current.showGear.collectAsState()
    if (!show) return
    val g = d.v(Ch.GEAR)?.toInt() ?: return
    Text(if (g == 0) "N" else "$g", style = digits(size, FontWeight.ExtraBold, italic = true), color = RxColors.partner, modifier = modifier)
}

/** 進階直向的檔位框（原版：圓角 10dp、2dp 描邊）；框跟著檔位開關一起顯示。 */
@Composable
private fun GearBox(d: DashData, modifier: Modifier, size: TextUnit) {
    val show by LocalRepo.current.showGear.collectAsState()
    if (!show) return
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.border(2.dp, RxColors.partner, shape), contentAlignment = Alignment.Center) {
        GearNum(d, Modifier, size)
    }
}

/** 引擎燈：有故障才亮（同原版，平常不佔位置）；故障明細在「更多」頁。 */
@Composable
private fun EngineCheck(d: DashData, modifier: Modifier) {
    if (d.engineCheck) EngineLamp(RxColors.warn, modifier)
}

/** XTune 進階／巡航的 MAP 切換（MAP_Switcher），縮成側邊小直排。點一下送 SDO 0x2003（免登入）。 */
@Composable
private fun MapMini(d: DashData) {
    val map = d.v(Ch.MAP_NUM)?.toInt()
    val repo = LocalRepo.current
    val busy by repo.controlBusy.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("MAP", color = RxColors.muted, style = digits(12.sp, FontWeight.SemiBold))
        (1..3).forEach { m ->
            val sel = map == m
            Box(
                Modifier.size(38.dp, 26.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (sel) RxColors.brand else RxColors.track.copy(alpha = 0.6f))
                    .clickable(enabled = !busy && !sel) { repo.setMap(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text("$m", style = digits(16.sp, FontWeight.Bold), color = if (sel) RxColors.palette.onBrand else RxColors.muted.copy(alpha = 0.7f))
            }
        }
    }
}
