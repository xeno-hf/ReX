package xb.tune

/**
 * 油圖頁「步進值」的預設（XTune ：每張表各自記在 `CalStep_<表名>`，
 * 沒記過時表 133＝1、54＝0.1、61＝4，其他＝2）。＋／− 一次加減這麼多（顯示值單位）。
 */
object CalStep {
    fun default(absId: Int): Double = when (absId) {
        133 -> 1.0
        54 -> 0.1
        61 -> 4.0
        else -> 2.0
    }

    /** 存設定用的鍵（同 XTune）。 */
    fun key(def: TableDef): String = "CalStep_" + def.name
}
