package xb.tune

/** 一條軸。動態軸的刻度存在另一張表裡，可以編輯；固定軸來自刻度表，不能改。 */
class Axis(
    val name: String,
    val values: FloatArray,
    /** 動態軸的來源表號；固定軸是 null。 */
    val sourceTable: Int?,
) {
    val editable: Boolean get() = sourceTable != null
}

sealed class AxisLookup {
    /** 這個方向沒有軸。 */
    object None : AxisLookup()
    class Found(val axis: Axis) : AxisLookup()
    /** 定義指到的刻度或表不存在。 */
    class Missing(val key: String) : AxisLookup()
}

/** 開檔檢查的結果：哪些地方和定義對不上。 */
class TuneReport {
    var tablesChecked = 0
    var eepromTables = 0
    var gainZeroTables = 0
    var fixedAxes = 0
    var dynamicAxes = 0
    var dynamicAxesShorter = 0
    var cells = 0
    var cellsOutOfRange = 0
    var cellsNotReversible = 0
    val tablesOutOfRange = ArrayList<Int>()
    val tablesNotReversible = ArrayList<Int>()
    /** 調校檔有、定義檔沒有的表。 */
    val unknownTables = ArrayList<Int>()
    /** 定義檔有、調校檔沒有的表。 */
    val missingTables = ArrayList<Int>()
    val nameDiffers = ArrayList<Int>()
    val sizeMismatch = ArrayList<Int>()
    val rawOverCell = ArrayList<Int>()
    val axisMissing = ArrayList<Int>()
    val fixedAxisLengthDiffers = ArrayList<Int>()
    val dynamicAxisLonger = ArrayList<Int>()
}

/** 表裡的一格，以儲存位置計（從 0 起），不含軸。 */
data class Cell(val row: Int, val col: Int)

enum class AdjustOp { Add, Multiply, Divide, Set }

sealed class EditResult {
    /** [changed]：原始值真的變了的格數；0 表示動作成立但沒有任何格子改變。 */
    class Done(val changed: Int) : EditResult()
    class Refused(val reason: String) : EditResult()
}

/**
 * 調校檔＋定義檔＋刻度表合起來的模型，也是編輯的工作區。
 * 記憶體裡保留原始整數；物理值是算出來給人看的。編輯只重新編碼**數值真的被改到的格子**，
 * 其餘格子（包含畫面看不到的儲存範圍、超出定義範圍的值）一律原樣保留。
 * STX 的做法是整列用畫面上的值重寫、看不到的部分補 0；這裡刻意不照做。
 */
