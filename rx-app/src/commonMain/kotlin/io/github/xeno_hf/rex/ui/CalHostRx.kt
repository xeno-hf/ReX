package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.xeno_hf.rex.Live
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.Tuning
import io.github.xeno_hf.rex.calCommandAllowed
import xb.calui.CalColors
import xb.calui.CalHost
import xb.calui.CalText
import xb.link.LiveTuning
import xb.tune.CalCommands
import xb.tune.Tune

/**
 * 功能專頁（xb-calui，跟 XB 桌面版同一份畫面）在 ReX 的宿主：調校檔＝開始調校時讀回的 ECU 內容，
 * 寫入走 [Tuning]（同一份 LiveTuning），命令與廠商訊框走 ReX [rx.session.Session] 的寫入閘門（清單是 xb-tune 的 [CalCommands]）。
 * 即時變數用監控串流；頻道組裡沒有的，[Repo.calWatch] 臨時加進頻道表。
 */
@Composable
internal fun rememberRxCalHost(work: Tuning.Work): CalHost {
    val repo = LocalRepo.current
    val rev = repo.tuning.revision.collectAsState()
    val states = work.live.states.collectAsState()
    val live = repo.live.collectAsState()
    return remember(work) { RxCalHost(repo, work, rev, states, live) }
}

internal fun rxCalColors() = CalColors(
    bg = RxColors.bg, card = RxColors.card, line = RxColors.line, text = RxColors.text, muted = RxColors.muted,
    brand = RxColors.brand, onBrand = Color.White, accent = RxColors.accent, warn = RxColors.warn,
    danger = RxColors.danger, ok = RxColors.accent, digits = Digits,
)

private fun calLangTag() = when (Strings.lang) { Strings.Lang.ZH_HANT -> "zh-TW"; Strings.Lang.ZH_HANS -> "zh-CN"; Strings.Lang.JA -> "ja-JP"; else -> "en-US" }

private class RxCalHost(
    private val repo: Repo,
    private val work: Tuning.Work,
    private val rev: State<Int>,
    private val states: State<Map<Int, LiveTuning.TableState>>,
    private val liveRound: State<Live?>,
) : CalHost {
    private val session get() = work.session
    override val tune: Tune get() = work.tune
    override val node: Int get() = session.node
    override val cCode: Int? get() = work.tune.file.cCode
    override val fwCode: Int? get() = null
    override val lang: String get() = calLangTag()
    override val online: Boolean get() = repo.tuning.work === work
    override val revision: Int get() = rev.value + states.value.hashCode()

    override fun has(absId: Int): Boolean =
        tune.definition(absId) != null && tune.rows(absId) != null && absId !in work.unreadable

    override fun writable(absId: Int): Boolean = has(absId) && work.live.writable(absId)
    override fun status(absId: Int): LiveTuning.TableState? = states.value[absId]
    override fun held(absId: Int): Boolean = absId in work.live.held.value
    override fun edited(absId: Int) = repo.tuning.edited(absId)

    override suspend fun readTables(ids: List<Int>): String? {
        // 每張都讀（一張失敗不跳過下一張），錯誤合在一起回報
        val errors = ids.mapNotNull { id ->
            try { repo.tuning.readTable(id); null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { "${tableTitle(id)}: ${e.message}" }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    override suspend fun writeTables(ids: List<Int>): String? {
        for (id in ids) {
            repo.tuning.writeTable(id)
            when (val st = work.live.states.value[id]) {
                null, LiveTuning.TableState.Synced -> Unit
                else -> return "${tableTitle(id)}: $st"
            }
        }
        return null
    }

    override suspend fun command(index: Int, sub: Int, value: Long, bytes: Int): String? {
        if (!online) return CalText.get("cal_need_online", lang)
        if (!CalCommands.writeAllowed(index, sub, value, bytes)) return "command not allowed"
        val r = session.calCommand(CalCommands.sdoFrame(index, sub, value, bytes))
        repo.log("Cal command 0x${index.toString(16)}/$sub=0x${value.toString(16)}: ${r ?: "ACK"}")
        return r
    }

    override suspend fun commandThenRead(index: Int, sub: Int, value: Long, bytes: Int, ids: List<Int>, settleMs: Long): LiveTuning.CommandRead {
        if (!online) return LiveTuning.CommandRead.notSent(CalText.get("cal_need_online", lang))
        if (!CalCommands.writeAllowed(index, sub, value, bytes)) return LiveTuning.CommandRead.notSent("command not allowed")
        return repo.tuning.commandThenRead(work, CalCommands.sdoFrame(index, sub, value, bytes), ids, settleMs)
    }

    override suspend fun readObject(index: Int, sub: Int): Result<ByteArray> {
        if (!online) return Result.failure(IllegalStateException(CalText.get("cal_need_online", lang)))
        if (!CalCommands.readAllowed(index, sub)) return Result.failure(IllegalArgumentException("read not allowed"))
        return session.readObject(index, sub, block = false).map { it.first }
    }

    override suspend fun raw(canId: Int, data: ByteArray, reply: Int?, count: Int, timeoutMs: Long, match: (ByteArray) -> Boolean): Result<List<ByteArray>> {
        if (!online) return Result.failure(IllegalStateException(CalText.get("cal_need_online", lang)))
        if (!calCommandAllowed(rx.protocol.Frame(canId, data))) return Result.failure(IllegalArgumentException("frame not allowed"))
        return runCatching { session.calRaw(canId, data, reply, count, timeoutMs, match) }
    }

    /** 負數是位元：−(變數×10＋位元)，例如 −195＝變數 19 的 bit 5（同 XTune）。 */
    private fun base(varId: Int) = if (varId < 0) -varId / 10 else varId

    override fun watch(vars: List<Int>) { if (vars.isNotEmpty()) repo.calWatch(vars.map(::base).distinct()) }

    override fun live(varId: Int): Double? {
        val l = liveRound.value ?: return null
        if (varId < 0) return l.v(base(varId))?.let { ((it.toLong() shr (-varId % 10)) and 1L).toDouble() }
        return l.v(varId)
    }

    override fun varLabel(varId: Int): String {
        val ch = repo.registry.value?.values?.get(base(varId)) ?: return "#$varId"
        return ch.label + if (ch.unit.isNotBlank() && ch.unit != "NA") " ${ch.unit}" else ""
    }

    override fun tableTitle(absId: Int): String =
        tune.definition(absId)?.let { work.opened.labels.shortName(absId, lang) ?: it.name } ?: "#$absId"

    @Composable
    override fun TableEditor(ids: List<Int>, modifier: Modifier) {
        androidx.compose.foundation.layout.Box(modifier) { TablesEditor(work, null, null, ids) }
    }
}
