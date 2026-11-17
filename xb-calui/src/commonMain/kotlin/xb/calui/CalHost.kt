package xb.calui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import xb.link.LiveTuning
import xb.tune.Cell
import xb.tune.Convert
import xb.tune.EditResult
import xb.tune.TableDef
import xb.tune.Tune

/**
 * 功能專頁（XTune 調校選單的各頁）共用畫面：XB 桌面版與 ReX 直接編同一份原始碼（不是另一個模組），
 * 各自提供 [CalHost]（調校檔、即時寫入、監控值、表格編輯器）與 [CalColors]。
 * 寫入一律走 [CalHost.edited] → 宿主的 [LiveTuning]（停手 1.2 秒才送、寫完讀回、ECU 拒絕就鎖）；
 * 非寫表的命令（例如自動暖車的開始／停止）走 [CalHost.command]，宿主的寫入閘門另外放行。
 */
interface CalHost {
    val tune: Tune
    /** ECU 的 CANopen node（1 Mini X、2 Super X、33 Mini XX、34 Super XX、37 SXS）。 */
    val node: Int
    /** 控制檔 C 版本（C 號）；不知道時 null。 */
    val cCode: Int?
    /**
     * ECU 握手回報的韌體機型碼：多缸機種用它判斷缸數（超級自動調校、SxS 爆震）。
     * 不知道回 null（當單缸，或全部列出）。
     */
    val fwCode: Int?
    /** XTune 語系代號：en-US、zh-TW、zh-CN、ja-JP。 */
    val lang: String
    /** 有連著 ECU、能即時寫入（XB 沒連 ECU 時是編輯檔案）。 */
    val online: Boolean
    /** 調校檔內容或寫入狀態變了就加一。要是 Compose 的 State（頁面讀它來重畫）。 */
    val revision: Int

    /** 調校包裡有這張表、而且讀得到（沒連 ECU 時＝調校檔裡有）。 */
    fun has(absId: Int): Boolean
    fun writable(absId: Int): Boolean
    fun status(absId: Int): LiveTuning.TableState?
    /** 這張表鎖著等重讀（[LiveTuning.held]，清除學習值之後）：不能改，要 [readTables] 成功才解開。 */
    fun held(absId: Int): Boolean
    /** [tune] 裡這張表被改了（改值、讀回、還原都算）：宿主排程寫入、重畫。 */
    fun edited(absId: Int)
    /** 從 ECU 重讀這幾張表（XTune 的全讀）；讀到的值放進 [tune]。完成時回 null 或錯誤原因。 */
    suspend fun readTables(ids: List<Int>): String?
    /** 不等停手，整張送出（XTune 的全寫）。 */
    suspend fun writeTables(ids: List<Int>): String?
    /**
     * 寫一個不是調校表的物件（XTune 頁上的命令鈕，例如自動暖車 0x200E）。
     * 宿主要確認這個 index／值在允許清單裡才送；不允許或沒連線回原因。
     */
    suspend fun command(index: Int, sub: Int, value: Long, bytes: Int): String?
    /**
     * 送一個會讓 ECU 自己改掉 [ids] 的命令（清除學習值），等 [settleMs] 再重讀 [ids] 換進 [tune]。
     * 宿主走 [LiveTuning.commandThenRead]：整段跟即時寫入互斥，頁面離開也會做完；重讀失敗的表鎖住，[readTables] 成功才解開。
     * 允許清單、連線檢查同 [command]。
     */
    suspend fun commandThenRead(index: Int, sub: Int, value: Long, bytes: Int, ids: List<Int>, settleMs: Long): LiveTuning.CommandRead
    /** 讀一個不是調校表的物件（保養資訊 0x200F、狀態字 0x2000…）。回傳資料（little-endian）或失敗。 */
    suspend fun readObject(index: Int, sub: Int): Result<ByteArray>
    /**
     * 送一個非 SDO 的廠商 CAN 訊框（ECU 燈效 0x689、每缸 AFR 讀取 0x680 `62`）。[reply]：要等的回應 CAN id、
     * [count] 筆（符合 [match]），收齊回傳各筆資料；不等回應就給 null。宿主只放行 [CalCommands.rawAllowed] 的訊框。
     */
    suspend fun raw(canId: Int, data: ByteArray, reply: Int?, count: Int = 1, timeoutMs: Long = 1500, match: (ByteArray) -> Boolean = { true }): Result<List<ByteArray>>

