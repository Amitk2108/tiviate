package app.tiviate.ui.player

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
