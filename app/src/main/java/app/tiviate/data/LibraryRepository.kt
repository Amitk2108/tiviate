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
import app.tiviate.data.db.TitlePrefEntity
import app.tiviate.data.db.VodMarkEntity
import kotlinx.coroutines.flow.first
import app.tiviate.core.meta.Kind
import app.tiviate.core.meta.TitleCleaner
import app.tiviate.core.meta.TmdbTitle
import kotlin.math.abs
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
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
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
        seriesName = seriesName, season = e.season, episode = e.episode,
    )

    suspend fun progress(playlistId: Long, key: String): WatchProgressEntity? = db.vod().progress(playlistId, key)
    fun progressFor(playlistId: Long, keys: List<String>) = db.vod().progressFor(playlistId, keys)
    /** The episode after [item] (by season, then number), from where the viewer stopped in it; null after the last. */
    suspend fun nextEpisode(item: VodPlay): VodPlay? {
        val seriesId = item.seriesId ?: return null
        val p = db.playlists().get(item.playlistId) ?: return null
        val series = db.vod().seriesItem(p.id, seriesId) ?: return null
        val episodes = seriesInfo(p, seriesId).episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val i = episodes.indexOfFirst { episodeKey(it.id) == item.itemKey }
        if (i < 0) return null
        val next = episodes.getOrNull(i + 1) ?: return null
        val at = db.vod().progress(p.id, episodeKey(next.id))?.takeIf { !it.finished }?.positionMs ?: 0
        return episodePlay(p, seriesId, series.name, next, series.cover, at)
    }

    /** What "Watch now" plays for a series: where the viewer stopped, the episode after a finished one, or the first. */
    suspend fun seriesPlay(p: PlaylistEntity, s: app.tiviate.data.db.SeriesEntity): VodPlay? {
        val episodes = seriesInfo(p, s.seriesId).episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        if (episodes.isEmpty()) return null
        val last = db.vod().lastProgressOfSeries(p.id, s.seriesId)
        val i = last?.let { l -> episodes.indexOfFirst { episodeKey(it.id) == l.itemKey } } ?: -1
        val (episode, at) = when {
            last == null || i < 0 -> episodes.first() to 0L
            !last.finished -> episodes[i] to last.positionMs
            else -> (episodes.getOrNull(i + 1) ?: episodes[i]) to 0L
        }
        return episodePlay(p, s.seriesId, s.name, episode, s.cover, at)
    }

    fun continueWatching(playlistId: Long) = db.vod().continueWatching(playlistId)

    /** The TMDB titles (Top 10, recommendations, a person's films) that this playlist has, in their order. */
    suspend fun findInLibrary(playlistId: Long, titles: List<TmdbTitle>): List<Any> {
        fun yearFits(a: Int?, b: Int?) = a == null || b == null || abs(a - b) <= 1
        val found = LinkedHashMap<String, Any>()
        for (t in titles.take(40)) {
            when (t.kind) {
                Kind.MOVIE -> db.vod().moviesNamedLike(playlistId, t.title)
                    .firstOrNull { TitleCleaner.same(it.name, t.title) && yearFits(TitleCleaner.year(it.name), t.year) }
                    ?.let { found.putIfAbsent("m${it.streamId}", it) }
                Kind.TV -> db.vod().seriesNamedLike(playlistId, t.title)
                    .firstOrNull { TitleCleaner.same(it.name, t.title) && yearFits(it.year?.toIntOrNull() ?: TitleCleaner.year(it.name), t.year) }
                    ?.let { found.putIfAbsent("s${it.seriesId}", it) }
            }
        }
        return found.values.toList()
    }

    // --- My list and favorites ([kind] MY_LIST or FAVORITE)
    fun isMarked(playlistId: Long, itemKey: String, kind: Int) = db.vod().isMarked(playlistId, itemKey, kind)
    fun marks(playlistId: Long, kind: Int) = db.vod().marks(playlistId, kind)
    suspend fun toggleMark(playlistId: Long, itemKey: String, kind: Int) {
        if (db.vod().isMarked(playlistId, itemKey, kind).first()) db.vod().removeMark(playlistId, itemKey, kind)
        else db.vod().addMark(VodMarkEntity(playlistId, itemKey, kind, System.currentTimeMillis()))
    }

    // --- What Tiviate remembers per film or series
    suspend fun pref(playlistId: Long, titleKey: String): TitlePrefEntity? = db.vod().pref(playlistId, titleKey)
    suspend fun updatePref(playlistId: Long, titleKey: String, change: (TitlePrefEntity) -> TitlePrefEntity) =
        db.vod().savePref(change(db.vod().pref(playlistId, titleKey) ?: TitlePrefEntity(playlistId, titleKey)))
    /** The series' page was opened: what was new until now has been seen. */
    suspend fun markSeen(playlistId: Long, seriesId: Long) = updatePref(playlistId, seriesKey(seriesId)) { it.copy(seenAt = System.currentTimeMillis()) }

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
        fun seriesKey(seriesId: Long) = "series:$seriesId"
        /** Episodes share their series' memory (sound, subtitles, intro). */
        fun titleKey(item: VodPlay) = item.seriesId?.let { seriesKey(it) } ?: item.itemKey
        const val MY_LIST = 0
        const val FAVORITE = 1

        fun creds(p: PlaylistEntity) = XtreamCredentials(
            XtreamUrls.normalizeServer(requireNotNull(p.xtreamServer)), requireNotNull(p.xtreamUser), requireNotNull(p.xtreamPass),
        )
    }
}
