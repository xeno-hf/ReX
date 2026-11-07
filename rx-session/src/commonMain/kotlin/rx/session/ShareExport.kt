package rx.session

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import rx.protocol.CanId
import rx.protocol.Frame
import rx.protocol.RxRaw

/**
 * 分享用的副本：原始錄製目錄不動，另寫一份到 [dst]。
 *
 * - [Mode.PUBLIC]（預設分享）：清掉登入密碼、ECU 識別（握手序號、硬體碼、0x1008／0x1018 讀值、loga 表頭識別欄）、
 *   裝置名與連線目標、手機定位（經緯度、高度）。
 * - [Mode.DIAGNOSTIC]（完整診斷）：只清登入密碼；ECU 識別與定位保留，介面必須先提醒。
 *
 * 只複製已知檔案；其他檔案不帶出。各檔 SHA-256 依去敏後內容重算。
 */
object ShareExport {
    enum class Mode { PUBLIC, DIAGNOSTIC }

    val FILES = listOf("frames.rxraw", "events.jsonl", "decoded.loga")
    private val LOGA_ID_KEYS = setOf("VSCA", "ECU SN", "Hardware ID", "Func. Code", "Upgrade Code", "Original Code")
    private val META_ID_KEYS = LOGA_ID_KEYS + setOf("rx.device", "rx.target")
    private val LOCATION_COLUMN = Regex("(?i)latitude|longitude|altitude|^(gps_)?(lat|lon|lng|alt)$")
    private val IDENTITY_OBJECTS = setOf(0x1008, 0x1018)

    /** 登入幀 `{0x88, 0x00, pw BE24}`：密碼歸零。所有 listener（錄製、同步）收到的都是這個版本。 */
    fun redactSecrets(f: Frame): Frame =
        if (f.canId == CanId.SYS && f.dlc >= 5 && f.u8(0) == 0x88)
            Frame(f.canId, f.data.copyOf().also { for (i in 2 until it.size) it[i] = 0 }, f.flags)
        else f

    fun copySession(src: Path, dst: Path, mode: Mode) {
        SystemFileSystem.createDirectories(dst)
        val written = LinkedHashMap<String, String>()
        for (name in FILES) {
            val from = Path(src, name)
            if (!SystemFileSystem.exists(from)) continue
            val bytes = read(from)
            val out = when (name) {
                "frames.rxraw" -> rxraw(bytes, mode)
                "events.jsonl" -> events(bytes.decodeToString(), mode).encodeToByteArray()
                else -> loga(bytes.decodeToString(), mode).encodeToByteArray()
            }
            write(Path(dst, name), out)
            written[name] = sha256Hex(Path(dst, name))
        }
        val meta = Path(src, "meta.json")
        if (SystemFileSystem.exists(meta)) write(Path(dst, "meta.json"), meta(read(meta).decodeToString(), mode, written).encodeToByteArray())
    }

    fun copyLoga(src: Path, dst: Path, mode: Mode) =
        write(dst, loga(read(src).decodeToString(), mode).encodeToByteArray())

