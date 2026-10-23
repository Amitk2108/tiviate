package app.tiviate.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.TiviateDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Sorting, hiding, reordering, renaming and custom groups, as the lists and zapping see them. */
@RunWith(AndroidJUnit4::class)
class OrganiseTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TiviateDatabase::class.java)
        .allowMainThreadQueries().build()
    private val sort = MutableStateFlow(ChannelSort.PLAYLIST)
    private val repo = ChannelRepository(db, clock = { 1_000L }, sortSetting = sort)

    @After
    fun tearDown() = db.close()

    /** Playlist order: A(News, #4) B(Sports, #3) C(News, #2) D(News, #1); D is the newest. */
    private suspend fun seed(prefixes: String? = null): Long {
        val pid = db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = "u", namePrefixes = prefixes))
        db.channels().insertAll(
            listOf("A" to "News", "B" to "Sports", "C" to "News", "D" to "News").mapIndexed { i, (n, g) ->
                ChannelEntity(
                    playlistId = pid, stableKey = n, number = 4 - i, name = if (n == "C") "c lower" else n, groupName = g,
                    logoUrl = null, streamUrl = "s$n", tvgId = null, tvgName = null, userAgent = null, referrer = null,
                    epgRef = null, logoFallback = null, catchupDays = null, sortIndex = i + 1, syncStamp = 1,
                    originalName = "UK: $n", addedAt = (i + 1) * 10L,
                )
            },
        )
        return pid
    }

    private suspend fun keys(pid: Long, category: Category, includeHidden: Boolean = false) =
        repo.list(pid, category, includeHidden).map { it.stableKey }

    @Test
    fun sortSettingChangesTheOrderOfEveryList() = runBlocking {
        val pid = seed()
        assertEquals(listOf("A", "B", "C", "D"), keys(pid, Category.All))
        sort.value = ChannelSort.NAME
        assertEquals(listOf("A", "B", "C", "D"), keys(pid, Category.All)) // "c lower" sorts without case
        sort.value = ChannelSort.NUMBER
        assertEquals(listOf("D", "C", "B", "A"), keys(pid, Category.All))
        sort.value = ChannelSort.NEWEST
        assertEquals(listOf("D", "C", "A"), keys(pid, Category.Group("News")))
        db.history().record(pid, "B", nowMs = 1, watchedSec = 50)
        db.history().record(pid, "C", nowMs = 2, watchedSec = 5)
        sort.value = ChannelSort.MOST_WATCHED
        assertEquals(listOf("B", "C", "A", "D"), keys(pid, Category.All))
        // Zapping follows the same order.
        val b = repo.byKey(pid, "B")!!
        assertEquals("C", repo.neighbor(pid, Category.All, b, forward = true)?.stableKey)
    }

    @Test
    fun hiddenChannelLeavesListsAndZappingButNotTheManager() = runBlocking {
        val pid = seed()
        repo.setHidden(pid, listOf("C"), hidden = true)
        assertEquals(listOf("A", "B", "D"), keys(pid, Category.All))
        assertEquals(listOf("A", "D"), keys(pid, Category.Group("News")))
        assertEquals(listOf("A", "C", "D"), keys(pid, Category.Group("News"), includeHidden = true))
        val a = repo.byKey(pid, "A")!!
        assertEquals("D", repo.neighbor(pid, Category.Group("News"), a, forward = true)?.stableKey)
        repo.showAllChannels(pid)
        assertEquals(listOf("A", "B", "C", "D"), keys(pid, Category.All))
    }

    @Test
    fun hiddenGroupLeavesAllChannelsAndTheGroupList() = runBlocking {
        val pid = seed()
        repo.setGroupHidden(pid, "Sports", hidden = true)
        assertEquals(listOf("A", "C", "D"), keys(pid, Category.All))
        assertEquals(listOf("News"), repo.groups(pid).first().map { it.name })
        val manage = repo.allGroups(pid).first()
        assertTrue(manage.single { it.name == "Sports" }.hidden)
        repo.renameGroup(pid, "News", "My news")
        assertEquals("My news", repo.groups(pid).first().single().shownName)
    }

    @Test
    fun reorderInsideAGroupKeepsOtherChannelsInPlace() = runBlocking {
        val pid = seed()
        sort.value = ChannelSort.MANUAL
        repo.reorder(pid, Category.Group("News"), listOf("D", "A", "C"))
        assertEquals(listOf("D", "A", "C"), keys(pid, Category.Group("News")))
        assertEquals(listOf("D", "B", "A", "C"), keys(pid, Category.All))
    }

    @Test
    fun favoritesKeepTheChosenOrder() = runBlocking {
        val pid = seed()
        listOf("A", "B", "C").forEach { repo.toggleFavorite(pid, it) }
        repo.reorder(pid, Category.Favorites, listOf("C", "A", "B"))
        assertEquals(listOf("C", "A", "B"), keys(pid, Category.Favorites))
    }

    @Test
    fun customGroupHoldsChosenChannelsInOrder() = runBlocking {
        val pid = seed()
        val id = repo.createCustomGroup(pid, "  Mine  ")!!
        assertEquals("Mine", repo.customGroups(pid).first().single().name)
        repo.addToCustomGroup(id, listOf("C", "A"))
        val group = Category.Custom(id, "Mine")
        assertEquals(listOf("C", "A"), keys(pid, group))
        repo.reorder(pid, group, listOf("A", "C"))
        assertEquals(listOf("A", "C"), keys(pid, group))
        repo.removeFromCustomGroup(id, listOf("A"))
        assertEquals(setOf("C"), repo.customGroupKeys(id))
        repo.deleteCustomGroup(id)
        assertTrue(repo.customGroups(pid).first().isEmpty())
        assertTrue(repo.customGroupKeys(id).isEmpty())
    }

    @Test
    fun renameThenRestoreUsesTheNamesEditor() = runBlocking {
        val pid = seed(prefixes = "UK:")
        val a = repo.byKey(pid, "A")!!
        repo.renameChannel(a, "  Mine ")
        assertEquals("Mine", repo.byKey(pid, "A")!!.name)
        assertTrue(repo.byKey(pid, "A")!!.renamed)
        repo.renameChannel(a, "   ") // blank names are ignored
        assertEquals("Mine", repo.byKey(pid, "A")!!.name)
        repo.restoreChannelName(repo.byKey(pid, "A")!!)
        assertEquals("A", repo.byKey(pid, "A")!!.name)
    }
}
