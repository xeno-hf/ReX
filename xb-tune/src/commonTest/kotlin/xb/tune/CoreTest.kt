package xb.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用人工寫的小檔案測規則；不含任何官方定義或實車資料。 */
class CoreTest {
    private fun tinf(
        id: Int, x: Int, y: Int, cell: Int = 1, sx: String = "0", sy: String = "0",
        max: String = "255", min: String = "0", gain: String = "1", off: String = "0", dec: String = "_", group: String = "G",
    ) = "$id,$x,$y,$cell,0,0,$sx,$sy,$max,$min,1,$gain,$off,_,u,$group,$dec,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_"

    private val longForm = """<?xml version="1.0" encoding="utf-8"?>
<ECUControlFile>
<ControlFileSerial sn="1"/>
<Parameters>
<Tab name="Map" absid="1"><Tinf>${tinf(1, 4, 2, sx = "Dyna-2-RPM", sy = "LOAD-1")}</Tinf>
<Ver c_version="7" status="Enable"><Model name="A" mid="10">1</Model><Model name="B" mid="20">4</Model></Ver>
<Ver c_version="6" status="Disable"><Model name="A" mid="10">1</Model></Ver>
</Tab>
<Tab name="Axis" absid="2"><Tinf>${tinf(2, 4, 0, cell = 2, max = "20000", gain = "10", dec = "0")}</Tinf>
<Ver c_version="7" status="Enable"><Model name="A" mid="10">7</Model></Ver>
</Tab>
<Tab name="Temp" absid="3"><Tinf>${tinf(3, 0, 0, max = "215", min = "-40", off = "40")}</Tinf>
<Ver c_version="7" status="Enable"><Model name="A" mid="10">1</Model></Ver>
</Tab>
</Parameters>
</ECUControlFile>"""

    private val shortForm = """<ECUControlFile><ControlFileSerial sn="2"/><Parameters>
<Tab n="Map" id="1"><D>${tinf(1, 4, 2)}</D><V c="7" st="En">10-1,20-4</V><V c="6" st="NA"></V></Tab>
</Parameters></ECUControlFile>"""

    private val scales = """<TableScale><Version>1</Version>
<LOAD><scale size="2" value="10,20">LOAD-1</scale><scale size="3" value="1,2,3">LOAD-2</scale></LOAD>
</TableScale>"""

    private fun tune(axisRow: String = "8000,16000,24000,0", note: String = "hi") = """<?xml version="1.0" encoding="UTF-8"?><AMC_File>
<MAP_Type>AMX</MAP_Type>
<V_Code>1</V_Code>
<C_Code>7</C_Code>
<A_Code>2</A_Code>
<Table_ID>05_18_138_01</Table_ID>
<LastSaveTime>t</LastSaveTime>
<CarNote>$note</CarNote>
<IO_Config>
55,AA,
</IO_Config>
<TableValue>
<Table name="Map" absid="1">
1,2,3,4,
5,6,7,8,
</Table>
<Table name="Axis" absid="2">
$axisRow,
</Table>
<Table name="Temp" absid="3">
65
</Table>
</TableValue>
</AMC_File>"""

    @Test
    fun xmlKeepsPositionsAndText() {
        val src = "<?xml version=\"1.0\"?><a x='1' y=\"&lt;2\"><!-- c --><b>t1</b>mid&amp;<c/><![CDATA[<raw>]]></a>"
        val a = Xml.parse(src)
        assertEquals("a", a.name)
        assertEquals("1", a.attr("x"))
        assertEquals("<2", a.attr("y"))
        assertEquals(listOf("b", "c"), a.children.map { it.name })
        assertEquals("t1mid&<raw>", a.innerText)
        val b = a.child("b")!!
        assertEquals("<b>t1</b>", src.substring(b.start, b.end))
        assertEquals("t1", src.substring(b.innerStart, b.innerEnd))
        val c = a.child("c")!!
        assertEquals("<c/>", src.substring(c.start, c.end))
        assertEquals(c.innerStart, c.innerEnd)
    }

