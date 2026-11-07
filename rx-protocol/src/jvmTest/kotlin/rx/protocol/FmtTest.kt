package rx.protocol

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fmt 要跟 Java String.format 逐字一致（Android 與 iOS 錄出的 loga 才會相同）。 */
class FmtTest {
    @Test
    fun fixedMatchesJava() {
        val r = Random(20261017)
        val samples = buildList {
            addAll(listOf(0.0, -0.0, 0.5, 1.5, 2.5, -2.5, 0.125, 0.15, 0.25, 0.35, 1e-7, 123456.789, 9.995, 99.95, -0.04, 1e21, 5e-5))
            repeat(200_000) {
                val scale = listOf(1.0, 2.0, 4.0, 10.0, 100.0, 1000.0, 65536.0)[r.nextInt(7)]
                add((r.nextLong(-2_000_000, 2_000_000) / scale) + listOf(0.0, 0.0, -32.5, 0.1)[r.nextInt(4)])
                add(r.nextDouble(-1e6, 1e6))
            }
        }
        var bad = 0
        for (v in samples) for (d in 0..6) {
            val want = "%.${d}f".format(Locale.US, v)
            val got = Fmt.fixed(v, d)
            if (want != got) { if (bad < 15) println("MISMATCH v=$v d=$d java=$want fmt=$got"); bad++ }
        }
        assertEquals(0, bad, "mismatches")
    }

    @Test
    fun printfSubset() {
        assertEquals("a 5 b x", Fmt.format("a %d b %s", 5, "x"))
        assertEquals("x 5", Fmt.format("%2\$s %1\$d", 5, "x"))
        assertEquals("1.5 MB", Fmt.format("%.1f MB", 1.45))
        assertEquals("12,345 rpm 100%", Fmt.format("%,d rpm 100%%", 12345))
    }
}
