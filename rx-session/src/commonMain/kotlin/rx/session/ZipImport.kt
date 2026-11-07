package rx.session

/** Bounded ZIP reader. Central directory sizes support streamed local entries without trusting them. */
object ZipImport {
    const val MAX_BYTES = 128 * 1024 * 1024
    fun first(zip: ByteArray, filter: (String) -> Boolean, inflate: (ByteArray, Int) -> ByteArray): Pair<String, ByteArray>? {
        require(zip.size <= MAX_BYTES) { "Import exceeds 128 MiB" }
        fun u16(p: Int): Int { require(p >= 0 && p <= zip.size - 2) { "Truncated ZIP" }; return (zip[p].toInt() and 255) or ((zip[p+1].toInt() and 255) shl 8) }
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p+2).toLong() shl 16)
        var end = zip.size - 22
        while (end >= maxOf(0, zip.size - 65557) && u32(end) != 0x06054b50L) end--
        require(end >= maxOf(0, zip.size - 65557) && end >= 0) { "ZIP directory missing" }
        require(end + 22 + u16(end+20) == zip.size && u16(end+4)==0 && u16(end+6)==0 && u16(end+8)==u16(end+10)) { "Unsupported ZIP layout" }
        val count=u16(end+10); val start=u32(end+16); val length=u32(end+12)
        require(count <= 4096 && start + length == end.toLong()) { "Invalid ZIP directory" }
        var p=start.toInt(); var total=0L
        var selected: Triple<String,ByteArray,Long>? = null
        repeat(count) {
            require(p >= 0 && p.toLong()+46 <= end && u32(p)==0x02014b50L) { "Invalid ZIP entry" }
            val flags=u16(p+8); val method=u16(p+10); val crc=u32(p+16); val compressed=u32(p+20); val size=u32(p+24)
            val nameLen=u16(p+28);val extra=u16(p+30);val comment=u16(p+32);val local=u32(p+42)
            require(flags and 1 == 0 && u16(p+34)==0 && compressed<=MAX_BYTES && size<=MAX_BYTES) { "Unsupported or oversized ZIP entry" }
            total+=size; require(total<=MAX_BYTES) { "ZIP expands beyond 128 MiB" }
            require(p.toLong()+46+nameLen+extra+comment <= end) { "Truncated ZIP entry" }
            val name=zip.decodeToString(p+46,p+46+nameLen)
            require(local+30 <= start && u32(local.toInt())==0x04034b50L) { "Invalid ZIP local entry" }
            val l=local.toInt(); val at=local+30+u16(l+26)+u16(l+28)
            require(at+compressed<=start && u16(l+8)==method && u16(l+6)==flags) { "Invalid ZIP bounds" }
            require(zip.decodeToString(l+30,l+30+u16(l+26))==name) { "ZIP names disagree" }
            if(selected==null && !name.endsWith('/') && filter(name)) {
                val raw=zip.copyOfRange(at.toInt(),(at+compressed).toInt())
                val data=when(method) { 0 -> raw; 8 -> inflate(raw,size.toInt()); else -> error("Unsupported ZIP compression") }
                require(data.size.toLong()==size && crc32(data)==crc) { "ZIP integrity check failed" }
                selected=Triple(name,data,crc)
            }
            p+=46+nameLen+extra+comment
        }
        require(p.toLong()==start+length) { "Invalid ZIP directory size" }
        return selected?.let { it.first to it.second }
    }
    private val table=IntArray(256) { var c=it; repeat(8) { c=if(c and 1!=0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 };c }
    fun crc32(bytes: ByteArray): Long { var c=-1;for(b in bytes)c=table[(c xor b.toInt()) and 255] xor (c ushr 8);return c.inv().toLong() and 0xffffffffL }
}
