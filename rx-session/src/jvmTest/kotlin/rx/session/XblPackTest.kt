package rx.session

import kotlinx.io.files.Path
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.protocol.ChannelTable
import rx.protocol.Frame
import rx.protocol.Rpc
import rx.protocol.RxRaw
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic recording only: no real serials, passwords or locations. */
class XblPackTest {
    private fun record(dir: File) {
        val rpm = ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap())
        val layout = DecodedLayout(ChannelRegistry(null, listOf(rpm)), ChannelTable(listOf(ChannelTable.Entry(26, 2))))
        val info = mapOf("ECU SN" to "SYN-ECU-SN", "Table ID" to "05_18_10_01", "rx.node" to "1", "rx.device" to "Synthetic Phone",
            XblPack.INFO_V to "1", XblPack.INFO_C to "17", XblPack.INFO_A to "2")
        val rpc = Rpc(1)
        Recorder(Path(dir.path), layout, info, wallClock = { 1_794_800_000_000L }).apply {
            onFrame(1_000_000, RxRaw.DIR_TX, rpc.login(123456))
            // 串流設定：每次連線都寫，不列進 writes
            onFrame(2_000_000, RxRaw.DIR_TX, rpc.smallWrite(Rpc.MON_ADDR, Rpc.MON_SUB, Rpc.MON_RUN, 1))
            onFrame(3_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x60, 0x00, 0x18, 5, 0, 0, 0, 0)))
            // eBoost 開（表 1230）：先讀到 0x08，再寫 → ACK
            onFrame(8_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0x40, 0xCE.toByte(), 0x34, 0, 0, 0, 0, 0)))
            onFrame(9_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x4F, 0xCE.toByte(), 0x34, 0, 0x08, 0, 0, 0)))
            onFrame(10_000_000, RxRaw.DIR_TX, rpc.smallWrite(Rpc.EBOOST_OPTION, 0, 1, 1))
            onFrame(12_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x60, 0xCE.toByte(), 0x34, 0, 0, 0, 0, 0)))
            // MAP 切換 → 被拒
            onFrame(20_000_000, RxRaw.DIR_TX, rpc.smallWrite(Rpc.MAP_SWITCH, 0, 2, 1))
            onFrame(21_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x80.toByte(), 0x03, 0x20, 0, 0x04, 0x00, 0x01, 0x06)))
            // 表 21 第 0 列寫兩個 byte，沒有回應
            onFrame(30_000_000, RxRaw.DIR_TX, rpc.smallWrite(0x3000 + 21, 0, 0x8077, 2))
            // 表 1 第 2 列區塊寫入 9 bytes：起始 → 兩段（末段 2 bytes＋5 bytes 填充）→ 結束 → ECU 確認，才算寫進去
            onFrame(40_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0xC6.toByte(), 0x01, 0x30, 2, 9, 0, 0, 0)))
            onFrame(41_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0xA4.toByte(), 0x01, 0x30, 2, 127, 0, 0, 0)))
            onFrame(42_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0x01, 1, 2, 3, 4, 5, 6, 7)))
            onFrame(43_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0x82.toByte(), 8, 9, 0, 0, 0, 0, 0)))
            onFrame(44_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0xA2.toByte(), 2, 127, 0, 0, 0, 0, 0)))
            onFrame(45_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0xD5.toByte(), 0, 0, 0, 0, 0, 0, 0)))
            onFrame(46_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0xA1.toByte(), 0, 0, 0, 0, 0, 0, 0)))
            // 表 2 區塊寫入，ECU 中途 abort：記成失敗
            onFrame(50_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0xC6.toByte(), 0x02, 0x30, 0, 9, 0, 0, 0)))
            onFrame(51_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0xA4.toByte(), 0x02, 0x30, 0, 127, 0, 0, 0)))
            onFrame(52_000_000, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0x01, 1, 2, 3, 4, 5, 6, 7)))
            onFrame(53_000_000, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x80.toByte(), 0x02, 0x30, 0, 0x04, 0x00, 0x01, 0x06)))
            repeat(400) { onRound(1_000_000L + it * 63_000_000L, longArrayOf(1500L + it)) }
            mark("SYN mark")
            close()
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun packsRecordingIntoAnXblThatXbCanRead() {
        val root = Files.createTempDirectory("xbl").toFile()
        try {
            val dir = File(root, "ECU_20261116_174800").also { record(it) }
            val out = File(root, "ride.xbl")
            XblPack.pack(Path(dir.path), Path(out.path), Path(root.path, "staging"), ShareExport.Mode.DIAGNOSTIC, "ReX test")
            assertFalse(File(root, "staging").exists(), "staging is cleaned up")

            val b = out.readBytes()
            assertEquals("mimetype", b.decodeToString(30, 38))
            assertEquals(XblPack.MIME, b.decodeToString(38, 38 + XblPack.MIME.length))
            ZipFile(out).use { z ->
                assertEquals(ZipEntry.STORED, z.getEntry("mimetype").method)
                assertEquals(ZipEntry.DEFLATED, z.getEntry("decoded.loga").method)
                val names = z.entries().asSequence().map { it.name }.toList()
                assertEquals(listOf("mimetype", "manifest.json", "meta.json", "frames.rxraw", "decoded.loga", "events.jsonl", "writes.jsonl"), names)
                fun read(n: String) = z.getInputStream(z.getEntry(n)).use { it.readBytes() }
                // 壓縮後解回來跟錄製一模一樣（診斷模式不改內容）
                assertContentEquals(File(dir, "decoded.loga").readBytes(), read("decoded.loga"))
                assertTrue(z.getEntry("decoded.loga").compressedSize < z.getEntry("decoded.loga").size)
                val manifest = JsonLine.parseObject(read("manifest.json").decodeToString(), nested = true)!!
                assertEquals("xbl-1", manifest["format"])
                assertEquals("full", manifest["mode"])
                @Suppress("UNCHECKED_CAST") val vehicle = manifest["vehicle"] as Map<String, Any?>
                assertEquals("Mini X", vehicle["platform"]); assertEquals("AMX", vehicle["map_type"]); assertEquals("05_18_10_01", vehicle["table_id"])
                assertEquals("17", (vehicle["c"] as JsonLine.Num).text)
                @Suppress("UNCHECKED_CAST") val files = manifest["files"] as Map<String, Any?>
                for (n in names.drop(2)) assertEquals(sha(read(n)), files[n], n)
                val writes = read("writes.jsonl").decodeToString().lines().filter { it.isNotBlank() }.map { JsonLine.parseObject(it)!! }
                assertEquals(listOf("table 1230", "MAP switch", "table 21", "table 1", "table 2"), writes.map { it["what"] })
                val results = writes.map { it["result"] as String }
                assertEquals("ack", results[0]); assertTrue(results[1].startsWith("abort 0x06010004"), results[1]); assertEquals("no reply", results[2])
                assertEquals("ack", results[3]); assertEquals("010203040506070809", writes[3]["data"]); assertEquals("block", writes[3]["kind"])
                assertTrue(results[4].startsWith("abort 0x06010004"), results[4])
                assertEquals("01", writes[0]["data"]); assertEquals("08", writes[0]["before"]); assertEquals("7780", writes[2]["data"])
                assertEquals(null, writes[2]["before"])
                assertEquals("9.0", (writes[0]["t_ms"] as JsonLine.Num).text)
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun publicPackDropsIdentityButKeepsTheVehicleModel() {
        val root = Files.createTempDirectory("xbl").toFile()
        try {
            val dir = File(root, "ECU_1").also { record(it) }
            val out = File(root, "ride.xbl")
            XblPack.pack(Path(dir.path), Path(out.path), Path(root.path, "staging"), ShareExport.Mode.PUBLIC, "ReX test")
            ZipFile(out).use { z ->
                fun text(n: String) = z.getInputStream(z.getEntry(n)).use { it.readBytes() }.decodeToString()
                for (e in z.entries()) for (secret in listOf("SYN-ECU-SN", "Synthetic Phone")) assertFalse(text(e.name).contains(secret), "${e.name} still has $secret")
                assertTrue(text("manifest.json").contains("\"mode\":\"public\""))
                assertTrue(text("manifest.json").contains("\"table_id\":\"05_18_10_01\""))
                assertEquals("", text("events.jsonl"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun tableIdTextMatchesTheTuneHeader() {
        assertEquals("05_18_10_01", XblPack.tableIdText(85068289L))
    }
}
