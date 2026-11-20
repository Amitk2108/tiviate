package app.tiviate

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.Parental
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Parental control with the remote: a locked channel asks for the PIN before it plays. */
@RunWith(AndroidJUnit4::class)
class ParentalControlTest {

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
            // Beacon Sports (channel 2) is locked with PIN 2468; the app starts on Aurora News.
            val beacon = graph.db.channels().allInPlaylist(id).first { it.name == "Beacon Sports" }
            graph.settings.update {
                it.copy(
                    activePlaylistId = id, startFullscreen = true,
                    pinHash = Parental.hash("2468"), lockedChannels = setOf(Parental.channelKey(beacon)),
                )
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        runBlocking { graph.settings.update { it.copy(pinHash = null, lockedChannels = emptySet(), lockedGroups = emptySet(), lockSettings = false) } }
        scenario.close()
        fixtures.shutdown()
    }

    @Test
    fun aLockedChannelPlaysOnlyWithTheRightPin() {
        assertEquals("Aurora News", graph.player.current.value?.name)
        Remote.press(KeyEvent.KEYCODE_CHANNEL_UP) // to Beacon Sports, which is locked
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("pin_dialog") }
        assertEquals("nothing changes before the PIN", "Aurora News", graph.player.current.value?.name)

        Remote.press(KeyEvent.KEYCODE_1, 4) // wrong
        rule.waitUntil(2_000) { rule.textExists("Wrong PIN") }
        assertEquals("Aurora News", graph.player.current.value?.name)

        listOf(KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_8).forEach { Remote.press(it) }
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("pin_dialog") && graph.player.current.value?.name == "Beacon Sports" }

        // Unlocked until the app leaves the screen: back and forth needs no PIN.
        Remote.press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        rule.waitUntil(5_000) { graph.player.current.value?.name == "Aurora News" }
        Remote.press(KeyEvent.KEYCODE_CHANNEL_UP)
        rule.waitUntil(5_000) { graph.player.current.value?.name == "Beacon Sports" }
        assert(!rule.onAllNodesWithTagExists("pin_dialog"))
    }

    @Test
    fun backCancelsThePinAndKeepsTheChannel() {
        Remote.press(KeyEvent.KEYCODE_CHANNEL_UP)
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("pin_dialog") }
        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("pin_dialog") }
        assertEquals("Aurora News", graph.player.current.value?.name)
    }

    @Test
    fun lockedSettingsAskForThePin() {
        runBlocking { graph.settings.update { it.copy(lockSettings = true) } }
        // Guide → Left, Left (side menu) → Settings, as a viewer would.
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_rail") }
        rule.waitForIdle()
        rule.downToRailSettings()
        Remote.ok()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("pin_dialog") }
        listOf(KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_8).forEach { Remote.press(it) }
        rule.waitUntil(5_000) { !rule.onAllNodesWithTagExists("pin_dialog") && rule.onAllNodesWithTagExists("settings_screen") }
    }
}