    fun rxraw(b: ByteArray, mode: Mode): ByteArray {
        val out = b.copyOf()
        val m = RxRaw.MAGIC.size
        require(b.size >= m && b.copyOfRange(0, m).contentEquals(RxRaw.MAGIC) && (b.size-m) % RxRaw.RECORD_LEN == 0) { "Invalid recording" }
        var identityRead = false
        var at = m
        while (at + RxRaw.RECORD_LEN <= out.size) {
            val dir = out[at + 8].toInt()
            val f = at + 10
            val canId = ((out[f + 4].toInt() and 0xFF) shl 24) or ((out[f + 5].toInt() and 0xFF) shl 16) or
                ((out[f + 6].toInt() and 0xFF) shl 8) or (out[f + 7].toInt() and 0xFF)
            val dlc = out[f + 9].toInt() and 0xFF
            require(dlc <= 8 && (out[at+9].toInt() and 255) == Frame.FRAME_LEN) { "Invalid recording frame" }
            require(Frame.checksum(out,f+2,f+Frame.FRAME_LEN-1) == (out[f+Frame.FRAME_LEN-1].toInt() and 255)) { "Recording checksum failed" }
            val d = f + 10
            fun zero(from: Int) { for (i in from until dlc) out[d + i] = 0 }
            val op = out[d].toInt() and 0xFF
            when {
                dir == RxRaw.DIR_TX && canId == CanId.SYS && op == 0x88 -> zero(2)
                mode == Mode.DIAGNOSTIC -> Unit
                dir == RxRaw.DIR_RX && canId == 0x6A2 && op in setOf(0xD2, 0xD3, 0xD4, 0xC2, 0xB2, 0xB3) -> zero(1)
                dir == RxRaw.DIR_TX && canId in 0x601..0x67F && dlc >= 4 -> {
                    // 新傳輸的 initiate（上傳 0x40、塊上傳 0xA0；下載 0x20、塊下載 0xC0）才帶 index
                    val cmd = op and 0xE0
                    val initiate = cmd == 0x20 || cmd == 0x40 || ((cmd == 0xA0 || cmd == 0xC0) && (op and 0x03) == 0)
                    if (initiate) {
                        val index = (out[d + 1].toInt() and 0xFF) or ((out[d + 2].toInt() and 0xFF) shl 8)
                        identityRead = (cmd == 0x40 || cmd == 0xA0) && index in IDENTITY_OBJECTS
                    }
                }
                dir == RxRaw.DIR_RX && canId in 0x581..0x5FF && (identityRead || (dlc >= 4 && ((out[d+1].toInt() and 255) or ((out[d+2].toInt() and 255) shl 8)) in IDENTITY_OBJECTS)) -> zero(1)
            }
            out[f + Frame.FRAME_LEN - 1] = Frame.checksum(out, f + 2, f + Frame.FRAME_LEN - 1).toByte()
            at += RxRaw.RECORD_LEN
        }
        return out.copyOf(at)
    }

    fun events(text: String, mode: Mode): String =
        if (mode == Mode.DIAGNOSTIC) text else "" // Free-form events may contain addresses, names or raw reply bytes.

    fun loga(text: String, mode: Mode): String {
        if (mode == Mode.DIAGNOSTIC) return text
        val lines = text.split("\n").toMutableList()
        var i = 0
        var hide = emptySet<Int>()
        while (i < lines.size && lines[i].trimEnd('\r') != "<DATA START>") {
            val l = lines[i].trimEnd('\r')
            val key = l.substringBefore(':', "")
            if (key in LOGA_ID_KEYS) lines[i] = "$key:" + if (lines[i].endsWith('\r')) "\r" else ""
            if (l == "<VAR NAME>" && i + 1 < lines.size)
                hide = lines[i + 1].trimEnd('\r').split(',').withIndex().filter { LOCATION_COLUMN.containsMatchIn(it.value.trim()) }.map { it.index }.toSet()
            i++
        }
        if (hide.isNotEmpty()) for (k in i + 1 until lines.size) {
            val cr = lines[k].endsWith('\r')
            val cells = lines[k].trimEnd('\r').split(',').toMutableList()
            if (cells.size <= 1) continue
            for (c in hide) if (c < cells.size) cells[c] = "0"
            lines[k] = cells.joinToString(",") + if (cr) "\r" else ""
        }
        return lines.joinToString("\n")
    }

    fun meta(text: String, mode: Mode, sha: Map<String, String>): String {
        if (mode == Mode.PUBLIC) {
            // Rebuild an allowlisted envelope rather than attempting to scrub arbitrary JSON fields.
            fun number(key: String) = Regex("\"" + key + "\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1) ?: "0"
            val table = Regex("\"table\"\\s*:\\s*(\\[(?:[0-9,\\s\\[\\]])*\\])").find(text)?.groupValues?.get(1) ?: "[]"
            return "{\n\"format\":\"rx-session-1\",\"share_sanitized\": \"public\",\"table\":$table,\"rounds\":${number("rounds")},\"frames\":${number("frames")},\"info\":{},\"sha256\":{${sha.entries.joinToString(",") { "${Recorder.jsonStr(it.key)}:${Recorder.jsonStr(it.value)}" }}}}\n"
        }
        val hashes = sha.entries.joinToString(",") { "${Recorder.jsonStr(it.key)}:${Recorder.jsonStr(it.value)}" }
        return text.replace(Regex("\"sha256\": \\{[^}]*\\}"), "\"sha256\": {$hashes}")
            .replaceFirst("{\n", "{\n  \"share_sanitized\": \"diagnostic\",\n")
    }

    private fun read(p: Path): ByteArray = SystemFileSystem.source(p).buffered().use { it.readByteArray() }
    private fun write(p: Path, b: ByteArray) {
        p.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(p).buffered().use { it.write(b) }
    }
}
