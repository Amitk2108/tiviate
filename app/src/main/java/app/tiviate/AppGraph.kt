package app.tiviate

import android.content.Context
import android.net.Uri
import android.util.Xml
import app.tiviate.data.ChannelRepository
import app.tiviate.data.PlaylistRepository
import app.tiviate.data.SettingsStore
import app.tiviate.data.db.TiviateDatabase
import app.tiviate.data.sync.EpgSyncer
import app.tiviate.data.sync.HttpClients
import app.tiviate.data.sync.PlaylistSyncer
import app.tiviate.player.PlayerController
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Hand-wired app-wide dependencies. Created once by [TiviateApp]; everything is lazy so startup stays cheap. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val db: TiviateDatabase by lazy { TiviateDatabase.create(appContext) }
    val http: OkHttpClient by lazy { HttpClients.create() }
    val settings: SettingsStore by lazy { SettingsStore.create(appContext) }
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val channels: ChannelRepository by lazy { ChannelRepository(db, sortSetting = settings.settings.map { it.channelSort }) }
    /** Long press of Back: return to fullscreen from anywhere (TiviMate's "Long Back"). */
    val longBack = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val player: PlayerController by lazy { PlayerController(appContext, http, settings, channels, appScope) }

    val playlists: PlaylistRepository by lazy {
        PlaylistRepository(
            db = db,
            http = http,
            playlistSyncer = PlaylistSyncer(db, http, openLocal = ::openLocal),
            epgSyncer = EpgSyncer(db, http, newPullParser = { Xml.newPullParser() }, pastDays = { settings.settings.first().epgPastDays }),
        )
    }

    private fun openLocal(uri: String): InputStream =
        if (uri.startsWith("content://")) {
            requireNotNull(appContext.contentResolver.openInputStream(Uri.parse(uri))) { "Cannot open $uri" }
        } else {
            File(uri.removePrefix("file://")).inputStream()
        }
}
