package rx.session

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

fun InputStream.readImport(limit: Int = ZipImport.MAX_BYTES): ByteArray {
    val out=ByteArrayOutputStream();val chunk=ByteArray(8192)
    while(true) { val n=read(chunk);if(n<0)break;require(out.size().toLong()+n<=limit) { "Import exceeds size limit" };out.write(chunk,0,n) }
    return out.toByteArray()
}
fun unzipImport(bytes: ByteArray, filter: (String)->Boolean) = ZipImport.first(bytes,filter) { raw,size ->
    val inflater=Inflater(true)
    try {
        inflater.setInput(raw)
        val out=ByteArray(size);var at=0
        while(!inflater.finished() && at<size) { val n=inflater.inflate(out,at,size-at); require(n>0) { "Truncated ZIP stream" };at+=n }
        if(size==0) inflater.inflate(ByteArray(1))
        require(inflater.finished() && at==size && inflater.bytesWritten==size.toLong() && inflater.remaining==0) { "ZIP size mismatch" }
        out
    } finally { inflater.end() }
}
