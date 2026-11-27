package app.tiviate.core.xtream

import com.squareup.moshi.JsonReader
import okio.BufferedSource

data class XtreamMovie(
    val streamId: Long,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: String?,
    val extension: String,
    val addedAt: Long?,
)

data class XtreamSeries(
    val seriesId: Long,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: String?,
    val genre: String?,
    val year: String?,
    val backdrop: String? = null,
    /** A YouTube video ID (or a link), as providers send it. */
    val trailer: String? = null,
    /** When the provider last changed the series (new episodes), in ms. */
    val lastModified: Long? = null,
    val cast: String? = null,
    val director: String? = null,
)

data class XtreamEpisode(
    val id: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val extension: String,
    val plot: String?,
    val image: String?,
    val durationSec: Int?,
    /** yyyy-MM-dd. */
    val airDate: String? = null,
)

/** Details shown on a film's or a series' page. */
data class XtreamDetails(
    val plot: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val year: String? = null,
    val durationSec: Int? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    val trailer: String? = null,
    /** yyyy-MM-dd. */
    val releaseDate: String? = null,
)

data class XtreamSeriesInfo(val details: XtreamDetails, val episodes: List<XtreamEpisode>)

/** Movies (VOD) and series from the Xtream API. Lenient like [XtreamJson]: providers send numbers as strings and vice versa. */
object XtreamVodJson {

    /** Emits each film of `get_vod_streams`; returns how many. */
    fun movies(src: BufferedSource, onMovie: (XtreamMovie) -> Unit): Int = eachObject(src) { r ->
        var id: Long? = null
        var name: String? = null
        var poster: String? = null
        var cat: String? = null
        var rating: String? = null
        var ext: String? = null
        var added: Long? = null
        while (r.hasNext()) {
            when (r.nextName()) {
                "stream_id" -> id = scalar(r)?.toLongValue()
                "name" -> name = scalar(r)?.trim()
                "stream_icon" -> poster = scalar(r)?.trim()?.ifEmpty { null }
                "category_id" -> cat = scalar(r)?.ifEmpty { null }
                "rating" -> rating = scalar(r)?.trim()?.ifEmpty { null }
                "container_extension" -> ext = scalar(r)?.trim()?.ifEmpty { null }
                "added" -> added = scalar(r)?.toLongValue()?.takeIf { it > 0 }?.times(1_000L)
                else -> r.skipValue()
            }
        }
        val streamId = id ?: return@eachObject false
        onMovie(XtreamMovie(streamId, name?.ifEmpty { null } ?: "Film $streamId", poster, cat, rating, ext ?: "mp4", added))
        true
    }

    /** Emits each series of `get_series`; returns how many. */
    fun series(src: BufferedSource, onSeries: (XtreamSeries) -> Unit): Int = eachObject(src) { r ->
        var id: Long? = null
        var name: String? = null
        var cover: String? = null
        var cat: String? = null
        var plot: String? = null
        var rating: String? = null
        var genre: String? = null
        var year: String? = null
        var backdrop: String? = null
        var trailer: String? = null
        var modified: Long? = null
        var cast: String? = null
        var director: String? = null
        while (r.hasNext()) {
            when (r.nextName()) {
                "series_id" -> id = scalar(r)?.toLongValue()
                "backdrop_path" -> backdrop = firstString(r)
                "youtube_trailer" -> trailer = scalar(r)?.trim()?.ifEmpty { null }
                "last_modified" -> modified = scalar(r)?.toLongValue()?.takeIf { it > 0 }?.times(1_000L)
                "cast" -> cast = scalar(r)?.trim()?.ifEmpty { null }
                "director" -> director = scalar(r)?.trim()?.ifEmpty { null }
                "name" -> name = scalar(r)?.trim()
                "cover" -> cover = scalar(r)?.trim()?.ifEmpty { null }
                "category_id" -> cat = scalar(r)?.ifEmpty { null }
                "plot" -> plot = scalar(r)?.trim()?.ifEmpty { null }
                "rating" -> rating = scalar(r)?.trim()?.ifEmpty { null }
                "genre" -> genre = scalar(r)?.trim()?.ifEmpty { null }
                "releaseDate", "release_date", "year" -> year = year ?: scalar(r)?.trim()?.take(4)?.ifEmpty { null }
                else -> r.skipValue()
            }
        }
        val seriesId = id ?: return@eachObject false
        onSeries(XtreamSeries(seriesId, name?.ifEmpty { null } ?: "Series $seriesId", cover, cat, plot, rating, genre, year, backdrop, trailer, modified, cast, director))
        true
    }

