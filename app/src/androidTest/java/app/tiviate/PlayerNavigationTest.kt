package app.tiviate

import android.view.KeyEvent
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
            graph.settings.update { it.copy(activePlaylistId = id) }
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
    fun zapDownChangesChannelAndShowsBanner() {
        assertEquals("Aurora News", current())
        Remote.down()
        rule.waitUntil(5_000) { current() == "Ember Music" } // Down = previous channel, wrapping around
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("zap_banner") }
        Remote.up()
        rule.waitUntil(5_000) { current() == "Aurora News" }
        Remote.press(KeyEvent.KEYCODE_CHANNEL_UP)
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
    }

    @Test
    fun okOpensPanelAndOkTunesFocused() {
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
        // Rail order: Search, All channels, Favorites, Recent, News & Sports, Entertainment, Settings
        Remote.down(5)
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_panel") }
        Remote.ok() // first channel of "Entertainment"
        rule.waitUntil(5_000) { current() == "Cobalt Movies" }
    }

    @Test
    fun menuOpensQuickActionsAndTogglesFavorite() {
        Remote.menu()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("quick_actions") }
        rule.onNodeWithTag("action_favorite").activate()
        rule.waitUntil(3_000) { runBlocking { graph.channels.favoriteKeys(graph.player.current.value!!.playlistId).first().isNotEmpty() } }
    }

    @Test
    fun audioDialogListsTwoTracks() {
        rule.waitUntil(10_000) { graph.player.engine.audioTracksOnMain().size == 2 }
        Remote.menu()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("quick_actions") }
        rule.onNodeWithTag("action_audio").activate()
        rule.waitUntil(3_000) { rule.textExists("English") && rule.textExists("Deutsch") }
    }

    @Test
    fun subtitleDialogHasOffOption() {
        Remote.menu()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("quick_actions") }
        rule.onNodeWithTag("action_subtitles").activate()
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
        graph.player.stop()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { current() == "Beacon Sports" && graph.player.engine.state.value.isPlaying }
    }

    @Test
    fun backTwiceExits() {
        Remote.back()
        rule.waitUntil(3_000) { rule.textExists("Press Back again to exit") }
        Remote.back()
        rule.waitUntil(5_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        assertNotEquals(null, graph)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()

/** Track lists come from the player, which lives on the main thread. */
fun app.tiviate.player.Media3Engine.audioTracksOnMain(): List<app.tiviate.player.TrackOption> {
    var out: List<app.tiviate.player.TrackOption> = emptyList()
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { out = audioTracks() }
    return out
}
