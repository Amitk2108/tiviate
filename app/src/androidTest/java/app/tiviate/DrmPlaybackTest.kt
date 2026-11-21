package app.tiviate

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.SyncProgress
import app.tiviate.player.PlaybackIssue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** DRM from the playlist's #KODIPROP lines: a real ClearKey-encrypted DASH stream, decrypted on the device. */
@RunWith(AndroidJUnit4::class)
class DrmPlaybackTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val graph get() = ApplicationProvider.getApplicationContext<TiviateApp>().graph

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            val id = graph.playlists.addM3uUrl("Protected", fixtures.url("/drm.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        fixtures.shutdown()
    }

    @Test
    fun aClearKeyChannelPlaysWithTheKeysFromThePlaylist() {
        rule.waitUntil(20_000) { graph.player.current.value?.name == "Locked Box" && graph.player.engine.state.value.isPlaying }
        // Decrypted pictures, not just a running clock: the decoder reports the video's size.
        rule.waitUntil(5_000) { graph.player.engine.state.value.videoWidth == 320 }
        assertEquals(null, graph.player.engine.state.value.issue)
    }

    @Test
    fun aWrongKeySaysTheProtectionCouldNotBeUnlocked() {
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
        Remote.press(KeyEvent.KEYCODE_CHANNEL_UP) // "Wrong Key"
        rule.waitUntil(20_000) { graph.player.engine.state.value.issue == PlaybackIssue.DRM_REFUSED }
        rule.waitUntil(3_000) { rule.textExists("This channel's protection couldn't be unlocked") }
    }
}
