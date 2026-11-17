package xb.tune

/**
 * 油圖上「引擎目前的位置」（準星）。做法照 XTune：
 * - 表定義 Tinf[4]（[TableDef.scope]）＝「X 軸即時變數 absid-Y 軸即時變數 absid」，0＝那個方向沒有。
 * - 位置以格子為單位：值剛好等於第 i 個軸刻度時在第 i 格的中心（i＋0.5），兩個刻度之間線性內插。
 * - 超出軸的範圍時照最外面那一段的斜率往外延伸（準星會跑到表格外面）。
 * - 「最近的格子」（Cell Track 用）：在兩個刻度之間，過了中點算下一格；超出範圍算最外面那一格。
 */
object Scope {
    /** [TableDef.scope] 解析成 X、Y 的變數 absid；沒有的方向是 null。整個沒有就回 null。 */
    fun vars(def: TableDef): Pair<Int?, Int?>? {
        val p = def.scope.split('-')
        if (p.size < 2) return null
        val x = p[0].trim().toIntOrNull()?.takeIf { it > 0 }
        val y = p[1].trim().toIntOrNull()?.takeIf { it > 0 }
        return if (x == null && y == null) null else x to y
    }

    /** 一個方向上的位置：[pos]＝格子座標（可以小於 0 或大於格數），[nearest]＝最近的格子。 */
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
