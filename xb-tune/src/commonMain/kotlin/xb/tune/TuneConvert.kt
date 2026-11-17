package xb.tune

/**
 * 調校檔轉換：ECU 換韌體版本或換代之後，把原本的調校搬到目標版本的範本上，不必重寫油圖。往上（Mini X → X2）和往下（X2 → Mini X）同一套規則。
 * 規則：
 * - 以目標版本的範本（目標 ECU 的原廠檔）為底（版本號、表的組成、沒對應的表都照範本）。
 * - 每張表：兩邊都有、而且**意義相同**（名稱、儲存尺寸、格寬、Gain／Offset、單位、兩條軸）才搬原值。
 *   固定刻度的軸比實際數值；動態軸（Dyna-表號）要那張軸表也搬了才算。
 * - 不搬、留範本值的情況，每張都記原因：
 *   版本識別表（V／A／C 碼、BUILDVER）；只有目標版本有；意義改了；原版本這張表停用；搬過去會有格子超出目標範圍；軸表沒搬；
 *   換版本時「只有一代有的設定」——兩代原廠一邊整張是 0、一邊不是，而原檔的值跟原版本原廠一樣（沒動過）：照目標預設
 *   「原本沒有的設定就照目標 ECU 預設值」）。往上例如 MGU 充電保護、eBoost SOC、電芯過壓（Mini X 原廠是 0）；往下就是把這些清回 0。
 *   原檔值跟原版本原廠不同（使用者自己改過、包括自己關成 0）就照搬。同版本時都照搬。
 *   目標原廠是 0 的那種（往下時最常見）列進確認清單：0 可能只是原廠沒裝那個配備（Mini X 原廠沒有 MGU），不一定是 ECU 沒有這個功能。
 *   沒給原版本原廠檔時退回只看原檔與目標範本，結果列進確認清單。
 * - 學習值（ECU 自己學出來的格子，目前是 NBO2 區塊學習 79／80）一律清成 0，讓 ECU 重學。
 *   學習功能的設定（上下限、步幅、學習條件，例如 NBO2_BL_Max／Min、IACSlowLearnBase）不是學習值，照一般規則。
 * - STX 不搬等級 ≥4 的表（例如 eBoost 設定，所以升級後會被蓋回範本）；這裡不看等級，意義相同就搬。
 * - EEPROM 不在調校內容裡，照範本。
 */
object TuneConvert {
    /** 版本與車種識別：BUILDVER_C、Table ID、V_CODE、A_CODE、C_CODE。一定照範本。 */
    val IDENTITY = setOf(0, 1001, 1002, 1003, 1016)
    /** 只有一代有的設定，照目標預設（原因開頭）。 */
    const val ONE_GEN = "Setting only one generation has"
    private const val LEARNED_REASON = "Learned values, cleared to 0 so the ECU relearns"
    /**
     * 學習值：調校檔裡有內容、ECU 執行中自己寫的格子。名稱以大寫 CELL 結尾（D3NBO2_BL_Warm_CELL、D3NBO2_BL_Cold_CELL）。
     * EERAM／RAM 開頭的同類表沒有調校內容，本來就照範本。
     */
    private val LEARNED = Regex("CELL$")
    private fun cells(n: Int) = if (n == 1) "1 cell" else "$n cells"
    fun isLearned(d: TableDef) = LEARNED.containsMatchIn(d.name) && !d.isEeprom

    /** Reset＝學習值清成 0。 */
    enum class Action { Carry, KeepTemplate, Reset }

    /**
     * [review]＝程式拿不準，畫面上要列出來讓使用者確認（可以切換搬或不搬）。
     * [userChosen]＝使用者用 [choose] 改過；[reason] 留原本的原因。
     */
    class Decision(val absId: Int, val name: String, val action: Action, val reason: String, val review: Boolean = false, val userChosen: Boolean = false)

