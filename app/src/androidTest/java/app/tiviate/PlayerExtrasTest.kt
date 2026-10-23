package app.tiviate

import androidx.compose.ui.test.assertIsFocused
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tiviate.data.Category
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Previous channel, peek from the info bar, long-OK channel menu, long Back and the menu language. */
@RunWith(AndroidJUnit4::class)
class PlayerExtrasTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val fixtures = FixtureServer().start()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private var playlistId = 0L

    private fun launch(language: String? = null) {
        runBlocking {
            playlistId = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(playlistId).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = playlistId, startFullscreen = true, language = language) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario?.close()
        fixtures.shutdown()
    }

    private fun current() = graph.player.current.value?.name

    /** A Back held for [holdMs], with the repeats a real remote sends. */
    private fun holdBack(holdMs: Long = 900) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downAt = SystemClock.uptimeMillis()
        fun key(action: Int, at: Long, repeat: Int) =
            KeyEvent(downAt, at, action, KeyEvent.KEYCODE_BACK, repeat, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        ui.injectInputEvent(key(KeyEvent.ACTION_DOWN, downAt, 0), true)
        var repeat = 1
        while (SystemClock.uptimeMillis() - downAt < holdMs) {
            Thread.sleep(50)
            ui.injectInputEvent(key(KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis(), repeat++), true)
        }
        ui.injectInputEvent(key(KeyEvent.ACTION_UP, SystemClock.uptimeMillis(), 0), true)
        Thread.sleep(200)
    }

    @Test
    fun lastChannelKeySwapsBetweenTheLastTwoChannels() {
        launch()
        assertEquals("Aurora News", current())
        Remote.up()
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
        Thread.sleep(400) // the stream follows 250 ms after the last zap
        Remote.press(KeyEvent.KEYCODE_LAST_CHANNEL)
        rule.waitUntil(5_000) { current() == "Aurora News" }
        Remote.press(KeyEvent.KEYCODE_LAST_CHANNEL)
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
    }

    @Test
    fun peekFromTheInfoBarOnlySwitchesOnOk() {
        launch()
        Remote.press(KeyEvent.KEYCODE_INFO)
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("zap_banner") }
        Remote.right()
        rule.waitUntil(3_000) { rule.textExists("Beacon Sports") }
        Thread.sleep(600)
        assertEquals("peeking must not switch the channel", "Aurora News", current())
        Remote.ok()
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
    }

    @Test
    fun longOkOnAListRowOpensTheChannelMenuAndHideWorks() {
        launch()
        Remote.ok() // options; "Channels" is the first tile
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.waitForIdle()
        repeat(20) { if (runCatching { rule.onNodeWithTag("option_channels").assertIsFocused() }.isFailure) { Remote.left(); rule.waitForIdle() } }
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_panel") }
        rule.waitForIdle()
        Remote.longOk()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_sheet") }
        rule.onNodeWithTag("sheet_hide", useUnmergedTree = true).activate()
        rule.waitUntil(5_000) {
            runBlocking { graph.channels.list(playlistId, Category.All).none { it.name == "Aurora News" } }
        }
        assertEquals("hiding keeps the channel playing", "Aurora News", current())
    }

    @Test
    fun longBackFromSettingsGoesStraightBackToThePlayer() {
        launch()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        Remote.down(5)
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("settings_screen") }
        rule.onNodeWithTag("section_appearance").activate()
        rule.waitForIdle()
        holdBack()
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("settings_screen") }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("player_screen") }
        assertFalse(rule.onAllNodesWithTagExists("guide_screen"))
    }

    @Test
    fun chosenLanguageTranslatesTheMenus() {
        launch(language = "de")
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        rule.waitUntil(3_000) { rule.textExists("Alle Sender") }
        Remote.down(5)
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("settings_screen") }
        rule.waitUntil(3_000) { rule.textExists("Allgemein") && rule.textExists("Darstellung") }
    }
}
