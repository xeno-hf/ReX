package xb.tune

class CellDiff(
    val row: Int,
    val col: Int,
    val left: Long,
    val right: Long,
    /** 畫面上顯示的值是否也不同。原始值不同但顯示相同的格子，STX 的比較看不出來。 */
    val shownDiffers: Boolean,
)

enum class DiffKind { Different, OnlyLeft, OnlyRight, SizeDiffers }

class TableDiff(val absId: Int, val name: String, val kind: DiffKind, val cells: List<CellDiff>)

/**
 * 兩份調校檔逐表比較。以原始值為準，另外標出顯示值有沒有差。
 * STX 比的是顯示出來的文字；這裡更嚴格，原始值有差就列出來。
 * EEPROM 群組不比。要不要只看某個等級以內的表，由呼叫端用 [include] 決定。
 */
object TuneCompare {
    fun compare(left: Tune, right: Tune, include: (TableDef) -> Boolean = { true }): List<TableDiff> {
        val out = ArrayList<TableDiff>()
        val ids = (left.file.tables.keys + right.file.tables.keys).sorted()
        for (id in ids) {
            val ld = left.definition(id)
            val rd = right.definition(id)
            val def = ld ?: rd
            if (def != null && (def.isEeprom || !include(def))) continue
            val name = def?.name ?: left.file.tables[id]?.name ?: right.file.tables[id]?.name ?: ""
            val l = left.rows(id)
            val r = right.rows(id)
            if (l == null) { out.add(TableDiff(id, name, DiffKind.OnlyRight, emptyList())); continue }
            if (r == null) { out.add(TableDiff(id, name, DiffKind.OnlyLeft, emptyList())); continue }
            if (l.size != r.size || l.indices.any { l[it].size != r[it].size }) {
                out.add(TableDiff(id, name, DiffKind.SizeDiffers, emptyList())); continue
            }
            val cells = ArrayList<CellDiff>()
            for (y in l.indices) for (x in l[y].indices) {
                val a = l[y][x]
                val b = r[y][x]
                if (a == b) continue
                val shown = if (ld == null || rd == null || ld.gain == 0f || rd.gain == 0f) true
                else Convert.rawToPhysical(ld, a) != Convert.rawToPhysical(rd, b)
                cells.add(CellDiff(y, x, a, b, shown))
            }
            if (cells.isNotEmpty()) out.add(TableDiff(id, name, DiffKind.Different, cells))
        }
        return out
    }
}
