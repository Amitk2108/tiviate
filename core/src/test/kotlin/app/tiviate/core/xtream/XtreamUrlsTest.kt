package app.tiviate.core.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamUrlsTest {

    private val creds = XtreamCredentials("http://host:8080", "us er", "p&ss")

    @Test
    fun normalizeServerAddsSchemeAndStripsPaths() {
        assertEquals("http://host:8080", XtreamUrls.normalizeServer("host:8080"))
        assertEquals("http://h:80", XtreamUrls.normalizeServer("http://h:80/"))
        assertEquals("http://h", XtreamUrls.normalizeServer("http://h/player_api.php?username=x"))
        assertEquals("https://h", XtreamUrls.normalizeServer("https://h/get.php?username=a&password=b&type=m3u"))
        assertEquals("https://h", XtreamUrls.normalizeServer("  https://h  "))
    }

    @Test
    fun apiUrlEncodesCredentials() {
        assertEquals("http://host:8080/player_api.php?username=us+er&password=p%26ss", XtreamUrls.api(creds))
        assertEquals(
            "http://host:8080/player_api.php?username=us+er&password=p%26ss&action=get_live_streams",
            XtreamUrls.api(creds, "get_live_streams"),
        )
    }

    @Test
    fun liveAndXmltvUrls() {
        val c = XtreamCredentials("http://h", "u", "p")
        assertEquals("http://h/live/u/p/42.ts", XtreamUrls.live(c, 42))
        assertEquals("http://h/live/u/p/42.m3u8", XtreamUrls.live(c, 42, "m3u8"))
        assertEquals("http://h/xmltv.php?username=u&password=p", XtreamUrls.xmltv(c))
    }

    @Test
    fun liveUrlEncodesCredentialsAsPathSegments() {
        val c = XtreamCredentials("http://h", "us er", "p#?/%&+")
        assertEquals("http://h/live/us%20er/p%23%3F%2F%25%26%2B/42.ts", XtreamUrls.live(c, 42))
    }

    @Test
    fun redactHidesPasswordsInQueryAndPath() {
        assertEquals(
            "http://h/xmltv.php?username=u&password=•••",
            XtreamUrls.redact("http://h/xmltv.php?username=u&password=s3cr%26t"),
        )
        assertEquals("http://h/get.php?password=•••&type=m3u", XtreamUrls.redact("http://h/get.php?password=x&type=m3u"))
        assertEquals("http://h/live/u/•••/42.ts", XtreamUrls.redact("http://h/live/u/s3cret/42.ts"))
        assertEquals("http://h/epg.xml.gz", XtreamUrls.redact("http://h/epg.xml.gz"))
    }

    @Test
    fun preferredExtensionFollowsAllowedFormats() {
        assertEquals("m3u8", XtreamUrls.preferredExtension(listOf("m3u8"), preferHls = false))
        assertEquals("ts", XtreamUrls.preferredExtension(listOf("m3u8", "ts"), preferHls = false))
        assertEquals("m3u8", XtreamUrls.preferredExtension(listOf("m3u8", "ts"), preferHls = true))
        assertEquals("ts", XtreamUrls.preferredExtension(emptyList(), preferHls = false))
    }
}
