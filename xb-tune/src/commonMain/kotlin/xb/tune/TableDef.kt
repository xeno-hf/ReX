package xb.tune

enum class TableKind { D1, D2, D3 }

/** 某個 C 版本下，這張表的狀態與各機型的可見等級。 */
class VersionEntry(val status: String, val levels: Map<Int, Int>) {
    /** 完整標籤寫 Enable，縮寫標籤寫 En。 */
    val enabled: Boolean get() = status == "Enable" || status == "En"
}

/**
 * 定義檔裡的一張表。欄位順序與意義照 STX 建構子。
 * Gain／Offset 在 STX 是單精度，換算時才轉雙精度；這裡保持相同型別，結果才會逐位元一致。
 */
class TableDef(
    val name: String,
    /** 表號；附掛定義檔（SYMGU）的表已加上位移。 */
    val absId: Int,
    val sizeX: Int,
    val sizeY: Int,
    val cellBytes: Int,
    val scope: String,
    val mark: String,
    val scaleX: String,
    val scaleY: String,
    val max: Double,
    val min: Double,
    val slew: Double,
    val gain: Float,
    val offset: Float,
    val optionBits: String,
    val unit: String,
    val group: String,
    val decimals: Int,
    val buttonMatrix: String,
    val groupShow: Int,
    val tc300Swap: String?,
    val ultraVisible: String?,
    val r3Swap: String?,
    val versions: Map<Int, VersionEntry>,
    /** 原始的逗號分隔欄位，已去頭尾空白。 */
    val fields: List<String>,
) {
    val kind: TableKind
        get() = if (sizeX == 0 && sizeY == 0) TableKind.D1 else if (sizeY == 0) TableKind.D2 else TableKind.D3

    /** 調校檔裡每列的格數與列數；D1／D2 的 0 視為 1。 */
    val storedColumns: Int get() = if (sizeX == 0) 1 else sizeX
    val storedRows: Int get() = if (sizeY == 0) 1 else sizeY

    /** EEPROM 群組的表不進調校檔內容。 */
    val isEeprom: Boolean get() = group == "EEPROM"

    /** 位元選項表與 0／1 開關：STX 對這類表停用加減乘除，改用開關。 */
    val adjustDisabled: Boolean get() = mark == "Option" || (min == 0.0 && max == 1.0 && kind == TableKind.D1)

    /** 該格寬能存的最大原始值。 */
    val rawMax: Long
        get() = when (cellBytes) {
            2 -> 65535L
            4 -> 4294967295L
            else -> 255L
        }

    /** 這個 C 版本、這個機型的可見等級；版本停用或機型沒列就是 null。 */
    fun level(cVersion: Int, modelId: Int): Int? = versions[cVersion]?.takeIf { it.enabled }?.levels?.get(modelId)
}

class Definitions(val serial: String, val tables: Map<Int, TableDef>) {
    companion object {
        /**
         * 讀已解開的定義檔。兩種外層標籤都認：
         * 完整（`Tab name absid`／`Tinf`／`Ver`／`Model`）與縮寫（`Tab n id`／`D`／`V`，X2 與 SXS 用）。
         * [idOffset]：附掛定義檔的表號位移，SYMGU 是 2560。
         */
        fun parse(xml: String, idOffset: Int = 0): Definitions {
            val root = Xml.parse(xml)
            val serial = root.child("ControlFileSerial")?.attr("sn") ?: ""
            val params = root.child("Parameters") ?: throw IllegalArgumentException("Definitions have no Parameters")
            val out = LinkedHashMap<Int, TableDef>()
            for (tab in params.children) {
                if (tab.name != "Tab") continue
                val name = tab.attr("name") ?: tab.attr("n") ?: throw IllegalArgumentException("Table has no name")
                val inf = tab.child("Tinf") ?: tab.child("D") ?: throw IllegalArgumentException("$name has no field definition")
                val f = inf.innerText.split(',').map { it.trim() }
                if (f.size < 25) throw IllegalArgumentException("$name has only ${f.size} fields")
                val versions = LinkedHashMap<Int, VersionEntry>()
                for (v in tab.children) {
                    when (v.name) {
                        "Ver" -> {
                            val levels = LinkedHashMap<Int, Int>()
                            for (m in v.children) if (m.name == "Model") {
                                val mid = m.attr("mid")?.toIntOrNull() ?: continue
                                levels[mid] = m.innerText.trim().toIntOrNull() ?: continue
                            }
                            versions[v.attr("c_version")!!.toInt()] = VersionEntry(v.attr("status") ?: "", levels)
                        }
                        "V" -> {
                            val levels = LinkedHashMap<Int, Int>()
                            for (item in v.innerText.split(',')) {
                                val dash = item.indexOf('-')
                                if (dash <= 0) continue
                                val mid = item.substring(0, dash).trim().toIntOrNull() ?: continue
                                levels[mid] = item.substring(dash + 1).trim().toIntOrNull() ?: continue
                            }
                            versions[v.attr("c")!!.toInt()] = VersionEntry(v.attr("st") ?: "", levels)
                        }
                    }
                }
                fun opt(i: Int): String? = f[i].takeUnless { it == "_" }
                val def = TableDef(
                    name = name,
                    absId = f[0].toInt() + idOffset,
                    sizeX = f[1].toInt(),
                    sizeY = f[2].toInt(),
                    cellBytes = f[3].toInt(),
                    scope = f[4],
                    mark = f[5],
                    scaleX = f[6],
                    scaleY = f[7],
                    max = f[8].toDouble(),
                    min = f[9].toDouble(),
                    slew = f[10].toDouble(),
                    gain = f[11].toFloat(),
                    offset = f[12].toFloat(),
                    optionBits = f[13],
                    unit = f[14],
                    group = f[15],
                    decimals = opt(16)?.toInt() ?: 0,
                    buttonMatrix = f[19],
                    groupShow = opt(21)?.toInt() ?: 0,
                    tc300Swap = opt(22),
                    ultraVisible = opt(23),
                    r3Swap = opt(24),
                    versions = versions,
                    fields = f,
                )
                out[def.absId] = def
            }
            return Definitions(serial, out)
        }
    }
}
