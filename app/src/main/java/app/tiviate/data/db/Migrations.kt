package app.tiviate.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema upgrades. Each one keeps the viewer's playlists, favourites and history: an app update
 * must never wipe them. Exported schemas live in app/schemas.
 */
object Migrations {

    /** v2: channel organising (hide, reorder, rename, custom groups, names editor, manual guide link). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `playlist` ADD COLUMN `namePrefixes` TEXT")
            db.execSQL("ALTER TABLE `playlist` ADD COLUMN `nameSuffixes` TEXT")

            db.execSQL("ALTER TABLE `channel` ADD COLUMN `originalName` TEXT")
            db.execSQL("ALTER TABLE `channel` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `channel` ADD COLUMN `customSort` INTEGER")
            db.execSQL("ALTER TABLE `channel` ADD COLUMN `addedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `channel` ADD COLUMN `epgOverride` TEXT")
            db.execSQL("ALTER TABLE `channel` ADD COLUMN `renamed` INTEGER NOT NULL DEFAULT 0")
            // Existing channels: the playlist name is the original, and they count as added at their last sync.
            db.execSQL("UPDATE `channel` SET `originalName` = `name`, `addedAt` = `syncStamp`")

            db.execSQL("ALTER TABLE `favorite` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `group_pref` (`playlistId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                    "`hidden` INTEGER NOT NULL DEFAULT 0, `position` INTEGER, `displayName` TEXT, PRIMARY KEY(`playlistId`, `name`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_group` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_group_playlistId` ON `custom_group` (`playlistId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `custom_group_member` (`groupId` INTEGER NOT NULL, `stableKey` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`groupId`, `stableKey`))",
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2)
}
