package rx.session

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import rx.session.sync.SealedInputStream
import rx.session.sync.SealedOutputStream
import rx.session.sync.SyncClient
import rx.session.sync.SyncCrypto
import rx.session.sync.SyncHost
import rx.session.sync.SyncServer
import rx.session.sync.SyncWire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncTest {
    private class Host : SyncHost {
        val peers = HashMap<String, SyncWire.Peer>()
        @Volatile var window: SyncWire.PairingWindow? = null
        val rides = HashMap<String, ByteArray>()
        val rounds = ArrayList<LongArray>()
        val events = ArrayList<String>()
        var hello: String? = null
        val liveDone = java.util.concurrent.CountDownLatch(1)
        var liveEnded = false
        val logs = ArrayList<String>()

        override fun findPeer(id: String) = synchronized(peers) { peers[id] }
        override fun pairing() = window
        /** 存之前先跑：測試用來卡住或弄壞存檔。 */
        @Volatile var beforeStore: () -> Unit = {}
        override fun onPaired(peer: SyncWire.Peer) { beforeStore(); synchronized(peers) { peers[peer.id] = peer } }
        override fun live(peer: SyncWire.Peer, input: InputStream): Boolean {
            liveEnded = UplinkStream.read(input, object : UplinkStream.Handler {
                override fun onHello(text: String) { hello = text }
                override fun onFrame(tNanos: Long, dir: Int, frame: rx.protocol.Frame) {}
                override fun onRound(tNanos: Long, raw: LongArray) { synchronized(rounds) { rounds += raw } }
                override fun onEvent(tNanos: Long, text: String) { synchronized(events) { events += text } }
            })
            liveDone.countDown()
            return liveEnded
        }
        override fun missingRides(peer: SyncWire.Peer, names: List<String>) = names.filter { it !in rides }
        override fun storeRide(peer: SyncWire.Peer, name: String, input: InputStream, length: Long): ByteArray {
            require(!name.contains('/')) { "bad name" }
            val b = input.readBytes()
            check(b.size.toLong() == length)
            rides[name] = b
            return SyncCrypto.sha256(b)
        }
        override fun defs(peer: SyncWire.Peer, request: Map<String, String>): ByteArray {
            require(request["map_type"] == "AMX") { "No definitions for ${request["map_type"]}" }
            return "{\"format\":\"rx-mapdefs-1\",\"c\":${request["c"]}}".toByteArray()
        }
        override fun log(line: String) { synchronized(logs) { logs += line } }
    }

    private val host = Host()
    private val server = SyncServer(SyncWire.Identity("a1b2c3d4e5f6a1b2c3d4e5f6", "Desktop"), host, InetAddress.getLoopbackAddress(), port = 0).also { it.start() }
    private val phone = SyncWire.Identity("0123456789abcdef01234567", "Phone")
    private fun client() = SyncClient(InetAddress.getLoopbackAddress().hostAddress, server.boundPort, phone)

    @AfterTest fun stop() = server.stop()

    private fun pair(): SyncWire.Peer {
        host.window = SyncWire.PairingWindow("ABCD2345", System.currentTimeMillis() + 60_000)
        return client().pair("abcd-2345")
    }

    /** 電腦產生的 16 字碼，照畫面顯示（分組、可能小寫）打進手機，要能配對（手機輸入框用同一份 [SyncCode]）。 */
    @Test
    fun generatedCodeAsDisplayedPairs() {
        val code = SyncCrypto.newPairingCode()
        val typed = SyncCrypto.showCode(code).lowercase()
        assertEquals(true, rx.session.SyncCode.complete(typed))
        assertEquals(false, rx.session.SyncCode.complete(typed.dropLast(1)))
        host.window = SyncWire.PairingWindow(code, System.currentTimeMillis() + 60_000)
        val pc = client().pair(rx.session.SyncCode.normalize(typed))
        assertEquals("Desktop", pc.name)
    }

    @Test
    fun pairingNeedsTheRightCodeAndAnOpenWindow() {
        assertEquals("no-pairing", assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2345") }.reason)
        host.window = SyncWire.PairingWindow("ABCD2345", System.currentTimeMillis() + 60_000)
        assertEquals("bad-code", assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2346ABCD2346") }.reason)
        val pc = client().pair("abcd 2345")
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6", pc.id); assertEquals("Desktop", pc.name)
        // 手機收到確認時電腦一定已經存好
        assertContentEquals(pc.token, assertNotNull(host.findPeer("0123456789abcdef01234567")).token)
        // 一組碼只配一台
        assertEquals("no-pairing", assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2345") }.reason)
    }

    /** 電腦還沒存好之前，手機不能收到配對成功。 */
    @Test
    fun phoneIsConfirmedOnlyAfterTheComputerStoredIt() {
        val storing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        host.beforeStore = { storing.countDown(); release.await() }
        host.window = SyncWire.PairingWindow("ABCD2345", System.currentTimeMillis() + 60_000)
        val done = java.util.concurrent.CompletableFuture.supplyAsync { client().pair("ABCD2345") }
        try {
            assertTrue(storing.await(10, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals(false, done.isDone)
        } finally {
            release.countDown()
        }
        val pc = done.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertContentEquals(pc.token, assertNotNull(host.findPeer("0123456789abcdef01234567")).token)
    }

    /** 電腦存不進去：手機配對失敗、電腦沒有這台，碼也用掉了。 */
    @Test
    fun storageFailureFailsThePairingOnThePhone() {
        host.beforeStore = { throw java.io.IOException("disk full") }
        host.window = SyncWire.PairingWindow("ABCD2345", System.currentTimeMillis() + 60_000)
        assertFailsWith<java.io.IOException> { client().pair("ABCD2345") }
        assertEquals(null, host.findPeer("0123456789abcdef01234567"))
        host.beforeStore = {}
        assertEquals("no-pairing", assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2345") }.reason)
    }

    @Test
    fun badPhoneIdAndCancelledWindowsCannotPair() {
        host.window = SyncWire.PairingWindow("ABCD2345", System.currentTimeMillis() + 60_000)
        val evil = SyncClient(InetAddress.getLoopbackAddress().hostAddress, server.boundPort, SyncWire.Identity("../../x", "Phone"))
        assertEquals("bad-id", assertFailsWith<SyncWire.SyncRefused> { evil.pair("ABCD2345") }.reason)
        host.window!!.cancel()
        assertEquals("no-pairing", assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2345") }.reason)
    }

    @Test
    fun wrongCodeFiveTimesKillsTheCode() {
        host.window = SyncWire.PairingWindow("ABCD2345", System.currentTimeMillis() + 60_000)
        repeat(5) { assertFailsWith<SyncWire.SyncRefused> { client().pair("ZZZZ2222") } }
        assertEquals("no-pairing", assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2345") }.reason)
    }

    @Test
    fun unpairedOrForgedTokenIsRefused() {
        val stranger = SyncWire.Peer("a1b2c3d4e5f6a1b2c3d4e5f6", "Desktop", ByteArray(32), 0)
        assertEquals("unknown-device", assertFailsWith<SyncWire.SyncRefused> { client().sync(stranger, emptyList(), { null }, null) }.reason)
        pair()
        assertEquals("auth", assertFailsWith<SyncWire.SyncRefused> { client().sync(stranger, emptyList(), { null }, null) }.reason)
    }

    @Test
    fun uploadsOnlyMissingRidesAndFetchesDefs() {
        val pc = pair()
        host.rides["ride_old"] = byteArrayOf(1)
        val tmp = Files.createTempDirectory("sync").toFile()
        val payload = ByteArray(200_000) { (it * 31).toByte() }
        val r = client().sync(pc, listOf("ride_old", "ride_new"), { name ->
            File(tmp, "$name.xbl").also { it.writeBytes(payload) }
        }, mapOf("map_type" to "AMX", "c" to "17"))
        assertEquals(listOf("ride_new"), r.uploaded)
        assertEquals(1, r.alreadyThere)
        assertContentEquals(payload, host.rides["ride_new"])
        assertEquals("{\"format\":\"rx-mapdefs-1\",\"c\":17}", String(r.defs!!))
        // 給不出定義包：照樣同步錄製，只回原因
        val r2 = client().sync(pc, listOf("ride_new"), { null }, mapOf("map_type" to "AMX2"))
        assertTrue(r2.uploaded.isEmpty() && r2.defs == null)
        assertEquals("No definitions for AMX2", r2.defsError)
        tmp.deleteRecursively()
    }

    @Test
    fun liveDataGoesThroughTheEncryptedLink() = runBlocking {
        val pc = pair()
        val table = rx.protocol.ChannelTable(listOf(rx.protocol.ChannelTable.Entry(26, 2), rx.protocol.ChannelTable.Entry(34, 2)))
        val up = Uplink(InetAddress.getLoopbackAddress().hostAddress, server.boundPort, { uplinkHello("0123456789abcdef01234567", table, mapOf("rx.target" to "Mini X")) },
            CoroutineScope(SupervisorJob() + Dispatchers.IO), secure = client().liveHandshake(pc))
        val t0 = System.nanoTime()
        while (!up.connected && System.nanoTime() - t0 < 5_000_000_000L) Thread.sleep(20)
        assertTrue(up.connected, "live link connected: ${up.lastError}")
        up.onEvent(1, "hello from the bike")
        up.onRound(2, longArrayOf(9696, 912))
        up.close(3_000)
        assertTrue(host.liveDone.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(host.liveEnded)
        assertTrue(host.hello!!.contains("table=26:2,34:2"))
        assertEquals(listOf("hello from the bike"), host.events)
        assertContentEquals(longArrayOf(9696, 912), host.rounds.single())
    }

    @Test
    fun sealedStreamRejectsTampering() {
        val key = SyncCrypto.randomBytes(32)
        val buf = ByteArrayOutputStream()
        SealedOutputStream(buf, key).apply { write("riding data".toByteArray()); flush() }
        val ok = SealedInputStream(ByteArrayInputStream(buf.toByteArray()), key).readBytes()
        assertEquals("riding data", String(ok))
        val bad = buf.toByteArray().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { SealedInputStream(ByteArrayInputStream(bad), key).readBytes() }
        assertFailsWith<Exception> { SealedInputStream(ByteArrayInputStream(buf.toByteArray()), SyncCrypto.randomBytes(32)).readBytes() }
        assertNotNull(SyncCrypto.newPairingCode().takeIf { it.length == SyncCrypto.CODE_LENGTH })
    }
}
