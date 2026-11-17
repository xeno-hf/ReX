package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 「監控」頁籤：示波與通道兩個分頁（2026-11-16 使用者定，底下導覽列從六個收成五個）。 */
@Composable
fun MonitorScreen(startTab: String? = null) {
    var channels by rememberSaveable { mutableStateOf(startTab.equals("channels", ignoreCase = true)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            FilterChip(selected = !channels, onClick = { channels = false }, label = { Text(stringResource(R.string.tab_scope)) }, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = channels, onClick = { channels = true }, label = { Text(stringResource(R.string.tab_channels)) })
        }
        Box(Modifier.weight(1f)) { if (channels) ChannelsScreen() else ScopeScreen() }
    }
}
