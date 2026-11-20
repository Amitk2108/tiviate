package app.tiviate.data.sync

import app.tiviate.core.xtream.XtreamCategory
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.core.xtream.XtreamJson
import app.tiviate.core.xtream.XtreamUrls
import app.tiviate.core.xtream.XtreamVodJson
import app.tiviate.data.db.MovieEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.SeriesEntity
import app.tiviate.data.db.TiviateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Movies & Series of an Xtream account. Loaded after the live channels (which never wait for it);
 * a failure leaves the previous lists in place.
 */
class LibrarySyncer(
    private val db: TiviateDatabase,
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns how many films and series were loaded (0 for M3U playlists). */
    suspend fun sync(playlistId: Long): Int = withContext(Dispatchers.IO) {
        val p = db.playlists().get(playlistId) ?: return@withContext 0
        if (p.type != PlaylistType.XTREAM) return@withContext 0
        val creds = credentials(p)
        val stamp = clock()

        val movieCats = rank(http.getOrThrow(XtreamUrls.api(creds, "get_vod_categories"), p.userAgent, "Movie groups").use { XtreamJson.categories(it.body.source()) })
        val movies = ArrayList<MovieEntity>(BATCH)
        var position = 0
        val movieCount = http.getOrThrow(XtreamUrls.api(creds, "get_vod_streams"), p.userAgent, "Movies").use { response ->
            XtreamVodJson.movies(response.body.source()) { m ->
                val (category, rank) = movieCats[m.categoryId] ?: (null to movieCats.size)
                movies += MovieEntity(p.id, m.streamId, m.name, category, m.poster, m.rating, m.extension, m.addedAt, sortIndex(rank, ++position), stamp)
                if (movies.size >= BATCH) { db.vod().upsertMoviesBlocking(ArrayList(movies)); movies.clear() }
            }
        }
        if (movies.isNotEmpty()) db.vod().upsertMoviesBlocking(movies)

        val seriesCats = rank(http.getOrThrow(XtreamUrls.api(creds, "get_series_categories"), p.userAgent, "Series groups").use { XtreamJson.categories(it.body.source()) })
        val series = ArrayList<SeriesEntity>(BATCH)
        position = 0
        val seriesCount = http.getOrThrow(XtreamUrls.api(creds, "get_series"), p.userAgent, "Series").use { response ->
            XtreamVodJson.series(response.body.source()) { s ->
                val (category, rank) = seriesCats[s.categoryId] ?: (null to seriesCats.size)
                series += SeriesEntity(p.id, s.seriesId, s.name, category, s.cover, s.plot, s.rating, s.genre, s.year, sortIndex(rank, ++position), stamp)
                if (series.size >= BATCH) { db.vod().upsertSeriesBlocking(ArrayList(series)); series.clear() }
            }
        }
        if (series.isNotEmpty()) db.vod().upsertSeriesBlocking(series)

        // Only after both lists arrived in full: what the provider dropped goes.
        db.vod().replaceLibraryBlocking(p.id, stamp)
        movieCount + seriesCount
    }

    /** Category id → (name, position in the provider's order). */
    private fun rank(list: List<XtreamCategory>): Map<String, Pair<String, Int>> =
        list.withIndex().associate { (i, c) -> c.id to (c.name to i) }

    private companion object {
        const val BATCH = 500
        /** Items keep the provider's order inside their category; categories keep theirs. */
        const val BAND = 1_000_000

        fun sortIndex(rank: Int, position: Int): Int =
            (rank.toLong() * BAND + position % BAND).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun credentials(p: PlaylistEntity) = XtreamCredentials(
            XtreamUrls.normalizeServer(requireNotNull(p.xtreamServer)), requireNotNull(p.xtreamUser), requireNotNull(p.xtreamPass),
        )
    }
}
