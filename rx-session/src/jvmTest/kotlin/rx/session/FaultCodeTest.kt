package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rx.defs.BuiltinDefs

class FaultCodeTest {
    @Test fun onlyCodeNamedBitsCountAsFaults() {
        for (n in listOf("Malf13.P0031", "Malf13.U0155", "Malf13.C0520", "Malf12.P056C", "BMS_Malf0.P058D_1", "MGU_Malf1.P0A1B"))
            assertTrue(DecodedLayout.isFaultCode(n), n)
        // ECU 自己的狀態旗標、沒有名字的位元、名字裡剛好有 Malf 的模式位元：都不是故障碼
        for (n in listOf("Malf8.Malf_On", "Malf8.MILReq", "Malf8.MalfCodeEnKeyOn_old", "Malf8.ClearEEEnKeyOn_old", "Malf8.MAPSwitching",
            "Malf14.bit7", "BMS_Malf1.Bit3", "MGU_Mode4.MGU_Malf_Short_Gnd", "MGU_Mode1.MalfRPMLimit", "Mode1.P0031", "P0031", "Malf13"))
            assertFalse(DecodedLayout.isFaultCode(n), n)
    }

    /** 那兩個 KeyOn_old 亮著時，故障清單是空的，它們列在狀態旗標。 */
    @Test fun statusFlagsAreListedApartFromFaults() {
        for (key in BuiltinDefs.KEYS) {
            val reg = BuiltinDefs.forKey(key)
            val layout = DecodedLayout(reg, Presets.table(Presets.ALL_CHANNELS.copy(absids = Presets.allAbsids(reg)), reg))
            val raw = LongArray(layout.table.entries.size)
            assertEquals(emptyList(), layout.activeMalfunctions(raw), key)
            assertEquals(emptyList(), layout.activeMalfFlags(raw), key)

            val malf8 = layout.columns.first { it.def.name == "Malf8.MalfCodeEnKeyOn_old" }
            raw[malf8.entryIndex] = 0xFF
            assertEquals(emptyList(), layout.activeMalfunctions(raw), key)
            val flags = layout.activeMalfFlags(raw).map { it.name }
            assertTrue("Malf8.MalfCodeEnKeyOn_old" in flags && "Malf8.ClearEEEnKeyOn_old" in flags && "Malf8.Malf_On" in flags, "$key $flags")

            val code = layout.columns.first { DecodedLayout.isFaultCode(it.def.name) }
            raw.fill(0); raw[code.entryIndex] = 0xFF
            val faults = layout.activeMalfunctions(raw).map { it.name }
            assertTrue(code.def.name in faults, "$key $faults")
            assertTrue(faults.all(DecodedLayout::isFaultCode), "$key $faults")
        }
    }
}
