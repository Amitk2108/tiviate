package app.tiviate

import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** GitHub issue #4: the TV's screen saver started while a channel played. */
@RunWith(AndroidJUnit4::class)
class KeepScreenOnTest {

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

    private fun screenKeptOn(): Boolean {
        var on = false
        scenario.onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    @Test
    fun theScreenStaysOnWhereverAChannelPlaysButNotWhilePaused() {
        assertTrue("fullscreen", screenKeptOn())

        // Search keeps the playing channel in view: the screen stays on there too.
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        Remote.up()
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("search_screen") }
        rule.waitForIdle()
        assertTrue("while searching", screenKeptOn())

        // Paused: the screen saver may start again (as Android TV asks of apps).
        InstrumentationRegistry.getInstrumentation().runOnMainSync { graph.player.engine.togglePause() }
        rule.waitUntil(5_000) { graph.player.engine.state.value.isPaused }
        rule.waitForIdle()
        assertFalse("paused", screenKeptOn())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { graph.player.engine.togglePause() }
        rule.waitUntil(5_000) { !graph.player.engine.state.value.isPaused }
        rule.waitForIdle()
        assertTrue("playing again", screenKeptOn())
    }
}
