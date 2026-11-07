package rx.protocol

import kotlin.math.abs

/**
 * 跨平台數字格式（取代 JVM 的 String.format）。
 * [fixed] 跟 Java `"%.nf"` 一樣：在最短十進位表示上做 HALF_UP 四捨五入，所以 Android 與 iOS 錄出的 loga 數字一致。
 */
object Fmt {
    /** `String.format("%.{decimals}f", v)` 的替代。 */
    fun fixed(v: Double, decimals: Int): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        val neg = v < 0 || (v == 0.0 && 1.0 / v < 0)
        val (digits, pointPos) = decimalDigits(abs(v))
        // digits＝有效數字（無前導零）；值 = 0.digits × 10^pointPos
        val d = decimals.coerceAtLeast(0)
        val keep = pointPos + d // 保留到小數第 d 位要幾個有效數字
        var intDigits: IntArray
        when {
            keep < 0 -> intDigits = IntArray(0)
            keep >= digits.size -> intDigits = digits.copyOf(keep).also { for (i in digits.size until keep) it[i] = 0 }
            else -> {
                intDigits = digits.copyOf(keep)
                if (digits[keep] >= 5) { // HALF_UP
                    var i = keep - 1
                    var carry = true
                    while (carry && i >= 0) {
                        if (intDigits[i] == 9) { intDigits[i] = 0; i-- } else { intDigits[i]++; carry = false }
                    }
                    if (carry) intDigits = intArrayOf(1) + intDigits
                }
            }
        }
        // intDigits 代表 round(v × 10^d) 的十進位數字
        val s = intDigits.joinToString("").trimStart('0').ifEmpty { "0" }.let { if (it.length <= d) it.padStart(d + 1, '0') else it }
        val body = if (d == 0) s else s.substring(0, s.length - d) + "." + s.substring(s.length - d)
        return if (neg) "-$body" else body // Java 對負數一律帶負號（含 -0.0）
    }

    /** 千分位整數（`"%,d"`）。 */
    fun grouped(n: Long): String {
        val s = abs(n).toString()
        val g = s.reversed().chunked(3).joinToString(",").reversed()
        return if (n < 0) "-$g" else g
    }

    /**
     * 精簡版 printf：支援 `%d %s %N$d %N$s %.Nf %,d %%`（App 字串表用到的全部）。
     */
    fun format(pattern: String, vararg args: Any?): String {
        val sb = StringBuilder()
        var i = 0
        var next = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c != '%' || i + 1 >= pattern.length) { sb.append(c); i++; continue }
            val m = SPEC.matchAt(pattern, i)
            if (m == null) { sb.append(c); i++; continue }
            i += m.value.length
            val conv = m.groupValues[4]
            if (conv == "%") { sb.append('%'); continue }
            val idx = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
            val a = args.getOrNull(idx)
            val prec = m.groupValues[3].toIntOrNull()
            sb.append(
                when (conv) {
                    "d" -> if (m.groupValues[2] == ",") grouped((a as Number).toLong()) else (a as? Number)?.toLong()?.toString() ?: a.toString()
                    "f" -> fixed((a as Number).toDouble(), prec ?: 6)
                    else -> a.toString()
                }
            )
        }
        return sb.toString()
    }

    private val SPEC = Regex("""%(?:(\d+)\$)?(,)?(?:\.(\d+))?([dsf%])""")

    /** 最短十進位表示的有效數字與小數點位置（值 = 0.d1d2… × 10^pos）。 */
    private fun decimalDigits(v: Double): Pair<IntArray, Int> {
        if (v == 0.0) return IntArray(0) to 1
        val s = v.toString().lowercase()
        val ePos = s.indexOf('e')
        val mant = if (ePos >= 0) s.substring(0, ePos) else s
        val exp = if (ePos >= 0) s.substring(ePos + 1).removePrefix("+").toInt() else 0
        val dot = mant.indexOf('.')
        val intPart = if (dot >= 0) mant.substring(0, dot) else mant
        val frac = if (dot >= 0) mant.substring(dot + 1) else ""
        val all = intPart + frac
        var lead = 0
        while (lead < all.length - 1 && all[lead] == '0') lead++
        val trimmed = all.substring(lead).trimEnd('0').ifEmpty { "0" }
        val pos = intPart.length - lead + exp
        return IntArray(trimmed.length) { trimmed[it] - '0' } to pos
    }
}
