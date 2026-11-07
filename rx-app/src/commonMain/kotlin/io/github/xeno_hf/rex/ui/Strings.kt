package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rx.protocol.Fmt
import io.github.xeno_hf.rex.res.StringTables

/**
 * 介面字串（四種語言）。表由 build 時從 Android 的 strings.xml 產生（io.github.xeno_hf.rex.res.R）。
 * 語言跟隨系統偏好；App 內不設切換（Android 13+／iOS 由系統的「App 語言」指定）。
 */
object Strings {
    enum class Lang { EN, ZH_HANT, ZH_HANS, JA }

    var lang by mutableStateOf(Lang.EN)
        private set

    /** 依系統偏好語言清單（BCP 47，例 zh-Hant-TW、zh-CN、ja-JP）挑第一個支援的。 */
    fun setLanguage(tags: List<String>) { lang = pick(tags) }

    fun pick(tags: List<String>): Lang {
        for (raw in tags) {
            val t = raw.replace('_', '-').lowercase()
            val parts = t.split('-')
            when (parts.first()) {
                "zh" -> return when {
                    "hans" in parts -> Lang.ZH_HANS
                    "hant" in parts -> Lang.ZH_HANT
                    parts.any { it in setOf("cn", "sg", "my") } -> Lang.ZH_HANS
                    else -> Lang.ZH_HANT
                }
                "ja" -> return Lang.JA
                "en" -> return Lang.EN
            }
        }
        return Lang.EN
    }

    private fun table(l: Lang) = when (l) {
        Lang.EN -> StringTables.en
        Lang.ZH_HANT -> StringTables.zhHant
        Lang.ZH_HANS -> StringTables.zhHans
        Lang.JA -> StringTables.ja
    }

    fun get(id: Int, vararg args: Any?): String {
        val s = table(lang)[id] ?: StringTables.en[id] ?: "?"
        return if (args.isEmpty()) s else Fmt.format(s, *args)
    }
}

/** 取代 Android 的 stringResource：讀 [Strings.lang]，換語言時自動重繪。 */
@Composable
fun stringResource(id: Int, vararg args: Any?): String = Strings.get(id, *args)
