package app.tiviate.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import app.tiviate.core.match.NameEditor
import app.tiviate.data.db.CategoryRow
import app.tiviate.data.db.CustomGroupEntity
import app.tiviate.data.db.CustomGroupMemberEntity
import app.tiviate.data.db.EpgChannelEntity
import app.tiviate.data.db.GroupPrefEntity
import app.tiviate.data.db.GroupRow
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.ProgrammeEntity
import app.tiviate.data.db.TiviateDatabase
import app.tiviate.data.db.chunkedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the user is browsing/zapping through. Channels without a group appear only under [All]. */
sealed interface Category {
    data object All : Category
    data object Favorites : Category
    data object Recent : Category
    data class Group(val name: String) : Category
    /** A group the viewer made. */
    data class Custom(val id: Long, val name: String) : Category
}

data class NowNext(val now: ProgrammeEntity?, val next: ProgrammeEntity?) {
    /** True once the programme on air has ended, or the next one has started. */
    fun isStaleAt(nowMs: Long): Boolean =
        (now != null && now.stopMs <= nowMs) || (now == null && next != null && next.startMs <= nowMs)
}

data class ProgrammeHit(val programme: ProgrammeEntity, val channel: ChannelEntity)

/** A guide channel offered by "Assign guide". */
data class GuideChoice(val ref: String, val name: String, val xmltvId: String, val sourceName: String)
data class SearchResult(val channels: List<ChannelEntity>, val programmes: List<ProgrammeHit>)

