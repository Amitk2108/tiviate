package app.tiviate

import android.app.UiAutomation
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
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
 * Tiviate on a phone (touch, upright and sideways), with the test playlist. Runs on a phone emulator when
 * asked: -Pandroid.testInstrumentationRunnerArguments.phone=true. Screens are saved to /sdcard/tiviate-phone/.
 */
@RunWith(AndroidJUnit4::class)
class PhoneFlowTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val fixtures = FixtureServer().start()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @After
    fun tearDown() {
        ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
        scenario?.close()
        fixtures.shutdown()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_200)
        ui.executeShellCommand("mkdir -p /sdcard/tiviate-phone").close()
        ui.executeShellCommand("screencap -p /sdcard/tiviate-phone/$name.png").close()
        Thread.sleep(600)
    }

    @Test
    fun aTapTypesIntoAFieldAndPasteFillsIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phone") == "true")
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            graph.settings.update { it.copy(activePlaylistId = null) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.onNodeWithText("M3U link").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("field_url") }

        // Phones: the fields use the full width, not a narrow column beside the explanation.
        val screen = rule.onNodeWithTag("add_playlist_screen").fetchSemanticsNode().size.width
        org.junit.Assert.assertTrue(rule.onNodeWithTag("field_url").fetchSemanticsNode().size.width > screen * 0.8f)

        // A tap goes into the field (this is what opens the keyboard).
        rule.onNodeWithTag("field_name_input").performClick()
        rule.onNodeWithTag("field_name_input").assertIsFocused()
        rule.onNodeWithTag("field_name_input").performTextInput("Home")
        rule.onNodeWithTag("field_name_input").assertTextEquals("Home")

        // Paste fills the field with the copied link.
        val link = fixtures.url("/list.m3u")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ApplicationProvider.getApplicationContext<TiviateApp>().getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("link", " $link\n"))
        }
        rule.onNodeWithTag("field_url_paste").performClick()
        rule.onNodeWithTag("field_url_input").assertTextEquals(link)
        shot("10-add-playlist")
    }

    @Test
    fun aFilmShowsASeekBarThatMovesWithTouch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phone") == "true")
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            val id = graph.playlists.addM3uUrl("My IPTV", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("phone_home") }
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("phone_channel_1") }
        rule.onNodeWithTag("phone_channel_1").performClick()
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        fun position(): Long {
            var at = -1L
            InstrumentationRegistry.getInstrumentation().runOnMainSync { at = graph.player.engine.seekInfo()?.positionMs ?: -1L }
            return at
        }

        // The test channels are short films: a tap on the video shows the controls with a seek bar.
        rule.onNodeWithTag("phone_video").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("seek_bar") }
        rule.onNodeWithTag("seek_back").performClick()
        rule.onNodeWithTag("seek_back").performClick()
        rule.waitUntil(3_000) { position() in 0..2_000 }
        rule.onNodeWithTag("seek_forward").performClick()
        rule.waitUntil(2_000) { position() >= 9_000 }
        // A tap on the line jumps there (the middle of a 12 s film).
        rule.onNodeWithTag("seek_line").performTouchInput { click(center) }
        rule.waitUntil(2_000) { position() in 4_000..9_000 }
        shot("11-seek")
    }

    @Test
    fun watchBrowseAndGoFullscreenWithTouch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phone") == "true")
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        // Android's one-time "Viewing full screen" hint would sit over the fullscreen screens and take Back.
        ui.executeShellCommand("settings put secure immersive_mode_confirmations confirmed").close()

        // First start: the welcome cards answer a tap.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        shot("01-welcome")
        scenario?.close()

        runBlocking {
            val id = graph.playlists.addM3uUrl("My IPTV", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("phone_home") }
        rule.waitUntil(10_000) { rule.textExists("Aurora News") }
        shot("02-home")

        // A tap on a channel plays it.
        rule.onNodeWithTag("phone_channel_1").performClick()
        rule.waitUntil(15_000) { graph.player.current.value?.name == "Beacon Sports" && graph.player.engine.state.value.isPlaying }
        shot("03-home-playing")

        // The guide tab: rows of programmes; a tap opens the programme with Watch.
        rule.onNodeWithTag("tab_guide").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("phone_guide") }
        rule.waitUntil(10_000) { rule.textExists("Aurora News Show 3") || rule.textExists("Aurora News Show 2") || rule.textExists("Aurora News Show 4") }
        shot("04-guide")
        rule.onNodeWithTag("phone_guide_row_2").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("phone_programme_sheet") }
        shot("05-programme")
        rule.onNodeWithTag("phone_watch").performClick()
        rule.waitUntil(15_000) { graph.player.current.value?.name == "Cobalt Movies" }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("phone_home") }

        // Settings and search open from the bottom bar.
        rule.onNodeWithTag("tab_settings").performClick()
        rule.waitForIdle()
        shot("06-settings")
        // Phones: a section opens full width; Back returns to the list, then to Live.
        rule.onNodeWithTag("section_remote").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("setting_left_right_zap") }
        shot("06b-settings-remote")
        // Tablets keep the two-column settings: Back leaves at once. Phones go back to the list first.
        if (rule.onAllNodesWithTagExists("settings_back")) {
            Remote.back()
            rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("section_general") }
        }
        Remote.back()
        rule.onNodeWithTag("tab_search").performClick()
        rule.waitForIdle()
        shot("07-search")
        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("phone_home") }

        // Every TV option is one tap away: Options on the video opens them (fullscreen, as on TV).
        rule.onNodeWithTag("phone_video").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithContentDescriptionExists("Options") }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Options")).performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTagExists("options_panel") }
        shot("08a-options")
        Remote.back() // closes the options
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("options_panel") }
        Remote.back() // leaves fullscreen: upright again
        rule.waitUntil(8_000) { rule.onAllNodesWithTagExists("phone_home") }
        // A real phone hands rotation back to its motion sensor once it is held upright; the emulator has none.
        scenario?.onActivity { app.tiviate.ui.phone.Rotation.release(); it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

        // Sideways: fullscreen video; a tap shows the touch controls.
        ui.setRotation(UiAutomation.ROTATION_FREEZE_90)
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("phone_home") }
        shot("08-fullscreen")
        ui.executeShellCommand("input tap 1200 400").close()
        Thread.sleep(800)
        shot("09-fullscreen-controls")
        ui.setRotation(UiAutomation.ROTATION_FREEZE_0)
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("phone_home") }
    }
}
