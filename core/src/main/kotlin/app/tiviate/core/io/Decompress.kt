package app.tiviate.core.io

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

/**
 * Detects gzip/xz by magic bytes rather than file name or Content-Type, because EPG hosts
 * often serve `.gz` files as `application/octet-stream` without a Content-Encoding header.
 */
object Decompress {

    private val GZIP = byteArrayOf(0x1f, 0x8b.toByte())
    private val XZ = byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0x00)

    fun wrap(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(XZ.size)
        val head = ByteArray(XZ.size)
        var read = 0
        while (read < head.size) {
            val n = buffered.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        return when {
            read >= GZIP.size && head.startsWith(GZIP) -> GZIPInputStream(buffered, 64 * 1024)
            read >= XZ.size && head.startsWith(XZ) -> XZInputStream(buffered)
            else -> buffered
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = prefix.indices.all { this[it] == prefix[it] }
}