class ChannelRepository(
    private val db: TiviateDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The viewer's sort order for channels in a group (Settings → Playlists → Sort channels). */
    private val sortSetting: Flow<ChannelSort> = flowOf(ChannelSort.PLAYLIST),
) {

    private suspend fun sort() = sortSetting.first()

    fun groups(playlistId: Long): Flow<List<CategoryRow>> =
        db.channels().categories(playlistId).map { rows -> rows.filter { it.name != null } }

    fun allGroups(playlistId: Long): Flow<List<GroupRow>> = db.channels().allGroups(playlistId)

    fun customGroups(playlistId: Long): Flow<List<CustomGroupEntity>> = db.organise().customGroups(playlistId)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun page(playlistId: Long, category: Category, includeHidden: Boolean = false): Flow<PagingData<ChannelEntity>> =
        sortSetting.distinctUntilChanged().flatMapLatest { sort ->
            Pager(PagingConfig(pageSize = 60, prefetchDistance = 40, enablePlaceholders = true, maxSize = 600, jumpThreshold = 180)) {
                db.channels().pageRaw(ChannelQuery.select(playlistId, category, sort, includeHidden = includeHidden))
            }.flow
        }

    /** Channels of [category] in list order (ids only), for zapping and positions. */
    private suspend fun orderedIds(playlistId: Long, category: Category): List<Long> =
        db.channels().idsRaw(ChannelQuery.select(playlistId, category, sort(), columns = "c.id"))

    /** All channels of [category] in list order (managers and pickers). */
    suspend fun list(playlistId: Long, category: Category, includeHidden: Boolean = false): List<ChannelEntity> =
        db.channels().listRaw(ChannelQuery.select(playlistId, category, sort(), includeHidden = includeHidden))

    suspend fun first(playlistId: Long, category: Category): ChannelEntity? =
        orderedIds(playlistId, category).firstOrNull()?.let { db.channels().byId(it) }

    /** Next/previous channel within [category], wrapping around at the ends. */
    suspend fun neighbor(playlistId: Long, category: Category, current: ChannelEntity, forward: Boolean): ChannelEntity? {
        val ids = orderedIds(playlistId, category)
        if (ids.isEmpty()) return null
        val i = ids.indexOf(current.id)
        if (i < 0) return db.channels().byId(ids.first())
        return db.channels().byId(ids[Math.floorMod(i + if (forward) 1 else -1, ids.size)])
    }

    /** Index of [channel] inside [category]'s list, used to open the channel list on the playing channel. */
    suspend fun positionOf(playlistId: Long, category: Category, channel: ChannelEntity): Int =
        orderedIds(playlistId, category).indexOf(channel.id).coerceAtLeast(0)

    // ---------------------------------------------------------------- organising

    suspend fun setHidden(playlistId: Long, keys: Collection<String>, hidden: Boolean) =
        chunkedIn(keys.toList()) { db.channels().setHidden(playlistId, it, hidden); emptyList<Unit>() }

    suspend fun showAllChannels(playlistId: Long) = db.channels().showAll(playlistId)

    suspend fun setGroupHidden(playlistId: Long, group: String, hidden: Boolean) {
        val pref = db.organise().groupPref(playlistId, group) ?: GroupPrefEntity(playlistId, group)
        db.organise().putGroupPref(pref.copy(hidden = hidden))
    }

    /** Own name for a group; null or blank restores the provider's name. */
    suspend fun renameGroup(playlistId: Long, group: String, displayName: String?) {
        val pref = db.organise().groupPref(playlistId, group) ?: GroupPrefEntity(playlistId, group)
        db.organise().putGroupPref(pref.copy(displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /** Groups in the viewer's order (names in the new order). */
    suspend fun reorderGroups(playlistId: Long, orderedNames: List<String>) = db.withTransaction {
        orderedNames.forEachIndexed { i, name ->
            val pref = db.organise().groupPref(playlistId, name) ?: GroupPrefEntity(playlistId, name)
            db.organise().putGroupPref(pref.copy(position = i))
        }
    }

    suspend fun renameChannel(channel: ChannelEntity, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        db.channels().setName(channel.id, clean, renamed = true)
    }

    /** Back to the playlist's name (cleaned by the names editor, if set). */
    suspend fun restoreChannelName(channel: ChannelEntity) {
        val p = db.playlists().get(channel.playlistId)
        val original = channel.originalName ?: channel.name
        val name = NameEditor.clean(original, NameEditor.parse(p?.namePrefixes), NameEditor.parse(p?.nameSuffixes))
        db.channels().setName(channel.id, name, renamed = false)
    }

    /**
     * "Reorder channels" inside [category]: [orderedKeys] is the new order of that list. Favorites
     * and custom groups store their own positions; groups and All share the channels' manual order.
     */
    suspend fun reorder(playlistId: Long, category: Category, orderedKeys: List<String>) = db.withTransaction {
        when (category) {
            Category.Favorites -> orderedKeys.forEachIndexed { i, k -> db.favorites().setPosition(playlistId, k, i) }
            is Category.Custom -> orderedKeys.forEachIndexed { i, k -> db.organise().setMemberPosition(category.id, k, i) }
            Category.Recent -> Unit
            Category.All, is Category.Group -> {
                // Reuse the list's current sort keys so channels outside this list keep their places.
                val current = db.channels().listRaw(ChannelQuery.select(playlistId, category, ChannelSort.MANUAL, includeHidden = true))
                val slots = current.map { it.customSort ?: it.sortIndex }.sorted()
                val byKey = current.associateBy { it.stableKey }
                val ordered = orderedKeys.mapNotNull(byKey::get) + current.filter { it.stableKey !in orderedKeys.toSet() }
                ordered.forEachIndexed { i, ch -> db.channels().setCustomSort(ch.id, slots[i]) }
            }
        }
    }

    suspend fun createCustomGroup(playlistId: Long, name: String): Long? {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return null
        val next = db.organise().customGroups(playlistId).first().size
        return db.organise().insertCustomGroup(CustomGroupEntity(playlistId = playlistId, name = clean, position = next))
    }

    suspend fun renameCustomGroup(groupId: Long, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        db.organise().customGroup(groupId)?.let { db.organise().updateCustomGroup(it.copy(name = clean)) }
    }

    suspend fun deleteCustomGroup(groupId: Long) = db.withTransaction {
        db.organise().clearMembers(groupId)
        db.organise().deleteCustomGroup(groupId)
    }

    suspend fun addToCustomGroup(groupId: Long, keys: List<String>) {
        var pos = db.organise().lastMemberPosition(groupId)
        db.organise().addMembers(keys.map { CustomGroupMemberEntity(groupId, it, ++pos) })
    }

    suspend fun removeFromCustomGroup(groupId: Long, keys: List<String>) =
        chunkedIn(keys) { db.organise().removeMembers(groupId, it); emptyList<Unit>() }

    suspend fun customGroupKeys(groupId: Long): Set<String> = db.organise().memberKeys(groupId).toSet()

    /** Guide link chosen by hand; null goes back to automatic matching at the next guide update. */
    suspend fun assignGuide(channel: ChannelEntity, epgRef: String?) {
        val logo = epgRef?.let { ref ->
            val (source, id) = ref.substringBefore(':').toLongOrNull() to ref.substringAfter(':')
            source?.let { db.epg().channels(it).firstOrNull { c -> c.xmltvId == id }?.iconUrl }
        }
        db.channels().setEpgOverride(channel.id, epgRef, logo)
    }

    /** Guide channels to choose from ("Assign guide"), matching [text] in their names or ids. */
    suspend fun guideChannels(playlistId: Long, text: String): List<GuideChoice> {
        val q = text.trim().lowercase()
        return db.epg().sourcesFor(playlistId).flatMap { src ->
            db.epg().channels(src.id).map { GuideChoice("${src.id}:${it.xmltvId}", it.displayNames.split(EpgChannelEntity.NAME_SEPARATOR).first(), it.xmltvId, src.name) }
        }.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.xmltvId.lowercase().contains(q) }.take(200)
    }

    companion object {
        /** "Star  Sp!" → "star* sp*": every typed word as a prefix match. */
        fun ftsQuery(text: String): String? =
            text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }

        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    }

    suspend fun byKey(playlistId: Long, key: String) = db.channels().byKey(playlistId, key)
    fun observe(playlistId: Long, key: String): Flow<ChannelEntity?> = db.channels().observeByKey(playlistId, key)
    suspend fun byNumber(playlistId: Long, number: Int) = db.channels().byNumber(playlistId, number)

    fun favoriteKeys(playlistId: Long): Flow<Set<String>> = db.favorites().observeKeys(playlistId).map { it.toSet() }
    suspend fun toggleFavorite(playlistId: Long, key: String): Boolean = db.favorites().toggle(playlistId, key, clock())
    suspend fun recordWatch(playlistId: Long, key: String, watchedSec: Int, keep: Int = 50) = db.history().record(playlistId, key, clock(), watchedSec, keep)
    suspend fun lastWatched(playlistId: Long) = db.history().lastWatched(playlistId)

    /** Changes whenever a guide source swaps in a refresh (or is added or removed). */
    fun guideVersion(): Flow<Long> = db.epg().observeSources()
        .map { sources -> sources.map { it.id to it.activeGeneration }.hashCode().toLong() }
        .distinctUntilChanged()

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

    /** Channels whose name starts with the typed words, and programmes on now or in the next 3 hours. */
    suspend fun search(playlistId: Long, text: String): SearchResult {
        val fts = ftsQuery(text) ?: return SearchResult(emptyList(), emptyList())
        val channels = db.channels().search(playlistId, fts)
        val now = clock()
        val programmes = db.epg().searchProgrammes(text.trim(), now, now + 3 * 3_600_000L, limit = 60)
        val byRef = chunkedIn(programmes.map { it.epgRef }.distinct()) { db.channels().byEpgRefs(playlistId, it) }
            .groupBy { it.epgRef }
        val hits = programmes.mapNotNull { p -> byRef[p.epgRef]?.firstOrNull()?.let { ProgrammeHit(p, it) } }
        return SearchResult(channels, hits)
    }

    /** Programmes for the guide grid window. */
    suspend fun programmes(refs: List<String>, fromMs: Long, toMs: Long): Map<String, List<ProgrammeEntity>> =
        chunkedIn(refs.distinct()) { db.epg().programmesFor(it, fromMs, toMs) }.groupBy { it.epgRef }
}
