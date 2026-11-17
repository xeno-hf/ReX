package rx.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_BUF_ERROR
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

@OptIn(ExperimentalForeignApi::class)
actual class RawDeflate actual constructor(level: Int) {
    private val zs = nativeHeap.alloc<z_stream>()
    private val buf = ByteArray(1 shl 16)
    private var ended = false

    init {
        platform.posix.memset(zs.ptr, 0, sizeOf<z_stream>().convert())
        // windowBits -15＝原始 deflate（ZIP 的格式）
        check(deflateInit2_(zs.ptr, level, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, zlibVersion()?.toKString(), sizeOf<z_stream>().convert()) == Z_OK) { "deflateInit failed" }
    }

    private fun run(input: ByteArray?, off: Int, len: Int, flush: Int, out: (ByteArray, Int) -> Unit) {
        val src = input ?: ByteArray(1)
        src.usePinned { s ->
            zs.next_in = if (len > 0) s.addressOf(off).reinterpret<UByteVar>() else null
            zs.avail_in = len.convert()
            while (true) {
                val rc = buf.usePinned { o ->
                    zs.next_out = o.addressOf(0).reinterpret<UByteVar>()
                    zs.avail_out = buf.size.convert()
                    deflate(zs.ptr, flush)
                }
                val n = buf.size - zs.avail_out.toInt()
                if (n > 0) out(buf, n)
                if (flush == Z_FINISH) {
                    if (rc == Z_STREAM_END) return
                    check(rc == Z_OK || rc == Z_BUF_ERROR) { "deflate failed ($rc)" }
                } else {
                    check(rc == Z_OK || rc == Z_BUF_ERROR) { "deflate failed ($rc)" }
                    if (zs.avail_in.toInt() == 0 && zs.avail_out.toInt() != 0) return
                }
            }
        }
    }

    actual fun update(input: ByteArray, off: Int, len: Int, out: (ByteArray, Int) -> Unit) {
        if (len > 0) run(input, off, len, Z_NO_FLUSH, out)
    }

    actual fun finish(out: (ByteArray, Int) -> Unit) = run(null, 0, 0, Z_FINISH, out)

    actual fun end() {
        if (ended) return
        ended = true
        deflateEnd(zs.ptr)
        nativeHeap.free(zs)
    }
}
