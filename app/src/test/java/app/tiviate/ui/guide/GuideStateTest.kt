package app.tiviate.ui.guide

import app.tiviate.data.db.ProgrammeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class GuideStateTest {

    private val min = 60_000L
    private val hour = 60 * min
    private val t10 = 1_792_749_600_000L // a 30-minute boundary (UTC)

    private fun p(start: Long, stop: Long, title: String) =
        ProgrammeEntity(sourceId = 1, epgRef = "1:x", startMs = start, stopMs = stop, title = title, generation = 0)

    private fun state(now: Long = t10 + 40 * min) = GuideState(nowMs = now, tzOffsetMs = 0)

    @Test
    fun opensOnNowWithWindowAtCurrentSlot() {
        val s = state()
        assertEquals(t10 + 30 * min, s.viewStartMs)
        assertEquals(t10 + 40 * min, s.focusTimeMs)
    }

    @Test
    fun focusFollowsTheClockWhileTheViewerStaysOnNow() {
        val s = state()
        val row = listOf(p(t10, t10 + hour, "A"), p(t10 + hour, t10 + 2 * hour, "B"))
        s.focusRow(0, row)
        assertEquals("A", s.focused?.programme?.title)
        assertFalse("nothing changes inside the same programme", s.keepUpWith(t10 + 50 * min, row))
        assertTrue(s.keepUpWith(t10 + 70 * min, row))
        assertEquals("B", s.focused?.programme?.title)
        assertEquals(t10 + 70 * min, s.focusTimeMs)
        assertEquals(t10 + hour, s.viewStartMs)
        assertEquals(t10 + 70 * min + 7 * 24 * hour, s.maxEndMs)
    }

    @Test
    fun aViewerWhoMovedInTimeKeepsTheirPlaceUntilBackToNow() {
        val s = state()
        val row = listOf(p(t10, t10 + hour, "A"), p(t10 + hour, t10 + 2 * hour, "B"), p(t10 + 2 * hour, t10 + 3 * hour, "C"))
        s.focusRow(0, row)
        s.moveTime(+1, row)
        s.moveTime(+1, row)
        assertEquals("C", s.focused?.programme?.title)
        assertFalse(s.keepUpWith(t10 + 70 * min, row))
        assertEquals("C", s.focused?.programme?.title)
        s.backToNow(t10 + 70 * min, row)
        assertEquals("B", s.focused?.programme?.title)
        assertEquals(t10 + hour, s.viewStartMs)
        assertTrue(s.keepUpWith(t10 + 2 * hour + min, row))
        assertEquals("C", s.focused?.programme?.title)
    }

    @Test
    fun gapsInTheWindowBecomeEmptySlotBlocks() {
        val s = state()
        val row = listOf(p(t10 + 30 * min, t10 + 90 * min, "A"))
        val empty = s.emptyBlocks(row, t10, t10 + 3 * hour)
        assertEquals(
            listOf(t10 to t10 + 30 * min, t10 + 90 * min to t10 + 2 * hour, t10 + 2 * hour to t10 + 150 * min, t10 + 150 * min to t10 + 3 * hour),
            empty.map { it.startMs to it.stopMs },
        )
        assertTrue(empty.all { it.programme == null })
        assertEquals(6, s.emptyBlocks(emptyList(), t10, t10 + 3 * hour).size)
    }

    @Test
    fun upDownKeepsTime() {
        val s = state()
        val row0 = listOf(p(t10, t10 + hour, "A"))
        val row1 = listOf(p(t10 - 30 * min, t10 + 30 * min, "B"), p(t10 + 30 * min, t10 + 90 * min, "C"))
        s.focusRow(0, row0)
        assertEquals("A", s.focused?.programme?.title)
        s.moveRow(+1, rows = 2, programmesInRow = row1)
        assertEquals(1, s.row)
        assertEquals("C", s.focused?.programme?.title)
        s.moveRow(-1, rows = 2, programmesInRow = row0)
        assertEquals("A", s.focused?.programme?.title)
        assertEquals(t10 + 40 * min, s.focusTimeMs)
        s.moveRow(-1, rows = 2, programmesInRow = row0)
        assertEquals(0, s.row) // clamped at the top
    }

    @Test
    fun rightMovesToNextProgrammeAndScrolls() {
        val s = state(now = t10 + 10 * min)
        val row = listOf(p(t10, t10 + hour, "A"), p(t10 + hour, t10 + 90 * min, "B"), p(t10 + 90 * min, t10 + 3 * hour, "C"))
        s.focusRow(0, row)
        assertEquals("A", s.focused?.programme?.title)
        s.moveTime(+1, row)
        assertEquals("B", s.focused?.programme?.title)
        assertEquals(t10, s.viewStartMs)
        s.moveTime(+1, row)
        assertEquals("C", s.focused?.programme?.title)
        s.moveTime(+1, row) // nothing scheduled after C: an empty slot, and the window follows
        assertNull(s.focused?.programme)
        assertEquals(t10 + 3 * hour, s.focused?.startMs)
        assertEquals(t10 + 3 * hour + 30 * min, s.focused?.stopMs)
        assertEquals(t10 + 3 * hour - 30 * min, s.viewStartMs)
        s.moveTime(-1, row)
        assertEquals("C", s.focused?.programme?.title)
    }

    @Test
    fun gapUsesEmptySlot() {
        val s = state(now = t10 + 5 * min)
        val row = listOf(p(t10, t10 + 30 * min, "A"), p(t10 + 90 * min, t10 + 2 * hour, "B"))
        s.focusRow(0, row)
        s.moveTime(+1, row)
        assertNull(s.focused?.programme)
        assertEquals(t10 + 30 * min, s.focused?.startMs)
        assertEquals(t10 + hour, s.focused?.stopMs)
        s.moveTime(+1, row)
        assertEquals(t10 + hour, s.focused?.startMs)
        s.moveTime(+1, row)
        assertEquals("B", s.focused?.programme?.title)
    }

    @Test
    fun jumpHoursMovesWindow() {
        val s = state(now = t10 + 5 * min)
        s.focusRow(0, emptyList())
        s.jumpHours(2, emptyList())
        assertEquals(t10 + 2 * hour, s.viewStartMs)
        s.jumpHours(-1, emptyList())
        assertEquals(t10 + hour, s.viewStartMs)
    }

    @Test
    fun cannotScrollBeforeWindowStart() {
        val s = state(now = t10 + 5 * min)
        s.focusRow(0, emptyList())
        s.jumpHours(-30, emptyList()) // further back than the one day of stored guide data
        val earliest = s.viewStartMs
        assertEquals(t10 - 24 * hour, earliest)
        s.moveTime(-1, emptyList())
        s.moveTime(-1, emptyList())
        assertEquals(earliest, s.viewStartMs)
    }

    @Test
    fun leftOnTheProgrammeOnAirOpensGroupsElsewhereItMovesLeft() {
        val now = t10 + 40 * min
        val s = state(now)
        val row = listOf(p(t10 - hour, t10, "Past"), p(t10, t10 + hour, "On air"), p(t10 + hour, t10 + 2 * hour, "Later"))
        s.focusRow(0, row)
        assertTrue("on air → groups", s.leftOpensGroups(now))
        s.moveTime(+1, row)
        assertFalse("future → move left", s.leftOpensGroups(now))
        s.moveTime(-1, row)
        s.moveTime(-1, row) // long Left: into the past
        assertEquals("Past", s.focused?.programme?.title)
        assertFalse("past → keep moving", s.leftOpensGroups(now))
        val empty = state(now).apply { focusRow(0, emptyList()) }
        assertTrue("no guide data → groups", empty.leftOpensGroups(now))
        val gap = state(now).apply { focusRow(0, row.take(2)) }
        gap.moveTime(+1, row.take(2)) // onto the empty slot after the programme on air
        assertEquals(null, gap.focused?.programme)
        assertFalse("empty future slot → move left, not the groups", gap.leftOpensGroups(now))
    }

    @Test
    fun pastWindowFollowsTheDaysKept() {
        val now = t10 + 40 * min
        val s = state(now)
        assertEquals(s.floorToSlot(now - 24 * hour), s.minStartMs)
        s.pastDays = 3
        assertEquals(s.floorToSlot(now - 72 * hour), s.minStartMs)
    }
}
