package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** 定義包讀進來前的檢查：換算參數、刻度上下界、動態軸指到的表。 */
class MapDefsTest {
    private fun pack(
        gain: String = "0.5",
        min: String = "0",
        max: String = "100",
        cols: Int = 3,
        rows: Int = 2,
        x: String = "{\"name\":\"TPS\",\"values\":[0,50,100]}",
        y: String = "{\"name\":\"RPM\",\"table\":99}",
        axes: String = "[{\"id\":99,\"cols\":2,\"bytes\":2,\"gain\":1,\"offset\":0,\"min\":0,\"max\":9000,\"dec\":0}]",
    ): String =
        """{"format":"rx-mapdefs-1","platform":"S3","map_type":"AMX","app":"test","tables":[
           {"id":1001,"name":"Fuel","group":"g","cols":$cols,"rows":$rows,"bytes":2,
            "gain":$gain,"offset":0,"min":$min,"max":$max,"dec":1,"unit":"%","scope":[0,0],
            "x":$x,"y":$y}],"axes":$axes}"""

    @Test
    fun acceptsValidScalesAndAxes() {
        val defs = MapDefs.parse(pack())
        val t = defs.tables.single()
        assertEquals(0.5f, t.scale.gain)
        assertEquals(50.0, t.scale.physical(25))
        val y = assertNotNull(t.y)
        val values = defs.axisValues(y) { id -> if (id == 99) longArrayOf(1000, 5000) else null }
        assertEquals(listOf(1000f, 5000f), values?.toList())
    }

    @Test
    fun rejectsZeroGain() {
        assertFailsWith<MapDefs.Companion.Invalid> { MapDefs.parse(pack(gain = "0")) }
        // 小到單精度變成 0 也不行（換算要除以 gain）
        assertFailsWith<MapDefs.Companion.Invalid> { MapDefs.parse(pack(gain = "1e-50")) }
    }

    @Test
    fun rejectsUnorderedScaleBounds() {
        assertFailsWith<MapDefs.Companion.Invalid> { MapDefs.parse(pack(min = "100", max = "0")) }
        assertEquals(5.0, MapDefs.parse(pack(min = "5", max = "5")).tables.single().scale.min)
    }

    @Test
    fun rejectsDynamicAxisWithoutItsTable() {
        assertFailsWith<MapDefs.Companion.Invalid> { MapDefs.parse(pack(axes = "[]")) }
    }

    @Test
    fun rejectsAxisTooShortForTheTable() {
        // x 軸要能標滿 3 欄；這張軸表只有 2 個值
        assertFailsWith<MapDefs.Companion.Invalid> {
            MapDefs.parse(pack(x = "{\"name\":\"TPS\",\"table\":99}"))
        }
        // 固定刻度也一樣：2 個值標不滿 3 欄
        assertFailsWith<MapDefs.Companion.Invalid> {
            MapDefs.parse(pack(x = "{\"name\":\"TPS\",\"values\":[0,50]}"))
        }
        // 值比欄數多照樣可用（動態軸的長度也是取到不再遞增為止）
        val longer = assertNotNull(MapDefs.parse(pack(x = "{\"name\":\"TPS\",\"values\":[0,50,100,150]}")).tables.single().x as? MapDefs.Axis.Fixed)
        assertEquals(4, longer.values.size)
    }
}
