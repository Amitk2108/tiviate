package app.tiviate.core.xtream

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamJsonTest {

    private fun src(json: String) = Buffer().writeUtf8(json)

    @Test
    fun accountParsesUserInfo() {
        val a = XtreamJson.account(
            src(
                """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1798761600",
                "is_trial":"0","active_cons":"1","max_connections":"2","allowed_output_formats":["m3u8"]},
                "server_info":{"url":"h","port":"80","timezone":"Asia/Kolkata"}}""",
            ),
        )
        assertTrue(a.authorized)
        assertEquals("Active", a.status)
        assertEquals(1_798_761_600_000L, a.expiresAtMs)
        assertEquals(2, a.maxConnections)
        assertEquals(1, a.activeConnections)
        assertEquals(listOf("m3u8"), a.allowedFormats)
    }

    @Test
    fun accountWithAuthZeroIsUnauthorized() {
        val a = XtreamJson.account(src("""{"user_info":{"auth":0}}"""))
        assertFalse(a.authorized)
        assertNull(a.expiresAtMs)
    }

    @Test
    fun categoriesParse() {
        val c = XtreamJson.categories(src("""[{"category_id":"11","category_name":"News","parent_id":0},{"category_id":12,"category_name":"Sports"}]"""))
        assertEquals(listOf(XtreamCategory("11", "News"), XtreamCategory("12", "Sports")), c)
    }

    @Test
    fun liveStreamsToleratesStringOrNumberFields() {
        val out = mutableListOf<XtreamLiveStream>()
        val n = XtreamJson.liveStreams(
            src(
                """[
                {"num":1,"name":"Colors HD","stream_type":"live","stream_id":6775,"stream_icon":"http://i/c.png",
                 "epg_channel_id":"144","category_id":"11","tv_archive":0,"tv_archive_duration":0,"custom_sid":"","direct_source":""},
                {"num":null,"name":"Two","stream_id":"12","stream_icon":"","epg_channel_id":null,"category_id":11,
                 "tv_archive":"1","tv_archive_duration":"7","extra":{"nested":[1,2,{"x":true}]}}
                ]""",
            ),
        ) { out += it }
        assertEquals(2, n)
        assertEquals(XtreamLiveStream(6775, 1, "Colors HD", "http://i/c.png", "144", "11", false, 0), out[0])
        assertEquals(XtreamLiveStream(12, null, "Two", null, null, "11", true, 7), out[1])
    }

    @Test
    fun liveStreamsSkipsMalformedItem() {
        val out = mutableListOf<XtreamLiveStream>()
        val n = XtreamJson.liveStreams(
            src("""[{"name":"No id"},{"stream_id":"abc","name":"Bad id"},{"stream_id":5,"name":"Ok"}, 42]"""),
        ) { out += it }
        assertEquals(1, n)
        assertEquals("Ok", out.single().name)
    }
}
