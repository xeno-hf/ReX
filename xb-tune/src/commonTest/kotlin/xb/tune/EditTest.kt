package xb.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 編輯、存檔、比較的規則。全部是人工寫的小檔案。 */
class EditTest {
    private fun tinf(
        id: Int, x: Int, y: Int, cell: Int = 1, mark: String = "0", sx: String = "0",
        max: String = "100", min: String = "0", slew: String = "1", gain: String = "1", dec: String = "0",
    ) = "$id,$x,$y,$cell,0,$mark,$sx,0,$max,$min,$slew,$gain,0,_,u,G,$dec,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_"

    private fun tab(name: String, inf: String) =
        """<Tab name="$name" absid="${inf.substringBefore(',')}"><Tinf>$inf</Tinf><Ver c_version="7" status="Enable"><Model name="A" mid="10">1</Model></Ver></Tab>"""

    private val defs = """<ECUControlFile><ControlFileSerial sn="1"/><Parameters>
${tab("Map", tinf(10, 5, 3, sx = "Dyna-12-RPM", gain = "2", dec = "1"))}
${tab("Fuel", tinf(11, 5, 0, gain = "2.55"))}
${tab("Axis", tinf(12, 5, 0, cell = 2, max = "10000", slew = "100"))}
${tab("Opt", tinf(13, 0, 0, mark = "Option", max = "255"))}
${tab("Lim", tinf(14, 0, 0, max = "50"))}
${tab("Peak", tinf(15, 3, 3, gain = "2", dec = "1"))}
${tab("Lag", tinf(16, 0, 0, max = "0.99609375", gain = "256"))}
</Parameters></ECUControlFile>"""

    private val source = """<?xml version="1.0" encoding="UTF-8"?><AMC_File>
<MAP_Type>AMX</MAP_Type>
<V_Code>1</V_Code>
<C_Code>7</C_Code>
<A_Code>2</A_Code>
<Table_ID>05_18_10_01</Table_ID>
<LastSaveTime>2026-05-19 07:32 AM</LastSaveTime>
<CarNote>
note
</CarNote>
<IO_Config>
55,AA,
</IO_Config>
<TableValue>

<Table name="Map" absid="10">
20,0,0,0,60,
20,40,20,40,20,
0,0,0,0,0,
</Table>

<Table name="Fuel" absid="11">
26,26,51,26,26
</Table>

<Table name="Axis" absid="12">
1000,2000,3000,4000,5000,
</Table>

<Table name="Opt" absid="13">
5
</Table>

<Table name="Lim" absid="14">
200
</Table>

<Table name="Peak" absid="15">
0,0,0,
0,16,0,
0,0,0,
</Table>

<Table name="Lag" absid="16">
128
</Table>

</TableValue>
</AMC_File>
"""

    private fun open(text: String = source) =
        Tune(Definitions.parse(defs), ScaleData.parse("<TableScale><Version>1</Version></TableScale>"), TuneFile.parse(text))

    private fun Tune.row(id: Int, r: Int = 0) = rows(id)!![r].toList()
    private fun row(r: Int, cols: IntRange) = cols.map { Cell(r, it) }
    private fun changed(r: EditResult) = assertIs<EditResult.Done>(r).changed

