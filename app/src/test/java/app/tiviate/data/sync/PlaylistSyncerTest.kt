package app.tiviate.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistSyncerTest {

    private val h = SyncHarness()

    @After
    fun tearDown() = h.close()

    private fun m3u(token: String, includeThird: Boolean = true) = buildString {
        appendLine("""#EXTM3U url-tvg="${h.url("/epg.xml.gz")}"""")
        appendLine("""#EXTINF:-1 tvg-id="news.one" tvg-logo="http://logo/n1.png" group-title="News",News One HD""")
        appendLine("http://h/live/1.m3u8?token=$token")
        appendLine("""#EXTINF:-1 group-title="Sports",Sport Two""")
        appendLine("#EXTVLCOPT:http-user-agent=Special/1")
        appendLine("http://h/live/2.m3u8?token=$token")
        if (includeThird) {
            appendLine("""#EXTINF:-1 group-title="Sports",Sport Three""")
            appendLine("http://h/live/3.m3u8?token=$token")
        }
    }

    private suspend fun addM3u(): Long =
        h.db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = h.url("/list.m3u")))

    @Test
    fun m3uUrlSyncInsertsChannelsAndDetectsEpg() = runBlocking {
        h.routes["/list.m3u"] = { text(m3u("a")) }
        val id = addM3u()
        val result = h.playlistSyncer().sync(id)
        assertEquals(3, result.channels)
        val channels = h.db.channels().allInPlaylist(id)
        assertEquals(listOf("News One HD", "Sport Two", "Sport Three"), channels.map { it.name })
        assertEquals(listOf(1, 2, 3), channels.map { it.number })
        assertEquals("Special/1", channels[1].userAgent)
        assertEquals("http://logo/n1.png", channels[0].logoUrl)
        val playlist = h.db.playlists().get(id)!!
        assertEquals(h.url("/epg.xml.gz"), playlist.detectedEpgUrl)
        assertEquals(h.now, playlist.lastSyncAt)
        assertEquals(null, playlist.lastError)
    }

    @Test
    fun resyncKeepsFavoritesWhenUrlTokensRotate() = runBlocking {
        h.routes["/list.m3u"] = { text(m3u("a")) }
        val id = addM3u()
        h.playlistSyncer().sync(id)
        val sport = h.db.channels().allInPlaylist(id).first { it.name == "Sport Two" }
        h.db.favorites().toggle(id, sport.stableKey, nowMs = 1)

        h.routes["/list.m3u"] = { text(m3u("b")) }
        h.now += 1_000
        h.playlistSyncer().sync(id)
        val favs = h.db.favorites().favoriteChannels(id).first()
        assertEquals(listOf("Sport Two"), favs.map { it.name })
        assertTrue(favs.single().streamUrl.endsWith("token=b"))
    }

    @Test
    fun resyncDeletesRemovedChannels() = runBlocking {
        h.routes["/list.m3u"] = { text(m3u("a")) }
        val id = addM3u()
        h.playlistSyncer().sync(id)
        h.routes["/list.m3u"] = { text(m3u("a", includeThird = false)) }
        h.now += 1_000
        h.playlistSyncer().sync(id)
        assertEquals(listOf("News One HD", "Sport Two"), h.db.channels().allInPlaylist(id).map { it.name })
    }

    @Test
    fun syncLargePlaylistStreamsInBatches() = runBlocking {
        val big = buildString {
            appendLine("#EXTM3U")
            for (i in 1..20_000) {
                appendLine("""#EXTINF:-1 tvg-id="id$i" group-title="G${i % 40}",Channel $i""")
                appendLine("http://h/s/$i.ts")
            }
        }
        h.routes["/list.m3u"] = { text(big) }
        val id = addM3u()
        var lastProgress = 0
        val result = h.playlistSyncer().sync(id) { lastProgress = it }
        assertEquals(20_000, result.channels)
        assertEquals(20_000, h.db.channels().count(id))
        assertEquals(20_000, lastProgress)
    }

    @Test
    fun anEmptyOrHtmlAnswerKeepsTheChannelsAndTheirSetUp() = runBlocking {
        h.routes["/list.m3u"] = { text(m3u("a")) }
        val id = addM3u()
        h.playlistSyncer().sync(id)
        val first = h.db.channels().allInPlaylist(id).first()
        h.db.channels().setHidden(id, listOf(first.stableKey), true)
        // The provider serves an "account expired" page, then an empty body, instead of the playlist.
        val page = "<!DOCTYPE html>\n<html><body>Account expired. <a href=\"https://provider.example/renew\">Renew</a></body></html>"
        for (body in listOf(page, "")) {
            h.routes["/list.m3u"] = { text(body) }
            assertTrue(runCatching { h.playlistSyncer().sync(id) }.isFailure)
            assertEquals(3, h.db.channels().count(id))
            assertTrue("set-up kept", h.db.channels().byKey(id, first.stableKey)!!.hidden)
            assertNotNull(h.db.playlists().get(id)!!.lastError)
        }
    }

    @Test
    fun xtreamWithoutItsGroupListKeepsTheGroups() = runBlocking {
        val streams = """[{"name":"A","stream_id":1,"category_id":"1"},{"name":"B","stream_id":2,"category_id":"2"}]"""
        h.routes["/player_api.php?username=u&password=p&action=get_live_categories"] = {
            text("""[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"Sports"}]""")
        }
        h.routes["/player_api.php?username=u&password=p&action=get_live_streams"] = { text(streams) }
        h.routes["/player_api.php?username=u&password=p"] = { text("""{"user_info":{"auth":1,"allowed_output_formats":["ts"]}}""") }
        val id = h.db.playlists().insert(
            PlaylistEntity(name = "X", type = PlaylistType.XTREAM, xtreamServer = h.url("/").trimEnd('/'), xtreamUser = "u", xtreamPass = "p"),
        )
        h.playlistSyncer().sync(id)
        h.routes["/player_api.php?username=u&password=p&action=get_live_categories"] = { text("""{"error":"busy"}""") }
        assertTrue(runCatching { h.playlistSyncer().sync(id) }.isFailure)
        assertEquals(setOf("News", "Sports"), h.db.channels().allInPlaylist(id).map { it.groupName }.toSet())
    }

    @Test
    fun failedDownloadReportsErrorAndKeepsOldChannels() = runBlocking {
        h.routes["/list.m3u"] = { text(m3u("a")) }
        val id = addM3u()
        h.playlistSyncer().sync(id)
        h.routes["/list.m3u"] = { text("gone", code = 500) }
        try {
            h.playlistSyncer().sync(id)
            fail("expected failure")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("500"))
        }
        assertEquals(3, h.db.channels().count(id))
        assertNotNull(h.db.playlists().get(id)!!.lastError)
    }

    @Test
    fun xtreamSyncBuildsHlsUrlsWhenOnlyM3u8AllowedAndUsesCategories() = runBlocking {
        h.routes["/player_api.php?username=u&password=p&action=get_live_categories"] = {
            text("""[{"category_id":"11","category_name":"Hindi Entertainment"},{"category_id":"7","category_name":"Sports"}]""")
        }
        h.routes["/player_api.php?username=u&password=p&action=get_live_streams"] = {
            text(
                """[{"num":1,"name":"Colors HD","stream_id":6775,"stream_icon":"http://i/c.png","epg_channel_id":"144","category_id":"11","tv_archive":0},
                   {"num":2,"name":"Sports 1","stream_id":"12","stream_icon":"","epg_channel_id":"","category_id":7,"tv_archive":"1","tv_archive_duration":"7"}]""",
            )
        }
        h.routes["/player_api.php?username=u&password=p"] = {
            text("""{"user_info":{"auth":1,"status":"Active","exp_date":"1795360633","max_connections":"1","allowed_output_formats":["m3u8"]}}""")
        }
        val server = h.url("/").trimEnd('/')
        val id = h.db.playlists().insert(
            PlaylistEntity(name = "X", type = PlaylistType.XTREAM, xtreamServer = server, xtreamUser = "u", xtreamPass = "p"),
        )
        val result = h.playlistSyncer().sync(id)
        assertEquals(2, result.channels)
        val ch = h.db.channels().allInPlaylist(id)
        assertEquals("$server/live/u/p/6775.m3u8", ch[0].streamUrl)
        assertEquals("Hindi Entertainment", ch[0].groupName)
        assertEquals("Sports", ch[1].groupName)
        assertEquals("x:6775", ch[0].stableKey)
        assertEquals("144", ch[0].tvgId)
        assertEquals(7, ch[1].catchupDays)
        assertEquals("$server/xmltv.php?username=u&password=p", h.db.playlists().get(id)!!.detectedEpgUrl)
    }

    @Test
    fun xtreamUnauthorizedFails() = runBlocking {
        h.routes["/player_api.php"] = { text("""{"user_info":{"auth":0}}""") }
        val id = h.db.playlists().insert(
            PlaylistEntity(name = "X", type = PlaylistType.XTREAM, xtreamServer = h.url("/").trimEnd('/'), xtreamUser = "u", xtreamPass = "bad"),
        )
        try {
            h.playlistSyncer().sync(id)
            fail("expected failure")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("login", ignoreCase = true))
        }
    }

    @Test
    fun xtreamNumbersFollowListOrderWhenProviderRepeatsNum() = runBlocking {
        h.routes["/player_api.php?username=u&password=p&action=get_live_categories"] = { text("[]") }
        h.routes["/player_api.php?username=u&password=p&action=get_live_streams"] = {
            text("""[{"num":2,"name":"A","stream_id":1},{"num":2,"name":"B","stream_id":2},{"num":4,"name":"C","stream_id":3}]""")
        }
        h.routes["/player_api.php?username=u&password=p"] = { text("""{"user_info":{"auth":1,"allowed_output_formats":["m3u8"]}}""") }
        val id = h.db.playlists().insert(
            PlaylistEntity(name = "X", type = PlaylistType.XTREAM, xtreamServer = h.url("/").trimEnd('/'), xtreamUser = "u", xtreamPass = "p"),
        )
        h.playlistSyncer().sync(id)
        assertEquals(listOf(1, 2, 3), h.db.channels().allInPlaylist(id).map { it.number })
    }

    @Test
    fun xtreamGroupsFollowTheProvidersCategoryOrder() = runBlocking {
        // The provider lists its categories News, Sports, Movies, but its first channel is a movie.
        h.routes["/player_api.php?username=u&password=p&action=get_live_categories"] = {
            text("""[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"Sports"},{"category_id":"3","category_name":"Movies"}]""")
        }
        h.routes["/player_api.php?username=u&password=p&action=get_live_streams"] = {
            text(
                """[{"name":"Film 1","stream_id":1,"category_id":"3"},{"name":"Match","stream_id":2,"category_id":"2"},
                   {"name":"Headlines","stream_id":3,"category_id":"1"},{"name":"Film 2","stream_id":4,"category_id":"3"}]""",
            )
        }
        h.routes["/player_api.php?username=u&password=p"] = { text("""{"user_info":{"auth":1,"allowed_output_formats":["ts"]}}""") }
        val id = h.db.playlists().insert(
            PlaylistEntity(name = "X", type = PlaylistType.XTREAM, xtreamServer = h.url("/").trimEnd('/'), xtreamUser = "u", xtreamPass = "p"),
        )
        h.playlistSyncer().sync(id)
        assertEquals(listOf("News", "Sports", "Movies"), h.db.channels().categories(id).first().map { it.name })
        // Inside a group the provider's channel order stays.
        assertEquals(listOf("Film 1", "Film 2"), h.db.channels().allInPlaylist(id).filter { it.groupName == "Movies" }.sortedBy { it.sortIndex }.map { it.name })
    }

    private fun ftsDocuments(): Int =
        h.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM channel_fts_docsize").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun resyncUpdatesRowsInPlaceKeepingIdsGuideLinksAndSearchIndex() = runBlocking {
        h.routes["/list.m3u"] = { text(m3u("a")) }
        val id = addM3u()
        h.playlistSyncer().sync(id)
        val before = h.db.channels().allInPlaylist(id)
        val news = before.first { it.name == "News One HD" }
        h.db.channels().setEpg(news.id, "9:news.one", "http://epg/n1.png") // as left by the last guide sync

        h.routes["/list.m3u"] = { text(m3u("b")) }
        repeat(3) {
            h.now += 1_000
            h.playlistSyncer().sync(id)
        }

        val after = h.db.channels().allInPlaylist(id)
        assertEquals("rows keep their ids, so lists don't rebuild", before.map { it.id }, after.map { it.id })
        val newsAfter = after.first { it.name == "News One HD" }
        assertEquals("guide link survives until the guide relinks", "9:news.one", newsAfter.epgRef)
        assertEquals("http://epg/n1.png", newsAfter.logoFallback)
        assertTrue(newsAfter.streamUrl.endsWith("token=b"))
        assertEquals("no orphaned search entries", 3, ftsDocuments())
    }
}
