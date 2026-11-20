package app.tiviate

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tvprovider.media.tv.TvContractCompat
import app.tiviate.data.SyncProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Android TV home screen rows (needs an Android TV 8.0+ device or emulator; skipped elsewhere). */
@RunWith(AndroidJUnit4::class)
class HomeChannelsFlowTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val fixtures = FixtureServer().start()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val context get() = ApplicationProvider.getApplicationContext<TiviateApp>()
    private val graph get() = context.graph

    @After
    fun tearDown() {
        scenario?.close()
        fixtures.shutdown()
    }

    @Test
    fun favoritesAndRecentAppearOnTheHomeScreenAndOpenTheirChannel() {
        assumeTrue(Build.VERSION.SDK_INT >= 26 && context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null)
        val (favorites, recent) = runBlocking {
            graph.playlists.playlists.first().forEach { graph.playlists.delete(it.id) }
            val id = graph.playlists.addM3uUrl("Fixture", fixtures.url("/list.m3u"), null, null)
            graph.playlists.sync(id).first { it is SyncProgress.Done || it is SyncProgress.Failed }
            graph.settings.update { it.copy(activePlaylistId = id) }
            val all = graph.db.channels().allInPlaylist(id)
            graph.channels.toggleFavorite(id, all[2].stableKey)
            graph.channels.recordWatch(id, all[0].stableKey, 60)
            graph.channels.recordWatch(id, all[1].stableKey, 60)
            graph.db.favorites().favoriteChannels(id).first() to graph.db.history().recent(id, 20).first()
        }
        HomeChannels.publish(context, HomeChannels.Rows(favorites, recent))

        // Two rows of our own, each holding its channels, each linking back to Tiviate.
        val resolver = context.contentResolver
        val rows = resolver.query(TvContractCompat.Channels.CONTENT_URI, arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_DISPLAY_NAME), null, null, null)!!
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(1), c.getLong(0)) } }
        assertEquals(setOf("Favorites", "Recent"), rows.keys)
        fun links(channelId: Long) = resolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), arrayOf(TvContractCompat.PreviewPrograms.COLUMN_INTENT_URI), null, null, null)!!
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(favorites.map { HomeChannels.link(it.playlistId, it.stableKey) }, links(rows.getValue("Favorites")))
        assertEquals(recent.size, links(rows.getValue("Recent")).size)

        // Publishing again replaces the entries instead of adding more.
        HomeChannels.publish(context, HomeChannels.Rows(favorites, recent))
        assertEquals(1, links(rows.getValue("Favorites")).size)

        // Picking the favourite on the home screen opens Tiviate fullscreen on it.
        val pick = favorites.first()
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(links(rows.getValue("Favorites")).first())).setClass(context, MainActivity::class.java),
        )
        rule.waitUntil(15_000) { graph.player.current.value?.stableKey == pick.stableKey && graph.player.engine.state.value.isPlaying }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("player_screen") && !rule.onAllNodesWithTagExists("guide_screen") }

        // For a look at the real home screen: /sdcard/tiviate-home.png.
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("input keyevent KEYCODE_HOME").close()
        Thread.sleep(6_000)
        ui.executeShellCommand("screencap -p /sdcard/tiviate-home.png").close()
        Thread.sleep(1_000)
    }
}
