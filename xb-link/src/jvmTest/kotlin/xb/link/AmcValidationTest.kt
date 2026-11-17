package xb.link

import xb.tune.Definitions
import xb.tune.ScaleData
import xb.tune.Tune
import xb.tune.TuneFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AMC 打包前的驗證：檔頭與識別表、ControlFile 的表序、重複／缺少／多出來的表、寬度與總長度。
 * 全部用人工寫的小定義檔與小調校檔，不含官方資料。
 */
class AmcValidationTest {
    private fun tinf(id: Int, x: Int, y: Int, cell: Int, max: String = "255", group: String = "G") =
        "$id,$x,$y,$cell,0,0,0,0,$max,0,1,1,0,_,u,$group,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_"

    private fun tab(
        name: String,
        inf: String,
        version: Int = 17,
        status: String = "Enable",
    ) = """<Tab name="$name" absid="${inf.substringBefore(',')}"><Tinf>$inf</Tinf>
<Ver c_version="$version" status="$status"><Model name="A" mid="10">1</Model></Ver>
</Tab>"""

    private fun definitions(dataWidth: Int = 2, dataVersion: Int = 17, extra: String = "") = """<ECUControlFile><ControlFileSerial sn="1"/><Parameters>
${tab("TABLE_ID", tinf(1001, 0, 0, 4, max = "4294901760", group = "Others"))}
${tab("V_CODE", tinf(1002, 0, 0, 2, max = "0", group = "Others"))}
${tab("A_CODE", tinf(1003, 0, 0, 2, max = "0", group = "Others"))}
${tab("C_CODE", tinf(1016, 0, 0, 2, max = "0", group = "Others"))}
${tab("Map", tinf(1, 2, 2, 1), version = 17)}
${tab("Scale", tinf(2, 0, 0, dataWidth, max = "65535"), version = dataVersion)}
${tab("Eeprom", tinf(3, 0, 0, 1, group = "EEPROM"), version = 17)}
${tab("Off", tinf(4, 0, 0, 1), version = 17, status = "NA")}
$extra
</Parameters></ECUControlFile>"""

    private fun table(id: Int, name: String, text: String) =
        """<Table name="$name" absid="$id">
$text
</Table>"""

    private fun defaultTables(): List<Pair<Int, String>> = listOf(
        1 to table(1, "Map", "1,2\n3,4"),
        2 to table(2, "Scale", "258"),
        3 to table(3, "Eeprom", "7"),
        4 to table(4, "Off", "0"),
        1001 to table(1001, "TABLE_ID", "85068289"),
        1002 to table(1002, "V_CODE", "1"),
        1003 to table(1003, "A_CODE", "2"),
        1016 to table(1016, "C_CODE", "17"),
    )

    private fun tuneXml(
        headerV: String = "1",
        headerC: String = "17",
        headerA: String = "2",
        headerId: String = "05_18_10_01",
        tables: List<Pair<Int, String>> = defaultTables(),
    ) = """<?xml version="1.0" encoding="utf-8"?>
<AMC_File>
<MAP_Type>AMX</MAP_Type>
<V_Code>$headerV</V_Code>
<C_Code>$headerC</C_Code>
<A_Code>$headerA</A_Code>
<Table_ID>$headerId</Table_ID>
<LastSaveTime>2026-01-01 00:00:00</LastSaveTime>
<CarNote/>
<IO_Config/>
<TableValue>
${tables.joinToString("\n") { it.second }}
</TableValue>
</AMC_File>"""

    private fun tune(
        defs: String = definitions(),
        xml: String = tuneXml(),
    ): Tune = Tune(
        Definitions.parse(defs),
        ScaleData.parse("<TableScale/>"),
        TuneFile.parse(xml),
    )

