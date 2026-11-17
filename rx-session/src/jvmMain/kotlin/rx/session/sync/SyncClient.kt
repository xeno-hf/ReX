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
        s.tcpNoDelay = true
        s.soTimeout = 20_000
        s.connect(InetSocketAddress(host, port), 5_000)
        return s
    }

    /** 用 XB 畫面上的配對碼配對。 */
    fun pair(code: String): SyncWire.Peer = socket().use { s ->
        SyncWire.pairClient(s.getInputStream(), s.getOutputStream().buffered(), me, code)
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
        val o = SyncWire.connectClient(s.getInputStream(), s.getOutputStream().buffered(1 shl 15), me, server, SyncWire.MODE_SYNC)
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
                val nb = name.toByteArray(Charsets.UTF_8)
                out.write(SyncOps.header(SyncOps.UPLOAD, 2L + nb.size + f.length()))
                out.write(byteArrayOf(nb.size.toByte(), (nb.size ushr 8).toByte())); out.write(nb)
                // 邊送邊算雜湊，不把整個檔讀進記憶體；送出的位元組數要跟宣告的一樣
                val declared = f.length()
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                var sent = 0L
                f.inputStream().use { input ->
                    val buf = ByteArray(1 shl 15)
                    while (sent < declared) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), declared - sent).toInt())
                        if (n < 0) break
                        sha.update(buf, 0, n); out.write(buf, 0, n); sent += n
                    }
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
            // 舊版 XB 不認得 'T' 會收線：當作沒有調校包，前面傳的照算
            try {
                SyncOps.send(out, SyncOps.TUNEPACK, SyncOps.kvText(tunePackRequest).toByteArray(Charsets.UTF_8))
                tp = SyncOps.expect(inp, SyncOps.TUNEPACK_REPLY)
            } catch (e: SyncWire.SyncRefused) { tpError = e.reason } catch (e: java.io.IOException) { tpError = "computer does not provide tune packs (${e.message})" }
            if (tpError != null && tp == null && tpError.startsWith("computer does not")) {
                return@use Result(uploaded, failed, rides.size - missing.size, defs, defsError, null, tpError)
            }
        }
        runCatching { SyncOps.send(out, SyncOps.END, ByteArray(0)) }
        Result(uploaded, failed, rides.size - missing.size, defs, defsError, tp, tpError)
    }
}
