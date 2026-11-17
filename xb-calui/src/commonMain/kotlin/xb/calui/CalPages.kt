package xb.calui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import xb.tune.CalItems

/**
 * 功能專頁入口：照 XTune 的頁種類（[CalItems.Page]）開對應的頁。每頁的依據寫在該頁的註解與
 *。
 */
@Composable
fun CalPage(host: CalHost, item: CalItems.Item, colors: CalColors, onBack: () -> Unit) {
    CompositionLocalProvider(LocalCalColors provides colors) {
        host.revision // 調校檔一變就重畫
        val title = CalItems.name(item.key, host.lang)
        when (item.page) {
            CalItems.Page.D1, CalItems.Page.D1x4, CalItems.Page.D1x5 -> SingleValuesPage(host, item, title, onBack)
            CalItems.Page.D3, CalItems.Page.SxsD3 -> GridPage(host, item, title, onBack)
            CalItems.Page.D3InjAngle -> InjAnglePage(host, item, title, onBack)
            CalItems.Page.Backfire -> BackfirePage(host, title, onBack)
            CalItems.Page.AutoWarmup -> AutoWarmupPage(host, title, onBack)
            CalItems.Page.NbClosedLoop -> NbClosedLoopPage(host, title, onBack)
            CalItems.Page.QsSetting -> QsSettingPage(host, title, onBack)
            CalItems.Page.QsWizard -> QsWizardPage(host, title, onBack)
            CalItems.Page.RButton -> RButtonPage(host, title, onBack)
            CalItems.Page.ResButton -> MatrixPage(host, title, onBack)
            CalItems.Page.AutoTune -> AutoTunePage(host, title, onBack)
            CalItems.Page.AutoTunePro -> AutoTuneProPage(host, item, title, onBack)
            CalItems.Page.EBoost -> EBoostPage(host, title, onBack)
            CalItems.Page.EBoostX2 -> EBoostX2Page(host, title, onBack)
            CalItems.Page.Exup -> ExupPage(host, title, onBack)
            CalItems.Page.WindShield -> WindShieldPage(host, title, onBack)
            CalItems.Page.RgbLed -> RgbLedPage(host, title, onBack)
            CalItems.Page.Service -> ServicePage(host, title, onBack)
            CalItems.Page.IdleAdj -> IdleAdjPage(host, title, onBack)
            CalItems.Page.TpsLearn -> TpsLearnPage(host, title, onBack)
            CalItems.Page.ClearLearn -> ClearLearnPage(host, title, onBack)
            CalItems.Page.InjectorCal -> InjectorCalPage(host, title, onBack)
            CalItems.Page.ECvtAt -> ECvtAtPage(host, title, onBack)
            CalItems.Page.ECvtMt -> ECvtMtPage(host, title, onBack)
            CalItems.Page.SxsBoost -> SxsBoostPage(host, title, onBack)
        }
    }
}

/** 這一項在這台 ECU 上能不能開（XTune 是表不存在就 NPE；這裡改成不讓開、說明缺表）。 */
fun CalHost.usable(item: CalItems.Item): Boolean = when (item.page) {
    // 命令頁、頁內自己組表的頁：看頁本身要的表
    CalItems.Page.Backfire -> has(if (isX2) 1569 else 42)
    // 燈效不是表，是 0x689 的廠商訊框：X2 平台、連著 ECU 才能用
    CalItems.Page.RgbLed -> online && isX2
    CalItems.Page.IdleAdj, CalItems.Page.TpsLearn -> online
    // 清除學習值：要連著 ECU，而且兩張學習表都在調校包裡（清完要重讀它們）
    CalItems.Page.ClearLearn -> online && CalItems.CLEAR_LEARN_TABLES.all { has(it) }
    // SxS 的控制檔同號是別的表（官方只看機型，在 SxS 上這兩頁是壞的）：看表名
    CalItems.Page.EBoostX2 -> named(1230, "D1MGU_eBoost_Option")
    CalItems.Page.WindShield -> named(1009, "D1WindShield_Option")
    else -> item.tables.any { has(it) }
}

