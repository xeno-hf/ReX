package rx.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * TCP 6666。Android 端傳入綁定 WiFi Network 的 [SocketFactory]
 * （XTune 也這樣做，避免沒網路的 ECU AP 被行動網路搶走路由）。
 * Socket 參數照 XTune `aRacerWiFiCANService.c`：TcpNoDelay、Recv 8192、Send 5120。
 */
class TcpTransport private constructor(private val socket: Socket) : Transport {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val buf = ByteArray(8192)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes); output.flush()
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        var result: ByteArray? = null
        while (true) {
            try {
                val n = input.read(buf)
                if (n >= 0) result = buf.copyOf(n)
                break
            } catch (_: SocketTimeoutException) {
                // 讀逾時只是讓協程有機會被取消；斷線由看門狗判斷
                if (socket.isClosed) break
                ensureActive()
            }
        }
        result
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        const val PORT = 6666

        suspend fun connect(host: String, port: Int = PORT, factory: SocketFactory = SocketFactory.getDefault(), timeoutMs: Int = 4000): TcpTransport {
            var socket: Socket? = null
            try {
                return withContext(Dispatchers.IO) {
                    val s = factory.createSocket()
                    socket = s
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 8192
                    s.sendBufferSize = 5120
                    s.soTimeout = 500
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    ensureActive()
                    TcpTransport(s)
                }
            } catch (e: Throwable) {
                // 失敗重連也必須釋放；包含 withContext 回傳時的取消（呼叫者拿不到 transport）。
                runCatching { socket?.close() }
                throw e
            }
        }
    }
}
