package rx.session

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.TCP_NODELAY
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import platform.posix.connect as posixConnect
import platform.posix.poll as posixPoll
import platform.posix.strerror
import rx.protocol.CanId
import rx.protocol.FrameParser
import rx.protocol.IOException
import rx.protocol.Rpc
import rx.protocol.monotonicNanos

/** iOS（Kotlin/Native）上的 TCP：POSIX socket。Java 的 Socket 在這裡沒有。 */
@OptIn(ExperimentalForeignApi::class)
internal object Posix {
    /** 連線（非阻塞 connect＋poll 逾時），成功回 fd；之後的 recv 以 [readTimeoutMs] 逾時返回。 */
    fun connect(host: String, port: Int, timeoutMs: Int, readTimeoutMs: Int = 500): Int = memScoped {
        val hints = alloc<addrinfo>().apply { ai_family = AF_UNSPEC; ai_socktype = SOCK_STREAM }
        val res = allocPointerTo<addrinfo>()
        val rc = getaddrinfo(host, port.toString(), hints.ptr, res.ptr)
        if (rc != 0 || res.value == null) throw IOException("Cannot resolve $host")
        try {
            val ai = res.value!!.pointed
            val fd = socket(ai.ai_family, ai.ai_socktype, ai.ai_protocol)
            if (fd < 0) throw IOException("socket(): ${err()}")
            try {
                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
                setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().convert())
                val flags = fcntl(fd, F_GETFL, 0)
                fcntl(fd, F_SETFL, flags or O_NONBLOCK)
                if (posixConnect(fd, ai.ai_addr, ai.ai_addrlen) != 0) {
                    if (errno != EINPROGRESS) throw IOException("connect: ${err()}")
                    val pfd = alloc<pollfd>().apply { this.fd = fd; events = POLLOUT.convert() }
                    val n = posixPoll(pfd.ptr, 1u, timeoutMs)
                    if (n <= 0) throw IOException("connect timed out")
                    val soErr = alloc<IntVar>()
                    val len = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
                    getsockopt(fd, SOL_SOCKET, SO_ERROR, soErr.ptr, len.ptr)
                    if (soErr.value != 0) throw IOException("connect: ${strerror(soErr.value)?.toKString()}")
                }
                fcntl(fd, F_SETFL, flags and O_NONBLOCK.inv())
                val tv = alloc<timeval>().apply { tv_sec = (readTimeoutMs / 1000).convert(); tv_usec = ((readTimeoutMs % 1000) * 1000).convert() }
                setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
                fd
            } catch (e: Throwable) { close(fd); throw e }
        } finally { freeaddrinfo(res.value) }
    }

    /** >0：位元組數；0：對端關閉；-1：讀逾時（還活著）。 */
    fun recv(fd: Int, buf: ByteArray): Int {
        val n = buf.usePinned { recv(fd, it.addressOf(0), buf.size.convert(), 0).toInt() }
        if (n < 0) {
            val e = errno
            if (e == EAGAIN || e == EWOULDBLOCK) return -1
            throw IOException("recv: ${err()}")
        }
        return n
    }

    fun sendAll(fd: Int, bytes: ByteArray) {
        var off = 0
        bytes.usePinned { p ->
            while (off < bytes.size) {
                val n = send(fd, p.addressOf(off), (bytes.size - off).convert(), 0).toInt()
                if (n <= 0) throw IOException("send: ${err()}")
                off += n
            }
        }
    }

    fun close(fd: Int) { platform.posix.close(fd) }

    private fun err() = strerror(errno)?.toKString() ?: "errno $errno"
}

