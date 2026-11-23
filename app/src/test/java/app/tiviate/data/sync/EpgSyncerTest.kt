package app.tiviate.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.EpgOrigin
import app.tiviate.data.db.EpgSourceEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgSyncerTest {

    private val h = SyncHarness()

    @After
    fun tearDown() = h.close()

    private fun epg(title: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="news.one"><display-name>News One</display-name><icon src="http://epg/n1.png"/></channel>
          <channel id="9001"><display-name>Sport Two</display-name><icon src="http://epg/s2.png"/></channel>
          <channel id="unused"><display-name>Unused</display-name></channel>
          <programme start="20261023093000 +0000" stop="20261023103000 +0000" channel="news.one"><title>$title A</title><icon src="http://img/p1.jpg"/></programme>
          <programme start="20261023103000 +0000" stop="20261023113000 +0000" channel="news.one"><title>$title B</title></programme>
          <programme start="20261023093000 +0000" stop="20261023110000 +0000" channel="9001"><title>$title S</title></programme>
          <programme start="20261023093000 +0000" stop="20261023110000 +0000" channel="unused"><title>Nope</title></programme>
        </tv>"""

    private suspend fun setUpPlaylist(): Long {
        h.routes["/list.m3u"] = {
            text(
                """#EXTM3U url-tvg="${h.url("/epg.xml.gz")}"
                |#EXTINF:-1 tvg-id="news.one" group-title="News",News One HD
                |http://h/1.m3u8
                |#EXTINF:-1 group-title="Sports",UK: Sport Two FHD
                |http://h/2.m3u8
                |#EXTINF:-1 group-title="Sports",No Guide
                |http://h/3.m3u8
                |""".trimMargin(),
            )
        }
        val id = h.db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = h.url("/list.m3u")))
        h.playlistSyncer().sync(id)
        return id
    }

    private fun guideOf(vararg programmes: Triple<Long, Long, String>): String {
        fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
        return "<tv>" + listOf("news.one", "9001").joinToString("") { id ->
            "<channel id=\"$id\"><display-name>${if (id == "9001") "Sport Two" else "News One"}</display-name></channel>"
        } + listOf("news.one", "9001").joinToString("") { id ->
            programmes.joinToString("") { (a, b, t) -> "<programme start=\"${fmt(a)}\" stop=\"${fmt(b)}\" channel=\"$id\"><title>$t</title></programme>" }
        } + "</tv>"
    }

    /** Free-TV's playlist lists over a hundred guide links: each one is read, and one that fails doesn't stop the rest. */
    @Test
    fun everyGuideLinkInThePlaylistHeaderIsRead() = runBlocking {
        val hour = 3_600_000L
        fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
        fun programme(channel: String, title: String) = "<programme start=\"${fmt(h.now)}\" stop=\"${fmt(h.now + hour)}\" channel=\"$channel\"><title>$title</title></programme>"
        fun channel(id: String, name: String) = "<channel id=\"$id\"><display-name>$name</display-name></channel>"
        h.routes["/a.xml.gz"] = { gzipOctet("<tv>${channel("news.one", "News One")}${programme("news.one", "From A")}</tv>") }
        h.routes["/b.xml.gz"] = {
            gzipOctet("<tv>${channel("news.one", "News One")}${channel("9001", "Sport Two")}${programme("news.one", "Also in B")}${programme("9001", "From B")}</tv>")
        }
        h.routes["/list.m3u"] = {
            text(
                "#EXTM3U x-tvg-url=\"${h.url("/a.xml.gz")}, ${h.url("/missing.xml.gz")}, ${h.url("/b.xml.gz")}\"\n" +
                    "#EXTINF:-1 tvg-id=\"news.one\",News One HD\nhttp://h/1.m3u8\n#EXTINF:-1,UK: Sport Two FHD\nhttp://h/2.m3u8\n",
            )
        }
        val id = h.db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = h.url("/list.m3u")))
        h.playlistSyncer().sync(id)
        h.epgSyncer().syncForPlaylist(id)

        suspend fun titles(name: String): List<String> {
            val ref = h.db.channels().allInPlaylist(id).first { it.name == name }.epgRef ?: return emptyList()
            return h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title }
        }
        assertEquals("once, from the first link that has it", listOf("From A"), titles("News One HD"))
        assertEquals("the third link is read too", listOf("From B"), titles("UK: Sport Two FHD"))
    }

    /** GitHub issue #4: guides that start at "now" made every refresh empty the archive. */
    @Test
    fun aRefreshKeepsProgrammesThatAiredAsFarBackAsTheChannelsArchive() = runBlocking {
        val hour = 3_600_000L
        val day = 24 * hour
        h.now = 1_795_000_000_000L
        h.routes["/epg.xml.gz"] = { gzipOctet(guideOf(
            Triple(h.now - 4 * day, h.now - 4 * day + hour, "Too old"),
            Triple(h.now - 2 * day, h.now - 2 * day + hour, "Two days ago"),
            Triple(h.now - 2 * day + hour, h.now - 2 * day + 2 * hour, "Then"),
            Triple(h.now - 3 * hour, h.now - 2 * hour, "Earlier today"),
            Triple(h.now - hour, h.now + hour, "Just ended"),
            Triple(h.now + hour, h.now + 3 * hour, "Replaced by the new guide"),
        )) }
        val id = setUpPlaylist()
        // News One has a 3-day archive; Sport Two has none.
        val news = h.db.channels().allInPlaylist(id).first { it.name == "News One HD" }
        h.db.channels().updateAllBlocking(listOf(news.copy(catchupType = "flussonic", catchupDays = 3)))
        h.epgSyncer().syncForPlaylist(id)

        // Tomorrow's guide only lists what is on from now.
        h.now += 2 * hour
        h.routes["/epg.xml.gz"] = { gzipOctet(guideOf(Triple(h.now - hour, h.now + hour, "On air, corrected"), Triple(h.now + hour, h.now + 2 * hour, "Next"))) }
        h.epgSyncer().syncForPlaylist(id)

        suspend fun titles(name: String): List<String> {
            val ref = h.db.channels().allInPlaylist(id).first { it.name == name }.epgRef!!
            return h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title }
        }
        assertEquals(listOf("Two days ago", "Then", "Earlier today", "Just ended", "On air, corrected", "Next"), titles("News One HD"))
        assertEquals("no archive: one day, as set", listOf("Earlier today", "Just ended", "On air, corrected", "Next"), titles("UK: Sport Two FHD"))
    }

    @Test
    fun gzEpgMatchesByIdAndNameAndLinksLogoFallback() = runBlocking {
        h.routes["/epg.xml.gz"] = { gzipOctet(epg("First")) }
        val id = setUpPlaylist()
        val result = h.epgSyncer().syncForPlaylist(id)
        assertEquals(3, result.programmes)

        val ch = h.db.channels().allInPlaylist(id).associateBy { it.name }
        val news = ch.getValue("News One HD")
        val sport = ch.getValue("UK: Sport Two FHD")
        assertTrue(news.epgRef!!.endsWith(":news.one"))
        assertTrue(sport.epgRef!!.endsWith(":9001"))
        assertNull(ch.getValue("No Guide").epgRef)
        assertEquals("http://epg/s2.png", sport.logoFallback)

        val progs = h.db.epg().programmesFor(listOf(news.epgRef!!), 0, Long.MAX_VALUE)
        assertEquals(listOf("First A", "First B"), progs.map { it.title })
        assertEquals("http://img/p1.jpg", progs.first().iconUrl)
    }

    @Test
    fun refreshSwapsGenerationWithoutEverShowingEmptyGuide() = runBlocking {
        h.routes["/epg.xml.gz"] = { gzipOctet(epg("First")) }
        val id = setUpPlaylist()
        h.epgSyncer().syncForPlaylist(id)
        val ref = h.db.channels().allInPlaylist(id).first { it.name == "News One HD" }.epgRef!!

        // While the second download is being served, the old guide must still be visible.
        val seenDuringRefresh = mutableListOf<String>()
        val served = CountDownLatch(1)
        h.routes["/epg.xml.gz"] = {
            runBlocking { seenDuringRefresh += h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title } }
            served.countDown()
            gzipOctet(epg("Second"))
        }
        h.now += 60_000
        h.epgSyncer().syncForPlaylist(id)
        assertEquals(listOf("First A", "First B"), seenDuringRefresh)
        assertEquals(listOf("Second A", "Second B"), h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title })
        val sourceId = ref.substringBefore(':').toLong()
        assertEquals(3, h.db.epg().countProgrammes(sourceId))
    }

    @Test
    fun brokenEpgKeepsPreviousGuideAndRecordsError() = runBlocking {
        h.routes["/epg.xml.gz"] = { gzipOctet(epg("First")) }
        val id = setUpPlaylist()
        h.epgSyncer().syncForPlaylist(id)
        h.routes["/epg.xml.gz"] = { text("not found", code = 404) }
        val result = h.epgSyncer().syncForPlaylist(id)
        assertEquals(1, result.failedSources)
        val ref = h.db.channels().allInPlaylist(id).first { it.name == "News One HD" }.epgRef!!
        assertEquals(listOf("First A", "First B"), h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title })
        val source = h.db.epg().autoSource(id)!!
        assertTrue(source.lastError!!.contains("404"))
    }

    @Test
    fun playlistResyncKeepsEpgLinks() = runBlocking {
        h.routes["/epg.xml.gz"] = { gzipOctet(epg("First")) }
        val id = setUpPlaylist()
        h.epgSyncer().syncForPlaylist(id)
        h.now += 1_000
        h.playlistSyncer().sync(id) // channels rows are replaced
        val news = h.db.channels().allInPlaylist(id).first { it.name == "News One HD" }
        assertTrue(news.epgRef!!.endsWith(":news.one"))
    }

    private suspend fun playlistWith(name: String, tvgId: String): Long {
        h.routes["/$name.m3u"] = { text("#EXTM3U\n#EXTINF:-1 tvg-id=\"$tvgId\",$name\nhttp://h/$name.m3u8\n") }
        val id = h.db.playlists().insert(PlaylistEntity(name = name, type = PlaylistType.M3U_URL, url = h.url("/$name.m3u")))
        h.playlistSyncer().sync(id)
        return id
    }

    private suspend fun addManualSource(): Long =
        h.db.epg().insertSource(EpgSourceEntity(url = h.url("/manual.xml"), name = "Manual", origin = EpgOrigin.MANUAL))

    private suspend fun titlesFor(playlistId: Long): List<String> {
        val ref = h.db.channels().allInPlaylist(playlistId).single().epgRef ?: return emptyList()
        return h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title }
    }

    @Test
    fun sharedManualSourceKeepsProgrammesForEveryPlaylist() = runBlocking {
        h.routes["/manual.xml"] = { text(epg("M")) }
        val a = playlistWith("A", "news.one")
        val b = playlistWith("B", "9001")
        addManualSource()

        h.epgSyncer().syncForPlaylist(a)
        h.epgSyncer().syncForPlaylist(b) // must not drop the programmes only playlist A uses

        assertEquals(listOf("M A", "M B"), titlesFor(a))
        assertEquals(listOf("M S"), titlesFor(b))
    }

    @Test
    fun syncAllDownloadsASharedSourceOnce() = runBlocking {
        var downloads = 0
        h.routes["/manual.xml"] = { downloads++; text(epg("M")) }
        val a = playlistWith("A", "news.one")
        val b = playlistWith("B", "9001")
        addManualSource()

        h.epgSyncer().syncPlaylists(listOf(a, b))

        assertEquals(1, downloads)
        assertEquals(listOf("M A", "M B"), titlesFor(a))
        assertEquals(listOf("M S"), titlesFor(b))
    }

    @Test
    fun pastDaysSettingControlsWhatIsKept() = runBlocking {
        val day = 86_400_000L
        fun xmltvTime(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
        val xml = buildString {
            append("""<tv><channel id="news.one"><display-name>News One</display-name></channel>""")
            for ((title, endsAgo) in listOf("Two days ago" to 2 * day, "Four days ago" to 4 * day, "Now" to -day / 24)) {
                val stop = h.now - endsAgo
                append("""<programme start="${xmltvTime(stop - 3_600_000L)}" stop="${xmltvTime(stop)}" channel="news.one"><title>$title</title></programme>""")
            }
            append("</tv>")
        }
        h.routes["/epg.xml.gz"] = { gzipOctet(xml) }
        val id = setUpPlaylist()
        suspend fun ref() = h.db.channels().allInPlaylist(id).first { it.name == "News One HD" }.epgRef!!

        EpgSyncer(h.db, h.http, newPullParser = { android.util.Xml.newPullParser() }, clock = { h.now }, pastDays = { 3 }).syncForPlaylist(id)
        assertEquals(listOf("Two days ago", "Now"), h.db.epg().programmesFor(listOf(ref()), 0, Long.MAX_VALUE).map { it.title })

        EpgSyncer(h.db, h.http, newPullParser = { android.util.Xml.newPullParser() }, clock = { h.now }, pastDays = { 1 }).syncForPlaylist(id)
        assertEquals(listOf("Now"), h.db.epg().programmesFor(listOf(ref()), 0, Long.MAX_VALUE).map { it.title })
    }
}
