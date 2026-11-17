package rx.session

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readTo
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.Rpc
import rx.protocol.RxRaw
import rx.protocol.Sdo

/**
 * `.xbl`：XB／ReX 共用的騎乘紀錄（2026-11-16 使用者定）。錄製時照舊寫資料夾，使用者要打包才打包。
 *
 * ```
 * mimetype       application/vnd.xenobrex.ride+zip（第一個成員、不壓縮）
 * manifest.json  格式版本、寫入的程式、起訖時間、車子型號、分享模式、各成員的 SHA-256
 * meta.json      錄製的 meta（同 ShareExport 去敏後）
 * frames.rxraw   原始封包（唯一的原始資料）
 * decoded.loga   解碼後的 loga（XTune 可以直接開）
 * events.jsonl   連線事件與標記（公開模式是空的，跟 ShareExport 一樣）
 * writes.jsonl   App 寫進 ECU 的動作（從 frames.rxraw 整理；串流設定 0x1800／0x1A00 不算）。
 *                寫之前讀過同一個物件就附 before（儀表鈕都是先讀再寫）；沒讀過就沒有，rxraw 裡沒有改前的值
 * ```
 * 公開模式不帶起訖時間（同 ShareExport 的公開 meta），車子型號照帶。
 * 車子型號：平台從錄製時的 node，車種代碼與 V／C／A 是錄製開始時讀 ECU 的表 1001／1002／1016／1003（讀不到就空著）。
 * 車款名稱不在這裡：要官方清單，由 XB 打開時查。
 */
object XblPack {
    const val MIME = "application/vnd.xenobrex.ride+zip"
    const val FORMAT = "xbl-1"

    /** 錄製 info 裡放 ECU 版本碼的鍵（Table ID 用 loga 表頭原本的鍵）。 */
    const val INFO_V = "rx.v_code"
    const val INFO_C = "rx.c_code"
    const val INFO_A = "rx.a_code"

    /** 調校檔的型號（MAP_Type）；X2 世代的 SXS 也是自己一種。 */
    fun mapType(p: EcuPlatform): String = when (p) {
        EcuPlatform.MINI_X -> "AMX"; EcuPlatform.SUPER_X -> "ASX"
        EcuPlatform.MINI_XX -> "AMX2"; EcuPlatform.SUPER_XX -> "ASX2"
        EcuPlatform.SUPER_X_PRO_CYL4 -> "A4X"; EcuPlatform.SUPER_X_PRO_RZR -> "AZR"; EcuPlatform.SXS -> "SXS"
    }

    fun tableIdText(v: Long): String = listOf(24, 16, 8, 0).joinToString("_") { ((v ushr it) and 0xFF).toString().padStart(2, '0') }

    /**
     * 把錄製資料夾 [src] 打包成 [out]。先照 [mode] 去敏到 [staging]（用完刪掉），再壓縮。
     * [app]：寫入的程式與版本（例「ReX 1.0.0」）。
     */
    fun pack(src: Path, out: Path, staging: Path, mode: ShareExport.Mode, app: String) {
        if (SystemFileSystem.exists(staging)) deleteTree(staging)
        try {
            ShareExport.copySession(src, staging, mode)
            // 車子型號與起訖時間從原始的 meta 拿：公開模式的 meta 只留白名單欄位，info 是空的
            val meta = Path(src, "meta.json")
            val metaObj = if (SystemFileSystem.exists(meta)) JsonLine.parseObject(read(meta).decodeToString(), nested = true) else null
            @Suppress("UNCHECKED_CAST")
            val info = (metaObj?.get("info") as? Map<String, Any?>).orEmpty()
            val rxraw = Path(staging, "frames.rxraw")
            val writes = if (SystemFileSystem.exists(rxraw)) writes(rxraw) else ""
            val writesFile = Path(staging, "writes.jsonl")
            SystemFileSystem.sink(writesFile).buffered().use { it.write(writes.encodeToByteArray()) }

            val members = listOf("meta.json", "frames.rxraw", "decoded.loga", "events.jsonl", "writes.jsonl").filter { SystemFileSystem.exists(Path(staging, it)) }
            val node = (info["rx.node"] as? String)?.toIntOrNull()
            val platform = node?.let(EcuPlatform::ofNode)
            fun num(o: Any?) = (o as? JsonLine.Num)?.text?.toLongOrNull()
            val vehicle = linkedMapOf<String, Any?>(
                "platform" to platform?.label,
                "node" to node,
                "map_type" to platform?.let(::mapType),
                "table_id" to (info["Table ID"] as? String)?.ifBlank { null },
                "v" to (info[INFO_V] as? String)?.toIntOrNull(),
                "c" to (info[INFO_C] as? String)?.toIntOrNull(),
                "a" to (info[INFO_A] as? String)?.toIntOrNull(),
            )
            val sb = StringBuilder("{")
            sb.append("\"format\":${Recorder.jsonStr(FORMAT)},\"created_by\":${Recorder.jsonStr(app)},\"created\":${Recorder.jsonStr(WallClock.iso(WallClock.nowMs()))}")
            sb.append(",\"mode\":${Recorder.jsonStr(if (mode == ShareExport.Mode.PUBLIC) "public" else "full")}")
            sb.append(",\"recording\":${Recorder.jsonStr(src.name)}")
            // 起訖時間跟 ShareExport 的公開 meta 一樣不帶
            if (mode == ShareExport.Mode.DIAGNOSTIC) {
                num(metaObj?.get("started_wall_ms"))?.let { sb.append(",\"started\":${Recorder.jsonStr(WallClock.iso(it))}") }
                num(metaObj?.get("stopped_wall_ms"))?.let { sb.append(",\"stopped\":${Recorder.jsonStr(WallClock.iso(it))}") }
            }
            sb.append(",\"vehicle\":{").append(vehicle.entries.joinToString(",") { (k, v) ->
                "${Recorder.jsonStr(k)}:" + when (v) { null -> "null"; is String -> Recorder.jsonStr(v); else -> v.toString() }
            }).append('}')
            sb.append(",\"files\":{").append(members.joinToString(",") { "${Recorder.jsonStr(it)}:${Recorder.jsonStr(sha256Hex(Path(staging, it)))}" }).append("}}\n")

            val entries = listOf(ZipStore.Entry("mimetype", bytes = MIME.encodeToByteArray(), deflate = false),
                ZipStore.Entry("manifest.json", bytes = sb.toString().encodeToByteArray())) +
                members.map { ZipStore.Entry(it, file = Path(staging, it)) }
            val tmp = Path(out.toString() + ".tmp")
            try {
                ZipStore.zip(entries, tmp, Path(staging, ".deflate"))
                if (SystemFileSystem.exists(out)) SystemFileSystem.delete(out)
                SystemFileSystem.atomicMove(tmp, out)
            } finally { if (SystemFileSystem.exists(tmp)) SystemFileSystem.delete(tmp) }
        } finally { deleteTree(staging) }
    }

