package rx.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rx.protocol.CanId
import rx.protocol.FrameParser
import rx.protocol.Rpc
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

// JVM／Android 端的探測與 UDP 監聽（java.net）。共用邏輯在 commonMain 的 Discovery／EcuFinder。

    /**
 * 連上 [host]:[port]，聽 [listenMs] 看有沒有合法 ProLink 幀。
 * 注意：ECU 只有一個座位，探測會把正在連的人（例如 XTune）擠掉。
 */
suspend fun Discovery.probe(
    host: String,
    port: Int = Discovery.ECU_PORT,
    factory: SocketFactory = SocketFactory.getDefault(),
    connectTimeoutMs: Int = 1500,
    listenMs: Long = 1300,
): Discovery.Probe? = withContext(Dispatchers.IO) {
    val t0 = System.nanoTime()
    val s = runCatching { factory.createSocket() }.getOrNull() ?: return@withContext null
    try {
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val connectMs = (System.nanoTime() - t0) / 1_000_000
        s.soTimeout = 200
        var frames = 0; var bc = false; var hb = false
        val p = FrameParser { f ->
            frames++
            if (f.canId == CanId.BROADCAST) bc = true
            if (f.canId == CanId.HEARTBEAT_BRIDGE || f.canId == CanId.HEARTBEAT_APP) hb = true
        }
        val buf = ByteArray(4096)
        val end = System.nanoTime() + listenMs * 1_000_000
        val inp = s.getInputStream()
        // ECU 未廣播（熄火待機）時橋端不主動送幀（2026-10-16 實車：埠開著但 3 s 0 位元組）。
        // 被動聽一半沒收到就送一句 session open（0x681 {00}，連線序第一句、寫入閘門白名單內），ECU 會回 0x6A1 狀態幀。
        val nudgeAt = System.nanoTime() + listenMs * 500_000
        var nudged = false
        while (System.nanoTime() < end && !(hb && frames >= 2) && !(nudged && frames >= 1)) {
            ensureActive()
            if (!nudged && frames == 0 && System.nanoTime() > nudgeAt) {
                s.getOutputStream().apply { write(Rpc(1).sessionOpen().encode()); flush() }
                nudged = true
            }
            val n = try { inp.read(buf) } catch (_: SocketTimeoutException) { 0 }
            if (n < 0) break
            if (n > 0) p.feed(buf, 0, n)
        }
        if (frames > 0) Discovery.Probe(host, port, frames, bc, hb, connectMs) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    } finally {
        runCatching { s.close() }
    }
}


    /** 聽 UDP 公告直到被取消。 */
suspend fun Discovery.listenUdp(port: Int = Discovery.UDP_PORT, onAnnounce: (Discovery.Announcement) -> Unit) = withContext(Dispatchers.IO) {
    val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(port)) } }
        .getOrNull() ?: return@withContext
    sock.soTimeout = 300
    try {
        val buf = ByteArray(512)
        while (true) {
            ensureActive()
            val pk = DatagramPacket(buf, buf.size)
            try { sock.receive(pk) } catch (_: SocketTimeoutException) { continue }
            Discovery.Announcement.parse(pk.data, pk.length, pk.address.hostAddress ?: continue)?.let(onAnnounce)
        }
    } finally {
        sock.close()
    }
}


/** JVM 探測器：route＝SocketFactory（Android 綁 WiFi 用）。 */
class JvmProber(private val udp: Boolean = true) : EcuFinder.Prober {
    override suspend fun probe(host: String, port: Int, route: Any?, connectTimeoutMs: Int, listenMs: Long) =
        Discovery.probe(host, port, route as? SocketFactory ?: SocketFactory.getDefault(), connectTimeoutMs, listenMs)
    override val canListenUdp get() = udp
    override suspend fun listenUdp(port: Int, onAnnounce: (Discovery.Announcement) -> Unit) = Discovery.listenUdp(port, onAnnounce)
}

/** 舊呼叫法（Android／測試）：[ecuApFactory] 綁 ECU 熱點用的 SocketFactory。 */
fun EcuFinder(
    ecuApFactory: () -> SocketFactory = { SocketFactory.getDefault() },
    subnets: () -> List<Discovery.Subnet> = { emptyList() },
    port: Int = Discovery.ECU_PORT,
    udpPort: Int = Discovery.UDP_PORT,
    udpEnabled: Boolean = true,
) = EcuFinder(JvmProber(udpEnabled), ecuApFactory, subnets, port, udpPort, udpEnabled)
