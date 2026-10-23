package app.tiviate.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var db: TiviateDatabase
    private var playlistId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TiviateDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlistId = db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = "http://h/p.m3u"))
    }

    @After
    fun tearDown() = db.close()

    private fun channel(i: Int, group: String?, key: String = "k$i", stamp: Long = 1) = ChannelEntity(
        playlistId = playlistId, stableKey = key, number = i, name = "Channel $i", groupName = group,
        logoUrl = null, streamUrl = "http://h/$i?token=$stamp", tvgId = null, tvgName = null,
        userAgent = null, referrer = null, epgRef = "1:c$i", logoFallback = null, catchupDays = null,
        sortIndex = i, syncStamp = stamp,
    )

    @Test
    fun categoriesCountPerGroupInPlaylistOrder() = runTest {
        db.channels().insertAll(listOf(channel(1, "News"), channel(2, "Sports"), channel(3, "News"), channel(4, null)))
        val cats = db.channels().categories(playlistId).first()
        assertEquals(listOf(CategoryRow("News", 2), CategoryRow("Sports", 1), CategoryRow(null, 1)), cats)
    }

    @Test
    fun neighborWalksGroupInSortOrder() = runTest {
        db.channels().insertAll(listOf(channel(1, "A"), channel(2, "B"), channel(3, "A"), channel(4, "A")))
        assertEquals(3, db.channels().next(playlistId, "A", 1)?.number)
        assertEquals(1, db.channels().previous(playlistId, "A", 3)?.number)
        assertNull(db.channels().next(playlistId, "A", 4))
        assertEquals(1, db.channels().first(playlistId, "A")?.number)
        assertEquals(4, db.channels().last(playlistId, "A")?.number)
        assertEquals(2, db.channels().next(playlistId, null, 1)?.number) // null group = All
    }

    @Test
    fun favoritesToggleAndSurviveChannelReplace() = runTest {
        db.channels().insertAll(listOf(channel(1, "A"), channel(2, "A")))
        db.favorites().toggle(playlistId, "k2", nowMs = 10)
        assertEquals(setOf("k2"), db.favorites().observeKeys(playlistId).first().toSet())

        // Resync: rows replaced with a new stamp and rotated URL tokens, same stable keys.
        db.channels().insertAll(listOf(channel(1, "A", stamp = 2), channel(2, "A", stamp = 2)))
        db.channels().deleteStale(playlistId, keepStamp = 2)
        assertEquals(listOf("Channel 2"), db.favorites().favoriteChannels(playlistId).first().map { it.name })

        db.favorites().toggle(playlistId, "k2", nowMs = 11)
        assertTrue(db.favorites().observeKeys(playlistId).first().isEmpty())
    }

    @Test
    fun historyKeepsLast50MostRecentFirst() = runTest {
        db.channels().insertAll((1..60).map { channel(it, "A") })
        for (i in 1..60) db.history().record(playlistId, "k$i", nowMs = i.toLong(), watchedSec = 5)
        db.history().record(playlistId, "k60", nowMs = 100, watchedSec = 7)
        val recent = db.history().recent(playlistId).first()
        assertEquals(50, recent.size)
        assertEquals("Channel 60", recent.first().name)
        assertEquals("Channel 11", recent.last().name)
        assertEquals(12, db.history().entry(playlistId, "k60")?.totalWatchSec)
        assertEquals("k60", db.history().lastWatched(playlistId)?.stableKey)
    }

    @Test
    fun programmesForChunksOver900Refs() = runTest {
        val sourceId = db.epg().insertSource(EpgSourceEntity(url = "http://h/e.xml", name = "E", origin = EpgOrigin.MANUAL))
        val rows = (1..2000).map { ProgrammeEntity(sourceId = sourceId, epgRef = "$sourceId:c$it", startMs = 0, stopMs = 1_000, title = "T$it", generation = 0) }
        db.epg().insertProgrammes(rows)
        val refs = (1..2000).map { "$sourceId:c$it" }
        val found = chunkedIn(refs) { chunk -> db.epg().programmesFor(chunk, fromMs = 0, toMs = 2_000) }
        assertEquals(2000, found.size)
    }

    @Test
    fun swapGenerationHidesThenDeletesOld() = runTest {
        val src = db.epg().insertSource(EpgSourceEntity(url = "http://h/e.xml", name = "E", origin = EpgOrigin.MANUAL))
        db.epg().insertProgrammes(listOf(ProgrammeEntity(sourceId = src, epgRef = "$src:a", startMs = 0, stopMs = 10, title = "Old", generation = 0)))
        db.epg().insertProgrammes(listOf(ProgrammeEntity(sourceId = src, epgRef = "$src:a", startMs = 0, stopMs = 10, title = "New", generation = 1)))
        assertEquals(listOf("Old"), db.epg().programmesFor(listOf("$src:a"), 0, 10).map { it.title })
        db.epg().swapGeneration(src, newGeneration = 1)
        assertEquals(listOf("New"), db.epg().programmesFor(listOf("$src:a"), 0, 10).map { it.title })
        assertEquals(1, db.epg().countProgrammes(src))
    }

    @Test
    fun channelFtsSearchFindsPrefix() = runTest {
        db.channels().insertAll(listOf(channel(1, "A").copy(name = "Star Sports 1"), channel(2, "A").copy(name = "Colors HD")))
        assertEquals(listOf("Star Sports 1"), db.channels().search(playlistId, "spo*").map { it.name })
    }

    @Test
    fun byNumberFindsChannel() = runTest {
        db.channels().insertAll(listOf(channel(7, "A")))
        assertEquals("Channel 7", db.channels().byNumber(playlistId, 7)?.name)
    }

    @Test
    fun recentKeepsTheChosenNumberOfChannels() = runTest {
        val pid = db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = "u"))
        db.channels().insertAll((1..5).map { channel(it, "A").copy(playlistId = pid) })
        for (i in 1..5) db.history().record(pid, "k$i", nowMs = i * 1_000L, watchedSec = 5, keep = 3)
        val recent = db.history().recent(pid).first()
        assertEquals(3, recent.size)
    }
}
