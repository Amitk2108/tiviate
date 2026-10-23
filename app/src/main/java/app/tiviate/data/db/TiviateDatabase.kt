package app.tiviate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, ChannelFts::class, FavoriteEntity::class,
        HistoryEntity::class, EpgSourceEntity::class, EpgChannelEntity::class, ProgrammeEntity::class,
        GroupPrefEntity::class, CustomGroupEntity::class, CustomGroupMemberEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TiviateDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun epg(): EpgDao
    abstract fun organise(): OrganiseDao

    companion object {
        fun create(context: Context): TiviateDatabase =
            Room.databaseBuilder(context, TiviateDatabase::class.java, "tiviate.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*Migrations.ALL)
                // Only a downgrade (older app over newer data) may start fresh; upgrades always migrate.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
