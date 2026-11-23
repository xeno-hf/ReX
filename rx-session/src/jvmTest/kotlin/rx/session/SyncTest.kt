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
        override fun missingRides(peer: SyncWire.Peer, names: List<String>) = synchronized(rides) { names.filter { it !in rides } }
        override fun storeRide(peer: SyncWire.Peer, name: String, input: InputStream, length: Long): ByteArray {
            require(!name.contains('/')) { "bad name" }
            val b = input.readBytes()
            check(b.size.toLong() == length)
            synchronized(rides) { rides[name] = b }
            return SyncCrypto.sha256(b)
        }
        override fun defs(peer: SyncWire.Peer, request: Map<String, String>): ByteArray {
            require(request["map_type"] == "AMX") { "No definitions for ${request["map_type"]}" }
            return "{\"format\":\"rx-mapdefs-1\",\"c\":${request["c"]}}".toByteArray()
        }
        override fun log(line: String) { synchronized(logs) { logs += line } }
        @Volatile var tunePackAction: (SyncWire.Peer) -> ByteArray = { throw UnsupportedOperationException("This computer does not provide tune packs") }
        override fun tunePack(peer: SyncWire.Peer, request: Map<String, String>): ByteArray = tunePackAction(peer)
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

    /** 連不上、回應不對、握手中被收線都換成畫面看得懂的原因（原本的例外在 cause）；收過壞公鑰之後照樣能配對。 */
    @Test
    fun failuresBecomeReasonsAndPairingStillWorksAfterABadKey() {
        val loop = InetAddress.getLoopbackAddress()
        // 綁住但不 listen 的埠：一定連不上，測試期間也不會被別人拿去用
        java.net.Socket().use { bound ->
            bound.bind(java.net.InetSocketAddress(loop, 0))
            val unreachable = assertFailsWith<SyncWire.SyncRefused> { SyncClient(loop.hostAddress, bound.localPort, phone).pair("ABCD2345") }
            assertEquals(SyncWire.UNREACHABLE, unreachable.reason)
            assertNotNull(unreachable.cause)
        }
        // 假電腦：第一次回不在曲線上的公鑰，第二次讀完就收線
        java.net.ServerSocket(0, 2, loop).use { fake -> pairAfterBadReplies(fake) }
        assertEquals("Desktop", pair().name)
        // 位址的 port 超出範圍也是連不上，不是原始例外
        assertEquals(SyncWire.UNREACHABLE, assertFailsWith<SyncWire.SyncRefused> { SyncClient(loop.hostAddress, 65536, phone).pair("ABCD2345") }.reason)
    }

    /** 電腦沒回任何東西就收線（舊版 XB 或配對剛被刪，分不出來）＝closed-on-tune-pack；回到一半斷線、逾時＝斷線；拒絕照原因；完整回覆照收。 */
    @Test
    fun tunePackReplyKinds() {
        fun ask(inp: InputStream) = SyncClient.requestTunePack(inp, ByteArrayOutputStream(), mapOf("c" to "17")).let { Triple(it.data, it.error, it.local) }
        fun ask(reply: ByteArray) = ask(ByteArrayInputStream(reply))
        fun frame(type: Byte, body: ByteArray) = ByteArrayOutputStream().also { rx.session.sync.SyncOps.send(it, type, body) }.toByteArray()
        assertEquals(Triple(null, SyncWire.CLOSED_ON_TUNE_PACK, true), ask(ByteArray(0)))
        val full = frame(rx.session.sync.SyncOps.TUNEPACK_REPLY, ByteArray(100) { it.toByte() })
        assertEquals(Triple(null, SyncWire.CONNECTION_LOST, true), ask(full.copyOf(full.size - 10)))
        assertEquals(Triple(null, SyncWire.CONNECTION_LOST, true), ask(full.copyOf(3)))
        assertContentEquals(ByteArray(100) { it.toByte() }, assertNotNull(ask(full).first))
        // 電腦送來的原因照原文、標成不是本機判斷，即使字面跟本機代碼一樣
        assertEquals(Triple(null, "connection-lost", false), ask(frame(rx.session.sync.SyncOps.ERROR, "connection-lost".toByteArray())))
        val timeout = object : InputStream() { override fun read(): Int = throw java.net.SocketTimeoutException("Read timed out") }
        assertEquals(Triple(null, SyncWire.CONNECTION_LOST, true), ask(timeout))
        val reset = object : InputStream() { override fun read(): Int = throw java.net.SocketException("Connection reset") }
        assertEquals(Triple(null, SyncWire.CLOSED_ON_TUNE_PACK, true), ask(reset))
        // 透過加密串流：第一筆密文收到一部分（標頭一半、密文一半）才被 reset ＝ 斷線；一個字都沒收到就 reset ＝ 收線
        val key = ByteArray(32) { 7 }
        val sealed = ByteArrayOutputStream().also { o -> SealedOutputStream(o, key).use { it.write(full) } }.toByteArray()
        fun resetAfter(n: Int) = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < n) sealed[i++].toInt() and 255 else throw java.net.SocketException("Connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= n) throw java.net.SocketException("Connection reset")
                val k = minOf(len, n - i); sealed.copyInto(b, off, i, i + k); i += k; return k
            }
        }
        assertEquals(Triple(null, SyncWire.CLOSED_ON_TUNE_PACK, true), ask(SealedInputStream(resetAfter(0), key)))
        assertEquals(Triple(null, SyncWire.CONNECTION_LOST, true), ask(SealedInputStream(resetAfter(2), key)))
        assertEquals(Triple(null, SyncWire.CONNECTION_LOST, true), ask(SealedInputStream(resetAfter(20), key)))
        assertContentEquals(ByteArray(100) { it.toByte() }, assertNotNull(ask(SealedInputStream(resetAfter(sealed.size), key)).first))
    }

    /** 真的 SyncServer：要調校包時配對剛被刪掉，電腦沒回就收線（跟舊版 XB 一樣）。前面傳的錄製照算；電腦送來的原因照原文、連線照常結束。 */
    @Test
    fun closeOnTunePackKeepsEarlierUploads() {
        val pc = pair()
        val tmp = Files.createTempDirectory("sync").toFile()
        try {
            val pack: (String) -> File? = { name -> File(tmp, "$name.xbl").also { it.writeBytes(byteArrayOf(1, 2, 3)) } }
            host.tunePackAction = { throw IllegalStateException("connection-lost") }
            val sent = client().sync(pc, listOf("ride_a"), pack, null, mapOf("c" to "17"))
            assertEquals(listOf("ride_a"), sent.uploaded)
            assertEquals("connection-lost", sent.tunePackError)
            assertEquals(false, sent.tunePackErrorLocal)
            // 電腦送來的原因不影響收尾：手機照樣送結束訊息
            assertTrue(waitFor { synchronized(host.logs) { "Sync: Phone finished" in host.logs } }, "server got END: ${synchronized(host.logs) { host.logs.toList() }}")
            host.tunePackAction = { peer -> synchronized(host.peers) { host.peers.remove(peer.id) }; byteArrayOf(1) }
            val closed = client().sync(pc, listOf("ride_a", "ride_b"), pack, null, mapOf("c" to "17"))
            assertEquals(listOf("ride_b"), closed.uploaded)
            assertEquals(1, closed.alreadyThere)
            assertEquals(SyncWire.CLOSED_ON_TUNE_PACK, closed.tunePackError)
            assertEquals(true, closed.tunePackErrorLocal)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** onProgress 丟的 I/O 例外是呼叫端自己的錯，原樣往外丟，不算斷線。 */
    @Test
    fun progressCallbackErrorsAreNotConnectionLost() {
        val pc = pair()
        val tmp = Files.createTempDirectory("sync").toFile()
        try {
            val boom = java.io.IOException("progress storage full")
            val thrown = assertFailsWith<java.io.IOException> {
                client().sync(pc, listOf("ride_p"), { name -> File(tmp, "$name.xbl").also { it.writeBytes(byteArrayOf(5)) } }, null) { _, _, _ -> throw boom }
            }
            assertTrue(thrown === boom, "got $thrown")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 錄製檔送到一半讀不下去：本機錯誤（UncheckedIOException），不是斷線；電腦不會存下半個檔，之後照常同步。 */
    @Test
    fun rideReadFailureMidUploadIsALocalError() {
        val pc = pair()
        val tmp = Files.createTempDirectory("sync").toFile()
        try {
            val pack: (String) -> File? = { name -> File(tmp, "$name.xbl").also { it.writeBytes(ByteArray(100_000) { i -> i.toByte() }) } }
            val c = client()
            c.openRide = { f ->
                object : java.io.FileInputStream(f) {
                    var left = 40_000
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (left <= 0) throw java.io.IOException("disk read failed")
                        val n = super.read(b, off, minOf(len, left)); left -= n; return n
                    }
                }
            }
            val e = assertFailsWith<java.io.UncheckedIOException> { c.sync(pc, listOf("ride_m"), pack, null) }
            assertEquals("disk read failed", e.cause?.message)
            // 電腦那邊處理完這條斷掉的連線再看：連收線時也不能存下半個檔
            assertTrue(waitFor { server.activeSessions(phone.id) == 0 }, "server session did not end")
            assertEquals(null, synchronized(host.rides) { host.rides["ride_m"] })
            val again = client().sync(pc, listOf("ride_m"), pack, null)
            assertEquals(listOf("ride_m"), again.uploaded)
            assertEquals(100_000, synchronized(host.rides) { host.rides["ride_m"] }?.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    private fun pairAfterBadReplies(fake: java.net.ServerSocket) {
        val t = Thread {
            for (round in 0 until 2) fake.accept().use { s ->
                val inp = s.getInputStream(); val out = s.getOutputStream()
                inp.readNBytes(SyncWire.MAGIC.size); SyncWire.readMsg(inp)
                if (round == 0) {
                    val epk = SyncCrypto.ecKeyPair().publicDer.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
                    SyncWire.writeMsg(out, linkedMapOf("op" to "pair", "id" to "a1b2c3d4e5f6a1b2c3d4e5f6", "name" to "Fake", "epk" to SyncCrypto.hex(epk), "sn" to "00"))
                    runCatching { SyncWire.readMsg(inp) } // 等手機放棄、收線
                }
            }
        }.apply { isDaemon = true; start() }
        val fakeClient = SyncClient(fake.inetAddress.hostAddress, fake.localPort, phone)
        assertEquals(SyncWire.BAD_REPLY, assertFailsWith<SyncWire.SyncRefused> { fakeClient.pair("ABCD2345") }.reason)
        assertEquals(SyncWire.CONNECTION_LOST, assertFailsWith<SyncWire.SyncRefused> { fakeClient.pair("ABCD2345") }.reason)
        t.join(5_000)
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
        val lost = assertFailsWith<SyncWire.SyncRefused> { client().pair("ABCD2345") }
        assertEquals(SyncWire.CONNECTION_LOST, lost.reason)
        assertTrue(lost.cause is java.io.IOException)
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
        synchronized(host.rides) { host.rides["ride_old"] = byteArrayOf(1) }
        val tmp = Files.createTempDirectory("sync").toFile()
        val payload = ByteArray(200_000) { (it * 31).toByte() }
        val r = client().sync(pc, listOf("ride_old", "ride_new"), { name ->
            File(tmp, "$name.xbl").also { it.writeBytes(payload) }
        }, mapOf("map_type" to "AMX", "c" to "17"))
        assertEquals(listOf("ride_new"), r.uploaded)
        assertEquals(1, r.alreadyThere)
        assertContentEquals(payload, synchronized(host.rides) { host.rides["ride_new"] })
        assertEquals("{\"format\":\"rx-mapdefs-1\",\"c\":17}", String(r.defs!!))
        // 給不出定義包：照樣同步錄製，只回原因
        val r2 = client().sync(pc, listOf("ride_new"), { null }, mapOf("map_type" to "AMX2"))
        assertTrue(r2.uploaded.isEmpty() && r2.defs == null)
        assertEquals("No definitions for AMX2", r2.defsError)
        tmp.deleteRecursively()
    }

    /** 錄製檔打不開：只算這筆失敗，其他照傳，不會被當成斷線。 */
    @Test
    fun unreadableRideFailsOnlyThatRide() {
        val pc = pair()
        val tmp = Files.createTempDirectory("sync").toFile()
        try {
            val locked = File(tmp, "ride_x.xbl").also { it.writeBytes(byteArrayOf(1, 2)); it.setReadable(false, false) }
            // root 或不支援權限的檔案系統：測不了，回報成略過而不是通過
            org.junit.jupiter.api.Assumptions.assumeFalse(locked.canRead(), "cannot make a file unreadable here")
            val r = client().sync(pc, listOf("ride_x", "ride_y"), { name ->
                if (name == "ride_x") locked else File(tmp, "$name.xbl").also { it.writeBytes(byteArrayOf(3, 4)) }
            }, null)
            assertEquals(listOf("ride_y"), r.uploaded)
            assertEquals("pack failed", r.failed["ride_x"])
        } finally {
            tmp.deleteRecursively()
        }
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
