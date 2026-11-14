package io.github.xeno_hf.rex

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import rx.defs.BuiltinDefs
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.session.DecodedLayout
import io.github.xeno_hf.rex.ui.DerivedChannels

import rx.protocol.Frame

import rx.session.LogReplayTransport
import rx.session.LogaValueSource
import rx.session.Presets
import rx.session.Recorder
import rx.protocol.DashFn
import rx.session.Session
import rx.session.SessionListener
import rx.session.SessionState
import rx.session.UplinkLink
import rx.session.WallClock
import io.github.xeno_hf.rex.Fs.nameWithoutExtension
import io.github.xeno_hf.rex.res.R
import io.github.xeno_hf.rex.ui.Strings
import io.github.xeno_hf.rex.ui.ThemeChoice

/** 一輪即時資料。 */
class Live(val layout: DecodedLayout, val raw: LongArray, val tNanos: Long, val seq: Long) {
    fun v(absid: Int): Double? = layout.physical(absid, raw)
}

/** 環形緩衝：示波器用（最近 [capacity] 輪）。 */
class History(val capacity: Int = 1200) {
    private val lock = SynchronizedObject()
    private val t = LongArray(capacity)
    private val rows = arrayOfNulls<LongArray>(capacity)
    private var head = 0
    var size = 0; private set

