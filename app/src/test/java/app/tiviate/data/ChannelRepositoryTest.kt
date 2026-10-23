package app.tiviate.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.ProgrammeEntity
import app.tiviate.data.db.EpgSourceEntity
import app.tiviate.data.db.EpgOrigin
import app.tiviate.data.db.TiviateDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TiviateDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = ChannelRepository(db, clock = { 1_000L })

    @After
    fun tearDown() = db.close()

    private suspend fun seed(): Long {
        val pid = db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = "u"))
        db.channels().insertAll(
            listOf("A" to "News", "B" to "Sports", "C" to "News", "D" to "News").mapIndexed { i, (n, g) ->
                ChannelEntity(
                    playlistId = pid, stableKey = n, number = i + 1, name = n, groupName = g, logoUrl = null, streamUrl = "s$n",
                    tvgId = null, tvgName = null, userAgent = null, referrer = null, epgRef = "1:$n", logoFallback = null,
                    catchupDays = null, sortIndex = i + 1, syncStamp = 1,
                )
            },
        )
        return pid
    }

    @Test
    fun neighborWrapsWithinGroup() = runBlocking {
        val pid = seed()
        val d = db.channels().byKey(pid, "D")!!
        val a = db.channels().byKey(pid, "A")!!
        assertEquals("A", repo.neighbor(pid, Category.Group("News"), d, forward = true)?.name)
        assertEquals("D", repo.neighbor(pid, Category.Group("News"), a, forward = false)?.name)
        assertEquals("B", repo.neighbor(pid, Category.All, a, forward = true)?.name)
    }

    @Test
    fun neighborInFavoritesFollowsFavoriteOrder() = runBlocking {
        val pid = seed()
        db.favorites().toggle(pid, "D", nowMs = 1)
        db.favorites().toggle(pid, "B", nowMs = 2)
        val d = db.channels().byKey(pid, "D")!!
        val b = db.channels().byKey(pid, "B")!!
        assertEquals("B", repo.neighbor(pid, Category.Favorites, d, forward = true)?.name)
        assertEquals("D", repo.neighbor(pid, Category.Favorites, b, forward = true)?.name)
    }

    @Test
    fun neighborInRecentGoesBackInTime() = runBlocking {
        val pid = seed()
        db.history().record(pid, "A", nowMs = 1, watchedSec = 5)
        db.history().record(pid, "C", nowMs = 2, watchedSec = 5)
        val c = db.channels().byKey(pid, "C")!!
        assertEquals("A", repo.neighbor(pid, Category.Recent, c, forward = true)?.name)
    }

    @Test
    fun currentNotInCategoryStartsAtFirst() = runBlocking {
        val pid = seed()
        val b = db.channels().byKey(pid, "B")!!
        assertEquals("A", repo.neighbor(pid, Category.Group("News"), b, forward = true)?.name)
    }

    @Test
    fun nowNextReturnsTwoPerChannel() = runBlocking {
        val pid = seed()
        val src = db.epg().insertSource(EpgSourceEntity(url = "e", name = "E", origin = EpgOrigin.MANUAL))
        db.epg().insertProgrammes(
            listOf(
                ProgrammeEntity(sourceId = src, epgRef = "$src:x", startMs = 0, stopMs = 900, title = "Past", generation = 0),
                ProgrammeEntity(sourceId = src, epgRef = "$src:x", startMs = 900, stopMs = 2_000, title = "Now", generation = 0),
                ProgrammeEntity(sourceId = src, epgRef = "$src:x", startMs = 2_000, stopMs = 3_000, title = "Next", generation = 0),
                ProgrammeEntity(sourceId = src, epgRef = "$src:x", startMs = 3_000, stopMs = 4_000, title = "Later", generation = 0),
            ),
        )
        val nn = repo.nowNext(listOf("$src:x"))
        assertEquals("Now", nn.getValue("$src:x").now?.title)
        assertEquals("Next", nn.getValue("$src:x").next?.title)
    }

    @Test
    fun positionOfCountsWithinCategory() = runBlocking {
        val pid = seed()
        val d = db.channels().byKey(pid, "D")!!
        assertEquals(3, repo.positionOf(pid, Category.All, d))
        assertEquals(2, repo.positionOf(pid, Category.Group("News"), d))
    }
}
