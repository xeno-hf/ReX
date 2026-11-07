package rx.session

import kotlinx.coroutines.test.runTest
import rx.protocol.CanId
import rx.protocol.Frame
import rx.protocol.FrameParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Offline identity strings must not break the three-part handshake. */
class PlaybackHandshakeTest {
    @Test fun shortIdentitySupportsRepeatedHandshake() = runTest {
        for (identity in listOf("", "REX-REPLAY", "PLAYBACK-000001", "PLAYBACK-000000000001")) {
            val transport = LogReplayTransport(backgroundScope, { _, _, _ -> 0L }, serial = identity.encodeToByteArray())
            try {
                repeat(3) {
                    transport.send(Frame(CanId.HANDSHAKE, byteArrayOf(0xD1.toByte())).encode())
                    val frames = ArrayList<Frame>()
                    val parser = FrameParser { frames += it }
                    while (frames.count { it.canId == 0x6A2 } < 3) parser.feed(transport.receive()!!)
                    val parts = frames.filter { it.canId == 0x6A2 }
                    assertEquals(listOf(0xD2, 0xD3, 0xD4), parts.map { it.u8(0) })
                    assertEquals(identity, parts.flatMap { it.data.drop(1) }.toByteArray().decodeToString())
                }
            } finally { transport.close() }
        }
    }
}
