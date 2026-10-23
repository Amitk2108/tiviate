package app.tiviate

import android.content.Context
import android.net.Uri
import android.util.Xml
import app.tiviate.data.PlaylistRepository
import app.tiviate.data.db.TiviateDatabase
import app.tiviate.data.sync.EpgSyncer
import app.tiviate.data.sync.HttpClients
import app.tiviate.data.sync.PlaylistSyncer
import java.io.File
import java.io.InputStream
import okhttp3.OkHttpClient

/** Hand-wired app-wide dependencies. Created once by [TiviateApp]; everything is lazy so startup stays cheap. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val db: TiviateDatabase by lazy { TiviateDatabase.create(appContext) }
    val http: OkHttpClient by lazy { HttpClients.create() }

    val playlists: PlaylistRepository by lazy {
        PlaylistRepository(
            db = db,
            http = http,
            playlistSyncer = PlaylistSyncer(db, http, openLocal = ::openLocal),
            epgSyncer = EpgSyncer(db, http, newPullParser = { Xml.newPullParser() }),
        )
    }

    private fun openLocal(uri: String): InputStream =
        if (uri.startsWith("content://")) {
            requireNotNull(appContext.contentResolver.openInputStream(Uri.parse(uri))) { "Cannot open $uri" }
        } else {
            File(uri.removePrefix("file://")).inputStream()
        }
}
