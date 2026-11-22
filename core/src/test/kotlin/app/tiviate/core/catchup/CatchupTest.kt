package app.tiviate.core.catchup

import app.tiviate.core.m3u.M3uEntry
import app.tiviate.core.m3u.M3uParser
import java.time.Instant
import java.time.ZoneId
import okio.Buffer
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

    private fun best(type: String?, source: String?, live: String, start: Long, end: Long, now: Long, zone: ZoneId = java.time.ZoneOffset.UTC) =
        Catchup.urls(type, source, live, start, end, now, zone).firstOrNull()

    @Test
    fun xtreamUsesTheServersLocalTime() {
        assertEquals(
            "http://host:8080/timeshift/user/p%40ss/30/2026-11-20:10-00/123.ts",
            best("xc", null, "http://host:8080/live/user/p%40ss/123.m3u8", start, end, now, ZoneId.of("Europe/Amsterdam")),
        )
        // Links without /live/ work too.
        assertEquals("http://host/timeshift/u/p/30/2026-11-20:09-00/7.ts", best("xc", null, "http://host/u/p/7", start, end, now))
        assertNull(best("xc", null, "http://host/playlist.m3u8", start, end, now))
    }

    @Test
    fun defaultAndAppendFillTheTemplate() {
        assertEquals(
            "http://arc.example/ch1?start=$s&end=$e&dur=1800&min=30&now=$n",
            best("default", "http://arc.example/ch1?start={utc}&end={utcend}&dur={duration}&min={duration:60}&now={lutc}", "http://live/ch1", start, end, now),
        )
        assertEquals(
            "http://live/ch1.m3u8?utc=$s&lutc=$n",
            best("append", "?utc=\${start}&lutc=\${timestamp}", "http://live/ch1.m3u8", start, end, now),
        )
        assertEquals(
            "http://arc/2026/11/20/09-00-00/x?o=2",
            best(null, "http://arc/{Y}/{m}/{d}/{H}-{M}-{S}/x?o={offset:3600}", "http://live", start, end, now),
        )
        assertEquals("http://arc/2026-11-20 09:00/x", best("default", "http://arc/{utc:Y-m-d H:M}/x", "http://live", start, end, now))
    }

    @Test
    fun shiftAndFlussonic() {
        assertEquals("http://live/ch?token=a&utc=$s&lutc=$n", best("shift", null, "http://live/ch?token=a", start, end, now))
        assertEquals("http://fs/ch1/archive-$s-1800.m3u8?token=a", best("flussonic", null, "http://fs/ch1/index.m3u8?token=a", start, end, now))
        assertEquals("http://fs/ch1/timeshift_abs-$s.ts", best("fs", null, "http://fs/ch1/mpegts", start, end, now))
    }

    /** GitHub issue #4: this exact line showed no archive. */
    @Test
    fun theFlussonicLineOfIssue4HasAnArchiveWhateverItsLinkLooksLike() {
        for (link in listOf("http://fs.example:8080/xxxx/index.m3u8?token=a", "http://fs.example/xxxx/mpegts", "http://fs.example/xxxx/live?token=a", "http://fs.example/xxxx", "http://fs.example/xxxx/")) {
            val entries = ArrayList<M3uEntry>()
            M3uParser.parse(Buffer().writeUtf8("#EXTM3U\n#EXTINF:0  catchup=\"flussonic\" catchup-days=\"3\",XXXX\n$link\n")) { entries += it }
            val e = entries.single()
            assertEquals("flussonic" to 3, e.catchup to e.catchupDays)
            assertTrue(link, Catchup.available(e.catchup, e.catchupSource, e.catchupDays, start, end, now))
            assertTrue("no archive link for $link", Catchup.urls(e.catchup, e.catchupSource, e.url, start, end, now).isNotEmpty())
        }
    }

    @Test
    fun flussonicOffersEveryLinkFormItsServersUseBestFirst() {
        fun urls(live: String, type: String = "flussonic") = Catchup.urls(type, null, live, start, end, now)
        assertEquals(
            listOf("archive-$s-1800.m3u8", "index-$s-1800.m3u8", "timeshift_abs-$s.m3u8", "timeshift_rel-7200.m3u8").map { "http://fs/ch/$it?t=1" },
            urls("http://fs/ch/index.m3u8?t=1"),
        )
        // One-track links keep their track.
        assertEquals(listOf("mono-$s-1800.m3u8", "archive-$s-1800.m3u8", "mono-timeshift_rel-7200.m3u8").map { "http://fs/ch/$it" }, urls("http://fs/ch/mono.m3u8"))
        assertEquals(listOf("timeshift_abs-$s.ts", "archive-$s-1800.ts", "archive-$s-1800.m3u8").map { "http://fs/ch/$it" }, urls("http://fs/ch/mpegts"))
        // No known file name: the last part is the output name, or the stream itself.
        assertEquals("http://fs/ch/archive-$s-1800.m3u8?t=1", urls("http://fs/ch/live?t=1").first())
        assertTrue("http://fs/ch/live/archive-$s-1800.m3u8?t=1" in urls("http://fs/ch/live?t=1"))
        assertEquals("http://fs/ch/archive-$s-1800.m3u8", urls("http://fs/ch").first())
        assertEquals("MPEG-TS asked for", "http://fs/ch/timeshift_abs-$s.ts", urls("http://fs/ch/live", "flussonic-ts").first())
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
