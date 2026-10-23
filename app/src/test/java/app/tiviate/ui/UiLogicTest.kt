package app.tiviate.ui

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.components.KeyAction
import app.tiviate.ui.components.LongPressDetector
import app.tiviate.ui.components.logoCandidates
import app.tiviate.ui.components.monogramInitials
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiLogicTest {

    private fun channel(logo: String?, fallback: String?) = ChannelEntity(
        playlistId = 1, stableKey = "k", number = 1, name = "N", groupName = null, logoUrl = logo, streamUrl = "s",
        tvgId = null, tvgName = null, userAgent = null, referrer = null, epgRef = null, logoFallback = fallback,
        catchupDays = null, sortIndex = 1, syncStamp = 1,
    )

    @Test
    fun logoModelFallsBackInOrder() {
        // Plain-http logos are tried over https first: some networks stall plain http to CDNs.
        assertEquals(
            listOf("https://a.png", "http://a.png", "https://b.png"),
            logoCandidates(channel("http://a.png", "https://b.png")),
        )
        assertEquals(listOf("https://b.png", "http://b.png"), logoCandidates(channel("  ", "http://b.png")))
        assertEquals(listOf("https://a.png", "http://a.png"), logoCandidates(channel("http://a.png", "http://a.png")))
        assertEquals(listOf("https://x/logo.svg"), logoCandidates(channel("https://x/logo.svg", null)))
        assertEquals(emptyList<String>(), logoCandidates(channel(null, null)))
    }

    @Test
    fun monogramInitials() {
        assertEquals("BO", monogramInitials("BBC One"))
        assertEquals("5", monogramInitials("5"))
        assertEquals("CO", monogramInitials("Colors"))
        assertEquals("SS", monogramInitials("Star Sports 1 HD"))
        assertEquals("?", monogramInitials("  "))
    }

    private fun down(t: Long, repeat: Int) = KeyEvent(0, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat)
    private fun up(t: Long) = KeyEvent(0, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0)

    @Test
    fun longPressAfterThreshold() {
        val d = LongPressDetector(thresholdMs = 500)
        assertEquals(KeyAction.None, d.onKey(down(0, 0)))
        assertEquals(KeyAction.None, d.onKey(down(250, 1)))
        assertEquals(KeyAction.Long, d.onKey(down(520, 2)))
        assertEquals(KeyAction.None, d.onKey(down(600, 3)))
        assertEquals(KeyAction.None, d.onKey(up(700)))
    }

    @Test
    fun shortPressOnRelease() {
        val d = LongPressDetector(thresholdMs = 500)
        assertEquals(KeyAction.None, d.onKey(down(0, 0)))
        assertEquals(KeyAction.Short, d.onKey(up(120)))
    }
}
