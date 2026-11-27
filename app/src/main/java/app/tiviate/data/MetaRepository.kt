package app.tiviate.data

import app.tiviate.core.meta.Kind
import app.tiviate.core.meta.OmdbJson
import app.tiviate.core.meta.OpenSubtitlesJson
import app.tiviate.core.meta.Ratings
import app.tiviate.core.meta.SubHit
import app.tiviate.core.meta.TitleCleaner
import app.tiviate.core.meta.TmdbDetails
import app.tiviate.core.meta.TmdbJson
import app.tiviate.core.meta.TmdbPerson
import app.tiviate.core.meta.TmdbTitle
import app.tiviate.data.db.MetaCacheEntity
import app.tiviate.data.db.TiviateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/** Where the online services live (tests point them at a local server). */
object MetaEndpoints {
    @Volatile var tmdb = "https://api.themoviedb.org/3"
    @Volatile var tmdbImages = "https://image.tmdb.org/t/p"
    @Volatile var omdb = "https://www.omdbapi.com"
    @Volatile var openSubtitles = "https://api.opensubtitles.com/api/v1"
}

/** What to look up subtitles for: a film ([season] null) or an episode. [languages]: ISO 639-1, best first. */
data class SubQuery(val title: String, val year: Int?, val season: Int?, val episode: Int?, val languages: List<String>)

/** OpenSubtitles refused the download (usually: today's free downloads are used up). */
class SubtitleRefused(message: String) : Exception(message)

/**
 * Movies & Series extras from online services, each with the viewer's own free key (Settings → API keys):
 * TMDB (descriptions, pictures, cast, Top 10, recommendations), OMDb (IMDb and Rotten Tomatoes ratings) and
 * OpenSubtitles. Nothing is sent to a service whose key isn't saved; answers are kept for a while.
 */
