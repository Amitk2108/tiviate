package app.tiviate

import android.view.KeyEvent
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** GitHub issue #4: a Flussonic playlist (`catchup="flussonic" catchup-days="3"`), with the remote. */
@RunWith(AndroidJUnit4::class)
class ArchiveTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            val id = graph.playlists.addM3uUrl("Flussonic", fixtures.url("/flussonic.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitUntil(5_000) { rule.textExists("Aurora News Show 3") } // on air
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        scenario.close()
        fixtures.shutdown()
    }

    @Test
    fun aProgrammeThatAiredPlaysFromTheFirstArchiveLinkTheServerKnows() {
        fun focusedOn(title: String) = rule.onAllNodes(hasTestTag("guide_focused") and hasAnyDescendant(hasText(title)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        Remote.press(KeyEvent.KEYCODE_MEDIA_REWIND)
        rule.waitForIdle()
        repeat(4) { if (!focusedOn("Aurora News Show 1") && !focusedOn("Aurora News Show 2")) { Remote.right(); rule.waitForIdle() } }
        assertTrue(focusedOn("Aurora News Show 1") || focusedOn("Aurora News Show 2"))
        Remote.ok()
        rule.waitUntil(5_000) { graph.player.catchup.value != null && !rule.onAllNodesWithTagExists("guide_screen") }
        // The test server refuses the archive- and index- forms, as older Flussonic servers do.
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying && graph.player.engine.currentUrl.orEmpty().contains("/fs/ch1/timeshift_abs-") }
        assertTrue("the token stays on the link", graph.player.engine.currentUrl.orEmpty().endsWith("?token=a"))
    }

    @Test
    fun theGuideReachesAsFarBackAsTheArchiveAlsoWhereItHasNoProgrammes() {
        Remote.press(KeyEvent.KEYCODE_MEDIA_REWIND, 14) // 28 hours back: more than the one day kept by default
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(5_000) { graph.player.catchup.value != null && !rule.onAllNodesWithTagExists("guide_screen") }
        val start = graph.player.catchup.value!!.startMs
        assertTrue("over a day ago", start < System.currentTimeMillis() - 25 * 3_600_000L)
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying }
    }
}
