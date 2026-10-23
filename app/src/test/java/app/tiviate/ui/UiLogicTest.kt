package app.tiviate.ui

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.components.KeyAction
import app.tiviate.ui.components.LongPressDetector
import app.tiviate.ui.components.logoCandidates
import app.tiviate.ui.components.logosToFetch
import app.tiviate.ui.components.monogramInitials
import app.tiviate.ui.player.optionOrder
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
    fun focusedItemsKeepRoomAroundThem() {
        val f: (Float, Float, Float) -> Float = { o, s, c -> app.tiviate.ui.theme.comfortScroll(o, s, c) }
        assertEquals("inside the comfortable zone: no scroll", 0f, f(400f, 60f, 1000f), 0.01f)
        assertEquals("near the bottom: scroll down until a fifth is clear", 60f, f(800f, 60f, 1000f), 0.01f)
        assertEquals("near the top: scroll back up (the heading reappears)", -150f, f(50f, 60f, 1000f), 0.01f)
        assertEquals("taller than the zone: centred", 0f, f(100f, 800f, 1000f), 0.01f)
    }

    @Test
    fun minutesLeftRoundsUp() {
        val now = 1_000_000_000L
        assertEquals(34, app.tiviate.ui.components.minutesLeft(now + 33 * 60_000 + 1, now))
        assertEquals(1, app.tiviate.ui.components.minutesLeft(now + 5_000, now))
        assertEquals(0, app.tiviate.ui.components.minutesLeft(now - 5_000, now))
    }

    @Test
    fun backToLiveSitsRightAfterPause() {
        val order = listOf("option_guide", "option_playpause", "option_audio")
        val tags = listOf("option_audio", "option_live", "option_unknown", "option_playpause", "option_guide")
        assertEquals(
            listOf("option_guide", "option_playpause", "option_live", "option_audio", "option_unknown"),
            tags.sortedBy { optionOrder(order, it) },
        )
    }

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
    fun onlyLogosNotSavedYetAreFetched() {
        val channels = listOf(
            channel("https://a.png", null), channel("https://b.png", null),
            channel("https://a.png", null), // same logo twice: fetched once
            channel(null, null), // no logo
        )
        assertEquals(listOf("https://b.png"), logosToFetch(channels) { it == "https://a.png" })
        assertEquals(emptyList<String>(), logosToFetch(channels) { true }) // everything saved: nothing to do
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
