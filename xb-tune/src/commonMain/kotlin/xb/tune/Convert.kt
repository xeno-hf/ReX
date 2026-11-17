package xb.tune

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.truncate

/**
 * 原始值與物理值的換算，照 STX。
 * 原始值一律無號；負的物理值靠 Offset 表示。
 */
object Convert {
    /** 沒夾範圍、沒捨入的物理值。用來判斷檔案裡的值有沒有超出定義範圍。 */
    fun rawToPhysicalExact(def: TableDef, raw: Long): Double =
        (raw.toDouble() - def.offset.toDouble()) / def.gain.toDouble()

    /** STX 畫面上顯示的值：夾在 Min～Max，再四捨五入到 Dec 位（遠離零）。 */
    fun rawToPhysical(def: TableDef, raw: Long): Double {
        var v = rawToPhysicalExact(def, raw)
        v = if (v > def.max) def.max else v
        v = if (v < def.min) def.min else v
        return roundAwayFromZero(v, def.decimals)
    }

    /**
     * 物理值轉原始值：先夾 Min～Max，乘 Gain 加 Offset 後銀行家捨入，再夾到格寬上限。
     * 算出負數或不是數字時回 0。STX 這裡是把雙精度直接轉無號整數，負數的結果依執行環境而定；
     * 只有定義本身不一致（Min 換算後小於 0）才會遇到。
     */
    fun physicalToRaw(def: TableDef, physical: Double): Long {
        var p = physical
        p = if (p > def.max) def.max else p
        p = if (p < def.min) def.min else p
        val r = round(p * def.gain.toDouble() + def.offset.toDouble())
        if (r.isNaN() || r < 0.0) return 0L
        val cap = def.rawMax
        return if (r > cap.toDouble()) cap else r.toLong()
    }

    /**
     * 檔案裡的值是否超出定義的 Min～Max。
     * 容許半個原始值的誤差：Gain 是單精度，剛好落在邊界的值換算後可能差一點點。
     */
    fun isOutOfRange(def: TableDef, raw: Long): Boolean {
        val g = def.gain.toDouble()
        if (g == 0.0) return false
        val v = rawToPhysicalExact(def, raw)
        val eps = 0.5 / abs(g)
        return v > def.max + eps || v < def.min - eps
    }

    /** 顯示值換回去是否得到同一個原始值。得不到的格子不能重新編碼，只能原樣保留。 */
    fun isReversible(def: TableDef, raw: Long): Boolean = physicalToRaw(def, rawToPhysical(def, raw)) == raw

    private val POW10 = doubleArrayOf(
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15,
    )

    /** 與 .NET 的 Math.Round(value, digits, MidpointRounding.AwayFromZero) 同樣的算法。 */
    fun roundAwayFromZero(value: Double, digits: Int): Double {
        if (!(abs(value) < 1e16)) return value
        val p = POW10[digits.coerceIn(0, 15)]
        val scaled = value * p
        var whole = truncate(scaled)
        val fraction = scaled - whole
        if (abs(fraction) >= 0.5) whole += sign(fraction)
        return whole / p
    }
}
