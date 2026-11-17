package xb.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 存檔後換檔案基準（[Tune.rebase]）：同一個 Tune 留著，改過、復原都以新檔為準。 */
class RebaseTest {
    private fun tinf(id: Int, x: Int, y: Int, cell: Int = 1, max: String = "255") =
        "$id,$x,$y,$cell,0,0,0,0,$max,0,1,1,0,_,u,G,0,_,_,_,_,_,_,_,_,_"

    private fun tab(name: String, inf: String) =
        """<Tab name="$name" absid="${inf.substringBefore(',')}"><Tinf>$inf</Tinf><Ver c_version="7" status="En"><Model name="A" mid="10">1</Model></Ver></Tab>"""

    private val defs = """<ECUControlFile><Parameters>
${tab("Fuel", tinf(11, 5, 1))}
</Parameters></ECUControlFile>"""

    private fun source(vararg row: Int) = """<?xml version="1.0" encoding="UTF-8"?><AMC_File>
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

<Table name="Fuel" absid="11">
${row.joinToString(",")}
</Table>

</TableValue>
</AMC_File>
"""

    private fun open(text: String = source(10, 20, 30, 40, 50)) =
        Tune(Definitions.parse(defs), ScaleData.parse("<TableScale><Version>1</Version></TableScale>"), TuneFile.parse(text))

    private fun cells(t: Tune, id: Int = 11) = t.rows(id)!![0].toList()

    @Test
    fun rebaseMovesTheBaselineAndKeepsTheInstance() {
        val t = open()
        t.setRaw(11, Cell(0, 0), 11)
        assertEquals(listOf(11), t.modifiedTables)
        val saved = TuneFile.parse(source(11, 20, 30, 40, 50))
        t.rebase(saved)
        assertSame(saved, t.file)
        assertFalse(t.hasChanges)
        assertEquals(listOf(11L, 20L, 30L, 40L, 50L), cells(t))
        // 存檔前的那一步還在：復原回到 10，算一個沒存的修改
        assertEquals(11, t.undo())
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), cells(t))
        assertTrue(t.hasChanges)
    }

    @Test
    fun revertAfterRebaseGoesToTheSavedValue() {
        val t = open()
        t.setRaw(11, Cell(0, 0), 11)
        t.rebase(TuneFile.parse(source(11, 20, 30, 40, 50)))
        t.setRaw(11, Cell(0, 0), 12)
        assertTrue(t.isModified(11))
        t.revert(11)
        assertEquals(11L, cells(t)[0], "復原要回存檔後的 11，不是檔案原本的 10")
        assertFalse(t.isModified(11))
    }

    @Test
    fun undoStepsAcrossSeveralSavesStillWalkBack() {
        val t = open()
        t.setRaw(11, Cell(0, 0), 11)
        t.setRaw(11, Cell(0, 0), 12)
        t.rebase(TuneFile.parse(source(12, 20, 30, 40, 50)))
        assertEquals(11, t.undo())
        assertEquals(11L, cells(t)[0])
        assertEquals(11, t.undo())
        assertEquals(10L, cells(t)[0])
        assertTrue(t.hasChanges)
    }

    @Test
    fun rebaseDropsTheWorkingCopyWithTheSameContent() {
        val t = open()
        t.setRaw(11, Cell(0, 0), 11)
        t.rebase(TuneFile.parse(source(11, 20, 30, 40, 50)))
        assertEquals(emptyList(), t.modifiedTables)
        // 存檔後再改同一個值：不再算改過
        assertEquals(0, (t.setRaw(11, Cell(0, 0), 11) as EditResult.Done).changed)
        assertFalse(t.isModified(11))
    }
}
