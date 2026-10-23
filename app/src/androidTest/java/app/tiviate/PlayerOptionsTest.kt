package app.tiviate

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.DisplayMode
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The fullscreen options panel's pickers: audio/subtitles for all channels, display, zoom, stats, sleep. */
@RunWith(AndroidJUnit4::class)
class PlayerOptionsTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private fun settings() = runBlocking { graph.settings.settings.first() }

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            val id = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario.close()
        fixtures.shutdown()
    }

    private fun focusedText(): String = rule.onAllNodes(isFocused(), useUnmergedTree = false).fetchSemanticsNodes()
        .joinToString { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() }

    /** Opens the options with Down, then moves Right along the cards (as a viewer would) and presses OK. */
    private fun pressOption(tag: String) {
        if (!rule.onAllNodesWithTagExists("options_panel")) {
            Remote.down()
            rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
            rule.waitForIdle()
        }
        // Cards left of the starting card (e.g. Pause) are reached by going to the start first.
        repeat(14) { i ->
            if (runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess) {
                Remote.ok()
                return
            }
            if (i == 0) Remote.left(4) else Remote.right()
            rule.waitForIdle()
        }
        throw AssertionError("could not reach $tag")
    }

    private fun openOption(tag: String, dialog: String) {
        pressOption(tag)
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists(dialog) }
        rule.waitForIdle()
    }

    @Test
    fun audioLanguageForAllChannelsIsSavedAndFollowsTheChoice() {
        rule.waitUntil(10_000) { graph.player.engine.audioTracksOnMain().size == 2 }
        openOption("option_audio", "track_dialog")
        // Remote only: English (playing, focused) → Deutsch → the "all channels" switch.
        Remote.down(2)
        rule.onNodeWithTag("picker_toggle").assertIsFocused()
        Remote.ok()
        rule.waitUntil(3_000) { settings().preferredAudioLanguage == "en" } // the language playing now
        // Up re-enters the list on the row used last (English); move on to Deutsch.
        Remote.up()
        repeat(3) { if (!focusedText().contains("Deutsch")) Remote.down() }
        Remote.ok()
        rule.waitUntil(5_000) { settings().preferredAudioLanguage == "de" }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") } // back to the options
        rule.onNodeWithTag("option_audio").assertIsFocused()
    }

    @Test
    fun displayModeAndZoomApplyToAllChannels() {
        openOption("option_display", "display_dialog")
        rule.onNodeWithTag("choice_16:9").activate()
        rule.waitUntil(3_000) { settings().displayMode == DisplayMode.RATIO_16_9 }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") && rule.textExists("16:9") }
        pressOption("option_zoom")
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("zoom_dialog") }
        rule.onNodeWithTag("choice_120%").activate()
        rule.waitUntil(3_000) { settings().zoomPercent == 120 }
    }

    @Test
    fun statsOverlayToggles() {
        pressOption("option_stats")
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("stats_overlay") && rule.textExists("320 × 180") }
        pressOption("option_stats")
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("stats_overlay") }
    }

    @Test
    fun sleepTimerStartsAndTurnsOff() {
        openOption("option_sleep", "sleep_dialog")
        rule.onNodeWithTag("choice_30 min").activate()
        rule.waitUntil(3_000) { graph.player.sleepMinutes.value == 30 }
        rule.waitUntil(3_000) { rule.textExists("30 min left") }
        pressOption("option_sleep")
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("sleep_dialog") }
        rule.onNodeWithTag("choice_Off").activate()
        rule.waitUntil(3_000) { graph.player.sleepMinutes.value == null }
    }

    @Test
    fun displayModeCanBeSetForThisChannelOnly() {
        openOption("option_display", "display_dialog")
        // Remote only: from the ticked "Fit" down to the "Apply to all channels" switch, turn it off, then pick 4:3.
        repeat(6) { if (runCatching { rule.onNodeWithTag("picker_toggle").assertIsFocused() }.isFailure) Remote.down() }
        Remote.ok()
        Remote.up()
        repeat(6) { if (!focusedText().contains("4:3")) Remote.up() }
        Remote.ok()
        val key = graph.player.current.value!!.let { "${it.playlistId}:${it.stableKey}" }
        rule.waitUntil(3_000) { settings().channelDisplayModes[key] == DisplayMode.RATIO_4_3 }
        org.junit.Assert.assertEquals("other channels keep Fit", DisplayMode.FIT, settings().displayMode)
    }

    @Test
    fun pauseThenBackToLive() {
        pressOption("option_playpause")
        rule.waitUntil(3_000) { graph.player.engine.state.value.isPaused }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("option_live") }
        val starts = graph.player.engine.startCount
        pressOption("option_live")
        rule.waitUntil(8_000) { !graph.player.engine.state.value.isPaused && graph.player.engine.startCount > starts && graph.player.engine.state.value.isPlaying }
    }

}
