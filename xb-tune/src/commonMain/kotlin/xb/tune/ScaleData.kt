package xb.tune

/**
 * 固定軸的刻度表（STX 與 XTune 內建，內容相同）。
 * 依族分組；查詢時族名取刻度代號第一個 `-` 之前的字，和 STX 的找法一致。
 */
class ScaleData(val version: String, private val families: Map<String, Map<String, FloatArray>>) {
    val count: Int get() = families.values.sumOf { it.size }

    /** 例如 `RPM-11`。找不到回 null。 */
    fun find(key: String): FloatArray? {
        val dash = key.indexOf('-')
        if (dash <= 0) return null
        return families[key.substring(0, dash)]?.get(key)
    }

    companion object {
        /** 刻度檔所有刻度加起來最多幾個值（實際檔案遠小於這個數）。 */
        const val MAX_VALUES = 2_000_000

        /** 逗號分隔的浮點數；邊讀邊算個數（[count] 超過上限時丟例外），不先切成字串清單。 */
        private fun values(text: String, count: () -> Unit): FloatArray {
            var out = FloatArray(16); var n = 0
            var i = 0
            while (i < text.length) {
                if (text[i] == ',') { i++; continue }
                val start = i
                while (i < text.length && text[i] != ',') i++
                val s = text.substring(start, i).trim()
                if (s.isEmpty()) continue
                count()
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = s.toFloat()
            }
            return out.copyOf(n)
        }

        fun parse(xml: String): ScaleData {
            val root = Xml.parse(xml)
            val families = LinkedHashMap<String, Map<String, FloatArray>>()
            var version = ""
            var cells = 0
            for (fam in root.children) {
                if (fam.name == "Version") { version = fam.innerText.trim(); continue }
                val scales = LinkedHashMap<String, FloatArray>()
                for (sc in fam.children) {
                    if (sc.name != "scale") continue
                    val key = sc.innerText
                    // STX 取同名的第一筆
                    if (key in scales) continue
                    scales[key] = values(sc.attr("value") ?: "") { if (++cells > MAX_VALUES) throw IllegalArgumentException("Scale data has more than $MAX_VALUES values") }
                }
                families[fam.name] = scales
            }
            return ScaleData(version, families)
        }
    }
}
