package rx.session

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ZipStoreTest {
    @Test fun zipIsReadableByStandardToolsAndByOurImporter() {
        val root = Files.createTempDirectory("rex-zip").toFile()
        try {
            val dir = File(root, "ECU_20260101_000000").also { it.mkdirs() }
            // 大於一個讀取塊（64 KiB）、不是塊長整數倍，再加空檔與子資料夾
            val files = mapOf(
                "frames.rxraw" to Random(1).nextBytes(200_003),
                "decoded.loga" to "<aRacer MX APP Log File>\nTime,RPM\n".toByteArray(),
                "events.jsonl" to ByteArray(0),
                "sub/note.txt" to "x".toByteArray(),
            )
            files.forEach { (n, b) -> File(dir, n).also { it.parentFile.mkdirs() }.writeBytes(b) }
            val zip = File(root, "out/share.zip")
            ZipStore.zipDir(Path(dir.path), Path(zip.path))

            ZipFile(zip).use { z ->
                assertEquals(files.keys.map { "${dir.name}/$it" }.toSet(), z.entries().toList().map { it.name }.toSet())
                files.forEach { (n, b) -> assertContentEquals(b, z.getInputStream(z.getEntry("${dir.name}/$n")).readBytes(), n) }
            }
            val (name, data) = unzipImport(zip.readBytes()) { it.endsWith(".loga") }!!
            assertEquals("${dir.name}/decoded.loga", name)
            assertContentEquals(files.getValue("decoded.loga"), data)
        } finally { root.deleteRecursively() }
    }
}