val CalHost.isX2: Boolean get() = node == 33 || node == 34 || node == 37
val CalHost.isMini: Boolean get() = node == 1 || node == 33
val CalHost.isMiniOrSuper: Boolean get() = node == 1 || node == 2 || node == 33 || node == 34

/** XTune 頁頂監控列的預設 4 個即時變數（各頁不同；沒寫的頁用這組）。 */
val MONITOR_DEFAULT = listOf(26, 34, 55, 33)

/** d3 頁：一張或幾張表，用宿主的表格編輯器（XTune 的分頁＝這裡的表切換）。 */
@Composable
private fun GridPage(host: CalHost, item: CalItems.Item, title: String, onBack: () -> Unit) {
    // SxS 爆震：韌體機型碼個位數＝有爆震控制的缸數（1～3），只列那幾缸的表
    val knock = when (host.fwCode?.rem(10)) {
        1 -> listOf(1501, 1497, 1496, 1613)
        2 -> listOf(1501, 1513, 1497, 1512, 1496, 1613, 977)
        3 -> listOf(1501, 1513, 1571, 1497, 1512, 1570, 1496, 1613, 977, 1358)
        else -> null
    }
    val ids = (if (item.key == "CalItem_Knock" && knock != null) knock else item.tables).filter { host.has(it) }
    CalFrame(host, title, emptyList(), emptyList(), onBack, scroll = false) {
        host.TableEditor(ids, Modifier.fillMaxSize())
    }
}

/**
 *；這裡把列舉做成多選一的按鈕，其餘是 −／＋、可拖的長條、點數字直接輸入。
 * 開 1059（寬域閉迴路）時照 連帶關掉衝突的功能（[Interlock]）。
 */
@Composable
private fun SingleValuesPage(host: CalHost, item: CalItems.Item, title: String, onBack: () -> Unit) {
    val msg = remember { PageMsg() }
    val first = item.tables.firstOrNull()
    val monitor = when (first) { 20 -> listOf(26, 34, 78, 33); 1059 -> listOf(26, 34, 55, 22); 33 -> listOf(26, 34, 55, 51); else -> MONITOR_DEFAULT }
    CalFrame(host, title, item.tables, monitor, onBack, msg) {
        for (id in item.tables) {
            Card { ValueControl(host, D1(host, id), msg) }
        }
    }
}

/** 一個單值表的控制項：ON／OFF、列舉、位元選項或數值。 */
@Composable
fun ValueControl(host: CalHost, d: D1, msg: PageMsg, label: String = host.tableTitle(d.id), step: Double? = null, lo: Double? = null, hi: Double? = null) {
    val def = d.def
    if (!d.present || def == null) { MissingRow(host, label, d.id); return }
    val en = d.writable || !host.online
    val opts = Enums.options(host, d.id)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    when {
        opts != null -> {
            ChoiceRow(label, opts.map { it.first to it.second }, Enums.current(host, d.id, d.value), en, tag = "cal.${d.id}") { v ->
                Interlock.set(host, d, d.rawFor(v.toDouble()), msg, scope)
            }
            TableState(host, d.id)
        }
        def.mark == "Option" -> {
            def.optionBits.split('-').forEachIndexed { bit, name ->
                if (name.isBlank() || name == "_") return@forEachIndexed
                SwitchRow(host, name, d.bit(bit), en, tag = "cal.${d.id}.b$bit") { on -> Interlock.set(host, d, if (on) d.raw or (1L shl bit) else d.raw and (1L shl bit).inv(), msg, scope) }
            }
            TableState(host, d.id)
        }
        Enums.isBool(d.id) || def.adjustDisabled -> {
            val yesNo = d.id == 1127
            SwitchRow(host, label, d.raw != 0L, en, tag = "cal.${d.id}", onText = if (yesNo) "YES" else null, offText = if (yesNo) "NO" else null) { on ->
                Interlock.set(host, d, if (on) 1 else 0, msg, scope)
            }
            TableState(host, d.id)
        }
        else -> AdjustRow(host, d, label, step = step, lo = lo, hi = hi, text = Enums.text(host, d.id), msg = msg, tag = "cal.${d.id}")
    }
}

