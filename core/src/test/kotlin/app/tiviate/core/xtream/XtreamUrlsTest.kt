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
    fun preferredExtensionFollowsAllowedFormats() {
        assertEquals("m3u8", XtreamUrls.preferredExtension(listOf("m3u8"), preferHls = false))
        assertEquals("ts", XtreamUrls.preferredExtension(listOf("m3u8", "ts"), preferHls = false))
        assertEquals("m3u8", XtreamUrls.preferredExtension(listOf("m3u8", "ts"), preferHls = true))
        assertEquals("ts", XtreamUrls.preferredExtension(emptyList(), preferHls = false))
    }
}
