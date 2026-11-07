package io.github.xeno_hf.rex

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion
import kotlinx.cinterop.toKString

/** 讀 ZIP 裡第一個符合的檔案（stored 或 deflate；XTune 分享出來的紀錄就是這種）。 */
@OptIn(ExperimentalForeignApi::class)
internal object MiniUnzip {
    fun first(zip: ByteArray, nameFilter: (String) -> Boolean): Pair<String, ByteArray>? {
        return rx.session.ZipImport.first(zip, nameFilter, ::inflateRaw)
    }

    private fun inflateRaw(src: ByteArray, size: Int): ByteArray = memScoped {
        val out = ByteArray(maxOf(1, size))
        val zs = alloc<z_stream>()
        platform.posix.memset(zs.ptr, 0, sizeOf<z_stream>().convert())
        check(inflateInit2_(zs.ptr, -15, zlibVersion()?.toKString(), sizeOf<z_stream>().convert()) == Z_OK) { "inflateInit failed" }
        try {
            src.usePinned { s ->
                out.usePinned { o ->
                    zs.next_in = s.addressOf(0).reinterpret<UByteVar>()
                    zs.avail_in = src.size.convert()
                    zs.next_out = o.addressOf(0).reinterpret<UByteVar>()
                    zs.avail_out = out.size.convert()
                    val rc = inflate(zs.ptr, Z_FINISH)
                    check(rc == Z_STREAM_END && zs.total_out.toLong() == size.toLong() && zs.avail_in.toInt() == 0) { "Invalid ZIP stream ($rc)" }
                }
            }
        } finally { inflateEnd(zs.ptr) }
        out.copyOf(size)
    }
}
