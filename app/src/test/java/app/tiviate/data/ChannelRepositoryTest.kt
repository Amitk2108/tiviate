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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun ftsQueryUsesPrefixTokens() {
        assertEquals("star* sp*", ChannelRepository.ftsQuery("Star  Sp!"))
        assertEquals(null, ChannelRepository.ftsQuery("  ** "))
    }

    @Test
    fun searchFindsChannelsAndProgrammesOnNowWithTheirChannel() = runBlocking {
        val pid = seed()
        val src = db.epg().insertSource(EpgSourceEntity(url = "e", name = "E", origin = EpgOrigin.MANUAL))
        // seed() links channel "C" to guide ref "1:C"; use the real source id for the programme.
        db.channels().setEpg(db.channels().byKey(pid, "C")!!.id, "$src:C", null)
        db.epg().insertProgrammes(
            listOf(
                ProgrammeEntity(sourceId = src, epgRef = "$src:C", startMs = 500, stopMs = 2_000, title = "Cricket Live", generation = 0),
                ProgrammeEntity(sourceId = src, epgRef = "$src:C", startMs = 20_000_000, stopMs = 21_000_000, title = "Cricket Tomorrow", generation = 0),
            ),
        )
        val result = repo.search(pid, "cri")
        assertEquals(listOf("Cricket Live"), result.programmes.map { it.programme.title })
        assertEquals("C", result.programmes.single().channel.name)
        assertEquals(emptyList<String>(), result.channels.map { it.name })
        assertEquals(listOf("A"), repo.search(pid, "a").channels.map { it.name })
    }

    @Test
    fun searchListsLaterProgrammesAndOnesStillInTheArchive() = runBlocking {
        val day = 86_400_000L
        val now = 10 * day
        val repoAt = ChannelRepository(db, clock = { now })
        val pid = seed()
        val src = db.epg().insertSource(EpgSourceEntity(url = "e", name = "E", origin = EpgOrigin.MANUAL))
        val c = db.channels().byKey(pid, "C")!!
        // Channel C keeps a 2-day archive.
        db.channels().updateAllBlocking(listOf(c.copy(epgRef = "$src:C", catchupType = "xc", catchupDays = 2)))
        fun hour(start: Long, title: String) = ProgrammeEntity(sourceId = src, epgRef = "$src:C", startMs = start, stopMs = start + 3_600_000, title = title, generation = 0)
        db.epg().insertProgrammes(
            listOf(
                hour(now - 3 * day, "Cricket Old"), // older than the archive
                hour(now - day, "Cricket Yesterday"),
                hour(now + 4 * 3_600_000, "Cricket Tonight"),
                hour(now + 8 * day, "Cricket Next Week"), // more than a week away
            ),
        )
        val result = repoAt.search(pid, "cricket")
        assertEquals(emptyList<String>(), result.programmes.map { it.programme.title })
        assertEquals(listOf("Cricket Tonight"), result.later.map { it.programme.title })
        assertEquals(listOf("Cricket Yesterday"), result.archive.map { it.programme.title })
        // A hidden channel's programmes are left out.
        db.channels().updateAllBlocking(listOf(db.channels().byKey(pid, "C")!!.copy(hidden = true)))
        assertEquals(emptyList<String>(), repoAt.search(pid, "cricket").later.map { it.programme.title })
    }

    @Test
    fun aGuideTimeOffsetMovesItsProgrammesAtOnce() = runBlocking {
        val src = db.epg().insertSource(EpgSourceEntity(url = "e", name = "E", origin = EpgOrigin.MANUAL))
        db.epg().insertProgrammes(listOf(ProgrammeEntity(sourceId = src, epgRef = "$src:x", startMs = 3_600_000, stopMs = 7_200_000, title = "Show", generation = 0)))
        repo.setEpgOffset(src, 60)
        repo.setEpgOffset(src, 30) // half an hour back again: +30 min in all
        val p = db.epg().programmesFor(listOf("$src:x"), 0, Long.MAX_VALUE).single()
        assertEquals(3_600_000 + 1_800_000L, p.startMs)
        assertEquals(7_200_000 + 1_800_000L, p.stopMs)
        assertEquals(30, db.epg().source(src)!!.timeOffsetMin)
    }

    private fun prog(start: Long, stop: Long, title: String) =
        ProgrammeEntity(sourceId = 1, epgRef = "1:x", startMs = start, stopMs = stop, title = title, generation = 0)

    @Test
    fun nowNextGoesStaleWhenTheProgrammeOnAirEnds() {
        val entry = NowNext(prog(900, 2_000, "Now"), prog(2_000, 3_000, "Next"))
        assertFalse(entry.isStaleAt(1_999))
        assertTrue(entry.isStaleAt(2_000))
        val gap = NowNext(null, prog(5_000, 6_000, "Later"))
        assertFalse(gap.isStaleAt(4_999))
        assertTrue("the next programme has started", gap.isStaleAt(5_000))
        assertFalse("nothing known: refreshed when the guide updates instead", NowNext(null, null).isStaleAt(Long.MAX_VALUE))
    }

    @Test
    fun guideVersionChangesWhenARefreshSwapsInNewProgrammes() = runBlocking {
        val src = db.epg().insertSource(EpgSourceEntity(url = "e", name = "E", origin = EpgOrigin.MANUAL))
        val before = repo.guideVersion().first()
        db.epg().swapGeneration(src, 1)
        assertNotEquals(before, repo.guideVersion().first())
    }
}
