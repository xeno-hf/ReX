package rx.session.sync

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * ReX ↔ XB 同步（2026-11-16）。XB 是伺服器（預設 port 6680），ReX 連過去。
 *
 * 每條連線先送 `RXSYNC1\n`，接著是明文的握手訊息（`len u32 LE | UTF-8 key=value 每行一筆`），
 * 握手完之後兩個方向都換成 [SealedOutputStream]／[SealedInputStream]。
 *
 * **配對**（使用者在 XB 按「配對手機」，XB 顯示 16 個字的配對碼，5 分鐘內有效）：
 * ```
 * ReX → XB  op=pair id name epk cn       epk＝P-256 公鑰（X.509 DER, hex），cn＝16 bytes 亂數
 * XB  → ReX op=pair id name epk sn       沒有在配對中：err=no-pairing
 * 兩邊算 Z＝ECDH，T＝SHA-256(前兩則訊息的原文)，ak＝HMAC(配對碼, Z, T)
 * ReX → XB  mac＝HMAC(ak, "client")
 * XB  → ReX mac＝HMAC(ak, "server")      配對碼錯：err=bad-code（錯 5 次這組碼作廢）
 * 雙方保存 token＝HMAC(ak, "token")
 * ```
 * 只聽到封包的人拿不到 token（要解 ECDH）。假裝成電腦的人拿得到手機的 mac，可以離線試配對碼，
 * 所以配對碼有 16 個字（約 79 bits），試不完；假裝成手機的人在線上只有 5 次機會。
 *
 * **之後每次連線**：
 * ```
 * ReX → XB  op=hello id cn mode           mode＝live（即時資料）或 sync（檔案同步）
 * XB  → ReX op=hello id sn                沒配對過：err=unknown-device
 * ReX → XB  mac＝HMAC(token, "c", cn, sn, ReX id, XB id, mode)
 * XB  → ReX mac＝HMAC(token, "s", …同上)    不對：err=auth
 * 金鑰：ReX→XB＝HMAC(token, "c2s", cn, sn)；XB→ReX＝HMAC(token, "s2c", cn, sn)
 * ```
 * mode=live 之後的內容跟原本的鏡射（RXLINK1）一樣；mode=sync 見 [SyncOps]。
 */
object SyncWire {
    /** 手機與電腦的 id：12～32 bytes 亂數的十六進位（ReX 產生 12 bytes）。會拿來當資料夾名的一部分，格式不對就拒絕。 */
    val ID = Regex("^[0-9a-f]{24,64}$")
    val MAGIC = "RXSYNC1\n".toByteArray(Charsets.US_ASCII)
    const val DEFAULT_PORT = 6680
    const val MODE_LIVE = "live"
    const val MODE_SYNC = "sync"

    /** 自己這一方：[id] 隨機產生一次後固定，[name] 給對方顯示用。 */
    class Identity(val id: String, val name: String)

    /** 配對過的對方。[token] 是秘密，要存在只有自己讀得到的地方。 */
    class Peer(val id: String, val name: String, val token: ByteArray, val pairedAt: Long)

    /** 握手完的連線。 */
    class Opened(val peer: Peer, val mode: String, val input: InputStream, val output: OutputStream)

    class SyncRefused(val reason: String) : Exception(reason)

    fun newId(): String = SyncCrypto.hex(SyncCrypto.randomBytes(12))

