package rx.session

import kotlinx.io.files.Path
import java.io.File
import java.security.MessageDigest

actual fun sha256Hex(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    File(path.toString()).inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
