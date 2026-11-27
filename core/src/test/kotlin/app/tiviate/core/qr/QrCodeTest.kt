package app.tiviate.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeTest {

    /** Reads the grid back with ZXing (what phone camera apps use), as a picture with a quiet zone. */
    private fun decode(grid: Array<BooleanArray>): String {
        val scale = 4
        val quiet = 4
        val side = (grid.size + 2 * quiet) * scale
        val pixels = ByteArray(side * side) { 0xFF.toByte() }
        for (y in grid.indices) for (x in grid.indices) if (grid[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0
            }
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * side, (y + 1) * side)
            override fun getMatrix(): ByteArray = pixels
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun phoneLinksAreReadable() {
        for (text in listOf("http://192.168.1.9:8765/?code=2124", "http://10.0.0.254:8775/?code=0042", "https://github.com/Amitk2108/tiviate")) {
            assertEquals(text, decode(QrCode.encode(text)!!))
        }
    }

    @Test
    fun longerTextsUseBiggerCodes() {
        val text = "http://192.168.100.200:8765/?code=1234&x=" + "a".repeat(150) // needs version 7+ (version info)
        val grid = QrCode.encode(text)!!
        assert(grid.size >= 45) { "size ${grid.size}" }
        assertEquals(text, decode(grid))
    }

    @Test
    fun tooLongIsRefused() = assertNull(QrCode.encode("x".repeat(QrCode.maxBytes() + 1)))
}
