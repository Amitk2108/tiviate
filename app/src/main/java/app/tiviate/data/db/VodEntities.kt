package app.tiviate.data.db

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A film from an Xtream account's "Movies" (VOD). */
@Entity(tableName = "movie", primaryKeys = ["playlistId", "streamId"], indices = [Index(value = ["playlistId", "category"])])
data class MovieEntity(
    val playlistId: Long,
    val streamId: Long,
    val name: String,
    val category: String?,
    val poster: String?,
    val rating: String?,
    val extension: String,
    val addedAt: Long?,
    val sortIndex: Int,
    val syncStamp: Long,
)

/** A series from an Xtream account; its episodes are fetched when it is opened. */
@Entity(tableName = "series", primaryKeys = ["playlistId", "seriesId"], indices = [Index(value = ["playlistId", "category"])])
data class SeriesEntity(
    val playlistId: Long,
    val seriesId: Long,
    val name: String,
    val category: String?,
    val cover: String?,
    val plot: String?,
    val rating: String?,
    val genre: String?,
    val year: String?,
    val sortIndex: Int,
    val syncStamp: Long,
    val backdrop: String? = null,
    /** A YouTube video ID or link. */
    val trailer: String? = null,
    /** When the provider last added episodes, in ms. */
    val lastModified: Long? = null,
    val castNames: String? = null,
    val director: String? = null,
)

/**
 * Where the viewer stopped in a film or an episode ("Continue watching"). [itemKey] is "movie:<streamId>"
 * or "episode:<episodeId>"; [seriesId] is set for episodes.
 */
@Entity(tableName = "watch_progress", primaryKeys = ["playlistId", "itemKey"])
data class WatchProgressEntity(
    val playlistId: Long,
    val itemKey: String,
    val title: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesId: Long? = null,
    @ColumnInfo(defaultValue = "0") val finished: Boolean = false,
)

/** A film or series on the viewer's "My list" ([kind] 0) or among their favorites ([kind] 1). [itemKey]: "movie:<id>" or "series:<id>". */
@Entity(tableName = "vod_mark", primaryKeys = ["playlistId", "itemKey", "kind"])
data class VodMarkEntity(val playlistId: Long, val itemKey: String, val kind: Int, val addedAt: Long)

/**
 * What Tiviate remembers per film or series ([titleKey] "movie:<id>" / "series:<id>"; episodes share their series'):
 * sound and subtitle language ("off" = subtitles off), where the intro ends, when its page was last opened (new
 * episodes) and an online subtitle file.
 */
@Entity(tableName = "title_pref", primaryKeys = ["playlistId", "titleKey"])
data class TitlePrefEntity(
    val playlistId: Long,
    val titleKey: String,
    val audioLang: String? = null,
    val textLang: String? = null,
    val introEndMs: Long? = null,
    val seenAt: Long? = null,
    val subtitlePath: String? = null,
)

/** Answers of online services (TMDB, OMDb), kept for a while: [kind] says which question, [query] its key. */
@Entity(tableName = "meta_cache", primaryKeys = ["kind", "query"])
data class MetaCacheEntity(val kind: String, val query: String, val json: String, val fetchedAt: Long)

data class LibraryCategory(val name: String?, val count: Int)

/** How a Movies & Series grid is ordered. */
object VodSort {
    const val PROVIDER = 0
    const val NEWEST = 1
    const val NAME = 2
    const val RATING = 3
}

@Dao
interface VodDao {
    @Upsert fun upsertMoviesBlocking(rows: List<MovieEntity>)
    @Upsert fun upsertSeriesBlocking(rows: List<SeriesEntity>)

    @Query("DELETE FROM movie WHERE playlistId = :playlistId AND syncStamp != :keep") fun deleteStaleMoviesBlocking(playlistId: Long, keep: Long): Int
    @Query("DELETE FROM series WHERE playlistId = :playlistId AND syncStamp != :keep") fun deleteStaleSeriesBlocking(playlistId: Long, keep: Long): Int
    @Query("DELETE FROM movie WHERE playlistId = :playlistId") suspend fun deleteMovies(playlistId: Long)
    @Query("DELETE FROM series WHERE playlistId = :playlistId") suspend fun deleteSeries(playlistId: Long)

    @Query("SELECT (SELECT COUNT(*) FROM movie WHERE playlistId = :playlistId) + (SELECT COUNT(*) FROM series WHERE playlistId = :playlistId)")
    fun libraryCount(playlistId: Long): Flow<Int>

