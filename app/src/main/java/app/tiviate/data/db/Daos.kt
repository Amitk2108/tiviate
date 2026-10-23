package app.tiviate.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert suspend fun insert(p: PlaylistEntity): Long
    @Update suspend fun update(p: PlaylistEntity)
    @Query("DELETE FROM playlist WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM playlist WHERE id = :id") suspend fun get(id: Long): PlaylistEntity?
    @Query("SELECT * FROM playlist ORDER BY sortIndex, id") fun observeAll(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlist ORDER BY sortIndex, id") suspend fun all(): List<PlaylistEntity>
}

@Dao
interface ChannelDao {
    /** Upsert by (playlistId, stableKey); the old row id is replaced. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(rows: List<ChannelEntity>)

    @Query("SELECT id, stableKey, epgRef, logoFallback FROM channel WHERE playlistId = :playlistId AND stableKey IN (:keys)")
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
        updateAllBlocking(known.map { r -> stored.getValue(r.stableKey).let { s -> r.copy(id = s.id, epgRef = s.epgRef, logoFallback = s.logoFallback) } })
        insertNewBlocking(fresh)
    }

    @Query("SELECT id, tvgId, tvgName, name, epgRef, logoFallback FROM channel WHERE playlistId = :playlistId")
    suspend fun matchRows(playlistId: Long): List<ChannelMatchRow>

    @Query("UPDATE channel SET epgRef = :epgRef, logoFallback = :logoFallback WHERE id = :id")
    fun setEpgBlocking(id: Long, epgRef: String?, logoFallback: String?)

    @Query("DELETE FROM channel WHERE playlistId = :playlistId AND syncStamp != :keepStamp")
    suspend fun deleteStale(playlistId: Long, keepStamp: Long): Int

    @Query("DELETE FROM channel WHERE playlistId = :playlistId") suspend fun deleteAll(playlistId: Long)

    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId") suspend fun count(playlistId: Long): Int

    @Query(
        """SELECT groupName AS name, COUNT(*) AS count FROM channel WHERE playlistId = :playlistId
           GROUP BY groupName ORDER BY MIN(sortIndex)""",
    )
    fun categories(playlistId: Long): Flow<List<CategoryRow>>

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

    @Query("SELECT * FROM channel WHERE playlistId = :playlistId AND number = :number ORDER BY sortIndex LIMIT 1")
    suspend fun byNumber(playlistId: Long, number: Int): ChannelEntity?

    @Query(
        """SELECT channel.* FROM channel JOIN channel_fts ON channel.id = channel_fts.rowid
           WHERE channel_fts MATCH :ftsQuery AND channel.playlistId = :playlistId ORDER BY channel.sortIndex LIMIT :limit""",
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
           AND channel.stableKey = favorite.stableKey WHERE favorite.playlistId = :playlistId ORDER BY favorite.addedAt""",
    )
    fun favoriteChannels(playlistId: Long): Flow<List<ChannelEntity>>
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

    @Query("UPDATE epg_source SET activeGeneration = :generation WHERE id = :sourceId")
    suspend fun setActiveGeneration(sourceId: Long, generation: Int)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND generation != :keep")
    suspend fun deleteOtherGenerations(sourceId: Long, keep: Int)

    @Query("DELETE FROM programme WHERE sourceId = :sourceId AND generation = :generation")
    suspend fun deleteGeneration(sourceId: Long, generation: Int)

    /** Makes [newGeneration] visible and drops every other generation of the source atomically. */
    @Transaction
    suspend fun swapGeneration(sourceId: Long, newGeneration: Int) {
        setActiveGeneration(sourceId, newGeneration)
        deleteOtherGenerations(sourceId, newGeneration)
    }

    @Query("DELETE FROM programme WHERE stopMs < :beforeMs") suspend fun deleteEndedBefore(beforeMs: Long): Int
}
