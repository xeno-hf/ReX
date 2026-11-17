package xb.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScopeTest {
    private val rpm = floatArrayOf(1000f, 2000f, 3000f, 5000f)

    @Test fun onAGridValueIsTheCellCentre() {
        val p = Scope.locate(2000.0, rpm)!!
        assertEquals(1.5, p.pos, 1e-9)
        assertEquals(1, p.nearest)
    }

    @Test fun betweenValuesInterpolatesAndRoundsAtTheMidpoint() {
        val a = Scope.locate(3500.0, rpm)!!
        assertEquals(2.75, a.pos, 1e-9)
        assertEquals(2, a.nearest)      // 3500 ≤ 中點 4000
        val b = Scope.locate(4100.0, rpm)!!
        assertEquals(3, b.nearest)
    }

    @Test fun outsideTheAxisExtendsWithTheEndSlope() {
        val hi = Scope.locate(6000.0, rpm)!!
        assertEquals(3 + 0.5 + 0.5, hi.pos, 1e-9) // 最後一段 2000 rpm 寬，超出 1000＝半格
        assertEquals(3, hi.nearest)
        val lo = Scope.locate(500.0, rpm)!!
        assertEquals(0.0, lo.pos, 1e-9)
        assertEquals(0, lo.nearest)
        assertEquals(0.5, Scope.locate(1000.0, rpm)!!.pos, 1e-9)
    }

    @Test fun degenerateAxes() {
        assertNull(Scope.locate(1.0, floatArrayOf()))
        assertEquals(0.5, Scope.locate(99.0, floatArrayOf(5f))!!.pos, 1e-9)
        assertNull(Scope.locate(Double.NaN, rpm))
    }
}
