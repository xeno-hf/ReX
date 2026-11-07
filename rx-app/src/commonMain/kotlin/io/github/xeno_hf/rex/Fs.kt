package io.github.xeno_hf.rex

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.io.writeString

/** 檔案小工具（kotlinx-io，Android 與 iOS 共用）。 */
object Fs {
    fun exists(p: Path) = SystemFileSystem.exists(p)
    fun isDir(p: Path) = SystemFileSystem.metadataOrNull(p)?.isDirectory == true
    fun size(p: Path): Long = SystemFileSystem.metadataOrNull(p)?.size ?: 0L
    fun mkdirs(p: Path): Path { if (!exists(p)) SystemFileSystem.createDirectories(p); return p }
    fun list(p: Path): List<Path> = if (isDir(p)) SystemFileSystem.list(p).toList() else emptyList()
    fun readBytes(p: Path): ByteArray = SystemFileSystem.source(p).buffered().use { it.readByteArray() }
    fun readText(p: Path): String = readBytes(p).decodeToString()
    fun writeBytes(p: Path, b: ByteArray) { p.parent?.let(::mkdirs); SystemFileSystem.sink(p).buffered().use { it.write(b) } }
    fun writeText(p: Path, s: String) { p.parent?.let(::mkdirs); SystemFileSystem.sink(p).buffered().use { it.writeString(s) } }

    /** 刪檔或整個資料夾。 */
    fun delete(p: Path) {
        if (!exists(p)) return
        if (isDir(p)) list(p).forEach(::delete)
        SystemFileSystem.delete(p, mustExist = false)
    }

    /** 資料夾裡所有檔案（遞迴）。 */
    fun walkFiles(p: Path): List<Path> = if (isDir(p)) list(p).flatMap { if (isDir(it)) walkFiles(it) else listOf(it) } else listOf(p)

    val Path.nameWithoutExtension: String get() = name.substringBeforeLast('.')
}
