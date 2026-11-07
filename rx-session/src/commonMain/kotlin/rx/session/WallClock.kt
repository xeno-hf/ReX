package rx.session

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path

/** 牆鐘時間（epoch ms）與本地時間格式化（取代 SimpleDateFormat）。 */
object WallClock {
    fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    /** 支援 yyyy MM dd HH mm ss 六個欄位，其餘字元原樣輸出。 */
    fun format(ms: Long, pattern: String): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
        fun p2(n: Int) = n.toString().padStart(2, '0')
        return pattern
            .replace("yyyy", t.year.toString().padStart(4, '0'))
            .replace("MM", p2(t.monthNumber))
            .replace("dd", p2(t.dayOfMonth))
            .replace("HH", p2(t.hour))
            .replace("mm", p2(t.minute))
            .replace("ss", p2(t.second))
    }
}

/** 檔案 SHA-256（小寫十六進位）。 */
expect fun sha256Hex(path: Path): String
