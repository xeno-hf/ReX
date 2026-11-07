package rx.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import rx.protocol.monotonicNanos

/**
 * ECU 自動尋找。
 *
 * XTune 的做法：
 * - ECU 開自己的 WiFi 熱點，ECU 本身固定在 **192.168.88.88:6666**
 * - App 一直聽 UDP 8888；ECU 公告封包帶「名稱／熱點 SSID／熱點密碼／型別碼」
 * - 已知 SSID＋密碼時用 WifiNetworkSpecifier 讓手機自動加入 ECU 熱點（QuickWiFi A/B、Fast Link QR）
 * - ECU 也可設定成連到別的 WiFi（STA，例如手機熱點），那時 IP 由那個網路決定
 *
 * RX 在這之上再加「掃網段」：實車 STA 模式下 ECU 待機時不發 UDP 公告，
 * 只靠公告找不到。每個候選都要收到真的 ProLink 幀（ECU WiFi 橋 0x701/0x70A 心跳 1Hz 永不停）才算數，
 * 不會把別的開著 6666 的設備誤認成 ECU。
 */
object Discovery {
    const val ECU_AP_HOST = "192.168.88.88"
    const val ECU_PORT = 6666
    const val UDP_PORT = 8888

    /** UDP 8888 公告（≥109B）：名稱[0,9)、SSID[10,42)、旗標@42、熱點密碼[43,107)、型別碼@108。 */
    data class Announcement(
        val host: String,
        val name: String,
        val ssid: String,
        val apPassword: String?,
        val deviceType: Int,
        val typeCode: Int,
        val flag: Int,
    ) {
        companion object {
            private val NAMES = listOf("aRacer MX" to 1, "aRacer SX" to 2, "aRacer S4" to 4, "aRacer RZ" to 5,
                "ECU MX2" to 33, "ECU SX2" to 34, "ECU SxS" to 37)

            fun parse(b: ByteArray, len: Int, host: String): Announcement? {
                if (len < 109) return null
                fun str(from: Int, to: Int) = b.decodeToString(from, to).replace("\u0000", "").trim()
                val name = str(0, 9)
                // XTune 是逐一 contains，後面的覆蓋前面的；這裡取最後一個符合的，行為相同
                val type = NAMES.lastOrNull { name.contains(it.first) }?.second ?: return null
                return Announcement(host, name, str(10, 42), str(43, 107).ifEmpty { null }, type,
                    b[108].toInt() and 0xFF, b[42].toInt() and 0xFF)
            }
        }
    }

    enum class Source(val label: String) {
        KNOWN("last used"), ECU_AP("ECU hotspot"), UDP("ECU broadcast"), SWEEP("subnet scan"), MANUAL("manual")
    }

    data class Probe(
        val host: String,
        val port: Int,
        val frames: Int,
        /** 收到 0x181 監控串流＝ECU 醒著且正在廣播（通常是別的 App 剛武裝過）。 */
        val broadcasting: Boolean,
        val heartbeat: Boolean,
        val connectMs: Long,
    )

    data class Found(val probe: Probe, val source: Source, val announcement: Announcement?)

    /** 一個要掃的網段。[hosts] 預設 1..254。[route] 平台決定 socket 走哪個網路（Android＝綁 WiFi 的 SocketFactory；null＝預設路由）。 */
    data class Subnet(val iface: String, val prefix: String, val route: Any? = null, val hosts: IntRange = 1..254) {
        override fun toString() = "$prefix.0/24（$iface）"
    }

    /** XTune Fast Link／一般 WiFi QR：`WIFI:T:WPA;S:<ssid>;P:<password>;;` */
    fun parseWifiQr(text: String): Pair<String, String?>? {
        val t = text.trim()
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null
        val ssid = Regex("(?:^|;|:)S:((?:\\\\.|[^;])*)").find(t)?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1") ?: return null
        val pw = Regex("(?:^|;)P:((?:\\\\.|[^;])*)").find(t)?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1")
        return ssid to pw?.ifEmpty { null }
    }

}

/**
 * 找 ECU 的總指揮：上次的 IP、ECU 熱點、UDP 公告、掃網段四路並行，
 * 前兩路一命中就提早結束（小白最常見情況：手機剛連上 ECU 熱點）。
 */
