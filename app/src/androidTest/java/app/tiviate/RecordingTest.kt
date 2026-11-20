package app.tiviate

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.tiviate.data.SyncProgress
import app.tiviate.data.db.RecordingStatus
import app.tiviate.data.record.Recordings
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Recording with the remote: the channel on screen from the options, a later programme from the guide. */
@RunWith(AndroidJUnit4::class)
class RecordingTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = ApplicationProvider.getApplicationContext<TiviateApp>()
    private val graph get() = context.graph
    private fun recordings() = runBlocking { graph.db.recordings().observeAll().first() }

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            recordings().forEach { Recordings.delete(context, it.id) }
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
        runBlocking { recordings().forEach { Recordings.delete(context, it.id) } }
        scenario.close()
        fixtures.shutdown()
    }

    private fun openRecordings() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("rail_recordings") }
        rule.waitForIdle()
        rule.onNodeWithTag("rail_recordings").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("recordings_screen") }
    }

    @Test
    fun recordTheChannelOnScreenThenWatchAndDeleteIt() {
        // Options → Record.
        Remote.down()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("options_panel") }
        rule.waitForIdle()
        repeat(20) { if (runCatching { rule.onNodeWithTag("option_record").assertIsFocused() }.isFailure) { Remote.right(); rule.waitForIdle() } }
        Remote.ok()

        // The test channel is a 12-second clip: the recording finishes on its own.
        rule.waitUntil(30_000) { recordings().singleOrNull()?.status == RecordingStatus.DONE }
        val made = recordings().single()
        assertEquals("Aurora News", made.channelName)
        val file = File(made.path!!)
        assertTrue("the file is there and not empty", file.length() > 10_000)

        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("options_panel") }
        openRecordings()
        rule.onNodeWithTag("recording_play_0").activate()
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying }
        assertTrue(graph.player.engine.currentUrl.orEmpty().startsWith("file:"))

        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("recordings_screen") }
        rule.onNodeWithTag("recording_delete_0").activate()
        rule.waitUntil(5_000) { recordings().isEmpty() }
        assertTrue("the file goes too", !file.exists())
    }

    @Test
    fun aLaterProgrammeIsScheduledFromTheGuideAndCanBeCalledOff() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitUntil(5_000) { rule.textExists("Aurora News Show 3") }
        rule.waitForIdle()
        Remote.right() // Show 4, still to come
        rule.waitForIdle()
        Remote.menu()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("sheet_record") }
        rule.onNodeWithTag("sheet_record", useUnmergedTree = true).activate()
        rule.waitUntil(5_000) { recordings().singleOrNull()?.status == RecordingStatus.SCHEDULED }
        val r = recordings().single()
        assertEquals("Aurora News Show 4", r.title)
        rule.waitUntil(5_000) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(Recordings.workName(r.id)).get().singleOrNull()?.state == WorkInfo.State.ENQUEUED
        }

        Remote.back()
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("channel_sheet") }
        rule.waitForIdle()
        Remote.left() // back from the later programme to the one on air
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("guide_groups") }
        rule.waitForIdle()
        Remote.left()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("rail_recordings") }
        rule.onNodeWithTag("rail_recordings").activate()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("recording_cancel_0") }
        rule.onNodeWithTag("recording_cancel_0").activate()
        rule.waitUntil(5_000) { recordings().single().status == RecordingStatus.CANCELLED }
        rule.waitUntil(5_000) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(Recordings.workName(r.id)).get().singleOrNull()?.state == WorkInfo.State.CANCELLED
        }
    }
}
