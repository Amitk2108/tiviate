package app.tiviate.core.meta

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaJsonTest {
    private fun src(json: String) = Buffer().writeUtf8(json)

    @Test
    fun iptvNamesAreCleaned() {
        assertEquals("Carrie", TitleCleaner.clean("EN | Carrie (2013) [4K]"))
        assertEquals(2013, TitleCleaner.year("EN | Carrie (2013) [4K]"))
        assertEquals("Der Pate", TitleCleaner.clean("DE: Der Pate (1972)"))
        assertEquals("The Matrix", TitleCleaner.clean("The Matrix 1999 FHD"))
        assertEquals(1999, TitleCleaner.year("The Matrix 1999 FHD"))
        // A number in the title, not a year; a title that is only a year.
        assertEquals("Blade Runner 2049", TitleCleaner.clean("Blade Runner 2049"))
        assertNull(TitleCleaner.year("Blade Runner 2049"))
        assertEquals("1917", TitleCleaner.clean("1917"))
        assertTrue(TitleCleaner.same("EN | Amélie [HD]", "Amelie"))
        assertFalse(TitleCleaner.same("Sintel", "Tears of Steel"))
    }

    /** Review Focus 4: with a known year, a hit from another year is not taken (no ratings rather than wrong ones). */
    @Test
    fun theBestHitMatchesNameAndYear() {
        val hits = TmdbJson.search(
            src(
                """{"results":[{"id":1,"title":"Carrie","release_date":"1976-11-03","poster_path":"/a.jpg"},""" +
                    """{"id":2,"title":"Carrie","release_date":"2013-10-18"},{"id":3,"title":"Carrie Pilby","release_date":"2016-01-01"}]}""",
            ),
            Kind.MOVIE,
        )
        assertEquals(3, hits.size)
        assertEquals(2L, TmdbJson.best(hits, "EN | Carrie (2013)", 2013)?.id)
        assertEquals(1L, TmdbJson.best(hits, "Carrie", null)?.id)
        assertNull(TmdbJson.best(hits, "Carrie", 1990))
        assertNull(TmdbJson.best(hits, "Christine", null))
    }

    @Test
    fun detailsBringImdbCastAndRecommendations() {
        val d = TmdbJson.details(
            src(
                """{"id":45745,"title":"Sintel","overview":"A girl and a dragon.","backdrop_path":"/b.jpg","poster_path":"/p.jpg","imdb_id":"tt1727587",""" +
                    """"credits":{"cast":[{"id":1001,"name":"Halina Reijn","character":"Sintel","profile_path":"/h.jpg"},{"id":1002,"name":"Thom Hoffman"}]},""" +
                    """"recommendations":{"results":[{"id":133701,"title":"Tears of Steel","release_date":"2012-09-26"}]}}""",
            ),
            Kind.MOVIE,
        )
        assertEquals("tt1727587", d.imdbId)
        assertEquals("A girl and a dragon.", d.overview)
        assertEquals("/b.jpg", d.backdrop)
        assertEquals(listOf("Halina Reijn", "Thom Hoffman"), d.cast.map { it.name })
        assertEquals("/h.jpg", d.cast[0].photo)
        assertEquals(TmdbTitle(133701, Kind.MOVIE, "Tears of Steel", 2012), d.recommendations.single())
        // A series: its IMDb id comes in external_ids.
        val tv = TmdbJson.details(src("""{"id":70001,"name":"Agent 327","external_ids":{"imdb_id":"tt7000001"},"recommendations":{"results":[{"id":5,"name":"Spring","first_air_date":"2019-04-04"}]}}"""), Kind.TV)
        assertEquals("tt7000001", tv.imdbId)
        assertEquals(TmdbTitle(5, Kind.TV, "Spring", 2019), tv.recommendations.single())
    }

    @Test
    fun trendingAndPeople() {
        val t = TmdbJson.trending(
            src("""{"results":[{"id":1,"media_type":"movie","title":"Sintel","release_date":"2010-09-27"},{"id":2,"media_type":"tv","name":"Agent 327"},{"id":3,"media_type":"person","name":"Someone"}]}"""),
        )
        assertEquals(listOf(TmdbTitle(1, Kind.MOVIE, "Sintel", 2010), TmdbTitle(2, Kind.TV, "Agent 327", null)), t)
        val p = TmdbJson.person(
            src("""{"id":1001,"name":"Halina Reijn","profile_path":"/h.jpg","biography":"Dutch actress.","combined_credits":{"cast":[{"id":45745,"media_type":"movie","title":"Sintel","release_date":"2010-09-27"}]}}"""),
        )
        assertEquals("Halina Reijn", p.name)
        assertEquals("Dutch actress.", p.biography)
        assertEquals(listOf(TmdbTitle(45745, Kind.MOVIE, "Sintel", 2010)), p.credits)
    }

    @Test
    fun omdbRatings() {
        val r = OmdbJson.ratings(src("""{"Response":"True","imdbRating":"7.4","Ratings":[{"Source":"Internet Movie Database","Value":"7.4/10"},{"Source":"Rotten Tomatoes","Value":"76%"}]}"""))
        assertEquals(Ratings("7.4", "76%"), r)
        assertNull(OmdbJson.ratings(src("""{"Response":"False","Error":"Movie not found!"}""")))
        assertEquals(Ratings(null, null), OmdbJson.ratings(src("""{"Response":"True","imdbRating":"N/A"}""")))
    }

    @Test
    fun openSubtitles() {
        val hits = OpenSubtitlesJson.search(
            src(
                """{"data":[{"attributes":{"language":"en","release":"Sintel.2010.1080p","download_count":900,"hearing_impaired":false,"files":[{"file_id":111,"file_name":"a.srt"}]}},""" +
                    """{"attributes":{"language":"de","release":"Sintel German","download_count":50,"files":[{"file_id":222}]}},{"attributes":{"language":"fr","files":[]}}]}""",
            ),
        )
        assertEquals(listOf(SubHit(111, "en", "Sintel.2010.1080p", 900, false), SubHit(222, "de", "Sintel German", 50, false)), hits)
        assertEquals("https://dl.example/a.srt", OpenSubtitlesJson.downloadLink(src("""{"link":"https://dl.example/a.srt","remaining":4}""")))
        assertNull(OpenSubtitlesJson.downloadLink(src("""{"message":"You have downloaded your allowed 5 subtitles"}""")))
    }
}
