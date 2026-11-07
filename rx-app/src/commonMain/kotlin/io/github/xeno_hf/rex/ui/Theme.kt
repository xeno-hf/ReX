package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import io.github.xeno_hf.rex.res.R
import io.github.xeno_hf.rex.res.Res
import io.github.xeno_hf.rex.res.oxanium_bold
import io.github.xeno_hf.rex.res.oxanium_extrabold
import io.github.xeno_hf.rex.res.oxanium_medium
import io.github.xeno_hf.rex.res.oxanium_semibold

/**
 * 配色三套：
 * - Emerald（預設，暗）：黑底＋白＋翡翠綠紋路，藍與金點綴
 * - Ruby（暗）：緋紅＋金飾，翠綠點綴
 * - Topaz（淺）：象牙白＋金飾＋翠綠點綴，白天陽光下看得清
 * 三套都用翡翠綠當「正常／已連線」色。
 */
data class RxPalette(
    val id: String,
    val light: Boolean,
    val bg: Color,
    val bg2: Color,
    val card: Color,
    val cardHi: Color,
    val line: Color,
    val track: Color,
    val text: Color,
    val muted: Color,
    /** 代表色（填色用）。 */
    val brand: Color,
    /** 代表色漸層的另一端。 */
    val brand2: Color,
    /** 代表色當文字用（淺色底上的金要壓深才讀得到）。 */
    val brandInk: Color,
    val onBrand: Color,
    /** 另一位的代表色（點綴）。 */
    val partner: Color,
    /** 核心水晶綠：正常／已連線。 */
    val accent: Color,
    val warn: Color,
    val danger: Color,
    val traces: List<Color>,
)

val Ruby = RxPalette(
    id = "ruby", light = false,
    bg = Color(0xFF110A0B), bg2 = Color(0xFF1E0D10),
    card = Color(0xFF1D1214), cardHi = Color(0xFF2A161A),
    line = Color(0xFF45242A), track = Color(0xFF301A1E),
    text = Color(0xFFFBEFEE), muted = Color(0xFFB39498),
    brand = Color(0xFFE5323F), brand2 = Color(0xFFFF6A55), brandInk = Color(0xFFFF5A64), onBrand = Color(0xFF2A0006),
    partner = Color(0xFFE8B54A),
    accent = Color(0xFF2FD9A8),
    warn = Color(0xFFE8B54A), danger = Color(0xFFFF2E4D),
    traces = listOf(Color(0xFF2FD9A8), Color(0xFF5AB0FF), Color(0xFFE8B54A), Color(0xFFE879F9), Color(0xFFFF6B6B), Color(0xFFA3E635)),
)

val Topaz = RxPalette(
    id = "topaz", light = true,
    bg = Color(0xFFFCFAF4), bg2 = Color(0xFFF4ECD8),
    card = Color(0xFFFFFFFF), cardHi = Color(0xFFFFF6DC),
    line = Color(0xFFE6DABB), track = Color(0xFFEDE1C4),
    text = Color(0xFF1E1B16), muted = Color(0xFF7D7258),
    brand = Color(0xFFD9A021), brand2 = Color(0xFFF5D27A), brandInk = Color(0xFF8A5E00), onBrand = Color(0xFF2B1F00),
    partner = Color(0xFF14B892),
    accent = Color(0xFF0E9F7E),
    warn = Color(0xFFCC7A00), danger = Color(0xFFD7263D),
    traces = listOf(Color(0xFF0E9F7E), Color(0xFF2F6FDB), Color(0xFFC98A00), Color(0xFFB03CC4), Color(0xFFD7263D), Color(0xFF5C9A0C)),
)