    @Query("SELECT category AS name, COUNT(*) AS count FROM movie WHERE playlistId = :playlistId GROUP BY category ORDER BY MIN(sortIndex)")
    fun movieCategories(playlistId: Long): Flow<List<LibraryCategory>>

    @Query("SELECT category AS name, COUNT(*) AS count FROM series WHERE playlistId = :playlistId GROUP BY category ORDER BY MIN(sortIndex)")
    fun seriesCategories(playlistId: Long): Flow<List<LibraryCategory>>

    /** [category] null = every film; [query] "" = no filter; [sort] one of [VodSort] (the provider's order otherwise). */
    @Query(
        """SELECT * FROM movie WHERE playlistId = :playlistId AND (:all OR category IS :category)
           AND (:query = '' OR name LIKE '%' || :query || '%')
           ORDER BY CASE WHEN :sort = 1 THEN -IFNULL(addedAt, 0) ELSE 0 END,
                    CASE WHEN :sort = 2 THEN name END COLLATE NOCASE,
                    CASE WHEN :sort = 3 THEN -CAST(IFNULL(rating, 0) AS REAL) ELSE 0 END,
                    sortIndex""",
    )
    fun movies(playlistId: Long, all: Boolean, category: String?, query: String, sort: Int): PagingSource<Int, MovieEntity>

    /** Like [movies]; [genre] null = every genre; "newest" = the most recently updated. */
    @Query(
        """SELECT * FROM series WHERE playlistId = :playlistId AND (:all OR category IS :category)
           AND (:query = '' OR name LIKE '%' || :query || '%') AND (:genre IS NULL OR genre LIKE '%' || :genre || '%')
           ORDER BY CASE WHEN :sort = 1 THEN -IFNULL(lastModified, 0) ELSE 0 END,
                    CASE WHEN :sort = 2 THEN name END COLLATE NOCASE,
                    CASE WHEN :sort = 3 THEN -CAST(IFNULL(rating, 0) AS REAL) ELSE 0 END,
                    sortIndex""",
    )
    fun series(playlistId: Long, all: Boolean, category: String?, query: String, sort: Int = VodSort.PROVIDER, genre: String? = null): PagingSource<Int, SeriesEntity>

    @Query("SELECT genre FROM series WHERE playlistId = :playlistId AND genre IS NOT NULL AND genre != ''")
    fun seriesGenreValues(playlistId: Long): Flow<List<String>>

