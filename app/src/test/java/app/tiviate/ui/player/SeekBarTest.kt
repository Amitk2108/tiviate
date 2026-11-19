package app.tiviate.ui.player

import app.tiviate.player.SeekInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekBarTest {

    @Test
    fun filmsShowPlayedAndTotal() {
        assertEquals("1:05" to "1:42:03", seekLabels(SeekInfo(positionMs = 65_400, durationMs = 6_123_000, live = false)))
        assertEquals("0:00" to "0:12", seekLabels(SeekInfo(0, 12_000, false)))
    }

    @Test
    fun aDragShowsWhereItWillLand() {
        assertEquals("1:00:00" to "2:00:00", seekLabels(SeekInfo(5_000, 7_200_000, false), draggedMs = 3_600_000))
    }

    @Test
    fun liveShowsHowFarBehind() {
        assertEquals("−5:00" to "Live", seekLabels(SeekInfo(positionMs = 1_500_000, durationMs = 1_800_000, live = true)))
        assertEquals("at the live edge nothing is shown", "" to "Live", seekLabels(SeekInfo(1_795_000, 1_800_000, true)))
    }
}
