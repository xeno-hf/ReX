package rx.session

import kotlin.test.*
import java.nio.file.Files
import java.net.Socket
import java.net.ServerSocket
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.thread

class SyncGuardTest {
    @Test fun refusesPublicBindAndOversizedMessages() {
        val root=Files.createTempDirectory("rex-sync-test").toFile()
        val xml=root.resolve("defs.xml").apply { writeText("<Variables></Variables>") }
        fun start(host: String,port: Int) {
            val cls=Class.forName("rx.session.UplinkServerKt")
            cls.getMethod("main",Array<String>::class.java).invoke(null,arrayOf(xml.path,root.path,host,port.toString()) as Any)
        }
        try {
            val ex=assertFailsWith<java.lang.reflect.InvocationTargetException> { start("0.0.0.0",0) }
            assertTrue(ex.cause is IllegalArgumentException)
            val port=ServerSocket(0).use { it.localPort }
            thread(isDaemon=true) { start("127.0.0.1",port) }
            var sock: Socket?=null
            for(i in 0..100) { try { sock=Socket("127.0.0.1",port);break } catch (_: Exception) { Thread.sleep(10) } }
            assertNotNull(sock).use { s ->
                s.soTimeout=2000
                val out=DeflaterOutputStream(s.getOutputStream(),true)
                out.write(Uplink.MAGIC);out.write('H'.code);out.write(byteArrayOf(-1,-1,-1,127));out.flush()
                // A heartbeat may race the rejection. The peer must then close, not allocate 2 GiB.
                while(true) { if(s.getInputStream().read()<0)break }
            }
        } finally { root.deleteRecursively() }
    }
}
