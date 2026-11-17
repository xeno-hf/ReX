package rx.session

import java.util.zip.Deflater

actual class RawDeflate actual constructor(level: Int) {
    private val d = Deflater(level, true)
    private val buf = ByteArray(1 shl 16)

    actual fun update(input: ByteArray, off: Int, len: Int, out: (ByteArray, Int) -> Unit) {
        d.setInput(input, off, len)
        while (!d.needsInput()) { val n = d.deflate(buf); if (n > 0) out(buf, n) else break }
    }

    actual fun finish(out: (ByteArray, Int) -> Unit) {
        d.finish()
        while (!d.finished()) { val n = d.deflate(buf); if (n > 0) out(buf, n) }
    }

    actual fun end() = d.end()
}
