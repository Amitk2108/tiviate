package app.tiviate

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Local HTTP server for instrumented tests. Serves a small playlist whose channels all point at a
 * bundled HLS fixture (2 audio tracks + 1 subtitle track), plus a matching XMLTV guide.
 */
class FixtureServer {
    private val server = MockWebServer()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    /** When true every stream answers 404, so tests can bring up the error card. */
    @Volatile var failStreams = false

    /** When true the guide answers 404, so tests can deliver it later. */
    @Volatile var failGuide = false

    fun start(): FixtureServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val query = request.requestUrl?.queryParameter("action")
                val vodId = request.requestUrl?.queryParameter("vod_id")?.toLongOrNull()
                val seriesId = request.requestUrl?.queryParameter("series_id")?.toLongOrNull()
                return when {
                    path == "/player_api.php" && query == "get_live_categories" ->
                        MockResponse().setBody("""[{"category_id":"1","category_name":"News & Sports"},{"category_id":"2","category_name":"Entertainment"}]""")
                    path == "/player_api.php" && query == "get_live_streams" -> MockResponse().setBody(xtreamStreams())
                    // Movies & Series, all on the bundled test stream: "Test Film" and "Test Show" first, then open films by the
                    // Blender Foundation (CC-BY) as a realistic library: backdrops, trailers, episode pictures, a very long series.
                    path == "/player_api.php" && query == "get_vod_categories" ->
                        MockResponse().setBody("""[{"category_id":"10","category_name":"Films"},{"category_id":"11","category_name":"Open Films"},{"category_id":"12","category_name":"Shorts"}]""")
                    path == "/player_api.php" && query == "get_vod_streams" -> MockResponse().setBody(vodStreams())
                    path == "/player_api.php" && query == "get_vod_info" -> MockResponse().setBody(vodInfo(vodId ?: 0))
                    path == "/player_api.php" && query == "get_series_categories" ->
                        MockResponse().setBody("""[{"category_id":"20","category_name":"Shows"},{"category_id":"21","category_name":"Animation"}]""")
                    path == "/player_api.php" && query == "get_series" -> MockResponse().setBody(seriesList())
                    path == "/player_api.php" && query == "get_series_info" -> MockResponse().setBody(seriesInfo(seriesId ?: 0))
                    path.startsWith("/tmdb/3/") || path.startsWith("/omdb/") || path.startsWith("/os/api/v1/") -> online(request, path)
                    path.startsWith("/img/") -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image(path)))
                    path == "/player_api.php" -> MockResponse().setBody(
                        """{"user_info":{"auth":1,"status":"Active","exp_date":"1924992000","max_connections":"2","active_cons":"0","allowed_output_formats":["m3u8"]}}""",
                    )
                    path == "/xmltv.php" -> MockResponse().setBody(guide())
                    path.startsWith("/live/u/p/") || path.startsWith("/movie/u/p/") || path.startsWith("/series/u/p/") -> {
                        val rest = path.substringAfter("/u/p/")
                        if (rest.matches(Regex("""\d+\.m3u8"""))) asset("fixture/hls/master.m3u8") else asset("fixture/hls/$rest")
                    }
                    path == "/list.m3u" -> MockResponse().setBody(playlist())
                    // GitHub issue #4: the reporter's playlist line (Cyrillic, no tvg-id), on a server that answers the
                    // archive- and index- links with a page instead of an error code, as theirs does.
                    path == "/flussonic.m3u" -> MockResponse().setBody(
                        "#EXTM3U url-tvg=\"${url("/ru-epg.xml")}\"\n" +
                            "#EXTINF:0 catchup=\"flussonic\" catchup-days=\"3\" tvg-name=\"НТВ HD\" group-title=\"Россия\",НТВ HD\n" +
                            "${url("/channel/n595c8c2e/index.m3u8")}?q=a\n",
                    )
                    path == "/ru-epg.xml" -> MockResponse().setBody(guide(listOf("perviy" to "Первый канал HD", "ntv" to "НТВ")))
                    path.matches(Regex("""/channel/n595c8c2e/(index|timeshift_abs-\d+)\.m3u8""")) -> asset("fixture/hls/master.m3u8")
                    path.matches(Regex("""/channel/n595c8c2e/(archive|index)-\d+-\d+\.m3u8""")) ->
                        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<h1>Forbidden</h1>")
                    path.startsWith("/channel/n595c8c2e/") -> asset("fixture/hls/" + path.removePrefix("/channel/n595c8c2e/"))
                    // DRM: a ClearKey-protected DASH stream (12 s), named only as "ClearKey" in its manifest.
                    path == "/drm.m3u" -> MockResponse().setBody(drmPlaylist())
                    path == "/drm/manifest" -> asset("fixture/drm/manifest.mpd")
                    path.startsWith("/drm/") -> asset("fixture" + path)
                    path == "/big.m3u" -> MockResponse().setBody(bigPlaylist())
                    path == "/epg.xml" -> if (failGuide) MockResponse().setResponseCode(404) else MockResponse().setBody(guide())
                    path.startsWith("/hls/") -> if (failStreams) MockResponse().setResponseCode(404) else asset("fixture" + path)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return this
    }

    fun url(path: String): String = server.url(path).toString()

    private fun vodStreams() = listOf(
        """{"stream_id":501,"name":"Test Film","category_id":"10","container_extension":"m3u8","stream_icon":""}""",
        film(511, "Big Buck Bunny", 11, 1_704_067_200, "6.4"),
        film(512, "Sintel", 11, 1_709_251_200, "7.4"),
        film(513, "Tears of Steel", 11, 1_717_200_000, "6.1"),
        film(514, "Elephants Dream", 12, 1_672_531_200, "6.5"),
    ).joinToString(",", "[", "]")

    private fun film(id: Int, name: String, category: Int, added: Long, rating: String) =
        """{"stream_id":$id,"name":"$name","category_id":"$category","container_extension":"m3u8","stream_icon":"${url("/img/poster_$id.png")}","added":"$added","rating":"$rating"}"""

    /** The film's details; 514 has neither backdrop nor trailer (a provider that sends none). */
    private fun vodInfo(id: Long): String = when (id) {
        511L -> filmInfo("A big, gentle rabbit gets even with three forest bullies.", "Animation, Comedy", "2008-04-10", 596, "YE7VzlLtp-4", "Sacha Goedegebure", "Frank, Rinky, Gimera", "bbb")
        512L -> filmInfo("A young woman searches a harsh land for the baby dragon she raised.", "Animation, Fantasy", "2010-09-27", 888, "eRsGyueVLvQ", "Colin Levy", "Halina Reijn, Thom Hoffman", "sintel")
        513L -> filmInfo("Scientists in a future Amsterdam try to save the world from robots.", "Science Fiction", "2012-09-26", 734, "R6MlUcmOul8", "Ian Hubert", "Derek de Lint, Sergio Hasselbaink", "tos")
        514L -> """{"info":{"plot":"Two men wander through an endless machine.","genre":"Animation","releasedate":"2006-03-24","duration_secs":654}}"""
        else -> """{"info":{"plot":"A short test film.","genre":"Drama","duration_secs":12}}"""
    }

    private fun filmInfo(plot: String, genre: String, date: String, secs: Int, trailer: String, director: String, cast: String, art: String) =
        """{"info":{"plot":"$plot","genre":"$genre","releasedate":"$date","duration_secs":$secs,"youtube_trailer":"$trailer",""" +
            """"director":"$director","cast":"$cast","backdrop_path":["${url("/img/back_$art.png")}"]}}"""

    private fun seriesList() = listOf(
        """{"series_id":9,"name":"Test Show","category_id":"20","plot":"A test show."}""",
        """{"series_id":21,"name":"Agent 327","category_id":"21","plot":"A secret agent on missions across Europe.","cover":"${url("/img/poster_s21.png")}",""" +
            """"genre":"Animation, Action","releaseDate":"2017-05-15","rating":"8","cast":"Agent 327, Boris","director":"Colin Levy",""" +
            """"backdrop_path":["${url("/img/back_agent.png")}"],"youtube_trailer":"mN0zPOpADL4","last_modified":"$SERIES_UPDATED"}""",
        """{"series_id":22,"name":"Long Show","category_id":"20","plot":"It goes on and on.","genre":"Drama","cover":"${url("/img/poster_s22.png")}"}""",
    ).joinToString(",", "[", "]")

    private fun seriesInfo(id: Long): String = when (id) {
        21L -> """{"info":{"plot":"A secret agent on missions across Europe."},"episodes":{""" +
            """"1":[${episode(2101, 1, 1, "The Barbershop", "A hidden lab under a barbershop.", "2017-05-15")},""" +
            """${episode(2102, 1, 2, "Operation Lisbon", "A chase through Lisbon's old town.", "2017-06-01")},""" +
            """${episode(2103, 1, 3, "Double Agent", "Nobody is who they seem.", "2017-06-15")}],""" +
            """"2":[${episode(2201, 2, 1, "Return of Boris", "An old enemy comes back.", "2018-05-01")},""" +
            """${episode(2202, 2, 2, "Final Mission", "Everything ends in Venice.", "2018-05-15")}]}}"""
        // 25 seasons of 40 episodes: very long series.
        22L -> (1..25).joinToString(",", """{"info":{"plot":"It goes on and on."},"episodes":{""", "}}") { s ->
            (1..40).joinToString(",", "\"$s\":[", "]") { e -> """{"id":"${22_000 + s * 100 + e}","episode_num":$e,"season":$s,"title":"Part $e","container_extension":"m3u8"}""" }
        }
        else -> """{"info":{"plot":"A test show."},"episodes":{"1":[{"id":"701","episode_num":1,"title":"Pilot","container_extension":"m3u8"},""" +
            """{"id":"702","episode_num":2,"title":"Second","container_extension":"m3u8"}]}}"""
    }

    private fun episode(id: Int, season: Int, number: Int, title: String, plot: String, date: String) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"$title","container_extension":"m3u8",""" +
            """"info":{"movie_image":"${url("/img/ep_$id.png")}","plot":"$plot","air_date":"$date","duration_secs":${600 + number}}}"""

    /** How often the online services were asked (tests check that nothing is sent without a key). */
    val onlineRequests = java.util.concurrent.atomic.AtomicInteger()

    private fun online(request: RecordedRequest, path: String): MockResponse {
        onlineRequests.incrementAndGet()
        if (path == "/os/api/v1/file.srt") return MockResponse().setHeader("Content-Type", "application/x-subrip").setBody(
            "1\n00:00:00,500 --> 00:00:04,000\nHello from the internet\n\n2\n00:00:04,500 --> 00:00:08,000\nSecond line\n\n3\n00:00:08,500 --> 00:00:11,000\nThe end\n",
        )
        val u = request.requestUrl!!
        val key = u.queryParameter("api_key") ?: u.queryParameter("apikey") ?: request.getHeader("Api-Key")
        if (key != "good-key") return MockResponse().setResponseCode(401).setBody("""{"status_message":"Invalid API key","Error":"Invalid API key!"}""")
        val query = u.queryParameter("query").orEmpty()
        fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
        return when {
            path == "/tmdb/3/configuration" -> json("""{"images":{}}""")
            path == "/tmdb/3/search/movie" -> json(
                when (query) {
                    "Sintel" -> """{"results":[{"id":45745,"title":"Sintel","release_date":"2010-09-27"}]}"""
                    "Tears of Steel" -> """{"results":[{"id":133701,"title":"Tears of Steel","release_date":"2012-09-26"}]}"""
                    else -> """{"results":[]}"""
                },
            )
            path == "/tmdb/3/search/tv" -> json(if (query == "Agent 327") """{"results":[{"id":70001,"name":"Agent 327","first_air_date":"2017-05-15"}]}""" else """{"results":[]}""")
            path == "/tmdb/3/movie/45745" -> json(
                """{"id":45745,"title":"Sintel","overview":"A girl and her dragon.","imdb_id":"tt1727587",""" +
                    """"credits":{"cast":[{"id":1001,"name":"Halina Reijn","character":"Sintel","profile_path":"/halina.png"},{"id":1002,"name":"Thom Hoffman","character":"Shaman"}]},""" +
                    """"recommendations":{"results":[{"id":133701,"title":"Tears of Steel","release_date":"2012-09-26"}]}}""",
            )
            path == "/tmdb/3/movie/133701" -> json("""{"id":133701,"title":"Tears of Steel","imdb_id":"tt2285752","credits":{"cast":[]},"recommendations":{"results":[]}}""")
            path == "/tmdb/3/tv/70001" -> json("""{"id":70001,"name":"Agent 327","external_ids":{"imdb_id":"tt7000001"},"credits":{"cast":[]},"recommendations":{"results":[]}}""")
            path == "/tmdb/3/trending/all/day" -> json(
                """{"results":[{"id":45745,"media_type":"movie","title":"Sintel","release_date":"2010-09-27"},""" +
                    """{"id":9,"media_type":"movie","title":"A Film Nobody Has","release_date":"2026-01-01"},{"id":70001,"media_type":"tv","name":"Agent 327","first_air_date":"2017-05-15"}]}""",
            )
            path == "/tmdb/3/person/1001" -> json(
                """{"id":1001,"name":"Halina Reijn","profile_path":"/halina.png","biography":"A Dutch actress.",""" +
                    """"combined_credits":{"cast":[{"id":45745,"media_type":"movie","title":"Sintel","release_date":"2010-09-27"},{"id":5,"media_type":"movie","title":"Not In The Playlist"}]}}""",
            )
            path == "/omdb/" -> json(
                if (u.queryParameter("i") == "tt1727587") """{"Response":"True","imdbRating":"7.4","Ratings":[{"Source":"Rotten Tomatoes","Value":"76%"}]}"""
                else """{"Response":"False","Error":"Incorrect IMDb ID."}""",
            )
            path == "/os/api/v1/subtitles" -> json(
                """{"data":[{"attributes":{"language":"en","release":"Sintel.2010.Open.Movie","download_count":900,"files":[{"file_id":111}]}},""" +
                    """{"attributes":{"language":"de","release":"Sintel German","download_count":50,"files":[{"file_id":222}]}}]}""",
            )
            path == "/os/api/v1/download" ->
                if (request.body.readUtf8().contains("\"file_id\":1}")) MockResponse().setResponseCode(400).setBody("""{"message":"Invalid file_id"}""")
                else json("""{"link":"${url("/os/api/v1/file.srt")}","remaining":4}""")
            path == "/os/api/v1/file.srt" -> MockResponse().setHeader("Content-Type", "application/x-subrip").setBody(
                "1\n00:00:00,500 --> 00:00:04,000\nHello from the internet\n\n2\n00:00:04,500 --> 00:00:08,000\nSecond line\n\n3\n00:00:08,500 --> 00:00:11,000\nThe end\n",
            )
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** A small generated picture (a colour from its name), so posters, backdrops and stills load like real ones. */
    private fun image(path: String): ByteArray {
        val wide = path.contains("back_") || path.contains("ep_")
        val bitmap = android.graphics.Bitmap.createBitmap(if (wide) 64 else 40, if (wide) 36 else 60, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF000000.toInt() or (path.hashCode() and 0x7F7F7F))
        return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun shutdown() = server.shutdown()

    val channelNames = listOf("Aurora News", "Beacon Sports", "Cobalt Movies", "Delta Kids", "Ember Music")

    private fun playlist() = buildString {
        appendLine("""#EXTM3U url-tvg="${url("/epg.xml")}"""")
        channelNames.forEachIndexed { i, name ->
            val group = if (i < 2) "News & Sports" else "Entertainment"
            // Aurora News keeps a 3-day archive (catch-up), served by the same test stream.
            val archive = if (i == 0) """ catchup="default" catchup-source="${url("/hls/master.m3u8")}?archive={utc}-{duration}" catchup-days="3"""" else ""
            appendLine("""#EXTINF:-1 tvg-id="ch$i" tvg-chno="${i + 1}" group-title="$group"$archive,$name""")
            appendLine(url("/hls/master.m3u8") + "?ch=$i")
        }
    }

    /** [BIG_COUNT] channels in one list ("Big 1"…), all on the bundled HLS stream. */
    /** The right keys, then a wrong key ID; the links have no ".mpd", so the playlist says it's DASH. */
    private fun drmPlaylist() = buildString {
        appendLine("#EXTM3U")
        appendLine("#KODIPROP:inputstream.adaptive.license_type=clearkey")
        appendLine("#KODIPROP:inputstream.adaptive.license_key=$DRM_KID:$DRM_KEY")
        appendLine("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
        appendLine("""#EXTINF:-1 tvg-chno="1",Locked Box""")
        appendLine(url("/drm/manifest"))
        appendLine("""#EXTINF:-1 tvg-chno="2",Wrong Key""")
        appendLine("#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey")
        appendLine("#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:$DRM_KEY")
        appendLine("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
        appendLine(url("/drm/manifest") + "?wrong=1")
    }

    private fun bigPlaylist() = buildString {
        appendLine("#EXTM3U")
        for (i in 1..BIG_COUNT) {
            appendLine("""#EXTINF:-1 tvg-chno="$i",Big $i""")
            appendLine(url("/hls/master.m3u8") + "?big=$i")
        }
    }

    private fun xtreamStreams() = channelNames.mapIndexed { i, name ->
        """{"num":${i + 1},"name":"$name","stream_id":${i + 1},"stream_icon":"","epg_channel_id":"ch$i","category_id":"${if (i < 2) 1 else 2}","tv_archive":0}"""
    }.joinToString(",", "[", "]")

    private fun guide(channels: List<Pair<String, String>> = channelNames.mapIndexed { i, name -> "ch$i" to name }): String {
        val now = System.currentTimeMillis()
        val slot = 30 * 60_000L
        val start = now - now % slot - 2 * slot
        fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?><tv>""")
            channels.forEach { (id, name) -> appendLine("""<channel id="$id"><display-name>$name</display-name></channel>""") }
            channels.forEach { (id, name) ->
                for (k in 0 until 16) {
                    val s = start + k * slot
                    appendLine("""<programme start="${fmt(s)}" stop="${fmt(s + slot)}" channel="$id"><title>$name Show ${k + 1}</title><desc>Episode ${k + 1} of $name.</desc></programme>""")
                }
            }
            appendLine("</tv>")
        }
    }

    private fun asset(path: String): MockResponse = try {
        val bytes = assets.open(path).use { it.readBytes() }
        val type = when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4s") -> "video/mp4"
            path.endsWith(".vtt") -> "text/vtt"
            else -> "application/octet-stream"
        }
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))
    } catch (e: java.io.IOException) {
        MockResponse().setResponseCode(404)
    }

    companion object {
        const val BIG_COUNT = 400
        /** Films and series in the fixture's Movies & Series (5 + 3). */
        const val LIBRARY_COUNT = 8
        /** When "Agent 327" last got episodes (seconds, as Xtream sends it). */
        const val SERIES_UPDATED = 1_735_689_600L
        /** The test stream's ClearKey key ID and key. */
        const val DRM_KID = "a7e61c373e219033c21091fa607bf3b8"
        const val DRM_KEY = "76a6c65c5ea762046bd749a2e632ccbb"
    }
}
