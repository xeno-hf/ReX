package rx.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import rx.protocol.ChannelTable
import kotlin.test.Test
import kotlin.test.assertEquals

/** 功能頁連著要求兩組通道（同一個監看周期之前）：第二次接在第一次後面，兩組都要在。 */
class ChannelRequestTest {
    @Test fun consecutiveRequestsAccumulate() {
        val s = Session({ error("not connected") }, ChannelTable(listOf(ChannelTable.Entry(26, 2))), null, CoroutineScope(Dispatchers.Default))
        assertEquals(listOf(55), s.requestMoreChannels(listOf(ChannelTable.Entry(55, 2))).map { it.absid })
        assertEquals(listOf(34), s.requestMoreChannels(listOf(ChannelTable.Entry(34, 1), ChannelTable.Entry(55, 2))).map { it.absid })
        assertEquals(listOf(26, 55, 34), s.requestedTable.entries.map { it.absid })
    }

    @Test fun requestsStopAtTheTableLimit() {
        val full = ChannelTable((1..ChannelTable.MAX_ENTRIES).map { ChannelTable.Entry(100 + it, 1) })
        val s = Session({ error("not connected") }, full, null, CoroutineScope(Dispatchers.Default))
        assertEquals(emptyList(), s.requestMoreChannels(listOf(ChannelTable.Entry(26, 2))))
        assertEquals(ChannelTable.MAX_ENTRIES, s.requestedTable.entries.size)
    }
}