class Tune(
    val definitions: Definitions,
    val scales: ScaleData,
    file: TuneFile,
    /** MGU 附掛表的定義（表號已加位移）；調校檔沒有附掛表時可省略。 */
    val attachedDefinitions: Definitions? = null,
) {
    /** 目前的檔案基準；存檔後由 [rebase] 換成剛寫出去的那份。 */
    var file: TuneFile = file
        private set

    /** 改過的表的工作副本；沒改過的表直接讀檔案裡的內容。 */
    private val working = HashMap<Int, List<LongArray>>()
    private val undoStack = ArrayList<Pair<Int, List<LongArray>?>>()

    fun definition(absId: Int): TableDef? = definitions.tables[absId] ?: attachedDefinitions?.tables?.get(absId)

    /** 這張表目前的原始值（含未存檔的修改）。 */
    fun rows(absId: Int): List<LongArray>? = working[absId] ?: file.tables[absId]?.rows

    /** 內容和檔案裡不同才算改過；改了又改回去不算。 */
    fun isModified(absId: Int): Boolean {
        val w = working[absId] ?: return false
        val o = file.tables[absId]?.rows ?: return true
        return !sameRows(w, o)
    }

    val modifiedTables: List<Int> get() = working.keys.filter { isModified(it) }.sorted()

    /** 這張表在這份調校檔的版本與機型下的可見等級。 */
    fun level(def: TableDef): Int? = def.level(file.cCode, file.modelId)

    fun axisX(def: TableDef): AxisLookup = axis(def.scaleX)
    fun axisY(def: TableDef): AxisLookup = axis(def.scaleY)

    /** 有效欄數／列數＝軸的長度；沒有軸或找不到就是 1。畫面只顯示這個範圍，檔案裡仍保留完整的儲存尺寸。 */
    fun effectiveColumns(def: TableDef): Int = (axisX(def) as? AxisLookup.Found)?.axis?.values?.size ?: 1
    fun effectiveRows(def: TableDef): Int = (axisY(def) as? AxisLookup.Found)?.axis?.values?.size ?: 1

    private fun axis(key: String): AxisLookup {
        if (key == "0") return AxisLookup.None
        if (key.contains("Dyna-")) {
            // Dyna-<表號>-<名稱>：刻度是那張表的第 0 列，取到數值不再遞增為止
            val parts = key.trim().split('-')
            val id = parts.getOrNull(1)?.toIntOrNull() ?: return AxisLookup.Missing(key)
            val refDef = definitions.tables[id] ?: return AxisLookup.Missing(key)
            val row = rows(id)?.firstOrNull() ?: return AxisLookup.Missing(key)
            var n = row.size
            for (i in 1 until row.size) {
                if (row[i] < row[i - 1]) { n = i; break }
            }
            val values = FloatArray(n) { Convert.rawToPhysical(refDef, row[it]).toFloat() }
            return AxisLookup.Found(Axis(parts.getOrNull(2) ?: "", values, id))
        }
        val values = scales.find(key) ?: return AxisLookup.Missing(key)
        return AxisLookup.Found(Axis(key.substring(0, key.lastIndexOf('-')), values, null))
    }

    // ---- 編輯 ----

    /**
     * 對選取的格子加、乘、除一個數，或直接設成一個數。運算對象是畫面上顯示的值，和 STX 相同。
     * 位元選項表與 0／1 開關不適用，請用 [setRaw]。
     */
    fun adjust(absId: Int, cells: Collection<Cell>, op: AdjustOp, value: Double): EditResult {
        val def = editable(absId) ?: return refuse(absId)
        if (def.adjustDisabled && op != AdjustOp.Set) return EditResult.Refused("${def.name} is a switch or bit option; arithmetic is not allowed")
        if (value.isNaN() || value.isInfinite()) return EditResult.Refused("Invalid value")
        if (op == AdjustOp.Divide && value == 0.0) return EditResult.Refused("Cannot divide by 0")
        val cur = rows(absId)!!
        outOfBounds(def, cells)?.let { return it }
        return write(absId, def, cells.associateWith { c ->
            val shown = Convert.rawToPhysical(def, cur[c.row][c.col])
            when (op) {
                AdjustOp.Add -> shown + value
                AdjustOp.Multiply -> shown * value
                AdjustOp.Divide -> shown / value
                AdjustOp.Set -> value
            }
        })
    }

    /** 一次設定多格的物理值（貼上用）。規則和 [adjust] 相同：顯示值沒變的格子不動。 */
    fun setValues(absId: Int, values: Map<Cell, Double>): EditResult {
        val def = editable(absId) ?: return refuse(absId)
        outOfBounds(def, values.keys)?.let { return it }
        if (values.values.any { it.isNaN() || it.isInfinite() }) return EditResult.Refused("Invalid value")
        return write(absId, def, values)
    }

    /**
     * 選取同一列或同一欄的連續 3 格以上：頭尾不動，中間等差填滿。
     * 算法照 STX，用單精度。
     */
    fun linear(absId: Int, cells: Collection<Cell>): EditResult {
        val def = editable(absId) ?: return refuse(absId)
        outOfBounds(def, cells)?.let { return it }
        val line = straightRun(cells) ?: return EditResult.Refused("Select at least 3 consecutive cells in one row or column")
        val cur = rows(absId)!!
        val v = FloatArray(line.size) { Convert.rawToPhysical(def, cur[line[it].row][line[it].col]).toFloat() }
        val n = v.size
        val first = v[0]
        val last = v[n - 1]
        val span = if (last > first) last - first else if (first > last) first - last else 0f
        val step = span / (n - 1).toFloat()
        // 頭尾不動；算出來和原本顯示值相同的格子也不動，原始值原樣保留
        val out = HashMap<Cell, Double>()
        for (i in 1 until n - 1) {
            val r = if (span == 0f) first else if (last > first) first + i.toFloat() * step else first - i.toFloat() * step
            if (r != v[i]) out[line[i]] = r.toDouble()
        }
        return write(absId, def, out)
    }

    /**
     * 平滑：每格＝（前＋自己×2＋後）÷4，頭尾不動。
     * 選同一列或同一欄時做一維；選到多列多欄時取外框，先縱向再橫向各做一次（外框內的格子都會算到）。
     * 算法照 STX，用單精度。
     */
    fun smooth(absId: Int, cells: Collection<Cell>): EditResult {
        val def = editable(absId) ?: return refuse(absId)
        outOfBounds(def, cells)?.let { return it }
        if (cells.size < 3) return EditResult.Refused("Select at least 3 cells")
        val cur = rows(absId)!!
        fun shown(r: Int, c: Int) = Convert.rawToPhysical(def, cur[r][c]).toFloat()
        val r0 = cells.minOf { it.row }
        val r1 = cells.maxOf { it.row }
        val c0 = cells.minOf { it.col }
        val c1 = cells.maxOf { it.col }
        val out = HashMap<Cell, Double>()
        if (r0 == r1 || c0 == c1) {
            val line = straightRun(cells) ?: return EditResult.Refused("Cells in one row or column must be consecutive")
            val v = FloatArray(line.size) { shown(line[it].row, line[it].col) }
            for (i in 1 until v.size - 1) {
                val x = (v[i - 1] + v[i] * 2f + v[i + 1]) / 4f
                if (x != v[i]) out[line[i]] = x.toDouble()
            }
            return write(absId, def, out)
        }
        val h = r1 - r0 + 1
        val w = c1 - c0 + 1
        val a = Array(h) { r -> FloatArray(w) { c -> shown(r0 + r, c0 + c) } }
        val vertical = Array(h) { r ->
            FloatArray(w) { c -> if (r != 0 && r != h - 1) (a[r - 1][c] + a[r][c] * 2f + a[r + 1][c]) / 4f else a[r][c] }
        }
        for (r in 0 until h) for (c in 0 until w) {
            val x = if (c != 0 && c != w - 1) (vertical[r][c - 1] + vertical[r][c] * 2f + vertical[r][c + 1]) / 4f else vertical[r][c]
            // 算出來和原本顯示值相同的格子（四個角、平坦區）不動，原始值原樣保留
            if (x != a[r][c]) out[Cell(r0 + r, c0 + c)] = x.toDouble()
        }
        return write(absId, def, out)
    }

    /** 直接設定一格的原始值。給開關、位元選項，或要保留超出定義範圍的值時用。 */
    fun setRaw(absId: Int, cell: Cell, raw: Long): EditResult {
        val def = editable(absId, needGain = false) ?: return refuse(absId)
        outOfBounds(def, listOf(cell))?.let { return it }
        if (raw < 0 || raw > def.rawMax) return EditResult.Refused("Raw value must be between 0 and ${def.rawMax}")
        val cur = rows(absId)!!
        if (cur[cell.row][cell.col] == raw) return EditResult.Done(0)
        val next = cur.map { it.copyOf() }
        next[cell.row][cell.col] = raw
        commit(absId, next)
        return EditResult.Done(1)
    }

    /**
     * 動態軸改完後整理成遞增：某格不大於前一格時，設成「前一格＋步進」，上限是 Max。和 STX 相同。
     * [length]：畫面上這條軸顯示幾格。要在編輯前先記下來，因為軸被改到不遞增時有效長度會跟著變短。
     */
    fun regularizeAxis(axisTableId: Int, length: Int): EditResult {
        val def = editable(axisTableId) ?: return refuse(axisTableId)
        val row = rows(axisTableId)!![0]
        if (length < 1 || length > row.size) return EditResult.Refused("Wrong axis length")
        val v = DoubleArray(length) { Convert.rawToPhysical(def, row[it]) }
        val out = HashMap<Cell, Double>()
        for (i in 0 until length - 1) {
            if (v[i] >= v[i + 1]) {
                v[i + 1] = minOf(def.max, v[i] + def.slew)
                out[Cell(0, i + 1)] = v[i + 1]
            }
        }
        return write(axisTableId, def, out)
    }

    /**
     * 編輯動態軸：對軸上的格子做一個動作，再整理成遞增；兩步算一次編輯，復原時一起回去。
     * [cols]：軸上第幾格（從 0 起）。[length]：畫面上這條軸顯示幾格。
     */
    fun editAxis(axisTableId: Int, length: Int, cols: Collection<Int>, op: AdjustOp, value: Double): EditResult {
        val mark = undoStack.size
        val first = adjust(axisTableId, cols.map { Cell(0, it) }, op, value)
        if (first is EditResult.Refused) return first
        val second = regularizeAxis(axisTableId, length)
        // 兩步都有改到時，合併成一筆復原紀錄（保留最早的那份內容）
        while (undoStack.size > mark + 1) undoStack.removeLast()
        if (second is EditResult.Refused) return second
        return EditResult.Done((first as EditResult.Done).changed + (second as EditResult.Done).changed)
    }

    /** 有幾張表用這張表當動態軸。改軸會同時影響這些表。 */
    fun axisUsers(axisTableId: Int): List<TableDef> {
        val key = "Dyna-$axisTableId-"
        return (definitions.tables.values).filter { it.scaleX.startsWith(key) || it.scaleY.startsWith(key) }
    }

    /** 這一格和檔案裡的內容是否不同（未存檔的修改）。 */
    fun isCellModified(absId: Int, row: Int, col: Int): Boolean {
        val w = working[absId] ?: return false
        val o = file.tables[absId]?.rows ?: return true
        return w[row][col] != o.getOrNull(row)?.getOrNull(col)
    }

    val hasChanges: Boolean get() = working.keys.any { isModified(it) }

    /**
     * 從另一份調校檔把原始值複製過來：整張表，或只複製指定的格子。
     * 兩邊這張表的儲存尺寸要相同。
     */
    fun copyFrom(other: Tune, absId: Int, cells: Collection<Cell>? = null): EditResult {
        val def = editable(absId, needGain = false) ?: return refuse(absId)
        val src = other.rows(absId) ?: return EditResult.Refused("The source does not have this table")
        val cur = rows(absId)!!
        if (src.size != cur.size || src.indices.any { src[it].size != cur[it].size }) return EditResult.Refused("Sizes differ")
        if (cells != null) outOfBounds(def, cells)?.let { return it }
        if (src.any { row -> row.any { it > def.rawMax } }) return EditResult.Refused("Source values exceed this table's cell width")
        val next = cur.map { it.copyOf() }
        var changed = 0
        if (cells == null) {
            for (r in next.indices) for (c in next[r].indices) if (next[r][c] != src[r][c]) { next[r][c] = src[r][c]; changed++ }
        } else {
            for (c in cells) if (next[c.row][c.col] != src[c.row][c.col]) { next[c.row][c.col] = src[c.row][c.col]; changed++ }
        }
        if (changed > 0) commit(absId, next)
        return EditResult.Done(changed)
    }

    /**
     * 整張換成外部來源的原始值（例如從 ECU 讀回）。尺寸要跟調校檔裡的一樣、每格不超過格寬，否則不換、回 false。
     * 跟檔案內容相同就當沒改。[undoable]＝true 時進復原紀錄（例如油圖頁的「全讀」），預設不進。
     */
    fun loadRows(absId: Int, next: List<LongArray>, undoable: Boolean = false): Boolean {
        val def = definition(absId) ?: return false
        val cur = file.tables[absId]?.rows ?: return false
        if (next.size != cur.size || next.indices.any { next[it].size != cur[it].size }) return false
        if (next.any { row -> row.any { it < 0 || it > def.rawMax } }) return false
        if (undoable && !sameRows(next, rows(absId)!!)) undoStack.add(absId to working[absId])
        if (sameRows(next, cur)) working.remove(absId) else working[absId] = next.map { it.copyOf() }
        return true
    }

    /** 這張表回到檔案裡的內容。可以復原。 */
    fun revert(absId: Int) {
        if (absId !in working) return
        undoStack.add(absId to working[absId])
        working.remove(absId)
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** 復原上一個編輯動作；回傳被復原的表號。 */
    fun undo(): Int? {
        val (id, before) = undoStack.removeLastOrNull() ?: return null
        if (before == null) working.remove(id) else working[id] = before
        return id
    }

    private fun editable(absId: Int, needGain: Boolean = true): TableDef? {
        val def = definition(absId) ?: return null
        if (def.isEeprom || rows(absId) == null) return null
        if (needGain && def.gain == 0f) return null
        val cur = rows(absId)!!
        if (cur.size != def.storedRows || cur.any { it.size != def.storedColumns }) return null
        return def
    }

    private fun refuse(absId: Int): EditResult {
        val def = definition(absId) ?: return EditResult.Refused("Table $absId is not in the definitions")
        if (def.isEeprom) return EditResult.Refused("${def.name} is EEPROM, not part of the tune")
        if (rows(absId) == null) return EditResult.Refused("${def.name} is not in the tune")
        if (def.gain == 0f) return EditResult.Refused("${def.name} has no scaling; only raw values can be edited")
        return EditResult.Refused("${def.name} does not match its definition size; cannot edit")
    }

    private fun outOfBounds(def: TableDef, cells: Collection<Cell>): EditResult.Refused? =
        if (cells.any { it.row !in 0 until def.storedRows || it.col !in 0 until def.storedColumns })
            EditResult.Refused("Selected cells are outside ${def.name}") else null

    /** 選取的格子是否排成一條連續的橫線或直線（至少 3 格）；是的話依序回傳。 */
    private fun straightRun(cells: Collection<Cell>): List<Cell>? {
        val set = cells.toSet()
        if (set.size < 3) return null
        val sameCol = set.all { it.col == set.first().col }
        val sameRow = set.all { it.row == set.first().row }
        val sorted = when {
            sameCol -> set.sortedBy { it.row }
            sameRow -> set.sortedBy { it.col }
            else -> return null
        }
        for (i in 1 until sorted.size) {
            val step = if (sameCol) sorted[i].row - sorted[i - 1].row else sorted[i].col - sorted[i - 1].col
            if (step != 1) return null
        }
        return sorted
    }

    /**
     * 把新的物理值寫進去。顯示值沒變的格子不動（原始值原樣保留）；
     * 有變的先夾在 Min～Max，再換成原始值。
     */
    private fun write(absId: Int, def: TableDef, values: Map<Cell, Double>): EditResult {
        if (values.values.any { it.isNaN() }) return EditResult.Refused("The computed value is invalid")
        val cur = rows(absId)!!
        var next: List<LongArray>? = null
        var changed = 0
        for ((cell, v) in values) {
            val raw = cur[cell.row][cell.col]
            // 先和顯示值比，再夾範圍：顯示值本身可能因為捨入而略超過 Max
            // （例：Max 0.996、顯示到整數時 0.5 顯示成 1），這種格子沒改就不能被夾回去
            val shown = Convert.rawToPhysical(def, raw)
            if (v == shown) continue
            val clamped = if (v > def.max) def.max else if (v < def.min) def.min else v
            if (clamped == shown) continue
            val newRaw = Convert.physicalToRaw(def, clamped)
            if (newRaw == raw) continue
            if (next == null) next = cur.map { it.copyOf() }
            next[cell.row][cell.col] = newRaw
            changed++
        }
        if (next != null) commit(absId, next)
        return EditResult.Done(changed)
    }

    private fun commit(absId: Int, next: List<LongArray>) {
        undoStack.add(absId to working[absId])
        working[absId] = next
    }

    private fun sameRows(a: List<LongArray>, b: List<LongArray>): Boolean =
        a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    // ---- 存檔 ----

    /**
     * 存檔後換掉檔案基準：同一個 Tune 留著，即時調校與工作副本接著用。
     * 剛存出去的內容變成「沒改過」；復原紀錄改成對新檔比，存檔前的值還原得回去。
     */
    fun rebase(next: TuneFile) {
        val old = file
        // 復原紀錄裡的 null 是指「當時的檔案內容」；換基準前先補成舊檔的值
        val fixed = ArrayList<Pair<Int, List<LongArray>?>>(undoStack.size)
        for ((id, before) in undoStack) {
            val at = before ?: old.tables[id]?.rows
            fixed.add(id to if (at != null && next.tables[id]?.rows?.let { sameRows(at, it) } == true) null else at)
        }
        // 工作副本只留跟新檔不同的表
        val kept = HashMap<Int, List<LongArray>>()
        for ((id, rows) in working) {
            val base = next.tables[id]?.rows
            if (base == null || !sameRows(rows, base)) kept[id] = rows
        }
        working.clear()
        working.putAll(kept)
        undoStack.clear()
        undoStack.addAll(fixed)
        file = next
    }

    /**
     * 目前內容轉回調校檔文字（未加密）。只替換改過的表與存檔時間，其餘原文照寫。
     * 表的寫法跟著原檔：原本每列結尾有逗號就保留逗號。
     * [lastSaveTime]：給了才更新；STX 的格式是 `yyyy-MM-dd HH:mm:ss`。
     */
    fun toXml(lastSaveTime: String? = null): String {
        class Patch(val start: Int, val end: Int, val text: String)
        val src = file.source
        val patches = ArrayList<Patch>()
        for (id in modifiedTables) {
            val el = file.tables[id]!!.element
            val original = src.substring(el.innerStart, el.innerEnd)
            val newline = if (original.contains("\r\n")) "\r\n" else "\n"
            val trailingComma = original.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.endsWith(",") ?: false
            val sb = StringBuilder(newline)
            for (row in working[id]!!) {
                sb.append(row.joinToString(","))
                if (trailingComma) sb.append(',')
                sb.append(newline)
            }
            patches.add(Patch(el.innerStart, el.innerEnd, sb.toString()))
        }
        if (lastSaveTime != null) {
            val el = file.root.child("LastSaveTime")!!
            val escaped = lastSaveTime.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            patches.add(Patch(el.innerStart, el.innerEnd, escaped))
        }
        if (patches.isEmpty()) return src
        patches.sortBy { it.start }
        val out = StringBuilder(src.length + 64)
        var at = 0
        for (p in patches) {
            out.append(src, at, p.start).append(p.text)
            at = p.end
        }
        out.append(src, at, src.length)
        return out.toString()
    }

    // ---- 檢查 ----

    /** 逐表對照主定義檔。只讀，不改任何內容。 */
    fun check(): TuneReport {
        val r = TuneReport()
        for (id in file.tables.keys) if (definition(id) == null) r.unknownTables.add(id)
        checkTables(definitions.tables.values, r, reportMissing = true)
        return r
    }

    /** 同樣的檢查，對象是調校檔附帶的 MGU 表。沒有附掛定義檔時回空的結果。 */
    fun checkAttached(): TuneReport {
        val r = TuneReport()
        val defs = attachedDefinitions?.tables?.values?.filter { file.tables[it.absId]?.attached == true } ?: emptyList()
        checkTables(defs, r, reportMissing = false)
        return r
    }

    private fun checkTables(all: Collection<TableDef>, r: TuneReport, reportMissing: Boolean) {
        for (def in all) {
            val raw = file.tables[def.absId]
            val rows = rows(def.absId)
            if (raw == null || rows == null) { if (reportMissing) r.missingTables.add(def.absId); continue }
            if (raw.name != def.name) r.nameDiffers.add(def.absId)
            if (def.isEeprom) { r.eepromTables++; continue }
            if (rows.size != def.storedRows || rows.any { it.size != def.storedColumns }) {
                r.sizeMismatch.add(def.absId); continue
            }
            r.tablesChecked++
            if (rows.any { row -> row.any { it > def.rawMax } }) r.rawOverCell.add(def.absId)
            for ((key, stored) in listOf(def.scaleX to def.storedColumns, def.scaleY to def.storedRows)) {
                when (val a = axis(key)) {
                    AxisLookup.None -> {}
                    is AxisLookup.Missing -> r.axisMissing.add(def.absId)
                    is AxisLookup.Found -> if (a.axis.editable) {
                        r.dynamicAxes++
                        if (a.axis.values.size < stored) r.dynamicAxesShorter++
                        if (a.axis.values.size > stored) r.dynamicAxisLonger.add(def.absId)
                    } else {
                        r.fixedAxes++
                        if (a.axis.values.size != stored) r.fixedAxisLengthDiffers.add(def.absId)
                    }
                }
            }
            if (def.gain == 0f) { r.gainZeroTables++; continue }
            var out = false
            var bad = false
            for (row in rows) for (v in row) {
                r.cells++
                if (Convert.isOutOfRange(def, v)) { r.cellsOutOfRange++; out = true }
                else if (!Convert.isReversible(def, v)) { r.cellsNotReversible++; bad = true }
            }
            if (out) r.tablesOutOfRange.add(def.absId)
            if (bad) r.tablesNotReversible.add(def.absId)
        }
    }
}
