package app.tiviate.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import app.tiviate.data.DisplayMode
import org.junit.Test

class NumberEntryTest {

    @Test
    fun digitsAccumulateAndCommitAfterDelay() {
        val e = NumberEntry(commitDelayMs = 1_500)
        e.append(1, nowMs = 0)
        e.append(2, nowMs = 400)
        assertEquals("12", e.text)
        assertNull(e.commitIfDue(nowMs = 1_800))
        assertEquals(12, e.commitIfDue(nowMs = 1_900))
        assertEquals("", e.text)
    }

    @Test
    fun capsAtFourDigitsAndIgnoresLeadingZeroOnly() {
        val e = NumberEntry()
        listOf(1, 2, 3, 4, 5).forEach { e.append(it, 0) }
        assertEquals("1234", e.text)
        val z = NumberEntry()
        z.append(0, 0)
        assertNull(z.commitIfDue(10_000))
    }
}

class DisplayModeMatcherTest {

    private val modes = listOf(
        DisplayModeInfo(1, 1920, 1080, 60f),
        DisplayModeInfo(2, 1920, 1080, 50f),
        DisplayModeInfo(3, 1920, 1080, 23.976f),
        DisplayModeInfo(4, 3840, 2160, 60f),
        DisplayModeInfo(5, 3840, 2160, 50f),
    )

    @Test
    fun picksExactOrIntegerMultipleAtCurrentResolution() {
        val current = modes[0]
        assertEquals(2, DisplayModeMatcher.pick(modes, current, 25f)?.id)
        assertEquals(2, DisplayModeMatcher.pick(modes, current, 50f)?.id)
        assertEquals(3, DisplayModeMatcher.pick(modes, current, 23.976f)?.id)
        assertEquals(1, DisplayModeMatcher.pick(modes, current, 30f)?.id)
        assertEquals(5, DisplayModeMatcher.pick(modes, modes[3], 25f)?.id)
    }

    @Test
    fun noSwitchWhenNothingMatches() {
        assertNull(DisplayModeMatcher.pick(listOf(DisplayModeInfo(1, 1920, 1080, 60f)), DisplayModeInfo(1, 1920, 1080, 60f), 25f))
        assertNull(DisplayModeMatcher.pick(modes, modes[0], 0f))
    }

    @Test
    fun videoFrameFollowsDisplayModeAndZoom() {
        fun f(mode: DisplayMode, vw: Int, vh: Int, zoom: Int = 100) = videoFrame(mode, zoom, 1920, 1080, vw, vh).let { it.width to it.height }
        assertEquals(1920 to 1080, f(DisplayMode.FIT, 1280, 720))
        assertEquals("4:3 video fits with side bars", 1440 to 1080, f(DisplayMode.FIT, 640, 480))
        assertEquals("stretch ignores the video shape", 1920 to 1080, f(DisplayMode.FILL, 640, 480))
        assertEquals("fill screen crops top and bottom", 1920 to 1440, f(DisplayMode.ZOOM, 640, 480))
        assertEquals(1920 to 1080, f(DisplayMode.RATIO_16_9, 640, 480))
        assertEquals(1440 to 1080, f(DisplayMode.RATIO_4_3, 1280, 720))
        assertEquals("zoom enlarges the frame", 2304 to 1296, f(DisplayMode.FIT, 1280, 720, zoom = 120))
        assertEquals("unknown video size fills the screen", 1920 to 1080, f(DisplayMode.FIT, 0, 0))
    }
}
