package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.Live

/**
 * 監控儀表（XTune「監控儀表」）：方磚格，直向 3 欄、橫向 6 欄。
 * RX 加的：磚底 10 秒走勢線、超標變色、單位不再被切掉、長按換通道／移除、最後一格新增。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonitorDash() {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    val tiles by repo.monitorTiles.collectAsState()
    val land = isLandscape()
    var edit by remember { mutableStateOf<Int?>(null) } // 索引；-1＝新增
    val l = live
    // 走勢線：最近 160 輪（約 10 秒），每 2 輪取一次快照
    val hist = remember(l?.seq?.div(2)) { repo.history.tail(160) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (land) 6 else 3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tiles, key = { i, a -> "$i:$a" }) { i, a ->
            MonitorTile(a, l, hist, Modifier.aspectRatio(if (land) 1.5f else 1.15f).combinedClickable(onClick = {}, onLongClick = { edit = i }))
        }
        item(key = "add") {
            Box(
                Modifier.aspectRatio(if (land) 1.5f else 1.15f).clip(RoundedCornerShape(14.dp)).border(1.dp, RxColors.line, RoundedCornerShape(14.dp))
                    .clickable { edit = -1 },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.add_tile), color = RxColors.muted) }
        }
    }
    edit?.let { idx ->
        val cur = tiles.getOrNull(idx)
        ChannelPicker(
            title = stringResource(if (idx < 0) R.string.pick_channel_add else R.string.pick_channel_replace),
            current = cur,
            onPick = { a -> repo.setMonitorTiles(if (idx < 0) tiles + a else tiles.toMutableList().also { it[idx] = a }); edit = null },
            onRemove = if (idx >= 0) ({ repo.setMonitorTiles(tiles.filterIndexed { j, _ -> j != idx }); edit = null }) else null,
            onReset = { repo.setMonitorTiles(io.github.xeno_hf.rex.Repo.DEFAULT_MONITOR); edit = null },
            onDismiss = { edit = null },
        )
    }
}

@Composable
private fun MonitorTile(absid: Int, l: Live?, hist: List<Pair<Long, LongArray>>, modifier: Modifier) {
    val repo = LocalRepo.current
    val registry by repo.registry.collectAsState()
    val def = DerivedChannels.def(absid) ?: l?.layout?.registry?.values?.get(absid) ?: registry?.values?.get(absid)
    repo.gps.fix.collectAsState().value
    val v = if (absid == io.github.xeno_hf.rex.PhoneGps.SPEED_ID) repo.gps.fresh()?.speedKmh else DerivedChannels.value(absid, l)
    val level = levelOf(absid, v)
    val spark = remember(hist, absid) {
        val lay = l?.layout
        if (lay == null || lay.indexOf(absid) < 0) FloatArray(0)
        else FloatArray(hist.size) { lay.physical(absid, hist[it].second)?.toFloat() ?: 0f }
    }
    val shape = RoundedCornerShape(14.dp)
    val text = fmtVal(v, def?.decimals ?: 1)
    Box(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(RxColors.cardHi, RxColors.card)))
            .border(if (level > 0) 1.5.dp else 1.dp, if (level > 0) levelColor(level) else RxColors.line, shape),
    ) {
        if (DerivedChannels.isPower(absid)) PowerBar(v, Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter))
        else Sparkline(spark, if (level > 0) levelColor(level) else RxColors.brand, Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter))
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                def?.name ?: "#$absid", color = RxColors.brandInk, fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(text, style = digits(valueSize(text), FontWeight.Bold), color = if (DerivedChannels.isPower(absid)) powerColor(v) else levelColor(level), maxLines = 1)
            }
            Text(unitOf(def), color = RxColors.muted, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.End))
        }
    }
}

private fun valueSize(s: String) = when {
    s.length <= 4 -> 28.sp
    s.length <= 5 -> 25.sp
    s.length <= 6 -> 22.sp
    s.length <= 7 -> 19.sp
    else -> 16.sp
}

/** 選通道：搜尋名稱／absid／單位。只列目前頻道表裡有的（沒連線時列預設組的）。 */
@Composable
fun ChannelPicker(
    title: String,
    current: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
) {
    val repo = LocalRepo.current
    val reg = repo.registry.collectAsState().value ?: return
    val live = repo.live.collectAsState().value
    var q by remember { mutableStateOf("") }
    val pool = remember(reg, live?.layout) {
        val ids = live?.layout?.table?.entries?.map { it.absid }
            ?: repo.currentPreset().absids
        (ids.distinct().mapNotNull { reg.values[it] }.filter { !it.isBitContainer } + DerivedChannels.definitions).sortedBy { it.name.lowercase() }
    }
    val shown = pool.filter { q.isBlank() || it.name.contains(q, true) || it.absid.toString() == q.trim() || it.unit.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(340.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.absid }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (c.absid == current) RxColors.brand.copy(alpha = 0.15f) else RxColors.card)
                                .clickable { onPick(c.absid) }.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(unitOf(c), color = RxColors.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        dismissButton = {
            Row {
                onReset?.let { TextButton(onClick = it) { Text(stringResource(R.string.reset_default), color = RxColors.muted) } }
                onRemove?.let { TextButton(onClick = it) { Text(stringResource(R.string.remove), color = RxColors.danger) } }
            }
        },
    )
}
