package rx.session.sync

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** 伺服器（XB）要提供的東西。除了 [findPeer]／[pairing]／[authorized] 之外都在連線自己的執行緒上呼叫。 */
interface SyncHost {
    fun findPeer(id: String): SyncWire.Peer?
    fun pairing(): SyncWire.PairingWindow?
    fun onPaired(peer: SyncWire.Peer)

    /**
     * 這條連線的配對還在嗎：每次請求前、以及開著的連線定期都會再問一次。
     * 另一個行程可能剛把配對刪掉；重新配對會換 token，舊連線就不算。
     */
    fun authorized(peer: SyncWire.Peer): Boolean = findPeer(peer.id)?.token?.contentEquals(peer.token) == true

    /** 一條即時資料（mode=live）：[input] 是解密後的 RXLINK1 串流，讀到結束為止。回傳是否正常結束。 */
    fun live(peer: SyncWire.Peer, input: InputStream): Boolean

    /** [names] 裡這台電腦還沒有的。 */
    fun missingRides(peer: SyncWire.Peer, names: List<String>): List<String>

    /** 收一筆 .xbl：從 [input] 讀 [length] bytes。回傳存下來的 SHA-256；收不下丟例外（訊息會回給手機）。 */
    fun storeRide(peer: SyncWire.Peer, name: String, input: InputStream, length: Long): ByteArray

    /** 油圖定義包；給不出來丟例外（訊息會回給手機）。 */
    fun defs(peer: SyncWire.Peer, request: Map<String, String>): ByteArray

    /** 調校包（即時調校用，ZIP）；給不出來丟例外（訊息會回給手機）。 */
    fun tunePack(peer: SyncWire.Peer, request: Map<String, String>): ByteArray = throw UnsupportedOperationException("This computer does not provide tune packs")

    fun log(line: String) {}
}

/**
 * XB 的同步伺服器：一次最多 [maxClients] 條連線。即時資料每 3 秒回 1 byte 心跳（加密），
 * 跟原本的鏡射接收端一樣，手機靠它判斷線路死活。
 */
