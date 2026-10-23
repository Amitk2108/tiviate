package app.tiviate

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

fun SemanticsNodeInteractionsProvider.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun SemanticsNodeInteractionsProvider.textExists(text: String): Boolean =
    onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/** TV devices have no touchscreen: activate a control through its click action instead of a tap. */
fun SemanticsNodeInteraction.activate(): SemanticsNodeInteraction = performSemanticsAction(SemanticsActions.OnClick)

/** Remote-control key presses, injected like a real TV remote. */
object Remote {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun press(keyCode: Int, times: Int = 1) = repeat(times) { device.pressKeyCode(keyCode); Thread.sleep(120) }
    fun ok() = press(KeyEvent.KEYCODE_DPAD_CENTER)
    fun up(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_UP, times)
    fun down(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_DOWN, times)
    fun left(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_LEFT, times)
    fun right(times: Int = 1) = press(KeyEvent.KEYCODE_DPAD_RIGHT, times)
    fun back() = press(KeyEvent.KEYCODE_BACK)
    fun menu() = press(KeyEvent.KEYCODE_MENU)
    fun longOk() {
        // A held OK: repeated ACTION_DOWN events over ~700 ms, then ACTION_UP.
        device.executeShellCommand("input keyevent --longpress ${KeyEvent.KEYCODE_DPAD_CENTER}")
        Thread.sleep(200)
    }
}
