package app.tiviate

import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
class StartScreenTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
    }

    @After
    fun tearDown() {
        scenario?.close()
        fixtures.shutdown()
    }

    private fun seed(startFullscreen: Boolean) = runBlocking {
        val id = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
        graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
        graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = startFullscreen) }
    }

    @Test
    fun launchOpensTheGuideWithThePreviewPlayingByDefault() {
        seed(startFullscreen = false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        Remote.back() // Back from the guide goes to fullscreen
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("player_screen") && !rule.onAllNodesWithTagExists("guide_screen") }
    }

    @Test
    fun launchGoesFullscreenWhenTheSettingIsOn() {
        seed(startFullscreen = true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("player_screen") && !rule.onAllNodesWithTagExists("guide_screen") }
    }
}
