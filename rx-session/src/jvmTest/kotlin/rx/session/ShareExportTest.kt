package rx.session

import kotlinx.io.files.Path
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.protocol.ChannelTable
import rx.protocol.Frame
import rx.protocol.Rpc
import rx.protocol.RxRaw
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic identities only: no real passwords, serials or locations. */
class ShareExportTest {
    private val password = 123456
    private val serial = "SYN-SERIAL-77".encodeToByteArray()

    private fun record(dir: File) {
        val rpm = ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap())
        val layout = DecodedLayout(ChannelRegistry(null, listOf(rpm)), ChannelTable(listOf(ChannelTable.Entry(26, 2))))
        val info = mapOf("ECU SN" to "SYN-ECU-SN", "Hardware ID" to "SYN-HWID", "rx.device" to "Synthetic Phone", "rx.target" to "Wifi(SYN-AP)", "rx.node" to "1")
        Recorder(Path(dir.path), layout, info, wallClock = { 0L }, extras = listOf(
            Recorder.ExtraColumn(-9002, "Phone_GPS", "Phone_GPS_Speed", 1) { 42.5 },
            Recorder.ExtraColumn(-9000, "Phone_GPS", "Phone_GPS_Latitude", 6) { 25.123456 },
            Recorder.ExtraColumn(-9001, "Phone_GPS", "Phone_GPS_Longitude", 6) { 121.654321 },
        )).apply {
            onFrame(1, RxRaw.DIR_TX, Rpc().login(password))
            onFrame(2, RxRaw.DIR_RX, Frame(0x6A2, byteArrayOf(0xD2.toByte()) + serial.copyOfRange(0, 7)))
            onFrame(3, RxRaw.DIR_RX, Frame(0x6A2, byteArrayOf(0xC2.toByte(), 0x11, 0x22, 0x33, 0x44)))
            // Expedited upload of 0x1018/4 (serial number) and its reply.
            onFrame(4, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0x40, 0x18, 0x10, 4, 0, 0, 0, 0)))
            onFrame(5, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x43, 0x18, 0x10, 4, 0x55, 0x66, 0x77, 0x08)))
            // A table read afterwards keeps its data.
            onFrame(6, RxRaw.DIR_TX, Frame(0x601, byteArrayOf(0x40, 0x01, 0x30, 0, 0, 0, 0, 0)))
            onFrame(7, RxRaw.DIR_RX, Frame(0x581, byteArrayOf(0x43, 0x01, 0x30, 0, 0x01, 0x02, 0x03, 0x04)))
            onFrame(8, RxRaw.DIR_RX, Frame(0x6A2, byteArrayOf(0xB2.toByte(), 1,2,3,4,5,6,7)))
            onFrame(9, RxRaw.DIR_RX, Frame(0x6A2, byteArrayOf(0xB3.toByte(), 8)))
            onEvent(8, "SYN-AP SYN-ECU-SN private note")
            onEvent(8, "Handshake reply 3 frames: 6a2:d253594e2d534552 | 6a2:c211223344")
            onRound(1_000_000, longArrayOf(1500))
            onRound(63_000_000, longArrayOf(1600))
            close()
        }
        File(dir, "notes.txt").writeText("SYN-ECU-SN private note")
    }

    private fun frames(f: File): List<Pair<Int, ByteArray>> {
        val b = f.readBytes()
        return (RxRaw.MAGIC.size until b.size step RxRaw.RECORD_LEN).map {
            b[it + 8].toInt() to b.copyOfRange(it + 10, it + 10 + Frame.FRAME_LEN)
        }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun recordingNeverStoresTheLoginPassword() {
        val dir = Files.createTempDirectory("rex-share").toFile()
        try {
            record(dir)
            val login = frames(File(dir, "frames.rxraw"))[0].second
            assertFalse(login.contentEquals(Rpc().login(password).encode()))
            assertEquals(0x88, login[10].toInt() and 0xFF)
            assertContentEquals(byteArrayOf(0, 0, 0), login.copyOfRange(12, 15))
        } finally { dir.deleteRecursively() }
    }

    @Test fun publicShareRemovesIdentityLocationAndSecrets() {
        val src = Files.createTempDirectory("rex-share-src").toFile()
        val dst = Files.createTempDirectory("rex-share-dst").toFile().also { it.delete() }
        try {
            record(src)
            val before = src.listFiles()!!.associate { it.name to it.readBytes() }
            ShareExport.copySession(Path(src.path), Path(dst.path), ShareExport.Mode.PUBLIC)

            // Originals untouched; unknown files not exported.
            assertEquals(before.keys, src.listFiles()!!.map { it.name }.toSet())
            before.forEach { (n, b) -> assertContentEquals(b, File(src, n).readBytes(), n) }
            assertEquals(setOf("frames.rxraw", "events.jsonl", "decoded.loga", "meta.json"), dst.listFiles()!!.map { it.name }.toSet())

            val all = dst.listFiles()!!.joinToString("\n") { it.readText(Charsets.ISO_8859_1) }
            for (secret in listOf("SYN-ECU-SN", "SYN-HWID", "Synthetic Phone", "SYN-AP", "SYN-SER", "d253594e", "c211223344", "25.123456", "121.654321"))
                assertFalse(secret in all, "leaked $secret")

            val fr = frames(File(dst, "frames.rxraw"))
            for ((_, f) in fr) assertEquals(Frame.checksum(f, 2, Frame.FRAME_LEN - 1), f[Frame.FRAME_LEN - 1].toInt() and 0xFF)
            assertTrue(fr[1].second.copyOfRange(11, 18).all { it == 0.toByte() })
            assertTrue(fr[4].second.copyOfRange(11, 18).all { it == 0.toByte() })
            assertTrue(fr[7].second.copyOfRange(11, 18).all { it == 0.toByte() })
            assertEquals(0, fr[8].second[11].toInt())
            assertContentEquals(byteArrayOf(0x43, 0x01, 0x30, 0, 0x01, 0x02, 0x03, 0x04), fr[6].second.copyOfRange(10, 18))

            val loga = Loga.parse(File(dst, "decoded.loga").readBytes())
            assertEquals("", loga.header["ECU SN"])
            assertEquals(listOf(1500.0, 1600.0), loga.column("RPM").toList())
            assertEquals(listOf(42.5, 42.5), loga.column("Phone_GPS_Speed").toList())
            assertEquals(listOf(0.0, 0.0), loga.column("Phone_GPS_Latitude").toList())

            val meta = File(dst, "meta.json").readText()
            assertTrue("\"share_sanitized\": \"public\"" in meta)
            assertTrue("\"info\":{}" in meta)
            for (n in listOf("frames.rxraw", "events.jsonl", "decoded.loga")) assertTrue("\"$n\":\"${sha(File(dst, n))}\"" in meta, n)
        } finally { src.deleteRecursively(); dst.deleteRecursively() }
    }

    @Test fun diagnosticShareKeepsIdentityButNotThePassword() {
        val src = Files.createTempDirectory("rex-diag-src").toFile()
        val dst = Files.createTempDirectory("rex-diag-dst").toFile().also { it.delete() }
        try {
            record(src)
            ShareExport.copySession(Path(src.path), Path(dst.path), ShareExport.Mode.DIAGNOSTIC)
            assertEquals("SYN-ECU-SN", Loga.parse(File(dst, "decoded.loga").readBytes()).header["ECU SN"])
            assertTrue("25.123456" in File(dst, "decoded.loga").readText())
            val login = frames(File(dst, "frames.rxraw"))[0].second
            assertContentEquals(byteArrayOf(0, 0, 0), login.copyOfRange(12, 15))
            assertTrue("\"share_sanitized\": \"diagnostic\"" in File(dst, "meta.json").readText())
        } finally { src.deleteRecursively(); dst.deleteRecursively() }
    }

    @Test fun legacyRecordingWithRawPasswordIsCleanedOnShare() {
        val b = RxRaw.MAGIC + RxRaw.encodeRecord(1, RxRaw.DIR_TX, Rpc().login(password))
        val out = ShareExport.rxraw(b, ShareExport.Mode.DIAGNOSTIC)
        val f = out.copyOfRange(RxRaw.MAGIC.size + 10, RxRaw.MAGIC.size + 10 + Frame.FRAME_LEN)
        assertContentEquals(byteArrayOf(0, 0, 0), f.copyOfRange(12, 15))
        assertEquals(Frame.checksum(f, 2, Frame.FRAME_LEN - 1), f[Frame.FRAME_LEN - 1].toInt() and 0xFF)
    }
}