    /**
     * frames.rxraw 裡 App 寫進 ECU 的動作，一行一個 JSON：時間（loga Time 軸的毫秒）、物件、資料、ECU 的回答。
     * 串流設定（0x1800 啟動、0x1A00 頻道表）每次連線都會寫，不算；表 [Rpc] 的 MAP 切換與 0x3000＋表號的寫入都列出。
     */
    fun writes(rxraw: Path): String {
        val out = StringBuilder()
        val size = SystemFileSystem.metadataOrNull(rxraw)?.size ?: return ""
        val m = RxRaw.MAGIC.size
        if (size < m) return ""
        class Pending(val tMs: Double, val index: Int, val sub: Int, val data: String, val kind: String, val before: String?)
        // 每個 node 進行中的一筆 expedited 寫入、一筆區塊寫入
        val pending = HashMap<Int, Pending>()
        class Block(val p: Pending) { val buf = ArrayList<Byte>(); var segmentsDone = false; var ended = false; var pad = 0 }
        val blocks = HashMap<Int, Block>()
        // 寫之前最後一次讀到的值（儀表鈕都是先讀再寫，所以通常有）
        val lastRead = HashMap<Long, String>()
        fun key(index: Int, sub: Int) = (index.toLong() shl 8) or sub.toLong()
        fun emit(p: Pending, result: String) {
            val what = when {
                p.index == Rpc.MAP_SWITCH -> "MAP switch"
                p.index in 0x3000..0x3FFF -> "table ${p.index - 0x3000}"
                else -> null
            }
            out.append("{\"t_ms\":${rx.protocol.Fmt.fixed(p.tMs, 1)},\"index\":\"0x${p.index.toString(16).padStart(4, '0')}\",\"sub\":${p.sub}")
            if (what != null) out.append(",\"what\":${Recorder.jsonStr(what)}")
            if (p.index in 0x3000..0x3FFF) out.append(",\"table\":${p.index - 0x3000},\"row\":${p.sub}")
            out.append(",\"kind\":${Recorder.jsonStr(p.kind)}")
            if (p.before != null) out.append(",\"before\":${Recorder.jsonStr(p.before)}")
            out.append(",\"data\":${Recorder.jsonStr(p.data)},\"result\":${Recorder.jsonStr(result)}}\n")
        }
        var t0 = -1L
        SystemFileSystem.source(rxraw).buffered().use { src ->
            if (!src.readByteArray(m).contentEquals(RxRaw.MAGIC)) return ""
            val rec = ByteArray(RxRaw.RECORD_LEN)
            var left = (size - m) / RxRaw.RECORD_LEN
            while (left-- > 0) {
                src.readTo(rec, 0, rec.size)
                var t = 0L
                for (k in 7 downTo 0) t = (t shl 8) or (rec[k].toLong() and 0xFF)
                if (t0 < 0) t0 = t
                val dir = rec[8].toInt()
                val f = rec.copyOfRange(10, 10 + Frame.FRAME_LEN)
                val dlc = f[9].toInt() and 0xFF
                val canId = (4..7).fold(0) { a, i -> (a shl 8) or (f[i].toInt() and 0xFF) }
                // 只看 RPC（手機送 0x600|node、ECU 回 0x580|node）；同一個 node 一次只有一筆交易
                val isTx = dir == RxRaw.DIR_TX && canId in 0x601..0x67F
                val isRx = dir != RxRaw.DIR_TX && canId in 0x581..0x5FF
                if (!isTx && !isRx || dlc < 1 || dlc > 8) continue
                val node = canId and 0x7F
                val data = f.copyOfRange(10, 10 + dlc)
                fun u8(i: Int) = data[i].toInt() and 0xFF
                fun hex(from: Int, n: Int) = (0 until n).joinToString("") { u8(from + it).toString(16).padStart(2, '0') }
                val tMs = (t - t0) / 1_000_000.0
                val bl = blocks[node]
                if (isTx) {
                    val b0 = u8(0)
                    // 區塊寫入進行中：分段（資料 7 bytes）、結束幀（0xC1|n<<2，末段有 n bytes 是填充）、abort
                    if (bl != null) {
                        when {
                            b0 == 0x80 -> { blocks.remove(node); emit(bl.p, "aborted by app") }
                            !bl.segmentsDone && dlc == 8 -> { for (i in 1..7) bl.buf.add(data[i]); if (b0 and 0x80 != 0) bl.segmentsDone = true }
                            bl.segmentsDone && (b0 and 0xE3) == 0xC1 -> { bl.pad = (b0 ushr 2) and 7; bl.ended = true }
                        }
                        continue
                    }
                    if (dlc < 4) continue
                    val index = u8(1) or (u8(2) shl 8)
                    val sub = u8(3)
                    if (index == Rpc.MON_ADDR || index in Rpc.CHAN_TABLE_ADDR..0x1AFF) continue
                    // 上一筆沒等到回應就送了下一筆：上一筆記成沒回應
                    fun supersede() { pending.remove(node)?.let { emit(it, "no reply") } }
                    when {
                        Sdo.ccs(b0) == Sdo.CCS_DOWNLOAD && dlc == 8 -> {
                            supersede()
                            val expedited = b0 and 0x02 != 0
                            val n = if (expedited && b0 and 0x01 != 0) 4 - ((b0 ushr 2) and 3) else if (expedited) 4 else 0
                            pending[node] = Pending(tMs, index, sub, hex(4, n), if (expedited) "expedited" else "segmented", lastRead[key(index, sub)])
                        }
                        b0 == 0xC6 && dlc == 8 -> {
                            supersede()
                            val p = Pending(tMs, index, sub, "", "block", lastRead[key(index, sub)])
                            blocks[node] = Block(p)
                        }
                    }
                } else {
                    val b0 = u8(0)
                    val index = if (dlc >= 3) u8(1) or (u8(2) shl 8) else -1
                    val sub = if (dlc >= 4) u8(3) else -1
                    if (bl != null) {
                        when {
                            b0 == 0x80 -> {
                                val code = if (dlc >= 8) (0 until 4).fold(0L) { a, i -> a or (u8(4 + i).toLong() shl (8 * i)) } else 0L
                                blocks.remove(node); emit(bl.p, "abort 0x${code.toString(16).padStart(8, '0')} ${Sdo.abortText(code)}")
                            }
                            // 0xA1：ECU 確認區塊寫入結束——這時整筆才算寫進去
                            b0 == 0xA1 && bl.ended -> {
                                blocks.remove(node)
                                val bytes = bl.buf.toByteArray().let { it.copyOf(maxOf(0, it.size - bl.pad)) }
                                emit(Pending(bl.p.tMs, bl.p.index, bl.p.sub, bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }, "block", bl.p.before), "ack")
                            }
                        }
                        continue
                    }
                    if ((b0 and 0xE2) == 0x42 && dlc >= 4) {
                        // expedited 上傳的回應：記下讀到的值
                        val n = if (b0 and 0x01 != 0) 4 - ((b0 ushr 2) and 3) else 4
                        lastRead[key(index, sub)] = hex(4, minOf(n, dlc - 4))
                        continue
                    }
                    val p = pending[node]?.takeIf { it.index == index && it.sub == sub } ?: continue
                    when {
                        b0 == 0x60 -> { pending.remove(node); emit(p, "ack") }
                        b0 == 0x80 -> {
                            val code = if (dlc >= 8) (0 until 4).fold(0L) { a, i -> a or (u8(4 + i).toLong() shl (8 * i)) } else 0L
                            pending.remove(node); emit(p, "abort 0x${code.toString(16).padStart(8, '0')} ${Sdo.abortText(code)}")
                        }
                    }
                }
            }
        }
        for (p in pending.values) emit(p, "no reply")
        for (b in blocks.values) emit(b.p, "not finished")
        return out.toString()
    }

    private fun read(p: Path): ByteArray = SystemFileSystem.source(p).buffered().use { it.readByteArray() }

    private fun deleteTree(p: Path) {
        val md = SystemFileSystem.metadataOrNull(p) ?: return
        if (md.isDirectory) SystemFileSystem.list(p).forEach(::deleteTree)
        SystemFileSystem.delete(p)
    }
}
