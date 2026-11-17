package xb.link

import rx.protocol.EcuPlatform

/**
 * 會讓 ECU 自己改校正表（油表）的學習開關。值 0＝關（XTune 互鎖關這些功能也是寫 0，見 xb-calui 的 Interlock）。
 * [LiveTuning.restore] 先全部關掉、確認關了才還原其他表，最後才寫回備份的值。
 * - 超級自動調校 Pro-AT：Super XX 是 1500、SxS 是 1580（Super X Pro 的 1500 是別的設定，不算）。
 *   Super XX 上的自動調校 14 跟著 1500 變，所以 Pro-AT 排在 14 前面：先關 1500，14 讀起來就是關；寫回時 14 也跟著回來。
 * - 寬域閉迴路 1059：開著時 ECU 修正燃油表（Pro-AT 頁開著它就定時重讀油表）。
 * - 自動調校 14：開著時 ECU 就地修正燃油表。
 */
object Learning {
    /** 超級自動調校的開關：Super XX 1500、SxS 1580；其他平台沒有（Super X Pro 的 1500 是別的設定）。 */
    fun proAt(platform: EcuPlatform?): Int? = when (platform) {
        EcuPlatform.SUPER_XX -> 1500
        EcuPlatform.SXS -> 1580
        else -> null
    }

    /** 還原前要關的學習開關，照關的順序；定義檔裡沒有的表由 [LiveTuning.restore] 略過。 */
    fun tables(platform: EcuPlatform?): List<Int> = listOfNotNull(proAt(platform), 1059, 14)
}
