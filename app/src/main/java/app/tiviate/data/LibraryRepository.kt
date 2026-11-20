package app.tiviate.data

import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.core.xtream.XtreamDetails
import app.tiviate.core.xtream.XtreamEpisode
import app.tiviate.core.xtream.XtreamSeriesInfo
import app.tiviate.core.xtream.XtreamUrls
import app.tiviate.core.xtream.XtreamVodJson
import app.tiviate.data.db.MovieEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.TiviateDatabase
import app.tiviate.data.db.WatchProgressEntity
import app.tiviate.data.sync.getOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** A film or an episode to play (Movies & Series). */
data class VodPlay(
    val playlistId: Long,
    /** "movie:<streamId>" or "episode:<episodeId>", the key of its watch progress. */
    val itemKey: String,
    val title: String,
    val poster: String?,
    val url: String,
    val seriesId: Long? = null,
    val startAtMs: Long = 0,
    val userAgent: String? = null,
)

/** Movies & Series of Xtream accounts: details and episodes on demand, and where the viewer stopped. */
class LibraryRepository(private val db: TiviateDatabase, private val http: OkHttpClient) {

    fun count(playlistId: Long) = db.vod().libraryCount(playlistId)

    suspend fun movieDetails(p: PlaylistEntity, streamId: Long): XtreamDetails = withContext(Dispatchers.IO) {
        runCatching {
            http.getOrThrow(XtreamUrls.api(creds(p), "get_vod_info") + "&vod_id=$streamId", p.userAgent, "Film").use { XtreamVodJson.movieDetails(it.body.source()) }
        }.getOrDefault(XtreamDetails())
    }

    /** Throws when the provider can't be reached (the page then offers to try again). */
    suspend fun seriesInfo(p: PlaylistEntity, seriesId: Long): XtreamSeriesInfo = withContext(Dispatchers.IO) {
        http.getOrThrow(XtreamUrls.api(creds(p), "get_series_info") + "&series_id=$seriesId", p.userAgent, "Series").use { XtreamVodJson.seriesInfo(it.body.source()) }
    }

    fun moviePlay(p: PlaylistEntity, m: MovieEntity, startAtMs: Long) =
        VodPlay(p.id, movieKey(m.streamId), m.name, m.poster, XtreamUrls.movie(creds(p), m.streamId, m.extension), null, startAtMs, p.userAgent)

    fun episodePlay(p: PlaylistEntity, seriesId: Long, seriesName: String, e: XtreamEpisode, poster: String?, startAtMs: Long) = VodPlay(
        p.id, episodeKey(e.id), "$seriesName · S${e.season} E${e.episode} · ${e.title}", e.image ?: poster,
        XtreamUrls.episode(creds(p), e.id, e.extension), seriesId, startAtMs, p.userAgent,
    )

    suspend fun progress(playlistId: Long, key: String): WatchProgressEntity? = db.vod().progress(playlistId, key)
    fun progressFor(playlistId: Long, keys: List<String>) = db.vod().progressFor(playlistId, keys)
    fun continueWatching(playlistId: Long) = db.vod().continueWatching(playlistId)

    /** Saves where playback is; near the end counts as watched (it then starts from the beginning again). */
    suspend fun saveProgress(item: VodPlay, positionMs: Long, durationMs: Long, now: Long = System.currentTimeMillis()) {
        if (durationMs <= 0) return
        val finished = positionMs >= durationMs * 95 / 100 || (durationMs > 20 * 60_000L && positionMs >= durationMs - FINISHED_MARGIN_MS)
        db.vod().saveProgress(
            WatchProgressEntity(item.playlistId, item.itemKey, item.title, item.poster, if (finished) 0 else positionMs, durationMs, now, item.seriesId, finished),
        )
    }

    companion object {
        private const val FINISHED_MARGIN_MS = 3 * 60_000L
        fun movieKey(streamId: Long) = "movie:$streamId"
        fun episodeKey(episodeId: Long) = "episode:$episodeId"

        fun creds(p: PlaylistEntity) = XtreamCredentials(
            XtreamUrls.normalizeServer(requireNotNull(p.xtreamServer)), requireNotNull(p.xtreamUser), requireNotNull(p.xtreamPass),
        )
    }
}
