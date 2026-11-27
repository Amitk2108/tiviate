package app.tiviate.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.GroupPrefEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Asked by a viewer with 50,000 channels: "Don't load hidden groups" leaves them out of the playlist (and its guide). */
@RunWith(AndroidJUnit4::class)
class HiddenGroupsSyncTest {

    private val h = SyncHarness()

    @After
    fun tearDown() = h.close()

    private fun syncer(skip: Boolean) = PlaylistSyncer(h.db, h.http, openLocal = { error("unused") }, clock = { h.now }, skipHiddenGroups = { skip })

    private suspend fun playlistHidingSports(skip: Boolean): Long {
        h.routes["/list.m3u"] = {
            text("#EXTM3U\n#EXTINF:-1 group-title=\"News\",One\nhttp://h/1\n#EXTINF:-1 group-title=\"Sports\",Two\nhttp://h/2\n#EXTINF:-1 group-title=\"Sports\",Three\nhttp://h/3\n")
        }
        val id = h.db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = h.url("/list.m3u")))
        syncer(skip).sync(id)
        h.db.organise().putGroupPref(GroupPrefEntity(id, "Sports").copy(hidden = true))
        h.now += 1_000
        syncer(skip).sync(id)
        return id
    }

    @Test
    fun hiddenGroupsAreLeftOutButStayInManageGroups() = runBlocking {
        val id = playlistHidingSports(skip = true)
        assertEquals(listOf("One"), h.db.channels().allInPlaylist(id).map { it.name })
        // Still listed (hidden, nothing loaded), so it can be shown again.
        assertEquals(listOf("News" to false, "Sports" to true), h.db.channels().allGroups(id).first().map { it.name to it.hidden })
    }

    @Test
    fun withTheSettingOffHiddenGroupsStillLoad() = runBlocking {
        val id = playlistHidingSports(skip = false)
        assertEquals(listOf("One", "Two", "Three"), h.db.channels().allInPlaylist(id).map { it.name })
    }
}
