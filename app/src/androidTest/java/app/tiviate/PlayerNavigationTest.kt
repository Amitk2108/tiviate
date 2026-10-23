package app.tiviate

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Player screen behaviour driven only by remote keys, against the local fixture playlist. */
@RunWith(AndroidJUnit4::class)
class PlayerNavigationTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            val id = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true) } // these flows start from fullscreen
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

    private fun current() = graph.player.current.value?.name

    @Test
    fun channelDownChangesChannelAndShowsBanner() {
        assertEquals("Aurora News", current())
        Remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        rule.waitUntil(5_000) { current() == "Ember Music" } // CH− = previous channel, wrapping around
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("zap_banner") }
        Remote.press(KeyEvent.KEYCODE_CHANNEL_UP)
        rule.waitUntil(5_000) { current() == "Aurora News" }
    }

    @Test
    fun upChangesToTheNextChannel() {
        assertEquals("Aurora News", current())
        Remote.up()
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("zap_banner") }
    }

    @Test
    fun downOpensTheOptionsPanel() {
        Remote.down()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.waitForIdle()
        rule.onNodeWithTag("option_audio").assertIsFocused()
        assertEquals("Down must not change channel", "Aurora News", current())
        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("options_panel") }
    }

    @Test
    fun okOpensTheControlPanelWithShortcutsAndRecentChannels() {
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("control_panel") }
        rule.waitForIdle()
        rule.onNodeWithTag("control_channels").assertIsFocused()
        rule.onNodeWithTag("control_guide").assertExistsCompat()
        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("control_panel") }
    }

    @Test
    fun okOpensPanelAndOkTunesFocused() {
        Remote.ok() // control panel; "Channels" has focus
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("control_panel") }
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_panel") }
        Remote.down(2)
        Remote.ok()
        rule.waitUntil(5_000) { current() == "Cobalt Movies" }
        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("channel_panel") }
    }

    @Test
    fun leftOpensCategoriesAndGroupFilters() {
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        rule.waitUntil(3_000) { rule.textExists("Entertainment") }
        rule.onNodeWithText("Entertainment", substring = true, useUnmergedTree = true).assertExistsCompat()
        // Rail order: Search, All channels, Favorites, Recent, News & Sports, Entertainment, Settings.
        // It opens focused on the current category (All channels).
        Remote.down(4)
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_panel") }
        Remote.ok() // first channel of "Entertainment"
        rule.waitUntil(5_000) { current() == "Cobalt Movies" }
    }

    @Test
    fun menuOpensTheOptionsPanelAndFavoriteToggles() {
        Remote.menu()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.waitForIdle()
        repeat(12) { if (runCatching { rule.onNodeWithTag("option_favorite").assertIsFocused() }.isFailure) { Remote.right(); rule.waitForIdle() } }
        Remote.ok()
        rule.waitUntil(3_000) { runBlocking { graph.channels.favoriteKeys(graph.player.current.value!!.playlistId).first().isNotEmpty() } }
    }

    @Test
    fun audioDialogListsTwoTracks() {
        rule.waitUntil(10_000) { graph.player.engine.audioTracksOnMain().size == 2 }
        Remote.down()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.onNodeWithTag("option_audio").activate()
        rule.waitUntil(3_000) { rule.textExists("English") && rule.textExists("Deutsch") }
    }

    @Test
    fun subtitleDialogHasOffOption() {
        Remote.down()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.onNodeWithTag("option_subtitles").activate()
        rule.waitUntil(3_000) { rule.textExists("Off") }
    }

    @Test
    fun historyRecordsAfterFiveSeconds() {
        Thread.sleep(6_500)
        val pid = graph.player.current.value!!.playlistId
        val recent = runBlocking { graph.db.history().recent(pid).first() }
        assertTrue(recent.any { it.name == "Aurora News" })
    }

    @Test
    fun playPauseKeyPausesAndResumes() {
        Remote.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        rule.waitUntil(3_000) { graph.player.engine.state.value.isPaused }
        Remote.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        rule.waitUntil(5_000) { !graph.player.engine.state.value.isPaused && graph.player.engine.state.value.isPlaying }
    }

    @Test
    fun numberKeysTuneChannel() {
        Remote.press(KeyEvent.KEYCODE_4)
        rule.waitUntil(5_000) { current() == "Delta Kids" }
    }

    @Test
    fun relaunchResumesLastChannel() {
        Remote.up()
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
        Thread.sleep(500)
        scenario.close()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { graph.player.stop() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { current() == "Beacon Sports" && graph.player.engine.state.value.isPlaying }
    }

    @Test
    fun backFromFullscreenOpensTheGuide() {
        rule.waitUntil(8_000) { !rule.onAllNodesWithTagExists("zap_banner") } // first Back would only hide the banner
        val starts = graph.player.engine.startCount
        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        assertEquals("the channel keeps playing in the preview", starts, graph.player.engine.startCount)
        assertNotEquals(null, graph)
    }

    @Test
    fun errorCardButtonsAreReachableWithTheRemote() {
        fixtures.failStreams = true
        Remote.up() // tune the next channel: its stream now fails
        rule.waitUntil(20_000) { rule.onAllNodesWithTagExists("error_card") }
        rule.waitForIdle()
        Remote.right()
        rule.waitForIdle()
        assertTrue("Right must not leave for the guide", !rule.onAllNodesWithTagExists("guide_screen"))
        rule.onNodeWithTag("error_next").assertIsFocused()
        Remote.right()
        rule.onNodeWithTag("error_external").assertIsFocused()
        Remote.left()
        Remote.left()
        rule.onNodeWithTag("error_retry").assertIsFocused()
    }


    @Test
    fun idlePanelClosesAfterTheChosenTime() {
        runBlocking { graph.settings.update { it.copy(panelTimeoutSec = 10) } }
        Remote.ok() // control panel; "Channels" has focus
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("control_panel") }
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_panel") }
        rule.waitUntil(15_000) { !rule.onAllNodesWithTagExists("channel_panel") }
    }

}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()

/** Track lists come from the player, which lives on the main thread. */
fun app.tiviate.player.Media3Engine.audioTracksOnMain(): List<app.tiviate.player.TrackOption> {
    var out: List<app.tiviate.player.TrackOption> = emptyList()
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { out = audioTracks() }
    return out
}
