package rx.session.sync

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** ReX 這一端：配對、即時資料的握手、檔案同步。全部是阻塞呼叫，請在 IO 執行緒用。 */
class SyncClient(private val host: String, private val port: Int, private val me: SyncWire.Identity) {

    private fun socket(): Socket {
        // 不用 apply：Socket 自己也有 port 屬性，會蓋掉這裡的 port
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.soTimeout = 20_000
            s.connect(InetSocketAddress(host, port), 5_000)
        } catch (e: Exception) {
            // IOException，或位址的 port 超出範圍（InetSocketAddress 丟 IllegalArgumentException）
            if (e !is java.io.IOException && e !is IllegalArgumentException) throw e
            runCatching { s.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw SyncWire.SyncRefused(SyncWire.UNREACHABLE, e)
        }
        return s
    }

    /** 握手時的例外換成畫面看得懂的原因，原本的例外留在 cause。 */
    private inline fun <T> handshake(block: () -> T): T = try {
        block()
    } catch (e: SyncWire.SyncRefused) {
        throw e
    } catch (e: java.io.IOException) {
        // 包括握手到一半對方就收線（EOF）：XB 自己出錯（例如存不進配對）也是這樣，不一定是位址錯
        throw SyncWire.SyncRefused(SyncWire.CONNECTION_LOST, e)
    } catch (e: Exception) {
        // 公鑰解析不了、欄位不對、電腦證明不了配對碼
        throw SyncWire.SyncRefused(SyncWire.BAD_REPLY, e)
    }

    /** 用 XB 畫面上的配對碼配對。 */
    fun pair(code: String): SyncWire.Peer = socket().use { s ->
        handshake { SyncWire.pairClient(s.getInputStream(), s.getOutputStream().buffered(), me, code) }
    }

    /** 給 [rx.session.Uplink] 用：連上之後驗證、加密。 */
    fun liveHandshake(server: SyncWire.Peer): (Socket) -> Pair<InputStream, OutputStream> = { s ->
        val o = SyncWire.connectClient(s.getInputStream(), s.getOutputStream().buffered(1 shl 15), me, server, SyncWire.MODE_LIVE)
        o.input to o.output
    }

    /** 一次同步的結果。 */
    class Result(
        val uploaded: List<String>, val failed: Map<String, String>, val alreadyThere: Int, val defs: ByteArray?, val defsError: String?,
        /** 調校包（即時調校）；沒要、要不到是 null，原因在 [tunePackError]。 */
        val tunePack: ByteArray? = null, val tunePackError: String? = null,
        /** [tunePackError] 是本機判斷的（[SyncWire.CONNECTION_LOST]、[SyncWire.CLOSED_ON_TUNE_PACK]），不是電腦送來的原文。 */
        val tunePackErrorLocal: Boolean = false,
    )

    /**
     * 一次同步：把 XB 還沒有的錄製傳上去，順便要油圖定義包。
     * [rides]：手機上的錄製名稱；[pack]：把一筆錄製打包成 .xbl（回 null＝這筆打包失敗、略過）。
     * [defsRequest]：要定義包時的條件（platform、map_type、c、model、lang）；null＝不要。
     */
    fun sync(
        server: SyncWire.Peer,
        rides: List<String>,
        pack: (String) -> File?,
        defsRequest: Map<String, String>?,
        tunePackRequest: Map<String, String>? = null,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): Result = socket().use { s ->
        s.soTimeout = 120_000
        val o = handshake { SyncWire.connectClient(s.getInputStream(), s.getOutputStream().buffered(1 shl 15), me, server, SyncWire.MODE_SYNC) }
        // 只有連線的 I/O 錯誤算斷線；呼叫端的 onProgress 丟出來的照原樣往外丟
        val progress: (Int, Int, String) -> Unit = { done, total, name ->
            try { onProgress(done, total, name) } catch (e: java.io.IOException) { throw CallbackFailed(e) }
        }
        try { transfer(o, rides, pack, defsRequest, tunePackRequest, progress) }
        catch (e: CallbackFailed) { throw e.io }
        catch (e: java.io.IOException) { throw SyncWire.SyncRefused(SyncWire.CONNECTION_LOST, e) }
    }

    /** 把 onProgress 的 I/O 例外帶過 [transfer]，不讓它被當成斷線。 */
    private class CallbackFailed(val io: java.io.IOException) : RuntimeException(io)

    /** 打開要上傳的錄製檔；測試用來重播讀到一半失敗。 */
    internal var openRide: (File) -> java.io.FileInputStream = { java.io.FileInputStream(it) }