    fun writeMsg(out: OutputStream, msg: Map<String, String>): ByteArray {
        val b = msg.entries.joinToString("") { (k, v) -> "$k=${v.replace('\n', ' ')}\n" }.toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf(b.size.toByte(), (b.size ushr 8).toByte(), (b.size ushr 16).toByte(), (b.size ushr 24).toByte()))
        out.write(b)
        out.flush()
        return b
    }

    /** 回傳（內容, 原文）。 */
    fun readMsg(inp: InputStream): Pair<Map<String, String>, ByteArray> {
        val d = DataInputStream(inp)
        val n = Integer.reverseBytes(d.readInt())
        require(n in 1..8192) { "Invalid sync handshake size" }
        val b = ByteArray(n).also(d::readFully)
        val kv = String(b, Charsets.UTF_8).lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        kv["err"]?.let { throw SyncRefused(it) }
        return kv to b
    }

    private fun need(m: Map<String, String>, k: String): String = m[k]?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: throw IllegalArgumentException("Sync handshake has no $k")

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // ---------------- ReX 這一端 ----------------

    /** 用配對碼跟 XB 配對。成功回傳 XB（含 token）；配對碼錯或 XB 沒在配對中丟 [SyncRefused]。 */
    fun pairClient(inp: InputStream, out: OutputStream, me: Identity, code: String): Peer {
        out.write(MAGIC)
        val kp = SyncCrypto.ecKeyPair()
        val cn = SyncCrypto.randomBytes(16)
        val m1 = writeMsg(out, linkedMapOf("op" to "pair", "id" to me.id, "name" to me.name, "epk" to SyncCrypto.hex(kp.publicDer), "cn" to SyncCrypto.hex(cn)))
        val (r, m2) = readMsg(inp)
        val z = SyncCrypto.ecdh(kp, SyncCrypto.unhex(need(r, "epk")))
        val ak = SyncCrypto.hmac(bytes(SyncCrypto.normalizeCode(code)), z, SyncCrypto.sha256(m1 + m2))
        writeMsg(out, mapOf("mac" to SyncCrypto.hex(SyncCrypto.hmac(ak, bytes("client")))))
        val (r2, _) = readMsg(inp)
        check(SyncCrypto.same(SyncCrypto.unhex(need(r2, "mac")), SyncCrypto.hmac(ak, bytes("server")))) { "The computer did not prove the pairing code" }
        return Peer(need(r, "id"), need(r, "name"), SyncCrypto.hmac(ak, bytes("token")), System.currentTimeMillis())
    }

    /** 跟配對過的 XB 開一條連線。 */
    fun connectClient(inp: InputStream, out: OutputStream, me: Identity, server: Peer, mode: String): Opened {
        out.write(MAGIC)
        val cn = SyncCrypto.randomBytes(16)
        writeMsg(out, linkedMapOf("op" to "hello", "id" to me.id, "cn" to SyncCrypto.hex(cn), "mode" to mode))
        val (r, _) = readMsg(inp)
        val sid = need(r, "id")
        if (sid != server.id) throw SyncRefused("different-computer")
        val sn = SyncCrypto.unhex(need(r, "sn"))
        val bind = arrayOf(cn, sn, bytes(me.id), bytes(sid), bytes(mode))
        writeMsg(out, mapOf("mac" to SyncCrypto.hex(SyncCrypto.hmac(server.token, bytes("c"), *bind))))
        val (r2, _) = readMsg(inp)
        if (!SyncCrypto.same(SyncCrypto.unhex(need(r2, "mac")), SyncCrypto.hmac(server.token, bytes("s"), *bind))) throw SyncRefused("auth")
        return Opened(
            server, mode,
            SealedInputStream(inp, SyncCrypto.hmac(server.token, bytes("s2c"), cn, sn)),
            SealedOutputStream(out, SyncCrypto.hmac(server.token, bytes("c2s"), cn, sn)),
        )
    }

    // ---------------- XB 這一端 ----------------

    /** XB 端：目前開著的配對（一組碼只配一台）。 */
    class PairingWindow(val code: String, val expiresAt: Long) {
        var failures = 0
        var done = false
        val open: Boolean get() = !done && failures < MAX_FAILURES && System.currentTimeMillis() < expiresAt
        /** 取消（換新的一組或使用者關掉）：進行中的配對也不能完成。 */
        fun cancel() = synchronized(this) { done = true }
        companion object { const val MAX_FAILURES = 5; const val LIFETIME_MS = 5 * 60_000L }
    }

    sealed interface Accepted {
        class Paired(val peer: Peer) : Accepted
        class Session(val opened: Opened) : Accepted
    }

    /**
     * 伺服器收一條連線（[MAGIC] 已經讀掉）。配對成功時先交給 [onPaired] 存起來，存好才回確認給手機，
     * 再回 [Accepted.Paired]；手機收到確認就能馬上連過來，不會被當成沒配對。
     * [onPaired] 正常回來＝已經存好、[findPeer] 查得到；丟例外就不回確認，手機配對失敗，這組碼也已用掉。
     * 存好之後確認才送丟（斷線）時只有電腦這邊有配對，手機重新配對會換掉它。
     * 一般連線回 [Accepted.Session]。被拒時先回錯誤給對方，再丟 [SyncRefused]。
     */
    fun accept(inp: InputStream, out: OutputStream, me: Identity, findPeer: (String) -> Peer?, pairing: () -> PairingWindow?, onPaired: (Peer) -> Unit): Accepted {
        val (m, raw1) = readMsg(inp)
        fun refuse(reason: String): Nothing { writeMsg(out, mapOf("err" to reason)); throw SyncRefused(reason) }
        when (m["op"]) {
            "pair" -> {
                val w = pairing()?.takeIf { it.open } ?: refuse("no-pairing")
                val cid = need(m, "id").takeIf { ID.matches(it) } ?: refuse("bad-id")
                val cname = need(m, "name").take(64)
                val kp = SyncCrypto.ecKeyPair()
                val sn = SyncCrypto.randomBytes(16)
                val raw2 = writeMsg(out, linkedMapOf("op" to "pair", "id" to me.id, "name" to me.name, "epk" to SyncCrypto.hex(kp.publicDer), "sn" to SyncCrypto.hex(sn)))
                val z = SyncCrypto.ecdh(kp, SyncCrypto.unhex(need(m, "epk")))
                val ak = SyncCrypto.hmac(bytes(w.code), z, SyncCrypto.sha256(raw1 + raw2))
                val (r, _) = readMsg(inp)
                synchronized(w) {
                    if (!w.open) refuse("no-pairing")
                    if (!SyncCrypto.same(SyncCrypto.unhex(need(r, "mac")), SyncCrypto.hmac(ak, bytes("client")))) { w.failures++; refuse("bad-code") }
                    w.done = true
                }
                val peer = Peer(cid, cname, SyncCrypto.hmac(ak, bytes("token")), System.currentTimeMillis())
                onPaired(peer)
                writeMsg(out, mapOf("mac" to SyncCrypto.hex(SyncCrypto.hmac(ak, bytes("server")))))
                return Accepted.Paired(peer)
            }
            "hello" -> {
                val cid = need(m, "id")
                val mode = need(m, "mode").takeIf { it == MODE_LIVE || it == MODE_SYNC } ?: refuse("bad-mode")
                val cn = SyncCrypto.unhex(need(m, "cn"))
                val peer = findPeer(cid) ?: refuse("unknown-device")
                val sn = SyncCrypto.randomBytes(16)
                writeMsg(out, linkedMapOf("op" to "hello", "id" to me.id, "sn" to SyncCrypto.hex(sn)))
                val bind = arrayOf(cn, sn, bytes(cid), bytes(me.id), bytes(mode))
                val (r, _) = readMsg(inp)
                if (!SyncCrypto.same(SyncCrypto.unhex(need(r, "mac")), SyncCrypto.hmac(peer.token, bytes("c"), *bind))) refuse("auth")
                writeMsg(out, mapOf("mac" to SyncCrypto.hex(SyncCrypto.hmac(peer.token, bytes("s"), *bind))))
                return Accepted.Session(Opened(
                    peer, mode,
                    SealedInputStream(inp, SyncCrypto.hmac(peer.token, bytes("c2s"), cn, sn)),
                    SealedOutputStream(out, SyncCrypto.hmac(peer.token, bytes("s2c"), cn, sn)),
                ))
            }
            else -> refuse("bad-op")
        }
    }

    fun readMagic(inp: InputStream): ByteArray {
        val b = ByteArray(MAGIC.size)
        var off = 0
        while (off < b.size) { val r = inp.read(b, off, b.size - off); if (r < 0) throw EOFException(); off += r }
        return b
    }
}

