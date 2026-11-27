package app.tiviate.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Update suspend fun update(p: PlaylistEntity)
    @Query("DELETE FROM playlist WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM playlist WHERE id = :id") suspend fun get(id: Long): PlaylistEntity?

    /** Marks a finished sync without rewriting the rest of the row (the viewer may have edited it meanwhile). */
    @Query("UPDATE playlist SET lastSyncAt = :stamp, lastError = NULL, detectedEpgUrl = COALESCE(:epgUrl, detectedEpgUrl) WHERE id = :id")
    suspend fun markSynced(id: Long, stamp: Long, epgUrl: String?)

    @Query("UPDATE playlist SET serverTimezone = :zone WHERE id = :id")
    suspend fun setServerTimezone(id: Long, zone: String)

    @Query("UPDATE playlist SET lastError = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String?)
    @Query("SELECT * FROM playlist ORDER BY sortIndex, id") fun observeAll(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlist ORDER BY sortIndex, id") suspend fun all(): List<PlaylistEntity>
}

@Dao
interface ChannelDao {
    /** Upsert by (playlistId, stableKey); the old row id is replaced. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(rows: List<ChannelEntity>)

    @Query(
        """SELECT id, stableKey, epgRef, logoFallback, name, hidden, customSort, addedAt, epgOverride, renamed
           FROM channel WHERE playlistId = :playlistId AND stableKey IN (:keys)""",
    )
    fun storedBlocking(playlistId: Long, keys: List<String>): List<StoredChannelRow>

    @Update fun updateAllBlocking(rows: List<ChannelEntity>)

    @Insert fun insertNewBlocking(rows: List<ChannelEntity>)

    /**
     * Writes one sync batch (≤ 900 rows, the SQLite variable limit). Channels already stored are
     * updated in place: they keep their id (stable list keys) and guide link until the guide relinks,
     * and the search index stays in step (REPLACE's implicit delete skips the FTS trigger).
     */
    @Transaction
    fun upsertBlocking(playlistId: Long, rows: List<ChannelEntity>) {
        val stored = storedBlocking(playlistId, rows.map { it.stableKey }).associateBy { it.stableKey }
        val (known, fresh) = rows.partition { it.stableKey in stored }
        updateAllBlocking(
            known.map { r ->
                stored.getValue(r.stableKey).let { s ->
                    r.copy(
                        id = s.id, epgRef = s.epgRef, logoFallback = s.logoFallback,
                        name = if (s.renamed) s.name else r.name, renamed = s.renamed,
                        hidden = s.hidden, customSort = s.customSort, addedAt = s.addedAt.takeIf { it > 0 } ?: r.addedAt,
                        epgOverride = s.epgOverride,
                    )
                }
            },
        )
        insertNewBlocking(fresh)
    }

    /** How many days back this playlist's archives reach (0: no channel has one; a week when it doesn't say). */
    @Query(
        """SELECT COALESCE(MAX(CASE WHEN catchupType IS NULL AND catchupSource IS NULL THEN 0
           WHEN COALESCE(catchupDays, 0) <= 0 THEN 7 ELSE catchupDays END), 0) FROM channel WHERE playlistId = :playlistId""",
    )
    fun archiveDays(playlistId: Long): Flow<Int>

    @Query("SELECT id, tvgId, tvgName, COALESCE(originalName, name) AS name, epgRef, logoFallback, epgOverride FROM channel WHERE playlistId = :playlistId")
    suspend fun matchRows(playlistId: Long): List<ChannelMatchRow>

    @Query("UPDATE channel SET epgRef = :epgRef, logoFallback = :logoFallback WHERE id = :id")
    fun setEpgBlocking(id: Long, epgRef: String?, logoFallback: String?)

    @Query("DELETE FROM channel WHERE playlistId = :playlistId AND syncStamp != :keepStamp")
    suspend fun deleteStale(playlistId: Long, keepStamp: Long): Int

    /** Guide channels the viewer assigned by hand ("<sourceId>:<xmltvId>") for one guide source. */
    @Query("SELECT DISTINCT epgOverride FROM channel WHERE epgOverride LIKE :sourceId || ':%'")
    suspend fun overridesForSource(sourceId: Long): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM channel WHERE playlistId = :playlistId AND groupName IS NOT NULL)")
    fun hasGroupsBlocking(playlistId: Long): Boolean

    @Query("DELETE FROM channel WHERE playlistId = :playlistId") suspend fun deleteAll(playlistId: Long)

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId") suspend fun count(playlistId: Long): Int

    /** Groups shown in the lists: not hidden, in the viewer's order (then playlist order), with own names. */
    @Query(
        """SELECT c.groupName AS name, COUNT(*) AS count, MAX(gp.displayName) AS label FROM channel c
           LEFT JOIN group_pref gp ON gp.playlistId = c.playlistId AND gp.name = c.groupName
           WHERE c.playlistId = :playlistId AND c.hidden = 0 AND COALESCE(gp.hidden, 0) = 0
           GROUP BY c.groupName ORDER BY COALESCE(MIN(gp.position), 1000000 + MIN(c.sortIndex))""",
    )
    fun categories(playlistId: Long): Flow<List<CategoryRow>>

    /**
     * Every provider group, hidden ones too, for "Manage groups"; also hidden groups whose channels weren't loaded
     * ("Don't load hidden groups"), so they can be shown again.
     */
    @Query(
        """SELECT name, label, count, hidden, position FROM (
             SELECT c.groupName AS name, MAX(gp.displayName) AS label, COUNT(*) AS count, COALESCE(MAX(gp.hidden), 0) AS hidden,
               MIN(gp.position) AS position, COALESCE(MIN(gp.position), 1000000 + MIN(c.sortIndex)) AS ord FROM channel c
             LEFT JOIN group_pref gp ON gp.playlistId = c.playlistId AND gp.name = c.groupName
             WHERE c.playlistId = :playlistId AND c.groupName IS NOT NULL
             GROUP BY c.groupName
             UNION ALL
             SELECT gp.name AS name, gp.displayName AS label, 0 AS count, 1 AS hidden, gp.position AS position,
               COALESCE(gp.position, 2000000) AS ord FROM group_pref gp
             WHERE gp.playlistId = :playlistId AND gp.hidden = 1
               AND NOT EXISTS (SELECT 1 FROM channel c WHERE c.playlistId = gp.playlistId AND c.groupName = gp.name))
           ORDER BY ord""",
    )
    fun allGroups(playlistId: Long): Flow<List<GroupRow>>

    @RawQuery(observedEntities = [ChannelEntity::class, FavoriteEntity::class, HistoryEntity::class, GroupPrefEntity::class, CustomGroupMemberEntity::class])
    fun pageRaw(query: SupportSQLiteQuery): PagingSource<Int, ChannelEntity>

    /**
     * For lists that only read the channel and group tables (All channels and the provider's groups in any
     * order but "most watched"): saving watch history or a favourite doesn't reload them.
     */
    @RawQuery(observedEntities = [ChannelEntity::class, GroupPrefEntity::class])
    fun pageChannelsRaw(query: SupportSQLiteQuery): PagingSource<Int, ChannelEntity>

    @RawQuery suspend fun idsRaw(query: SupportSQLiteQuery): List<Long>

    @RawQuery suspend fun listRaw(query: SupportSQLiteQuery): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id = :id") suspend fun byId(id: Long): ChannelEntity?

    @Query("UPDATE channel SET hidden = :hidden WHERE playlistId = :playlistId AND stableKey IN (:keys)")
    suspend fun setHidden(playlistId: Long, keys: List<String>, hidden: Boolean)

    @Query("UPDATE channel SET hidden = 0 WHERE playlistId = :playlistId")
    suspend fun showAll(playlistId: Long)

    @Query("UPDATE channel SET customSort = :customSort WHERE id = :id") suspend fun setCustomSort(id: Long, customSort: Int?)

    @Query("UPDATE channel SET name = :name, renamed = :renamed WHERE id = :id") suspend fun setName(id: Long, name: String, renamed: Boolean)

    @Query("UPDATE channel SET epgOverride = :ref, epgRef = :ref, logoFallback = :logo WHERE id = :id")
    suspend fun setEpgOverride(id: Long, ref: String?, logo: String?)

    @Query("SELECT id, originalName, name, renamed FROM channel WHERE playlistId = :playlistId")
    suspend fun nameRows(playlistId: Long): List<NameRow>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :playlistId AND (:group IS NULL OR groupName = :group)
           ORDER BY sortIndex""",
    )
    fun pageInGroup(playlistId: Long, group: String?): PagingSource<Int, ChannelEntity>

    @Query(
        """SELECT * FROM channel WHERE playlistId = :playlistId AND (:group IS NULL OR groupName = :group)
           AND sortIndex > :sortIndex ORDER BY sortIndex LIMIT 1""",
    )
    suspend fun next(playlistId: Long, group: String?, sortIndex: Int): ChannelEntity?

    @Query(
        """SELECT * FROM channel WHERE playlistId = :playlistId AND (:group IS NULL OR groupName = :group)
           AND sortIndex < :sortIndex ORDER BY sortIndex DESC LIMIT 1""",
    )
    suspend fun previous(playlistId: Long, group: String?, sortIndex: Int): ChannelEntity?

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND (:group IS NULL OR groupName = :group) ORDER BY sortIndex LIMIT 1")
    suspend fun first(playlistId: Long, group: String?): ChannelEntity?

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND (:group IS NULL OR groupName = :group) ORDER BY sortIndex DESC LIMIT 1")
    suspend fun last(playlistId: Long, group: String?): ChannelEntity?

    @Query(
        """SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId AND (:group IS NULL OR groupName = :group)
           AND sortIndex < :sortIndex""",
    )
    suspend fun positionOf(playlistId: Long, group: String?, sortIndex: Int): Int

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND stableKey = :stableKey")
    suspend fun byKey(playlistId: Long, stableKey: String): ChannelEntity?

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND stableKey = :stableKey")
    fun observeByKey(playlistId: Long, stableKey: String): Flow<ChannelEntity?>

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND number = :number AND hidden = 0 ORDER BY sortIndex LIMIT 1")
    suspend fun byNumber(playlistId: Long, number: Int): ChannelEntity?

    @Query(
        """SELECT channel.* FROM channel JOIN channel_fts ON channel.id = channel_fts.rowid
           WHERE channel_fts MATCH :ftsQuery AND channel.playlistId = :playlistId AND channel.hidden = 0
           ORDER BY channel.sortIndex LIMIT :limit""",
    )
    suspend fun search(playlistId: Long, ftsQuery: String, limit: Int = 50): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId ORDER BY sortIndex")
    suspend fun allInPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("UPDATE channel SET epgRef = :epgRef, logoFallback = :logoFallback WHERE id = :id")
    suspend fun setEpg(id: Long, epgRef: String?, logoFallback: String?)

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND epgRef IN (:refs) ORDER BY sortIndex")
    suspend fun byEpgRefs(playlistId: Long, refs: List<String>): List<ChannelEntity>

    @Query("SELECT DISTINCT epgRef FROM channel WHERE playlistId = :playlistId AND epgRef IS NOT NULL")
    suspend fun epgRefs(playlistId: Long): List<String>
}

@Dao
interface OrganiseDao {
    @Query("SELECT * FROM group_pref WHERE playlistId = :playlistId AND name = :name")
    suspend fun groupPref(playlistId: Long, name: String): GroupPrefEntity?

    @Query("SELECT name FROM group_pref WHERE playlistId = :playlistId AND hidden = 1")
    suspend fun hiddenGroupNames(playlistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putGroupPref(p: GroupPrefEntity)

    @Query("SELECT * FROM custom_group WHERE playlistId = :playlistId ORDER BY position, id")
    fun customGroups(playlistId: Long): Flow<List<CustomGroupEntity>>

    @Insert suspend fun insertCustomGroup(g: CustomGroupEntity): Long
    @Update suspend fun updateCustomGroup(g: CustomGroupEntity)
    @Query("SELECT * FROM custom_group WHERE id = :id") suspend fun customGroup(id: Long): CustomGroupEntity?

    @Query("DELETE FROM custom_group WHERE id = :id") suspend fun deleteCustomGroup(id: Long)
    @Query("DELETE FROM custom_group_member WHERE groupId = :groupId") suspend fun clearMembers(groupId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addMembers(members: List<CustomGroupMemberEntity>)

    @Query("DELETE FROM custom_group_member WHERE groupId = :groupId AND stableKey IN (:keys)")
    suspend fun removeMembers(groupId: Long, keys: List<String>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM custom_group_member WHERE groupId = :groupId")
    suspend fun lastMemberPosition(groupId: Long): Int

    @Query("UPDATE custom_group_member SET position = :position WHERE groupId = :groupId AND stableKey = :key")
    suspend fun setMemberPosition(groupId: Long, key: String, position: Int)

    @Query("SELECT stableKey FROM custom_group_member WHERE groupId = :groupId")
    suspend fun memberKeys(groupId: Long): List<String>

    @Query("DELETE FROM custom_group_member WHERE groupId IN (SELECT id FROM custom_group WHERE playlistId = :playlistId)")
    suspend fun clearMembersOfPlaylist(playlistId: Long)
    @Query("DELETE FROM custom_group WHERE playlistId = :playlistId") suspend fun clearCustomGroups(playlistId: Long)
    @Query("DELETE FROM group_pref WHERE playlistId = :playlistId") suspend fun clearGroupPrefs(playlistId: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE playlistId = :playlistId AND stableKey = :key)")
    suspend fun isFavorite(playlistId: Long, key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(f: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE playlistId = :playlistId AND stableKey = :key")
    suspend fun delete(playlistId: Long, key: String)

    /** Adds or removes; returns true when the channel is now a favorite. */
    @Transaction
    suspend fun toggle(playlistId: Long, key: String, nowMs: Long): Boolean =
        if (isFavorite(playlistId, key)) { delete(playlistId, key); false } else { insert(FavoriteEntity(playlistId, key, nowMs)); true }

    @Query("DELETE FROM favorite WHERE playlistId = :playlistId") suspend fun clear(playlistId: Long)

    @Query("SELECT stableKey FROM favorite WHERE playlistId = :playlistId")
    fun observeKeys(playlistId: Long): Flow<List<String>>

    @Query(
        """SELECT channel.* FROM favorite JOIN channel ON channel.playlistId = favorite.playlistId
           AND channel.stableKey = favorite.stableKey WHERE favorite.playlistId = :playlistId ORDER BY favorite.position, favorite.addedAt""",
    )
    fun favoriteChannels(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("UPDATE favorite SET position = :position WHERE playlistId = :playlistId AND stableKey = :key")
    suspend fun setPosition(playlistId: Long, key: String, position: Int)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE playlistId = :playlistId AND stableKey = :key")
    suspend fun entry(playlistId: Long, key: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(h: HistoryEntity)

    @Query(
        """DELETE FROM history WHERE playlistId = :playlistId AND stableKey NOT IN
           (SELECT stableKey FROM history WHERE playlistId = :playlistId ORDER BY lastWatchedAt DESC LIMIT :keep)""",
    )
    suspend fun trim(playlistId: Long, keep: Int)

    @Transaction
    suspend fun record(playlistId: Long, key: String, nowMs: Long, watchedSec: Int, keep: Int = 50) {
        val existing = entry(playlistId, key)
        put(HistoryEntity(playlistId, key, nowMs, (existing?.totalWatchSec ?: 0) + watchedSec))
        trim(playlistId, keep)
    }

    @Query(
        """SELECT channel.* FROM history JOIN channel ON channel.playlistId = history.playlistId
           AND channel.stableKey = history.stableKey WHERE history.playlistId = :playlistId
           ORDER BY history.lastWatchedAt DESC LIMIT :limit""",
    )
    fun recent(playlistId: Long, limit: Int = 500): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM history WHERE playlistId = :playlistId ORDER BY lastWatchedAt DESC LIMIT 1")
    suspend fun lastWatched(playlistId: Long): HistoryEntity?

    @Query("DELETE FROM history WHERE playlistId = :playlistId") suspend fun clear(playlistId: Long)
}

@Dao
interface EpgDao {
    /** When the last stored programme ends (null with no guide data). */
    @Query("SELECT MAX(stopMs) FROM programme") suspend fun latestStop(): Long?

    @Insert suspend fun insertSource(s: EpgSourceEntity): Long
    @Update suspend fun updateSource(s: EpgSourceEntity)
    @Query("DELETE FROM epg_source WHERE id = :id") suspend fun deleteSource(id: Long)
    @Query("SELECT * FROM epg_source WHERE id = :id") suspend fun source(id: Long): EpgSourceEntity?
    @Query("SELECT * FROM epg_source ORDER BY origin, priority, id") fun observeSources(): Flow<List<EpgSourceEntity>>

    /** The playlist's AUTO source plus every MANUAL source, in priority order. */
    @Query(
        """SELECT * FROM epg_source WHERE enabled = 1 AND ((origin = 'AUTO' AND playlistId = :playlistId) OR origin = 'MANUAL')
           ORDER BY CASE origin WHEN 'AUTO' THEN 0 ELSE 1 END, priority, id""",
    )
    suspend fun sourcesFor(playlistId: Long): List<EpgSourceEntity>

    /** Guide channels ("sourceId:xmltvId") with programmes that end after [afterMs]. */
    @Query("SELECT DISTINCT epgRef FROM programme WHERE stopMs > :afterMs")
    suspend fun refsWithProgrammesAfter(afterMs: Long): List<String>

    @Query("SELECT * FROM epg_source WHERE origin = 'AUTO' AND playlistId = :playlistId LIMIT 1")
    suspend fun autoSource(playlistId: Long): EpgSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChannels(rows: List<EpgChannelEntity>)
    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId") suspend fun deleteChannels(sourceId: Long)
    @Query("DELETE FROM programme WHERE sourceId = :sourceId") suspend fun deleteProgrammes(sourceId: Long)
    @Query("SELECT * FROM epg_channel WHERE sourceId = :sourceId") suspend fun channels(sourceId: Long): List<EpgChannelEntity>

    @Insert suspend fun insertProgrammes(rows: List<ProgrammeEntity>)

    /** Blocking variant for the streaming XMLTV parser callbacks. */
    @Insert fun insertProgrammesBlocking(rows: List<ProgrammeEntity>)

    @Transaction
    suspend fun replaceChannels(sourceId: Long, rows: List<EpgChannelEntity>) {
        deleteChannels(sourceId)
        rows.chunked(500).forEach { insertChannels(it) }
    }

    @Query(
        """SELECT p.* FROM programme p JOIN epg_source s ON s.id = p.sourceId AND p.generation = s.activeGeneration
           WHERE p.epgRef IN (:refs) AND p.stopMs > :fromMs AND p.startMs < :toMs ORDER BY p.epgRef, p.startMs""",
    )
    suspend fun programmesFor(refs: List<String>, fromMs: Long, toMs: Long): List<ProgrammeEntity>

    @Query(
        """SELECT p.* FROM programme p JOIN epg_source s ON s.id = p.sourceId AND p.generation = s.activeGeneration
           WHERE p.title LIKE '%' || :text || '%' AND p.stopMs > :fromMs AND p.startMs < :toMs ORDER BY p.startMs LIMIT :limit""",
    )
    suspend fun searchProgrammes(text: String, fromMs: Long, toMs: Long, limit: Int = 50): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programme WHERE sourceId = :sourceId") suspend fun countProgrammes(sourceId: Long): Int

    @Query("UPDATE programme SET startMs = startMs + :deltaMs, stopMs = stopMs + :deltaMs WHERE sourceId = :sourceId")
    suspend fun shiftProgrammes(sourceId: Long, deltaMs: Long)

    @Query("UPDATE epg_source SET activeGeneration = :generation WHERE id = :sourceId")
    suspend fun setActiveGeneration(sourceId: Long, generation: Int)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND generation != :keep")
    suspend fun deleteOtherGenerations(sourceId: Long, keep: Int)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND generation = :generation")
    suspend fun deleteGeneration(sourceId: Long, generation: Int)

    /**
     * Moves programmes that aired into [newGeneration] where it doesn't list that time any more: most
     * providers' guides start at "now". Kept since [keepFromMs], or [archiveFromMs] for channels with an archive.
     */
    @Query(
        """UPDATE programme SET generation = :newGeneration
           WHERE sourceId = :sourceId AND generation = (SELECT activeGeneration FROM epg_source WHERE id = :sourceId) AND startMs < :nowMs
             AND (stopMs > :keepFromMs OR (stopMs > :archiveFromMs AND epgRef IN (
               SELECT epgRef FROM channel WHERE epgRef IS NOT NULL AND (catchupType IS NOT NULL OR catchupSource IS NOT NULL))))
             AND stopMs <= COALESCE((SELECT MIN(n.startMs) FROM programme n WHERE n.epgRef = programme.epgRef AND n.generation = :newGeneration), :nowMs)""",
    )
    suspend fun carryOverPast(sourceId: Long, newGeneration: Int, nowMs: Long, keepFromMs: Long, archiveFromMs: Long)

    /** Programmes a guide lists twice for a channel and start (GitHub issue #7: once more with a "." added): the first stays. */
    @Query(
        """DELETE FROM programme WHERE sourceId = :sourceId AND generation = :generation AND id NOT IN (
             SELECT MIN(id) FROM programme WHERE sourceId = :sourceId AND generation = :generation GROUP BY epgRef, startMs)""",
    )
    suspend fun deleteRepeats(sourceId: Long, generation: Int)

    /** Makes [newGeneration] visible (with what already aired) and drops every other generation of the source atomically. */
    @Transaction
    suspend fun swapGeneration(sourceId: Long, newGeneration: Int, nowMs: Long = 0, keepFromMs: Long = 0, archiveFromMs: Long = 0) {
        deleteRepeats(sourceId, newGeneration)
        carryOverPast(sourceId, newGeneration, nowMs, keepFromMs, archiveFromMs)
        setActiveGeneration(sourceId, newGeneration)
        deleteOtherGenerations(sourceId, newGeneration)
    }

    /** Drops what ended before [beforeMs]; for channels with an archive only what ended before [archiveBeforeMs]. */
    @Query(
        """DELETE FROM programme WHERE stopMs < :archiveBeforeMs OR (stopMs < :beforeMs AND epgRef NOT IN (
             SELECT epgRef FROM channel WHERE epgRef IS NOT NULL AND (catchupType IS NOT NULL OR catchupSource IS NOT NULL)))""",
    )
    suspend fun deleteEndedBefore(beforeMs: Long, archiveBeforeMs: Long): Int
}
