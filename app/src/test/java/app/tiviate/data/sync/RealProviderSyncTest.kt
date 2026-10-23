package app.tiviate.data.sync

import android.util.Xml
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.data.db.TiviateDatabase
import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional end-to-end check against the owner's real Xtream account. Skipped unless the
 * gitignored `local.test.properties` exists at the repository root. Never commit credentials.
 */
@RunWith(AndroidJUnit4::class)
class RealProviderSyncTest {

    @Test
    fun realXtreamAccountSyncsChannelsAndGuide() = runBlocking {
        val file = listOf(File("../local.test.properties"), File("local.test.properties")).firstOrNull { it.exists() }
        assumeTrue("local.test.properties not present", file != null)
        val props = Properties().apply { file!!.inputStream().use { load(it) } }

        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TiviateDatabase::class.java)
            .allowMainThreadQueries().build()
        val http = HttpClients.create()
        val id = db.playlists().insert(
            PlaylistEntity(
                name = "Real", type = PlaylistType.XTREAM,
                xtreamServer = props.getProperty("xtream.server"),
                xtreamUser = props.getProperty("xtream.username"),
                xtreamPass = props.getProperty("xtream.password"),
            ),
        )
        val t0 = System.currentTimeMillis()
        val channels = PlaylistSyncer(db, http, openLocal = { error("unused") }).sync(id)
        val t1 = System.currentTimeMillis()
        val epg = EpgSyncer(db, http, newPullParser = { Xml.newPullParser() }).syncForPlaylist(id)
        val t2 = System.currentTimeMillis()

        val rows = db.channels().allInPlaylist(id)
        val linked = rows.count { it.epgRef != null }
        val withLogo = rows.count { it.logoUrl != null || it.logoFallback != null }
        val programmesWithImage = db.epg().programmesFor(rows.mapNotNull { it.epgRef }.distinct().take(800), 0, Long.MAX_VALUE)
        println(
            "REAL: channels=${channels.channels} groups=${rows.map { it.groupName }.distinct().size} " +
                "playlist=${t1 - t0}ms epg=${t2 - t1}ms programmes=${epg.programmes} ok=${epg.okSources} failed=${epg.failedSources} " +
                "linked=$linked/${rows.size} logos=$withLogo programmeImages=${programmesWithImage.count { it.iconUrl != null }}/${programmesWithImage.size} " +
                "sampleUrl=${rows.first().streamUrl.replace(props.getProperty("xtream.password"), "***").replace(props.getProperty("xtream.username"), "***")}",
        )
        assertTrue(channels.channels > 0)
        assertTrue(epg.programmes > 0)
        assertTrue("most channels should link to the guide", linked > rows.size / 2)
        db.close()
    }
}
