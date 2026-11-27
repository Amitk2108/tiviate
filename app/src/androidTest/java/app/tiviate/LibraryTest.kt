package app.tiviate

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
        rule.waitUntil(15_000) { runBlocking { graph.library.count(playlistId).first() } == FixtureServer.LIBRARY_COUNT }
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
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_screen") && rule.onAllNodesWithTagExists("library_rows") }
    }

    /** The home's rows come into view as they are scrolled to: scroll to the row with [key] ("c:<group>", "mylist"…). */
    private fun scrollToRow(key: String) {
        // Rows can arrive a moment later (groups load, online rows look titles up): try until the row is there.
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("library_rows").performScrollToKey(key) }.isSuccess }
        rule.waitForIdle()
    }


    @Test
    fun aFilmPlaysAndResumesThenLiveTvComesBack() {
        openLibrary()
        scrollToRow("c:Films")
        rule.onNodeWithTag("library_row_Films_item_0").activate()
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

    /** [tag]'s node, or one inside it, shows [text]. */
    private fun shows(tag: String, text: String): Boolean {
        val t: SemanticsMatcher = hasText(text, substring = true)
        return rule.onAllNodes(hasTestTag(tag) and (t or hasAnyDescendant(t)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Sam: search Movies & Series by name. */
    @Test
    fun searchFindsTitlesByName() {
        openLibrary()
        rule.onNodeWithTag("library_search_input").performTextInput("zzz")
        rule.waitUntil(5_000) { rule.textExists("No titles match this search.") && !rule.textExists("Test Film") }
        rule.onNodeWithTag("library_search_input").performTextClearance()
        rule.onNodeWithTag("library_search_input").performTextInput("FILM")
        rule.waitUntil(5_000) { shows("library_item_0", "Test Film") }
        // One search for both tabs.
        rule.onNodeWithTag("library_search_input").performTextClearance()
        rule.onNodeWithTag("library_search_input").performTextInput("show")
        rule.onNodeWithTag("library_tab_series").activate()
        rule.waitUntil(5_000) { shows("library_item_0", "Test Show") }
    }

    /** Sam: on the TV, the highlighted title shows what it's about beside the posters. */
    @Test
    fun aHighlightedTitleShowsItsDescription() {
        openLibrary()
        // The grid with the panel: a row's "Show all".
        scrollToRow("c:Films")
        rule.onNodeWithTag("library_all_Films").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_all_screen") }
        rule.waitForIdle()
        // Down from the tabs, through the groups, to the first poster.
        val films = listOf("Test Film", "Big Buck Bunny", "Sintel", "Tears of Steel", "Elephants Dream")
        repeat(4) { if (films.none { shows("library_info", it) }) { Remote.down(); runCatching { rule.waitUntil(2_000) { films.any { shows("library_info", it) } } } } }
        repeat(5) { if (!shows("library_info", "Test Film")) { Remote.left(); runCatching { rule.waitUntil(1_000) { shows("library_info", "Test Film") } } } }
        rule.waitUntil(5_000) { shows("library_info", "Test Film") }
        rule.waitUntil(5_000) { shows("library_info", "A short test film.") && shows("library_info", "Drama") } // fetched from the provider
    }

    /** Sam: Recently added lists the newest films first. */
    @Test
    fun recentlyAddedListsTheNewestFilmsFirst() {
        runBlocking {
            val film = graph.db.vod().movie(playlistId, 501)!!
            graph.db.vod().upsertMoviesBlocking(
                listOf(
                    film.copy(streamId = 502, name = "Old Film", addedAt = 1_000L, sortIndex = film.sortIndex + 1),
                    film.copy(streamId = 503, name = "New Film", addedAt = System.currentTimeMillis(), sortIndex = film.sortIndex + 2),
                ),
            )
        }
        openLibrary()
        scrollToRow("c:Films")
        rule.onNodeWithTag("library_all_Films").activate()
        rule.waitUntil(5_000) { shows("library_item_0", "Test Film") }
        rule.onNodeWithTag("library_cat_recent").activate()
        rule.waitUntil(5_000) { shows("library_item_0", "New Film") && shows("library_item_1", "Tears of Steel") }
        rule.onNodeWithTag("library_cat_all").activate()
        rule.waitUntil(5_000) { shows("library_item_0", "Test Film") }
    }

    /** When an episode ends, the next one plays (Settings → Player can turn it off). */
    @Test
    fun theNextEpisodePlaysWhenOneEnds() {
        try {
            runBlocking { graph.settings.update { it.copy(autoNextEpisode = false) } }
            openLibrary()
            rule.onNodeWithTag("library_tab_series").activate()
            scrollToRow("c:Shows")
            rule.waitUntil(5_000) { shows("library_row_Shows_item_0", "Test Show") }
            rule.onNodeWithTag("library_row_Shows_item_0").activate()
            rule.waitUntil(5_000) { rule.textExists("Pilot") }
            fun playThePilotToItsEnd() {
                rule.onNodeWithTag("series_episode_0").activate()
                rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying }
                assert(graph.player.engine.currentUrl.orEmpty().contains("/series/u/p/701")) { graph.player.engine.currentUrl.orEmpty() }
                graph.player.vodEnded.value = true // the end of the episode, as the player reports it
            }
            // Off: back to the episodes.
            playThePilotToItsEnd()
            rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("vod_player") && rule.onAllNodesWithTagExists("series_episode_0") }
            // On (the default): the second episode follows.
            runBlocking { graph.settings.update { it.copy(autoNextEpisode = true) } }
            playThePilotToItsEnd()
            rule.waitUntil(15_000) {
                graph.player.engine.currentUrl.orEmpty().contains("/series/u/p/702") && rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying
            }
        } finally {
            runBlocking { graph.settings.update { it.copy(autoNextEpisode = true) } }
        }
    }

    @Test
    fun aSeriesListsItsEpisodesAndPlaysThem() {
        openLibrary()
        rule.onNodeWithTag("library_tab_series").activate()
        scrollToRow("c:Shows")
        rule.waitUntil(5_000) { shows("library_row_Shows_item_0", "Test Show") }
        rule.onNodeWithTag("library_row_Shows_item_0").activate()
        rule.waitUntil(5_000) { rule.textExists("Pilot") && rule.textExists("Second") }
        rule.onNodeWithTag("series_episode_1").activate()
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying }
        assert(graph.player.engine.currentUrl.orEmpty().contains("/series/u/p/702")) { graph.player.engine.currentUrl.orEmpty() }
    }
}