    // --- Movies & Series home rows (each a few titles; a group's row asks only when it comes into view)
    @Query("SELECT * FROM movie WHERE playlistId = :playlistId AND addedAt IS NOT NULL ORDER BY addedAt DESC LIMIT :limit")
    fun recentMovies(playlistId: Long, limit: Int): Flow<List<MovieEntity>>
    @Query("SELECT * FROM movie WHERE playlistId = :playlistId AND category IS :category ORDER BY sortIndex LIMIT :limit")
    fun moviesIn(playlistId: Long, category: String?, limit: Int): Flow<List<MovieEntity>>
    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND category IS :category ORDER BY sortIndex LIMIT :limit")
    fun seriesIn(playlistId: Long, category: String?, limit: Int): Flow<List<SeriesEntity>>
    @Query(
        """SELECT m.* FROM movie m JOIN vod_mark k ON k.playlistId = m.playlistId AND k.itemKey = 'movie:' || m.streamId
           WHERE m.playlistId = :playlistId AND k.kind = :kind ORDER BY k.addedAt DESC""",
    )
    fun markedMovies(playlistId: Long, kind: Int): Flow<List<MovieEntity>>
    @Query(
        """SELECT s.* FROM series s JOIN vod_mark k ON k.playlistId = s.playlistId AND k.itemKey = 'series:' || s.seriesId
           WHERE s.playlistId = :playlistId AND k.kind = :kind ORDER BY k.addedAt DESC""",
    )
    fun markedSeries(playlistId: Long, kind: Int): Flow<List<SeriesEntity>>
    /** Series for the top of the Series tab: with a picture, most recently updated first. */
    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND backdrop IS NOT NULL ORDER BY IFNULL(lastModified, 0) DESC, sortIndex LIMIT :limit")
    fun heroSeries(playlistId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM movie WHERE playlistId = :playlistId AND name LIKE '%' || :name || '%' LIMIT 20")
    suspend fun moviesNamedLike(playlistId: Long, name: String): List<MovieEntity>
    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND name LIKE '%' || :name || '%' LIMIT 20")
    suspend fun seriesNamedLike(playlistId: Long, name: String): List<SeriesEntity>

    @Query("SELECT * FROM movie WHERE playlistId = :playlistId AND streamId = :streamId") suspend fun movie(playlistId: Long, streamId: Long): MovieEntity?
    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND seriesId = :seriesId") suspend fun seriesItem(playlistId: Long, seriesId: Long): SeriesEntity?

    // --- Watch progress
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(p: WatchProgressEntity)
    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND itemKey = :itemKey") suspend fun progress(playlistId: Long, itemKey: String): WatchProgressEntity?
    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND itemKey IN (:keys)") fun progressFor(playlistId: Long, keys: List<String>): Flow<List<WatchProgressEntity>>

    /** Started but not finished, newest first; one entry per series (its latest episode). */
    @Query(
        """SELECT * FROM watch_progress WHERE playlistId = :playlistId AND finished = 0 AND positionMs > 0
           AND (seriesId IS NULL OR updatedAt = (SELECT MAX(w2.updatedAt) FROM watch_progress w2 WHERE w2.playlistId = watch_progress.playlistId AND w2.seriesId = watch_progress.seriesId))
           ORDER BY updatedAt DESC LIMIT 30""",
    )
    fun continueWatching(playlistId: Long): Flow<List<WatchProgressEntity>>

    @Query("DELETE FROM watch_progress WHERE playlistId = :playlistId") suspend fun clearProgress(playlistId: Long)
    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastProgressOfSeries(playlistId: Long, seriesId: Long): WatchProgressEntity?

    // --- My list, favorites, per-title memory, online answers
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addMark(m: VodMarkEntity)
    @Query("DELETE FROM vod_mark WHERE playlistId = :playlistId AND itemKey = :itemKey AND kind = :kind") suspend fun removeMark(playlistId: Long, itemKey: String, kind: Int)
    @Query("SELECT COUNT(*) > 0 FROM vod_mark WHERE playlistId = :playlistId AND itemKey = :itemKey AND kind = :kind") fun isMarked(playlistId: Long, itemKey: String, kind: Int): Flow<Boolean>
    @Query("SELECT * FROM vod_mark WHERE playlistId = :playlistId AND kind = :kind ORDER BY addedAt DESC") fun marks(playlistId: Long, kind: Int): Flow<List<VodMarkEntity>>
    @Query("SELECT * FROM title_pref WHERE playlistId = :playlistId AND titleKey = :titleKey") suspend fun pref(playlistId: Long, titleKey: String): TitlePrefEntity?
    @Query("SELECT * FROM title_pref WHERE playlistId = :playlistId") fun prefs(playlistId: Long): Flow<List<TitlePrefEntity>>
    @Upsert suspend fun savePref(p: TitlePrefEntity)

    /** Series the viewer watches whose provider added episodes after they last watched or opened them. */
    @Query(
        """SELECT s.seriesId FROM series s
           JOIN (SELECT seriesId, MAX(updatedAt) AS at FROM watch_progress WHERE playlistId = :playlistId AND seriesId IS NOT NULL GROUP BY seriesId) w
             ON w.seriesId = s.seriesId
           LEFT JOIN title_pref t ON t.playlistId = s.playlistId AND t.titleKey = 'series:' || s.seriesId
           WHERE s.playlistId = :playlistId AND s.lastModified IS NOT NULL AND s.lastModified > MAX(w.at, IFNULL(t.seenAt, 0))""",
    )
    fun seriesWithNewEpisodes(playlistId: Long): Flow<List<Long>>
    @Query("DELETE FROM vod_mark WHERE playlistId = :playlistId") suspend fun clearMarks(playlistId: Long)
    @Query("DELETE FROM title_pref WHERE playlistId = :playlistId") suspend fun clearPrefs(playlistId: Long)
    @Query("SELECT * FROM meta_cache WHERE kind = :kind AND query = :query") suspend fun cached(kind: String, query: String): MetaCacheEntity?
    @Upsert suspend fun cache(e: MetaCacheEntity)

    @Transaction
    fun replaceLibraryBlocking(playlistId: Long, keep: Long) {
        deleteStaleMoviesBlocking(playlistId, keep)
        deleteStaleSeriesBlocking(playlistId, keep)
    }
}
