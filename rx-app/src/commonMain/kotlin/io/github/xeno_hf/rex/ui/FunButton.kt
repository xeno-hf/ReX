package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R
import rx.protocol.DashFn

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 多功能鈕（XTune MultiFunButton＋「Config of Shortcut Button」對話框）。
 * 功能 ID／標籤照原版：241 AT、242 Pit、243 LC、244 TC、247 EB；兩顆不能選同一個。
 * 短按寫 ECU 開關（SDO，見 rx.protocol.DashFn；只限儀表功能），長按換功能。
 * 沒登入時 MAP／Pit／LC 能切，AT／TC／EB 顯示「功能限制」；AT 開啟前要再確認一次。
 */
enum class FunKind(val id: Int, val short: String, val title: String, val fn: DashFn) {
    AT(241, "AT", "Auto Tune", DashFn.AT),
    PIT(242, "Pit", "Pit Limit", DashFn.PIT),
    LC(243, "LC", "Launch Control", DashFn.LC),
    TC(244, "TC", "TCS", DashFn.TC),
    EB(247, "EB", "eBoost", DashFn.EB);

    companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AT }
}

enum class EbStatus(val label: String) { Ready("eBoost Ready"), Running("eBoost Running"), Charging("eBoost Charging") }

/** [on] 功能開著（null＝這輪收不到）、[lit] 整顆填色、[note] 按鈕下的小字。 */
data class FunState(val on: Boolean?, val lit: Boolean, val note: String?)

/**
 * AT／TC／EB 的開關從 ECU 讀回（[switchOn]，原版讀的也是 ECU 上的開關，物件見 [DashFn]）；還沒讀到（沒登入、ECU 拒讀）才退回看即時通道：
 * - AT：退回看 Fuel5.WBO2_AT_En
 * - Pit／LC：IOModule_Indicator_Status #973 的 bit4／bit2（同原版）
 * - TC：作動＝TCStatus.TCCtrActEn（原版 TCS 燈 icon_tcs_imgv 看的 -1252＝#125 bit2）。
 *   TCStatus.TCEnable 不是開關：實車開關開著，靜止時它是 0，騎乘中在 0／1 之間來回跳。只有還沒讀到開關時才退回看它。
 * - EB：開著（或還沒讀到）顯示三態（見 [DashData.eBoostStatus]）：Ready／Charging 維持開啟的亮框，只有 Running 整顆填色；
 *   讀到關著就顯示 OFF，除非即時通道說正在作動。
 *   即時通道沒有對應的開關：MGU_Mode4.MGU_eBoost_SW 在一趟實車紀錄裡整趟是 0，eBoost 卻有 26% 時間在作動。
 */
fun funState(k: FunKind, d: DashData, switchOn: Boolean? = null): FunState {
    fun onOff(on: Boolean?) = when (on) { true -> "ON"; false -> "OFF"; null -> "—" }
    return when (k) {
        FunKind.AT -> (switchOn ?: d.bit("Fuel5.WBO2_AT_En")).let { FunState(it, false, onOff(it)) }
        FunKind.PIT -> d.ioBit(4).let { FunState(it, false, onOff(it)) }
        FunKind.LC -> d.ioBit(2).let { FunState(it, false, onOff(it)) }
        FunKind.TC -> {
            val on = switchOn ?: d.bit("TCStatus.TCEnable")
            val act = d.bit("TCStatus.TCCtrActEn") == true
            FunState(on, act, if (act) "ACTIVE" else onOff(on))
        }
        FunKind.EB -> {
            val st = d.eBoostStatus
            if (switchOn == false && st != EbStatus.Running) return FunState(false, false, "OFF")
            val note = when (st) { EbStatus.Running -> "RUN"; EbStatus.Ready -> "READY"; EbStatus.Charging -> "CHARGE"; null -> if (switchOn == true) "ON" else "—" }
            FunState(switchOn ?: st?.let { true }, st == EbStatus.Running, note)
        }
    }
}

/** 第 [slot] 顆（0＝A、1＝B）多功能鈕。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FunButton(slot: Int, d: DashData, modifier: Modifier = Modifier, labelSize: TextUnit = 28.sp) {
    val repo = LocalRepo.current
    val ids by repo.funButtons.collectAsState()
    val loggedIn by repo.ecuLoggedIn.collectAsState()
    val kind = FunKind.of(ids[slot])
    val switches by repo.dashSwitch.collectAsState()
    val st = funState(kind, d, switches[kind.fn])
    var pick by remember { mutableStateOf(false) }
    var confirmAt by remember { mutableStateOf(false) }
    if (confirmAt) AlertDialog(
        onDismissRequest = { confirmAt = false },
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.at_confirm_title)) },
        text = { Text(stringResource(R.string.at_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmAt = false; repo.pressFun(DashFn.AT, kind.title, on = true) }) { Text(stringResource(R.string.at_confirm_ok)) } },
        dismissButton = { TextButton(onClick = { confirmAt = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (pick) FunPicker(kind, FunKind.of(ids[1 - slot]), onPick = { repo.setFunButton(slot, it.id); pick = false }, onDismiss = { pick = false })

    // 開著＝亮框，作動＝整顆填色；兩者都用主題色，TC 和 EB 同一套。
    val hot = RxColors.brand
    val on = st.on == true
    val lit = st.lit
    val shape = RoundedCornerShape(14.dp)
    val fill = when {
        lit -> Brush.verticalGradient(listOf(hot, hot.copy(alpha = 0.78f)))
        on -> Brush.verticalGradient(listOf(RxColors.cardHi, RxColors.brand.copy(alpha = 0.18f)))
        else -> SolidColor(RxColors.card)
    }
    val ink = when {
        lit -> RxColors.palette.onBrand
        on -> RxColors.text
        else -> RxColors.muted.copy(alpha = 0.55f)
    }
    Box(
        modifier.clip(shape).background(fill)
            .border(if (on || st.lit) 2.dp else 1.5.dp, if (st.lit) hot else if (on) RxColors.brandInk else RxColors.muted.copy(alpha = 0.4f), shape)
            .combinedClickable(
                onClick = {
                    // 開／關方向以 ECU 讀回的值為準（Session.setDash），不看畫面上的即時通道。
                    // AT：畫面顯示沒開時要先確認；已開著（要關）就直接送。
                    if (kind == FunKind.AT && st.on != true && loggedIn) confirmAt = true
                    else repo.pressFun(kind.fn, kind.title)
                },
                onLongClick = { pick = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(kind.short, style = digits(labelSize, FontWeight.ExtraBold, italic = true), color = ink)
            st.note?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (st.lit) ink else RxColors.muted) }
        }
    }
}

/** 長按換功能（XTune dialog_config_multibutton_layout：另一顆已用的反灰不能選）。 */
@Composable
private fun FunPicker(current: FunKind, taken: FunKind, onPick: (FunKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.fun_dialog_title)) },
        text = {
            Column {
                FunKind.entries.forEach { k ->
                    val used = k == taken
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (k == current) RxColors.brand.copy(alpha = 0.15f) else Color.Transparent)
                            .then(if (used) Modifier else Modifier.clickable { onPick(k) })
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val c = if (used) RxColors.muted.copy(alpha = 0.45f) else RxColors.text
                        Text(k.short, style = digits(20.sp, FontWeight.ExtraBold, italic = true), color = c, modifier = Modifier.width(52.dp))
                        Text(k.title, color = c, modifier = Modifier.weight(1f))
                        if (used) Text(stringResource(R.string.fun_in_use), fontSize = 11.sp, color = RxColors.muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
