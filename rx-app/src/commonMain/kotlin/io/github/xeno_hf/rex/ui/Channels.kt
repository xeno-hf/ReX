package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rx.session.Recorder

/**
 * 其他通道照官方公式算，但沒有實車對照，標「未驗」。
 */
val VERIFIED = setOf(
    "RPM", "SA", "T_Air", "T_Eng", "Volt_Batt", "BMS_Imon", "MGU_A", "MGU_Temp", "MGU_Energy_Percent",
    "TPS_AD", "TPS_Percent", "P_Atm", "Cyl1_Eng_AP", "NBO2_Volt", "BMS_Vtotal",
    "BMS_Vcell1", "BMS_Vcell2", "BMS_Vcell3", "BMS_Vcell4", "Avg_Fuel_Consump", "MapNum",
)

@Composable
fun ChannelsScreen() {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    var q by remember { mutableStateOf("") }
    var bits by remember { mutableStateOf(false) }
    var onlyOn by remember { mutableStateOf(false) }
    val l = live ?: return
    val wboReady = l.bit("Fuel5.WBO2_Ready")

    val cols = l.layout.columns.filter { c ->
        (bits || !c.def.isBit) && c.def.name.contains(q, ignoreCase = true) && (!onlyOn || !c.def.isBit || l.layout.value(c, l.raw) > 0.5)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.ch_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = bits, onClick = { bits = !bits }, label = { Text(stringResource(R.string.ch_with_flags)) }, modifier = Modifier.padding(end = 6.dp))
            if (bits) FilterChip(selected = onlyOn, onClick = { onlyOn = !onlyOn }, label = { Text(stringResource(R.string.ch_only_on)) })
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.ch_count, cols.size), color = RxColors.muted, fontSize = 12.sp)
        }
        LazyColumn {
            items(cols, key = { "${it.def.absid}:${it.def.bitIndex}" }) { c ->
                val d = c.def
                val v = l.layout.value(c, l.raw)
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            if (d.name == "AFR_WBO2" && wboReady == false) Badge(stringResource(R.string.ch_sensor_not_ready), RxColors.warn)
                        }
                    }
                    Mono(if (d.isBit) (if (v > 0.5) "1" else "0") else Recorder.fmt(v, d.decimals ?: 2), if (d.isBit && v > 0.5) RxColors.warn else RxColors.text, 16)
                    Spacer(Modifier.width(4.dp))
                    Text(d.unit.takeIf { it != "NA" } ?: "", color = RxColors.muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
                }
                HorizontalDivider(color = RxColors.line)
            }
        }
    }
}
