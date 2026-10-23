package app.tiviate.player

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.ChannelRepository
import app.tiviate.data.SettingsStore
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.TiviateDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerControllerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TiviateDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = PlayerController(
        context,
        OkHttpClient(),
        SettingsStore.forFile(File(context.filesDir, "controller-${System.nanoTime()}.preferences_pb")),
        ChannelRepository(db),
        scope,
    )

    @After
    fun tearDown() {
        controller.release()
        scope.cancel()
        db.close()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun playingChannelPicksUpItsResyncedRow() {
        val channel = runBlocking {
            val pid = db.playlists().insert(PlaylistEntity(name = "P", type = PlaylistType.M3U_URL, url = "u"))
            db.channels().insertAll(
                listOf(
                    ChannelEntity(
                        playlistId = pid, stableKey = "k", number = 1, name = "A", groupName = null, logoUrl = null,
                        streamUrl = "http://127.0.0.1:9/a.ts?token=old", tvgId = null, tvgName = null, userAgent = null,
                        referrer = null, epgRef = null, logoFallback = null, catchupDays = null, sortIndex = 1, syncStamp = 1,
                    ),
                ),
            )
            db.channels().byKey(pid, "k")!!
        }
        controller.tune(channel, null)

        // A resync rotates the stream token, then the guide links the channel.
        db.channels().upsertBlocking(channel.playlistId, listOf(channel.copy(id = 0, streamUrl = "http://127.0.0.1:9/a.ts?token=new", syncStamp = 2)))
        runBlocking { db.channels().setEpg(channel.id, "1:a", null) }

        waitFor { controller.current.value?.epgRef == "1:a" && controller.engine.currentUrl?.endsWith("token=new") == true }
        assertEquals("banner needs the fresh guide link", "1:a", controller.current.value?.epgRef)
        assertEquals("reconnects must use the fresh token", "http://127.0.0.1:9/a.ts?token=new", controller.engine.currentUrl)
    }
}
