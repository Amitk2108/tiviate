package app.tiviate.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.ChannelSort
import app.tiviate.data.SettingsStore
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.CustomGroupEntity
import app.tiviate.data.db.CustomGroupMemberEntity
import app.tiviate.data.db.EpgOrigin
import app.tiviate.data.db.EpgSourceEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.TiviateDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun newDb() = Room.inMemoryDatabaseBuilder(context, TiviateDatabase::class.java).allowMainThreadQueries().build()
    private fun newSettings() = SettingsStore.forFile(File(context.filesDir, "backup-test-${System.nanoTime()}.preferences_pb"))
    private val source = newDb()
    private val target = newDb()

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    /** What the provider's playlist holds: the same two channels on every load. */
    private fun channels(pid: Long) = listOf("A", "B").mapIndexed { i, k ->
        ChannelEntity(
            playlistId = pid, stableKey = k, number = i + 1, name = "Channel $k", groupName = "News", logoUrl = null,
            streamUrl = "http://h/$k.ts", tvgId = null, tvgName = null, userAgent = null, referrer = null, epgRef = null,
            logoFallback = null, catchupDays = null, sortIndex = i, syncStamp = 1, originalName = "Channel $k",
        )
    }

    @Test
    fun everythingTheViewerSetUpComesBackOnAnEmptyTv() = runBlocking {
        // A TV with a playlist (and its login), favourites, an own group, a hidden and a renamed channel,
        // a manual guide link and some settings.
        val pid = source.playlists().insert(
            PlaylistEntity(name = "My IPTV", type = PlaylistType.XTREAM, url = null, xtreamServer = "http://s", xtreamUser = "u", xtreamPass = "p"),
        )
        source.channels().insertAll(channels(pid))
        val b = source.channels().byKey(pid, "B")!!
        source.channels().setHidden(pid, listOf("A"), true)
        source.channels().setName(b.id, "My B", renamed = true)
        source.channels().setEpgOverride(b.id, "9:b.in", null)
        source.favorites().toggle(pid, "B", nowMs = 5)
        val gid = source.organise().insertCustomGroup(CustomGroupEntity(playlistId = pid, name = "Kids", position = 0))
        source.organise().addMembers(listOf(CustomGroupMemberEntity(gid, "A", 0)))
        source.epg().insertSource(EpgSourceEntity(url = "http://g/guide.xml", name = "Extra guide", origin = EpgOrigin.MANUAL, priority = 0))
        val settings = newSettings()
        settings.update { it.copy(activePlaylistId = pid, channelSort = ChannelSort.NAME, zoomPercent = 120, hiddenOptionCards = setOf("option_stats")) }

        val json = Backup(source, settings, "0.1.0").toJson(nowMs = 42)
        assertEquals("Tiviate", JSONObject(json).getString("app"))

        val restored = newSettings()
        var reloaded = emptyList<Long>()
        Backup(target, restored, "0.1.0").restore(json) { id ->
            reloaded = reloaded + id
            target.channels().insertAll(channels(id)) // what the real sync does: channels come back from the provider
        }

        assertEquals(listOf(pid), reloaded)
        val p = target.playlists().get(pid)!!
        assertEquals("My IPTV" to "p", p.name to p.xtreamPass)
        assertTrue("hidden channel stays hidden", target.channels().byKey(pid, "A")!!.hidden)
        val rb = target.channels().byKey(pid, "B")!!
        assertEquals("My B", rb.name)
        assertEquals("9:b.in", rb.epgOverride)
        assertEquals(setOf("B"), target.favorites().observeKeys(pid).first().toSet())
        assertEquals("Kids", target.organise().customGroups(pid).first().single().name)
        assertEquals(listOf("A"), target.organise().memberKeys(gid))
        assertEquals(listOf("Extra guide"), target.epg().observeSources().first().map { it.name })
        val s = restored.settings.first()
        assertEquals(pid, s.activePlaylistId)
        assertEquals(ChannelSort.NAME, s.channelSort)
        assertEquals(120, s.zoomPercent)
        assertEquals(setOf("option_stats"), s.hiddenOptionCards)
    }

    @Test
    fun otherFilesAreRefused() = runBlocking {
        val r = runCatching { Backup(target, newSettings(), "0.1.0").restore("""{"app":"Other"}""") {} }
        assertTrue(r.isFailure)
    }
}
