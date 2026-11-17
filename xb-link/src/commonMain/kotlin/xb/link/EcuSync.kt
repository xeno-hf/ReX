package xb.link

import rx.protocol.EcuPlatform
import rx.protocol.SdoPort

import xb.tune.TableDef
import xb.tune.Tune

/**
 * ECU 上的版本（物件 0x1018 的 sub 6–9、0x1008 裝置名稱）與車種代號（表 1001，寫成調校檔的 Table_ID）。
 */
data class EcuIdentity(val v: Int?, val s: Int?, val c: Int?, val a: Int?, val name: String?, val tableId: String? = null) {
    fun label() = "V${v ?: "?"} S${s ?: "?"} C${c ?: "?"} A${a ?: "?"}" + (tableId?.let { " $it" } ?: "")
    /** 跟調校檔的版本對得上（V、C 相同）。讀不到版本就是 false。只看版本；寫入前要用 [EcuSync.compatibility]。 */
    fun matches(tune: Tune) = v != null && c != null && v == tune.file.vCode && c == tune.file.cCode
}

/** 一張表在 ECU 上讀到的內容，或讀不到的原因。 */
sealed interface TableRead {
    val def: TableDef
    class Ok(override val def: TableDef, val rows: List<LongArray>) : TableRead
    class Failed(override val def: TableDef, val reason: String, val abortCode: Long? = null) : TableRead
}

/** 一次整份讀回。[reads] 依表號排序。 */
class Snapshot(val identity: EcuIdentity, val reads: List<TableRead>) {
    /** 每一張表都讀到了：只有完整的讀回能拿來整包寫入或當還原的依據。 */
    val complete: Boolean get() = reads.isNotEmpty() && reads.all { it is TableRead.Ok }
    val ok: List<TableRead.Ok> get() = reads.filterIsInstance<TableRead.Ok>()
    val failed: List<TableRead.Failed> get() = reads.filterIsInstance<TableRead.Failed>()
    fun rows(absId: Int): List<LongArray>? = (reads.firstOrNull { it.def.absId == absId } as? TableRead.Ok)?.rows
}

/** 跟調校檔不同的一張表：[cells]＝不同的格數。 */
class TableDelta(val def: TableDef, val cells: Int)

/**
 * 調校檔的表跟 ECU 物件字典的對應，以及整份讀回、比對、備份。
 * - 物件：表號＋0x3000；一列一個 sub，從 0 起。
 * - 一列＝儲存欄數 × 格寬 bytes，little-endian、無號。4 bytes 以內用一般讀寫，其餘區塊讀寫（同 XTune）。
 * - 只對 ECU 本體的表：EEPROM 群組不在調校內容裡、MGU 附掛表在 MGU 上，都不碰。
 */
object EcuSync {
    const val TABLE_BASE = 0x3000

    fun index(def: TableDef) = TABLE_BASE + def.absId
    fun rowBytes(def: TableDef) = def.storedColumns * def.cellBytes

    /** 這份調校檔裡，ECU 本體上有對應物件的表（依表號排序）。 */
    fun tables(tune: Tune): List<TableDef> = tune.definitions.tables.values
        .filter { def ->
            !def.isEeprom && def.absId in 0 until 0x1000 && def.cellBytes in 1..4 &&
                tune.file.tables[def.absId]?.let { t ->
                    !t.attached && t.rows.size == def.storedRows && t.rows.all { it.size == def.storedColumns }
                } == true
        }
        .sortedBy { it.absId }

    fun encodeRow(def: TableDef, row: LongArray): ByteArray {
        require(row.size == def.storedColumns) { "${def.name}: row has ${row.size} cells, expected ${def.storedColumns}" }
        val w = def.cellBytes
        val out = ByteArray(row.size * w)
        for (i in row.indices) {
            val v = row[i]
            require(v in 0..def.rawMax) { "${def.name}: raw $v exceeds ${def.rawMax}" }
            for (k in 0 until w) out[i * w + k] = (v ushr (8 * k)).toByte()
        }
        return out
    }

    fun decodeRow(def: TableDef, bytes: ByteArray): LongArray {
        val w = def.cellBytes
        require(bytes.size == rowBytes(def)) { "${def.name}: ECU row is ${bytes.size} bytes, expected ${rowBytes(def)}" }
        return LongArray(def.storedColumns) { i ->
            var v = 0L
            for (k in 0 until w) v = v or ((bytes[i * w + k].toLong() and 0xFF) shl (8 * k))
            v
        }
    }

    suspend fun identity(link: SdoPort): EcuIdentity {
        suspend fun u16(sub: Int): Int? = runCatching {
            val d = link.read(0x1018, sub, block = false)
            if (d.isEmpty()) null else (d[0].toInt() and 0xFF) or (if (d.size > 1) (d[1].toInt() and 0xFF) shl 8 else 0)
        }.getOrNull()
        val name = runCatching { link.read(0x1008, 0, block = false).decodeToString().trimEnd('\u0000') }.getOrNull()
        val tableId = runCatching { link.read(TABLE_BASE + TABLE_ID, 0, block = false) }.getOrNull()?.takeIf { it.size == 4 }?.let { d ->
            tableIdText((0 until 4).fold(0L) { a, i -> a or ((d[i].toLong() and 0xFF) shl (8 * i)) })
        }
        return EcuIdentity(u16(6), u16(7), u16(8), u16(9), name, tableId)
    }

    /** 車種代號在 ECU 上的表號。 */
    const val TABLE_ID = 1001