/** TCP 6666（iOS）。行為同 JVM 的 TcpTransport：讀逾時只是讓協程能被取消，斷線由 Session 看門狗判斷。 */
class PosixTcpTransport private constructor(private val fd: Int) : Transport {
    private val buf = ByteArray(8192)
    private var closed = false

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) { Posix.sendAll(fd, bytes) }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        while (true) {
            if (closed) return@withContext null
            val n = Posix.recv(fd, buf)
            if (n > 0) return@withContext buf.copyOf(n)
            if (n == 0) return@withContext null
            ensureActive()
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    override fun close() { if (!closed) { closed = true; Posix.close(fd) } }

    companion object {
        suspend fun connect(host: String, port: Int = 6666, timeoutMs: Int = 4000): PosixTcpTransport =
            withContext(Dispatchers.IO) { PosixTcpTransport(Posix.connect(host, port, timeoutMs)) }
    }
}

/** iOS 探測器：TCP 探測同 JVM 版；UDP 公告要 Apple 的 multicast entitlement，不聽。 */
class PosixProber : EcuFinder.Prober {
    override suspend fun probe(host: String, port: Int, route: Any?, connectTimeoutMs: Int, listenMs: Long): Discovery.Probe? =
        withContext(Dispatchers.IO) {
            val t0 = monotonicNanos()
            val fd = runCatching { Posix.connect(host, port, connectTimeoutMs, readTimeoutMs = 200) }.getOrNull() ?: return@withContext null
            try {
                val connectMs = (monotonicNanos() - t0) / 1_000_000
                var frames = 0; var bc = false; var hb = false
                val p = FrameParser { f ->
                    frames++
                    if (f.canId == CanId.BROADCAST) bc = true
                    if (f.canId == CanId.HEARTBEAT_BRIDGE || f.canId == CanId.HEARTBEAT_APP) hb = true
                }
                val buf = ByteArray(4096)
                val end = monotonicNanos() + listenMs * 1_000_000
                val nudgeAt = monotonicNanos() + listenMs * 500_000
                var nudged = false
                while (monotonicNanos() < end && !(hb && frames >= 2) && !(nudged && frames >= 1)) {
                    ensureActive()
                    if (!nudged && frames == 0 && monotonicNanos() > nudgeAt) { Posix.sendAll(fd, Rpc(1).sessionOpen().encode()); nudged = true }
                    val n = runCatching { Posix.recv(fd, buf) }.getOrDefault(0)
                    if (n == 0) break
                    if (n > 0) p.feed(buf, 0, n)
                }
                if (frames > 0) Discovery.Probe(host, port, frames, bc, hb, connectMs) else null
            } finally { Posix.close(fd) }
        }
}

/** iOS 的區網：所有私有 IPv4 介面（WiFi en0、熱點 bridge…）的 /24。 */
@OptIn(ExperimentalForeignApi::class)
fun iosSubnets(): List<Discovery.Subnet> = memScoped {
    val head = allocPointerTo<platform.darwin.ifaddrs>()
    if (platform.darwin.getifaddrs(head.ptr) != 0) return emptyList()
    val out = ArrayList<Discovery.Subnet>()
    try {
        var p = head.value
        while (p != null) {
            val ifa = p.pointed
            val sa = ifa.ifa_addr
            if (sa != null && sa.pointed.sa_family.toInt() == AF_INET) {
                val sin = sa.reinterpretAsSockaddrIn()
                val a = sin.sin_addr.s_addr // 網路位元組序（小端主機上低位元組＝第一段）
                val b = intArrayOf((a and 0xFFu).toInt(), ((a shr 8) and 0xFFu).toInt(), ((a shr 16) and 0xFFu).toInt(), ((a shr 24) and 0xFFu).toInt())
                val private = b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
                if (private) out += Discovery.Subnet(ifa.ifa_name?.toKString() ?: "?", "${b[0]}.${b[1]}.${b[2]}")
            }
            p = ifa.ifa_next
        }
    } finally { platform.darwin.freeifaddrs(head.value) }
    out.distinctBy { it.prefix }
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<platform.posix.sockaddr>.reinterpretAsSockaddrIn(): platform.posix.sockaddr_in =
    this.reinterpret<platform.posix.sockaddr_in>().pointed