class SyncServer(
    private val me: SyncWire.Identity,
    private val host: SyncHost,
    private val bind: InetAddress?,
    private val port: Int = SyncWire.DEFAULT_PORT,
    private val maxClients: Int = 4,
    /** 配對紀錄可能被另一個行程改掉：開著的連線每隔這麼久重問一次。 */
    private val recheckMs: Long = 1_000,
) {
    @Volatile private var server: ServerSocket? = null
    private val slots = Semaphore(maxClients)
    private val open = java.util.Collections.synchronizedSet(HashSet<Socket>())

    /** 開著的一條連線：記住握手時的 peer，token 被換掉就不算同一台。 */
    private class Tracked(val peer: SyncWire.Peer, val sock: Socket)

    /** 每台手機目前開著的連線；手機被移除時靠它一起關。 */
    private val sessions = HashMap<String, MutableSet<Tracked>>()

    val running: Boolean get() = server?.isClosed == false
    val boundPort: Int get() = server?.localPort ?: -1

    /** 這台手機目前幾條連線（測試與紀錄用）。 */
    fun activeSessions(peerId: String): Int = synchronized(sessions) { sessions[peerId]?.size ?: 0 }

    /** 配對紀錄已經刪了：這台手機的連線全部關掉，進行中的請求和即時資料也一起停。 */
    fun revoke(peerId: String) {
        val closing = synchronized(sessions) { sessions.remove(peerId)?.map { it.sock }.orEmpty() }
        for (s in closing) runCatching { s.close() }
        if (closing.isNotEmpty()) host.log("Sync: closed ${closing.size} session(s) of a removed phone")
    }

    private fun track(peer: SyncWire.Peer, sock: Socket): Boolean = synchronized(sessions) {
        // 剛好在移除前一刻完成握手：不再接受，連線直接收掉。
        if (!host.authorized(peer)) {
            runCatching { sock.close() }
            return@synchronized false
        }
        sessions.getOrPut(peer.id) { HashSet() } += Tracked(peer, sock)
        true
    }

    private fun untrack(peer: SyncWire.Peer, sock: Socket) = synchronized(sessions) {
        val set = sessions[peer.id] ?: return@synchronized
        set.removeAll { it.sock == sock }
        if (set.isEmpty()) sessions.remove(peer.id)
    }

    fun start() {
        check(server == null) { "Sync server already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bind, port), 8)
        server = ss
        host.log("Sync server listening on ${bind?.hostAddress ?: "*"}:${ss.localPort}")
        thread(isDaemon = true, name = "xb-sync-accept") {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: SocketException) { break }
                if (!slots.tryAcquire()) { runCatching { sock.close() }; continue }
                open += sock
                thread(isDaemon = true, name = "xb-sync-${sock.remoteSocketAddress}") {
                    try { serve(sock) } finally { open -= sock; runCatching { sock.close() }; slots.release() }
                }
            }
        }
        // 另一個行程可能刪掉配對：開著的連線沒在發請求也要收掉（即時資料就是這種）。
        thread(isDaemon = true, name = "xb-sync-recheck") {
            while (!ss.isClosed) {
                try { Thread.sleep(recheckMs) } catch (_: InterruptedException) { break }
                val openNow = synchronized(sessions) { sessions.values.flatten().toList() }
                for (t in openNow) if (!runCatching { host.authorized(t.peer) }.getOrDefault(false)) runCatching { t.sock.close() }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
    }

    private fun serve(sock: Socket) {
        val who = sock.remoteSocketAddress.toString()
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 20_000
            val rawIn = sock.getInputStream()
            val rawOut = sock.getOutputStream().buffered(1 shl 15)
            val magic = SyncWire.readMagic(rawIn)
            if (!magic.contentEquals(SyncWire.MAGIC)) { host.log("Sync: $who is not a ReX sync connection, closed"); return }
            when (val a = SyncWire.accept(rawIn, rawOut, me, host::findPeer, host::pairing)) {
                is SyncWire.Accepted.Paired -> { host.onPaired(a.peer); host.log("Sync: paired ${a.peer.name} ($who)") }
                is SyncWire.Accepted.Session -> {
                    val o = a.opened
                    if (!track(o.peer, sock)) { host.log("Sync: ${o.peer.name} is no longer paired ($who)"); return }
                    try {
                        host.log("Sync: ${o.peer.name} connected for ${o.mode} ($who)")
                        if (o.mode == SyncWire.MODE_LIVE) live(sock, o) else syncLoop(sock, o)
                    } finally {
                        untrack(o.peer, sock)
                    }
                }
            }
        } catch (e: SyncWire.SyncRefused) {
            host.log("Sync: refused $who: ${e.reason}")
        } catch (e: Exception) {
            host.log("Sync: $who dropped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun live(sock: Socket, o: SyncWire.Opened) {
        sock.soTimeout = 30_000
        val ping = thread(isDaemon = true) {
            runCatching { while (true) { synchronized(o.output) { o.output.write(0); o.output.flush() }; Thread.sleep(3_000) } }
        }
        try {
            val ended = host.live(o.peer, o.input)
            host.log("Sync: ${o.peer.name} live ${if (ended) "ended" else "dropped"}")
        } finally { ping.interrupt() }
    }

    private fun syncLoop(sock: Socket, o: SyncWire.Opened) {
        sock.soTimeout = 120_000
        val inp = o.input; val out = o.output
        while (true) {
            // 每次請求前都重問授權；另一個行程剛刪掉配對就不再做任何事。
            if (!host.authorized(o.peer)) return
            val (type, len) = SyncOps.readHeader(inp) ?: return
            // 等請求的期間也可能被刪：收到後再確認一次才處理。
            if (!host.authorized(o.peer)) return
            when (type) {
                SyncOps.NAMES -> {
                    // 名單最多 4 MiB、20000 筆，每個名字 128 字以內，重複的只算一次
                    val body = SyncOps.readBody(inp, len, max = 4 shl 20)
                    val names = LinkedHashSet<String>()
                    for (line in String(body, Charsets.UTF_8).lineSequence()) {
                        if (line.isEmpty()) continue
                        require(line.length <= 128) { "Name too long" }
                        names += line
                        require(names.size <= 20_000) { "Too many names" }
                    }
                    val answer = host.missingRides(o.peer, names.toList())
                    if (!reply(out, o.peer, SyncOps.MISSING, answer.joinToString("\n").toByteArray(Charsets.UTF_8))) return
                }
                SyncOps.UPLOAD -> {
                    require(len in 3..SyncOps.MAX_UPLOAD) { "Ride too large" }
                    val d = DataInputStream(inp)
                    val nl = (d.readUnsignedByte()) or (d.readUnsignedByte() shl 8)
                    require(nl in 1..255 && 2L + nl < len)
                    val name = String(ByteArray(nl).also(d::readFully), Charsets.UTF_8)
                    val body = len - 2 - nl
                    val limited = Limited(inp, body)
                    try {
                        val sha = host.storeRide(o.peer, name, limited, body)
                        limited.drain()
                        if (!reply(out, o.peer, SyncOps.UPLOADED, SyncCrypto.hex(sha).toByteArray(Charsets.US_ASCII))) return
                        host.log("Sync: received ${o.peer.name}/$name ($body B)")
                    } catch (e: Exception) {
                        limited.drain()
                        if (!reply(out, o.peer, SyncOps.ERROR, (e.message ?: e.javaClass.simpleName).toByteArray(Charsets.UTF_8))) return
                        host.log("Sync: could not store ${o.peer.name}/$name: ${e.message}")
                    }
                }
                SyncOps.DEFS -> {
                    val req = SyncOps.kv(String(SyncOps.readBody(inp, len), Charsets.UTF_8))
                    try {
                        if (!reply(out, o.peer, SyncOps.DEFS_REPLY, host.defs(o.peer, req))) return
                    } catch (e: Exception) {
                        if (!reply(out, o.peer, SyncOps.ERROR, (e.message ?: e.javaClass.simpleName).toByteArray(Charsets.UTF_8))) return
                        host.log("Sync: no map definitions for ${o.peer.name}: ${e.message}")
                    }
                }
                SyncOps.TUNEPACK -> {
                    val req = SyncOps.kv(String(SyncOps.readBody(inp, len), Charsets.UTF_8))
                    try {
                        if (!reply(out, o.peer, SyncOps.TUNEPACK_REPLY, host.tunePack(o.peer, req))) return
                    } catch (e: Exception) {
                        if (!reply(out, o.peer, SyncOps.ERROR, (e.message ?: e.javaClass.simpleName).toByteArray(Charsets.UTF_8))) return
                        host.log("Sync: no tune pack for ${o.peer.name}: ${e.message}")
                    }
                }
                SyncOps.END -> return
                else -> error("Unknown sync request")
            }
        }
    }

    /**
     * 回覆前最後一次確認：建包、驗證可能花時間，另一個行程可能已經把配對刪掉。
     * 傳不出去就收線，讓對方知道這條連線不再提供服務。
     */
    private fun reply(out: OutputStream, peer: SyncWire.Peer, type: Byte, payload: ByteArray): Boolean {
        if (!runCatching { host.authorized(peer) }.getOrDefault(false)) return false
        SyncOps.send(out, type, payload)
        return true
    }

    /** 只讓下一層讀 [left] bytes，讀不完的由 [drain] 丟掉，下一則訊息才對得上。 */
    private class Limited(private val inp: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inp.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        fun drain() { val buf = ByteArray(8192); while (left > 0) { if (read(buf, 0, buf.size) < 0) throw java.io.EOFException() } }
    }
}
