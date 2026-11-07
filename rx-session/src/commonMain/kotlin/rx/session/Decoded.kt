package rx.session

import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.protocol.ChannelTable

/**
 * 頻道表 × 名錄 → 展開後的欄位（值通道＋位元容器的位元子通道）。
 * loga 匯出、UI 列表、故障碼頁共用同一份欄位順序。
 */
class DecodedLayout(val registry: ChannelRegistry, val table: ChannelTable) {

    /** 一個顯示/匯出欄位：值通道或位元。[entryIndex] = 在頻道表中的位置。 */
    data class Column(val def: ChannelDef, val entryIndex: Int)

    val entryDefs: List<ChannelDef> = table.entries.map { e ->
        registry.values[e.absid] ?: error("no channel definition for absid ${e.absid}")
    }

    /** 跟 XTune loga 相同的展開方式：位元容器只出位元，其他出本身。 */
    val columns: List<Column> = buildList {
        entryDefs.forEachIndexed { i, d ->
            if (d.isBitContainer) {
                registry.bitsOf[d.absid].orEmpty().sortedByDescending { it.bitIndex }.forEach { add(Column(it, i)) }
            } else add(Column(d, i))
        }
    }

    fun value(col: Column, raw: LongArray): Double = col.def.physical(raw[col.entryIndex])

    fun indexOf(absid: Int): Int = table.entries.indexOfFirst { it.absid == absid }

    /** 方便 UI：依 absid 取物理值；表裡沒有回 null。 */
    fun physical(absid: Int, raw: LongArray): Double? {
        val i = indexOf(absid)
        return if (i < 0) null else entryDefs[i].physical(raw[i])
    }

    /** 目前亮著的故障碼（Malf*／MGU_Malf* 容器的位元）。 */
    fun activeMalfunctions(raw: LongArray): List<ChannelDef> =
        columns.filter { it.def.isBit && it.def.name.contains("Malf") && value(it, raw) > 0.5 }.map { it.def }
}

/** 頻道表預設組。名錄沒有的 absid 自動略過。 */
object Presets {
    data class Preset(val id: String, val title: String, val description: String, val absids: List<Int>)

    private val CORE = listOf(26, 34, 51, 29, 33, 144, 45)

    /** XTune 連線時寫的全餐表（2026-09-20 從 loga 檔頭還原，73 母通道＝151 欄）。 */
    val XTUNE_151 = Preset(
        "xtune151", "XTune standard", "The 73 channels XTune writes",
        listOf(1598, 1583, 1578, 1573, 1368, 1367, 162, 161, 160, 159, 158, 157, 16, 15, 14, 13, 12, 10, 9, 8, 7, 4, 2,
            20, 22, 24, 25, 26, 27, 28, 29, 30, 33, 34, 35, 44, 45, 46, 51, 52, 53, 55, 61, 66, 70, 72, 77, 78, 82, 88,
            124, 130, 144, 163, 164, 165, 171, 172, 1183, 1265, 1398, 1420, 1427, 1428, 1429, 1430, 1431, 1432, 1433,
            1434, 1435, 1436, 1437),
    )

    val BATTERY = Preset(
        "battery", "Battery", "BMS cells, current, SOC, temperature and MGU",
        CORE + listOf(1428, 1429, 1430, 1431, 1432, 1433, 1434, 1435, 1436, 1437, 159, 160, 161, 162, 163, 164, 165, 171, 1398, 157, 158, 1367, 1368),
    )

    val NBCL = Preset(
        "nbcl", "NB-O2 closed loop", "Narrowband O2, Fuel_CL and closed-loop flags",
        CORE + listOf(46, 66, 22, 1, 2, 4, 20, 35, 24, 88, 70),
    )

    val EBOOST = Preset(
        "eboost", "eBoost", "MGU current, state flags and battery",
        CORE + listOf(159, 160, 161, 162, 163, 164, 165, 171, 1398, 1432, 1433, 1434, 35),
    )

    /** App 內建唯一的頻道組：全開（＝XTune 全餐表）。其他組由使用者從這裡面挑。 */
    val ALL_CHANNELS = XTUNE_151.copy(title = "All", description = "All channels")

    val ALL = listOf(XTUNE_151, BATTERY, NBCL, EBOOST)

    fun table(p: Preset, registry: ChannelRegistry): ChannelTable =
        ChannelTable(p.absids.distinct().mapNotNull { a -> registry.values[a]?.let { ChannelTable.Entry(a, it.cellSize) } })
}
