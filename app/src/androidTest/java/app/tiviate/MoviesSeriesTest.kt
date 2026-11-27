package app.tiviate

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import app.tiviate.data.LibraryRepository
import app.tiviate.data.db.TitlePrefEntity
import androidx.test.platform.app.InstrumentationRegistry
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

/** Movies & Series phase 2 and 3, on the open-film library of [FixtureServer], with the remote. */
@RunWith(AndroidJUnit4::class)
class MoviesSeriesTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private var playlistId = 0L

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        // Online services: the test server's (nothing goes to them without a key).
        app.tiviate.data.MetaEndpoints.tmdb = fixtures.url("/tmdb/3").trimEnd('/')
        app.tiviate.data.MetaEndpoints.tmdbImages = fixtures.url("/img").trimEnd('/')
        app.tiviate.data.MetaEndpoints.omdb = fixtures.url("/omdb").trimEnd('/')
        app.tiviate.data.MetaEndpoints.openSubtitles = fixtures.url("/os/api/v1").trimEnd('/')
        runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            playlistId = graph.playlists.addXtream("Fixture Xtream", XtreamCredentials(fixtures.url("/").trimEnd('/'), "u", "p"), preferHls = true)
            graph.playlists.sync(playlistId).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = playlistId, startFullscreen = true) }
        }
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

    /** [tag]'s node, or one inside it, shows [text]. */
    private fun shows(tag: String, text: String): Boolean {
        val t: SemanticsMatcher = hasText(text, substring = true)
        return rule.onAllNodes(hasTestTag(tag) and (t or hasAnyDescendant(t)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Fullscreen → guide → its menu → Movies & Series. */
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
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_screen") }
    }

    /** The home's rows come into view as they are scrolled to: scroll to the row with [key] ("c:<group>", "mylist"…). */
    private fun scrollToRow(key: String) {
        // Rows can arrive a moment later (groups load, online rows look titles up): try until the row is there.
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("library_rows").performScrollToKey(key) }.isSuccess }
        rule.waitForIdle()
    }


    /** Movies & Series → (Series tab) → search [name] → its page. */
    private fun openTitle(name: String, series: Boolean = false) {
        openLibrary()
        if (series) rule.onNodeWithTag("library_tab_series").activate()
        rule.onNodeWithTag("library_search_input").performTextInput(name)
        rule.waitUntil(5_000) { shows("library_item_0", name) }
        rule.onNodeWithTag("library_item_0").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_details") }
    }

    private fun isMarked(key: String, kind: Int) = runBlocking { graph.db.vod().isMarked(playlistId, key, kind).first() }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun position() = onMain { graph.player.engine.seekInfo()?.positionMs ?: 0 }

    private fun germanSound() = onMain { graph.player.engine.audioTracks().any { it.selected && it.language == "de" } }

    private fun playing() = rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying

    @Test
    fun aFilmPageShowsItsBackdropTrailerAndLists() {
        openTitle("Sintel")
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("details_backdrop") && rule.onAllNodesWithTagExists("details_trailer") && rule.textExists("Colin Levy") }
        rule.onNodeWithTag("details_mylist").activate()
        rule.waitUntil(3_000) { isMarked("movie:512", 0) }
        rule.onNodeWithTag("details_favorite").activate()
        rule.waitUntil(3_000) { isMarked("movie:512", 1) }
        rule.onNodeWithTag("details_mylist").activate() // again: off the list
        rule.waitUntil(3_000) { !isMarked("movie:512", 0) }
    }

    /** A provider that sends no backdrop or trailer: the page has no Trailer button. */
    @Test
    fun aFilmWithoutExtrasHidesThem() {
        openTitle("Elephants Dream")
        rule.waitUntil(5_000) { rule.textExists("Two men wander") }
        assert(!rule.onAllNodesWithTagExists("details_trailer"))
    }

    @Test
    fun episodesShowPicturesDescriptionsAndSeasons() {
        openTitle("Agent 327", series = true)
        rule.waitUntil(5_000) { rule.textExists("The Barbershop") && rule.textExists("A hidden lab under a barbershop.") && rule.textExists("2017-05-15") }
        rule.onNodeWithTag("series_season_2").activate()
        rule.waitUntil(3_000) { rule.textExists("Return of Boris") && !rule.textExists("The Barbershop") }
    }

    /** Review Focus 5: a very long series. */
    @Test
    fun goToEpisodeReachesIt() {
        openTitle("Long Show", series = true)
        rule.waitUntil(5_000) { rule.textExists("Part 1") && rule.onAllNodesWithTagExists("series_goto") }
        rule.onNodeWithTag("series_goto").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("series_goto_dialog_field_input") }
        rule.onNodeWithTag("series_goto_dialog_field_input").performTextInput("37")
        rule.onNodeWithTag("series_goto_dialog_save").activate()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("series_episode_36") and (isFocused() or hasAnyDescendant(isFocused())), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theChosenSoundTrackIsKeptForTheTitle() {
        openTitle("Sintel")
        fun play() {
            rule.onNodeWithTag("movie_play").activate()
            rule.waitUntil(15_000) { playing() && onMain { graph.player.engine.audioTracks().size } == 2 }
        }
        play()
        Remote.ok() // the controls
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("vod_audio") }
        rule.onNodeWithTag("vod_audio").activate()
        rule.waitUntil(3_000) { rule.textExists("Deutsch") }
        rule.onNode(hasText("Deutsch", substring = true)).activate()
        rule.waitUntil(5_000) { germanSound() }
        // Back leaves the film (a second Back if the first only closed what was still open).
        fun onThePage() = rule.onAllNodesWithTagExists("movie_resume") || rule.onAllNodesWithTagExists("movie_play")
        repeat(2) { if (!onThePage()) { Remote.back(); runCatching { rule.waitUntil(4_000) { onThePage() } } } }
        rule.waitUntil(5_000) { onThePage() }
        // From the start again: the German sound comes back by itself.
        rule.onNodeWithTag(if (rule.onAllNodesWithTagExists("movie_resume")) "movie_resume" else "movie_play").activate()
        rule.waitUntil(15_000) { playing() && germanSound() }
    }

    @Test
    fun newEpisodesAreFlagged() {
        runBlocking {
            graph.db.vod().saveProgress(
                // Watched before the provider added episodes.
                app.tiviate.data.db.WatchProgressEntity(playlistId, "episode:2101", "Agent 327 · S1 E1", null, 60_000, 600_000, 1_000_000L, seriesId = 21),
            )
            graph.db.vod().savePref(TitlePrefEntity(playlistId, "series:21", seenAt = 1_000L)) // seen long before the last update
        }
        openLibrary()
        rule.onNodeWithTag("library_tab_series").activate()
        scrollToRow("c:Animation")
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_new_21") }
        rule.onNodeWithTag("library_search_input").performTextInput("Agent")
        rule.waitUntil(5_000) { shows("library_item_0", "Agent 327") }
        rule.onNodeWithTag("library_item_0").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_details") }
        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_screen") && !rule.onAllNodesWithTagExists("library_new_21") }
    }

    @Test
    fun skipIntroJumpsPastTheIntro() {
        openTitle("Agent 327", series = true)
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("series_episode_0") }
        rule.onNodeWithTag("series_episode_0").activate()
        rule.waitUntil(15_000) { playing() }
        rule.waitUntil(10_000) { position() >= 4_000 }
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("vod_intro_set") }
        rule.onNodeWithTag("vod_intro_set").activate() // "the intro ends here"
        val introEnd = position()
        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("series_episode_1") }
        rule.onNodeWithTag("series_episode_1").activate()
        rule.waitUntil(15_000) { playing() && rule.onAllNodesWithTagExists("vod_skip_intro") }
        rule.onNodeWithTag("vod_skip_intro").activate()
        rule.waitUntil(5_000) { position() >= introEnd - 500 }
    }

    /** The viewer's keys (the test server accepts "good-key"). */
    private fun withKeys() = runBlocking { graph.settings.update { it.copy(tmdbKey = "good-key", omdbKey = "good-key", openSubtitlesKey = "good-key") } }

    @Test
    fun ratingsAndCastShowWithKeys() {
        withKeys()
        openTitle("Sintel")
        rule.waitUntil(10_000) { shows("details_imdb", "IMDb 7.4") && shows("details_rt", "76%") && rule.onAllNodesWithTagExists("details_cast_0") }
        rule.onNodeWithTag("details_cast_0").activate()
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("person_screen") && rule.textExists("A Dutch actress.") && shows("person_item_0", "Sintel") }
    }

    /** Without keys nothing is asked of the online services. */
    @Test
    fun withoutKeysNothingIsSent() {
        openTitle("Sintel")
        rule.waitUntil(5_000) { rule.textExists("Colin Levy") }
        Thread.sleep(1_500)
        assert(fixtures.onlineRequests.get() == 0) { "online requests: ${fixtures.onlineRequests.get()}" }
        assert(!rule.onAllNodesWithTagExists("details_imdb"))
    }

    @Test
    fun topTenListsTrendingTitlesInThePlaylist() {
        withKeys()
        openLibrary()
        scrollToRow("top10")
        rule.waitUntil(10_000) { shows("library_row_top10", "Sintel") }
        rule.onNodeWithTag("library_tab_series").activate()
        scrollToRow("top10")
        rule.waitUntil(10_000) { shows("library_row_top10", "Agent 327") }
    }

    @Test
    fun becauseYouWatchedFollowsTheLastTitle() {
        withKeys()
        runBlocking {
            graph.db.vod().saveProgress(app.tiviate.data.db.WatchProgressEntity(playlistId, "movie:512", "Sintel", null, 60_000, 888_000, System.currentTimeMillis()))
        }
        openLibrary()
        scrollToRow("because")
        rule.waitUntil(10_000) { shows("library_row_because", "Tears of Steel") && rule.textExists("Because you watched Sintel") }
    }

    @Test
    fun anOnlineSubtitleIsAttachedAndKept() {
        withKeys()
        openTitle("Sintel")
        rule.onNodeWithTag("movie_play").activate()
        rule.waitUntil(15_000) { playing() }
        fun onlineSubtitleOn() = onMain { graph.player.engine.textTracks().any { it.selected && it.label.contains("OpenSubtitles") } }
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("vod_subtitles") }
        rule.onNodeWithTag("vod_subtitles").activate()
        rule.waitUntil(3_000) { rule.textExists("Search online") }
        rule.onNode(hasText("Search online", substring = true)).activate()
        rule.waitUntil(10_000) { rule.textExists("Sintel.2010.Open.Movie") }
        rule.onNode(hasText("Sintel.2010.Open.Movie", substring = true)).activate()
        rule.waitUntil(15_000) { onlineSubtitleOn() }
        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("movie_resume") }
        rule.onNodeWithTag("movie_resume").activate()
        rule.waitUntil(15_000) { playing() && onlineSubtitleOn() }
    }

    @Test
    fun theOpenFilmLibraryLoads() {
        val sintel = runBlocking { graph.db.vod().movie(playlistId, 512) }
        assert(sintel?.name == "Sintel") { sintel.toString() }
        assert(sintel?.addedAt == 1_709_251_200_000L) { "added: ${sintel?.addedAt}" }
        assert(runBlocking { graph.db.vod().seriesItem(playlistId, 21) }?.name == "Agent 327")
        openLibrary()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_rows") }
    }

    @Test
    fun theHomeShowsRowsAndAHero() {
        openLibrary()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_hero") && shows("library_row_recent", "Tears of Steel") }
        scrollToRow("c:Open Films")
        rule.waitUntil(5_000) { shows("library_row_Open Films", "Sintel") }
    }

    /** The title at the top follows the highlighted poster. */
    @Test
    fun theHeroFollowsTheHighlightedPoster() {
        openLibrary()
        // Down from the tabs: the hero's buttons, then the first row (Recently added, Tears of Steel first).
        fun focused(i: Int) = rule.onAllNodes(hasTestTag("library_row_recent_item_$i") and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        fun onFirstPoster() = focused(0)
        fun inTheRow() = (0..3).any { focused(it) }
        repeat(4) { if (!inTheRow()) { Remote.down(); runCatching { rule.waitUntil(2_000) { inTheRow() } } } }
        repeat(4) { if (!onFirstPoster()) { Remote.left(); runCatching { rule.waitUntil(1_000) { onFirstPoster() } } } }
        rule.waitUntil(5_000) { onFirstPoster() && shows("library_hero", "Tears of Steel") && shows("library_hero", "Science Fiction") }
        Remote.right()
        rule.waitUntil(5_000) { shows("library_hero", "Sintel") }
    }

    @Test
    fun showAllOpensTheWholeGroup() {
        openLibrary()
        scrollToRow("c:Open Films")
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_all_Open Films") }
        rule.onNodeWithTag("library_all_Open Films").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_all_screen") && shows("library_item_0", "Big Buck Bunny") && shows("library_item_2", "Tears of Steel") }
    }

    @Test
    fun sortingByNameAndRatingReordersTheGrid() {
        openLibrary()
        scrollToRow("c:Open Films")
        rule.onNodeWithTag("library_all_Open Films").activate()
        rule.waitUntil(5_000) { shows("library_item_0", "Big Buck Bunny") } // as listed
        rule.onNodeWithTag("library_sort").activate() // newest
        rule.waitUntil(5_000) { shows("library_item_0", "Tears of Steel") }
        rule.onNodeWithTag("library_sort").activate() // A–Z
        rule.waitUntil(5_000) { shows("library_item_0", "Big Buck Bunny") && shows("library_item_2", "Tears of Steel") }
        rule.onNodeWithTag("library_sort").activate() // rating: Sintel 7.4, Big Buck Bunny 6.4, Tears of Steel 6.1
        rule.waitUntil(5_000) { shows("library_item_0", "Sintel") && shows("library_item_1", "Big Buck Bunny") }
    }

    @Test
    fun aGenreFiltersSeries() {
        openLibrary()
        rule.onNodeWithTag("library_tab_series").activate()
        scrollToRow("c:Shows")
        rule.onNodeWithTag("library_all_Shows").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_all_screen") }
        rule.onNodeWithTag("library_cat_all").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_genre_Drama") }
        rule.onNodeWithTag("library_genre_Drama").activate()
        rule.waitUntil(5_000) { shows("library_item_0", "Long Show") && !rule.onAllNodesWithTagExists("library_item_1") }
    }

    @Test
    fun myListAndFavoritesHaveRows() {
        runBlocking {
            graph.library.toggleMark(playlistId, "movie:512", LibraryRepository.MY_LIST)
            graph.library.toggleMark(playlistId, "movie:511", LibraryRepository.FAVORITE)
        }
        openLibrary()
        scrollToRow("mylist")
        rule.waitUntil(5_000) { shows("library_row_mylist", "Sintel") }
        scrollToRow("favorites")
        rule.waitUntil(5_000) { shows("library_row_favorites", "Big Buck Bunny") }
    }
}
