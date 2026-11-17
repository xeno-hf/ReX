package rx.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 照 CiA 301 寫的離線重播（只收 SDO 寫入），用來驗 [SdoDownload] 的幀。 */
private class FakeServer(val node: Int, val blkSize: Int = 127, val crc: Boolean = false, val abort: Long? = null) {
    val written = HashMap<Pair<Int, Int>, ByteArray>()
    private var idx = 0; private var sub = 0; private var size = 0
    private val buf = ArrayList<Byte>()
    private var lastSeg = ByteArray(0)
    private var seqInBlock = 0
    var blocks = 0
    var crcChecked = false

    private fun r(vararg b: Int) = Frame(0x580 or node, ByteArray(8).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } })

    fun onFrame(f: Frame): Frame? {
        val b0 = f.u8(0)
        if (b0 and 0xE0 == 0x20) { // expedited
            idx = f.u8(1) or (f.u8(2) shl 8); sub = f.u8(3)
            abort?.let { return Sdo.abortFrame(0x580 or node, idx, sub, it) }
            val n = 4 - ((b0 shr 2) and 3)
            written[idx to sub] = f.data.copyOfRange(4, 4 + n)
            return r(0x60, idx and 0xFF, idx ushr 8, sub)
        }
        if (b0 == 0xC6) {
            idx = f.u8(1) or (f.u8(2) shl 8); sub = f.u8(3)
            size = f.u8(4) or (f.u8(5) shl 8) or (f.u8(6) shl 16) or (f.u8(7) shl 24)
            abort?.let { return Sdo.abortFrame(0x580 or node, idx, sub, it) }
            buf.clear(); seqInBlock = 0; blocks = 1
            return r(0xA0 or (if (crc) 4 else 0), idx and 0xFF, idx ushr 8, sub, blkSize)
        }
        if (b0 and 0xE3 == 0xC1) { // end
            val n = (b0 shr 2) and 7
            buf.addAll(lastSeg.copyOf(7 - n).toList())
            val data = buf.toByteArray()
            assertEquals(size, data.size)
            if (crc) { assertEquals(Sdo.crc16(data), f.u8(1) or (f.u8(2) shl 8)); crcChecked = true }
            written[idx to sub] = data
            return r(0xA1)
        }
        // 分段
        val seq = b0 and 0x7F
        seqInBlock++
        assertEquals(seqInBlock, seq)
        if (lastSeg.isNotEmpty()) buf.addAll(lastSeg.toList())
        lastSeg = f.data.copyOfRange(1, 8)
        if (b0 and 0x80 != 0 || seq == blkSize) {
            val ack = r(0xA2, seq, blkSize)
            if (b0 and 0x80 == 0) { seqInBlock = 0; blocks++ }
            return ack
        }
        return null
    }
}

private fun run(dl: SdoDownload, s: FakeServer): SdoDownload.Step {
    var pending = listOf(dl.start())
    repeat(10_000) {
        val out = ArrayList<Frame>()
        for (f in pending) s.onFrame(f)?.let { reply ->
            when (val st = dl.onReply(reply)) {
                is SdoDownload.Step.Send -> out += st.frames
                SdoDownload.Step.Wait -> Unit
                else -> return st
            }
        }
        pending = out
        if (pending.isEmpty()) return SdoDownload.Step.Wait
    }
    error("no end")
}

class SdoDownloadTest {
    @Test fun expeditedSmallWrite() {
        val s = FakeServer(1)
        val dl = SdoDownload(0x601, 0x34CE, 0, byteArrayOf(5, 6))
        assertTrue(dl.expedited)
        assertEquals(0x2B, dl.start().u8(0)) // 2 bytes：0x23 | (4−2)<<2
        assertEquals(SdoDownload.Step.Done, run(dl, s))
        assertContentEquals(byteArrayOf(5, 6), s.written[0x34CE to 0])
    }

    @Test fun blockWriteOfOneRow() {
        // D3Cyl1_VM 一列：56 欄 × 2 bytes＝112 bytes＝16 段（末段剛好滿 7 bytes）
        val row = ByteArray(112) { (it * 7).toByte() }
        val s = FakeServer(0x22)
        val dl = SdoDownload(0x622, 0x3001, 3, row)
        assertEquals(0xC6, dl.start().u8(0))
        assertEquals(SdoDownload.Step.Done, run(dl, s))
        assertContentEquals(row, s.written[0x3001 to 3])
        assertEquals(1, s.blocks)
    }

    @Test fun blockWriteWithPartialLastSegmentAndCrc() {
        val row = ByteArray(72) { (255 - it).toByte() } // 6 欄 × 6 列 × 2 bytes 的一張表整張＝72 bytes，末段 2 bytes
        val s = FakeServer(1, crc = true)
        assertEquals(SdoDownload.Step.Done, run(SdoDownload(0x601, 0x3023, 0, row), s))
        assertContentEquals(row, s.written[0x3023 to 0])
        assertTrue(s.crcChecked)
    }

    @Test fun severalBlocksWhenEcuAsksForSmallBlocks() {
        val data = ByteArray(200) { it.toByte() }
        val s = FakeServer(1, blkSize = 8)
        assertEquals(SdoDownload.Step.Done, run(SdoDownload(0x601, 0x3001, 1, data), s))
        assertContentEquals(data, s.written[0x3001 to 1])
        assertEquals(4, s.blocks) // 29 段，每塊 8 段
    }

    @Test fun abortIsReported() {
        val st = run(SdoDownload(0x601, 0x342B, 0, byteArrayOf(1)), FakeServer(1, abort = 0x06010004L))
        val f = assertIs<SdoDownload.Step.Failed>(st)
        assertEquals(0x06010004L, f.abort?.code)
    }

    @Test fun repliesForOtherObjectsAreIgnored() {
        val dl = SdoDownload(0x601, 0x3001, 1, ByteArray(20))
        dl.start()
        val other = Frame(0x581, byteArrayOf(0xA0.toByte(), 0x02, 0x30, 1, 127, 0, 0, 0))
        assertEquals(SdoDownload.Step.Wait, dl.onReply(other))
        val wrongNode = Frame(0x582, byteArrayOf(0xA0.toByte(), 0x01, 0x30, 1, 127, 0, 0, 0))
        assertEquals(SdoDownload.Step.Wait, dl.onReply(wrongNode))
    }

    @Test fun missingSegmentsFail() {
        val dl = SdoDownload(0x601, 0x3001, 1, ByteArray(30))
        dl.start()
        assertIs<SdoDownload.Step.Send>(dl.onReply(Frame(0x581, byteArrayOf(0xA0.toByte(), 0x01, 0x30, 1, 127, 0, 0, 0))))
        val st = dl.onReply(Frame(0x581, byteArrayOf(0xA2.toByte(), 2, 127, 0, 0, 0, 0, 0)))
        assertIs<SdoDownload.Step.Failed>(st)
    }
}
