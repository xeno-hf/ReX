package rx.session

/** 配對碼的格式（電腦顯示、手機輸入共用）：16 個字，大小寫、空白、連字號都不算。 */
object SyncCode {
    /** 16 個字（約 79 bits），假的電腦拿到手機的回應也沒辦法離線猜出來。 */
    const val LENGTH = 16

    /** 使用者輸入（可以帶空白、連字號、小寫）→ 只留英數、轉大寫。 */
    fun normalize(text: String): String = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    /** 輸入的字數剛好是一組配對碼。 */
    fun complete(text: String): Boolean = normalize(text).length == LENGTH
}
