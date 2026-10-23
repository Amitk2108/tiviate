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
class GuideTest {

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
    fun guideOpensOnNowAndOkPreviewsThenGoesFullscreen() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        // The guide opens on the playing channel with the programme on air focused.
        rule.waitUntil(5_000) { rule.textExists("Aurora News Show 3") }
        Remote.down()
        Remote.ok() // preview Beacon Sports in the guide
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_screen") }
        Remote.ok() // second OK on the previewed channel: fullscreen
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("guide_screen") && rule.onAllNodesWithTagExists("player_screen") }
    }

    @Test
    fun okOnThePlayingChannelDoesNotReconnect() {
        val before = graph.player.engine.state.value.channelKey
        val starts = graph.player.engine.startCount
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("guide_screen") }
        org.junit.Assert.assertEquals(before, graph.player.engine.state.value.channelKey)
        org.junit.Assert.assertEquals("the stream must not be reopened", starts, graph.player.engine.startCount)
    }

    @Test
    fun backTwiceFromTheGuideExits() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
        Remote.back()
        rule.waitUntil(3_000) { rule.textExists("Press Back again to exit") }
        assert(rule.onAllNodesWithTagExists("guide_screen")) { "the first Back only asks" }
        Remote.back()
        rule.waitUntil(5_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
    }

    @Test
    fun okOnAnEarlierProgrammePlaysTheChannelAndReturnsToNow() {
        openGuide()
        Remote.down() // Beacon Sports
        Remote.press(android.view.KeyEvent.KEYCODE_MEDIA_REWIND) // two hours back
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(5_000) { current() == "Beacon Sports" }
        assert(!rule.onAllNodesWithTagExists("programme_details")) { "OK plays the channel, no details dialog" }
        rule.waitUntil(3_000) { rule.textExists("Beacon Sports Show 3") } // focus back on the programme on air
    }

    private fun openGuide() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitUntil(5_000) { rule.textExists("Aurora News Show 3") } // focused on the programme on air
        rule.waitForIdle()
    }

    @Test
    fun leftOpensGroupsAndOkFiltersTheGuide() {
        openGuide()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.down(3) // All channels → Recent → News & Sports → Entertainment
        Remote.ok()
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("guide_groups") && rule.textExists("Cobalt Movies") && !rule.textExists("Beacon Sports") }
    }

    @Test
    fun leftTwiceReachesSettings() {
        openGuide()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_rail") }
        rule.waitForIdle()
        Remote.down() // TV → Settings
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("settings_screen") }
    }

    @Test
    fun backClosesTheGroupsColumnFirst() {
        openGuide()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("guide_groups") }
        assert(rule.onAllNodesWithTagExists("guide_screen") && !rule.textExists("Press Back again to exit"))
    }
}
