package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rx.session.Recorder

@Composable
fun ScopeScreen() {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    var chans by remember { mutableStateOf(repo.scopeChannels) }
    var windowSec by remember { mutableStateOf(15) }
    var paused by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<Pair<Long, LongArray>>?>(null) }
    val l = live

    val layout = l?.layout
    val active = chans.filter { layout == null || layout.indexOf(it) >= 0 }.take(6)
    val data = (if (paused) frozen else null) ?: repo.history.snapshot()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(5, 15, 60).forEach { s -> FilterChip(selected = windowSec == s, onClick = { windowSec = s }, label = { Text("${s}s") }, modifier = Modifier.padding(end = 6.dp)) }
            FilterChip(selected = paused, onClick = { paused = !paused; frozen = if (paused) repo.history.snapshot() else null }, label = { Text(stringResource(if (paused) R.string.scope_paused else R.string.scope_pause)) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { picking = true }) { Text(stringResource(R.string.scope_pick)) }
        }
        Card(Modifier.fillMaxWidth().weight(1f)) {
            if (layout == null || data.size < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.scope_waiting), color = RxColors.muted) }
            } else {
                val tEnd = data.last().first
                val tStart = tEnd - windowSec * 1_000_000_000L
                val win = data.filter { it.first >= tStart }
                Canvas(Modifier.fillMaxSize()) {
                    for (g in 1..3) {
                        val y = size.height * g / 4
                        drawLine(RxColors.line, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    active.forEachIndexed { k, absid ->
                        val idx = layout.indexOf(absid)
                        val def = layout.entryDefs[idx]
                        val ys = win.map { def.physical(it.second[idx]) }
                        val lo = ys.min(); val hi = ys.max(); val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
                        val p = Path()
                        win.forEachIndexed { i, (t, _) ->
                            val x = ((t - tStart).toFloat() / (windowSec * 1e9f)) * size.width
                            val y = size.height - ((ys[i] - lo) / span).toFloat() * (size.height * 0.9f) - size.height * 0.05f
                            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        drawPath(p, RxColors.traces[k % RxColors.traces.size], style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (layout != null && data.isNotEmpty()) {
            val last = data.last().second
            val tEnd = data.last().first
            val win = data.filter { it.first >= tEnd - windowSec * 1_000_000_000L }
            active.forEachIndexed { k, absid ->
                val idx = layout.indexOf(absid)
                val def = layout.entryDefs[idx]
                val ys = win.map { def.physical(it.second[idx]) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.size(10.dp).background(RxColors.traces[k % RxColors.traces.size], CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(def.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Mono("${Recorder.fmt(def.physical(last[idx]), 2)} ${def.unit.takeIf { it != "NA" } ?: ""}")
                    Spacer(Modifier.width(8.dp))
                    Mono("[${Recorder.fmt(ys.min(), 1)}~${Recorder.fmt(ys.max(), 1)}]", RxColors.muted, 11)
                }
            }
            Text(stringResource(R.string.scope_window, windowSec), color = RxColors.muted, fontSize = 11.sp)
        }
    }

    if (picking && layout != null) {
        var q by remember { mutableStateOf("") }
        var sel by remember { mutableStateOf(chans.toSet()) }
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.scope_pick_title)) },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(layout.entryDefs.filter { !it.isBitContainer && it.name.contains(q, ignoreCase = true) }, key = { it.absid }) { d ->
                            val on = d.absid in sel
                            Row(Modifier.fillMaxWidth().clickable {
                                sel = if (on) sel - d.absid else if (sel.size < 6) sel + d.absid else sel
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(if (on) RxColors.accent else RxColors.line, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(d.name, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                Text(d.unit, color = RxColors.muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chans = sel.toList(); repo.scopeChannels = chans; picking = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