    private fun transfer(
        o: SyncWire.Opened,
        rides: List<String>,
        pack: (String) -> File?,
        defsRequest: Map<String, String>?,
        tunePackRequest: Map<String, String>?,
        onProgress: (done: Int, total: Int, name: String) -> Unit,
    ): Result {
        val inp = o.input; val out = o.output
        SyncOps.send(out, SyncOps.NAMES, rides.joinToString("\n").toByteArray(Charsets.UTF_8))
        // 只信自己送出去的名字，重複的只傳一次
        val asked = rides.toHashSet()
        val missing = String(SyncOps.expect(inp, SyncOps.MISSING), Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() && it in asked }.distinct().toList()
        val uploaded = ArrayList<String>(); val failed = LinkedHashMap<String, String>()
        missing.forEachIndexed { i, name ->
            onProgress(i, missing.size, name)
            val f = runCatching { pack(name) }.getOrNull()
            if (f == null || !f.isFile) { failed[name] = "pack failed"; return@forEachIndexed }
            try {
                // 先打開檔案、從打開的檔案拿長度，再送標頭：打不開或拿不到長度只算這筆失敗，連線照常
                val file = try { openRide(f) } catch (e: java.io.IOException) { failed[name] = "pack failed"; return@forEachIndexed }
                val declared = try { file.channel.size() } catch (e: java.io.IOException) {
                    runCatching { file.close() }; failed[name] = "pack failed"; return@forEachIndexed
                }
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                var sent = 0L
                try {
                    val nb = name.toByteArray(Charsets.UTF_8)
                    out.write(SyncOps.header(SyncOps.UPLOAD, 2L + nb.size + declared))
                    out.write(byteArrayOf(nb.size.toByte(), (nb.size ushr 8).toByte())); out.write(nb)
                    // 邊送邊算雜湊，不把整個檔讀進記憶體；送出的位元組數要跟宣告的一樣
                    val buf = ByteArray(1 shl 15)
                    while (sent < declared) {
                        // 讀檔失敗不是斷線：包成 UncheckedIOException，不會被當成 connection-lost
                        val n = try { file.read(buf, 0, minOf(buf.size.toLong(), declared - sent).toInt()) }
                            catch (e: java.io.IOException) { throw java.io.UncheckedIOException("Couldn't read the ride $name", e) }
                        if (n < 0) break
                        sha.update(buf, 0, n); out.write(buf, 0, n); sent += n
                    }
                } finally {
                    // 關唯讀檔失敗不影響已經讀到的內容，也不是斷線：忽略，不蓋掉正在往外丟的連線錯誤
                    runCatching { file.close() }
                }
                check(sent == declared) { "ride package changed while uploading" }
                out.flush()
                val got = String(SyncOps.expect(inp, SyncOps.UPLOADED), Charsets.UTF_8)
                val mine = SyncCrypto.hex(sha.digest())
                if (got == mine) uploaded += name else failed[name] = "checksum mismatch"
            } catch (e: SyncWire.SyncRefused) {
                failed[name] = e.reason
            } finally {
                f.delete()
            }
        }
        onProgress(missing.size, missing.size, "")
        var defs: ByteArray? = null; var defsError: String? = null
        if (defsRequest != null) {
            SyncOps.send(out, SyncOps.DEFS, SyncOps.kvText(defsRequest).toByteArray(Charsets.UTF_8))
            try { defs = SyncOps.expect(inp, SyncOps.DEFS_REPLY) } catch (e: SyncWire.SyncRefused) { defsError = e.reason }
        }
        var tp: ByteArray? = null; var tpError: String? = null
        if (tunePackRequest != null) {
            val reply = requestTunePack(inp, out, tunePackRequest)
            tp = reply.data; tpError = reply.error
            // 本機判斷的原因＝連線已經不能用：不送結束訊息，前面傳的照算
            if (reply.local) return Result(uploaded, failed, rides.size - missing.size, defs, defsError, null, tpError, tunePackErrorLocal = true)
        }
        runCatching { SyncOps.send(out, SyncOps.END, ByteArray(0)) }
        return Result(uploaded, failed, rides.size - missing.size, defs, defsError, tp, tpError)
    }

    /** 要調校包的結果：[local]＝[error] 是本機判斷的、連線已經不能用；否則是電腦送來的原因。 */
    internal class TunePackReply(val data: ByteArray?, val error: String?, val local: Boolean = false)

    companion object {
        /**
         * 要調校包。電腦一個位元組都沒回就收線 → [SyncWire.CLOSED_ON_TUNE_PACK]（舊版 XB 不認得 'T'，或配對剛被刪掉，分不出來）；
         * 逾時、回到一半斷線 → [SyncWire.CONNECTION_LOST]；XB 拒絕 → 它送來的原因（連線還能用）。
         */
        internal fun requestTunePack(inp: InputStream, out: OutputStream, request: Map<String, String>): TunePackReply {
            val pb = java.io.PushbackInputStream(inp, 1)
            // 加密串流一次讀完一整筆才給第一個位元組：要看底層有沒有讀到東西
            val raw = { (inp as? SealedInputStream)?.rawBytesRead ?: 0L }
            val first = try {
                SyncOps.send(out, SyncOps.TUNEPACK, SyncOps.kvText(request).toByteArray(Charsets.UTF_8))
                val before = raw()
                // 一個位元組都還沒收到就被 reset，也算收線；收到一部分才 reset、或逾時（不是 SocketException），照斷線算
                try { pb.read() } catch (e: java.net.SocketException) { if (raw() > before) throw e else -1 }
            } catch (e: java.io.IOException) {
                return TunePackReply(null, SyncWire.CONNECTION_LOST, local = true)
            }
            if (first < 0) return TunePackReply(null, SyncWire.CLOSED_ON_TUNE_PACK, local = true)
            pb.unread(first)
            return try {
                TunePackReply(SyncOps.expect(pb, SyncOps.TUNEPACK_REPLY), null)
            } catch (e: SyncWire.SyncRefused) {
                TunePackReply(null, e.reason)
            } catch (e: java.io.IOException) {
                TunePackReply(null, SyncWire.CONNECTION_LOST, local = true)
            }
        }
    }
}