val Emerald = RxPalette(
    id = "emerald", light = false,
    bg = Color(0xFF0A0C0D), bg2 = Color(0xFF0D1614),
    card = Color(0xFF131A19), cardHi = Color(0xFF1A2624),
    line = Color(0xFF283A37), track = Color(0xFF1D2B28),
    text = Color(0xFFF3F7F6), muted = Color(0xFF8CA59F),
    brand = Color(0xFF22D69A), brand2 = Color(0xFF7FE8D0), brandInk = Color(0xFF48EBB5), onBrand = Color(0xFF00241A),
    partner = Color(0xFF4F6BEA),
    accent = Color(0xFF22D69A),
    warn = Color(0xFFE9B949), danger = Color(0xFFFF3D55),
    traces = listOf(Color(0xFF22D69A), Color(0xFF6E8BFF), Color(0xFFE9B949), Color(0xFFE879F9), Color(0xFFFF6B6B), Color(0xFFA3E635)),
)

/** 目前配色。讀取會訂閱 Compose 狀態，換主題時全部畫面自動重繪。 */
object RxColors {
    var palette by mutableStateOf(Emerald)
    val bg get() = palette.bg
    val bg2 get() = palette.bg2
    val card get() = palette.card
    val cardHi get() = palette.cardHi
    val line get() = palette.line
    val track get() = palette.track
    val text get() = palette.text
    val muted get() = palette.muted
    val brand get() = palette.brand
    val brand2 get() = palette.brand2
    val brandInk get() = palette.brandInk
    val partner get() = palette.partner
    val accent get() = palette.accent
    val warn get() = palette.warn
    val danger get() = palette.danger
    /** 示波器多軌色（色盲友善的分散色相）。 */
    val traces get() = palette.traces
}

/** 儀表數字字型：Oxanium（OFL），數字等寬、方正，跳動時不晃。字型資源要在 Compose 裡載入，由 [RxTheme] 填入。 */
val Digits: FontFamily get() = DigitsHolder.family

private object DigitsHolder { var family: FontFamily = FontFamily.Default }

enum class ThemeChoice(val label: Int) {
    EMERALD(R.string.theme_emerald), RUBY(R.string.theme_ruby), TOPAZ(R.string.theme_topaz), SYSTEM(R.string.theme_system);

    companion object {
        /** 讀設定值。 */
        fun parse(s: String?): ThemeChoice = when (s) {
            else -> entries.firstOrNull { it.name == s } ?: EMERALD
        }
    }
}

@Composable
fun RxTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val p = when (choice) {
        ThemeChoice.EMERALD -> Emerald
        ThemeChoice.RUBY -> Ruby
        ThemeChoice.TOPAZ -> Topaz
        ThemeChoice.SYSTEM -> if (isSystemInDarkTheme()) Emerald else Topaz
    }
    RxColors.palette = p
    DigitsHolder.family = FontFamily(
        Font(Res.font.oxanium_medium, FontWeight.Medium),
        Font(Res.font.oxanium_semibold, FontWeight.SemiBold),
        Font(Res.font.oxanium_bold, FontWeight.Bold),
        Font(Res.font.oxanium_extrabold, FontWeight.ExtraBold),
    )
    PlatformSystemBars(p)
    val scheme = if (p.light) lightColorScheme(
        primary = p.brand, onPrimary = p.onBrand, secondary = p.accent, background = p.bg, surface = p.card,
        surfaceVariant = p.line, onSurface = p.text, onSurfaceVariant = p.muted, onBackground = p.text, error = p.danger,
        surfaceContainer = p.card, surfaceContainerHigh = p.card, secondaryContainer = p.cardHi, onSecondaryContainer = p.brandInk,
        outline = p.line,
    ) else darkColorScheme(
        primary = p.brand, onPrimary = p.onBrand, secondary = p.accent, background = p.bg, surface = p.card,
        surfaceVariant = p.line, onSurface = p.text, onSurfaceVariant = p.muted, onBackground = p.text, error = p.danger,
        surfaceContainer = p.card, surfaceContainerHigh = p.card, secondaryContainer = p.cardHi, onSecondaryContainer = p.brandInk,
        outline = p.line,
    )
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        // Surface 提供預設內容色（否則 Text 在非 Surface 容器裡會是黑字）
        Surface(color = p.bg, contentColor = p.text, content = content)
    }
}
