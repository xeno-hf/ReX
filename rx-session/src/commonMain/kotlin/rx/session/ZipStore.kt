package rx.session

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * 寫 ZIP。[zipDir]：只存不壓（分享整個錄製資料夾用）；[zip]：逐個成員選存或壓（.xbl 用，壓縮見 [RawDeflate]）。
 * 檔案逐塊讀（先算 CRC、再複製或壓），記憶體用量與檔案大小無關。不支援 ZIP64：單檔或整包超過 2 GiB 就拒絕。
 */
object ZipStore {
    private const val LIMIT = Int.MAX_VALUE.toLong()

    fun zipDir(dir: Path, out: Path) {
        val files = walkFiles(dir).sortedBy { it.toString() }
        val base = dir.toString().length + 1
        val central = ArrayList<ByteArray>()
        var offset = 0L
        out.parent?.let { SystemFileSystem.createDirectories(it) }
        val buf = ByteArray(1 shl 16)
        SystemFileSystem.sink(out).buffered().use { s ->
            for (f in files) {
                val size = SystemFileSystem.metadataOrNull(f)?.size ?: error("Cannot read ${f.name}")
                require(size <= LIMIT && offset + size <= LIMIT) { "Recording is too large to share as one ZIP" }
                val name = (dir.name + "/" + f.toString().substring(base).replace('\\', '/')).encodeToByteArray()
                val crc = crc32(f, buf)
                val local = header(0x04034b50, name, crc, size.toInt(), central = false, offset = 0)
                s.write(local); s.write(name)
                require(copy(f, s, buf) == size) { "${f.name} changed while sharing" }
                central += header(0x02014b50, name, crc, size.toInt(), central = true, offset = offset) + name
                offset += local.size + name.size + size
            }
            val cdSize = central.sumOf { it.size }
            central.forEach { s.write(it) }
            s.write(le32(0x06054b50) + le16(0) + le16(0) + le16(files.size) + le16(files.size) + le32(cdSize) + le32(offset.toInt()) + le16(0))
        }
    }

    /** 一個成員：內容來自檔案 [file] 或位元組 [bytes]；[deflate]＝要不要壓。 */
    class Entry(val name: String, val file: Path? = null, val bytes: ByteArray? = null, val deflate: Boolean = true) {
        init { require((file == null) != (bytes == null)) { "Entry needs a file or bytes" } }
    }

    /**
     * 依序寫出 [entries]。要壓的成員先壓到 [scratch]（暫存檔，寫完刪掉）以得到壓縮後的大小，檔頭不用資料描述區。
     * 寫到一半失敗會丟例外，[out] 可能不完整，呼叫者自己刪。
     */
    fun zip(entries: List<Entry>, out: Path, scratch: Path) {
        val central = ArrayList<ByteArray>()
        var offset = 0L
        out.parent?.let { SystemFileSystem.createDirectories(it) }
        val buf = ByteArray(1 shl 16)
        try {
            SystemFileSystem.sink(out).buffered().use { s ->
                for (e in entries) {
                    val name = e.name.encodeToByteArray()
                    val size = e.bytes?.size?.toLong() ?: (SystemFileSystem.metadataOrNull(e.file!!)?.size ?: error("Cannot read ${e.file.name}"))
                    require(size <= LIMIT) { "${e.name} is too large for one ZIP" }
                    val crc = e.bytes?.let(::crc32) ?: crc32(e.file!!, buf)
                    val stored = if (e.deflate) {
                        val d = RawDeflate()
                        try {
                            SystemFileSystem.sink(scratch).buffered().use { t ->
                                val w: (ByteArray, Int) -> Unit = { b, n -> t.write(b, 0, n) }
                                if (e.bytes != null) d.update(e.bytes, 0, e.bytes.size, w) else chunks(e.file!!, buf) { n -> d.update(buf, 0, n, w) }
                                d.finish(w)
                            }
                        } finally { d.end() }
                        SystemFileSystem.metadataOrNull(scratch)?.size ?: error("Cannot read the scratch file")
                    } else size
                    require(offset + stored <= LIMIT) { "Too large to write as one ZIP" }
                    val method = if (e.deflate) 8 else 0
                    val local = header(0x04034b50, name, crc, size.toInt(), central = false, offset = 0, method = method, compressed = stored.toInt())
                    s.write(local); s.write(name)
                    val wrote = when {
                        e.deflate -> copy(scratch, s, buf)
                        e.bytes != null -> { s.write(e.bytes); size }
                        else -> copy(e.file!!, s, buf)
                    }
                    require(wrote == stored) { "${e.name} changed while writing" }
                    central += header(0x02014b50, name, crc, size.toInt(), central = true, offset = offset, method = method, compressed = stored.toInt()) + name
                    offset += local.size + name.size + stored
                }
                val cdSize = central.sumOf { it.size }
                central.forEach { s.write(it) }
                s.write(le32(0x06054b50) + le16(0) + le16(0) + le16(entries.size) + le16(entries.size) + le32(cdSize) + le32(offset.toInt()) + le16(0))
            }
        } finally { if (SystemFileSystem.exists(scratch)) SystemFileSystem.delete(scratch) }
    }

    private fun walkFiles(p: Path): List<Path> =
        if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).flatMap(::walkFiles) else listOf(p)

    private inline fun chunks(f: Path, buf: ByteArray, each: (Int) -> Unit) {
        SystemFileSystem.source(f).buffered().use { src ->
            while (true) { val n = src.readAtMostTo(buf, 0, buf.size); if (n <= 0) break; each(n) }
        }
    }

    private fun copy(f: Path, to: Sink, buf: ByteArray): Long {
        var total = 0L
        chunks(f, buf) { n -> to.write(buf, 0, n); total += n }
        return total
    }

    private fun header(sig: Int, name: ByteArray, crc: Int, size: Int, central: Boolean, offset: Long, method: Int = 0, compressed: Int = size): ByteArray {
        val common = le16(20) + le16(0x0800) + le16(method) + le16(0) + le16(0x21) + le32(crc) + le32(compressed) + le32(size) + le16(name.size) + le16(0)
        return if (!central) le32(sig) + common
        else le32(sig) + le16(20) + common + le16(0) + le16(0) + le16(0) + le32(0) + le32(offset.toInt())
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private val TABLE = IntArray(256) { n -> var c = n; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }; c }
    fun crc32(b: ByteArray): Int { var c = -1; for (x in b) c = TABLE[(c xor x.toInt()) and 0xFF] xor (c ushr 8); return c.inv() }
    private fun crc32(f: Path, buf: ByteArray): Int {
        var c = -1
        chunks(f, buf) { n -> for (i in 0 until n) c = TABLE[(c xor buf[i].toInt()) and 0xFF] xor (c ushr 8) }
        return c.inv()
    }
}
