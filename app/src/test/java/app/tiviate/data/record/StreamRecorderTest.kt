package app.tiviate.data.record

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRecorderTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, () -> MockResponse>()
    private var now = 0L
    private val recorder = StreamRecorder(OkHttpClient(), clock = { now }, pause = { now += it })

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.requestUrl?.encodedPath.orEmpty()]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body(text: String, type: String = "application/octet-stream") = MockResponse().setBody(text).setHeader("Content-Type", type)

    private fun record(path: String, untilMs: Long): Pair<String, String> {
        val out = ByteArrayOutputStream()
        var ext = ""
        runBlocking { recorder.record(server.url(path).toString(), "Tiviate-test", emptyMap(), untilMs, { e -> ext = e; out }) }
        return ext to out.toString(Charsets.UTF_8)
    }

    @Test
    fun aPlainStreamIsReopenedWhenItDropsUntilTheEndTime() {
        routes["/live/1.ts"] = { body("TSDATA", "video/mp2t") } // each connection ends after a few bytes
        val (ext, data) = record("/live/1.ts", untilMs = 7_000)
        assertEquals("ts", ext)
        assertEquals("three connections (0 s, 3 s, 6 s)", "TSDATA".repeat(3), data)
    }

    @Test
    fun hlsFollowsTheBestVariantAndAppendsSegmentsOnce() {
        routes["/hls/master.m3u8"] = {
            body("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=200000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nhigh/index.m3u8\n")
        }
        var refresh = 0
        routes["/hls/high/index.m3u8"] = {
            refresh++
            // A live window that moves on by one segment, then ends.
            if (refresh == 1) body("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:4,\ns10.ts\n#EXTINF:4,\ns11.ts\n")
            else body("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:11\n#EXTINF:4,\ns11.ts\n#EXTINF:4,\ns12.ts\n#EXT-X-ENDLIST\n")
        }
        listOf(10, 11, 12).forEach { n -> routes["/hls/high/s$n.ts"] = { body("[$n]") } }
        val (ext, data) = record("/hls/master.m3u8", untilMs = 60_000)
        assertEquals("ts", ext)
        assertEquals("[10][11][12]", data)
    }

    @Test
    fun fmp4SegmentsStartWithTheirInitSection() {
        routes["/v/index.m3u8"] = { body("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST\n") }
        routes["/v/init.mp4"] = { body("<init>") }
        routes["/v/a.m4s"] = { body("<a>") }
        val (ext, data) = record("/v/index.m3u8", untilMs = 60_000)
        assertEquals("mp4", ext)
        assertEquals("<init><a>", data)
    }

    @Test
    fun encryptedStreamsAreRefused() {
        routes["/e/index.m3u8"] = { body("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n") }
        val error = runCatching { record("/e/index.m3u8", untilMs = 60_000) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("encrypted"))
    }

    @Test
    fun playlistParsing() {
        val m = Hls.media("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttp://cdn/x.ts?t=1\n#EXTINF:6,\ny.ts\n", "http://h/a/list.m3u8?token=9")
        assertEquals(listOf(5L to "http://cdn/x.ts?t=1", 6L to "http://h/a/y.ts"), m.segments.map { it.sequence to it.uri })
        assertEquals(6_000L, m.targetDurationMs)
        assertEquals("http://h/a/high.m3u8", Hls.bestVariant("#EXT-X-STREAM-INF:BANDWIDTH=5\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=50\nhigh.m3u8", "http://h/a/m.m3u8"))
    }
}
