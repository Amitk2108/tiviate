package app.tiviate.core.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class DecompressTest {

    private val text = "<tv><channel id=\"a\"/></tv>".repeat(50)

    private fun roundTrip(bytes: ByteArray): String =
        Decompress.wrap(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8)

    @Test
    fun plainPassesThrough() = assertEquals(text, roundTrip(text.toByteArray()))

    @Test
    fun gzipIsDetectedByMagicBytes() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        assertEquals(text, roundTrip(out.toByteArray()))
    }

    @Test
    fun xzIsDetectedByMagicBytes() {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { it.write(text.toByteArray()) }
        assertEquals(text, roundTrip(out.toByteArray()))
    }

    @Test
    fun tinyInputShorterThanMagicIsPlain() = assertEquals("<", roundTrip("<".toByteArray()))
}
