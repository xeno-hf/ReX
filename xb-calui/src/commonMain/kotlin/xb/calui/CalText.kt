package xb.calui

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** 功能專頁的字串（四語）。找不到就回 key。 */
object CalText {
    fun get(key: String, lang: String): String {
        val a = CAL_STRINGS[key] ?: return key
        return when (lang) { "zh-TW" -> a[1]; "zh-CN" -> a[2]; "ja-JP" -> a[3]; else -> a[0] }
    }

    /** 字串裡的 %s／%d／%.Nf（也收 %1$s 這種寫法）依序換成 [args]。 */
    fun format(key: String, lang: String, vararg args: Any): String {
        var i = 0
        return Regex("%(?:\\d+\\$)?(\\.(\\d+))?([sdf])").replace(get(key, lang)) { m ->
            val a = args.getOrNull(i++) ?: return@replace m.value
            when (m.groupValues[3]) {
                "f" -> fixed((a as Number).toDouble(), m.groupValues[2].toIntOrNull() ?: 6)
                "d" -> (a as Number).toLong().toString()
                else -> a.toString()
            }
        }
    }

    fun has(key: String): Boolean = key in CAL_STRINGS
}

/** 固定小數位（common 沒有 String.format）。 */
fun fixed(v: Double, digits: Int): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    val p = 10.0.pow(digits)
    val n = (abs(v) * p).roundToLong()
    val sign = if (v < 0 && n != 0L) "-" else ""
    if (digits == 0) return sign + n
    val s = n.toString().padStart(digits + 1, '0')
    return sign + s.dropLast(digits) + "." + s.takeLast(digits)
}

/** 顯示用數字：最多 [digits] 位小數，去掉多餘的 0。 */
fun num(v: Double, digits: Int = 2): String = fixed(v, digits).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }.let { if (it == "-0") "0" else it }
