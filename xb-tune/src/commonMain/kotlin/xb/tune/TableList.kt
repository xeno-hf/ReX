package xb.tune

class TableEntry(val def: TableDef, val title: String, val level: Int)
class GroupEntry(val name: String, val title: String, val tables: List<TableEntry>)

/** 表清單：依群組排好、依等級與搜尋字過濾。XB 桌面版左邊的清單與 ReX 調校頁共用。 */
object TableList {
    /**
     * [showAll]＝也列進階表（等級 > 1）；[include] 再篩（例如 XB 的「只看不同」、ReX 的「ECU 上讀得到」）。
     * 附掛的 MGU 表：檔案裡的顯示旗標開著才算一般表，否則歸在進階表（STX 是旗標關著就不顯示）。
     */
    fun groups(tune: Tune, labels: Labels, lang: String, showAll: Boolean, query: String, include: (TableDef) -> Boolean = { true }): List<GroupEntry> {
        val q = query.trim().lowercase()
        val defs = tune.definitions.tables.values + (tune.attachedDefinitions?.tables?.values?.filter { tune.file.tables[it.absId]?.attached == true } ?: emptyList())
        val byGroup = LinkedHashMap<String, MutableList<TableEntry>>()
        for (d in defs) {
            if (d.isEeprom || tune.rows(d.absId) == null) continue
            val attached = tune.file.tables[d.absId]?.attached == true
            val lv = if (attached) (if (tune.file.symgu?.visible == "1") 1 else 4) else (tune.level(d) ?: continue)
            if (!showAll && lv > 1) continue
            if (!include(d)) continue
            val title = labels.shortName(d.absId, lang) ?: d.name
            if (q.isNotEmpty() && !title.lowercase().contains(q) && !d.name.lowercase().contains(q) && d.absId.toString() != q) continue
            byGroup.getOrPut(d.group) { ArrayList() }.add(TableEntry(d, title, lv))
        }
        val order = Platform.GROUP_ORDER
        return byGroup.entries
            .sortedBy { e -> order.indexOf(e.key).let { if (it < 0) order.size else it } }
            .map { GroupEntry(it.key, labels.group(it.key, lang), it.value) }
    }
}