    /** 這頁要看哪些即時變數（頁面開著時宣告；宿主把它們加進監控串流）。空清單＝不需要。 */
    fun watch(vars: List<Int>)
    /** 即時變數（absid）目前的值；沒在串流回 null。 */
    fun live(varId: Int): Double?
    /** 即時變數的名字與單位。 */
    fun varLabel(varId: Int): String
    /** 表的顯示名（定義檔的中文短名或表名）。 */
    fun tableTitle(absId: Int): String

    /** 宿主的表格編輯器（D2／D3 表用；功能頁裡嵌一張或多張表時）。 */
    @Composable
    fun TableEditor(ids: List<Int>, modifier: Modifier)
}

/** 宿主的配色。 */
data class CalColors(
    val bg: Color,
    val card: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val brand: Color,
    val onBrand: Color,
    val accent: Color,
    val warn: Color,
    val danger: Color,
    val ok: Color,
    val digits: FontFamily = FontFamily.Default,
)

val LocalCalColors = compositionLocalOf<CalColors> { error("CalColors not provided") }

/**
 * 讀表內容並訂閱 [CalHost.revision]：調校檔不是可觀察的，畫面裡讀值一律走這裡，改值、讀回、ECU 拒絕換回時才會重畫
 * （Compose 會跳過參數沒變的子畫面，只在頁面頂層讀 revision 不夠）。
 */
fun CalHost.rowsOf(id: Int): List<LongArray>? { revision; return tune.rows(id) }

/** 一張單值表（D1）的讀寫小工具。值都用「顯示值」（換算後）。 */
class D1(val host: CalHost, val id: Int, val row: Int = 0, val col: Int = 0) {
    val def: TableDef? get() = host.tune.definition(id)
    val present: Boolean get() = host.has(id) && def != null
    val raw: Long get() = host.rowsOf(id)?.getOrNull(row)?.getOrNull(col) ?: 0L
    val value: Double get() = def?.let { Convert.rawToPhysical(it, raw) } ?: raw.toDouble()
    val min: Double get() = def?.min ?: 0.0
    val max: Double get() = def?.max ?: 0.0
    val writable: Boolean get() = present && host.writable(id)

    fun setRaw(v: Long): EditResult {
        val r = host.tune.setRaw(id, Cell(row, col), v)
        if (r is EditResult.Done && r.changed > 0) host.edited(id)
        return r
    }

    /** 顯示值換成要寫的 raw（夾在定義檔範圍內）。 */
    fun rawFor(v: Double): Long {
        val d = def ?: return v.toLong()
        return Convert.physicalToRaw(d, v.coerceIn(minOf(d.min, d.max), maxOf(d.min, d.max)))
    }

    /** 設顯示值（夾在定義檔範圍內，照 XTune）。 */
    fun set(v: Double): EditResult {
        val d = def ?: return EditResult.Refused("missing table $id")
        val c = v.coerceIn(minOf(d.min, d.max), maxOf(d.min, d.max))
        return setRaw(Convert.physicalToRaw(d, c))
    }

    fun bit(b: Int): Boolean = (raw shr b) and 1L == 1L
    /** XTune 開關列：XOR 那一位。 */
    fun toggleBit(b: Int): EditResult = setRaw(raw xor (1L shl b))
    fun setBit(b: Int, on: Boolean): EditResult = setRaw(if (on) raw or (1L shl b) else raw and (1L shl b).inv())
}
