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

    fun start(): FixtureServer {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val query = request.requestUrl?.queryParameter("action")
                return when {
                    path == "/player_api.php" && query == "get_live_categories" ->
                        MockResponse().setBody("""[{"category_id":"1","category_name":"News & Sports"},{"category_id":"2","category_name":"Entertainment"}]""")
                    path == "/player_api.php" && query == "get_live_streams" -> MockResponse().setBody(xtreamStreams())
                    path == "/player_api.php" -> MockResponse().setBody(
                        """{"user_info":{"auth":1,"status":"Active","exp_date":"1924992000","max_connections":"2","active_cons":"0","allowed_output_formats":["m3u8"]}}""",
                    )
                    path == "/xmltv.php" -> MockResponse().setBody(guide())
                    path.startsWith("/live/u/p/") -> {
                        val rest = path.removePrefix("/live/u/p/")
                        if (rest.matches(Regex("""\d+\.m3u8"""))) asset("fixture/hls/master.m3u8") else asset("fixture/hls/$rest")
                    }
                    path == "/list.m3u" -> MockResponse().setBody(playlist())
                    path == "/epg.xml" -> MockResponse().setBody(guide())
                    path.startsWith("/hls/") -> asset("fixture" + path)
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
            appendLine("""#EXTINF:-1 tvg-id="ch$i" tvg-chno="${i + 1}" group-title="$group",$name""")
            appendLine(url("/hls/master.m3u8") + "?ch=$i")
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
            path.endsWith(".vtt") -> "text/vtt"
            else -> "application/octet-stream"
        }
        MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))
    } catch (e: java.io.IOException) {
        MockResponse().setResponseCode(404)
    }
}
