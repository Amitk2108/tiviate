package app.tiviate

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        // A new playlist lands on the TV guide; OK plays the focused (first) channel in the preview.
        rule.waitUntil(30_000) { rule.onAllNodesWithTagExists("guide_screen") }
        val graph = (rule.activity.application as TiviateApp).graph
        rule.waitUntil(10_000) { rule.textExists(fixtures.channelNames.first()) }
        rule.waitForIdle()
        Remote.ok()
        rule.waitUntil(20_000) { graph.player.engine.state.value.isPlaying }
        rule.waitUntil(5_000) { graph.player.current.value?.name == fixtures.channelNames.first() }
    }

    /** "Easier with your phone": the phone sends the login with the TV's code; the playlist is added and loads. */
    @Test
    fun aPlaylistSentFromThePhoneLoads() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("welcome_screen") }
        rule.waitForIdle()
        Remote.right()
        Remote.ok() // "Xtream Codes"
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("phone_code") && rule.onAllNodesWithTagExists("phone_qr") }
        // The QR code opens the page with the code filled in.
        val link = rule.textOf("phone_address") + "/?code=" + rule.textOf("phone_code")
        rule.onNodeWithTag("phone_qr").assert(androidx.compose.ui.test.hasContentDescription(link))
        val status = rule.sendFromPhone("type" to "xtream", "server" to fixtures.url("/").trimEnd('/'), "username" to "u", "password" to "p", "name" to "From the phone")
        assert(status == 200) { "status $status" }
        rule.waitUntil(30_000) { rule.onAllNodesWithTagExists("guide_screen") }
        val graph = (rule.activity.application as TiviateApp).graph
        assert(runBlocking { graph.playlists.playlists.first() }.any { it.name == "From the phone" })
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
        Remote.down(6) // server → username → password → name → User-Agent → HLS → (buttons)
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
