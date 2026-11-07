package rx.session

import kotlin.test.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class ImportGuardTest {
    private fun zip()=ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { z ->
        z.putNextEntry(ZipEntry("ride.loga"));z.write("synthetic ride".toByteArray());z.closeEntry()
    } }.toByteArray()
    @Test fun acceptsStreamedZipAndChecksContent() {
        assertEquals("synthetic ride",unzipImport(zip()) { it.endsWith(".loga") }!!.second.decodeToString())
    }
    @Test fun rejectsTruncationAndBadCrc() {
        val b=zip();assertFails { unzipImport(b.copyOf(b.size-5)) { true } }
        val p=(0..b.size-4).first { b[it]==0x50.toByte() && b[it+1]==0x4b.toByte() && b[it+2]==1.toByte() && b[it+3]==2.toByte() }
        b[p+16]=(b[p+16].toInt() xor 1).toByte()
        assertFails { unzipImport(b) { true } }
    }
    @Test fun rejectsUnsignedOversizeBeforeAllocating() {
        val b=zip();val p=(0..b.size-4).first { b[it]==0x50.toByte() && b[it+1]==0x4b.toByte() && b[it+2]==1.toByte() && b[it+3]==2.toByte() }
        for(i in 24..27)b[p+i]=0xff.toByte()
        assertFails { unzipImport(b) { true } }
    }
    @Test fun boundsStreamWithoutRequiringARealLargeFile() {
        assertContentEquals(byteArrayOf(1,2),byteArrayOf(1,2).inputStream().readImport(2))
        assertFails { byteArrayOf(1,2,3).inputStream().readImport(2) }
    }
}
