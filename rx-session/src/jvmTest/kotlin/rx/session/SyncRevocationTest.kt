package rx.session

import rx.session.sync.SyncCrypto
import rx.session.sync.SyncHost
import rx.session.sync.SyncOps
import rx.session.sync.SyncServer
import rx.session.sync.SyncWire
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 伺服器每一則請求都要重問授權：配對在另一個行程被刪掉後，已建立的連線不能再收到回覆。 */
class SyncRevocationTest {
    private class Host : SyncHost {
        val peers = ConcurrentHashMap<String, SyncWire.Peer>()
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())

        override fun findPeer(id: String) = peers[id]
        override fun pairing() = null
        override fun onPaired(peer: SyncWire.Peer) { peers[peer.id] = peer }
        override fun live(peer: SyncWire.Peer, input: InputStream) = input.readBytes().isNotEmpty()
        override fun missingRides(peer: SyncWire.Peer, names: List<String>) = names
        override fun storeRide(peer: SyncWire.Peer, name: String, input: InputStream, length: Long) = ByteArray(0)
        override fun defs(peer: SyncWire.Peer, request: Map<String, String>) = ByteArray(0)
        override fun log(line: String) { logs += line }
    }

    private val serverIdentity = SyncWire.Identity("a1b2c3d4e5f6a1b2c3d4e5f6", "Desktop")
    private val phoneId = "0123456789abcdef01234567"
    private val token = SyncCrypto.randomBytes(32)
    private val host = Host().also { it.peers[phoneId] = SyncWire.Peer(phoneId, "Phone", token, 1) }

    // 定期重問先拉長，讓測試只看到「每則請求前」的檢查。
    private val server = SyncServer(serverIdentity, host, InetAddress.getLoopbackAddress(), port = 0, recheckMs = 60_000).also { it.start() }
    private val sockets = ArrayList<Socket>()

    @AfterTest
    fun cleanup() {
        server.stop()
        sockets.forEach { runCatching { it.close() } }
    }

    private class Link(val sock: Socket, val opened: SyncWire.Opened)

    private fun xb(token: ByteArray) = SyncWire.Peer(serverIdentity.id, serverIdentity.name, token, 1)

    private fun connect(me: SyncWire.Identity, xb: SyncWire.Peer): Link {
        val sock = Socket()
        sock.soTimeout = 5_000
        sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.boundPort), 5_000)
        sockets += sock
        val o = SyncWire.connectClient(sock.getInputStream(), sock.getOutputStream().buffered(1 shl 15), me, xb, SyncWire.MODE_SYNC)
        return Link(sock, o)
    }

    private fun askNames(l: Link): Pair<Byte, Long>? {
        SyncOps.send(l.opened.output, SyncOps.NAMES, ByteArray(0))
        return runCatching { SyncOps.readHeader(l.opened.input) }.getOrNull()
    }

    private fun waitSessions(id: String, count: Int) {
        val t0 = System.currentTimeMillis()
        while (server.activeSessions(id) != count && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
        assertEquals(count, server.activeSessions(id))
    }

    @Test
    fun aRevokedPeerGetsNoFurtherResponses() {
        val l = connect(SyncWire.Identity(phoneId, "Phone"), xb(token))
        waitSessions(phoneId, 1)
        assertEquals(SyncOps.MISSING, askNames(l)?.first)
        // 另一個行程刪掉配對：這個行程從共用紀錄查，馬上看到 null
        host.peers.remove(phoneId)
        assertNull(askNames(l), "revoked peer must not get another reply")
        waitSessions(phoneId, 0)
    }

    @Test
    fun anotherPairedPhoneKeepsWorking() {
        val otherId = "fedcba9876543210fedcba98"
        val otherToken = SyncCrypto.randomBytes(32)
        host.peers[otherId] = SyncWire.Peer(otherId, "Other", otherToken, 1)
        val mine = connect(SyncWire.Identity(phoneId, "Phone"), xb(token))
        val other = connect(SyncWire.Identity(otherId, "Other"), xb(otherToken))
        waitSessions(phoneId, 1)
        waitSessions(otherId, 1)
        assertEquals(SyncOps.MISSING, askNames(mine)?.first)
        assertEquals(SyncOps.MISSING, askNames(other)?.first)
        host.peers.remove(phoneId)
        assertNull(askNames(mine))
        assertEquals(SyncOps.MISSING, askNames(other)?.first, "revoking one phone must not touch another")
        waitSessions(otherId, 1)
    }

    @Test
    fun pairingAgainReplacesTheTokenForOpenSessions() {
        val l = connect(SyncWire.Identity(phoneId, "Phone"), xb(token))
        waitSessions(phoneId, 1)
        assertEquals(SyncOps.MISSING, askNames(l)?.first)
        // 重新配對換一組 token：舊連線用的 token 已經不算
        val fresh = SyncCrypto.randomBytes(32)
        host.peers[phoneId] = SyncWire.Peer(phoneId, "Phone", fresh, 2)
        assertNull(askNames(l))
        val again = connect(SyncWire.Identity(phoneId, "Phone"), xb(fresh))
        assertEquals(SyncOps.MISSING, askNames(again)?.first)
    }

    /** 沒有在發請求的連線（例如即時資料）也要靠定期重問收掉。 */
    @Test
    fun anIdleSessionIsClosedToo() {
        val idleHost = Host().also { it.peers[phoneId] = SyncWire.Peer(phoneId, "Phone", token, 1) }
        val idle = SyncServer(serverIdentity, idleHost, InetAddress.getLoopbackAddress(), port = 0, recheckMs = 50).also { it.start() }
        var sock: Socket? = null
        try {
            sock = Socket()
            sock.soTimeout = 5_000
            sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), idle.boundPort), 5_000)
            SyncWire.connectClient(sock.getInputStream(), sock.getOutputStream().buffered(1 shl 15), SyncWire.Identity(phoneId, "Phone"), xb(token), SyncWire.MODE_SYNC)
            val t0 = System.currentTimeMillis()
            while (idle.activeSessions(phoneId) != 1 && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
            assertEquals(1, idle.activeSessions(phoneId))
            idleHost.peers.remove(phoneId)
            val t1 = System.currentTimeMillis()
            while (idle.activeSessions(phoneId) != 0 && System.currentTimeMillis() - t1 < 5_000) Thread.sleep(10)
            assertEquals(0, idle.activeSessions(phoneId))
            assertEquals(-1, sock.getInputStream().read(), "the closed session must end with EOF")
        } finally {
            idle.stop()
            sock?.let { runCatching { it.close() } }
        }
    }

    /** 回覆算好時配對已經被刪：連自動回答的調校包也不再送出，直接收線。 */
    @Test
    fun anAnswerFinishedAfterRevocationIsNotSent() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowHost = Host().also { it.peers[phoneId] = SyncWire.Peer(phoneId, "Phone", token, 1) }
        // 定期重問先拉長，只有回覆前的那一次確認能攔下來。
        val slow = SyncServer(serverIdentity, object : SyncHost by slowHost {
            override fun tunePack(peer: SyncWire.Peer, request: Map<String, String>): ByteArray {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return "pack".toByteArray(Charsets.UTF_8)
            }
        }, InetAddress.getLoopbackAddress(), port = 0, recheckMs = 60_000).also { it.start() }
        var sock: Socket? = null
        try {
            sock = Socket()
            sock.soTimeout = 5_000
            sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), slow.boundPort), 5_000)
            val o = SyncWire.connectClient(sock.getInputStream(), sock.getOutputStream().buffered(1 shl 15), SyncWire.Identity(phoneId, "Phone"), xb(token), SyncWire.MODE_SYNC)
            SyncOps.send(o.output, SyncOps.TUNEPACK, SyncOps.kvText(mapOf("map_type" to "AMX")).toByteArray(Charsets.UTF_8))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the tune pack should be building")
            slowHost.peers.remove(phoneId)
            release.countDown()
            assertNull(runCatching { SyncOps.readHeader(o.input) }.getOrNull(), "a revoked peer must not get the tune pack")
            val t0 = System.currentTimeMillis()
            while (slow.activeSessions(phoneId) != 0 && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
            assertEquals(0, slow.activeSessions(phoneId))
        } finally {
            release.countDown()
            slow.stop()
            sock?.let { runCatching { it.close() } }
        }
    }
}
