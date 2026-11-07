package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rx.protocol.CanId
import rx.protocol.DashFn
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.Rpc

/** Public, synthetic protocol vectors; no vehicle data or external service required. */
class PlatformRoutingTest {
    @Test fun recognizesAllDeclaredNodesAndIgnoresAppHeartbeat() {
        val nodes = setOf(1, 2, 4, 5, 0x21, 0x22)
        assertEquals(nodes, EcuPlatform.entries.map { it.node }.toSet())
        for (p in EcuPlatform.entries) {
            assertEquals(p, EcuPlatform.ofHeartbeat(0x700 or p.node))
            assertEquals(0x600 or p.node, Rpc(p.node).rpcId)
            assertEquals(0x580 or p.node, CanId.rpcReply(p.node))
            assertEquals(0x180 or p.node, CanId.broadcast(p.node))
        }
        assertNull(EcuPlatform.ofHeartbeat(CanId.HEARTBEAT_APP))
        assertNull(EcuPlatform.ofHeartbeat(0x77F))
        assertNull(EcuPlatform.ofHeartbeat(0x181))
    }

    @Test fun eachNodeKeepsTheSameDashboardOnlyWriteBoundary() {
        for (p in EcuPlatform.entries) {
            val rpc = Rpc(p.node)
            val gate = WriteGate(p.node)
            for (map in 1..3) assertTrue(gate.allows(rpc.mapSwitch(map)), p.label)
            for (fn in DashFn.entries) {
                assertTrue(gate.allows(rpc.dashWrite(fn, 0)), "${p.label} $fn off")
                assertTrue(gate.allows(rpc.dashWrite(fn, 1)), "${p.label} $fn on")
                assertFalse(gate.allows(rpc.smallWrite(fn.index, 0, 1, 2)), "${p.label} $fn width")
            }
            assertFalse(gate.allows(rpc.smallWrite(Rpc.MAP_SWITCH, 0, 4, 1)))
            assertFalse(gate.allows(rpc.smallWrite(0x301D, 0, 1, 1)))
            assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x88.toByte(), 1, 1, 2, 3))))
            assertFalse(gate.allows(Frame(CanId.NMT, byteArrayOf(0x81.toByte(), 0))))
            assertTrue(gate.allows(rpc.monitorStart()))
            assertTrue(gate.allows(rpc.monitorStop()))
        }
    }
}
