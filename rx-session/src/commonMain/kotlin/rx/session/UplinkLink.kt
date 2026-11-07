package rx.session

/** 「同步到電腦」的連線（JVM 實作＝[Uplink]）；平台沒有實作時就不提供這個功能。 */
interface UplinkLink : SessionListener {
    val connected: Boolean
    val sent: Long
    val dropped: Long
    val reconnects: Int
    val lastError: String?
    suspend fun close(graceMs: Long = 2_000)
}

/** 鏡射連線開頭的 hello 內容（[Uplink.helloText] 同格式）：值裡的換行會被換成空白。 */
fun uplinkHello(id: String, table: rx.protocol.ChannelTable, info: Map<String, String>): String = buildString {
    append("id=").append(id).append('\n')
    append("table=").append(table.entries.joinToString(",") { "${it.absid}:${it.cellSize}" }).append('\n')
    info.forEach { (k, v) -> append("info.").append(k).append('=').append(v.replace('\n', ' ')).append('\n') }
}
