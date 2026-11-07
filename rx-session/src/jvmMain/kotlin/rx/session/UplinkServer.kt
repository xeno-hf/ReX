package rx.session

import rx.defs.ChannelRegistry
import rx.protocol.Frame
import rx.protocol.FrameParser
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.zip.InflaterInputStream
import kotlin.concurrent.thread

/**
 * 電腦 端：收 [Uplink] 鏡射，原樣餵 [Recorder]（跟手機本機錄製同格式），並維護即時快照 `live.json`。
 * 同一個連線 id 斷線重連會接回同一份錄製；手機送 'X' 或閒置 [IDLE_CLOSE_MS] 才收檔。
 *
 * `./gradlew :rx-session:uplinkServer --args="<ControlFile.xml> <輸出夾> [綁定位址] [port]"`
 *
 * live.json：最近一輪的全部欄位物理值＋亮著的故障碼＋最近事件，約每 0.5 秒覆寫一次（先寫暫存再改名）。
 */
private const val IDLE_CLOSE_MS = 10 * 60_000L

private class Mirror(val id: String, val layout: DecodedLayout, val rec: Recorder) {
    @Volatile var lastSeen = System.currentTimeMillis()
    @Volatile var online = false
    val events = ArrayDeque<String>()
    var lastRaw: LongArray? = null
    var lastRoundT = 0L
    var firstRoundT = -1L
    var lastSnap = 0L
}

fun main(args: Array<String>) {
    val registry = ChannelRegistry.parse(File(args[0]).readText())
    val root = File(args[1]).apply { mkdirs() }
    val bindAddr = args.getOrNull(2)?.let(InetAddress::getByName) ?: InetAddress.getLoopbackAddress()
    require(bindAddr.isLoopbackAddress) { "Use an authenticated SSH tunnel to the loopback receiver" }
    val port = args.getOrNull(3)?.toInt() ?: Uplink.DEFAULT_PORT
    val mirrors = HashMap<String, Mirror>()
    val server = ServerSocket(port, 4, bindAddr)
    val slots = java.util.concurrent.Semaphore(4)
    println("Sync receiver ${bindAddr?.hostAddress ?: "*"}:$port -> $root (${registry.channels.size} channel defs)")

    Runtime.getRuntime().addShutdownHook(Thread { synchronized(mirrors) { mirrors.values.forEach { it.rec.close() } } })
    thread(isDaemon = true, name = "idle-close") {
        while (true) {
            Thread.sleep(30_000)
            synchronized(mirrors) {
                val now = System.currentTimeMillis()
                mirrors.values.filter { !it.online && now - it.lastSeen > IDLE_CLOSE_MS }.forEach {
                    it.rec.close(); mirrors.remove(it.id); println("[${it.id}] idle, closed: ${it.rec.dir.name}")
                }
            }
        }
    }

    while (true) {
        val sock = server.accept()
        if (!slots.tryAcquire()) { sock.close(); continue }
        thread(name = "link-${sock.remoteSocketAddress}") {
            try { serve(sock, registry, root, mirrors) } finally { slots.release() }
        }
    }
}

