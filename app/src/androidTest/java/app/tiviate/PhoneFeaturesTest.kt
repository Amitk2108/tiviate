package app.tiviate

import android.app.UiAutomation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 0.4 features with touch on a phone: reminders from the guide's programme card, and Movies & Series
 * from the bottom bar. Runs on a phone emulator when asked (-Pandroid.testInstrumentationRunnerArguments.phone=true).
 */
@RunWith(AndroidJUnit4::class)
class PhoneFeaturesTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private lateinit var fixtures: FixtureServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context get() = ApplicationProvider.getApplicationContext<TiviateApp>()
    private val graph get() = context.graph

    @Before
    fun setUp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phone") == "true")
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        fixtures = FixtureServer().start()
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork()
        runBlocking { graph.settings.update { it.copy(reminders = emptySet()) } }
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        scenario?.close()
        if (::fixtures.isInitialized) fixtures.shutdown()
    }

    private fun start(xtream: Boolean) {
        runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            val id = if (xtream) {
                graph.playlists.addXtream("Fixture Xtream", XtreamCredentials(fixtures.url("/").trimEnd('/'), "u", "p"), preferHls = true)
            } else {
                graph.playlists.addM3uUrl("My IPTV", fixtures.url("/list.m3u"), null, null)
            }
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id, reminders = emptySet()) }
            if (xtream) {
                while (graph.library.count(id).first() < 2) kotlinx.coroutines.delay(200)
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("phone_home") }
    }

    @Test
    fun aLaterProgrammeGetsAReminderFromItsCard() {
        start(xtream = false)
        rule.onNodeWithTag("tab_guide").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("phone_guide_row_0") }
        rule.waitForIdle()
        // Far right of the first row: a programme still to come.
        rule.onNodeWithTag("phone_guide_row_0").performTouchInput { click(Offset(width * 0.95f, height / 2f)) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("phone_programme_sheet") }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("phone_record") && rule.onAllNodesWithTagExists("phone_remind") }
        rule.onNodeWithTag("phone_remind").performClick()
        rule.waitUntil(5_000) { runBlocking { graph.settings.settings.first().reminders.size } == 1 }
    }

    @Test
    fun aLongPressOpensABottomSheetThatBackClosesWithoutLeavingTheApp() {
        start(xtream = false)
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("phone_channel_1") }
        rule.onNodeWithTag("phone_channel_1").performTouchInput { longClick() }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_sheet_handle") }
        rule.waitForIdle()
        // A bottom sheet: it starts well below the top of the screen.
        val screen = rule.onNodeWithTag("channel_sheet").fetchSemanticsNode().boundsInRoot
        val handle = rule.onNodeWithTag("channel_sheet_handle").fetchSemanticsNode().boundsInRoot
        assertTrue("sheet top ${handle.top} on a screen of ${screen.height}", handle.top > screen.height * 0.1f)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/tiviate-phone/12-sheet.png").close()
        Thread.sleep(600)

        Remote.back() // closes the sheet, not the app
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("channel_sheet") }
        assertTrue(rule.onAllNodesWithTagExists("phone_home"))
        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario!!.state)

        // A tap above the sheet closes it too.
        rule.onNodeWithTag("phone_channel_1").performTouchInput { longClick() }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists("channel_sheet_handle") }
        rule.onNodeWithTag("channel_sheet").performTouchInput { click(Offset(width / 2f, 20f)) }
        rule.waitUntil(3_000) { !rule.onAllNodesWithTagExists("channel_sheet") }
    }

    @Test
    fun moviesAndSeriesFromTheBottomBar() {
        start(xtream = true)
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("tab_library") }
        rule.onNodeWithTag("tab_library").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("library_screen") && rule.textExists("Test Film") }
        rule.onNodeWithTag("library_item_0").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("movie_play") }
        rule.onNodeWithTag("movie_play").performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithTagExists("vod_player") && graph.player.engine.state.value.isPlaying }
    }
}
