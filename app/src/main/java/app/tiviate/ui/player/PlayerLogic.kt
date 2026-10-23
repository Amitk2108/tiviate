package app.tiviate.ui.player

import androidx.compose.ui.unit.IntSize
import app.tiviate.data.DisplayMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** Collects channel-number digits typed on the remote and commits them after a short pause. */
class NumberEntry(private val commitDelayMs: Long = 1_500, private val maxDigits: Int = 4) {
    var text: String = ""
        private set
    private var lastInputAt = 0L

    fun append(digit: Int, nowMs: Long) {
        if (text.length < maxDigits) text += digit
        lastInputAt = nowMs
    }

    /** Returns the number once the pause has elapsed (and clears the entry); null while still typing. */
    fun commitIfDue(nowMs: Long): Int? {
        if (text.isEmpty() || nowMs - lastInputAt < commitDelayMs) return null
        val value = text.toIntOrNull()?.takeIf { it > 0 }
        text = ""
        return value
    }

    fun clear() {
        text = ""
    }
}

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Chooses a display mode whose refresh rate is an integer multiple of the video frame rate
 * (25 fps → 50 Hz, 23.976 → 23.976 Hz) at the current resolution, which removes judder.
 */
object DisplayModeMatcher {
    fun pick(modes: List<DisplayModeInfo>, current: DisplayModeInfo, frameRate: Float): DisplayModeInfo? {
        if (frameRate <= 1f) return null
        val sameSize = modes.filter { it.width == current.width && it.height == current.height }
        return sameSize
            .filter { isMultiple(it.refreshRate, frameRate) }
            .minWithOrNull(compareBy({ (it.refreshRate / frameRate).roundToInt() }, { -it.refreshRate }))
    }

    private fun isMultiple(refresh: Float, fps: Float): Boolean {
        val ratio = refresh / fps
        val n = ratio.roundToInt()
        return n >= 1 && abs(ratio - n) < 0.01f
    }
}

/**
 * Size of the picture for a display mode and zoom, centred in the container (anything larger is
 * cropped by the screen). The video is drawn stretched into this frame.
 */
fun videoFrame(mode: DisplayMode, zoomPercent: Int, containerW: Int, containerH: Int, videoW: Int, videoH: Int): IntSize {
    val cw = containerW.toFloat()
    val ch = containerH.toFloat()
    fun boxOf(aspect: Float): Pair<Float, Float> = if (cw / ch > aspect) (ch * aspect) to ch else cw to (cw / aspect)
    val known = videoW > 0 && videoH > 0
    val videoAspect = if (known) videoW.toFloat() / videoH else cw / ch
    val (w, h) = when (mode) {
        DisplayMode.FIT -> boxOf(videoAspect)
        DisplayMode.FILL -> cw to ch
        DisplayMode.ZOOM -> if (cw / ch > videoAspect) cw to (cw / videoAspect) else (ch * videoAspect) to ch
        DisplayMode.RATIO_16_9 -> boxOf(16f / 9f)
        DisplayMode.RATIO_4_3 -> boxOf(4f / 3f)
    }
    val z = zoomPercent.coerceIn(50, 300) / 100f
    return IntSize(Math.round(w * z), Math.round(h * z))
}
