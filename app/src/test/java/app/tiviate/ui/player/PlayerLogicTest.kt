package app.tiviate.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
