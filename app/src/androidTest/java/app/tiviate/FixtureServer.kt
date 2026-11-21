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
                return when {
                    path == "/player_api.php" && query == "get_live_categories" ->
                        MockResponse().setBody("""[{"category_id":"1","category_name":"News & Sports"},{"category_id":"2","category_name":"Entertainment"}]""")
                    path == "/player_api.php" && query == "get_live_streams" -> MockResponse().setBody(xtreamStreams())
                    // Movies & Series: one film and one two-episode series, all on the bundled test stream.
                    path == "/player_api.php" && query == "get_vod_categories" -> MockResponse().setBody("""[{"category_id":"10","category_name":"Films"}]""")
                    path == "/player_api.php" && query == "get_vod_streams" ->
                        MockResponse().setBody("""[{"stream_id":501,"name":"Test Film","category_id":"10","container_extension":"m3u8","stream_icon":""}]""")
                    path == "/player_api.php" && query == "get_vod_info" -> MockResponse().setBody("""{"info":{"plot":"A short test film.","genre":"Drama","duration_secs":12}}""")
                    path == "/player_api.php" && query == "get_series_categories" -> MockResponse().setBody("""[{"category_id":"20","category_name":"Shows"}]""")
                    path == "/player_api.php" && query == "get_series" -> MockResponse().setBody("""[{"series_id":9,"name":"Test Show","category_id":"20","plot":"A test show."}]""")
                    path == "/player_api.php" && query == "get_series_info" -> MockResponse().setBody(
                        """{"info":{"plot":"A test show."},"episodes":{"1":[{"id":"701","episode_num":1,"title":"Pilot","container_extension":"m3u8"},""" +
                            """{"id":"702","episode_num":2,"title":"Second","container_extension":"m3u8"}]}}""",
                    )
                    path == "/player_api.php" -> MockResponse().setBody(
                        """{"user_info":{"auth":1,"status":"Active","exp_date":"1924992000","max_connections":"2","active_cons":"0","allowed_output_formats":["m3u8"]}}""",
                    )
                    path == "/xmltv.php" -> MockResponse().setBody(guide())
                    path.startsWith("/live/u/p/") || path.startsWith("/movie/u/p/") || path.startsWith("/series/u/p/") -> {
                        val rest = path.substringAfter("/u/p/")
                        if (rest.matches(Regex("""\d+\.m3u8"""))) asset("fixture/hls/master.m3u8") else asset("fixture/hls/$rest")
                    }
                    path == "/list.m3u" -> MockResponse().setBody(playlist())
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

    private fun guide(): String {
        val now = System.currentTimeMillis()
        val slot = 30 * 60_000L
        val start = now - now % slot - 2 * slot
        fun fmt(ms: Long) = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?><tv>""")
            channelNames.forEachIndexed { i, name -> appendLine("""<channel id="ch$i"><display-name>$name</display-name></channel>""") }
            channelNames.forEachIndexed { i, name ->
                for (k in 0 until 16) {
                    val s = start + k * slot
                    appendLine("""<programme start="${fmt(s)}" stop="${fmt(s + slot)}" channel="ch$i"><title>$name Show ${k + 1}</title><desc>Episode ${k + 1} of $name.</desc></programme>""")
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
        /** The test stream's ClearKey key ID and key. */
        const val DRM_KID = "a7e61c373e219033c21091fa607bf3b8"
        const val DRM_KEY = "76a6c65c5ea762046bd749a2e632ccbb"
    }
}
