package app.tiviate.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.sync.HttpClients
import app.tiviate.ui.images.IMAGE_USER_AGENT
import app.tiviate.ui.images.imageHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageClientTest {

    @Test
    fun imageClientFollowsRedirectsWithBrowserUserAgent() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/real/logo").toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png"))
        server.start()
        try {
            val client = imageHttpClient(HttpClients.create())
            val response = client.newCall(Request.Builder().url(server.url("/logo")).build()).execute()
            assertEquals(200, response.code)
            assertEquals("png", response.body.string())
            assertEquals(IMAGE_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
            assertEquals(IMAGE_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }
}
