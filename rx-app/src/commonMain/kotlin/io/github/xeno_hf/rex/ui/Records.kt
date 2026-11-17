package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.xeno_hf.rex.SessionDir

@Composable
fun RecordsScreen() {
    val repo = LocalRepo.current
    val rec by repo.recording.collectAsState()
    val liveMarks by repo.liveMarks.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<SessionDir>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<SessionDir?>(null) }
    var confirmShare by remember { mutableStateOf<SessionDir?>(null) }
    var confirmPack by remember { mutableStateOf<SessionDir?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh, rec) { list = withContext(Dispatchers.Default) { SessionDir.list(repo.sessionsRoot) } }
    // 開始錄製時新的一筆插在最上面；清單若停在原位，它會被擠到畫面外
    val listState = rememberLazyListState()
    LaunchedEffect(list.firstOrNull()?.dir?.name) { if (rec != null) listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        rec?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(stringResource(R.string.rec_recording), color = RxColors.danger, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { addMark(repo) }) { Text(stringResource(R.string.rec_mark)) }
                        Button(onClick = { repo.stopRecording(); refresh++ }) { Text(stringResource(R.string.rec_stop_recording)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(stringResource(R.string.rec_list, list.size), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.dir.name }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Text(s.dir.name, fontWeight = FontWeight.Bold)
                        // 正在錄的那筆還沒收檔（meta 沒有 rounds），不是「沒有正常結束」
                        val live = rec?.dir == s.dir
                        Text(
                            if (live) stringResource(R.string.rec_in_progress)
                            else if (s.rounds != null) "${rx.protocol.Fmt.fixed(s.sizeBytes / 1e6, 1)} MB"
                            else stringResource(R.string.rec_unfinished, s.sizeBytes / 1e6),
                            color = if (live) RxColors.danger else if (s.rounds != null) RxColors.muted else RxColors.warn, fontSize = 12.sp,
                        )
                        val marks = if (live) liveMarks else s.marks
                        if (marks.isNotEmpty()) Text(
                            stringResource(R.string.rec_marks, marks.joinToString(" · ") { "#${it.n} ${it.clock}" }),
                            color = RxColors.muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmShare = s }) { Text(stringResource(R.string.rec_share_all)) }
                            TextButton(enabled = rec?.dir != s.dir, onClick = { confirmPack = s }) { Text(stringResource(R.string.rec_pack_xbl)) }
                            TextButton(onClick = {
                                val f = kotlinx.io.files.Path(s.dir, "decoded.loga")
                                if (io.github.xeno_hf.rex.Fs.exists(f)) scope.launch {
                                    val out = kotlinx.io.files.Path(repo.shareDir, "${s.dir.name}.loga")
                                    runCatching {
                                        withContext(Dispatchers.Default) { rx.session.ShareExport.copyLoga(f, out, rx.session.ShareExport.Mode.PUBLIC) }
                                        repo.platform.share(out, "text/plain", "loga")
                                    }.onFailure { Toasts.show(it.message ?: "Share failed") }
                                }
                            }) { Text(stringResource(R.string.rec_share_loga)) }
                            TextButton(enabled = rec?.dir != s.dir, onClick = { confirmDelete = s }) { Text(stringResource(R.string.delete), color = RxColors.danger) }
                        }
                    }
                }
            }
        }
    }
    confirmShare?.let { s ->
        fun share(mode: rx.session.ShareExport.Mode) {
            confirmShare = null
            scope.launch {
                val tag = if (mode == rx.session.ShareExport.Mode.PUBLIC) "" else "_full"
                val staging = kotlinx.io.files.Path(repo.shareDir, "staging", s.dir.name + tag)
                val zip = kotlinx.io.files.Path(repo.shareDir, "${s.dir.name}$tag.zip")
                runCatching {
                    withContext(Dispatchers.Default) {
                        try {
                            io.github.xeno_hf.rex.Fs.delete(staging)
                            rx.session.ShareExport.copySession(s.dir, staging, mode)
                            rx.session.ZipStore.zipDir(staging, zip)
                        } finally { io.github.xeno_hf.rex.Fs.delete(staging) }
                    }
                    repo.platform.share(zip, "application/zip", s.dir.name)
                }.onFailure { Toasts.show(it.message ?: "Share failed") }
            }
        }
        AlertDialog(
            onDismissRequest = { confirmShare = null },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.rec_share_title)) },
            text = { Text(stringResource(R.string.rec_share_text)) },
            confirmButton = { TextButton(onClick = { share(rx.session.ShareExport.Mode.PUBLIC) }) { Text(stringResource(R.string.rec_share_public)) } },
            dismissButton = { TextButton(onClick = { share(rx.session.ShareExport.Mode.DIAGNOSTIC) }) { Text(stringResource(R.string.rec_share_full), color = RxColors.warn) } },
        )
    }
    confirmPack?.let { s ->
        // 要打包才打包（2026-11-16 使用者定）：錄製照舊是資料夾，這裡才壓成 .xbl 交給分享面板
        fun pack(mode: rx.session.ShareExport.Mode) {
            confirmPack = null
            scope.launch {
                val tag = if (mode == rx.session.ShareExport.Mode.PUBLIC) "" else "_full"
                val out = kotlinx.io.files.Path(repo.shareDir, "${s.dir.name}$tag.xbl")
                runCatching {
                    withContext(Dispatchers.Default) {
                        rx.session.XblPack.pack(s.dir, out, kotlinx.io.files.Path(repo.shareDir, "staging", s.dir.name + tag + "_xbl"), mode, "ReX ${repo.platform.appVersion}")
                    }
                    repo.platform.share(out, rx.session.XblPack.MIME, s.dir.name)
                }.onFailure { Toasts.show(it.message ?: "Pack failed") }
            }
        }
        AlertDialog(
            onDismissRequest = { confirmPack = null },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.rec_pack_title)) },
            text = { Text(stringResource(R.string.rec_pack_text)) },
            confirmButton = { TextButton(onClick = { pack(rx.session.ShareExport.Mode.PUBLIC) }) { Text(stringResource(R.string.rec_share_public)) } },
            dismissButton = { TextButton(onClick = { pack(rx.session.ShareExport.Mode.DIAGNOSTIC) }) { Text(stringResource(R.string.rec_share_full), color = RxColors.warn) } },
        )
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.rec_delete_title, s.dir.name)) },
            text = { Text(stringResource(R.string.rec_delete_text)) },
            confirmButton = { TextButton(onClick = { io.github.xeno_hf.rex.Fs.delete(s.dir); confirmDelete = null; refresh++ }) { Text(stringResource(R.string.delete), color = RxColors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
