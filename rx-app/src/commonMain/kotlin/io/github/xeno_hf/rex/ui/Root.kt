package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import rx.session.EcuFinder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import rx.session.Presets
import rx.session.SessionState
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.Target

private enum class Tab(val label: Int, val icon: ImageVector) {
    Dash(R.string.tab_dash, Icons.Filled.Home),
    Scope(R.string.tab_scope, Icons.Filled.DateRange),
    Channels(R.string.tab_channels, Icons.AutoMirrored.Filled.List),
    Records(R.string.tab_records, Icons.Filled.Star),
    More(R.string.tab_more, Icons.Filled.Menu),
}

/** 全 App 的 Repo（Android：Application；iOS：App 啟動時建立）。 */
val LocalRepo = staticCompositionLocalOf<Repo> { error("Repo not provided") }

/** 短暫提示（取代 Android Toast，兩個平台同一個樣子）。 */
object Toasts {
    var message by mutableStateOf<String?>(null)
        private set
    // 序號要是 Compose state：同一句話連跳兩次時 message 沒變，只有序號變才會重組、重新顯示
    private var seq by mutableIntStateOf(0)
    fun show(text: String) { message = text; seq++ }
    internal val serial get() = seq
}

/** App 進入點：主題＋Repo＋畫面。[startTab]＝開啟時的頁籤名稱（自動截圖驗收用，平常是 null）。 */
@Composable
fun ReXApp(repo: Repo, startTab: String? = null) {
    CompositionLocalProvider(LocalRepo provides repo) {
        val theme by repo.theme.collectAsState()
        RxTheme(theme) {
            Box(Modifier.fillMaxSize()) {
                RxRoot(startTab)
                ToastHost(Modifier.align(Alignment.BottomCenter))
                // 儀表控制結果（MAP／AT／Pit／LC／TC／EB）轉成在地化 toast。
                val control by repo.lastControl.collectAsState()
                LaunchedEffect(control) { control?.let { Toasts.show(controlText(it)) } }
            }
        }
    }
}

private fun controlText(m: Repo.ControlMsg): String = when (m.outcome) {
    Repo.ControlMsg.Outcome.ON -> Strings.get(R.string.ctl_on, m.label)
    Repo.ControlMsg.Outcome.OFF -> Strings.get(R.string.ctl_off, m.label)
    Repo.ControlMsg.Outcome.MAP_OK -> Strings.get(R.string.ctl_map_ok, m.label)
    Repo.ControlMsg.Outcome.NEEDS_LOGIN -> Strings.get(R.string.ctl_needs_login, m.label)
    Repo.ControlMsg.Outcome.NOT_LICENSED -> Strings.get(R.string.ctl_not_licensed, m.label)
    Repo.ControlMsg.Outcome.REFUSED -> Strings.get(R.string.ctl_refused, m.label, m.detail ?: "")
    Repo.ControlMsg.Outcome.ERROR -> Strings.get(R.string.ctl_error, m.label, m.detail ?: "")
    Repo.ControlMsg.Outcome.NOT_CONNECTED -> Strings.get(R.string.ctl_not_connected, m.label)
    Repo.ControlMsg.Outcome.DEMO -> Strings.get(R.string.ctl_demo, m.label)
}

