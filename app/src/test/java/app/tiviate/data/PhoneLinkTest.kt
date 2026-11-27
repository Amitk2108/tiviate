package app.tiviate.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PhoneLinkTest {
    private val received = LinkedBlockingQueue<Map<String, String>>()
    private val link = PhoneLink(PhoneLink.Form.PLAYLIST) { received.add(it) }
    private val http = OkHttpClient()

    @After
    fun tearDown() = link.stop()

    private fun base() = "http://127.0.0.1:${link.port}"

    private fun send(vararg fields: Pair<String, String>): Int {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url(base() + "/send").post(body).build()).execute().use { it.code }
    }

    @Test
    fun thePhonePageAsksForTheCodeAndThePlaylist() {
        assertTrue(link.start())
        assertTrue(link.port in 8765..8775)
        val page = http.newCall(Request.Builder().url(base() + "/?code=1234").build()).execute().use { it.body!!.string() }
        assertTrue(page.contains("name=\"code\""))
        assertTrue(page.contains("Xtream") && page.contains("M3U"))
    }

    @Test
    fun onlyTheRightCodeIsAccepted() {
        assertTrue(link.start())
        assertEquals(403, send("code" to ("0000".takeIf { it != link.code.value } ?: "1111"), "type" to "xtream"))
        assertTrue(received.isEmpty())
        assertEquals(200, send("code" to link.code.value, "type" to "xtream", "server" to "http://p.example:8080", "username" to "u", "password" to "p w&x"))
        val fields = received.poll(5, TimeUnit.SECONDS)!!
        assertEquals("xtream", fields["type"])
        assertEquals("p w&x", fields["password"])
        assertTrue("code" !in fields)
    }

    /** Guessing: after 5 wrong codes the TV shows a new one. */
    @Test
    fun wrongCodesChangeTheCode() {
        assertTrue(link.start())
        val first = link.code.value
        repeat(5) { send("code" to (if (first == "9999") "8888" else "9999")) }
        assertNotEquals(first, link.code.value)
    }

    @Test
    fun formsAreDecoded() {
        assertEquals(mapOf("a" to "x y", "b" to "é&=", "c" to ""), PhoneLink.parseForm("a=x+y&b=%C3%A9%26%3D&c="))
    }
}
