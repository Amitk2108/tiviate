package app.tiviate

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Multi-view with the remote: the channel on screen plus a second one, then the selected one fullscreen. */
@RunWith(AndroidJUnit4::class)
class MultiViewTest {

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

    private fun tilePlaying(i: Int) = rule.onAllNodes(
        hasTestTag("multiview_tile_$i") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "playing"),
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun twoChannelsAtOnceThenTheSelectedOneFullscreen() {
        Remote.down()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.waitForIdle()
        repeat(20) { if (runCatching { rule.onNodeWithTag("option_multiview").assertIsFocused() }.isFailure) { Remote.right(); rule.waitForIdle() } }
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("multiview_screen") }
        rule.waitUntil(15_000) { tilePlaying(0) } // the channel that was on screen
        assert(!graph.player.engine.state.value.isPlaying) { "the main player rests meanwhile" }

        Remote.right() // the tile beside it
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("multiview_picker") && rule.textExists("Beacon Sports") }
        rule.onNodeWithTag("choice_Beacon Sports", useUnmergedTree = true).activate()
        rule.waitUntil(15_000) { tilePlaying(1) && tilePlaying(0) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/tiviate-multiview.png").close()
        Thread.sleep(800)

        Remote.back() // the selected tile (Beacon Sports) fullscreen
        rule.waitUntil(15_000) {
            !rule.onAllNodesWithTagExists("multiview_screen") && graph.player.current.value?.name == "Beacon Sports" && graph.player.engine.state.value.isPlaying
        }
    }
}