/**
 * mode=sync 的訊息：`type u8 | len u32 LE | payload`，一問一答。
 * ```
 * ReX → XB  'N' 手機上的錄製名稱，一行一個            XB → ReX 'n' XB 還沒有的名稱
 * ReX → XB  'U' name u16 LE | name | .xbl 內容       XB → ReX 'u' 收下的 SHA-256（hex），或 'e' 原因
 * ReX → XB  'D' key=value（platform、map_type、c、model、lang）
 *                                                  XB → ReX 'd' 油圖定義包（JSON），或 'e' 原因
 * ReX → XB  'T' key=value（map_type、table_id、c）    XB → ReX 't' 調校包（ZIP，xb-tune TunePack），或 'e' 原因
 *           （舊版 XB 不認得 'T' 會直接收線；手機把它當作沒有調校包）
 * ReX → XB  'Z' 結束
 * ```
 */
object SyncOps {
    const val NAMES = 'N'.code.toByte()
    const val MISSING = 'n'.code.toByte()
    const val UPLOAD = 'U'.code.toByte()
    const val UPLOADED = 'u'.code.toByte()
    const val DEFS = 'D'.code.toByte()
    const val DEFS_REPLY = 'd'.code.toByte()
    const val TUNEPACK = 'T'.code.toByte()
    const val TUNEPACK_REPLY = 't'.code.toByte()
    const val ERROR = 'e'.code.toByte()
    const val END = 'Z'.code.toByte()