    class Result(
        /** 轉好的內容：範本為底、搬過來的表換成舊值（還沒存檔）。 */
        val tune: Tune,
        decisions: List<Decision>,
        /** 只有舊版有、新版沒有的表（丟掉）。 */
        val dropped: List<Pair<Int, String>>,
        /** 轉換用的範本（目標基準）。 */
        val template: Tune,
    ) {
        private val list = decisions.toMutableList()
        /** 目標版本每張表的決定。 */
        val decisions: List<Decision> get() = list
        internal fun replace(d: Decision) { list[list.indexOfFirst { it.absId == d.absId }] = d }
        val carried get() = decisions.count { it.action == Action.Carry }
        val reset get() = decisions.filter { it.action == Action.Reset }
        val kept get() = decisions.filter { it.action == Action.KeepTemplate }
        val toReview get() = decisions.filter { it.review }
        /** 搬過來而且值跟範本不同的表（也就是轉換真正保住的調校）。 */
        val carriedDifferent: Int get() = list.count { d ->
            d.action == Action.Carry && tune.rows(d.absId).let { a -> val b = template.rows(d.absId); a != null && b != null && !(a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }) }
        }
    }

    private fun axisValues(t: Tune, key: String): FloatArray? = if (key == "0" || key.contains("Dyna-")) null else t.scales.find(key)

    private fun dynaId(key: String): Int? = if (key.contains("Dyna-")) key.trim().split('-').getOrNull(1)?.toIntOrNull() else null

    /** 兩邊定義的意義是否相同；不同就回原因。 */
    private fun meaning(src: Tune, s: TableDef, dst: Tune, d: TableDef): String? {
        if (s.name != d.name) return "Name differs (${s.name} -> ${d.name})"
        if (s.storedColumns != d.storedColumns || s.storedRows != d.storedRows) return "Size differs (${s.storedColumns}x${s.storedRows} -> ${d.storedColumns}x${d.storedRows})"
        if (s.cellBytes != d.cellBytes) return "Cell width differs (${s.cellBytes} -> ${d.cellBytes} bytes)"
        if (s.gain != d.gain || s.offset != d.offset) return "Scaling differs (gain ${s.gain}/offset ${s.offset} -> ${d.gain}/${d.offset})"
        if (s.unit != d.unit) return "Unit differs (${s.unit} -> ${d.unit})"
        for ((a, b, label) in listOf(Triple(s.scaleX, d.scaleX, "X axis"), Triple(s.scaleY, d.scaleY, "Y axis"))) {
            val da = dynaId(a); val db = dynaId(b)
            if (da != null || db != null) { if (da != db) return "$label changed ($a -> $b)"; continue }
            if (a == "0" && b == "0") continue
            val va = axisValues(src, a); val vb = axisValues(dst, b)
            if (va == null || vb == null || !va.contentEquals(vb)) return "$label scale differs ($a -> $b)"
        }
        return null
    }

    /**
     * [template]＝目標 ECU 的原廠檔；[sourceStock]＝原版本（與 [source] 同 C 碼、同機型）的原廠檔，用來判斷哪些設定原本沒動過。
     * 版本對不上的 [sourceStock] 不用。
     */
    fun convert(source: Tune, template: Tune, sourceStock: Tune? = null): Result {
        val out = Tune(template.definitions, template.scales, template.file, template.attachedDefinitions)
        val reasons = LinkedHashMap<Int, String?>() // null＝搬
        val review = HashMap<Int, String>()          // 搬了但要使用者確認的原因
        val tables = template.file.tables.filterValues { !it.attached }.keys.sorted()
        // 換版本或換代才用「整張是 0」這條；同版本時整張 0 可能是使用者自己關掉的，要照搬
        val crossVersion = source.file.cCode != template.file.cCode || source.file.mapType != template.file.mapType
        val stock = sourceStock?.takeIf { it.file.cCode == source.file.cCode && it.file.mapType == source.file.mapType }
        fun zero(rows: List<LongArray>) = rows.all { r -> r.all { it == 0L } }
        fun same(a: List<LongArray>, b: List<LongArray>) = a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }
        for (id in tables) {
            val d = template.definitions.tables[id]
            val s = source.definitions.tables[id]
            val srcRows = source.file.tables[id]?.takeIf { !it.attached }?.let { source.rows(id) }
            reasons[id] = when {
                d == null -> "Not in the target definitions"
                id in IDENTITY -> "Version identity, from the target"
                d.isEeprom -> "EEPROM, not part of the tune"
                isLearned(d) -> LEARNED_REASON
                s == null || srcRows == null -> "Only in the target version"
                crossVersion && source.definitions.tables[id]!!.versions[source.file.cCode]?.enabled == false -> "Disabled in the input's version"
                else -> meaning(source, s, template, d) ?: if (!crossVersion) null else {
                    val tplZero = zero(template.rows(id)!!)
                    val stockRows = stock?.rows(id)?.takeIf { it.size == srcRows.size }
                    when {
                        stockRows != null && zero(stockRows) != tplZero && same(srcRows, stockRows) ->
                            if (tplZero) {
                                // 目標原廠是 0 可能只是「原廠沒裝」（例如 Mini X 原廠沒有 MGU），不一定是沒有這個功能：照目標預設，但請使用者確認
                                review[id] = "Target stock is all 0 (possibly not fitted, or off by default) and the input is its stock value; target default kept for now"
                                "$ONE_GEN (target stock is all 0, input unchanged from its stock), target default kept"
                            }
                            else "$ONE_GEN (input's stock is all 0, input unchanged), target default kept"
                        stockRows != null -> null
                        zero(srcRows) && !tplZero -> { review[id] = "No stock tune for the input's version to compare; input is all 0 but the target default is not; target default kept for now"; "$ONE_GEN? (input is all 0, target default is not), target default kept" }
                        !zero(srcRows) && tplZero -> { review[id] = "No stock tune for the input's version to compare; target default is all 0 but the input is not; carried for now"; null }
                        else -> null
                    }
                }
            }
            if (reasons[id] == null && d != null) {
                val newOut = srcRows!!.indices.sumOf { r -> srcRows[r].indices.count { c ->
                    Convert.isOutOfRange(d, srcRows[r][c]) && !Convert.isOutOfRange(s!!, srcRows[r][c]) } }
                val oldOut = srcRows.indices.sumOf { r -> srcRows[r].indices.count { c -> Convert.isOutOfRange(d, srcRows[r][c]) } } - newOut
                if (newOut > 0) { reasons[id] = "Out of the target range: ${cells(newOut)} (${d.min} to ${d.max})"; review.remove(id) }
                else if (crossVersion && oldOut > 0 && id !in review) review[id] = "Out of range: ${cells(oldOut)} (${d.min} to ${d.max}; already out of range in the input, carried)"
            }
        }
        // 動態軸：軸表沒搬，用它的表也不搬（一直推到不再變）
        var changed = true
        while (changed) {
            changed = false
            for (id in tables) {
                if (reasons[id] != null) continue
                val d = template.definitions.tables[id] ?: continue
                for (key in listOf(d.scaleX, d.scaleY)) {
                    val ax = dynaId(key) ?: continue
                    if (ax != id && reasons[ax] != null) { reasons[id] = "Axis table not carried ($ax: ${reasons[ax]})"; changed = true; break }
                }
            }
        }
        val decisions = tables.map { id ->
            val r = reasons[id]
            val name = template.definitions.tables[id]?.name ?: template.file.tables[id]!!.name
            if (r == null) {
                val rows = source.rows(id)!!
                check(out.loadRows(id, rows)) { "$name: rows do not fit the template" }
                review[id]?.let { Decision(id, name, Action.Carry, it, review = true) } ?: Decision(id, name, Action.Carry, "Same meaning")
            } else if (r == LEARNED_REASON) {
                val def = template.definitions.tables[id]!!
                val zero = Convert.physicalToRaw(def, 0.0)
                check(out.loadRows(id, template.rows(id)!!.map { LongArray(it.size) { zero } })) { "$name: rows do not fit the template" }
                Decision(id, name, Action.Reset, r)
            } else Decision(id, name, Action.KeepTemplate, r, review = id in review)
        }
        val dropped = source.file.tables.filter { (id, t) -> !t.attached && id !in template.file.tables }.map { (id, t) -> id to t.name }.sortedBy { it.first }
        return Result(out, decisions, dropped, template)
    }

    /**
     * 使用者改某張表的決定：[carry]＝搬原值，否則用基準（範本）。不能改就回原因，改好回 null。
     * 只有確認清單裡的、「只有一代有的設定」、或本來就搬的表可以改；學習值一律清空，意義不同的表不能搬（換算或尺寸對不上）。
     */
    fun choose(result: Result, source: Tune, absId: Int, carry: Boolean): String? {
        val d = result.decisions.firstOrNull { it.absId == absId } ?: return "Table $absId is not in the target version"
        if (d.action == Action.Reset) return "${d.absId} ${d.name}: learned values are always cleared"
        if (carry && d.action == Action.KeepTemplate && !d.review && !d.reason.startsWith(ONE_GEN))
            return "${d.absId} ${d.name} cannot be carried: ${d.reason}"
        val want = if (carry) Action.Carry else Action.KeepTemplate
        if (d.action == want) return null
        val rows = (if (carry) source.rows(absId) else result.template.rows(absId)) ?: return "${d.absId} ${d.name}: no data to use"
        if (!result.tune.loadRows(absId, rows)) return "${d.absId} ${d.name}: sizes do not fit"
        result.replace(Decision(d.absId, d.name, want, d.reason, d.review, userChosen = true))
        return null
    }
}
