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
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<SessionDir>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<SessionDir?>(null) }
    var confirmShare by remember { mutableStateOf<SessionDir?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh, rec) { list = withContext(Dispatchers.Default) { SessionDir.list(repo.sessionsRoot) } }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        rec?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(stringResource(R.string.rec_recording), color = RxColors.danger, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { repo.mark(Strings.get(R.string.rec_mark_text, ++markCounter)) }) { Text(stringResource(R.string.rec_mark)) }
                        Button(onClick = { repo.stopRecording(); refresh++ }) { Text(stringResource(R.string.rec_stop_recording)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(stringResource(R.string.rec_list, list.size), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.dir.name }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Text(s.dir.name, fontWeight = FontWeight.Bold)
                        Text(
                            if (s.rounds != null) "${rx.protocol.Fmt.fixed(s.sizeBytes / 1e6, 1)} MB"
                            else stringResource(R.string.rec_unfinished, s.sizeBytes / 1e6),
                            color = if (s.rounds != null) RxColors.muted else RxColors.warn, fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmShare = s }) { Text(stringResource(R.string.rec_share_all)) }
                            TextButton(onClick = {
                                val f = kotlinx.io.files.Path(s.dir, "decoded.loga")
                                if (io.github.xeno_hf.rex.Fs.exists(f)) scope.launch {
                                    val out = kotlinx.io.files.Path(repo.platform.filesDir, "share", "${s.dir.name}.loga")
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
                val staging = kotlinx.io.files.Path(repo.platform.filesDir, "share", "staging", s.dir.name + tag)
                val zip = kotlinx.io.files.Path(repo.platform.filesDir, "share", "${s.dir.name}$tag.zip")
                runCatching {
                withContext(Dispatchers.Default) {
                    io.github.xeno_hf.rex.Fs.delete(staging)
                    rx.session.ShareExport.copySession(s.dir, staging, mode)
                    io.github.xeno_hf.rex.ZipStore.zipDir(staging, zip)
                    io.github.xeno_hf.rex.Fs.delete(staging)
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

private var markCounter = 0
