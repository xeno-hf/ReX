package rx.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** No real network: check socket ownership across setup failure, success and cancellation. */
class TcpTransportTest {
    private class FakeSocket(val failAt: String? = null, val onConnect: () -> Unit = {}) : Socket() {
        var closes = 0
        override fun setTcpNoDelay(on: Boolean) { if (failAt == "option") throw IOException("option") }
        override fun setReceiveBufferSize(size: Int) {}
        override fun setSendBufferSize(size: Int) {}
        override fun setSoTimeout(timeout: Int) {}
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            onConnect()
            if (failAt == "connect") throw IOException("connect")
        }
        override fun getInputStream() = if (failAt == "stream") throw IOException("stream") else ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun close() { closes++ }
    }
    private class Factory(val socket: Socket) : SocketFactory() {
        override fun createSocket() = socket
        override fun createSocket(h: String, p: Int): Socket = error("unused")
        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
        override fun createSocket(h: InetAddress, p: Int): Socket = error("unused")
        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
    }
    @Test fun closesSocketAtEverySetupFailure() = runBlocking {
        for (stage in listOf("option", "connect", "stream")) {
            val socket = FakeSocket(stage)
            assertFailsWith<IOException> { TcpTransport.connect("127.0.0.1", factory = Factory(socket)) }
            assertEquals(1, socket.closes, stage)
        }
    }
    @Test fun successfulConnectionTransfersOwnership() = runBlocking {
        val socket = FakeSocket()
        val transport = TcpTransport.connect("127.0.0.1", factory = Factory(socket))
        assertEquals(0, socket.closes)
        transport.close()
        assertEquals(1, socket.closes)
    }
    @Test fun cancellationBeforeTransportDeliveryClosesSocket() = runBlocking {
        lateinit var socket: FakeSocket
        val owner = Job()
        socket = FakeSocket(onConnect = { owner.cancel() })
        val child = launch(owner) {
            assertFailsWith<CancellationException> { TcpTransport.connect("127.0.0.1", factory = Factory(socket)) }
        }
        child.join()
        assertEquals(1, socket.closes)
    }
}