    const val MAX_UPLOAD = 512L shl 20
    const val MAX_SMALL = 16 shl 20

    fun header(type: Byte, len: Long): ByteArray {
        require(len in 0..0xFFFFFFFFL)
        return byteArrayOf(type, len.toByte(), (len ushr 8).toByte(), (len ushr 16).toByte(), (len ushr 24).toByte())
    }

    fun send(out: OutputStream, type: Byte, payload: ByteArray) {
        out.write(header(type, payload.size.toLong())); out.write(payload); out.flush()
    }

    /** 讀一則的開頭，回傳（type, len）。對方收線回 null。 */
    fun readHeader(inp: InputStream): Pair<Byte, Long>? {
        val t = inp.read()
        if (t < 0) return null
        val d = DataInputStream(inp)
        val len = Integer.reverseBytes(d.readInt()).toLong() and 0xFFFFFFFFL
        return t.toByte() to len
    }

    fun readBody(inp: InputStream, len: Long, max: Int = MAX_SMALL): ByteArray {
        require(len <= max) { "Sync message too large" }
        return ByteArray(len.toInt()).also(DataInputStream(inp)::readFully)
    }

    /** 讀一則完整的（小）訊息；要是錯誤訊息就丟 [SyncWire.SyncRefused]。 */
    fun expect(inp: InputStream, type: Byte): ByteArray {
        val (t, len) = readHeader(inp) ?: throw EOFException("Computer closed the sync")
        val body = readBody(inp, len)
        if (t == ERROR) throw SyncWire.SyncRefused(String(body, Charsets.UTF_8))
        require(t == type) { "Unexpected sync reply ${t.toInt().toChar()}" }
        return body
    }

    fun kv(text: String): Map<String, String> = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    fun kvText(m: Map<String, String>): String = m.entries.joinToString("") { (k, v) -> "$k=${v.replace('\n', ' ')}\n" }
}
