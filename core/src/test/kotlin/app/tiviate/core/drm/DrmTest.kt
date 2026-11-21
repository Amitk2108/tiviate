package app.tiviate.core.drm

import app.tiviate.core.m3u.M3uEntry
import app.tiviate.core.m3u.M3uParser
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrmTest {
    private val kid = "a7e61c373e219033c21091fa607bf3b8"
    private val key = "76a6c65c5ea762046bd749a2e632ccbb"

    private fun parse(text: String): List<M3uEntry> {
        val out = ArrayList<M3uEntry>()
        M3uParser.parse(Buffer().writeUtf8(text)) { out += it }
        return out
    }

    @Test
    fun kodiPropertiesBeforeOrAfterExtinfBelongToTheirChannel() {
        val list = parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=$kid:$key
            #KODIPROP:inputstream.adaptive.manifest_type=MPD
            #EXTINF:-1 tvg-id="a",Protected One
            https://cdn.example/one/manifest
            #EXTINF:-1,Protected Two
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example/wv?t=1|User-Agent=Box&X-Token=a%20b
            https://cdn.example/two.mpd
            #EXTINF:-1,Plain
            https://cdn.example/three.m3u8
            #KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|$kid:$key
            https://cdn.example/four.mpd
            """.trimIndent(),
        )
        assertEquals(listOf("clearkey", "widevine", null, "clearkey"), list.map { it.drmScheme })
        assertEquals("$kid:$key", list[0].drmLicense)
        assertEquals("mpd", list[0].manifestType)
        assertEquals("https://lic.example/wv?t=1|User-Agent=Box&X-Token=a%20b", list[1].drmLicense)
        assertNull("no leftovers on the next channel", list[2].drmLicense)
        assertEquals("$kid:$key", list[3].drmLicense)
    }

    @Test
    fun licenceFormats() {
        assertEquals(DrmLicense(url = "https://lic.example/wv?t=1", headers = mapOf("User-Agent" to "Box", "X-Token" to "a b")), Drm.license("https://lic.example/wv?t=1|User-Agent=Box&X-Token=a%20b"))
        assertEquals(mapOf(kid to key), Drm.license("$kid:$key")?.clearKeys)
        assertEquals(mapOf(kid to key), Drm.license("a7e61c37-3e21-9033-c210-91fa607bf3b8:${key.uppercase()}")?.clearKeys)
        assertEquals(mapOf(kid to key), Drm.license("""{"$kid":"$key"}""")?.clearKeys)
        // A JWK set (base64url).
        assertEquals(mapOf(kid to key), Drm.license("""{"keys":[{"kty":"oct","k":"dqbGXF6nYgRr10mi5jLMuw","kid":"p-YcNz4hkDPCEJH6YHvzuA"}],"type":"temporary"}""")?.clearKeys)
        assertNull(Drm.license(" "))
        assertNull(Drm.license("not a key"))
    }

    @Test
    fun clearKeyResponseAndPssh() {
        assertEquals(
            """{"keys":[{"kty":"oct","k":"dqbGXF6nYgRr10mi5jLMuw","kid":"p-YcNz4hkDPCEJH6YHvzuA"}],"type":"temporary"}""",
            Drm.clearKeyResponse(mapOf(kid to key)),
        )
        val box = Drm.commonPssh(listOf(kid))
        assertEquals(52, box.size)
        assertEquals("pssh", String(box, 4, 4))
        assertEquals(kid, box.copyOfRange(32, 48).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun schemes() {
        assertEquals(listOf("widevine", "clearkey", "playready", null), listOf("com.widevine.alpha", "org.w3.clearkey", "com.microsoft.playready", "fairplay").map(Drm::scheme))
    }
}