@Composable
private fun ToastHost(modifier: Modifier) {
    val msg = Toasts.message ?: return
    val serial = Toasts.serial
    var visible by remember(serial) { mutableStateOf(true) }
    LaunchedEffect(serial) { kotlinx.coroutines.delay(2500); visible = false }
    if (visible) Text(
        msg, color = RxColors.text, fontSize = 14.sp,
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
            .background(RxColors.cardHi, RoundedCornerShape(12.dp)).border(1.dp, RxColors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxRoot(startTab: String? = null) {
    val repo = LocalRepo.current
    val registry by repo.registry.collectAsState()
    val state by repo.state.collectAsState()
    val rec by repo.recording.collectAsState()
    val target by repo.target.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { it.name.equals(startTab, ignoreCase = true) } ?: Tab.Dash) }
    var showConnect by remember { mutableStateOf(false) }

    LaunchedEffect(target) { repo.platform.keepScreenOn(target != null && repo.keepScreenOn) }

    val loadingDefs by repo.registryLoading.collectAsState()
    if (registry == null) {
        Box(Modifier.fillMaxSize().rxBackground()) {
            if (loadingDefs) Text(stringResource(R.string.loading), color = RxColors.muted, modifier = Modifier.align(Alignment.Center)) else ImportScreen()
        }
        return
    }

    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.Dash -> DashboardScreen()
            Tab.Scope -> ScopeScreen()
            Tab.Channels -> ChannelsScreen()
            Tab.Records -> RecordsScreen()
            Tab.More -> MoreScreen()
        }
        if (target == null && tab != Tab.Records && tab != Tab.More) {
            IdleHint(onConnect = { showConnect = true })
        }
    }
    val onRec = { if (rec == null) repo.startRecording() else repo.stopRecording() }

    if (isLandscape()) {
        // 橫向：左側窄導覽欄（狀態燈＋頁籤），錄製／連線放在內容頂列右側，儀表吃滿其餘空間
        val actions: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == SessionState.Streaming) RecButton(recording = rec != null, compact = true, onClick = onRec)
                Spacer(Modifier.width(8.dp))
                SmallPill(stringResource(if (target == null) R.string.connect else R.string.disconnect), if (target == null) RxColors.brandInk else RxColors.muted) {
                    if (target == null) showConnect = true else repo.disconnect()
                }
            }
        }
        Row(Modifier.fillMaxSize().rxBackground().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier.width(66.dp).fillMaxHeight().background(RxColors.card.copy(alpha = 0.92f)).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Wordmark(20)
                Spacer(Modifier.height(4.dp))
                StatusBadge(state, target, compact = true)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                    Tab.entries.forEach { t -> RailItem(t, tab == t) { tab = t } }
                }
            }
            CompositionLocalProvider(LocalBarActions provides actions) {
                Column(Modifier.weight(1f).fillMaxSize()) {
                    if (tab != Tab.Dash) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) { Spacer(Modifier.weight(1f)); actions() }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().rxBackground()) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Wordmark(24)
                                Spacer(Modifier.width(12.dp))
                                StatusBadge(state, target)
                            }
                        },
                        actions = {
                            if (state == SessionState.Streaming) RecButton(recording = rec != null, onClick = onRec)
                            if (target == null) {
                                Button(onClick = { showConnect = true }) { Text(stringResource(R.string.connect)) }
                            } else {
                                OutlinedButton(onClick = { repo.disconnect() }) { Text(stringResource(R.string.disconnect), color = RxColors.text) }
                            }
                            Spacer(Modifier.width(8.dp))
                        },
                    )
                },
                bottomBar = {
                    if (repo.platform.compactTabBar) CompactTabBar(tab) { tab = it }
                    else NavigationBar(containerColor = RxColors.card) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = RxColors.brand.copy(alpha = 0.2f), selectedIconColor = RxColors.brandInk,
                                    selectedTextColor = RxColors.brandInk))
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) { content() }
            }
        }
    }
    if (showConnect) ConnectDialog(onDismiss = { showConnect = false })
}

/** iOS 樣式的矮頁籤列（49 pt＋Home 指示條區；Material 的 NavigationBar 是 80 dp，小 iPhone 上太佔位）。 */
@Composable
private fun CompactTabBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(RxColors.card)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).height(49.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val tint = if (tab == t) RxColors.brandInk else RxColors.muted
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = null, indication = null) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                Text(stringResource(t.label), color = tint, fontSize = 10.sp, fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** 背景：暗色＝漸層＋碳纖斜紋；淺色＝象牙白＋頂部金色柔光。 */
fun Modifier.rxBackground(): Modifier = drawBehind {
    val p = RxColors.palette
    drawRect(Brush.verticalGradient(listOf(p.bg, p.bg2)))
    if (p.light) {
        drawRect(Brush.radialGradient(listOf(p.brand2.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.5f, 0f), radius = size.maxDimension * 0.7f))
    } else {
        drawRect(Brush.radialGradient(listOf(p.brand.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.35f), radius = size.maxDimension * 0.6f))
        val step = 6.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(Color.White.copy(alpha = 0.018f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2)
            x += step
        }
    }
}

/** 橫向時儀表頁頂列右側要放的錄製／連線鍵（由 Root 提供）。 */
val LocalBarActions = staticCompositionLocalOf<@Composable () -> Unit> { {} }

@Composable
private fun RailItem(t: Tab, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) RxColors.brandInk else RxColors.muted
    Column(
        Modifier.width(56.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) RxColors.brand.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(t.icon, null, tint = c, modifier = Modifier.size(20.dp))
        Text(stringResource(t.label), fontSize = 10.sp, lineHeight = 13.sp, color = c)
    }
}

