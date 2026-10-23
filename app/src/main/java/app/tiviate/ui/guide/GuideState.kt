package app.tiviate.ui.guide

import app.tiviate.data.db.ProgrammeEntity
import java.util.TimeZone

/** The focused cell: a programme, or an empty slot where the guide has nothing scheduled. */
data class FocusBlock(val startMs: Long, val stopMs: Long, val programme: ProgrammeEntity?)

/**
 * Focus and scrolling for the TV guide grid, kept free of Compose so it is unit-tested and cheap:
 * the grid is one focusable surface and this class decides which cell is highlighted.
 *
 * - Up/Down keep the time position ([focusTimeMs]), so moving between channels stays in the same column.
 * - Left/Right step to the adjacent programme, or to a [slotMs] empty slot where nothing is scheduled.
 * - The visible window ([windowMs], from [viewStartMs]) follows the focus and never scrolls before the
 *   stored guide data (now − 1 day) or after it (now + 7 days).
 * - Until the viewer moves in time, the focus follows the clock ([keepUpWith]): a guide left open for an
 *   hour still sits on what is on now. Coming back to the guide returns to now ([backToNow]).
 */
class GuideState(
    nowMs: Long,
    val slotMs: Long = 30 * 60_000L,
    val windowMs: Long = 2 * 60 * 60_000L,
    private val tzOffsetMs: Long = TimeZone.getDefault().getOffset(nowMs).toLong(),
    pastMs: Long = 24 * 60 * 60_000L,
    private val futureMs: Long = 7 * 24 * 60 * 60_000L,
) {
    private var clockMs = nowMs
    private var pastWindowMs = pastMs

    /** "Days of past programmes to keep": how far back the guide scrolls. */
    var pastDays: Int
        get() = (pastWindowMs / (24 * 60 * 60_000L)).toInt()
        set(value) { pastWindowMs = value.coerceIn(1, 14) * 24 * 60 * 60_000L }

    val minStartMs: Long get() = floorToSlot(clockMs - pastWindowMs)
    val maxEndMs: Long get() = clockMs + futureMs

    /** True until the viewer moves left/right in time; the focus then stays where they put it. */
    var followsNow: Boolean = true
        private set

    var row: Int = 0
        private set
    var focusTimeMs: Long = nowMs
        private set
    var viewStartMs: Long = floorToSlot(nowMs)
        private set
    var focused: FocusBlock? = null
        private set

    fun floorToSlot(ms: Long): Long {
        val local = ms + tzOffsetMs
        return local - Math.floorMod(local, slotMs) - tzOffsetMs
    }

    fun focusRow(row: Int, programmesInRow: List<ProgrammeEntity>) {
        this.row = row
        focused = blockAt(programmesInRow, focusTimeMs)
    }

    fun moveRow(delta: Int, rows: Int, programmesInRow: List<ProgrammeEntity>) {
        if (rows <= 0) return
        row = (row + delta).coerceIn(0, rows - 1)
        focused = blockAt(programmesInRow, focusTimeMs)
    }

    /** [direction] > 0 moves right (later), < 0 left (earlier). */
    fun moveTime(direction: Int, programmesInRow: List<ProgrammeEntity>) {
        followsNow = false
        val current = focused ?: blockAt(programmesInRow, focusTimeMs)
        if (direction > 0) {
            val target = current.stopMs
            if (target >= maxEndMs) return
            val block = blockAt(programmesInRow, target)
            focused = block
            focusTimeMs = block.startMs
            if (block.startMs >= viewStartMs + windowMs - slotMs) {
                viewStartMs = clampView(floorToSlot(block.startMs) - slotMs)
            }
        } else if (direction < 0) {
            val target = current.startMs - 1
            if (target < minStartMs) return
            val block = blockAt(programmesInRow, target)
            focused = block
            if (block.stopMs <= viewStartMs) viewStartMs = clampView(floorToSlot(maxOf(block.startMs, minStartMs)))
            focusTimeMs = maxOf(block.startMs, viewStartMs)
        }
    }

    /**
     * Left on what is on now (a programme, or the empty slot around now) opens the groups column.
     * Anywhere later or earlier — empty "No information" slots included — Left moves back towards now.
     */
    fun leftOpensGroups(nowMs: Long): Boolean {
        val block = focused ?: return true
        return block.startMs <= nowMs && nowMs < block.stopMs
    }

    fun jumpHours(hours: Int, programmesInRow: List<ProgrammeEntity>) {
        followsNow = false
        viewStartMs = clampView(viewStartMs + hours * 3_600_000L)
        focusTimeMs = viewStartMs
        focused = blockAt(programmesInRow, focusTimeMs)
    }

    /** The clock moved on: while [followsNow], keep the focus and the window on now. True when anything changed. */
    fun keepUpWith(nowMs: Long, programmesInRow: List<ProgrammeEntity>): Boolean {
        clockMs = nowMs
        if (!followsNow) return false
        focusTimeMs = nowMs
        val start = clampView(floorToSlot(nowMs))
        val f = focused
        val refocus = f == null || nowMs < f.startMs || nowMs >= f.stopMs
        if (refocus) focused = blockAt(programmesInRow, nowMs)
        if (start == viewStartMs && !refocus) return false
        viewStartMs = start
        return true
    }

    /** Back on now: the programme on air, the window at the current half-hour. */
    fun backToNow(nowMs: Long, programmesInRow: List<ProgrammeEntity>) {
        followsNow = true
        focused = null
        keepUpWith(nowMs, programmesInRow)
    }

    private fun clampView(start: Long): Long = start.coerceIn(minStartMs, maxOf(minStartMs, maxEndMs - windowMs))

    /** The stretches of [fromMs, toMs) with nothing scheduled, as the same empty slots the focus lands on. */
    fun emptyBlocks(programmes: List<ProgrammeEntity>, fromMs: Long, toMs: Long): List<FocusBlock> {
        val out = ArrayList<FocusBlock>()
        var t = fromMs
        while (t < toMs) {
            val b = blockAt(programmes, t)
            if (b.programme == null) out += b
            t = maxOf(b.stopMs, t + 1)
        }
        return out
    }

    /** The programme covering [t], or the empty slot around [t] bounded by its neighbours. */
    fun blockAt(programmes: List<ProgrammeEntity>, t: Long): FocusBlock {
        programmes.firstOrNull { it.startMs <= t && t < it.stopMs }?.let { return FocusBlock(it.startMs, it.stopMs, it) }
        val slotStart = floorToSlot(t)
        val prevEnd = programmes.filter { it.stopMs <= t }.maxOfOrNull { it.stopMs } ?: Long.MIN_VALUE
        val nextStart = programmes.filter { it.startMs > t }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
        return FocusBlock(maxOf(slotStart, prevEnd), minOf(slotStart + slotMs, nextStart), null)
    }
}