class MetaRepository(
    private val db: TiviateDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val userAgent: String,
) {
    enum class Service { TMDB, OMDB, OPENSUBTITLES }

    sealed interface KeyCheck {
        data object Works : KeyCheck
        data object Rejected : KeyCheck
        data object Missing : KeyCheck
        data class Failed(val message: String) : KeyCheck
    }

    /** The services that have a key. */
    val services: Flow<Set<Service>> = settings.settings.map { s ->
        buildSet {
            if (!s.tmdbKey.isNullOrBlank()) add(Service.TMDB)
            if (!s.omdbKey.isNullOrBlank()) add(Service.OMDB)
            if (!s.openSubtitlesKey.isNullOrBlank()) add(Service.OPENSUBTITLES)
        }
    }

    private suspend fun key(service: Service): String? = settings.settings.first().let {
        when (service) {
            Service.TMDB -> it.tmdbKey
            Service.OMDB -> it.omdbKey
            Service.OPENSUBTITLES -> it.openSubtitlesKey
        }
    }?.trim()?.ifEmpty { null }

    private suspend fun language(): String = settings.settings.first().language ?: Locale.getDefault().toLanguageTag()

    /** Tries the saved key with a request that costs nothing. */
    suspend fun check(service: Service): KeyCheck = withContext(Dispatchers.IO) {
        val k = key(service) ?: return@withContext KeyCheck.Missing
        val request = when (service) {
            Service.TMDB -> Request.Builder().url("${MetaEndpoints.tmdb}/configuration?api_key=${enc(k)}")
            Service.OMDB -> Request.Builder().url("${MetaEndpoints.omdb}/?apikey=${enc(k)}&i=tt0000001")
            // A file that doesn't exist: a known key gets "invalid file", an unknown one 401/403.
            Service.OPENSUBTITLES -> os(k, "/download").post("""{"file_id":1}""".toRequestBody(JSON))
        }.header("User-Agent", userAgent).build()
        runCatching {
            http.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.code == 401 || r.code == 403 -> KeyCheck.Rejected
                    service == Service.OMDB && body.contains("Invalid API key", ignoreCase = true) -> KeyCheck.Rejected
                    r.isSuccessful || service == Service.OPENSUBTITLES && r.code in 400..499 -> KeyCheck.Works
                    else -> KeyCheck.Failed("HTTP ${r.code}")
                }
            }
        }.getOrElse { KeyCheck.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** The title on TMDB (same name; year within one when known), or null: no key, not found, no network. */
    suspend fun titleInfo(kind: Kind, name: String, year: Int?): TmdbDetails? = withContext(Dispatchers.IO) {
        val k = key(Service.TMDB) ?: return@withContext null
        val lang = language()
        val clean = TitleCleaner.clean(name)
        val y = year ?: TitleCleaner.year(name)
        val path = if (kind == Kind.MOVIE) "movie" else "tv"
        val search = cached("tmdb_search", "$path|$clean|$lang", DAYS_14) {
            get("${MetaEndpoints.tmdb}/search/$path?api_key=${enc(k)}&language=${enc(lang)}&query=${enc(clean)}")
        } ?: return@withContext null
        val hit = runCatching { TmdbJson.best(TmdbJson.search(buffer(search), kind), clean, y) }.getOrNull() ?: return@withContext null
        val body = cached("tmdb_title", "$path|${hit.id}|$lang", DAYS_14) {
            get("${MetaEndpoints.tmdb}/$path/${hit.id}?api_key=${enc(k)}&language=${enc(lang)}&append_to_response=credits,recommendations,external_ids")
        } ?: return@withContext null
        runCatching { TmdbJson.details(buffer(body), kind) }.getOrNull()
    }

    /** IMDb's rating and Rotten Tomatoes' score for an IMDb id (needs the OMDb key). */
    suspend fun ratings(imdbId: String): Ratings? = withContext(Dispatchers.IO) {
        val k = key(Service.OMDB) ?: return@withContext null
        val body = cached("omdb", imdbId, DAYS_7) { get("${MetaEndpoints.omdb}/?apikey=${enc(k)}&i=${enc(imdbId)}") } ?: return@withContext null
        runCatching { OmdbJson.ratings(buffer(body)) }.getOrNull()
    }

    suspend fun person(personId: Long): TmdbPerson? = withContext(Dispatchers.IO) {
        val k = key(Service.TMDB) ?: return@withContext null
        val lang = language()
        val body = cached("tmdb_person", "$personId|$lang", DAYS_14) {
            get("${MetaEndpoints.tmdb}/person/$personId?api_key=${enc(k)}&language=${enc(lang)}&append_to_response=combined_credits")
        } ?: return@withContext null
        runCatching { TmdbJson.person(buffer(body)) }.getOrNull()
    }

    /** Today's most watched films and series worldwide (TMDB). */
    suspend fun trending(): List<TmdbTitle> = withContext(Dispatchers.IO) {
        val k = key(Service.TMDB) ?: return@withContext emptyList()
        val body = cached("tmdb_trending", "day", HOURS_6) { get("${MetaEndpoints.tmdb}/trending/all/day?api_key=${enc(k)}") } ?: return@withContext emptyList()
        runCatching { TmdbJson.trending(buffer(body)) }.getOrDefault(emptyList())
    }

    /** A TMDB picture's address; [size]: "w185" (faces), "w342" (posters), "w780" (backdrops). */
    fun image(path: String?, size: String): String? = path?.let { "${MetaEndpoints.tmdbImages}/$size$it" }

    suspend fun searchSubtitles(q: SubQuery): List<SubHit> = withContext(Dispatchers.IO) {
        val k = key(Service.OPENSUBTITLES) ?: return@withContext emptyList()
        val url = buildString {
            append("${MetaEndpoints.openSubtitles}/subtitles?languages=").append(enc(q.languages.distinct().sorted().joinToString(",")))
            append("&query=").append(enc(TitleCleaner.clean(q.title).lowercase()))
            q.year?.let { append("&year=").append(it) }
            q.season?.let { append("&season_number=").append(it) }
            q.episode?.let { append("&episode_number=").append(it) }
        }
        val body = http.newCall(os(k, url, absolute = true).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext emptyList()
        val order = q.languages.distinct()
        runCatching { OpenSubtitlesJson.search(buffer(body)) }.getOrDefault(emptyList())
            .sortedWith(compareBy<SubHit>({ order.indexOf(it.language).let { i -> if (i < 0) order.size else i } }, { -it.downloads }))
    }

    /** Downloads [hit] into [dir] (at most 2 MB); [SubtitleRefused] says why OpenSubtitles refused it. */
    suspend fun downloadSubtitle(hit: SubHit, dir: File): File = withContext(Dispatchers.IO) {
        val k = key(Service.OPENSUBTITLES) ?: throw SubtitleRefused("No OpenSubtitles key")
        val answer = http.newCall(os(k, "/download").post("""{"file_id":${hit.fileId}}""".toRequestBody(JSON)).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 406) throw SubtitleRefused("Today's free downloads are used up")
            if (!r.isSuccessful) throw SubtitleRefused("HTTP ${r.code}")
            text
        }
        val link = OpenSubtitlesJson.downloadLink(buffer(answer)) ?: throw SubtitleRefused("Today's free downloads are used up")
        val file = File(dir.apply { mkdirs() }, "${hit.fileId}.srt")
        http.newCall(Request.Builder().url(link).header("User-Agent", userAgent).build()).execute().use { r ->
            if (!r.isSuccessful) throw SubtitleRefused("HTTP ${r.code}")
            val bytes = r.body?.byteStream()?.use { it.readNBytesCompat(MAX_SUBTITLE) } ?: throw SubtitleRefused("Empty file")
            file.writeBytes(bytes)
        }
        file
    }

    private fun os(key: String, path: String, absolute: Boolean = false) = Request.Builder()
        .url(if (absolute) path else MetaEndpoints.openSubtitles + path)
        .header("Api-Key", key).header("User-Agent", userAgent).header("Accept", "application/json")

    /** The body of a successful GET, or null (no network, an error answer). */
    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /** A stored answer younger than [maxAgeMs], or a fresh one (stored; a failed request isn't). */
    private suspend fun cached(kind: String, query: String, maxAgeMs: Long, fetch: suspend () -> String?): String? {
        val now = System.currentTimeMillis()
        db.vod().cached(kind, query)?.takeIf { now - it.fetchedAt < maxAgeMs }?.let { return it.json }
        val body = fetch() ?: return null
        db.vod().cache(MetaCacheEntity(kind, query, body, now))
        return body
    }

    private fun buffer(s: String) = Buffer().writeUtf8(s)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val HOURS_6 = 6 * 3_600_000L
        const val DAYS_7 = 7 * 86_400_000L
        const val DAYS_14 = 14 * 86_400_000L
        const val MAX_SUBTITLE = 2 * 1024 * 1024
    }
}
