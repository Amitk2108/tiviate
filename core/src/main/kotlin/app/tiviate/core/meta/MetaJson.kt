package app.tiviate.core.meta

import com.squareup.moshi.JsonReader
import okio.BufferedSource
import kotlin.math.abs

enum class Kind { MOVIE, TV }

/** A film or series on TMDB, as lists name it (recommendations, trending, a person's credits). */
data class TmdbTitle(val id: Long, val kind: Kind, val title: String, val year: Int?)

data class TmdbHit(val id: Long, val title: String, val year: Int?, val poster: String?)

data class TmdbCast(val id: Long, val name: String, val character: String?, val photo: String?)

/** A title's TMDB page; image fields are TMDB paths ("/abc.jpg"). */
data class TmdbDetails(
    val id: Long,
    val imdbId: String?,
    val overview: String?,
    val backdrop: String?,
    val poster: String?,
    val cast: List<TmdbCast>,
    val recommendations: List<TmdbTitle>,
)

data class TmdbPerson(val id: Long, val name: String, val photo: String?, val biography: String?, val credits: List<TmdbTitle>)

data class Ratings(val imdb: String?, val rottenTomatoes: String?)

data class SubHit(val fileId: Long, val language: String, val release: String, val downloads: Int, val hearingImpaired: Boolean)

/** TMDB (themoviedb.org) API v3 answers. */
object TmdbJson {

    fun search(src: BufferedSource, kind: Kind): List<TmdbHit> = results(src) { o -> TmdbHit(o.id, o.title, o.year, o.poster) }

    /** The hit with the same title and, when the year is known, a year at most one off; otherwise none. */
    fun best(hits: List<TmdbHit>, name: String, year: Int?): TmdbHit? =
        hits.filter { TitleCleaner.same(it.title, name) }.firstOrNull { year == null || (it.year != null && abs(it.year - year) <= 1) }

    fun details(src: BufferedSource, kind: Kind): TmdbDetails {
        val r = reader(src)
        var id = 0L
        var imdb: String? = null
        var overview: String? = null
        var backdrop: String? = null
        var poster: String? = null
        val cast = ArrayList<TmdbCast>()
        val recs = ArrayList<TmdbTitle>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = long(r) ?: 0
                "imdb_id" -> imdb = string(r)
                "overview" -> overview = string(r)
                "backdrop_path" -> backdrop = string(r)
                "poster_path" -> poster = string(r)
                "external_ids" -> obj(r) { name -> if (name == "imdb_id") { imdb = imdb ?: string(r); true } else false }
                "credits" -> obj(r) { name ->
                    if (name == "cast") { array(r) { val o = item(r); cast += TmdbCast(o.id, o.title, o.character, o.profile) }; true } else false
                }
                "recommendations" -> obj(r) { name ->
                    if (name == "results") { array(r) { val o = item(r); recs += TmdbTitle(o.id, o.kind ?: kind, o.title, o.year) }; true } else false
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return TmdbDetails(id, imdb, overview, backdrop, poster, cast.filter { it.name.isNotEmpty() }, recs.filter { it.title.isNotEmpty() })
    }

    /** `trending/all/day`: films and series (people left out). */
    fun trending(src: BufferedSource): List<TmdbTitle> = results(src) { o -> o.kind?.let { TmdbTitle(o.id, it, o.title, o.year) } }.filterNotNull()

    fun person(src: BufferedSource): TmdbPerson {
        val r = reader(src)
        var id = 0L
        var name = ""
        var photo: String? = null
        var bio: String? = null
        val credits = ArrayList<TmdbTitle>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = long(r) ?: 0
                "name" -> name = string(r).orEmpty()
                "profile_path" -> photo = string(r)
                "biography" -> bio = string(r)
                "combined_credits" -> obj(r) { n ->
                    if (n == "cast") { array(r) { val o = item(r); o.kind?.let { credits += TmdbTitle(o.id, it, o.title, o.year) } }; true } else false
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return TmdbPerson(id, name, photo, bio, credits.distinctBy { it.kind to it.id })
    }

    /** One object of a TMDB list (a film, a series, a cast member, a person). */
    private class Item(val id: Long, val title: String, val year: Int?, val poster: String?, val kind: Kind?, val character: String?, val profile: String?)

    private fun item(r: JsonReader): Item {
        var id = 0L
        var title = ""
        var date: String? = null
        var poster: String? = null
        var kind: Kind? = null
        var character: String? = null
        var profile: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = long(r) ?: 0
                "title", "name" -> title = title.ifEmpty { string(r).orEmpty() }
                "release_date", "first_air_date" -> date = date ?: string(r)
                "poster_path" -> poster = string(r)
                "media_type" -> kind = when (string(r)) { "movie" -> Kind.MOVIE; "tv" -> Kind.TV; else -> null }
                "character" -> character = string(r)
                "profile_path" -> profile = string(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Item(id, title, date?.take(4)?.toIntOrNull(), poster, kind, character, profile)
    }

    private fun <T> results(src: BufferedSource, map: (Item) -> T): List<T> {
        val r = reader(src)
        val out = ArrayList<T>()
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) return out
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "results" && r.peek() == JsonReader.Token.BEGIN_ARRAY) array(r) { out += map(item(r)) } else r.skipValue()
        }
        r.endObject()
        return out
    }
}

