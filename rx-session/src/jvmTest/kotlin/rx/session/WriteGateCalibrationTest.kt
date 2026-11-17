package rx.session

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rx.protocol.CanId
import rx.protocol.Frame
import rx.protocol.SdoDownload

/** 調校模式（ReX 1.0 即時調校）：只准寫定義包裡的表，寫法只有 expedited 與區塊寫入；其他照舊擋。 */
class WriteGateCalibrationTest {
    private fun f(vararg b: Int) = Frame(0x601, ByteArray(8).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } })

    @Test fun offByDefault() {
        val gate = WriteGate(1)
        assertFalse(gate.allows(f(0x2B, 0x01, 0x30, 4, 1, 0)))          // 0x3001/4 expedited
        assertFalse(gate.allows(f(0xC6, 0x01, 0x30, 4, 52, 0, 0, 0)))  // 區塊寫入起始
    }

    @Test fun onlyListedObjects() {
        val gate = WriteGate(1, calibration = setOf(0x3001, 0x34B2))
        assertTrue(gate.allows(f(0x2B, 0x01, 0x30, 4, 1, 0)))
        assertTrue(gate.allows(f(0x2F, 0xB2, 0x34, 0, 11)))
        assertFalse(gate.allows(f(0x2B, 0x02, 0x30, 0, 1, 0)))         // 不在清單
        assertTrue(gate.allows(f(0xC6, 0x01, 0x30, 4, 52, 0, 0, 0)))
        assertFalse(gate.allows(f(0xC6, 0x02, 0x30, 4, 52, 0, 0, 0)))
        // 調校模式也不放：改密碼、刷韌體、NMT Stop、其他區塊寫入命令字
        assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x88.toByte(), 1, 1, 2, 3))))
        assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x3C))))
        assertFalse(gate.allows(Frame(CanId.NMT, byteArrayOf(0x02, 0))))
        assertFalse(gate.allows(f(0xC2, 0x01, 0x30, 4, 52, 0, 0, 0)))
        // 沒有起始幀，分段一律不放
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))
        assertFalse(gate.allows(f(0xC1, 0, 0)))
    }

    @Test fun blockDownloadSequence() {
        val gate = WriteGate(1, calibration = setOf(0x3001))
        val data = ByteArray(52) { it.toByte() }
        val dl = SdoDownload(0x601, 0x3001, 4, data)
        val start = dl.start(); gate.check(start)
        val step = dl.onReply(Frame(0x581, byteArrayOf(0xA0.toByte(), 0x01, 0x30, 4, 127, 0, 0, 0))) as SdoDownload.Step.Send
        step.frames.forEach { gate.check(it) }                        // 分段（含 bit7 末段）
        assertFailsWith<WriteGate.Refused> { gate.check(f(0x09, 1, 2, 3, 4, 5, 6, 7)) } // 末段之後不准再送分段
        val end = dl.onReply(Frame(0x581, byteArrayOf(0xA2.toByte(), step.frames.size.toByte(), 127, 0, 0, 0, 0, 0))) as SdoDownload.Step.Send
        end.frames.forEach { gate.check(it) }
        // 結束幀送出後、ECU 最後確認前：什麼都不放（同一物件的 abort 除外）
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))
        assertFalse(gate.allows(start))
        assertTrue(gate.allows(f(0x80, 0x01, 0x30, 4, 0, 0, 4, 5)))
        gate.endBlock()
        // 結束之後分段不放；新的寫入要重新起始
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))
        assertTrue(gate.allows(start))
    }

    @Test fun abortEndsBlock() {
        val gate = WriteGate(1, calibration = setOf(0x3001))
        gate.check(f(0xC6, 0x01, 0x30, 4, 52, 0, 0, 0))
        assertTrue(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))
        gate.check(f(0x80, 0x01, 0x30, 4, 0, 0, 4, 5))
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))
    }

    @Test fun turningOffClearsBlock() {
        val gate = WriteGate(1, calibration = setOf(0x3001))
        gate.check(f(0xC6, 0x01, 0x30, 4, 52, 0, 0, 0))
        gate.calibration = null
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))
        assertFalse(gate.allows(f(0x2B, 0x01, 0x30, 4, 1, 0)))
    }

    @Test fun nothingElseDuringABlockDownload() {
        val gate = WriteGate(1, calibration = setOf(0x3001, 0x3002))
        gate.check(f(0xC6, 0x01, 0x30, 4, 16, 0, 0, 0))               // 16 bytes＝3 段，末段 2 bytes
        assertFalse(gate.allows(f(0x2B, 0x02, 0x30, 0, 1, 0)))         // 准寫的別張表也不行
        assertFalse(gate.allows(f(0x2B, 0x99, 0x30, 0, 1, 0)))
        assertFalse(gate.allows(f(0xC6, 0x02, 0x30, 0, 16, 0, 0, 0)))  // 第二筆區塊寫入
        assertFalse(gate.allows(f(0x40, 0x01, 0x30, 4)))               // 讀
        assertFalse(gate.allows(f(0x80, 0x02, 0x30, 0, 0, 0, 4, 5)))   // 別的物件的 abort
        assertFalse(gate.allows(f(0x02, 1, 2, 3, 4, 5, 6, 7)))         // 序號跳號
        assertFalse(gate.allows(f(0x81, 1, 2, 3, 4, 5, 6, 7)))         // 第 1 段就說是末段
        gate.check(f(0x01, 1, 2, 3, 4, 5, 6, 7))
        gate.check(f(0x02, 1, 2, 3, 4, 5, 6, 7))
        assertFalse(gate.allows(f(0x03, 1, 2, 0, 0, 0, 0, 0)))         // 第 3 段一定是末段
        assertFalse(gate.allows(f(0x83, 1, 2, 3, 0, 0, 0, 0)))         // 末段只有 2 bytes，後面要補 0
        gate.check(f(0x83, 1, 2, 0, 0, 0, 0, 0))
        assertFalse(gate.allows(f(0x04, 1, 2, 3, 4, 5, 6, 7)))         // 末段之後
        assertFalse(gate.allows(f(0xC1 or (4 shl 2), 0x12, 0x34)))     // 空位數不對（應為 5）
        gate.check(f(0xC1 or (5 shl 2), 0x12, 0x34))
        assertFalse(gate.allows(f(0x2B, 0x02, 0x30, 0, 1, 0)))         // 等 ECU 最後確認
        gate.endBlock()
        assertTrue(gate.allows(f(0x2B, 0x02, 0x30, 0, 1, 0)))          // 確認後恢復
    }

    @Test fun nextBlockWaitsForTheEcuAcknowledgement() {
        val gate = WriteGate(1, calibration = setOf(0x3001))
        gate.check(f(0xC6, 0x01, 0x30, 4, 21, 0, 0, 0))               // 21 bytes＝3 段
        gate.check(f(0x01, 1, 2, 3, 4, 5, 6, 7))
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))         // 沒收到區塊確認不能從 1 重來
        gate.blockAcknowledged()
        gate.check(f(0x01, 1, 2, 3, 4, 5, 6, 7))                       // 確認後下一個區塊
        assertFalse(gate.allows(f(0x01, 1, 2, 3, 4, 5, 6, 7)))         // 每個區塊都要各自確認
        gate.check(f(0x82, 1, 2, 3, 4, 5, 6, 7))                       // 第 3 段＝末段
    }

    @Test fun declaredSizeIsBounded() {
        val gate = WriteGate(1, calibration = setOf(0x3001))
        assertFalse(gate.allows(f(0xC6, 0x01, 0x30, 4, 4, 0, 0, 0)))    // 4 bytes 以內要用 expedited
        assertFalse(gate.allows(f(0xC6, 0x01, 0x30, 4, 0, 0, 1, 0)))    // 64 KiB
        assertTrue(gate.allows(f(0xC6, 0x01, 0x30, 4, 0, 16, 0, 0)))    // 4096
    }
}
