package app.tiviate.ui.player

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/** Requests focus once the target is attached (it may take a frame after composition). */
suspend fun focusSoon(requester: FocusRequester) {
    repeat(10) {
        if (runCatching { requester.requestFocus() }.isSuccess) return
        delay(16)
    }
}
