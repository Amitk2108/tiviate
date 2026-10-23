package app.tiviate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class, ChannelEntity::class, ChannelFts::class, FavoriteEntity::class,
        HistoryEntity::class, EpgSourceEntity::class, EpgChannelEntity::class, ProgrammeEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TiviateDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun epg(): EpgDao

    companion object {
        fun create(context: Context): TiviateDatabase =
            Room.databaseBuilder(context, TiviateDatabase::class.java, "tiviate.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