/**
 * 單值表的特殊顯示。
 */
object Enums {
    /** XTune 顯示 ON／OFF 的表。 */
    private val BOOL = setOf(60, 1059, 22, 1067, 1283, 1311, 1573, 1501, 1463, 1009)
    fun isBool(id: Int) = id in BOOL

    /** 列舉表：(顯示值, 字串) 清單；不是列舉回 null。 */
    fun options(host: CalHost, id: Int): List<Pair<Long, String>>? {
        fun s(k: String) = CalText.get(k, host.lang)
        return when (id) {
            138 -> if (host.isMini) listOf(0L to s("OFF"), 1L to s("TC_Weak"), 4L to s("TC_Medium"), 7L to s("TC_Sensitive")) else null
            1408 -> listOf(0L to s("OFF"), 1L to s("TC_Sport"), 2L to s("TC_Normal"), 3L to s("TC_Slippery"))
            1010 -> listOf(0L to s("PitByRPM"), 1L to s("PitByVSS"))
            1128, 1150 -> listOf(0L to s("TCS_CVT"), 1L to s("TCS_MT"))
            1401 -> listOf(0L to s("EXUP_OFF"), 1L to s("EXUP_ON"), 2L to s("EXUP_RPM"), 3L to s("EXUP_MAP"))
            1418 -> listOf(0L to s("eBoostM_Disable"), 1L to s("eBoostM_Normal"), 2L to s("eBoostM_Street"), 3L to s("eBoostM_Track"))
            1065 -> (0L..6L).map { it to if (it == 0L) s("Warmup_Mode0") else CalText.format("Warmup_ModeN", host.lang, it.toDouble()) }
            else -> null
        }
    }

    /** 目前選的是哪一個（138 在 Mini 上照 進位到 0／1／4／7）。 */
    fun current(host: CalHost, id: Int, v: Double): Long = when {
        id == 138 && host.isMini -> listOf(0L, 1L, 4L, 7L).firstOrNull { v <= it } ?: 7L
        else -> kotlin.math.round(v).toLong()
    }

    /** 數值表的特殊文字：1402 是 0 顯示 OFF；138（非 Mini）0 顯示 OFF。 */
    fun text(host: CalHost, id: Int): ((Double) -> String)? = when (id) {
        1402 -> { v -> if (v == 0.0) CalText.get("EXUP_OFF", host.lang) else num(v) }
        138 -> { v -> if (v == 0.0) "OFF" else num(v) }
        else -> null
    }
}

/**
 * 功能互斥：開了其中一個，ECU 上衝突的功能要關掉，否則一起作用。
 * - 開 自動調校（14）：關 1059、60，表 42 清掉怠速音效兩個位元（bit2、bit4）。
 * - 開 超級自動調校（Super XX 1500；SxS 1580）：關 60，表 42 清 bit2／bit4。
 * - 開 寬域閉迴路（1059）：關 60、14。開 窄域閉迴路（60）：關 1059、14。
 * - 開 怠速音效（表 42 的 bit2／bit4，回火頁）：關 14。
 * XTune 是立刻各寫一次；這裡改表、交給宿主停手後送（同一條寫入路），並顯示同一句提示。
 */
object Interlock {
    /** 把 [id] 改成 [raw] 時要先關掉的功能：（要關的表、要不要清 42 的 bit2／bit4、提示字串）。沒有衝突回 null。 */
    private fun conflicts(host: CalHost, id: Int, raw: Long) = conflicts(host.node, id, raw)