class EcuFinder(
    private val prober: Prober,
    private val ecuApRoute: () -> Any? = { null },
    private val subnets: () -> List<Discovery.Subnet> = { emptyList() },
    private val port: Int = Discovery.ECU_PORT,
    private val udpPort: Int = Discovery.UDP_PORT,
    private val udpEnabled: Boolean = true,
) {
    sealed interface Event {
        data class Step(val text: String) : Event
        data class Announced(val a: Discovery.Announcement) : Event
        data class Hit(val found: Discovery.Found) : Event
        data class Done(val found: List<Discovery.Found>, val probed: Int) : Event
    }

    data class KnownHost(val host: String, val route: Any? = null)

    /** 平台實作：TCP 探測與 UDP 公告監聽（iOS 收廣播要 Apple 核准的 entitlement，可回傳 false 不聽）。 */
    interface Prober {
        suspend fun probe(host: String, port: Int, route: Any?, connectTimeoutMs: Int, listenMs: Long): Discovery.Probe?
        val canListenUdp: Boolean get() = false
        suspend fun listenUdp(port: Int, onAnnounce: (Discovery.Announcement) -> Unit) {}
    }

    suspend fun find(known: List<KnownHost>, sweep: Boolean = true, minUdpMs: Long = 2500, onEvent: (Event) -> Unit = {}): List<Discovery.Found> = coroutineScope {
        val outer = this
        val lock = SynchronizedObject()
        val found = LinkedHashMap<String, Discovery.Found>()
        val probed = HashSet<String>()
        val announcements = HashMap<String, Discovery.Announcement>()
        val gate = Semaphore(48)

        fun foundEmpty() = synchronized(lock) { found.isEmpty() }

        suspend fun check(host: String, source: Discovery.Source, route: Any?, connectTimeout: Int, listen: Long = 1300) {
            if (!synchronized(lock) { probed.add(host) }) return
            val p = gate.withPermit { prober.probe(host, port, route, connectTimeout, listen) } ?: return
            val f = synchronized(lock) {
                Discovery.Found(p, source, announcements[host]).takeIf { found[host] == null }?.also { found[host] = it }
            }
            if (f != null) onEvent(Event.Hit(f))
        }

        val udpJob = if (udpEnabled && prober.canListenUdp) launch {
            // UDP 8888 可能被 XTune 的背景服務占著（bind 失敗）。聽不到公告不能拖垮整個尋找。
            runCatching { prober.listenUdp(udpPort) { a ->
                if (synchronized(lock) { announcements.put(a.host, a) } == null) {
                    onEvent(Event.Announced(a))
                    outer.launch { check(a.host, Discovery.Source.UDP, null, 1500) }
                }
            } }.onFailure { if (it is CancellationException) throw it; onEvent(Event.Step("Cannot listen on UDP $udpPort (${it.message}), scanning only")) }
        } else null
        val t0 = monotonicNanos()

        // 第一段：上次的位址＋ECU 熱點固定位址
        onEvent(Event.Step("Checking last ECU and ECU hotspot ${Discovery.ECU_AP_HOST}"))
        (known.map { async { check(it.host, Discovery.Source.KNOWN, it.route, 1500) } } +
            async { check(Discovery.ECU_AP_HOST, Discovery.Source.ECU_AP, ecuApRoute(), 1500) }).awaitAll()

        // 第二段：掃網段（第一段沒中才掃）
        if (foundEmpty() && sweep) {
            val nets = subnets()
            if (nets.isEmpty()) onEvent(Event.Step("Phone is not on any local network (WiFi or hotspot)"))
            for (net in nets) {
                onEvent(Event.Step("Scanning $net"))
                net.hosts.map { h -> async { check("${net.prefix}.$h", Discovery.Source.SWEEP, net.route, 350) } }.awaitAll()
            }
        }

        // 讓 UDP 至少聽滿 minUdpMs（ECU 公告約每秒一次；剛連上熱點時最常見）
        if (foundEmpty() && udpJob != null) {
            val left = minUdpMs - (monotonicNanos() - t0) / 1_000_000
            if (left > 0) { onEvent(Event.Step("Listening for ECU broadcast…")); withTimeoutOrNull(left) { while (foundEmpty()) delay(100) } }
        }
        udpJob?.cancel()
        // 等 UDP 觸發、還在跑的探測收尾（最多一個探測週期）
        withTimeoutOrNull(1800) { outer.coroutineContext[Job]!!.children.filter { it != udpJob }.forEach { it.join() } }
        val list = synchronized(lock) { found.values.sortedBy { it.source.ordinal } }
        onEvent(Event.Done(list, synchronized(lock) { probed.size }))
        list
    }
}
