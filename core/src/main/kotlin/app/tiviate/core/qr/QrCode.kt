package app.tiviate.core.qr

import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** QR codes (ZXing's encoder): error correction level M, for short texts such as "http://192.168.1.9:8765/?code=2124". */
object QrCode {
    /** Longer texts make codes too dense to scan from across a room. */
    private const val MAX_BYTES = 250

    fun maxBytes(): Int = MAX_BYTES

    /** The grid for [text] (true = dark module), or null when it is too long. */
    fun encode(text: String): Array<BooleanArray>? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val matrix = try {
            Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        } catch (e: WriterException) {
            return null
        }
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
    }
}
