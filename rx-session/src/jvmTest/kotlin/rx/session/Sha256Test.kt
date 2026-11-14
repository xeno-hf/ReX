package rx.session

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.protocol.ChannelTable
import java.nio.file.Files
import java.security.MessageDigest

class Sha256Test {
    private fun jdk(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun matchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256().hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256().apply { update("abc".encodeToByteArray()) }.hex())
    }

    @Test fun matchesThePlatformDigestAtEveryPaddingBoundaryAndChunking() {
        val rnd = Random(20261114)
        for (len in (0..200) + listOf(1000, 4096, 8191, 8192, 8193, 100_003)) {
            val data = rnd.nextBytes(len)
            val sha = Sha256()
            var i = 0
            while (i < len) { val n = minOf(len - i, 1 + rnd.nextInt(97)); sha.update(data, i, n); i += n }
            assertEquals(jdk(data), sha.hex(), "length $len")
        }
    }

    /** 收檔寫進 meta.json 的雜湊是邊寫邊算的，要跟事後讀檔算出來的一樣。 */
    @Test fun recorderMetaHashesMatchTheFilesOnDisk() {
        val def = ChannelDef("RPM", 26, 2, 1.0, 0.0, 20000.0, 0.0, "Para", "rpm", "Stage_1", 0, emptyMap())
        val layout = DecodedLayout(ChannelRegistry(null, listOf(def)), ChannelTable(listOf(ChannelTable.Entry(26, 2))))
        val dir = Files.createTempDirectory("rex-hash").toFile()
        try {
            val rec = Recorder(kotlinx.io.files.Path(dir.path), layout, emptyMap(), wallClock = { 0L })
            val frame = rx.protocol.Frame(0x181, ByteArray(8) { it.toByte() })
            for (i in 0 until 5000) {
                rec.onFrame(i * 1_000_000L, rx.protocol.RxRaw.DIR_RX, frame)
                rec.onRound(i * 1_000_000L, longArrayOf(1000L + i))
                if (i % 500 == 0) rec.mark("lap $i")
            }
            rec.close()
            val meta = dir.resolve("meta.json").readText()
            for (name in listOf("frames.rxraw", "events.jsonl", "decoded.loga")) {
                val bytes = dir.resolve(name).readBytes()
                assertTrue(bytes.size > 100, name)
                assertTrue("\"$name\":\"${jdk(bytes)}\"" in meta, "$name hash in $meta")
                assertEquals(jdk(bytes), sha256Hex(kotlinx.io.files.Path(dir.resolve(name).path)), name)
            }
            assertTrue("\"rounds\": 5000" in meta && "\"frames\": 5000" in meta, meta)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSecondRecordingInTheSameSecondGetsItsOwnFolder() {
        val root = Files.createTempDirectory("rex-dirs").toFile()
        try {
            val r = kotlinx.io.files.Path(root.path)
            val a = Recorder.newSessionDir(r, "ECU", 1_794_640_000_000L)
            kotlinx.io.files.SystemFileSystem.createDirectories(a)
            val b = Recorder.newSessionDir(r, "ECU", 1_794_640_000_000L)
            kotlinx.io.files.SystemFileSystem.createDirectories(b)
            val c = Recorder.newSessionDir(r, "ECU", 1_794_640_000_000L)
            assertEquals(listOf(a.name, a.name + "_2", a.name + "_3"), listOf(a.name, b.name, c.name))
        } finally { root.deleteRecursively() }
    }
}
