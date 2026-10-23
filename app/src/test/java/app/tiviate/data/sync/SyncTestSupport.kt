package app.tiviate.data.sync

import android.util.Xml
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tiviate.data.db.TiviateDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** Shared fixtures for sync tests: in-memory DB, a routing MockWebServer and gzip helpers. */
class SyncHarness {
    val db: TiviateDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TiviateDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val server = MockWebServer()
    val routes = HashMap<String, () -> MockResponse>()
    val http = OkHttpClient()
    var now = 1_792_756_800_000L // 2026-10-23T10:00:00Z

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val key = routes.keys.sortedByDescending { it.length }.firstOrNull { path.startsWith(it) }
                return key?.let { routes.getValue(it)() } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    fun url(path: String) = server.url(path).toString()

    fun playlistSyncer() = PlaylistSyncer(db, http, openLocal = { File(it).inputStream() }, clock = { now })
    fun epgSyncer() = EpgSyncer(db, http, newPullParser = { Xml.newPullParser() }, clock = { now })

    fun close() {
        server.shutdown()
        db.close()
    }
}

fun text(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

/** Gzip served as application/octet-stream without Content-Encoding, exactly like the real provider. */
fun gzipOctet(body: String): MockResponse {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(body.toByteArray()) }
    return MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(out.toByteArray()))
}
