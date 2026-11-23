package app.tiviate

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
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
    /** A key held for [holdMs], with the repeats a real remote sends ("input --longpress" is too short for Tiviate). */
    fun hold(keyCode: Int, holdMs: Long = 900) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downAt = android.os.SystemClock.uptimeMillis()
        fun key(action: Int, at: Long, repeat: Int) =
            KeyEvent(downAt, at, action, keyCode, repeat, 0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
        ui.injectInputEvent(key(KeyEvent.ACTION_DOWN, downAt, 0), true)
        var repeat = 1
        while (android.os.SystemClock.uptimeMillis() - downAt < holdMs) {
            Thread.sleep(50)
            ui.injectInputEvent(key(KeyEvent.ACTION_DOWN, android.os.SystemClock.uptimeMillis(), repeat++), true)
        }
        ui.injectInputEvent(key(KeyEvent.ACTION_UP, android.os.SystemClock.uptimeMillis(), 0), true)
        Thread.sleep(200)
    }
    fun longOk() {
        // A held OK: repeated ACTION_DOWN events over ~700 ms, then ACTION_UP.
        device.executeShellCommand("input keyevent --longpress ${KeyEvent.KEYCODE_DPAD_CENTER}")
        Thread.sleep(200)
    }
}

fun SemanticsNodeInteractionsProvider.onAllNodesWithContentDescriptionExists(text: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

/** In the guide's side menu: Down until Settings (Movies & Series and Recordings sit above it). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.downToRailSettings() {
    repeat(4) {
        if (runCatching { onNodeWithTag("rail_settings").assertIsFocused() }.isSuccess) return
        Remote.down()
        waitForIdle()
    }
}
