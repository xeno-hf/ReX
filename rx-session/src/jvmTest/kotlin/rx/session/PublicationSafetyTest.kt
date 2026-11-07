package rx.session

import kotlin.test.*
import java.nio.file.Files
import rx.defs.ChannelRegistry
import rx.protocol.*

class PublicationSafetyTest {
    @Test fun defaultReplayHandshakeMustComplete() = kotlinx.coroutines.test.runTest {
        val t = LogReplayTransport(backgroundScope, { _, _, _ -> 0L })
        try { t.send(Frame(CanId.HANDSHAKE, byteArrayOf(0xD1.toByte())).encode()) }
        finally { t.close() }
    }

    @Test fun rawRecordingMustNotExposeLoginSecret() {
        val dir = Files.createTempDirectory("rex-audit-synthetic").toFile()
        try {
            val def = rx.defs.ChannelDef("RPM",26,2,1.0,0.0,20000.0,0.0,"Para","rpm","Stage_1",0,emptyMap())
            val layout = DecodedLayout(ChannelRegistry(null, listOf(def)), ChannelTable(listOf(ChannelTable.Entry(26,2))))
            val frame = Rpc().login(123456) // synthetic, never a real credential
            Recorder(kotlinx.io.files.Path(dir.path), layout, emptyMap()).apply {
                onFrame(1000000, RxRaw.DIR_TX, frame)
                close()
            }
            val bytes = dir.resolve("frames.rxraw").readBytes()
            val recorded = bytes.copyOfRange(RxRaw.MAGIC.size + 10, RxRaw.MAGIC.size + 10 + Frame.FRAME_LEN)
            assertFalse(recorded.contentEquals(frame.encode()), "Unredacted login frame survives recording and whole-folder ZIP export")
        } finally { dir.deleteRecursively() }
    }
    @Test fun prohibitedAddressesStayBlockedAcrossCommandsAndNodes() {
        for (node in EcuPlatform.entries.map { it.node }) {
            val gate = WriteGate(node)
            for (addr in listOf(0x1234, 0x3001, 0x3002, 0xFFFF)) for (command in 0..255) {
                val ccs = command ushr 5
                if (ccs !in listOf(0, 1, 6)) continue
                assertFalse(gate.allows(Frame(CanId.rpc(node), byteArrayOf(command.toByte(),addr.toByte(),(addr ushr 8).toByte(),0,1,0,0,0))))
            }
        }
    }
    @Test fun dashboardWriteWidthAndValuesAreLimited() {
        val gate=WriteGate(1); val rpc=Rpc()
        for (n in 1..4) for (value in 0..255) {
            assertEquals(n==1 && value in 1..3,gate.allows(rpc.smallWrite(Rpc.MAP_SWITCH,0,value.toLong(),n)))
            for (fn in listOf(DashFn.AT,DashFn.PIT,DashFn.LC,DashFn.TC))
                assertEquals(n==1 && value in 0..1,gate.allows(rpc.smallWrite(fn.index,0,value.toLong(),n)))
        }
    }
}
