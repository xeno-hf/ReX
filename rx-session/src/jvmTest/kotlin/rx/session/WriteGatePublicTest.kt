package rx.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rx.protocol.CanId
import rx.protocol.Frame

class WriteGatePublicTest {
    @Test fun rejectsCalibrationPasswordAndFirmwareWrites() {
        val gate = WriteGate(1)
        // A calibration address must remain blocked for every expedited command variant.
        for (command in listOf(0x23, 0x27, 0x2B, 0x2F)) {
            assertFalse(gate.allows(Frame(0x601, byteArrayOf(command.toByte(), 0x34, 0x12, 0, 1, 0, 0, 0))))
        }
        assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x88.toByte(), 1, 1, 2, 3))))
        assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x3C))))
        assertFalse(gate.allows(Frame(CanId.NMT, byteArrayOf(0x81.toByte(), 0))))
        assertTrue(gate.allows(Frame(CanId.NMT, byteArrayOf(1, 0))))
        assertTrue(gate.allows(Frame(0x601, byteArrayOf(0x40, 8, 0x10, 0, 0, 0, 0, 0))))
    }
}
