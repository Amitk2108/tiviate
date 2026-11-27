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

/** Fullscreen: Left opens the channel list, Left again the categories (with Search and Settings). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openCategories() {
    // On a busy device a Left pressed while the list is still opening can be missed: press until the categories show.
    repeat(4) {
        if (onAllNodesWithTagExists("categories_rail")) return
        val listOpen = onAllNodesWithTagExists("channel_panel")
        Remote.left()
        runCatching { waitUntil(3_000) { onAllNodesWithTagExists(if (listOpen) "categories_rail" else "channel_panel") } }
    }
    waitUntil(3_000) { onAllNodesWithTagExists("categories_rail") }
}

/** The text a node shows (its own, not merged with others'). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.textOf(tag: String): String =
    onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("")

/** What a phone does on "Easier with your phone": opens the page, then sends [fields] with the TV's code. Returns the answer's status. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.sendFromPhone(vararg fields: Pair<String, String>): Int {
    val address = textOf("phone_address")
    val code = textOf("phone_code")
    val port = address.substringAfterLast(':')
    val http = okhttp3.OkHttpClient()
    val page = http.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:$port/?code=$code").build()).execute().use { it.body!!.string() }
    check(page.contains("name=\"code\"")) { "the phone page" }
    val body = okhttp3.FormBody.Builder().add("code", code).apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    return http.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:$port/send").post(body).build()).execute().use { it.code }
}

/** In the guide's side menu: Down until Settings (Movies & Series and Recordings sit above it). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.downToRailSettings() {
    repeat(4) {
        if (runCatching { onNodeWithTag("rail_settings").assertIsFocused() }.isSuccess) return
        Remote.down()
        waitForIdle()
    }
}
