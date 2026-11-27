package app.tiviate.core.xtream

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamVodJsonTest {
    private fun src(json: String) = Buffer().writeUtf8(json)

    @Test
    fun seriesListCarriesBackdropTrailerAndLastModified() {
        val list = mutableListOf<XtreamSeries>()
        XtreamVodJson.series(
            src(
                """[{"series_id":21,"name":"Agent 327","backdrop_path":["http://x/b.png","http://x/c.png"],"youtube_trailer":"mN0zPOpADL4",""" +
                    """"last_modified":"1735689600","cast":"Agent 327, Boris","director":"Colin Levy"}]""",
            ),
        ) { list += it }
        val s = list.single()
        assertEquals("http://x/b.png", s.backdrop)
        assertEquals("mN0zPOpADL4", s.trailer)
        assertEquals(1_735_689_600_000L, s.lastModified)
        assertEquals("Agent 327, Boris", s.cast)
        assertEquals("Colin Levy", s.director)
    }

    @Test
    fun detailsCarryTheTrailerAndTheFullDate() {
        val d = XtreamVodJson.movieDetails(src("""{"info":{"youtube_trailer":"eRsGyueVLvQ","releasedate":"2010-09-27"}}"""))
        assertEquals("eRsGyueVLvQ", d.trailer)
        assertEquals("2010", d.year)
        assertEquals("2010-09-27", d.releaseDate)
    }

    @Test
    fun episodesCarryTheirAirDate() {
        val info = XtreamVodJson.seriesInfo(
            src(
                """{"episodes":{"1":[{"id":"1","episode_num":1,"info":{"air_date":"2017-05-15"}},""" +
                    """{"id":"2","episode_num":2,"info":{"releasedate":"2017-06-01 00:00:00"}},{"id":"3","episode_num":3}]}}""",
            ),
        )
        assertEquals(listOf("2017-05-15", "2017-06-01", null), info.episodes.map { it.airDate })
    }

    /** Providers that send nothing, or empty strings, for the extras. */
    @Test
    fun missingExtrasAreNull() {
        val list = mutableListOf<XtreamSeries>()
        XtreamVodJson.series(src("""[{"series_id":1,"name":"Plain","backdrop_path":[],"youtube_trailer":"","last_modified":"0","cast":"","director":null}]""")) { list += it }
        val s = list.single()
        assertNull(s.backdrop)
        assertNull(s.trailer)
        assertNull(s.lastModified)
        assertNull(s.cast)
        assertNull(s.director)
        assertNull(XtreamVodJson.movieDetails(src("""{"info":{"youtube_trailer":""}}""")).trailer)
    }

    @Test
    fun moviesToleratesStringsNumbersAndGaps() {
        val out = ArrayList<XtreamMovie>()
        val n = XtreamVodJson.movies(
            src(
                """[{"num":1,"name":" Dune ","stream_id":"501","stream_icon":"http://i/d.jpg","rating":"8.1","category_id":7,"container_extension":"mkv","added":"1700000000"},
                   {"name":"No id"},
                   {"stream_id":502,"name":"","stream_icon":"","container_extension":null}, 3]""",
            ),
        ) { out += it }
        assertEquals(2, n)
        assertEquals(XtreamMovie(501, "Dune", "http://i/d.jpg", "7", "8.1", "mkv", 1_700_000_000_000L), out[0])
        assertEquals("Film 502", out[1].name)
        assertEquals("mp4", out[1].extension)
        assertNull(out[1].poster)
    }

    @Test
    fun seriesListAndDetailsWithEpisodesInOrder() {
        val list = ArrayList<XtreamSeries>()
        XtreamVodJson.series(src("""[{"series_id":"9","name":"Dark","cover":"http://c","category_id":"3","plot":"Time.","releaseDate":"2017-12-01"}]""")) { list += it }
        assertEquals("2017", list.single().year)

        val info = XtreamVodJson.seriesInfo(
            src(
                """{"seasons":[],"info":{"name":"Dark","plot":"Time travel.","genre":"Drama","backdrop_path":["http://b1","http://b2"]},
                   "episodes":{"2":[{"id":"22","episode_num":"1","title":"S2E1","container_extension":"mp4","info":{"duration_secs":3000,"movie_image":"http://e"}}],
                               "1":[{"id":"12","episode_num":2,"title":"S1E2","container_extension":"mkv","info":[]},{"id":"11","episode_num":1,"title":"","container_extension":"mkv"}]}}""",
            ),
        )
        assertEquals("Time travel.", info.details.plot)
        assertEquals("http://b1", info.details.backdrop)
        assertEquals(listOf(11L, 12L, 22L), info.episodes.map { it.id })
        assertEquals("Episode 1", info.episodes[0].title)
        assertEquals(3000, info.episodes[2].durationSec)
        assertEquals("http://e", info.episodes[2].image)
        assertEquals(2, info.episodes[2].season)
    }

    @Test
    fun episodesAsAnArrayOfSeasons() {
        val info = XtreamVodJson.seriesInfo(src("""{"episodes":[[{"id":1,"episode_num":1,"title":"A"}],[{"id":2,"title":"B"}]]}"""))
        assertEquals(listOf(1 to 1, 2 to 1), info.episodes.map { it.season to it.episode })
    }

    @Test
    fun movieDetails() {
        val d = XtreamVodJson.movieDetails(src("""{"info":{"plot":"Sand.","cast":"Paul","releasedate":"2021-10-22","duration_secs":"9360","rating":"8"},"movie_data":{"stream_id":501}}"""))
        assertEquals(XtreamDetails(plot = "Sand.", cast = "Paul", year = "2021", durationSec = 9360, rating = "8", releaseDate = "2021-10-22"), d)
        assertEquals(XtreamDetails(), XtreamVodJson.movieDetails(src("[]")))
    }

    @Test
    fun playbackLinks() {
        val c = XtreamCredentials("http://h:8080", "u", "p w")
        assertEquals("http://h:8080/movie/u/p%20w/501.mkv", XtreamUrls.movie(c, 501, "mkv"))
        assertEquals("http://h:8080/series/u/p%20w/22.mp4", XtreamUrls.episode(c, 22, "mp4"))
    }
}
