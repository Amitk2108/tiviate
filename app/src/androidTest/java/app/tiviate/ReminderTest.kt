package app.tiviate

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.tiviate.data.Reminders
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Remind me" from the guide's menu, and the reminder coming up while Tiviate is on screen. */
@RunWith(AndroidJUnit4::class)
class ReminderTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = ApplicationProvider.getApplicationContext<TiviateApp>()
    private val graph get() = context.graph

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
        runBlocking {
            val id = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, startFullscreen = true, reminders = emptySet()) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { graph.player.engine.state.value.isPlaying }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWorkByTag(app.tiviate.data.ReminderWorker.TAG)
        runBlocking { graph.settings.update { it.copy(reminders = emptySet()) } }
        scenario.close()
        fixtures.shutdown()
    }

    @Test
    fun remindMeOnALaterProgrammeThenWatchItWhenItStarts() {
        Remote.right()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("guide_screen") }
        rule.waitUntil(5_000) { rule.textExists("Aurora News Show 3") } // on air
        rule.waitForIdle()
        Remote.right() // Show 4, still to come
        rule.waitForIdle()
        Remote.menu()
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("sheet_remind") }
        rule.waitForIdle()
        rule.onNodeWithTag("sheet_remind", useUnmergedTree = true).activate()

        // Stored, scheduled, and marked in the guide with a bell.
        rule.waitUntil(5_000) { runBlocking { graph.settings.settings.first().reminders.size == 1 } }
        val reminder = Reminders.all(runBlocking { graph.settings.settings.first() }).single()
        assertEquals("Aurora News Show 4", reminder.title)
        // Scheduled right after it is stored.
        rule.waitUntil(5_000) {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(reminder.key).get().singleOrNull()?.state == WorkInfo.State.ENQUEUED
        }
        rule.waitUntil(3_000) { rule.onAllNodesWithContentDescriptionExists("Reminder set") }

        // It comes up while Tiviate is open: Watch plays the channel fullscreen.
        Remote.down() // leave Aurora News so Watch has something to switch
        Remote.ok()
        rule.waitUntil(5_000) { graph.player.current.value?.name == "Beacon Sports" }
        runBlocking { Reminders.fire(context, reminder) }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("reminder_prompt") }
        rule.waitForIdle()
        Remote.ok() // Watch has the focus
        rule.waitUntil(5_000) { graph.player.current.value?.name == "Aurora News" && !rule.onAllNodesWithTagExists("reminder_prompt") }
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("guide_screen") }
        assertEquals("a reminder that came is removed", 0, runBlocking { graph.settings.settings.first().reminders.size })
    }
}
