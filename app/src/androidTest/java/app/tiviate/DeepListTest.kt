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

/** The channel list opened deep inside a long category still has the playing channel focused. */
@RunWith(AndroidJUnit4::class)
class DeepListTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            val id = graph.playlists.addM3uUrl("Big", fixtures.url("/big.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            val deep = graph.channels.byNumber(id, 350)!!
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true, lastChannelKey = deep.stableKey) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.current.value?.name == "Big 350" && graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario.close()
        fixtures.shutdown()
    }

    @Test
    fun listOpensOnThePlayingChannelDeepInTheCategory() {
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("channel_panel") }
        rule.waitUntil(5_000) { rule.textExists("Big 350") }
        Thread.sleep(500) // let the list settle and focus land
        Remote.down()
        Remote.ok() // tunes the focused row: only right if focus started on Big 350
        rule.waitUntil(8_000) { graph.player.current.value?.name == "Big 351" }
    }

    @Test
    fun pageDownJumpsAScreenOfChannels() {
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("channel_panel") }
        rule.waitUntil(5_000) { rule.textExists("Big 350") }
        Thread.sleep(500)
        Remote.press(android.view.KeyEvent.KEYCODE_PAGE_DOWN)
        Remote.ok()
        rule.waitUntil(8_000) { (graph.player.current.value?.number ?: 0) >= 353 }
    }

}