private fun serve(sock: Socket, registry: ChannelRegistry, root: File, mirrors: HashMap<String, Mirror>) {
    val peer = sock.remoteSocketAddress.toString()
    println("Connected: $peer")
    // 心跳：每 3 秒回 1 byte，手機端靠它判斷線路死活
    val ping = thread(isDaemon = true) {
        runCatching { val o = sock.getOutputStream(); while (true) { o.write(0); o.flush(); Thread.sleep(3_000) } }
    }
    var m: Mirror? = null
    var ended = false
    try {
        sock.soTimeout = 30_000
        val din = DataInputStream(InflaterInputStream(sock.getInputStream()).buffered(1 shl 15))
        val magic = ByteArray(Uplink.MAGIC.size).also(din::readFully)
        require(magic.contentEquals(Uplink.MAGIC)) { "not RXLINK1" }
        while (true) {
            val type = try { din.readByte() } catch (_: EOFException) { break }
            val len = Integer.reverseBytes(din.readInt())
            require(len in 0..65536) { "Invalid sync message size" }
            val p = ByteArray(len).also(din::readFully)
            when (type) {
                Uplink.T_FRAME -> require(len == 9 + Frame.FRAME_LEN)
                Uplink.T_ROUND -> {
                    require(len >= 10)
                    val count = (p[8].toInt() and 255) or ((p[9].toInt() and 255) shl 8)
                    require(count <= 4096 && len == 10 + count * 8)
                }
                Uplink.T_EVENT -> require(len >= 8)
                Uplink.T_HELLO -> require(len in 1..32768)
                Uplink.T_END -> require(len == 0)
                else -> error("Unknown sync message")
            }
            when (type) {
                Uplink.T_HELLO -> m = attach(String(p, Charsets.UTF_8), registry, root, mirrors).also {
                    it.online = true
                    it.rec.onEvent(System.nanoTime(), "Sync connected: $peer")
                }
                Uplink.T_FRAME -> {
                    val mm = m ?: continue
                    val (frames, st) = FrameParser.parseAll(p.copyOfRange(9, 9 + Frame.FRAME_LEN))
                    if (frames.size == 1 && st.checksumErrors == 0L) mm.rec.onFrame(Uplink.getLong(p, 0), p[8].toInt(), frames[0])
                }
                Uplink.T_ROUND -> {
                    val mm = m ?: continue
                    val t = Uplink.getLong(p, 0)
                    val n = (p[8].toInt() and 0xFF) or ((p[9].toInt() and 0xFF) shl 8)
                    val raw = LongArray(n) { Uplink.getLong(p, 10 + 8 * it) }
                    mm.rec.onRound(t, raw)
                    onRound(mm, t, raw, root)
                }
                Uplink.T_EVENT -> {
                    val mm = m ?: continue
                    val text = String(p, 8, p.size - 8, Charsets.UTF_8)
                    mm.rec.onEvent(Uplink.getLong(p, 0), text)
                    synchronized(mm.events) { mm.events.addLast(text); while (mm.events.size > 30) mm.events.removeFirst() }
                    println("[${mm.id}] $text")
                }
                Uplink.T_END -> { ended = true; break }
            }
            m?.lastSeen = System.currentTimeMillis()
        }
    } catch (e: Exception) {
        println("[$peer] dropped: ${e.message ?: e.javaClass.simpleName}")
    } finally {
        ping.interrupt()
        runCatching { sock.close() }
        m?.let { mm ->
            mm.online = false
            mm.rec.onEvent(System.nanoTime(), if (ended) "Sync ended" else "Sync dropped")
            if (ended) synchronized(mirrors) { mm.rec.close(); mirrors.remove(mm.id); println("[${mm.id}] closed: ${mm.rec.dir}") }
            else println("[${mm.id}] dropped, waiting for reconnect (closes after ${IDLE_CLOSE_MS / 60_000} min)")
            writeSnapshot(mm, root)
        }
    }
}

private fun attach(helloText: String, registry: ChannelRegistry, root: File, mirrors: HashMap<String, Mirror>): Mirror {
    val (id, table, info) = Uplink.parseHello(helloText)
    synchronized(mirrors) {
        require(id.length <= 128) { "Invalid sync id" }
        mirrors[id]?.let { println("[$id] reconnected, resuming ${it.rec.dir.name}"); return it }
        require(mirrors.size < 16 && table.entries.size <= 4096) { "Too many sync sessions/channels" }
        val layout = DecodedLayout(registry, table)
        val label = if (info["rx.target"].orEmpty().startsWith("Demo")) "LINK_DEMO" else "LINK"
        val rec = Recorder(Recorder.newSessionDir(kotlinx.io.files.Path(root.path), label), layout, info + ("link.id" to id))
        println("[$id] new recording ${rec.dir.name}: ${table.entries.size} ch, ${layout.columns.size} columns; ${info["rx.device"] ?: ""}")
        return Mirror(id, layout, rec).also { mirrors[id] = it }
    }
}

private fun onRound(m: Mirror, t: Long, raw: LongArray, root: File) {
    m.lastRaw = raw; m.lastRoundT = t
    if (m.firstRoundT < 0) m.firstRoundT = t
    val now = System.currentTimeMillis()
    if (now - m.lastSnap >= 500) { m.lastSnap = now; writeSnapshot(m, root) }
}

private fun writeSnapshot(m: Mirror, root: File) {
    val raw = m.lastRaw
    val sb = StringBuilder("{\n")
    sb.append("  \"id\": ${Recorder.jsonStr(m.id)},\n")
    sb.append("  \"online\": ${m.online},\n")
    sb.append("  \"wall_ms\": ${System.currentTimeMillis()},\n")
    sb.append("  \"dir\": ${Recorder.jsonStr(java.io.File(m.rec.dir.toString()).absolutePath)},\n")
    sb.append("  \"rounds\": ${m.rec.rounds},\n  \"frames\": ${m.rec.frames},\n")
    sb.append("  \"ride_s\": ${"%.1f".format(Locale.US, if (m.firstRoundT < 0) 0.0 else (m.lastRoundT - m.firstRoundT) / 1e9)},\n")
    if (raw != null) {
        sb.append("  \"malf\": [${m.layout.activeMalfunctions(raw).joinToString(",") { Recorder.jsonStr(it.name) }}],\n")
        sb.append("  \"values\": {")
        sb.append(m.layout.columns.joinToString(",") { c -> "${Recorder.jsonStr(c.def.name)}:${Recorder.fmt(m.layout.value(c, raw), c.def.decimals)}" })
        sb.append("},\n")
    }
    sb.append("  \"events\": [${synchronized(m.events) { m.events.joinToString(",") { Recorder.jsonStr(it) } }}]\n}\n")
    val tmp = File(root, ".live.json.tmp")
    tmp.writeText(sb.toString())
    tmp.renameTo(File(root, "live.json"))
}