    /***/
    fun tableIdText(v: Long): String = listOf(24, 16, 8, 0).joinToString("_") { ((v ushr it) and 0xFF).toString().padStart(2, '0') }

    private fun tableIdParts(t: String): List<Int>? = t.trim().split('_').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.size == 4 }

    /** 調校檔類型（MAP_Type）對應的 ECU 平台（心跳 node）。 */
    fun platformOf(mapType: String): EcuPlatform? = when (mapType.trim().uppercase()) {
        "AMX" -> EcuPlatform.MINI_X; "ASX" -> EcuPlatform.SUPER_X; "AMX2" -> EcuPlatform.MINI_XX; "ASX2" -> EcuPlatform.SUPER_XX
        "A4X" -> EcuPlatform.SUPER_X_PRO_CYL4; "AZR" -> EcuPlatform.SUPER_X_PRO_RZR; "SXS" -> EcuPlatform.SXS
        else -> null
    }

    /**
     * 能不能把 [tune] 寫進這台 ECU：平台（心跳 node 對 MAP_Type）、V、C、車種代號（Table_ID）全部要對。
     * 任何一項讀不到就不行。回 null＝可以，否則是原因（英文，畫面自己翻）。
     */
    fun compatibility(id: EcuIdentity, platform: EcuPlatform?, tune: Tune): String? {
        val f = tune.file
        val want = platformOf(f.mapType) ?: return "Unknown tune type ${f.mapType}"
        if (platform == null) return "The ECU platform is unknown (no heartbeat)"
        if (platform != want) return "The ECU is ${platform.label}, the tune is for ${want.label} (${f.mapType})"
        if (id.v == null || id.c == null) return "The ECU version could not be read"
        if (id.v != f.vCode || id.c != f.cCode) return "The ECU is V${id.v} C${id.c}, the tune is V${f.vCode} C${f.cCode}"
        val ecuModel = id.tableId?.let(::tableIdParts) ?: return "The ECU vehicle code (Table ID) could not be read"
        val tuneModel = tableIdParts(f.tableId) ?: return "The tune has no valid Table ID"
        if (ecuModel != tuneModel) return "The ECU vehicle code is ${id.tableId}, the tune is ${f.tableId}"
        // 檔頭跟檔案裡的表要一致：表 1016（C 碼）與表 1001（Table ID）
        val c1016 = tune.rows(1016)?.firstOrNull()?.firstOrNull()
        if (c1016 != null && c1016.toInt() != f.cCode) return "The tune header says C${f.cCode} but table 1016 says C$c1016"
        val t1001 = tune.rows(TABLE_ID)?.firstOrNull()?.firstOrNull()
        if (t1001 != null && tableIdParts(tableIdText(t1001)) != tuneModel) return "The tune header Table ID ${f.tableId} differs from table 1001 (${tableIdText(t1001)})"
        return null
    }

    /** 讀一張表的每一列。被拒或長度不對就回 [TableRead.Failed]，不丟例外（連線斷了才丟）。 */
    suspend fun readTable(link: SdoPort, def: TableDef): TableRead {
        val small = rowBytes(def) <= 4
        val rows = ArrayList<LongArray>(def.storedRows)
        for (r in 0 until def.storedRows) {
            val bytes = try {
                link.read(index(def), r, block = !small)
            } catch (e: SdoAbortException) {
                return TableRead.Failed(def, e.abort.toString(), e.abort.code)
            }
            if (bytes.size != rowBytes(def)) return TableRead.Failed(def, "row $r: ECU returned ${bytes.size} bytes, expected ${rowBytes(def)}")
            rows += decodeRow(def, bytes)
        }
        return TableRead.Ok(def, rows)
    }

    /** 整份讀回。[onProgress]（已讀表數、總表數）。 */
    suspend fun readAll(link: SdoPort, tune: Tune, onProgress: (Int, Int) -> Unit = { _, _ -> }): Snapshot {
        val id = identity(link)
        val defs = tables(tune)
        val reads = ArrayList<TableRead>(defs.size)
        defs.forEachIndexed { i, def ->
            reads += readTable(link, def)
            onProgress(i + 1, defs.size)
        }
        return Snapshot(id, reads)
    }

    /** ECU 上跟 [tune] 目前內容（含還沒存檔的修改）不同的表。讀不到的表不列。 */
    fun differences(snapshot: Snapshot, tune: Tune): List<TableDelta> = snapshot.ok.mapNotNull { r ->
        val mine = tune.rows(r.def.absId) ?: return@mapNotNull null
        var n = 0
        for (row in r.rows.indices) for (c in r.rows[row].indices) if (mine.getOrNull(row)?.getOrNull(c) != r.rows[row][c]) n++
        if (n == 0) null else TableDelta(r.def, n)
    }

    /**
     * ECU 現值做成一份調校檔：以 [tune] 開的那份檔案為底，讀到的表換成 ECU 的值。
     * 讀不到的表留檔案裡的值，所以 [Snapshot.complete] 是 false 的備份不能拿來整包寫回（呼叫的人要記下來）。
     * 回傳未加密的調校檔文字。
     */
    fun backup(snapshot: Snapshot, tune: Tune, lastSaveTime: String? = null): String {
        val copy = Tune(tune.definitions, tune.scales, tune.file, tune.attachedDefinitions)
        for (r in snapshot.ok) check(copy.loadRows(r.def.absId, r.rows)) { "${r.def.name}: ECU rows do not fit the tune file" }
        return copy.toXml(lastSaveTime)
    }
}
