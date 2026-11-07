package rx.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SdoTest {
    private fun fr(id: Int, vararg b: Int) = Frame(id, ByteArray(b.size) { b[it].toByte() })

    @Test
    fun realAbortsDecode() {
        // 小讀回應
        assertEquals(0x08000000L, Sdo.parseAbort(fr(0x581, 0x80, 0, 0, 0, 0, 0, 0, 0x08))!!.code)
        // 0x0000 區塊讀回應：物件不存在
        val a = Sdo.parseAbort(fr(0x581, 0x80, 0, 0, 0, 0, 0, 0x02, 0x06))!!
        assertEquals(0x06020000L, a.code); assertEquals("object does not exist", Sdo.abortText(a.code))
        assertIs<RpcReply.Nack>(RpcReply.classify(fr(0x581, 0x80, 0, 0, 0, 0, 0, 0, 0x08)))
    }

    @Test
    fun xtuneCommandBytes() {
        // c.b(v)<<5：小讀 0x40、區塊讀 0xA4（blksize 0x7F）
        val up = SdoUpload(0x601, 0x1008, 0)
        assertEquals(listOf(0xA4, 0x08, 0x10, 0x00, 0x7F, 0, 0, 0), up.start().data.map { it.toInt() and 0xFF })
        assertEquals(0x40, SdoUpload(0x601, 0x1008, 0, block = false).start().u8(0))
        assertEquals(0x23, Rpc(1).smallWrite(0x1800, 5, 64, 4, flags = 1).u8(0)) // 小寫 flags=1 → 0x2X
    }

    @Test
    fun realBlockInitThenFullTransfer() {
        val up = SdoUpload(0x601, 0x1008, 0)
        up.start()
        // 實車：`c2 08 10 00 0a …` ＝ block upload init，s=1、size=10、無 CRC
        val s1 = up.onReply(fr(0x581, 0xC2, 0x08, 0x10, 0x00, 0x0A, 0, 0, 0))
        assertEquals(0xA3, (s1 as SdoUpload.Step.Send).frames.single().u8(0))
        assertIs<SdoUpload.Step.Wait>(up.onReply(fr(0x581, 0x01, 'a'.code, 'R'.code, 'a'.code, 'c'.code, 'e'.code, 'r'.code, ' '.code)))
        val ack = up.onReply(fr(0x581, 0x82, 'M'.code, 'X'.code, 0, 0, 0, 0, 0)) as SdoUpload.Step.Send
        assertEquals(listOf(0xA2, 2, 0x7F), ack.frames.single().data.take(3).map { it.toInt() and 0xFF })
        val done = up.onReply(fr(0x581, 0xC1 or (4 shl 2), 0, 0, 0, 0, 0, 0, 0)) // 末片 4 bytes 無效
        assertEquals("aRacer MX\u0000".substring(0, 9), String((done as SdoUpload.Step.Done).data).substring(0, 9))
        assertEquals(10, done.data.size)
        assertEquals(0xA1, up.pendingEnd!!.u8(0))
    }

    @Test
    fun expeditedAndCrc() {
        val e = SdoUpload(0x601, 0x1018, 1, block = false)
        val d = e.onReply(fr(0x581, 0x43, 0x18, 0x10, 0x01, 0x78, 0x56, 0x34, 0x12)) as SdoUpload.Step.Done
        assertTrue(d.data.contentEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12)))
        assertEquals(0x31C3, Sdo.crc16("123456789".toByteArray())) // CRC-16/XMODEM 標準檢查值
    }
}
