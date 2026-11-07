package rx.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {

    /** 在 127.0.0.1 隨機埠開一顆離線重播（真的 TCP，行為同 offline playback）。 */
    private class FakeEcu : AutoCloseable {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val port get() = server.localPort
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init {
            scope.launch {
                while (true) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    val sim = LogReplayTransport(scope, { _, _, _ -> 0L })
                    launch { runCatching { val o = s.getOutputStream(); while (true) { o.write(sim.receive() ?: break); o.flush() } } }
                    launch { runCatching { val i = s.getInputStream(); val b = ByteArray(1024); while (true) { val n = i.read(b); if (n < 0) break; sim.send(b.copyOf(n)) } }; sim.close(); s.close() }
                }
            }
        }
        override fun close() { scope.cancel(); server.close() }
    }

    private fun announcement(name: String, ssid: String, pw: String, type: Int): ByteArray {
        val b = ByteArray(120)
        name.toByteArray().copyInto(b, 0)
        ssid.toByteArray().copyInto(b, 10)
        b[42] = 0
        pw.toByteArray().copyInto(b, 43)
        b[108] = type.toByte()
        return b
    }

    @Test
    fun parsesXtuneAnnouncementLayout() {
        val b = announcement("aRacer MX", "My ECU", "12345678", 7)
        val a = Discovery.Announcement.parse(b, b.size, "192.168.88.88")!!
        assertEquals(1, a.deviceType); assertEquals("My ECU", a.ssid); assertEquals("12345678", a.apPassword); assertEquals(7, a.typeCode)
        assertEquals(33, Discovery.Announcement.parse(announcement("ECU MX2", "x", "", 0), 120, "h")!!.deviceType)
        assertNull(Discovery.Announcement.parse(announcement("Printer", "x", "", 0), 120, "h"), "不認得的名稱要忽略")
        assertNull(Discovery.Announcement.parse(b, 100, "h"), "太短要忽略")
        assertNull(Discovery.Announcement.parse(announcement("aRacer SX", "x", "", 0), 120, "h")!!.apPassword)
    }

    @Test
    fun parsesWifiQr() {
        assertEquals("My ECU" to "12345678", Discovery.parseWifiQr("WIFI:T:WPA;S:My ECU;P:12345678;;"))
        assertEquals("a;b" to "p\\q", Discovery.parseWifiQr("WIFI:S:a\\;b;T:WPA;P:p\\\\q;;"))
        assertEquals("Open" to null, Discovery.parseWifiQr("WIFI:T:nopass;S:Open;P:;;"))
        assertNull(Discovery.parseWifiQr("https://example.com"))
    }

    @Test
    fun probeAcceptsEcuAndRejectsOtherServers() = runBlocking {
        FakeEcu().use { ecu ->
            val p = Discovery.probe("127.0.0.1", ecu.port)
            assertNotNull(p); assertTrue(p.heartbeat, "要看到 0x701/0x70A 心跳")
        }
        // 開著埠但不是 ECU（例如 HTTP 伺服器回一串字）
        val other = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread { runCatching { other.accept().use { it.getOutputStream().write("HTTP/1.1 400 Bad\r\n\r\n".toByteArray()); Thread.sleep(1500) } } }
        assertNull(Discovery.probe("127.0.0.1", other.localPort))
        other.close()
        // 沒開的埠
        assertNull(Discovery.probe("127.0.0.1", 1))
    }

    @Test
    fun finderSweepsSubnetAndFindsOnlyTheEcu() = runBlocking {
        FakeEcu().use { ecu ->
            val events = ArrayList<EcuFinder.Event>()
            // 127.0.0.1 有 ECU；127.0.0.2..5 都是 loopback 但沒人聽這個埠（ServerSocket 綁死 127.0.0.1）
            val finder = EcuFinder(subnets = { listOf(Discovery.Subnet("lo", "127.0.0", hosts = 1..5)) }, port = ecu.port, udpEnabled = false)
            val t0 = System.nanoTime()
            val found = finder.find(known = emptyList()) { synchronized(events) { events += it } }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(listOf("127.0.0.1"), found.map { it.probe.host })
            assertEquals(Discovery.Source.SWEEP, found.single().source)
            assertTrue(events.any { it is EcuFinder.Event.Step && "Scanning" in it.text })
            println("  掃 5 台找到 1 顆 ECU，${ms}ms")
        }
    }

    @Test
    fun finderStopsEarlyOnKnownHost() = runBlocking {
        FakeEcu().use { ecu ->
            var swept = false
            val finder = EcuFinder(subnets = { swept = true; emptyList() }, port = ecu.port, udpEnabled = false)
            val found = finder.find(known = listOf(EcuFinder.KnownHost("127.0.0.1")))
            assertEquals(Discovery.Source.KNOWN, found.single().source)
            assertTrue(!swept, "上次的位址命中就不該再掃網段")
        }
    }

    @Test
    fun finderUsesUdpAnnouncement() = runBlocking {
        FakeEcu().use { ecu ->
            val udpPort = DatagramSocket(0).use { it.localPort }
            val finder = EcuFinder(subnets = { emptyList() }, port = ecu.port, udpPort = udpPort)
            val sender = thread {
                Thread.sleep(400)
                DatagramSocket().use { s ->
                    val b = announcement("aRacer MX", "My ECU", "pw123456", 1)
                    repeat(3) { s.send(DatagramPacket(b, b.size, InetAddress.getByName("127.0.0.1"), udpPort)); Thread.sleep(200) }
                }
            }
            val found = finder.find(known = emptyList(), sweep = false)
            sender.join()
            val f = found.single()
            assertEquals(Discovery.Source.UDP, f.source)
            assertEquals("My ECU", f.announcement?.ssid)
        }
    }
}
