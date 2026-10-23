package app.tiviate

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tiviate.data.db.Migrations
import app.tiviate.data.db.TiviateDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An app update must keep the viewer's playlists, channels and favourites. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TiviateDatabase::class.java)

    @Test
    fun version1DataSurvivesTheUpgradeToVersion2() {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO playlist (id, name, type, preferHls, enabled, sortIndex) VALUES (1, 'Home', 'M3U', 0, 1, 0)",
            )
            execSQL(
                "INSERT INTO channel (id, playlistId, stableKey, number, name, groupName, streamUrl, sortIndex, syncStamp) " +
                    "VALUES (7, 1, 'k7', 3, 'UK: BBC One', 'News', 'http://x/7', 2, 1234)",
            )
            execSQL("INSERT INTO favorite (playlistId, stableKey, addedAt) VALUES (1, 'k7', 99)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 2, true, *Migrations.ALL)

        db.query("SELECT name, originalName, hidden, customSort, addedAt, epgOverride, renamed FROM channel WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("UK: BBC One", c.getString(0))
            assertEquals("UK: BBC One", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertTrue(c.isNull(3))
            assertEquals(1234L, c.getLong(4))
            assertTrue(c.isNull(5))
            assertEquals(0, c.getInt(6))
        }
        db.query("SELECT addedAt, position FROM favorite WHERE stableKey = 'k7'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(99L, c.getLong(0))
            assertEquals(0, c.getInt(1))
        }
        db.query("SELECT name, namePrefixes, nameSuffixes FROM playlist WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Home", c.getString(0))
            assertTrue(c.isNull(1) && c.isNull(2))
        }
        // The new organising tables are there and usable.
        db.execSQL("INSERT INTO group_pref (playlistId, name, hidden) VALUES (1, 'News', 1)")
        db.execSQL("INSERT INTO custom_group (playlistId, name) VALUES (1, 'Mine')")
        db.execSQL("INSERT INTO custom_group_member (groupId, stableKey) VALUES (1, 'k7')")
        db.query("SELECT COUNT(*) FROM custom_group_member").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }
}
