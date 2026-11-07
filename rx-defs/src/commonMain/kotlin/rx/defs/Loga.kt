package rx.defs

/**
 * XTune `.loga` 讀取（純文字 CSV；格式 ）。
 *
 * 規則與分析管線 `analysis/loga_analyze2.parse` 對齊：空行略過；任何欄位無法轉數字
 * 或欄數 ≠ VAR NAME 數的列一律拒收並計數，**不補零**。
 */
class Loga(
    val header: Map<String, String>,
    val varIds: List<Int>,
    val groups: List<String>,
    val names: List<String>,
    /** 列優先：rows[i][j]＝第 i 列第 j 欄（第 0 欄為 Time ms）。 */
    val rows: List<DoubleArray>,
    val rejectedRows: Int,
) {
    fun column(name: String): DoubleArray {
        val j = names.indexOf(name)
        require(j >= 0) { "no channel $name" }
        return DoubleArray(rows.size) { rows[it][j] }
    }

    companion object {
        fun parse(bytes: ByteArray): Loga {
            val text = bytes.decodeToString()
            val ds = text.indexOf("<DATA START>")
            require(ds >= 0) { "not a loga: missing <DATA START>" }
            val headText = text.substring(0, ds)

            val header = LinkedHashMap<String, String>()
            for (line in headText.lineSequence()) {
                val l = line.trimEnd('\r')
                if (l.startsWith("<")) continue
                val k = l.indexOf(':')
                if (k > 0) header[l.substring(0, k)] = l.substring(k + 1)
            }
            fun section(tag: String): List<String> {
                val i = headText.indexOf("<$tag>")
                if (i < 0) return emptyList()
                val start = headText.indexOf('\n', i) + 1
                var end = headText.indexOf('\n', start)
                if (end < 0) end = headText.length
                return headText.substring(start, end).trimEnd('\r').split(',')
            }
            val names = section("VAR NAME").map { it.trim() }
            val ids = section("VAR ID").mapNotNull { it.trim().toIntOrNull() }
            val groups = section("VAR GROUP")

            val rows = ArrayList<DoubleArray>(20000)
            var rejected = 0
            val dataStart = text.indexOf('\n', ds) + 1
            for (raw in text.substring(dataStart).lineSequence()) {
                if (raw.isBlank()) continue
                val parts = raw.trimEnd('\r').split(',')
                if (parts.size != names.size) { rejected++; continue }
                val row = DoubleArray(parts.size)
                var ok = true
                for (j in parts.indices) {
                    val v = parts[j].trim().toDoubleOrNull()
                    if (v == null || !v.isFinite()) { ok = false; break }
                    row[j] = v
                }
                if (ok) rows += row else rejected++
            }
            return Loga(header, ids, groups, names, rows, rejected)
        }
    }
}
