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

data class LibraryCategory(val name: String?, val count: Int)

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

    /** [category] null = every film; [query] "" = no filter. */
    @Query(
        """SELECT * FROM movie WHERE playlistId = :playlistId AND (:all OR category IS :category)
           AND (:query = '' OR name LIKE '%' || :query || '%') ORDER BY sortIndex""",
    )
    fun movies(playlistId: Long, all: Boolean, category: String?, query: String): PagingSource<Int, MovieEntity>

    @Query(
        """SELECT * FROM series WHERE playlistId = :playlistId AND (:all OR category IS :category)
           AND (:query = '' OR name LIKE '%' || :query || '%') ORDER BY sortIndex""",
    )
    fun series(playlistId: Long, all: Boolean, category: String?, query: String): PagingSource<Int, SeriesEntity>

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

    @Transaction
    fun replaceLibraryBlocking(playlistId: Long, keep: Long) {
        deleteStaleMoviesBlocking(playlistId, keep)
        deleteStaleSeriesBlocking(playlistId, keep)
    }
}
