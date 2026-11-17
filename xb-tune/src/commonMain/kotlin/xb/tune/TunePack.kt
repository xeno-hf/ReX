package xb.tune

/**
 * 調校包（給 ReX 即時調校）：XB 用使用者自己的金鑰把定義檔解開，連同刻度、名稱說明、官方原廠檔一起交給手機。
 * 手機不用金鑰、不用解定義檔，用這份包建出跟 XB 一模一樣的 [Tune]（同一份 xb-tune 程式碼），表的值再從 ECU 讀。
 * 內容是一組文字檔（存成 ZIP，見 XB 的 xb-sync TunePackZip）：
 * ```
 * manifest key=value：format、platform、map_type、c、table_id、vehicle、serial（control.xml 的序號）、attached_serial（有附掛才有）、app
 * control.xml ControlFile_<platform>（已解開）
 * scales.xml ScaleData
 * group.xml short.xml desc.xml 名稱說明（GroupDes／TabShortName／ParaAndVarDes，可省略）
 * attached/control.xml attached/group.xml … 附掛 MGU 表（X2 的 SYMGU，有才有）
 * template.xml 官方原廠調校檔（已解開）：只拿它的結構（版本、表的尺寸、附掛區段），值以 ECU 讀回為準
 * ```
 */
class TunePack(val manifest: Map<String, String>, private val files: Map<String, String>) {
    class Invalid(message: String) : Exception(message)

    val platform: Platform = manifest["platform"]?.let { p -> Platform.entries.firstOrNull { it.definition == p } }
        ?: throw Invalid("Unknown platform ${manifest["platform"]}")
    val mapType: String get() = manifest["map_type"] ?: ""
    val cCode: Int? get() = manifest["c"]?.toIntOrNull()
    val tableId: String get() = manifest["table_id"] ?: ""
    /** 官方清單上的車款名稱（給人看）。 */
    val vehicle: String? get() = manifest["vehicle"]?.takeIf { it.isNotBlank() }

    init {
        if (manifest["format"] != FORMAT) throw Invalid("Not a tune pack (format ${manifest["format"]})")
        for (n in listOf(CONTROL, SCALES, TEMPLATE)) if (files[n] == null) throw Invalid("Tune pack is missing $n")
    }

    fun file(name: String): String? = files[name]
    val names: Set<String> get() = files.keys

    class Opened(val tune: Tune, val labels: Labels, val pack: TunePack)

    /** 建出 [Tune]：表的值是原廠檔的，連上 ECU 後用 `EcuSync.readAll` 讀回、`Tune.loadRows` 換成 ECU 的值。 */
    fun open(): Opened {
        val tf = try { TuneFile.parse(files.getValue(TEMPLATE)) } catch (e: Exception) { throw Invalid("Template is not a tune file: ${e.message}") }
        // 清單跟範本要一致：平台、調校檔類型、C、車種代號（檔名只是從清單算出來的，不能當證據）
        val mt = tf.mapType.trim().uppercase()
        if (mt !in platform.mapTypes) throw Invalid("Template type $mt does not belong to ${platform.definition}")
        if (!mapType.trim().equals(mt, ignoreCase = true)) throw Invalid("Manifest type $mapType differs from the template ($mt)")
        if (manifest["c"] != null && cCode != tf.cCode) throw Invalid("Manifest C${manifest["c"]} differs from the template C${tf.cCode}")
        if (tableId.trim() != tf.tableId.trim()) throw Invalid("Manifest Table ID $tableId differs from the template ${tf.tableId}")
        val defs = Definitions.parse(files.getValue(CONTROL))
        // 清單記的定義檔序號要跟包裡的控制檔一致（換了檔、拼錯包就不用）
        val sn = manifest["serial"] ?: throw Invalid("Manifest has no definitions serial")
        if (sn != defs.serial) throw Invalid("Manifest serial $sn differs from the definitions (${defs.serial})")
        val scales = ScaleData.parse(files.getValue(SCALES))
        val attached = if (tf.symgu != null) files[ATTACHED + CONTROL]?.let { Definitions.parse(it, TuneFile.SYMGU_ID_OFFSET) } else null
        if (attached != null && manifest["attached_serial"] != attached.serial) {
            throw Invalid("Manifest attached serial ${manifest["attached_serial"]} differs from the attached definitions (${attached.serial})")
        }
        val labels = Labels.parse(files[GROUP], files[SHORT], files[DESC]) +
            (if (attached != null) Labels.parse(files[ATTACHED + GROUP], files[ATTACHED + SHORT], files[ATTACHED + DESC], TuneFile.SYMGU_ID_OFFSET) else Labels.EMPTY)
        return Opened(Tune(defs, scales, tf, attached), labels, this)
    }

    companion object {
        const val FORMAT = "rx-tunepack-1"
        const val MANIFEST = "manifest"
        const val CONTROL = "control.xml"
        const val SCALES = "scales.xml"
        const val GROUP = "group.xml"
        const val SHORT = "short.xml"
        const val DESC = "desc.xml"
        const val ATTACHED = "attached/"
        const val TEMPLATE = "template.xml"

        fun parseManifest(text: String): Map<String, String> = text.lines().mapNotNull { l ->
            val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.toMap()

        fun writeManifest(m: Map<String, String>): String = m.entries.joinToString("") { (k, v) -> "$k=${v.replace('\n', ' ')}\n" }

        /** 從一組檔案（ZIP 解開後）建包。 */
        fun of(files: Map<String, String>): TunePack {
            val m = parseManifest(files[MANIFEST] ?: throw Invalid("Tune pack has no manifest"))
            return TunePack(m, files - MANIFEST)
        }
    }
}
