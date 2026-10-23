package app.tiviate

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** After login the guide opens before its data has arrived; it must fill in by itself. */
@RunWith(AndroidJUnit4::class)
class GuideLateDataTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph
    private var playlistId = 0L

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        fixtures.failGuide = true // channels arrive, the guide doesn't (yet)
        runBlocking {
            playlistId = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(playlistId).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = playlistId, startFullscreen = false) } // start on the guide
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario.close()
        fixtures.shutdown()
    }

    @Test
    fun guideFillsInWhenItsDataArrivesWithoutAKeyPress() {
        fixtures.failGuide = false
        graph.appScope.launch { graph.playlists.refreshGuide(playlistId) }
        rule.waitUntil(15_000) { rule.textExists("Aurora News Show 3") } // the programme on air now
    }
}
