package rx.session

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * 油圖定義包（`rx-mapdefs-1`，XB 從使用者自己的定義檔做的；格式見 XB 的 `xb.sync.MapDefs`）。
 * ReX 的油圖頁只看不改：表的內容連上 ECU 自己讀（物件 0x3000＋表號，一列一個 sub），
 * 換算、軸、準星位置都照 XB（＝STX／XTune）的算法，兩邊畫出來才會一樣。
 */
class MapDefs(
    val platform: String,
    val mapType: String,
    val c: Int?,
    val model: Int?,
    val app: String,
    val tables: List<Table>,
    val axes: Map<Int, AxisTable>,
) {
    /** 數值的換算參數。gain／offset 是單精度（同 STX）。 */
    class Scale(val gain: Float, val offset: Float, val min: Double, val max: Double, val dec: Int) {
        /** STX 畫面上顯示的值：夾在 Min～Max，再四捨五入到 Dec 位（遠離零）。同 XB `Convert.rawToPhysical`。 */
        fun physical(raw: Long): Double {
            var v = (raw.toDouble() - offset.toDouble()) / gain.toDouble()
            if (v > max) v = max
            if (v < min) v = min
            return roundAwayFromZero(v, dec)
        }
    }

    sealed interface Axis {
        val name: String
        class Fixed(override val name: String, val values: FloatArray) : Axis
        /** 動態軸：刻度是 [table] 那張表的第 0 列，取到數值不再遞增為止。 */
        class Dynamic(override val name: String, val table: Int) : Axis
    }

    class Table(
        val id: Int, val name: String, val titles: Map<String, String>, val group: String,
        val cols: Int, val rows: Int, val bytes: Int, val scale: Scale, val unit: String,
        val scopeX: Int?, val scopeY: Int?, val x: Axis?, val y: Axis?,
    ) {
        fun title(lang: String): String = titles[lang] ?: titles["en-US"] ?: name
    }

    class AxisTable(val id: Int, val cols: Int, val bytes: Int, val scale: Scale)

    fun table(id: Int): Table? = tables.firstOrNull { it.id == id }

    /** 這條軸的刻度。動態軸要給它那張表的第 0 列（ECU 讀到的原始值）；讀不到回 null。 */
    fun axisValues(axis: Axis, rows: (Int) -> LongArray?): FloatArray? = when (axis) {
        is Axis.Fixed -> axis.values
        is Axis.Dynamic -> {
            val at = axes[axis.table]
            val row = rows(axis.table)
            if (at == null || row == null) null else {
                var n = row.size
                for (i in 1 until row.size) if (row[i] < row[i - 1]) { n = i; break }
                FloatArray(n) { at.scale.physical(row[it]).toFloat() }
            }
        }
    }

    companion object {
        const val FORMAT = "rx-mapdefs-1"

        class Invalid(message: String) : Exception(message)

        fun parse(text: String): MapDefs {
            val root = MiniJson.parse(text) as? Map<*, *> ?: throw Invalid("Not map definitions")
            if (root["format"] != FORMAT) throw Invalid("Not map definitions (${root["format"] ?: "no format"})")
            // 數字一律要是有限的；整數欄位要剛好是整數
            fun Map<*, *>.int(k: String): Int? = (this[k] as? Double)?.let { d ->
                if (!d.isFinite() || d != truncate(d) || abs(d) > 1e9) throw Invalid("Bad $k") else d.toInt()
            }
            fun Map<*, *>.dbl(k: String): Double = (this[k] as? Double)?.takeIf { it.isFinite() } ?: throw Invalid("Missing or bad $k")
            fun Map<*, *>.str(k: String): String = this[k] as? String ?: ""
            // 換成單精度之後也要是有限的數；dec 0～15
            fun f32(m: Map<*, *>, k: String): Float = m.dbl(k).toFloat().takeIf { it.isFinite() } ?: throw Invalid("Bad $k")
            fun scale(m: Map<*, *>): Scale {
                val g = f32(m, "gain"); val o = f32(m, "offset")
                // 換算要除以 gain：0（含太小變成 0 的）不能用
                if (g == 0f) throw Invalid("Bad gain")
                val dec = m.int("dec") ?: 0
                if (dec !in 0..15) throw Invalid("Bad dec")
                val min = m.dbl("min"); val max = m.dbl("max")
                // 刻度上下界要照順序，畫面與夾值都靠它
                if (min > max) throw Invalid("Bad scale range")
                return Scale(g, o, min, max, dec)
            }
            fun axis(o: Any?): Axis? {
                val m = o as? Map<*, *> ?: return null
                val name = m.str("name")
                m.int("table")?.let { return Axis.Dynamic(name, it) }
                val vals = (m["values"] as? List<*>)?.map { (it as? Double)?.takeIf { d -> d.isFinite() && d.toFloat().isFinite() } ?: throw Invalid("Bad axis value") } ?: return null
                if (vals.isEmpty() || vals.size > 256) throw Invalid("Bad axis length")
                return Axis.Fixed(name, vals.map { it.toFloat() }.toFloatArray())
            }
            val list = root["tables"] as? List<*> ?: throw Invalid("No tables")
            if (list.isEmpty() || list.size > 4096) throw Invalid("Bad table count ${list.size}")
            val tables = list.map { t ->
                val m = t as? Map<*, *> ?: throw Invalid("Bad table")
                val sc = (m["scope"] as? List<*>)?.map { v ->
                    (v as? Double)?.takeIf { it.isFinite() && it == truncate(it) && it in 0.0..65535.0 }?.toInt() ?: throw Invalid("Bad scope")
                } ?: listOf(0, 0)
                if (sc.size > 2) throw Invalid("Bad scope")
                @Suppress("UNCHECKED_CAST")
                Table(
                    id = m.int("id") ?: throw Invalid("Table without id"), name = m.str("name"),
                    titles = (m["title"] as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap().orEmpty(),
                    group = m.str("group"),
                    cols = m.int("cols") ?: 1, rows = m.int("rows") ?: 1, bytes = m.int("bytes") ?: 1,
                    scale = scale(m), unit = m.str("unit"),
                    scopeX = sc.getOrNull(0)?.takeIf { it > 0 }, scopeY = sc.getOrNull(1)?.takeIf { it > 0 },
                    x = axis(m["x"]), y = axis(m["y"]),
                ).also { if (it.id !in 0..0xFFF || it.cols !in 1..256 || it.rows !in 1..256 || it.bytes !in 1..4) throw Invalid("Bad table ${it.id}") }
            }
            val axes = (root["axes"] as? List<*>).orEmpty().map { a ->
                val m = a as? Map<*, *> ?: throw Invalid("Bad axis table")
                AxisTable(m.int("id") ?: throw Invalid("Axis without id"), m.int("cols") ?: 1, m.int("bytes") ?: 1, scale(m))
                    .also { if (it.id !in 0..0xFFF || it.cols !in 1..256 || it.bytes !in 1..4) throw Invalid("Bad axis table ${it.id}") }
            }.associateBy { it.id }
            if (tables.map { it.id }.toSet().size != tables.size) throw Invalid("Duplicate table id")
            // 軸要能標滿這張表（x 對 cols、y 對 rows）：固定的看 values 長度，動態的看它指到的軸表
            for (t in tables) for ((dir, axis) in listOf("x" to t.x, "y" to t.y)) {
                val need = if (dir == "x") t.cols else t.rows
                when (axis) {
                    is Axis.Dynamic -> {
                        val at = axes[axis.table] ?: throw Invalid("Missing axis table ${axis.table} for table ${t.id}")
                        if (at.cols < need) throw Invalid("Axis table ${axis.table} has ${at.cols} values, table ${t.id} needs $need")
                    }
                    is Axis.Fixed -> if (axis.values.size < need) throw Invalid("Axis ${axis.name} has ${axis.values.size} values, table ${t.id} needs $need")
                    null -> Unit
                }
            }
            return MapDefs(root.str("platform"), root.str("map_type"), root.int("c"), root.int("model"), root.str("app"), tables, axes)
        }

        private val POW10 = doubleArrayOf(1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15)

        /** 與 .NET Math.Round(value, digits, MidpointRounding.AwayFromZero) 同樣的算法（同 XB）。 */
        fun roundAwayFromZero(value: Double, digits: Int): Double {
            if (!(abs(value) < 1e16)) return value
            val p = POW10[digits.coerceIn(0, 15)]
            val scaled = value * p
            var whole = truncate(scaled)
            val fraction = scaled - whole
            if (abs(fraction) >= 0.5) whole += sign(fraction)
            return whole / p
        }

        /** 一列的原始值（little-endian、無號，[bytes] 格寬）。 */
        fun decodeRow(data: ByteArray, cols: Int, bytes: Int): LongArray? {
            if (data.size != cols * bytes) return null
            return LongArray(cols) { i -> var v = 0L; for (k in 0 until bytes) v = v or ((data[i * bytes + k].toLong() and 0xFF) shl (8 * k)); v }
        }
    }
}

/**
 * 準星位置（同 XB `xb.tune.Scope`，跟 XTune 的準星位置一致）：值剛好等於第 i 個刻度時在第 i 格中心（i＋0.5），
 * 刻度之間線性內插，超出範圍照最外面那段的斜率延伸。[nearest]：過了中點算下一格。
 */
object MapScope {
    class Pos(val pos: Double, val nearest: Int)

    fun locate(value: Double, axis: FloatArray): Pos? {
        val n = axis.size
        if (n == 0 || value.isNaN()) return null
        if (n == 1) return Pos(0.5, 0)
        val first = axis[0].toDouble()
        val last = axis[n - 1].toDouble()
        if (value > last) {
            val step = last - axis[n - 2]
            val over = if (step != 0.0) (value - last) / step else 0.0
            return Pos(n - 1 + over + 0.5, n - 1)
        }
        if (value <= first) {
            val step = axis[1] - first
            val under = if (step != 0.0) (first - value) / step else 0.0
            return Pos(-under + 0.5, 0)
        }
        var i = 1
        while (i < n && axis[i] < value) i++
        val lo = axis[i - 1].toDouble()
        val hi = axis[i].toDouble()
        val frac = if (hi != lo) (value - lo) / (hi - lo) else 0.0
        return Pos(i - 1 + frac + 0.5, if (value <= (lo + hi) / 2.0) i - 1 else i)
    }
}

/** 定義包用的小 JSON 解析：物件→Map、陣列→List、數字→Double、字串、true／false、null。 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw MapDefs.Companion.Invalid("Trailing data in JSON")
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun bad(): Nothing = throw MapDefs.Companion.Invalid("Bad JSON at $i")
        fun peek(): Char = if (i < s.length) s[i] else bad()
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun eat(c: Char) { if (peek() != c) bad(); i++ }

        fun value(depth: Int): Any? {
            if (depth > 32) bad()
            return when (peek()) {
                '{' -> {
                    i++; ws()
                    val m = LinkedHashMap<String, Any?>()
                    if (peek() == '}') { i++; return m }
                    while (true) {
                        ws(); val k = str(); ws(); eat(':'); ws()
                        m[k] = value(depth + 1); ws()
                        when (peek()) { ',' -> i++; '}' -> { i++; return m }; else -> bad() }
                    }
                }
                '[' -> {
                    i++; ws()
                    val l = ArrayList<Any?>()
                    if (peek() == ']') { i++; return l }
                    while (true) {
                        ws(); l += value(depth + 1); ws()
                        when (peek()) { ',' -> i++; ']' -> { i++; return l }; else -> bad() }
                    }
                }
                '"' -> str()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> num()
            }
        }

        fun lit(w: String) { if (!s.startsWith(w, i)) bad(); i += w.length }

        fun num(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            if (i - start > 40) bad()
            return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad()
        }

        fun str(): String {
            eat('"')
            val sb = StringBuilder()
            while (true) {
                val c = peek(); i++
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = peek().also { i++ }) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '"', '\\', '/' -> sb.append(e)
                            'u' -> {
                                if (i + 4 > s.length || (i until i + 4).any { s[it] !in "0123456789abcdefABCDEF" }) bad()
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> bad()
                        }
                    }
                    else -> { if (c < ' ') bad(); sb.append(c) }
                }
            }
        }
    }
}
