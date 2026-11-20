package app.tiviate

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Movies & Series of an Xtream account, with the remote: browse, play, resume, and back to live TV. */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private var playlistId = 0L

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            playlistId = graph.playlists.addXtream("Fixture Xtream", XtreamCredentials(fixtures.url("/").trimEnd('/'), "u", "p"), preferHls = true)
            graph.playlists.sync(playlistId).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = playlistId, startFullscreen = true) }
        }
        // Movies & Series load after the channels, in the background.
        rule.waitUntil(15_000) { runBlocking { graph.library.count(playlistId).first() } == 2 }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario.close()
        fixtures.shutdown()
    }

    private fun openLibrary() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("rail_library") }
        rule.waitForIdle()
        Remote.down() // TV → Movies & Series
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_screen") && rule.textExists("Test Film") }
    }

    @Test
    fun aFilmPlaysAndResumesThenLiveTvComesBack() {
        openLibrary()
        rule.onNodeWithTag("library_item_0").activate()
        rule.waitUntil(5_000) { rule.textExists("A short test film.") } // details from the provider
        rule.onNodeWithTag("movie_play").activate()
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying }
        assert(graph.player.engine.currentUrl.orEmpty().contains("/movie/u/p/501")) { graph.player.engine.currentUrl.orEmpty() }
        assert(graph.player.current.value == null) { "the live channel stops while a film plays" }
        Thread.sleep(3_000)

        Remote.back() // leaves the film: where it stopped is kept
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("movie_resume") }

        Remote.back()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("library_screen") }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("library_resume_0") } // Continue watching

        // Back to the guide it was opened from; OK plays live TV again.
        Remote.back()
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("library_screen") && rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(15_000) { graph.player.current.value != null && graph.player.engine.state.value.isPlaying && graph.player.engine.state.value.channelKey?.startsWith("vod:") == false }
    }

    @Test
    fun aSeriesListsItsEpisodesAndPlaysThem() {
        openLibrary()
        rule.onNodeWithTag("library_tab_series").activate()
        rule.waitUntil(5_000) { rule.textExists("Test Show") }
        rule.onNodeWithTag("library_item_0").activate()
        rule.waitUntil(5_000) { rule.textExists("Pilot") && rule.textExists("Second") }
        rule.onNodeWithTag("series_episode_1").activate()
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying }
        assert(graph.player.engine.currentUrl.orEmpty().contains("/series/u/p/702")) { graph.player.engine.currentUrl.orEmpty() }
    }
}
