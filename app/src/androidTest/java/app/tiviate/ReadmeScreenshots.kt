package app.tiviate

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The README screenshots, taken on the emulator with the test playlist (made-up channels and the
 * project's own test video and guide — no real broadcaster content). Runs only when asked:
 *   -Pandroid.testInstrumentationRunnerArguments.screenshots=true
 * Pictures land in /sdcard/tiviate-shots/ (pull them with adb).
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshots {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val fixtures = FixtureServer().start()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun tearDown() {
        scenario?.close()
        fixtures.shutdown()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_200)
        ui.executeShellCommand("mkdir -p /sdcard/tiviate-shots").close()
        ui.executeShellCommand("screencap -p /sdcard/tiviate-shots/$name.png").close()
        Thread.sleep(800)
    }

    @Test
    fun takeReadmeScreenshots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")

        // Welcome (no playlist yet).
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        shot("01-welcome")
        scenario?.close()

        runBlocking {
            val id = graph.playlists.addM3uUrl("My IPTV", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = false) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitUntil(10_000) { rule.textExists("Aurora News") }
        Remote.ok() // play the first channel in the preview
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        Thread.sleep(2_000)
        shot("02-tv-guide")

        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        shot("03-groups")
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_rail") }
        shot("04-menu")
        repeat(3) { if (rule.onAllNodesWithTagExists("guide_rail") || rule.onAllNodesWithTagExists("guide_groups")) Remote.back() }
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()

        Remote.ok() // the playing channel: fullscreen
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("guide_screen") }
        Remote.press(KeyEvent.KEYCODE_INFO)
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("zap_banner") }
        shot("05-fullscreen-info")

        Remote.back()
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        shot("06-player-options")

        repeat(6) { Remote.left() }
        Remote.ok() // "Channels"
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_panel") }
        shot("07-channel-list")

        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        repeat(8) { Remote.down() }
        Remote.ok() // Settings (last entry)
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("settings_screen") }
        shot("08-settings-general")
        rule.onNodeWithTag("section_playlists").activate()
        shot("09-settings-playlists")
        rule.onNodeWithTag("section_appearance").activate()
        shot("10-settings-appearance")
        rule.onNodeWithTag("section_player").activate()
        shot("11-settings-playback")
        rule.onNodeWithTag("section_about").activate()
        shot("12-about")
    }
}
