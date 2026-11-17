package xb.tune

/** 調校檔裡的一張表：只有原始整數。 */
class RawTable(
    val name: String,
    /** 表號；MGU 附掛表已加上位移。 */
    val absId: Int,
    val rows: List<LongArray>,
    /** 原文裡的位置，存檔時用。 */
    val element: XmlElement,
    /** 是否為 MGU 附掛表。 */
    val attached: Boolean,
)

/** 調校檔附帶的 MGU 設定。C1 與 C2 以後放的位置不同。 */
class SymguAttachment(
    val cVersion: Int,
    val visible: String,
    val batteryVisible: String,
    val element: XmlElement,
)

/**
 * 已解開的調校檔（AMX／ASX／AMX2…）。讀法照 STX。
 * 這一層不解密，也不看定義檔；表的尺寸與意義由 [Tune] 對照定義檔後才知道。
 */
class TuneFile private constructor(
    val source: String,
    val root: XmlElement,
    val mapType: String,
    val vCode: Int,
    val cCode: Int,
    val aCode: Int,
    val tableId: String,
    val lastSaveTime: String,
    val carNote: String,
    val ioConfig: String,
    val tables: Map<Int, RawTable>,
    /** 檔案裡重複出現的表號；只保留第一筆。 */
    val duplicateIds: List<Int>,
    val symgu: SymguAttachment?,
    val camshaft: Map<String, String>?,
) {
    private val tableIdParts: List<Int> get() = tableId.split('_').map { it.trim().toIntOrNull() ?: 0 }

    /** 機型代號：Table_ID 第 3 段的低 7 位。 */
    val modelId: Int get() = (tableIdParts.getOrNull(2) ?: 0) and 0x7F

    /** Table_ID 第 3 段的最高位：300 kPa 版本。 */
    val is300kPa: Boolean get() = ((tableIdParts.getOrNull(2) ?: 0) and 0x80) != 0

    companion object {
        /** MGU 附掛表（C2 以後）的表號位移。 */
        const val SYMGU_ID_OFFSET = 2560

        fun parse(xml: String): TuneFile {
            val root = Xml.parse(xml)
            if (root.name != "AMC_File") throw IllegalArgumentException("Not a tune file: root element is ${root.name}")
            fun text(name: String): String = root.child(name)?.innerText
                ?: throw IllegalArgumentException("Tune file is missing $name")

            val tables = LinkedHashMap<Int, RawTable>()
            val duplicates = ArrayList<Int>()
            val budget = CellBudget(MAX_CELLS)
            fun load(nodes: List<XmlElement>, offset: Int, attached: Boolean) {
                for (t in nodes) {
                    val id = (t.attr("absid") ?: continue).trim().toInt() + offset
                    if (id in tables) { duplicates.add(id); continue }
                    tables[id] = RawTable(t.attr("name") ?: "", id, parseRows(t.innerText, budget), t, attached)
                }
            }

            val tableValue = root.child("TableValue") ?: throw IllegalArgumentException("Tune file is missing TableValue")
            load(tableValue.children("Table"), 0, attached = false)

            // C1 放在 Attachment，C2 以後放在 AccessoryInfo；兩者都有時以後者的版本為準
            var symgu: SymguAttachment? = null
            val c1 = root.find("Attachment", "SYMGU")
            val later = root.find("AccessoryInfo", "SYMGU")
            if (later != null) {
                symgu = SymguAttachment(
                    (later.attr("c_ver") ?: throw IllegalArgumentException("AccessoryInfo/SYMGU has no c_ver")).trim().toInt(),
                    later.attr("visible") ?: "0",
                    later.attr("Config_eRacingBattery_visible") ?: "0",
                    later,
                )
            } else if (c1 != null) {
                symgu = SymguAttachment(1, c1.attr("visible") ?: "0", c1.attr("Config_eRacingBattery_visible") ?: "0", c1)
            }
            if (symgu != null) {
                if (symgu.cVersion == 1) {
                    val nodes = (c1 ?: later!!).find("AMC_File", "TableValue")?.children("Table") ?: emptyList()
                    load(nodes, 0, attached = true)
                } else if (symgu.cVersion >= 2) {
                    val nodes = later?.child("TableValue")?.children("Table") ?: emptyList()
                    load(nodes, SYMGU_ID_OFFSET, attached = true)
                }
            }

            val cam = tableValue.child("CAMSHAFT")?.let { c -> c.attributes.associate { it.name to it.value } }

            return TuneFile(
                source = xml,
                root = root,
                mapType = text("MAP_Type"),
                vCode = text("V_Code").trim().toInt(),
                cCode = text("C_Code").trim().toInt(),
                aCode = text("A_Code").trim().toInt(),
                tableId = text("Table_ID"),
                lastSaveTime = text("LastSaveTime"),
                carNote = text("CarNote"),
                ioConfig = text("IO_Config"),
                tables = tables,
                duplicateIds = duplicates,
                symgu = symgu,
                camshaft = cam,
            )
        }

        /** 一份調校檔所有表加起來最多幾格（實際檔案遠小於這個數）。 */
        const val MAX_CELLS = 4_000_000

        /** 解析時累計的格數；超過 [max] 就丟例外。 */
        class CellBudget(private val max: Int) {
            private var used = 0
            fun take() { if (++used > max) throw IllegalArgumentException("Tune file has more than $max cells") }
        }

        /**
         * 表的內文：每列一行、逗號分隔的十進位無號整數；空白與空項目略過。
         * 邊讀邊算格數（[budget]），不先把整段切成字串清單。
         */
        fun parseRows(text: String, budget: CellBudget = CellBudget(MAX_CELLS)): List<LongArray> {
            val rows = ArrayList<LongArray>()
            var row = LongArray(16); var n = 0
            var i = 0
            fun endRow() { if (n > 0) rows.add(row.copyOf(n)); n = 0 }
            while (i <= text.length) {
                val ch = if (i < text.length) text[i] else '\n'
                when (ch) {
                    '\n' -> { endRow(); i++ }
                    ',' -> i++
                    else -> {
                        val start = i
                        while (i < text.length && text[i] != ',' && text[i] != '\n') i++
                        val s = text.substring(start, i).trim()
                        if (s.isEmpty()) continue
                        val v = s.toLong()
                        if (v < 0 || v > 4294967295L) throw NumberFormatException("Raw value outside the unsigned 32-bit range: $s")
                        budget.take()
                        if (n == row.size) row = row.copyOf(n * 2)
                        row[n++] = v
                    }
                }
            }
            return rows
        }
    }
}