    /** `get_vod_info`: the film's details. */
    fun movieDetails(src: BufferedSource): XtreamDetails {
        val r = JsonReader.of(src).apply { isLenient = true }
        var details = XtreamDetails()
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) return details
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "info" && r.peek() == JsonReader.Token.BEGIN_OBJECT) details = readDetails(r) else r.skipValue()
        }
        r.endObject()
        return details
    }

    /** `get_series_info`: the series' details and every episode, in season and episode order. */
    fun seriesInfo(src: BufferedSource): XtreamSeriesInfo {
        val r = JsonReader.of(src).apply { isLenient = true }
        var details = XtreamDetails()
        val episodes = ArrayList<XtreamEpisode>()
        if (r.peek() != JsonReader.Token.BEGIN_OBJECT) return XtreamSeriesInfo(details, episodes)
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "info" -> if (r.peek() == JsonReader.Token.BEGIN_OBJECT) details = readDetails(r) else r.skipValue()
                // Usually {"1": [...], "2": [...]}; some panels send [[...], [...]].
                "episodes" -> when (r.peek()) {
                    JsonReader.Token.BEGIN_OBJECT -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val season = r.nextName().toIntOrNull() ?: 0
                            readEpisodes(r, season, episodes)
                        }
                        r.endObject()
                    }
                    JsonReader.Token.BEGIN_ARRAY -> {
                        r.beginArray()
                        var i = 1
                        while (r.hasNext()) readEpisodes(r, i++, episodes)
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return XtreamSeriesInfo(details, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
    }

    private fun readEpisodes(r: JsonReader, seasonKey: Int, out: MutableList<XtreamEpisode>) {
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) { r.skipValue(); return }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); continue }
            var id: Long? = null
            var season: Int? = null
            var number: Int? = null
            var title: String? = null
            var ext: String? = null
            var info = XtreamDetails()
            var image: String? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "id" -> id = scalar(r)?.toLongValue()
                    "season" -> season = scalar(r)?.toLongValue()?.toInt()
                    "episode_num" -> number = scalar(r)?.toLongValue()?.toInt()
                    "title" -> title = scalar(r)?.trim()
                    "container_extension" -> ext = scalar(r)?.trim()?.ifEmpty { null }
                    "info" -> if (r.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        info = readDetails(r) { name, reader -> if (name == "movie_image") { image = scalar(reader)?.trim()?.ifEmpty { null }; true } else false }
                    } else r.skipValue()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val episodeId = id ?: continue
            val n = number ?: (out.count { it.season == (season ?: seasonKey) } + 1)
            out += XtreamEpisode(episodeId, season ?: seasonKey, n, title?.ifEmpty { null } ?: "Episode $n", ext ?: "mp4", info.plot, image, info.durationSec, info.releaseDate)
        }
        r.endArray()
    }

    private fun readDetails(r: JsonReader, extra: (String, JsonReader) -> Boolean = { _, _ -> false }): XtreamDetails {
        var d = XtreamDetails()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (extra(name, r)) continue
            when (name) {
                "plot", "description" -> d = d.copy(plot = d.plot ?: scalar(r)?.trim()?.ifEmpty { null })
                "genre" -> d = d.copy(genre = scalar(r)?.trim()?.ifEmpty { null })
                "cast", "actors" -> d = d.copy(cast = d.cast ?: scalar(r)?.trim()?.ifEmpty { null })
                "director" -> d = d.copy(director = scalar(r)?.trim()?.ifEmpty { null })
                "releasedate", "releaseDate", "release_date", "air_date" -> {
                    val date = scalar(r)?.trim()?.ifEmpty { null }
                    d = d.copy(year = d.year ?: date?.take(4), releaseDate = d.releaseDate ?: date?.take(10)?.takeIf { it.length == 10 })
                }
                "youtube_trailer" -> d = d.copy(trailer = scalar(r)?.trim()?.ifEmpty { null })
                "duration_secs" -> d = d.copy(durationSec = scalar(r)?.toLongValue()?.toInt()?.takeIf { it > 0 })
                "rating" -> d = d.copy(rating = scalar(r)?.trim()?.ifEmpty { null })
                "backdrop_path" -> d = d.copy(backdrop = firstString(r))
                else -> r.skipValue()
            }
        }
        r.endObject()
        return d
    }

    private fun firstString(r: JsonReader): String? = when (r.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
            var first: String? = null
            r.beginArray()
            while (r.hasNext()) { val s = scalar(r); if (first == null) first = s?.trim()?.ifEmpty { null } }
            r.endArray()
            first
        }
        else -> scalar(r)?.trim()?.ifEmpty { null }
    }

    /** Walks a top-level array of objects; [read] reads one object's fields and says whether it counted. */
    private fun eachObject(src: BufferedSource, read: (JsonReader) -> Boolean): Int {
        val r = JsonReader.of(src).apply { isLenient = true }
        if (r.peek() != JsonReader.Token.BEGIN_ARRAY) return 0
        var count = 0
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonReader.Token.BEGIN_OBJECT) { r.skipValue(); continue }
            r.beginObject()
            if (read(r)) count++
            r.endObject()
        }
        r.endArray()
        return count
    }

    private fun scalar(r: JsonReader): String? = when (r.peek()) {
        JsonReader.Token.STRING, JsonReader.Token.NUMBER -> r.nextString()
        JsonReader.Token.BOOLEAN -> r.nextBoolean().toString()
        JsonReader.Token.NULL -> r.nextNull<Unit>().let { null }
        else -> { r.skipValue(); null }
    }

    private fun String.toLongValue(): Long? = trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong()
}
