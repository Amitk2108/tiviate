package app.tiviate.core.catchup

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {
    private val start = Instant.parse("2026-11-20T09:00:00Z").toEpochMilli() // 1795165200
    private val end = start + 30 * 60_000
    private val now = start + 2 * 3_600_000
    private val s = start / 1000
    private val e = end / 1000
    private val n = now / 1000

    @Test
    fun xtreamUsesTheServersLocalTime() {
        assertEquals(
            "http://host:8080/timeshift/user/p%40ss/30/2026-11-20:10-00/123.ts",
            Catchup.url("xc", null, "http://host:8080/live/user/p%40ss/123.m3u8", start, end, now, ZoneId.of("Europe/Amsterdam")),
        )
        // Links without /live/ work too.
        assertEquals("http://host/timeshift/u/p/30/2026-11-20:09-00/7.ts", Catchup.url("xc", null, "http://host/u/p/7", start, end, now))
        assertNull(Catchup.url("xc", null, "http://host/playlist.m3u8", start, end, now))
    }

    @Test
    fun defaultAndAppendFillTheTemplate() {
        assertEquals(
            "http://arc.example/ch1?start=$s&end=$e&dur=1800&min=30&now=$n",
            Catchup.url("default", "http://arc.example/ch1?start={utc}&end={utcend}&dur={duration}&min={duration:60}&now={lutc}", "http://live/ch1", start, end, now),
        )
        assertEquals(
            "http://live/ch1.m3u8?utc=$s&lutc=$n",
            Catchup.url("append", "?utc=\${start}&lutc=\${timestamp}", "http://live/ch1.m3u8", start, end, now),
        )
        assertEquals(
            "http://arc/2026/11/20/09-00-00/x?o=2",
            Catchup.url(null, "http://arc/{Y}/{m}/{d}/{H}-{M}-{S}/x?o={offset:3600}", "http://live", start, end, now),
        )
        assertEquals("http://arc/2026-11-20 09:00/x", Catchup.url("default", "http://arc/{utc:Y-m-d H:M}/x", "http://live", start, end, now))
    }

    @Test
    fun shiftAndFlussonic() {
        assertEquals("http://live/ch?token=a&utc=$s&lutc=$n", Catchup.url("shift", null, "http://live/ch?token=a", start, end, now))
        assertEquals("http://fs/ch1/archive-$s-1800.m3u8?token=a", Catchup.url("flussonic", null, "http://fs/ch1/index.m3u8?token=a", start, end, now))
        assertEquals("http://fs/ch1/timeshift_abs-$s.ts", Catchup.url("fs", null, "http://fs/ch1/mpegts", start, end, now))
    }

    @Test
    fun onlyPastProgrammesInsideTheArchiveWindow() {
        assertTrue(Catchup.available("xc", null, 3, start, end, now))
        assertFalse("still on air", Catchup.available("xc", null, 3, start, now + 60_000, now))
        assertFalse("older than the archive", Catchup.available("xc", null, 1, start - 2 * 86_400_000L, end - 2 * 86_400_000L, now))
        assertTrue("no days given: a week", Catchup.available("default", "http://a/{utc}", null, start - 6 * 86_400_000L, end - 6 * 86_400_000L, now))
        assertFalse("no catch-up", Catchup.available(null, null, 7, start, end, now))
        assertFalse("type without a template", Catchup.available("default", " ", 7, start, end, now))
    }
}
