package app.tiviate

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.data.BufferPreset
import app.tiviate.data.SyncProgress
import app.tiviate.data.UpDownMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {

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

    private fun launchWith(xtream: Boolean) {
        runBlocking {
            val id = if (xtream) {
                graph.playlists.addXtream("Fixture Xtream", XtreamCredentials(fixtures.url("/").trimEnd('/'), "u", "p"), preferHls = true)
            } else {
                graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            }
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true) } // these flows start from fullscreen
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
        // Left opens the rail on "All channels"; Settings is the last entry (after 2 groups).
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("categories_rail") }
        Remote.down(5)
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("settings_screen") }
        rule.waitForIdle()
    }

    @Test
    fun changeBufferPresetPersists() {
        launchWith(xtream = false)
        rule.onNodeWithTag("section_player").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("setting_buffer") }
        rule.onNodeWithTag("setting_buffer").activate() // Normal → Stable
        rule.waitUntil(3_000) { runBlocking { graph.settings.settings.first().bufferPreset == BufferPreset.STABLE } }
    }

    @Test
    fun addManualEpgSourceAndRefresh() {
        launchWith(xtream = false)
        rule.onNodeWithTag("section_guide").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("epg_url") }
        rule.onNodeWithTag("epg_url").activate()
        rule.onNodeWithTag("epg_url_input").performTextInput(fixtures.url("/epg.xml?manual=1"))
        rule.onNodeWithTag("epg_add").activate()
        rule.waitUntil(5_000) { runBlocking { graph.db.epg().observeSources().first().size == 2 } }
        rule.waitUntil(5_000) { rule.textExists("manual=1") }
        rule.onNodeWithTag("epg_refresh").activate()
        rule.waitUntil(10_000) { runBlocking { graph.db.epg().observeSources().first().all { it.lastSyncAt != null } } }
    }

    @Test
    fun deletePlaylistReturnsToWelcomeWhenNoneLeft() {
        launchWith(xtream = false)
        rule.onNodeWithTag("section_playlists").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("playlist_delete_0") }
        rule.onNodeWithTag("playlist_delete_0").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("confirm_delete") }
        rule.onNodeWithTag("confirm_delete").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        assertEquals(0, runBlocking { graph.db.playlists().all().size })
    }

    @Test
    fun editPlaylistSavesNewDetailsAndReloads() {
        launchWith(xtream = true)
        rule.onNodeWithTag("section_playlists").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("playlist_edit_0") }
        rule.onNodeWithTag("playlist_edit_0").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("add_playlist_screen") }
        rule.waitUntil(3_000) { rule.textExists("Fixture Xtream") } // the saved details are filled in
        rule.onNodeWithTag("field_name").activate()
        rule.onNodeWithTag("field_name_input").performTextClearance()
        rule.onNodeWithTag("field_name_input").performTextInput("Home IPTV")
        rule.onNodeWithTag("save_playlist").activate()
        rule.waitUntil(10_000) { runBlocking { graph.db.playlists().all().single().name == "Home IPTV" } }
        val p = runBlocking { graph.db.playlists().all().single() }
        assertEquals("login is kept unless changed", "p", p.xtreamPass)
        rule.waitUntil(15_000) { runBlocking { graph.db.playlists().all().single().lastSyncAt != null } }
    }

    @Test
    fun remoteControlModeSwitchesUpDown() {
        launchWith(xtream = false)
        rule.onNodeWithTag("section_remote").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("setting_updown") }
        rule.onNodeWithTag("setting_updown").activate()
        rule.waitUntil(3_000) { runBlocking { graph.settings.settings.first().upDownMode == UpDownMode.ZAP } }
        rule.waitUntil(3_000) { rule.textExists("Up and Down: change channel") }
    }

    @Test
    fun confirmExitCanBeTurnedOff() {
        launchWith(xtream = false)
        rule.onNodeWithTag("section_general").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("setting_confirm_exit") }
        rule.onNodeWithTag("setting_confirm_exit").activate()
        rule.waitUntil(3_000) { runBlocking { !graph.settings.settings.first().confirmExit } }
    }

    @Test
    fun guideKeepsTheChosenNumberOfPastDays() {
        launchWith(xtream = false)
        rule.onNodeWithTag("section_guide").activate()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("setting_epg_past_days") }
        rule.onNodeWithTag("setting_epg_past_days").activate() // 1 → 2 days
        rule.waitUntil(3_000) { runBlocking { graph.settings.settings.first().epgPastDays == 2 } }
    }

    @Test
    fun xtreamAccountStatusShown() {
        launchWith(xtream = true)
        rule.onNodeWithTag("section_playlists").activate()
        rule.waitUntil(8_000) { rule.textExists("Active") && rule.textExists("Expires") }
    }
}
