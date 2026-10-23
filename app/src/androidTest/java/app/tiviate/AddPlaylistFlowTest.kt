package app.tiviate

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performImeAction
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
    fun addM3uUrlOpensTheGuideWithThePreviewPlaying() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.waitForIdle()
        Remote.ok() // the first card ("M3U link") has focus when the screen opens
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("field_url") }
        rule.onNodeWithTag("field_url").activate()
        rule.onNodeWithTag("field_url_input").performTextInput(fixtures.url("/list.m3u"))
        rule.onNodeWithTag("save_playlist").activate()

        // Like other TV players, a new playlist lands on the TV guide with the channel in the preview.
        rule.waitUntil(30_000) { rule.onAllNodesWithTagExists("guide_screen") }
        val graph = (rule.activity.application as TiviateApp).graph
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying }
        rule.waitUntil(5_000) { graph.player.current.value?.name == fixtures.channelNames.first() }
    }

    @Test
    fun dpadMovesBetweenFieldsWithoutTheKeyboardPoppingUp() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.waitForIdle()
        Remote.right()
        Remote.ok() // "Xtream Codes"
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("field_server") }
        rule.waitForIdle()
        rule.onNodeWithTag("field_server").assertIsFocused()
        Remote.down() // must move to the next field, not into an on-screen keyboard
        rule.waitForIdle()
        rule.onNodeWithTag("field_username").assertIsFocused()
        Remote.down()
        rule.waitForIdle()
        rule.onNodeWithTag("field_password").assertIsFocused()
    }

    @Test
    fun downFromTheLastFieldLandsOnAddPlaylist() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.waitForIdle()
        Remote.right()
        Remote.ok() // "Xtream Codes"
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("field_server") }
        rule.waitForIdle()
        Remote.down(4) // server → username → password → name → (buttons)
        rule.waitForIdle()
        rule.onNodeWithTag("save_playlist").assertIsFocused()
    }

    @Test
    fun backFromTheFormReturnsToWelcome() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("field_url") }
        rule.waitForIdle()
        Remote.down() // focus inside the form
        Remote.back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("welcome_screen") }
    }
}
