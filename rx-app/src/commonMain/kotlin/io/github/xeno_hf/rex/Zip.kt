package io.github.xeno_hf.rex

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write

/** 只存不壓的 ZIP（分享整個錄製資料夾用；錄製檔本身就是文字＋二進位，壓縮省不了多少，換來兩個平台同一份程式）。 */
object ZipStore {
    fun zipDir(dir: Path, out: Path) {
        val files = Fs.walkFiles(dir).sortedBy { it.toString() }
        val base = dir.toString().length + 1
        val central = ArrayList<ByteArray>()
        var offset = 0L
        out.parent?.let(Fs::mkdirs)
        SystemFileSystem.sink(out).buffered().use { s ->
            for (f in files) {
                val data = Fs.readBytes(f)
                val name = (dir.name + "/" + f.toString().substring(base).replace('\\', '/')).encodeToByteArray()
                val crc = crc32(data)
                val local = header(0x04034b50, name, crc, data.size, central = false, offset = 0)
                s.write(local); s.write(name); s.write(data)
                central += header(0x02014b50, name, crc, data.size, central = true, offset = offset) + name
                offset += local.size + name.size + data.size
            }
            val cdSize = central.sumOf { it.size }
            central.forEach { s.write(it) }
            s.write(le32(0x06054b50) + le16(0) + le16(0) + le16(files.size) + le16(files.size) + le32(cdSize) + le32(offset.toInt()) + le16(0))
        }
    }

    private fun header(sig: Int, name: ByteArray, crc: Int, size: Int, central: Boolean, offset: Long): ByteArray {
        val common = le16(20) + le16(0x0800) + le16(0) + le16(0) + le16(0x21) + le32(crc) + le32(size) + le32(size) + le16(name.size) + le16(0)
        return if (!central) le32(sig) + common
        else le32(sig) + le16(20) + common + le16(0) + le16(0) + le16(0) + le32(0) + le32(offset.toInt())
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private val TABLE = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }
    fun crc32(b: ByteArray): Int { var c = -1; for (x in b) c = TABLE[(c xor x.toInt()) and 0xFF] xor (c ushr 8); return c.inv() }
}