    internal fun conflicts(node: Int, id: Int, raw: Long): Triple<List<Int>, Boolean, String>? {
        // 只有 Super XX（1500）與 SxS（1580）有超級自動調校；Super X Pro 的 1500 是別的設定，不能跟著關
        val proAt = xb.link.Learning.proAt(rx.protocol.EcuPlatform.ofNode(node))
        return when {
            id == 14 && raw == 1L -> Triple(listOf(1059, 60), true, "SynOff_AT_Related")
            proAt != null && id == proAt && raw == 1L -> Triple(listOf(60, 1059), true, "SynOff_ProAT_Related")
            id == 1059 && raw == 1L -> Triple(listOfNotNull(60, 14, proAt), false, "SynOff_WBCL_Related")
            id == 60 && raw == 1L -> Triple(listOf(1059, 14), false, "SynOff_NBCL_Related")
            id == 42 && (raw and 0x14L) != 0L -> Triple(listOf(14), false, "SynOff_IdleLoop_Related")
            else -> null
        }
    }

    /**
     * 把 [d] 改成 [raw]。會跟其他功能互斥時：連著 ECU 就先把衝突的功能關掉、
     * 等 ECU 收下並讀回一致，**才**開這一個；關不掉（被拒、讀回不同）就不開，留言說明。沒連 ECU（編輯檔案）照順序一起改。
     */
    fun set(host: CalHost, d: D1, raw: Long, msg: PageMsg?, scope: kotlinx.coroutines.CoroutineScope) {
        val c = conflicts(host, d.id, raw)
        // 要關的表：編輯器上還開著的，加上上次沒確定寫進 ECU 的（編輯器是 0 不代表 ECU 已經關了）
        fun unsure(id: Int) = host.online && host.status(id).let { it != null && it != xb.link.LiveTuning.TableState.Synced }
        fun offs(): List<Int> {
            val changed = ArrayList<Int>()
            if (c == null) return changed
            for (o in c.first) if (o != d.id && host.has(o)) {
                val x = D1(host, o)
                if (x.raw != 0L) { x.setRaw(0); changed += o } else if (unsure(o)) changed += o
            }
            if (c.second && d.id != 42 && host.has(42)) {
                val x = D1(host, 42)
                if (x.raw and 0x14L != 0L) { x.setRaw(x.raw and 0x14L.inv()); changed += 42 } else if (unsure(42)) changed += 42
            }
            return changed
        }
        if (c == null || !host.online) {
            val changed = offs()
            val r = d.setRaw(raw)
            if (msg != null) { if (r is xb.tune.EditResult.Refused) msg.text = CalText.format("cal_edit_refused", host.lang, r.reason) else if (changed.isNotEmpty()) msg.text = CalText.get(c!!.third, host.lang) }
            return
        }
        scope.launch { lock.withLock {
            // 衝突的功能以 ECU 現值為準：儀表鈕等別的路可能已經開了，編輯器上看不出來。讀不到就不開。
            // 調校包裡有、但這次連線讀不到的衝突表：確認不了它關著，也不開
            val applicable = (c.first.filter { it != d.id } + if (c.second && d.id != 42) listOf(42) else emptyList())
                .filter { host.tune.definition(it) != null && host.tune.rows(it) != null }
            applicable.firstOrNull { !host.has(it) }?.let { o ->
                msg?.text = CalText.format("cal_interlock_failed", host.lang, "${host.tune.definition(o)?.name ?: o}: unreadable"); return@launch
            }
            val fresh = applicable
            if (fresh.isNotEmpty()) host.readTables(fresh)?.let { why -> msg?.text = CalText.format("cal_interlock_failed", host.lang, why); return@launch }
            val changed = offs()
            if (changed.isNotEmpty()) {
                host.writeTables(changed)?.let { why -> msg?.text = CalText.format("cal_interlock_failed", host.lang, why); return@launch }
                msg?.text = CalText.get(c.third, host.lang)
            }
            when (val r = d.setRaw(raw)) {
                is xb.tune.EditResult.Refused -> msg?.text = CalText.format("cal_edit_refused", host.lang, r.reason)
                else -> host.writeTables(listOf(d.id))?.let { msg?.text = it }
            }
        } }
    }

    /** 互斥的開關一次只處理一個（關衝突→確認→開），不會兩個請求交錯。 */
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** 別的路要寫這些開關（ReX 的儀表鈕）時也走同一把鎖，不會插進「關衝突→確認→開」中間。 */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }
}
