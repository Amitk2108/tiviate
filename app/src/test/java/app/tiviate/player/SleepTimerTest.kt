package app.tiviate.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    private var now = 1_000_000L
    private val timer = SleepTimer { now }

    @Test
    fun dueExactlyWhenTheMinutesHavePassed() {
        assertNull("off by default", timer.remainingMs())
        timer.start(30)
        assertEquals(30, timer.minutesLeft())
        now += 30 * 60_000L - 1
        assertFalse(timer.isDue())
        assertEquals(1, timer.minutesLeft())
        now += 1
        assertTrue(timer.isDue())
    }

    @Test
    fun cancelClearsIt() {
        timer.start(15)
        timer.cancel()
        assertNull(timer.remainingMs())
        now += 16 * 60_000L
        assertFalse(timer.isDue())
    }
}