    @Test
    fun packBuildsTheControlFileOrder() {
        val p = Amc.pack(tune())

        // 表號順序：1、2、1001、1002、1003、1016；被跳過的是 EEPROM 3 與狀態 NA 的 4。
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 2, 1, 1, 10, 18, 5, 1, 0, 2, 0, 17, 0),
            p.data,
        )
        assertEquals(16, p.data.size)
        assertEquals(17, p.cCode)
        assertEquals(6, p.tables)
        assertEquals(listOf(3, 4), p.skipped)
        assertEquals(1, p.blocks)
        assertEquals(1024, p.padded.size)
        val expectedChecksum = p.padded.sumOf { it.toInt() and 0xFF } and 0xFFFF
        assertEquals(expectedChecksum, p.checksum)
        assertContentEquals(
            byteArrayOf(0x30, 0, 17, 0, 16, (expectedChecksum ushr 8).toByte(), expectedChecksum.toByte()),
            Amc.requestFrame(p).data,
        )
    }

    @Test
    fun overridesReplaceOnlyPackedTables() {
        val p = Amc.pack(tune(), mapOf(1 to listOf(longArrayOf(9, 8), longArrayOf(7, 6))))
        assertContentEquals(byteArrayOf(9, 8, 7, 6), p.data.copyOfRange(0, 4))
        val e = assertFailsWith<Amc.AmcException> {
            Amc.pack(tune(), mapOf(3 to listOf(longArrayOf(1))))
        }
        assertTrue(e.message!!.contains("not in the C17 AMC layout"), e.message)
    }

    @Test
    fun missingTableIsRejected() {
        val tables = defaultTables().filterNot { it.first == 2 }
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(tables = tables))) }
        assertTrue(e.message!!.contains("missing table 2"), e.message)
    }

    @Test
    fun duplicateTableIsRejected() {
        val tables = defaultTables() + (2 to table(2, "Scale", "258"))
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(tables = tables))) }
        assertTrue(e.message!!.contains("Duplicate tables"), e.message)
    }

    @Test
    fun unexpectedTableIsRejected() {
        val tables = defaultTables() + (99 to table(99, "Unknown", "1"))
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(tables = tables))) }
        assertTrue(e.message!!.contains("Table 99") && e.message!!.contains("not in the definitions"), e.message)
    }

    @Test
    fun wrongHeaderCIsRejected() {
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(headerC = "16"))) }
        assertTrue(e.message!!.contains("header says C16") && e.message!!.contains("C17"), e.message)
    }

    @Test
    fun wrongHeaderVIsRejected() {
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(headerV = "2"))) }
        assertTrue(e.message!!.contains("header says V2") && e.message!!.contains("V1"), e.message)
    }

    @Test
    fun wrongHeaderTableIdIsRejected() {
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(headerId = "05_18_10_02"))) }
        assertTrue(e.message!!.contains("Table ID"), e.message)
    }

    @Test
    fun missingControlFileVersionIsRejected() {
        val e = assertFailsWith<Amc.AmcException> {
            Amc.pack(tune(defs = definitions(dataVersion = 16)))
        }
        assertTrue(e.message!!.contains("no C17 entry"), e.message)
    }

    @Test
    fun unsupportedWidthIsRejected() {
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(defs = definitions(dataWidth = 3))) }
        assertTrue(e.message!!.contains("unsupported width 3"), e.message)
    }

    @Test
    fun wrongRowShapeIsRejected() {
        val tables = defaultTables().map { (id, text) ->
            id to if (id == 1) table(1, "Map", "1,2\n3") else text
        }
        val e = assertFailsWith<Amc.AmcException> { Amc.pack(tune(xml = tuneXml(tables = tables))) }
        assertTrue(e.message!!.contains("row 1 has 1 cells, expected 2"), e.message)
    }

    @Test
    fun oversizedContentIsRejected() {
        val huge = (0 until 256).joinToString("\n") { (0 until 256).joinToString(",") { "0" } }
        val tables = defaultTables() + (5 to table(5, "Huge", huge))
        val e = assertFailsWith<Amc.AmcException> {
            Amc.pack(tune(defs = definitions(extra = tab("Huge", tinf(5, 256, 256, 1))), xml = tuneXml(tables = tables)))
        }
        assertTrue(e.message!!.contains("Bad content length"), e.message)
    }

    @Test
    fun packedContentIsImmutable() {
        val p = Amc.pack(tune())
        val data = p.data
        data[0] = 99
        assertEquals(1.toByte(), p.data[0])
        val padded = p.padded
        padded[0] = 99
        assertEquals(1.toByte(), p.padded[0])
    }
    @Test
    fun identityOverridesCannotChangeTheValidatedHeader() {
        val t = tune()
        assertFailsWith<Amc.AmcException> { Amc.pack(t, mapOf(1016 to listOf(longArrayOf(18)))) }
        assertFailsWith<Amc.AmcException> { Amc.pack(t, mapOf(1002 to listOf(longArrayOf(2)))) }
        assertFailsWith<Amc.AmcException> { Amc.pack(t, mapOf(1001 to listOf(longArrayOf(1)))) }
    }

}
