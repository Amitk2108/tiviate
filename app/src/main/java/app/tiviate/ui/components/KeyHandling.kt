package app.tiviate.ui.components

import android.view.KeyEvent

enum class KeyAction { None, Short, Long }

/**
 * Turns a key's down/repeat/up events into a single short or long press. Remotes send repeated
 * ACTION_DOWN events while a button is held; the long press fires once the hold passes [thresholdMs].
 */
class LongPressDetector(private val thresholdMs: Long = 500) {
    private var downAt = -1L
    private var longFired = false

    fun onKey(event: KeyEvent): KeyAction = when (event.action) {
        KeyEvent.ACTION_DOWN -> {
            if (event.repeatCount == 0) {
                downAt = event.eventTime
                longFired = false
                KeyAction.None
            } else if (!longFired && downAt >= 0 && event.eventTime - downAt >= thresholdMs) {
                longFired = true
                KeyAction.Long
            } else {
                KeyAction.None
            }
        }
        KeyEvent.ACTION_UP -> {
            val wasDown = downAt >= 0
            downAt = -1L
            if (wasDown && !longFired) KeyAction.Short else KeyAction.None
        }
        else -> KeyAction.None
    }
}