/** OMDb (omdbapi.com): IMDb's rating and Rotten Tomatoes' score. */
object OmdbJson {
    /** Null when OMDb doesn't know the title; "N/A" counts as missing. */
    fun ratings(src: BufferedSource): Ratings? {
        val r = reader(src)
        var ok = false
        var imdb: String? = null
        var rt: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "Response" -> ok = string(r).equals("True", ignoreCase = true)
                "imdbRating" -> imdb = string(r)?.takeIf { it != "N/A" }
                "Ratings" -> array(r) {
                    var source: String? = null
                    var value: String? = null
                    obj(r) { n -> when (n) { "Source" -> { source = string(r); true }; "Value" -> { value = string(r); true }; else -> false } }
                    if (source == "Rotten Tomatoes") rt = value?.takeIf { it != "N/A" }
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (ok) Ratings(imdb, rt) else null
    }
}

/** OpenSubtitles.com REST API v1. */
object OpenSubtitlesJson {
    fun search(src: BufferedSource): List<SubHit> {
        val r = reader(src)
        val out = ArrayList<SubHit>()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "data" || r.peek() != JsonReader.Token.BEGIN_ARRAY) { r.skipValue(); continue }
            array(r) {
                var language = ""
                var release = ""
                var downloads = 0
                var hi = false
                var fileId: Long? = null
                obj(r) { n ->
                    if (n != "attributes") return@obj false
                    obj(r) { a ->
                        when (a) {
                            "language" -> language = string(r).orEmpty()
                            "release" -> release = string(r).orEmpty()
                            "download_count" -> downloads = long(r)?.toInt() ?: 0
                            "hearing_impaired" -> hi = string(r) == "true"
                            "files" -> array(r) { obj(r) { f -> if (f == "file_id") { fileId = fileId ?: long(r); true } else false } }
                            else -> return@obj false
                        }
                        true
                    }
                    true
                }
                fileId?.let { out += SubHit(it, language, release, downloads, hi) }
            }
        }
        r.endObject()
        return out
    }

    /** The file's link from `/download`; null when the day's downloads are used up (or another refusal). */
    fun downloadLink(src: BufferedSource): String? {
        val r = reader(src)
        var link: String? = null
        r.beginObject()
        while (r.hasNext()) if (r.nextName() == "link") link = string(r) else r.skipValue()
        r.endObject()
        return link
    }
}

private fun reader(src: BufferedSource) = JsonReader.of(src).apply { isLenient = true }

private fun string(r: JsonReader): String? = when (r.peek()) {
    JsonReader.Token.STRING, JsonReader.Token.NUMBER -> r.nextString().trim().ifEmpty { null }
    JsonReader.Token.BOOLEAN -> r.nextBoolean().toString()
    else -> { r.skipValue(); null }
}

private fun long(r: JsonReader): Long? = string(r)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

private inline fun array(r: JsonReader, each: () -> Unit) {
    if (r.peek() != JsonReader.Token.BEGIN_ARRAY) { r.skipValue(); return }
    r.beginArray()
    while (r.hasNext()) if (r.peek() == JsonReader.Token.BEGIN_OBJECT) each() else r.skipValue()
    r.endArray()
}

/** Reads an object; [field] reads the fields it knows (returns true) and the rest are skipped. */
private inline fun obj(r: JsonReader, field: (String) -> Boolean) {
    if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); return }
    r.beginObject()
    while (r.hasNext()) { if (!field(r.nextName())) r.skipValue() }
    r.endObject()
}
