package app.tiviate.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrarySyncerTest {
    private val h = SyncHarness()
    private val api = "/player_api.php?username=u&password=p&action="

    @After
    fun tearDown() = h.close()

    private fun account(): Long = runBlocking {
        h.db.playlists().insert(PlaylistEntity(name = "X", type = PlaylistType.XTREAM, xtreamServer = h.url("/").trimEnd('/'), xtreamUser = "u", xtreamPass = "p"))
    }

    private fun serve(movies: String) {
        h.routes[api + "get_vod_categories"] = { text("""[{"category_id":"2","category_name":"Drama"},{"category_id":"1","category_name":"Action"}]""") }
        h.routes[api + "get_vod_streams"] = { text(movies) }
        h.routes[api + "get_series_categories"] = { text("""[{"category_id":"5","category_name":"Crime"}]""") }
        h.routes[api + "get_series"] = { text("""[{"series_id":9,"name":"Dark","category_id":"5"}]""") }
    }

    @Test
    fun loadsMoviesAndSeriesInTheProvidersOrderAndDropsRemovedOnes() = runBlocking {
        val id = account()
        serve("""[{"stream_id":1,"name":"Fast","category_id":"1","container_extension":"mp4"},{"stream_id":2,"name":"Sad","category_id":"2"}]""")
        assertEquals(3, LibrarySyncer(h.db, h.http, clock = { 100 }).sync(id))
        assertEquals("groups in the provider's order", listOf("Drama", "Action"), h.db.vod().movieCategories(id).first().map { it.name })
        assertEquals(listOf("Crime"), h.db.vod().seriesCategories(id).first().map { it.name })
        assertEquals("Dark", h.db.vod().seriesItem(id, 9)?.name)

        serve("""[{"stream_id":2,"name":"Sad (2024)","category_id":"2"}]""")
        LibrarySyncer(h.db, h.http, clock = { 200 }).sync(id)
        assertEquals(listOf("Drama"), h.db.vod().movieCategories(id).first().map { it.name })
        assertEquals("updated in place", "Sad (2024)", h.db.vod().movie(id, 2)?.name)
    }

    @Test
    fun aFailedLoadKeepsTheLists() = runBlocking {
        val id = account()
        serve("""[{"stream_id":1,"name":"Fast","category_id":"1"}]""")
        LibrarySyncer(h.db, h.http, clock = { 100 }).sync(id)
        h.routes[api + "get_vod_streams"] = { text("busy", code = 503) }
        assertTrue(runCatching { LibrarySyncer(h.db, h.http, clock = { 200 }).sync(id) }.isFailure)
        assertEquals("Fast", h.db.vod().movie(id, 1)?.name)
    }

    @Test
    fun m3uPlaylistsHaveNoLibrary() = runBlocking {
        val id = h.db.playlists().insert(PlaylistEntity(name = "M", type = PlaylistType.M3U_URL, url = "http://x"))
        assertEquals(0, LibrarySyncer(h.db, h.http).sync(id))
    }
}
