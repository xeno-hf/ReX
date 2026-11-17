package rx.session

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path

/** 牆鐘時間（epoch ms）與本地時間格式化（取代 SimpleDateFormat）。 */
object WallClock {
    fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    /** ISO 8601，帶本地時區的位移（例 2026-11-16T17:48:09+08:00）。 */
    fun iso(ms: Long): String {
        val inst = Instant.fromEpochMilliseconds(ms)
        val off = TimeZone.currentSystemDefault().offsetAt(inst).totalSeconds
        val sign = if (off < 0) "-" else "+"
        val a = kotlin.math.abs(off)
        return format(ms, "yyyy-MM-ddTHH:mm:ss") + sign + (a / 3600).toString().padStart(2, '0') + ":" + (a / 60 % 60).toString().padStart(2, '0')
    }

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
