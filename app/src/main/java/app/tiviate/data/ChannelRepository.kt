package app.tiviate.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import app.tiviate.data.db.CategoryRow
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.ProgrammeEntity
import app.tiviate.data.db.TiviateDatabase
import app.tiviate.data.db.chunkedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the user is browsing/zapping through. Channels without a group appear only under [All]. */
sealed interface Category {
    data object All : Category
    data object Favorites : Category
    data object Recent : Category
    data class Group(val name: String) : Category
}

data class NowNext(val now: ProgrammeEntity?, val next: ProgrammeEntity?)

class ChannelRepository(private val db: TiviateDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    fun groups(playlistId: Long): Flow<List<CategoryRow>> =
        db.channels().categories(playlistId).map { rows -> rows.filter { it.name != null } }

    fun page(playlistId: Long, category: Category): Flow<PagingData<ChannelEntity>> = when (category) {
        Category.All, is Category.Group -> Pager(PagingConfig(pageSize = 60, prefetchDistance = 40, enablePlaceholders = true, maxSize = 600)) {
            db.channels().pageInGroup(playlistId, (category as? Category.Group)?.name)
        }.flow
        Category.Favorites -> db.favorites().favoriteChannels(playlistId).map { PagingData.from(it) }
        Category.Recent -> db.history().recent(playlistId).map { PagingData.from(it) }
    }

    suspend fun first(playlistId: Long, category: Category): ChannelEntity? = when (category) {
        Category.All -> db.channels().first(playlistId, null)
        is Category.Group -> db.channels().first(playlistId, category.name)
        Category.Favorites -> db.favorites().favoriteChannels(playlistId).first().firstOrNull()
        Category.Recent -> db.history().recent(playlistId).first().firstOrNull()
    }

    /** Next/previous channel within [category], wrapping around at the ends. */
    suspend fun neighbor(playlistId: Long, category: Category, current: ChannelEntity, forward: Boolean): ChannelEntity? {
        val dao = db.channels()
        return when (category) {
            Category.All, is Category.Group -> {
                val group = (category as? Category.Group)?.name
                if (group != null && current.groupName != group) return dao.first(playlistId, group)
                val step = if (forward) dao.next(playlistId, group, current.sortIndex) else dao.previous(playlistId, group, current.sortIndex)
                step ?: if (forward) dao.first(playlistId, group) else dao.last(playlistId, group)
            }
            Category.Favorites -> cycle(db.favorites().favoriteChannels(playlistId).first(), current, forward)
            Category.Recent -> cycle(db.history().recent(playlistId).first(), current, forward)
        }
    }

    private fun cycle(list: List<ChannelEntity>, current: ChannelEntity, forward: Boolean): ChannelEntity? {
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.stableKey == current.stableKey }
        if (i < 0) return list.first()
        return list[Math.floorMod(i + if (forward) 1 else -1, list.size)]
    }

    suspend fun byKey(playlistId: Long, key: String) = db.channels().byKey(playlistId, key)
    suspend fun byNumber(playlistId: Long, number: Int) = db.channels().byNumber(playlistId, number)

    fun favoriteKeys(playlistId: Long): Flow<Set<String>> = db.favorites().observeKeys(playlistId).map { it.toSet() }
    suspend fun toggleFavorite(playlistId: Long, key: String): Boolean = db.favorites().toggle(playlistId, key, clock())
    suspend fun recordWatch(playlistId: Long, key: String, watchedSec: Int) = db.history().record(playlistId, key, clock(), watchedSec)
    suspend fun lastWatched(playlistId: Long) = db.history().lastWatched(playlistId)

    /** Current and next programme for each guide reference. */
    suspend fun nowNext(refs: List<String>): Map<String, NowNext> {
        val now = clock()
        val programmes = chunkedIn(refs.distinct()) { db.epg().programmesFor(it, now, now + 12 * 3_600_000L) }
        return programmes.groupBy { it.epgRef }.mapValues { (_, list) ->
            val current = list.firstOrNull { it.startMs <= now && it.stopMs > now }
            val next = list.firstOrNull { it.startMs >= (current?.stopMs ?: now) && it !== current }
            NowNext(current, next)
        }
    }

    /** Programmes for the guide grid window. */
    suspend fun programmes(refs: List<String>, fromMs: Long, toMs: Long): Map<String, List<ProgrammeEntity>> =
        chunkedIn(refs.distinct()) { db.epg().programmesFor(it, fromMs, toMs) }.groupBy { it.epgRef }
}