@Composable
private fun SmallPill(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

@Composable
private fun Wordmark(size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("R", style = digits(size.sp, FontWeight.ExtraBold, italic = true), color = RxColors.brand)
        Text("e", style = digits((size * 0.78f).sp, FontWeight.ExtraBold, italic = true), color = RxColors.brand)
        Text("X", style = digits(size.sp, FontWeight.ExtraBold, italic = true), color = RxColors.partner)
    }
}

@Composable
private fun StatusBadge(state: SessionState, target: Target?, compact: Boolean = false) {
    val repo = LocalRepo.current
    val tick by repo.tick.collectAsState()
    val hz = remember(tick) { repo.session?.stats?.roundHz ?: 0.0 }
    val (color, text) = when (state) {
        SessionState.Streaming -> RxColors.accent to stringResource(R.string.st_connected)
        is SessionState.Arming -> RxColors.warn to stringResource(R.string.st_connecting)
        is SessionState.NoBroadcast -> RxColors.warn to stringResource(R.string.st_waiting)
        is SessionState.Connecting -> RxColors.warn to stringResource(R.string.st_connecting)
        is SessionState.Reconnecting -> RxColors.danger to stringResource(R.string.st_reconnecting)
        else -> RxColors.muted to stringResource(R.string.not_connected)
    }
    if (compact) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CrystalDot(color, state == SessionState.Streaming, Modifier.size(14.dp))
            Text(text, color = color, style = digits(10.sp, FontWeight.SemiBold).copy(lineHeight = 13.sp))
            if (target is Target.Demo) Text(stringResource(R.string.demo_short), fontSize = 9.sp, lineHeight = 11.sp, color = RxColors.muted)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrystalDot(color, state == SessionState.Streaming, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(text, fontSize = 14.sp, color = color, style = digits(14.sp, FontWeight.SemiBold))
                if (target is Target.Demo) Text(stringResource(R.string.demo_mode), fontSize = 10.sp, color = RxColors.muted)
            }
        }
    }
}

@Composable
private fun RecButton(recording: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val c = if (recording) RxColors.danger else RxColors.muted
    Row(
        Modifier.padding(end = if (compact) 0.dp else 8.dp).background(RxColors.card, RoundedCornerShape(16.dp))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (recording) R.string.rec_stop else R.string.rec_start), color = c, fontSize = 13.sp)
    }
}

@Composable
private fun IdleHint(onConnect: () -> Unit) {
    val repo = LocalRepo.current
    Box(Modifier.fillMaxSize().background(RxColors.bg.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.idle_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.idle_hint), color = RxColors.muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onConnect) { Text(stringResource(R.string.connect)) }
        }
    }
}

@Composable
fun ImportScreen() {
    val repo = LocalRepo.current
    val err by repo.registryError.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pick = rememberFilePicker { _, bytes -> scope.launch { busy = true; repo.importControlFile(bytes); busy = false } }
    val canDecrypt = repo.platform.canDecryptControlFile
    val hasXTune = remember { repo.platform.xtuneInstalled() }
    Column(Modifier.fillMaxSize().background(RxColors.bg).padding(24.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(32.dp))
        Text("ReX", fontSize = 40.sp, fontWeight = FontWeight.Black, color = RxColors.accent)
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.import_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (canDecrypt) R.string.import_hint_xtune else R.string.import_hint_file),
            color = RxColors.muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        if (canDecrypt) {
            Button(
                onClick = { scope.launch { busy = true; repo.importFrom { repo.platform.controlFileFromXTune() }; busy = false } },
                enabled = hasXTune && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (hasXTune) R.string.import_from_xtune else R.string.xtune_missing)) }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = { pick() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_from_file))
        }
        if (busy) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator() }
        err?.let { Spacer(Modifier.height(16.dp)); Text(it, color = RxColors.danger) }
    }
}