    @Test
    fun addMultiplyDivideAndClamp() {
        val t = open()
        assertEquals(1, changed(t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, 1.0))) // 10.0 → 11.0
        assertEquals(22L, t.row(10)[0])
        assertEquals(1, changed(t.adjust(10, listOf(Cell(1, 1)), AdjustOp.Multiply, 1.1))) // 20.0 → 22.0
        assertEquals(44L, t.row(10, 1)[1])
        assertEquals(1, changed(t.adjust(10, listOf(Cell(1, 3)), AdjustOp.Divide, 4.0))) // 20.0 → 5.0
        assertEquals(10L, t.row(10, 1)[3])
        assertEquals(1, changed(t.adjust(10, listOf(Cell(0, 4)), AdjustOp.Add, 1000.0))) // 夾到 Max 100
        assertEquals(200L, t.row(10)[4])
        assertEquals(1, changed(t.adjust(10, listOf(Cell(0, 4)), AdjustOp.Set, 12.5)))
        assertEquals(25L, t.row(10)[4])
        assertIs<EditResult.Refused>(t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Divide, 0.0))
        assertIs<EditResult.Refused>(t.adjust(10, listOf(Cell(3, 0)), AdjustOp.Add, 1.0)) // 超出表的範圍
        assertIs<EditResult.Refused>(t.adjust(99, listOf(Cell(0, 0)), AdjustOp.Add, 1.0))
        assertEquals(listOf(10), t.modifiedTables)
    }

    @Test
    fun cellsWhoseValueDidNotChangeKeepTheirRaw() {
        val t = open()
        // 原始 26 顯示成 10，但 10 換回去是 25：這種格子只要沒被改到就不能動
        assertFalse(Convert.isReversible(t.definition(11)!!, 26))
        assertEquals(0, changed(t.adjust(11, row(0, 0..4), AdjustOp.Add, 0.0)))
        assertEquals(0, changed(t.adjust(11, row(0, 0..4), AdjustOp.Multiply, 1.0)))
        assertEquals(0, changed(t.adjust(11, listOf(Cell(0, 0)), AdjustOp.Set, 10.0))) // 設成和顯示值一樣
        assertFalse(t.isModified(11))
        assertEquals(1, changed(t.adjust(11, listOf(Cell(0, 2)), AdjustOp.Add, 1.0))) // 只改中間那格：20 → 21
        assertEquals(listOf(26L, 26L, 54L, 26L, 26L), t.row(11))
        // 超出定義範圍的值：沒改到就留著
        assertTrue(Convert.isOutOfRange(t.definition(14)!!, 200))
        assertEquals(0, changed(t.adjust(14, listOf(Cell(0, 0)), AdjustOp.Add, 0.0)))
        assertEquals(listOf(200L), t.row(14))
        assertEquals(0, changed(t.adjust(14, listOf(Cell(0, 0)), AdjustOp.Add, 1.0))) // 已經在上限，往上加不動
        assertEquals(listOf(200L), t.row(14))
        assertEquals(1, changed(t.adjust(14, listOf(Cell(0, 0)), AdjustOp.Add, -1.0))) // 從顯示的 50 起算
        assertEquals(listOf(49L), t.row(14))
        // 顯示值因捨入略超過 Max：128÷256＝0.5 顯示成 1，Max 是 0.996。沒改就不能被夾成 255
        assertEquals(1.0, Convert.rawToPhysical(t.definition(16)!!, 128))
        assertEquals(0, changed(t.adjust(16, listOf(Cell(0, 0)), AdjustOp.Add, 0.0)))
        assertEquals(listOf(128L), t.row(16))
    }

    @Test
    fun linearFillsBetweenTheEnds() {
        val t = open()
        assertEquals(3, changed(t.linear(10, row(0, 0..4)))) // 10,0,0,0,30 → 10,15,20,25,30
        assertEquals(listOf(20L, 30L, 40L, 50L, 60L), t.row(10))
        assertEquals(0, changed(t.linear(10, row(0, 0..4)))) // 已經是等差，不動
        // 直的：欄 0 是 10,10,0 → 中間變 5
        assertEquals(1, changed(t.linear(10, listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0)))))
        assertEquals(10L, t.row(10, 1)[0])
        assertIs<EditResult.Refused>(t.linear(10, row(0, 0..1)))
        assertIs<EditResult.Refused>(t.linear(10, listOf(Cell(0, 0), Cell(0, 2), Cell(0, 4)))) // 不連續
        assertIs<EditResult.Refused>(t.linear(10, listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1)))) // 不是一條線
    }

    @Test
    fun smoothOneAndTwoDimensions() {
        val t = open()
        assertEquals(3, changed(t.smooth(10, row(1, 0..4)))) // 10,20,10,20,10 → 10,15,15,15,10
        assertEquals(listOf(20L, 30L, 30L, 30L, 20L), t.row(10, 1))
        // 外框 3×3，中間一個尖峰 8：先縱向 (0+16+0)/4=4，再橫向 (0+8+0)/4=2；邊上的格子不動
        val all = (0..2).flatMap { r -> (0..2).map { c -> Cell(r, c) } }
        assertEquals(1, changed(t.smooth(15, all)))
        assertEquals(listOf(0L, 4L, 0L), t.row(15, 1))
        assertEquals(listOf(0L, 0L, 0L), t.row(15, 0))
        assertIs<EditResult.Refused>(t.smooth(10, row(0, 0..1)))
    }

    @Test
    fun undoAndRevert() {
        val t = open()
        t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, 1.0)
        t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, 1.0)
        assertEquals(24L, t.row(10)[0])
        assertEquals(10, t.undo()); assertEquals(22L, t.row(10)[0])
        assertEquals(10, t.undo()); assertEquals(20L, t.row(10)[0])
        assertFalse(t.isModified(10))
        assertNull(t.undo())
        // 改了又改回去不算改過
        t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, 1.0)
        t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, -1.0)
        assertFalse(t.isModified(10))
        t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, 5.0)
        t.revert(10)
        assertEquals(20L, t.row(10)[0])
        t.undo() // 復原「回到檔案內容」
        assertEquals(30L, t.row(10)[0])
    }

    @Test
    fun switchesAndRawEdits() {
        val t = open()
        assertIs<EditResult.Refused>(t.adjust(13, listOf(Cell(0, 0)), AdjustOp.Add, 1.0)) // 位元選項表不能加減
        assertEquals(1, changed(t.setRaw(13, Cell(0, 0), 7)))
        assertEquals(listOf(7L), t.row(13))
        assertEquals(0, changed(t.setRaw(13, Cell(0, 0), 7)))
        assertIs<EditResult.Refused>(t.setRaw(13, Cell(0, 0), 256)) // 超過 1 位元組
        assertEquals(1, changed(t.setRaw(14, Cell(0, 0), 255))) // 可以刻意寫超出定義範圍的值
    }

    @Test
    fun dynamicAxisIsRepairedToIncrease() {
        val t = open()
        val map = t.definition(10)!!
        assertEquals(5, t.effectiveColumns(map))
        val shown = t.effectiveColumns(map) // 編輯前先記下顯示幾格
        t.adjust(12, listOf(Cell(0, 2)), AdjustOp.Set, 1500.0) // 1000,2000,1500,…：不遞增了
        assertEquals(2, t.effectiveColumns(map))
        assertEquals(1, changed(t.regularizeAxis(12, shown))) // 1500 → 2000＋步進 100
        assertEquals(listOf(1000L, 2000L, 2100L, 4000L, 5000L), t.row(12))
        assertEquals(5, t.effectiveColumns(map))
        // 接近上限時夾在 Max
        t.adjust(12, listOf(Cell(0, 3)), AdjustOp.Set, 10000.0)
        t.regularizeAxis(12, shown)
        assertEquals(listOf(1000L, 2000L, 2100L, 10000L, 10000L), t.row(12))
    }

    @Test
    fun axisEditIsOneUndoStep() {
        val t = open()
        val map = t.definition(10)!!
        assertEquals(listOf(map), t.axisUsers(12))
        // 第 3 格設成 1500：先改值、再整理成 2100，算一次編輯
        assertEquals(2, changed(t.editAxis(12, 5, listOf(2), AdjustOp.Set, 1500.0)))
        assertEquals(listOf(1000L, 2000L, 2100L, 4000L, 5000L), t.row(12))
        assertTrue(t.isCellModified(12, 0, 2)); assertFalse(t.isCellModified(12, 0, 1))
        assertTrue(t.hasChanges)
        assertEquals(12, t.undo())
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L, 5000L), t.row(12))
        assertFalse(t.canUndo); assertFalse(t.hasChanges)
    }

    @Test
    fun savingRewritesOnlyWhatChanged() {
        val t = open()
        assertTrue(t.toXml() === source) // 沒改就是原文
        t.adjust(10, listOf(Cell(0, 0)), AdjustOp.Add, 1.0)
        t.adjust(11, listOf(Cell(0, 2)), AdjustOp.Add, 1.0)
        val out = t.toXml("2026-11-14 19:30:00")
        val expected = source
            .replace("20,0,0,0,60,\n", "22,0,0,0,60,\n")                 // 原本每列結尾有逗號：保留
            .replace("26,26,51,26,26\n", "26,26,54,26,26\n")             // 原本沒有：也不加
            .replace("2026-05-19 07:32 AM", "2026-11-14 19:30:00")
        assertEquals(expected, out)
        val again = open(out)
        assertEquals(listOf(22L, 0L, 0L, 0L, 60L), again.row(10))
        assertEquals(listOf(26L, 26L, 54L, 26L, 26L), again.row(11))
        assertEquals("2026-11-14 19:30:00", again.file.lastSaveTime)
        assertTrue(again.modifiedTables.isEmpty())
    }

    @Test
    fun compareAndCopyBetweenFiles() {
        val a = open()
        val b = open(source.replace("26,26,51,26,26", "25,26,60,26,26").replace("<Table name=\"Lim\" absid=\"14\">\n200\n</Table>", ""))
        val diffs = TuneCompare.compare(a, b)
        assertEquals(listOf(11 to DiffKind.Different, 14 to DiffKind.OnlyLeft), diffs.map { it.absId to it.kind })
        val fuel = diffs[0].cells
        assertEquals(listOf(0, 2), fuel.map { it.col })
        assertFalse(fuel[0].shownDiffers) // 26 與 25 都顯示成 10：畫面上看不出差別
        assertTrue(fuel[1].shownDiffers)
        assertTrue(TuneCompare.compare(a, a).isEmpty())
        // 只複製一格，再複製整張
        assertEquals(1, changed(b.copyFrom(a, 11, listOf(Cell(0, 0)))))
        assertEquals(listOf(26L, 26L, 60L, 26L, 26L), b.row(11))
        assertEquals(1, changed(b.copyFrom(a, 11)))
        assertEquals(listOf(14), TuneCompare.compare(a, b).map { it.absId })
        assertIs<EditResult.Refused>(b.copyFrom(a, 14)) // 這邊沒有這張表
    }
}
