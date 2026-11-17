package xb.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 調校包清單的定義檔序號要跟包裡的 control.xml 一致；合成的最小包，不需要廠商資料。 */
class TunePackSerialTest {
    private val control = """<ECUControlFile><ControlFileSerial sn="SN-A"/><Parameters>
        <Tab name="Synthetic" absid="1"><Tinf>1,0,0,1,0,0,0,0,255,0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_</Tinf><Ver c_version="7" status="En"/></Tab>
        </Parameters></ECUControlFile>"""
    private val template = """<AMC_File><MAP_Type>AMX</MAP_Type><V_Code>1</V_Code><C_Code>7</C_Code><A_Code>1</A_Code><Table_ID>00_00_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue><Table name="Synthetic" absid="1">10</Table></TableValue></AMC_File>"""

    private fun pack(serial: String?) = TunePack(
        buildMap {
            put("format", TunePack.FORMAT); put("platform", "S3"); put("map_type", "AMX"); put("c", "7"); put("table_id", "00_00_10_01")
            if (serial != null) put("serial", serial)
        },
        mapOf(TunePack.CONTROL to control, TunePack.SCALES to "<ScaleData/>", TunePack.TEMPLATE to template),
    )

    @Test fun matchingSerialOpens() { assertEquals("SN-A", pack("SN-A").open().tune.definitions.serial) }
    @Test fun differentSerialIsRefused() { assertFailsWith<TunePack.Invalid> { pack("SN-B").open() } }
    @Test fun missingSerialIsRefused() { assertFailsWith<TunePack.Invalid> { pack(null).open() } }
}
