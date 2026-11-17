package xb.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClearLearnTest {
    @Test fun onlyTheOfficialClearLearnFramePasses() {
        // XTune 送的就是這一幀：2F 09 20 00 01 00 00 00
        val f = CalCommands.sdoFrame(0x2009, 0, 1, 1)
        assertEquals("2f09200001000000", f.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        assertTrue(CalCommands.frameAllowed(f))
        assertTrue(CalCommands.writeAllowed(0x2009, 0, 1, 1))
        assertFalse(CalCommands.writeAllowed(0x2009, 0, 0, 1))
        assertFalse(CalCommands.writeAllowed(0x2009, 0, 2, 1))
        assertFalse(CalCommands.writeAllowed(0x2009, 1, 1, 1))
        assertFalse(CalCommands.writeAllowed(0x2008, 0, 1, 1))
        // 只准官方的 1 byte（2F）：2B／27／23 的同一個值都不放行
        for (bytes in 2..4) {
            assertFalse(CalCommands.writeAllowed(0x2009, 0, 1, bytes))
            assertFalse(CalCommands.frameAllowed(CalCommands.sdoFrame(0x2009, 0, 1, bytes)))
        }
        // 別的命令照舊可用其他寬度（噴嘴校正宣告 2 bytes）
        assertTrue(CalCommands.writeAllowed(0x2000, 4, 0x14001, 2))
    }

    @Test fun menuOnlyOnConfirmedModels() {
        for (n in listOf(1, 2, 33, 34)) {
            val it = CalItems.items(n).single { it.key == "CalItem_ClearLearn" }
            assertEquals(CalItems.Page.ClearLearn, it.page)
            assertEquals(listOf(79, 80), it.tables)
        }
        assertTrue(CalItems.items(37).none { it.key == "CalItem_ClearLearn" })
    }
}
