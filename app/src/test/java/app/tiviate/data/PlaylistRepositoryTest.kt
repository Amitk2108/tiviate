package app.tiviate.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.flow.first
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.sync.SyncHarness
import app.tiviate.data.sync.text
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistRepositoryTest {

    private val h = SyncHarness()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    @After
    fun tearDown() = h.close()

    /** Holds each download open long enough that two overlapping syncs would both be inside it. */
    private fun slow(response: () -> MockResponse): () -> MockResponse = {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            Thread.sleep(300)
            response()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    @Test
    fun overlappingSyncsAndGuideRefreshesRunOneAtATime() = runBlocking {
        h.routes["/list.m3u"] = slow {
            text(
                """#EXTM3U url-tvg="${h.url("/epg.xml")}"
                |#EXTINF:-1 tvg-id="a",A
                |http://h/a.m3u8
                |#EXTINF:-1 tvg-id="b",B
                |http://h/b.m3u8
                |""".trimMargin(),
            )
        }
        h.routes["/epg.xml"] = slow {
            text(
                """<tv><channel id="a"><display-name>A</display-name></channel>
                  |<programme start="20261023093000 +0000" stop="20261023113000 +0000" channel="a"><title>Now</title></programme></tv>
                """.trimMargin(),
            )
        }
        val id = h.db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = h.url("/list.m3u")))
        val repo = PlaylistRepository(h.db, h.http, h.playlistSyncer(), h.epgSyncer(), clock = { h.now })

        coroutineScope {
            launch(Dispatchers.IO) { repo.syncAll() } // the overdue periodic job…
            launch(Dispatchers.IO) { repo.sync(id).collect {} } // …racing the start-up sync
            launch(Dispatchers.IO) { repo.refreshGuide(id) } // …and "Update guide now"
        }

        assertEquals("downloads must never overlap", 1, maxInFlight.get())
        assertEquals(2, h.db.channels().count(id))
        val ref = h.db.channels().allInPlaylist(id).first { it.name == "A" }.epgRef!!
        assertEquals(listOf("Now"), h.db.epg().programmesFor(listOf(ref), 0, Long.MAX_VALUE).map { it.title })
    }

    @Test
    fun scheduledUpdateReloadsOnlyThePlaylistsThatAreDue() = runBlocking {
        val playlistHits = AtomicInteger()
        val guideHits = AtomicInteger()
        h.routes["/list.m3u"] = {
            playlistHits.incrementAndGet()
            text("#EXTM3U url-tvg=\"${h.url("/epg.xml")}\"\n#EXTINF:-1 tvg-id=\"a\",A\nhttp://h/a.m3u8\n")
        }
        h.routes["/epg.xml"] = { guideHits.incrementAndGet(); text("<tv><channel id=\"a\"><display-name>A</display-name></channel></tv>") }
        val day = 24 * 3_600_000L
        val id = h.db.playlists().insert(
            PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = h.url("/list.m3u"), epgUrl = h.url("/epg.xml"), lastSyncAt = h.now - 2 * day),
        )
        val repo = repo()

        repo.scheduledUpdate(PlaylistUpdate.WEEKLY, atStart = false, guideOnStart = true)
        assertEquals("a weekly playlist updated 2 days ago stays", 0, playlistHits.get())
        assertEquals("the guide still updates on its own interval", 1, guideHits.get())
        assertEquals(h.now - 2 * day, h.db.playlists().get(id)!!.lastSyncAt)

        repo.scheduledUpdate(PlaylistUpdate.DAILY, atStart = false, guideOnStart = true)
        assertEquals(1, playlistHits.get())
        assertEquals(1, h.db.channels().count(id))

        assertFalse("updated just now: nothing to do at start", repo.needsStartSync(PlaylistUpdate.ON_START, guideOnStart = true))
    }

    private fun repo() = PlaylistRepository(h.db, h.http, h.playlistSyncer(), h.epgSyncer(), clock = { h.now })

    @Test
    fun editingAPlaylistKeepsItsFavoritesAndUsesTheNewDetails() = runBlocking {
        h.routes["/old.m3u"] = { text("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",A\nhttp://h/a.m3u8?t=old\n") }
        h.routes["/new.m3u"] = { text("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",A\nhttp://h/a.m3u8?t=new\n") }
        val repo = repo()
        val id = repo.addM3uUrl("Old", h.url("/old.m3u"), null, null)
        repo.sync(id).collect {}
        h.db.favorites().toggle(id, h.db.channels().allInPlaylist(id).single().stableKey, 1)

        repo.updateM3uUrl(id, "New name", h.url("/new.m3u"), epgUrl = null, userAgent = "UA/2")
        h.now += 1_000
        repo.sync(id).collect {}

        val p = h.db.playlists().get(id)!!
        assertEquals("New name", p.name)
        assertEquals(h.url("/new.m3u"), p.url)
        assertEquals("UA/2", p.userAgent)
        assertTrue(h.db.channels().allInPlaylist(id).single().streamUrl.endsWith("t=new"))
        assertEquals("favorites survive the edit", 1, h.db.favorites().observeKeys(id).first().size)
    }

    @Test
    fun editingAnXtreamLoginStoresTheNewServerAndPassword() = runBlocking {
        val repo = repo()
        val id = repo.addXtream("X", XtreamCredentials("old.host", "u", "p1"), preferHls = false)
        repo.updateXtream(id, "Home", XtreamCredentials("http://new.host:8080/", " u2 ", "p2"))
        val p = h.db.playlists().get(id)!!
        assertEquals("Home", p.name)
        assertEquals("http://new.host:8080", p.xtreamServer)
        assertEquals("u2", p.xtreamUser)
        assertEquals("p2", p.xtreamPass)
    }

    @Test
    fun xtreamUserAgentAndStreamFormatAreSavedAndEditable() = runBlocking {
        val repo = repo()
        val id = repo.addXtream("X", XtreamCredentials("h", "u", "p"), preferHls = false, userAgent = "Box/1")
        assertEquals("Box/1", h.db.playlists().get(id)!!.userAgent)
        repo.updateXtream(id, "X", XtreamCredentials("h", "u", "p"), preferHls = true, userAgent = " ")
        val p = h.db.playlists().get(id)!!
        assertTrue(p.preferHls)
        assertEquals("blank clears it", null, p.userAgent)
    }

    @Test
    fun guideSourcesCanBeRenamed() = runBlocking {
        val repo = repo()
        val src = repo.addEpgSource("http://epg.example/guide.xml")
        assertEquals("epg.example", h.db.epg().source(src)!!.name)
        repo.renameEpgSource(src, "  My guide ")
        assertEquals("My guide", h.db.epg().source(src)!!.name)
        repo.renameEpgSource(src, "   ")
        assertEquals("an empty name is ignored", "My guide", h.db.epg().source(src)!!.name)
    }
}
