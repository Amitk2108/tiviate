package app.tiviate

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchTest {

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

    private fun openSearch() {
        Remote.left() // categories rail, focused on "All channels"
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        Remote.up() // "Search"
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("search_screen") }
        rule.waitForIdle()
    }

    @Test
    fun typingFindsChannelAndOkTunes() {
        openSearch()
        rule.onNodeWithTag("search_field").activate()
        rule.onNodeWithTag("search_field_input").performTextInput("cobalt")
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("search_channel_0") }
        rule.onNodeWithTag("search_channel_0").activate()
        rule.waitUntil(5_000) { graph.player.current.value?.name == "Cobalt Movies" }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("player_screen") }
    }

    @Test
    fun typingFindsProgrammeOnNowAndOkTunesItsChannel() {
        openSearch()
        rule.onNodeWithTag("search_field").activate()
        rule.onNodeWithTag("search_field_input").performTextInput("Delta Kids Show 3")
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("search_programme_0") }
        rule.onNodeWithTag("search_programme_0").activate()
        rule.waitUntil(5_000) { graph.player.current.value?.name == "Delta Kids" }
    }
}