@Composable
private fun ConnectDialog(onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(repo.host) }
    var pw by remember { mutableStateOf(repo.secrets.get() ?: "") }
    var preset by remember { mutableStateOf(repo.currentPreset().id) }
    var bind by remember { mutableStateOf(repo.bind) }
    var ssid by remember { mutableStateOf(repo.ecuSsid) }
    var wpw by remember { mutableStateOf(repo.ecuWifiPw) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Presets.Preset?>(null) }
    var demoPick by remember { mutableStateOf(false) }
    val custom by repo.customPresets.collectAsState()
    val reg = repo.registry.collectAsState().value

    fun save() { repo.host = host.trim(); repo.secrets.put(pw.trim().ifEmpty { null }); repo.presetId = preset; repo.bind = bind; repo.ecuSsid = ssid; repo.ecuWifiPw = wpw }
    val askGps = rememberLocationPermission {
        repo.connect(Target.Ecu(host.trim()))
        onDismiss()
    }
    var demoTarget by remember { mutableStateOf<Target?>(null) }
    val askGpsDemo = rememberLocationPermission {
        demoTarget?.let(repo::connect); demoTarget = null; demoPick = false; onDismiss()
    }
    fun needLocation() = (repo.gpsEnabled.value || repo.platform.backgroundNeedsLocation) && !repo.gps.hasPermission()


    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.connect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.conn_host)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !scanning, onClick = {
                        scanning = true; found = null
                        scope.launch {
                            val finder = repo.platform.ecuFinder()
                            found = runCatching {
                                finder.find(listOf(EcuFinder.KnownHost(host.trim()))).map { it.probe.host }
                            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
                            found?.singleOrNull()?.let { host = it }
                            scanning = false
                        }
                    }) { Text(stringResource(if (scanning) R.string.conn_finding else R.string.conn_find)) }
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                found?.let { list ->
                    if (list.isEmpty()) Text(stringResource(R.string.conn_not_found), color = RxColors.warn, fontSize = 12.sp)
                    if (list.size > 1) list.forEach { h -> Text("● $h", color = RxColors.accent, modifier = Modifier.clickable { host = h }.padding(4.dp)) }
                }
                Text(stringResource(R.string.conn_xtune_kicked), color = RxColors.muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.conn_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.conn_channels), fontWeight = FontWeight.Bold)
                (listOf(Presets.ALL_CHANNELS) + custom).forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { preset = p.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(if (preset == p.id) RxColors.accent else RxColors.line, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (p.id == Presets.ALL_CHANNELS.id) stringResource(R.string.preset_all) else p.title)
                            Text(stringResource(R.string.n_channels, p.absids.count { reg?.values?.get(it)?.isBitContainer == false }), color = RxColors.muted, fontSize = 11.sp)
                        }
                        if (p.id != Presets.ALL_CHANNELS.id) TextButton(onClick = { editing = p }) { Text(stringResource(R.string.edit)) }
                    }
                }
                TextButton(onClick = {
                    editing = Presets.Preset("u" + rx.session.WallClock.nowMs(), Strings.get(R.string.preset_default_name, custom.size + 1), "", Presets.ALL_CHANNELS.absids)
                }) { Text(stringResource(R.string.conn_new_group)) }
                Text(stringResource(R.string.conn_fewer_faster), color = RxColors.muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                if (repo.platform.supportsNetworkBinding) TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.conn_adv_hide else R.string.conn_adv_show)) }
                if (advanced && repo.platform.supportsNetworkBinding) {
                    Text(stringResource(R.string.conn_bike_wifi), fontWeight = FontWeight.Bold)
                    OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.conn_wifi_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (ssid.isNotBlank()) OutlinedTextField(wpw, { wpw = it }, label = { Text(stringResource(R.string.conn_wifi_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.conn_wifi_hint), color = RxColors.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.conn_network), fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.FlowRow {
                        listOf(io.github.xeno_hf.rex.BindMode.AUTO to stringResource(R.string.conn_bind_auto), io.github.xeno_hf.rex.BindMode.WIFI to stringResource(R.string.conn_bind_wifi), io.github.xeno_hf.rex.BindMode.NONE to stringResource(R.string.conn_bind_none)).forEach { (b, l) ->
                            FilterChip(selected = bind == b, onClick = { bind = b }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Text(stringResource(R.string.conn_bind_hint), color = RxColors.muted, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                save()
                if (needLocation()) {
                    askGps()
                } else { repo.connect(Target.Ecu(host.trim())); onDismiss() }
            }, enabled = host.isNotBlank()) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = {
            TextButton(onClick = { save(); demoPick = true }) { Text(stringResource(R.string.demo_mode)) }
        },
    )
    if (demoPick) DemoPicker(onStart = { t -> if (needLocation()) { demoTarget = t; askGpsDemo() } else { repo.connect(t); demoPick = false; onDismiss() } }, onDismiss = { demoPick = false })
    editing?.let { p ->
        PresetEditor(p, isNew = custom.none { it.id == p.id },
            onSave = { repo.upsertPreset(it); preset = it.id; editing = null },
            onDelete = { repo.deletePreset(p.id); if (preset == p.id) preset = Presets.ALL_CHANNELS.id; editing = null },
            onDismiss = { editing = null })
    }
}

/** 體驗模式：選一份騎乘紀錄重播（App 內建的，或使用者從 XTune 分享／選檔匯入的）。 */
@Composable
private fun DemoPicker(onStart: (Target) -> Unit, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val logs by repo.demoLogs.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = rememberFilePicker { name, bytes -> repo.importDemoLog(name, bytes) { msg = it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.demo_mode)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.demo_hint), color = RxColors.muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (repo.hasBuiltinDemo) Row(Modifier.fillMaxWidth().clickable { onStart(Target.Demo()) }.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.demo_builtin), color = RxColors.brandInk)
                }
                logs.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("▶ ${f.name.substringBeforeLast('.')}", color = RxColors.brandInk, modifier = Modifier.weight(1f).clickable { onStart(Target.Demo(f)) }.padding(vertical = 8.dp))
                        TextButton(onClick = { repo.deleteDemoLog(f) }) { Text(stringResource(R.string.delete), color = RxColors.muted, fontSize = 12.sp) }
                    }
                }
                // ReX 自己錄的紀錄：不用匯入，直接重播（刪除請到「紀錄」頁）
                remember(logs) { repo.ownRecordings() }.forEach { f ->
                    Text("▶ ${f.parent!!.name}", color = RxColors.brandInk, modifier = Modifier.fillMaxWidth().clickable { onStart(Target.Demo(f)) }.padding(vertical = 8.dp))
                }
                if (logs.isEmpty() && !repo.hasBuiltinDemo) Text(stringResource(R.string.demo_empty), color = RxColors.muted)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { pick() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_from_file)) }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.demo_share_hint), color = RxColors.muted, fontSize = 12.sp)
                msg?.let { Spacer(Modifier.height(6.dp)); Text(it, color = RxColors.accent, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** 編輯頻道組：名稱＋勾選通道（從「全部」裡挑）。 */
@Composable
private fun PresetEditor(p: Presets.Preset, isNew: Boolean, onSave: (Presets.Preset) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val reg = repo.registry.collectAsState().value ?: return
    var name by remember { mutableStateOf(p.title) }
    var q by remember { mutableStateOf("") }
    val pool = remember(reg) { Presets.ALL_CHANNELS.absids.distinct().mapNotNull { reg.values[it] }.filter { !it.isBitContainer }.sortedBy { it.label.lowercase() } }
    var sel by remember { mutableStateOf(p.absids.toSet().intersect(pool.map { it.absid }.toSet())) }
    val shown = pool.filter { q.isBlank() || it.label.contains(q, true) || it.name.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(if (isNew) R.string.group_new else R.string.group_edit)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.group_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.group_selected, sel.size, pool.size), color = RxColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sel = sel + shown.map { it.absid } }) { Text(stringResource(R.string.select_all)) }
                    TextButton(onClick = { sel = sel - shown.map { it.absid }.toSet() }) { Text(stringResource(R.string.select_none)) }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(300.dp)) {
                    items(shown.size, key = { shown[it].absid }) { i ->
                        val c = shown[i]
                        val on = c.absid in sel
                        Row(Modifier.fillMaxWidth().clickable { sel = if (on) sel - c.absid else sel + c.absid }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = { sel = if (it) sel + c.absid else sel - c.absid })
                            Text(c.label, modifier = Modifier.weight(1f), maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = sel.isNotEmpty() && name.isNotBlank(), onClick = {
                onSave(p.copy(title = name.trim(), absids = Presets.ALL_CHANNELS.absids.filter { it in sel || reg.values[it]?.isBitContainer == true }))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = RxColors.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** 共用小元件。 */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(RxColors.card, RoundedCornerShape(14.dp)).padding(12.dp)) { content() }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text, fontSize = 10.sp, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun Mono(text: String, color: Color = RxColors.text, size: Int = 12) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color)
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

val Arrangement.gap8 get() = spacedBy(8.dp)
