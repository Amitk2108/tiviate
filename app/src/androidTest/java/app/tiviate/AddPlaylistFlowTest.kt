package app.tiviate

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddPlaylistFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var fixtures: FixtureServer

    @Before
    fun setUp() {
        fixtures = FixtureServer().start()
    }

    @After
    fun tearDown() = fixtures.shutdown()

    @Test
    fun addM3uUrlThenAutoPlays() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.waitForIdle()
        Remote.ok() // the first card ("M3U link") has focus when the screen opens
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("field_url") }
        rule.onNodeWithTag("field_url").performTextInput(fixtures.url("/list.m3u"))
        rule.onNodeWithTag("save_playlist").activate()

        rule.waitUntil(30_000) { rule.onAllNodesWithTagExists("player_screen") }
        val graph = (rule.activity.application as TiviateApp).graph
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying }
        rule.waitUntil(5_000) { graph.player.current.value?.name == fixtures.channelNames.first() }
    }
}