    fun add(tn: Long, raw: LongArray) = synchronized(lock) {
        t[head] = tn; rows[head] = raw; head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() = synchronized(lock) { size = 0; head = 0 }

    /** 由舊到新回傳 (t, raw)。 */
    fun snapshot(): List<Pair<Long, LongArray>> = tail(size)

    /** 最近 [n] 輪，由舊到新。 */
    fun tail(n: Int): List<Pair<Long, LongArray>> = synchronized(lock) {
        val m = minOf(n, size)
        (0 until m).map { k -> val i = (head - m + k + capacity) % capacity; t[i] to rows[i]!! }
    }
}

sealed interface Target {
    data class Ecu(val host: String) : Target
    /** 體驗模式：離線重播使用者提供的騎乘紀錄。 */
    data class Demo(val log: Path? = null) : Target
}

/** 檔案位置（ControlFile 快取）。 */
private val Platform.controlDir get() = Path(filesDir, "controlfile")
private val Platform.xmlCache get() = Path(controlDir, "ControlFile_S3.xml")
/** 解析後的精簡版（[ChannelRegistry.toCompact]），啟動時優先讀。 */
private val Platform.compactCache get() = Path(controlDir, "channels.rxdef")

/** 連線綁網路（Android 專用；iOS 不顯示）。 */
enum class BindMode { AUTO, WIFI, NONE }

/** 全 App 單例狀態（Android：Application 持有；iOS：App 啟動時建立）。 */
/** aRacer ECU 出廠預設密碼（XTune 沒存過密碼時用的值）。 */
const val DEFAULT_ECU_PASSWORD = 888888

class Repo(val platform: Platform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = platform.kv
    val secrets get() = platform.secrets

    val registry = MutableStateFlow<ChannelRegistry?>(null)
    /** 用內建定義時＝平台代號（S3、X2…）；用使用者匯入的 XML 時為 null。 */
    val builtinKey = MutableStateFlow<String?>(null)
    val registryError = MutableStateFlow<String?>(null)
    /** 啟動時正在讀快取的定義檔（7 MB XML 要幾秒）；讀完前不能顯示匯入頁，否則像是「重裝後要重匯」。 */
    val registryLoading = MutableStateFlow(false)
    val state = MutableStateFlow<SessionState>(SessionState.Idle)
    val live = MutableStateFlow<Live?>(null)
    val target = MutableStateFlow<Target?>(null)
    val recording = MutableStateFlow<Recorder?>(null)
    val events = MutableStateFlow<List<String>>(emptyList())
    /** 本次連線出現過的故障碼：名稱 → 首次出現時間。 */
    val malfSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val history = History()
    /** 最近幀（診斷頁），只存物件，顯示時才格式化。 */
    private val recentFrames = ArrayDeque<Triple<Long, Int, Frame>>()
    private val framesLock = SynchronizedObject()
    val tick = MutableStateFlow(0L) // 診斷頁刷新用

    /** 分享用副本的位置；[Platform.cacheDir] 底下，Android 的 FileProvider 只開放這裡。 */
    val shareDir: Path get() = Path(platform.cacheDir, "share")

    init {
        // 上次分享留下的副本（含舊版放在 filesDir/share 的）在啟動時清掉；分享當下不清，接收端可能還在讀
        scope.launch { runCatching { Fs.delete(shareDir); Fs.delete(Path(platform.filesDir, "share")) } }
    }

    var session: Session? = null; private set
    private var stateJob: Job? = null
    private var idleJob: Job? = null
    private var seq = 0L

    /** 預留的體驗紀錄來源；本版不附範例紀錄。 */
    val hasBuiltinDemo: Boolean by lazy { runCatching { platform.builtinDemoLoga() != null }.getOrDefault(false) }

    /** 使用者匯入的騎乘紀錄（從 XTune 分享或選檔），體驗模式用。 */
    val demoLogsDir: Path get() = Fs.mkdirs(Path(platform.filesDir, "demo_logs"))
    val demoLogs = MutableStateFlow(listDemoLogs())
    private fun listDemoLogs(): List<Path> = Fs.list(demoLogsDir).filter { it.name.endsWith(".loga") }.sortedByDescending { it.name }

    /** ReX 自己錄的紀錄（sessions/<資料夾>/decoded.loga），體驗模式可以直接重播。 */
    fun ownRecordings(): List<Path> = Fs.list(sessionsRoot).mapNotNull { d -> Path(d, "decoded.loga").takeIf { Fs.size(it) > 0 && !d.name.startsWith("DEMO") } }
        .sortedByDescending { it.parent!!.name }

    /** 匯入一份 loga：可以是 XTune 分享的 zip（內含一個 .loga），也可以是 .loga 本身。 */
    fun importDemoLog(fileName: String?, bytes: ByteArray, done: (String) -> Unit) = scope.launch(Dispatchers.Default) {
        val msg = runCatching {
            val (name, data) = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                platform.unzipFirst(bytes) { it.endsWith(".loga", true) }?.let { (n, d) -> n.substringAfterLast('/') to d }
                    ?: error(Strings.get(R.string.err_zip_no_loga))
            } else {
                (fileName ?: "ride_${WallClock.nowMs()}.loga") to bytes
            }
            require(data.decodeToString(0, minOf(data.size, 64)).contains("aRacer")) { Strings.get(R.string.err_not_loga) }
            Loga.parse(data) // 解析得了才收
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".loga", true)) it else "$it.loga" }
            Fs.writeBytes(Path(demoLogsDir, safe), data)
            demoLogs.value = listDemoLogs()
            Strings.get(R.string.demo_added, safe)
        }.getOrElse { Strings.get(R.string.demo_import_failed, it.message ?: "") }
        log(msg)
        done(msg)
    }

    fun deleteDemoLog(f: Path) { Fs.delete(f); demoLogs.value = listDemoLogs() }

    val sessionsRoot: Path get() = Fs.mkdirs(platform.sessionsRoot)

    // ---------------- 設定 ----------------
    var host: String
        get() = prefs.getString("host", "192.168.88.88")!!
        set(v) = prefs.putString("host", v)
    var presetId: String
        get() = prefs.getString("preset", Presets.ALL_CHANNELS.id)!!
        set(v) = prefs.putString("preset", v)
    /** ECU 熱點。有填就用 local-only 副連線加入，主 WiFi 保留上網＋同步。 */
    var ecuSsid: String
        get() = prefs.getString("ecuSsid", "")!!
        set(v) = prefs.putString("ecuSsid", v.trim())
    var ecuWifiPw: String
        get() = prefs.getString("ecuWifiPw", "")!!
        set(v) = prefs.putString("ecuWifiPw", v)
    var bind: BindMode
        get() = runCatching { BindMode.valueOf(prefs.getString("bind", "AUTO")!!) }.getOrDefault(BindMode.AUTO)
        set(v) = prefs.putString("bind", v.name)
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.putBoolean("keepScreenOn", v)
    /** 從最近使用的 App 清單滑掉 ReX 後，是否繼續在背景連線。預設不繼續（關掉就是關掉）。 */
    var runAfterClose: Boolean
        get() = prefs.getBoolean("runAfterClose", false)
        set(v) = prefs.putBoolean("runAfterClose", v)
    /** 收不到資料多久自動斷線（分鐘，0＝不自動斷）。 */
    var idleStopMinutes: Int
        get() = prefs.getInt("idleStopMin", 10)
        set(v) = prefs.putInt("idleStopMin", v)

    // ---------------- 儀表選項（同 XTune 設定頁） ----------------
    /** 進階儀表轉速上限：10、12、16（×1000 rpm，XTune GaugeRpmMax 1／2／3）。 */
    val rpmMax = MutableStateFlow(prefs.getInt("rpmMax", 10).takeIf { it in RPM_MAXES } ?: 10)
    fun setRpmMax(v: Int) { rpmMax.value = v; prefs.putInt("rpmMax", v) }
    /** 進階儀表顯示檔位（XTune ShowGearNum，預設開）。 */
    val showGear = MutableStateFlow(prefs.getBoolean("showGear", true))
    fun setShowGear(v: Boolean) { showGear.value = v; prefs.putBoolean("showGear", v) }
    /** 連線就自動錄製（預設關）。 */
    val autoRecord = MutableStateFlow(prefs.getBoolean("autoRecord", AUTO_RECORD_DEFAULT))
    fun setAutoRecord(v: Boolean) { autoRecord.value = v; prefs.putBoolean("autoRecord", v) }
    /** 手機 GPS（XTune「Phone GPS」）：開了才定位，錄製時 loga 多四欄 Phone_GPS_*。 */
    val gps get() = platform.gps
    val gpsEnabled = MutableStateFlow(prefs.getBoolean("gps", true))
    fun setGpsEnabled(v: Boolean) {
        gpsEnabled.value = v; prefs.putBoolean("gps", v)
        if (session != null) platform.onSessionActive(true)
        if (v && session != null) gps.start() else if (!v) gps.stop()
    }
    /** 儀表時速改用手機 GPS（XTune 點時速切換；ECU 車速沒接或不準時用）。 */
    val speedFromGps = MutableStateFlow(prefs.getBoolean("speedFromGps", true))
    fun setSpeedFromGps(v: Boolean) { speedFromGps.value = v; prefs.putBoolean("speedFromGps", v) }
    /** 同步到電腦：連線時把資料即時轉送到 [uplinkAddr]（host:port）。 */
    var uplinkEnabled: Boolean
        get() = prefs.getBoolean("uplink", false)
        set(v) = prefs.putBoolean("uplink", v)
    var uplinkAddr: String
        get() = prefs.getString("uplinkAddr", "")!!
        set(v) = prefs.putString("uplinkAddr", v.trim())
    var scopeChannels: List<Int>
        get() = prefs.getString("scope", "26,34,29,1433")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = prefs.putString("scope", v.joinToString(","))

    // ---------------- 外觀／儀表 ----------------
    val theme = MutableStateFlow(
        ThemeChoice.parse(prefs.getString("theme", null))
    )
    fun setTheme(t: ThemeChoice) { theme.value = t; prefs.putString("theme", t.name) }

    var dashPage: Int
        get() = prefs.getInt("dashPage", 0)
        set(v) = prefs.putInt("dashPage", v)

    /** 監控儀表的預設通道格。 */
    val monitorTiles = MutableStateFlow(intList("monitor", DEFAULT_MONITOR))
    fun setMonitorTiles(v: List<Int>) { monitorTiles.value = v; prefs.putString("monitor", v.joinToString(",")) }

    /** 儀表上可自選的格：key → absid。 */
    val slots = MutableStateFlow(DEFAULT_SLOTS.mapValues { (k, d) -> prefs.getInt("slot.$k", d).let { if (it == 124) PhoneGps.SPEED_ID else it } })
    fun setSlot(key: String, absid: Int) { slots.value = slots.value + (key to absid); prefs.putInt("slot.$key", absid) }

    /** 進階直向（race，最多 4 條）／巡航（cruise，最多 6 格）顯示幾格；預設放滿。 */
    val slotCount = MutableStateFlow(SLOT_MAX.mapValues { (g, _) -> prefs.getInt("slots.$g", SLOT_MAX[g]!!).coerceIn(1, SLOT_MAX[g]!!) })
    fun addSlot(group: String) {
        val n = slotCount.value[group]!!
        if (n >= SLOT_MAX[group]!!) return
        slotCount.value = slotCount.value + (group to n + 1); prefs.putInt("slots.$group", n + 1)
    }
    /** 移除第 [index] 格（1 起算），後面的往前補。 */
    fun removeSlot(group: String, index: Int) {
        val n = slotCount.value[group]!!
        if (n <= 1) return
        for (i in index until n) setSlot("$group$i", slots.value["$group${i + 1}"]!!)
        slotCount.value = slotCount.value + (group to n - 1); prefs.putInt("slots.$group", n - 1)
    }

    /** 兩顆多功能鈕的功能 ID（XTune 偏好鍵 MFButton_A／_B，預設 241 AT、244 TC），進階與巡航共用。 */
    val funButtons = MutableStateFlow(listOf(prefs.getInt("mfb.A", 241), prefs.getInt("mfb.B", 244)))
    fun setFunButton(i: Int, id: Int) {
        funButtons.value = funButtons.value.toMutableList().also { it[i] = id }
        prefs.putInt("mfb.${"AB"[i]}", id)
    }

    // ---- 儀表運行期控制 ----
    // 就算有密碼，ReX 也只能改儀表上的功能（MAP／AT／Pit／LC／TC／EB）；
    // 沒登入時 MAP／Pit／LC 能切，其他顯示「功能限制」；AT 要再確認一次才開。
    private val NO_LOGIN_FUNS = setOf(DashFn.PIT, DashFn.LC)

    /** 控制結果。UI 依 [outcome] 挑在地化字串，[label] 是功能名（AT、MAP 2…）。 */
    data class ControlMsg(val label: String, val outcome: Outcome, val detail: String? = null, val serial: Long = 0) {
        enum class Outcome { ON, OFF, MAP_OK, NEEDS_LOGIN, NOT_LICENSED, REFUSED, ERROR, NOT_CONNECTED, DEMO }
    }
    private var controlSerial = 0L
    /** 最近一次控制寫入結果，給 UI 顯示 toast（null＝沒有）。 */
    val lastControl = MutableStateFlow<ControlMsg?>(null)
    /** 控制寫入進行中旗標，避免連點重入。 */
    val controlBusy = MutableStateFlow(false)
    /** 這次連線有沒有登入成功（儀表鈕除 MAP 外都要）。 */
    val ecuLoggedIn = MutableStateFlow(false)
    private var loginJob: Job? = null
    /** 儀表鈕在 ECU 上的開關（[Session.dashSwitch]）；沒讀到的不在表裡，按鈕退回看即時通道。 */
    val dashSwitch = MutableStateFlow<Map<DashFn, Boolean>>(emptyMap())
    private var switchJob: Job? = null

    private fun emit(label: String, o: ControlMsg.Outcome, detail: String? = null) {
        lastControl.value = ControlMsg(label, o, detail, ++controlSerial)
        log("Control $label -> $o${detail?.let { " ($it)" } ?: ""}")
    }

    private fun runControl(label: String, needsLogin: Boolean, onOk: (Session) -> suspend () -> ControlMsg.Outcome, op: suspend (Session) -> Session.WriteResult) {
        val s = session ?: return emit(label, ControlMsg.Outcome.NOT_CONNECTED)
        if (target.value is Target.Demo) return emit(label, ControlMsg.Outcome.DEMO)
        if (needsLogin && !ecuLoggedIn.value) return emit(label, ControlMsg.Outcome.NEEDS_LOGIN)
        if (controlBusy.value) return
        controlBusy.value = true
        scope.launch {
            try {
                when (val r = runCatching { op(s) }.getOrElse { Session.WriteResult.Error(it.message ?: "exception") }) {
                    Session.WriteResult.Ack -> emit(label, onOk(s)())
                    is Session.WriteResult.Aborted -> when (r.abort.code) {
                        0x06010001L, 0x06010003L -> emit(label, ControlMsg.Outcome.NEEDS_LOGIN)
                        0x06010004L -> emit(label, ControlMsg.Outcome.NOT_LICENSED)
                        else -> emit(label, ControlMsg.Outcome.REFUSED, r.abort.toString())
                    }
                    is Session.WriteResult.Error -> emit(label, ControlMsg.Outcome.ERROR, r.reason)
                }
            } finally { controlBusy.value = false }
        }
    }

    /** 切 MAP 檔位（1-3）。免登入。 */
    fun setMap(mapNo: Int) = runControl("MAP $mapNo", needsLogin = false, onOk = { { ControlMsg.Outcome.MAP_OK } }) { it.setMap(mapNo) }

    /** 按儀表多功能鈕：[on]=null 依 ECU 現值反轉。結果讀回 ECU 再報「已開啟／已關閉」。 */
    fun pressFun(fn: DashFn, label: String, on: Boolean? = null) = runControl(label, needsLogin = fn !in NO_LOGIN_FUNS,
        onOk = { s -> { when (s.readDash(fn)) { true -> ControlMsg.Outcome.ON; false -> ControlMsg.Outcome.OFF; null -> ControlMsg.Outcome.ERROR } } },
    ) { it.setDash(fn, on) }

    private fun intList(key: String, def: List<Int>): List<Int> =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: def

    // ---------------- ControlFile ----------------
    fun loadRegistry() {
        val f = platform.xmlCache
        val c = platform.compactCache
        if (!Fs.exists(f) && !Fs.exists(c)) { useBuiltin(lastPlatformKey); return }
        registryLoading.value = true
        scope.launch(Dispatchers.Default) {
            try {
                runCatching {
                    runCatching { ChannelRegistry.fromCompact(Fs.readText(c)) }.getOrNull()
                        ?: ChannelRegistry.parse(Fs.readText(f)).also { saveCompact(it) }
                }
                    .onSuccess { registry.value = it; builtinKey.value = null; registryError.value = null }
                    .onFailure { useBuiltin(lastPlatformKey); registryError.value = Strings.get(R.string.err_registry) }
            } finally { registryLoading.value = false }
        }
    }

    /** 上次連到的 ECU 平台的定義代號；下次啟動直接用它的內建定義。 */
    private var lastPlatformKey: String
        get() = prefs.getString("builtinDefs", "S3")!!.takeIf(BuiltinDefs::has) ?: "S3"
        set(v) = prefs.putString("builtinDefs", v)

    /** 改用 ReX 內建的監控定義（不需要匯入任何檔案）。 */
    fun useBuiltin(key: String) {
        val k = if (BuiltinDefs.has(key)) key else "S3"
        registry.value = BuiltinDefs.forKey(k)
        builtinKey.value = k
        lastPlatformKey = k
    }

    /**
     * 連線判別平台後決定頻道表。用內建定義時依 ECU 平台換名錄；匯入的 XML 一律沿用。
     * 名錄換了就重建欄位、清歷史，錄製和同步也重開，避免舊欄位對到新資料。
     */
    private fun tableForPlatform(s: () -> Session?, t: Target, preset: Presets.Preset, p: rx.protocol.EcuPlatform?, current: rx.protocol.ChannelTable): rx.protocol.ChannelTable {
        val key = builtinKey.value ?: return current
        val want = p?.controlFile?.takeIf(BuiltinDefs::has) ?: key
        if (want == key) return current
        if (recording.value != null) stopRecording()
        useBuiltin(want)
        val reg = registry.value!!
        val table = tableFor(preset, reg)
        currentLayout = DecodedLayout(reg, table)
        history.clear(); malfSeen.value = emptyMap(); live.value = null
        log("Using built-in definitions for ${p?.label ?: want}: ${reg.channels.size} channels")
        s()?.let { session ->
            uplink.value?.let { u -> session.removeListener(u); uplink.value = null; scope.launch { u.close() }; startUplink(session, t, table) }
        }
        return table
    }

    private fun saveCompact(reg: ChannelRegistry) {
        runCatching { Fs.writeText(platform.compactCache, reg.toCompact()) }
    }

    /** [block] 回傳車輛資料 XML；解析成功就存快取。 */
    suspend fun importFrom(block: suspend () -> String) {
        runCatching {
            val xml = block()
            require(xml.contains("<Variables>")) { Strings.get(R.string.err_not_controlfile) }
            withContext(Dispatchers.Default) { ChannelRegistry.parse(xml).also { Fs.writeText(platform.xmlCache, xml); saveCompact(it) } }
        }
            .onSuccess { registry.value = it; builtinKey.value = null; registryError.value = null; log("Vehicle data imported: ${it.channels.size} channels") }
            .onFailure { registryError.value = it.message ?: it::class.simpleName }
    }

    /** 使用者選的檔案（ControlFile XML）。 */
    suspend fun importControlFile(bytes: ByteArray) = importFrom { platform.controlFileFromBytes(bytes) }

    /** 刪掉匯入的定義檔，改回內建定義。 */
    fun clearRegistry() {
        Fs.delete(platform.xmlCache); Fs.delete(platform.compactCache)
        registryError.value = null
        useBuiltin(lastPlatformKey)
    }

    // ---------------- 頻道組 ----------------
    /** 使用者自訂的頻道組（內建只有「全部」）。存成一行一組：id|名稱|absid,absid… */
    val customPresets = MutableStateFlow(loadPresets())

    private fun loadPresets(): List<Presets.Preset> =
        prefs.getString("customPresets", "")!!.lines().mapNotNull { l ->
            val p = l.split('|')
            if (p.size < 3) null else Presets.Preset(p[0], p[1], "", p[2].split(',').mapNotNull { it.toIntOrNull() })
        }

    private fun savePresets(v: List<Presets.Preset>) {
        customPresets.value = v
        prefs.putString("customPresets", v.joinToString("\n") { "${it.id}|${it.title.replace('|', ' ').replace('\n', ' ')}|${it.absids.joinToString(",")}" })
    }

    fun upsertPreset(p: Presets.Preset) {
        val l = customPresets.value
        savePresets(if (l.any { it.id == p.id }) l.map { if (it.id == p.id) p else it } else l + p)
    }

    fun deletePreset(id: String) {
        savePresets(customPresets.value.filter { it.id != id })
        if (presetId == id) presetId = Presets.ALL_CHANNELS.id
    }

    val allPresets: List<Presets.Preset> get() = listOf(Presets.ALL_CHANNELS) + customPresets.value
    fun currentPreset(): Presets.Preset = allPresets.firstOrNull { it.id == presetId } ?: Presets.ALL_CHANNELS

    // ---------------- 連線 ----------------
    private val listener = object : SessionListener {
        override fun onFrame(tNanos: Long, dir: Int, frame: Frame) {
            synchronized(framesLock) {
                recentFrames.addLast(Triple(tNanos, dir, frame))
                while (recentFrames.size > 60) recentFrames.removeFirst()
            }
        }

        override fun onRound(tNanos: Long, raw: LongArray) {
            val layout = currentLayout ?: return
            history.add(tNanos, raw)
            live.value = Live(layout, raw, tNanos, ++seq)
            val active = layout.activeMalfunctions(raw)
            if (active.isNotEmpty()) {
                val m = malfSeen.value
                val add = active.filter { it.name !in m }
                if (add.isNotEmpty()) malfSeen.value = m + add.associate { it.name to WallClock.nowMs() }
            }
            if (seq % 8 == 0L) tick.value = seq
        }

        override fun onEvent(tNanos: Long, text: String) = log(text)
    }

    var currentLayout: DecodedLayout? = null; private set

    fun recentFrames(): List<Triple<Long, Int, Frame>> = synchronized(framesLock) { recentFrames.toList() }

    fun log(text: String) {
        val ts = WallClock.format(WallClock.nowMs(), "HH:mm:ss")
        events.value = (events.value + "$ts  $text").takeLast(300)
    }

    fun connect(t: Target) {
        val reg = registry.value ?: return
        scope.launch {
            disconnectInternal()
            val preset = currentPreset()
            val table = tableFor(preset, reg)
            val layout = DecodedLayout(reg, table)
            currentLayout = layout
            history.clear(); malfSeen.value = emptyMap(); live.value = null
            val connector: suspend () -> rx.session.Transport = when (t) {
                is Target.Ecu -> ({ platform.connectEcu(t.host) })
                is Target.Demo -> {
                    val loga = withContext(Dispatchers.Default) {
                        Loga.parse(t.log?.let(Fs::readBytes) ?: platform.builtinDemoLoga() ?: error("no demo log"))
                    }
                    val src = LogaValueSource(loga, reg)
                    ({ LogReplayTransport(scope, src) })
                }
            }
            // 密碼留空＝用 aRacer 出廠預設 888888
            val pw = if (t is Target.Demo) DEFAULT_ECU_PASSWORD else secrets.get()?.trim()?.toIntOrNull() ?: DEFAULT_ECU_PASSWORD
            var created: Session? = null
            val s = Session(connector, table, pw, scope, nanoTime = platform::elapsedNanos,
                tableFor = if (t is Target.Ecu) ({ p -> tableForPlatform({ created }, t, preset, p, created?.table ?: table) }) else null,
                dashSwitches = if (t is Target.Ecu) ECU_SWITCH_FUNS else emptySet())
            created = s
            s.addListener(listener)
            if (uplinkEnabled) startUplink(s, t, table)
            session = s
            target.value = t
            loginJob = scope.launch { s.loggedIn.collect { ecuLoggedIn.value = it } }
            switchJob = scope.launch { s.dashSwitch.collect { dashSwitch.value = it } }
            stateJob = scope.launch {
                s.state.collectLatest {
                    state.value = it
                    // 連上車（不含體驗模式）就自動開始錄；重連時沿用同一份錄製
                    if (it == SessionState.Streaming && autoRecord.value && t !is Target.Demo && recording.value == null) startRecording()
                }
            }
            idleJob = scope.launch { autoStopWhenIdle(s) }
            log("Connect ${if (t is Target.Ecu) t.host else "demo"}; group ${preset.title}: ${table.entries.size} ch")
            s.start()
            if (gpsEnabled.value) gps.start()
            platform.onSessionActive(true)
        }
    }

    fun disconnect() = scope.launch { disconnectInternal(); platform.onSessionActive(false) }

    /** 收不到資料（熄火、離車）超過 [idleStopMinutes] 分鐘就自己斷線，不在背景一直重連。 */
    private suspend fun autoStopWhenIdle(s: Session) {
        var lastData = platform.elapsedNanos() / 1_000_000
        while (true) {
            kotlinx.coroutines.delay(5_000)
            if (session !== s) return
            val now = platform.elapsedNanos() / 1_000_000
            if (state.value == SessionState.Streaming) lastData = now
            val limit = idleStopMinutes
            if (limit > 0 && now - lastData > limit * 60_000L) {
                log("No data for $limit min, disconnecting")
                disconnect()
                return
            }
        }
    }

    private suspend fun disconnectInternal() {
        idleJob?.cancel(); idleJob = null
        stopRecording()
        platform.releaseEcuAp()
        uplink.value?.let { u -> session?.removeListener(u); u.close() }
        uplink.value = null
        session?.let { it.stop(); it.removeListener(listener) }
        session = null
        gps.stop()
        stateJob?.cancel(); stateJob = null
        loginJob?.cancel(); loginJob = null; ecuLoggedIn.value = false
        switchJob?.cancel(); switchJob = null; dashSwitch.value = emptyMap()
        state.value = SessionState.Idle
        target.value = null
    }

    /** 目前用的車上熱點（顯示用）。 */
    val ecuAp get() = platform.ecuAp

    // ---------------- 分身鏡射 ----------------
    val uplink = MutableStateFlow<UplinkLink?>(null)

    private fun startUplink(s: Session, t: Target, table: rx.protocol.ChannelTable) {
        val host = uplinkAddr.substringBeforeLast(':')
        val port = uplinkAddr.substringAfterLast(':').toIntOrNull() ?: UPLINK_PORT
        val id = WallClock.format(WallClock.nowMs(), "yyyyMMdd_HHmmss") + "_" + platform.deviceName.replace(' ', '_')
        val info = mapOf(
            "ECU SN" to (if (t is Target.Demo) "REPLAY" else ""), "Table ID" to "", "Product ID" to "",
            "rx.version" to platform.appVersion, "rx.target" to t.toString(), "rx.preset" to presetId,
            "rx.device" to platform.deviceName,
        )
        val u = platform.startUplink(host, port, { rx.session.uplinkHello(id, table, info) }) ?: return
        s.addListener(u)
        uplink.value = u
        log("Sync -> $host:$port (id $id)")
    }

    // ---------------- 錄製 ----------------
    fun startRecording() {
        val s = session ?: return
        val layout = currentLayout ?: return
        if (recording.value != null) return
        val label = if (target.value is Target.Demo) "DEMO" else "ECU"
        val dir = Recorder.newSessionDir(sessionsRoot, label)
        val info = mapOf(
            "ECU SN" to (if (label == "DEMO") "REPLAY" else ""),
            "Table ID" to "", "Product ID" to "",
        ) + mapOf(
            "rx.version" to platform.appVersion,
            "rx.target" to (target.value?.toString() ?: ""),
            "rx.preset" to presetId,
            "rx.device" to platform.deviceName,
            "rx.node" to s.node.toString(),
        )
        val rec = Recorder(dir, layout, info,
            extras = (if (gpsEnabled.value) gps.logColumns() else emptyList()) + powerColumns(layout),
            nanoTime = platform::elapsedNanos, clockDomain = "platform.elapsedNanos")
        rec.onEvent(platform.elapsedNanos(), "Recording started: ${layout.table.entries.size} ch, ${layout.columns.size} columns")
        s.addListener(rec)
        recording.value = rec
        log("Recording -> ${dir.name}")
    }

    /**
     * ReX 自己的欄位：MGU／BMS 電功率（V×A，保留來源電流正負號；BMS 正＝充電、負＝放電）。接在最後、VarID 用 XTune 沒用到的 -9100／-9101，
     * 其他欄位順序不變，XTune 和其他工具照樣讀前面的官方欄。車上沒 MGU／BMS 就不加。
     */
    private fun powerColumns(layout: DecodedLayout): List<Recorder.ExtraColumn> = buildList {
        fun pair(v: Int, a: Int) = layout.indexOf(v) >= 0 && layout.indexOf(a) >= 0
        fun w(raw: LongArray, v: Int, a: Int) = DerivedChannels.product(layout.physical(v, raw), layout.physical(a, raw))
        if (pair(163, 164)) add(Recorder.ExtraColumn(DerivedChannels.MGU_POWER, "ReX", "MGU_Power", 0) { w(it, 163, 164) })
        if (pair(1432, 1433)) add(Recorder.ExtraColumn(DerivedChannels.BMS_POWER, "ReX", "BMS_Power", 0) { w(it, 1432, 1433) })
    }

    fun stopRecording() {
        val rec = recording.value ?: return
        session?.removeListener(rec)
        rec.onEvent(platform.elapsedNanos(), "Recording stopped: ${rec.rounds} rounds")
        rec.close()
        recording.value = null
        log("Recording stopped: ${rec.rounds} rounds, ${rec.frames} frames")
    }

    fun mark(text: String) {
        recording.value?.mark(text)
        uplink.value?.onEvent(platform.elapsedNanos(), "Mark: $text")
        log("Mark: $text")
    }

    companion object {
        val DEFAULT_MONITOR = listOf(
            20, 22, 24, 25, 26, 27, 28, 29, 30, 33, 34, 35, 44, 45, 46, 51, 52, 53, 55, 61, 66, 70, 72, 77, 78, 82, 88,
            124, 130, 144, 1183, 1265, 1420, 1427, 1428, 1429, 1430, 1431, 1432, 1433, 1434, 1435, 1436, 1437,
        )
        /**
         * 進階直向兩條 `RaceDBVars_V`="25,34"（SA、TPS%）、進階橫向斜條 `RaceDBVars_H`="34"、
         * 巡航兩格 `SimpleDBVars`="34,55"（TPS%、AFR_WBO2）。
         */
        val DEFAULT_SLOTS = mapOf(
            // 只放每台車都有的通道（BMS／MGU 沒裝就沒有）：GPS 車速、TPS、平均油耗、Fuel_CL、馬力、扭力
            "race1" to PhoneGps.SPEED_ID, "race2" to 34, "race3" to 53, "race4" to 22, "raceH" to 34,
            "cruise1" to 34, "cruise2" to PhoneGps.SPEED_ID, "cruise3" to 22, "cruise4" to 53, "cruise5" to 1420, "cruise6" to 1427,
        )
        const val AUTO_RECORD_DEFAULT = false
        const val UPLINK_PORT = 6680
        val SLOT_MAX = mapOf("race" to 4, "cruise" to 6)

        /**
         * 儀表燈號／多功能鈕要的狀態通道，預設組沒有就連線時補上：Fuel5 #4（AT）、Mode1 #9（過熱）、Malf8 #16（引擎燈）、
         * TCStatus #125（TC）、MGU_Mode2／4 #160／162＋MGU_Energy_Percent #1398（EB）、IOModule_Indicator_Status #973（Pit／LC）。
         */
        val DASH_CHANNELS = listOf(4, 9, 16, 125, 130, 160, 162, 973, 1398)
        /**
         * 開關狀態要直接讀 ECU 物件的儀表鈕（XTune 這三顆讀的也是校正表開關）。
         * TC 的即時通道 TCStatus.TCEnable 在開關打開、車子靜止時仍是 0（實車），騎乘中來回跳；EB 沒有對應的即時通道。
         * Pit／LC 的即時位元就是原版用的來源，不必另讀。
         */
        val ECU_SWITCH_FUNS = setOf(DashFn.AT, DashFn.TC, DashFn.EB)
        val RPM_MAXES = listOf(10, 12, 16)

        fun tableFor(p: Presets.Preset, reg: ChannelRegistry): rx.protocol.ChannelTable {
            val base = if (p.id == Presets.ALL_CHANNELS.id) Presets.allAbsids(reg) else p.absids
            return Presets.table(p.copy(absids = base + DASH_CHANNELS), reg)
        }
    }
}

/** 已錄製的 session 夾。 */
data class SessionDir(val dir: Path, val sizeBytes: Long, val rounds: Long?, val frames: Long?) {
    companion object {
        private val ROUNDS = Regex("\"rounds\": (\\d+)")
        private val FRAMES = Regex("\"frames\": (\\d+)")
        fun list(root: Path): List<SessionDir> = Fs.list(root).filter { Fs.isDir(it) }
            .sortedByDescending { it.name.substringAfter('_') } // 依時間（ECU_／DEMO_ 前綴不影響），最新在上
            .map { d ->
                val metaFile = Path(d, "meta.json")
                val meta = if (Fs.exists(metaFile)) Fs.readText(metaFile) else ""
                SessionDir(d, Fs.walkFiles(d).sumOf { Fs.size(it) },
                    ROUNDS.find(meta)?.groupValues?.get(1)?.toLong(), FRAMES.find(meta)?.groupValues?.get(1)?.toLong())
            }
    }
}
