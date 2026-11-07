package rx.session

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(path: Path): String = memScoped {
    val ctx = alloc<CC_SHA256_CTX>()
    CC_SHA256_Init(ctx.ptr)
    SystemFileSystem.source(path).buffered().use { src ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = src.readAtMostTo(buf, 0, buf.size)
            if (n <= 0) break
            buf.usePinned { CC_SHA256_Update(ctx.ptr, it.addressOf(0), n.toUInt()) }
        }
    }
    val out = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    CC_SHA256_Final(out, ctx.ptr)
    (0 until CC_SHA256_DIGEST_LENGTH).joinToString("") { out[it].toInt().toString(16).padStart(2, '0') }
}