    @Test
    fun rowsSkipBlanksAndTrailingCommas() {
        val rows = TuneFile.parseRows("\n1, 2,3,\r\n\n4294967295,0,\n")
        assertEquals(2, rows.size)
        assertEquals(listOf(1L, 2L, 3L), rows[0].toList())
        assertEquals(listOf(4294967295L, 0L), rows[1].toList())
    }

    @Test
    fun bothDefinitionFormsGiveTheSameTable() {
        val long = Definitions.parse(longForm).tables.getValue(1)
        val short = Definitions.parse(shortForm).tables.getValue(1)
        assertEquals(TableKind.D3, long.kind)
        assertEquals(4, short.sizeX)
        assertEquals(1, long.level(7, 10)); assertEquals(1, short.level(7, 10))
        assertEquals(4, long.level(7, 20)); assertEquals(4, short.level(7, 20))
        assertNull(long.level(6, 10)) // 停用的版本沒有等級
        assertNull(short.level(6, 10))
        assertNull(long.level(7, 99))
        assertEquals(2562, Definitions.parse(longForm, idOffset = 2560).tables.keys.elementAt(1))
    }

    @Test
    fun conversionFollowsStxRounding() {
        val defs = Definitions.parse(longForm)
        val temp = defs.tables.getValue(3) // 物理值＝原始值−40
        assertEquals(25.0, Convert.rawToPhysical(temp, 65))
        assertEquals(65L, Convert.physicalToRaw(temp, 25.0))
        assertEquals(0L, Convert.physicalToRaw(temp, -999.0)) // 先夾到 Min
        assertEquals(255L, Convert.physicalToRaw(temp, 999.0))
        // 物理→原始：銀行家捨入
        assertEquals(64L, Convert.physicalToRaw(temp, 24.5))
        assertEquals(66L, Convert.physicalToRaw(temp, 25.5))
        // 顯示捨入：遠離零
        assertEquals(3.0, Convert.roundAwayFromZero(2.5, 0))
        assertEquals(-3.0, Convert.roundAwayFromZero(-2.5, 0))
        assertEquals(0.13, Convert.roundAwayFromZero(0.125, 2))
        assertEquals(2.0, Convert.roundAwayFromZero(2.4999, 0))
        // 超出定義範圍的原始值要認得出來，不是默默夾掉
        assertTrue(Convert.isOutOfRange(temp, 0xFFFF))
        assertFalse(Convert.isOutOfRange(temp, 255))
    }

    @Test
    fun tuneResolvesAxesAndEffectiveSize() {
        val t = Tune(Definitions.parse(longForm), ScaleData.parse(scales), TuneFile.parse(tune()))
        assertEquals(10, t.file.modelId)
        assertTrue(t.file.is300kPa)
        val map = t.definition(1)!!
        // 動態軸取到不再遞增為止：8000,16000,24000,0 → 3 格
        val x = assertIs<AxisLookup.Found>(t.axisX(map)).axis
        assertTrue(x.editable); assertEquals(2, x.sourceTable); assertEquals("RPM", x.name)
        assertEquals(listOf(800f, 1600f, 2400f), x.values.toList())
        val y = assertIs<AxisLookup.Found>(t.axisY(map)).axis
        assertFalse(y.editable); assertEquals("LOAD", y.name)
        assertEquals(3, t.effectiveColumns(map)) // 儲存 4 欄，有效 3 欄
        assertEquals(2, t.effectiveRows(map))
        assertEquals(1, t.level(map))
        val r = t.check()
        assertEquals(3, r.tablesChecked)
        assertEquals(1, r.dynamicAxes); assertEquals(1, r.dynamicAxesShorter); assertEquals(1, r.fixedAxes)
        assertTrue(r.sizeMismatch.isEmpty() && r.axisMissing.isEmpty() && r.unknownTables.isEmpty())
    }

    @Test
    fun tuneReportsWhatDoesNotMatch() {
        val file = TuneFile.parse(tune().replace("5,6,7,8,\n", "").replace("<Table name=\"Temp\" absid=\"3\">", "<Table name=\"Temp\" absid=\"9\">"))
        val r = Tune(Definitions.parse(longForm), ScaleData.parse(scales), file).check()
        assertEquals(listOf(1), r.sizeMismatch)
        assertEquals(listOf(3), r.missingTables)
        assertEquals(listOf(9), r.unknownTables)
    }
}
