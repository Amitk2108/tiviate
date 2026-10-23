package app.tiviate.data

import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
