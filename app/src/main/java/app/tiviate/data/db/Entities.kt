package app.tiviate.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistType { M3U_URL, M3U_FILE, XTREAM }

enum class EpgOrigin { AUTO, MANUAL }

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U link, or the content/file URI of a local playlist. */
    val url: String? = null,
    val xtreamServer: String? = null,
    val xtreamUser: String? = null,
    val xtreamPass: String? = null,
    val preferHls: Boolean = false,
    val userAgent: String? = null,
    /** EPG URL typed by the user when adding the playlist. */
    val epgUrl: String? = null,
    /** EPG URL found in the playlist header or derived from Xtream. */
    val detectedEpgUrl: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val enabled: Boolean = true,
    val sortIndex: Int = 0,
)

@Entity(
    tableName = "channel",
    indices = [
        Index(value = ["playlistId", "stableKey"], unique = true),
        Index(value = ["playlistId", "groupName", "sortIndex"]),
        Index(value = ["playlistId", "sortIndex"]),
        Index(value = ["playlistId", "number"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val stableKey: String,
    val number: Int,
    val name: String,
    val groupName: String?,
    val logoUrl: String?,
    val streamUrl: String,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
    /** "sourceId:xmltvId" of the matched EPG channel, or null. */
    val epgRef: String?,
    /** Logo from the EPG `<channel><icon>`, used when [logoUrl] is missing or fails. */
    val logoFallback: String?,
    val catchupDays: Int?,
    val sortIndex: Int,
    val syncStamp: Long,
)

@Fts4(contentEntity = ChannelEntity::class)
@Entity(tableName = "channel_fts")
data class ChannelFts(val name: String)

@Entity(tableName = "favorite", primaryKeys = ["playlistId", "stableKey"])
data class FavoriteEntity(val playlistId: Long, val stableKey: String, val addedAt: Long)

@Entity(tableName = "history", primaryKeys = ["playlistId", "stableKey"])
data class HistoryEntity(val playlistId: Long, val stableKey: String, val lastWatchedAt: Long, val totalWatchSec: Int)

@Entity(tableName = "epg_source")
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val name: String,
    val origin: EpgOrigin,
    /** Set for AUTO sources: the playlist the URL was detected from. */
    val playlistId: Long? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val timeOffsetMin: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** Programmes are visible only for this generation; a refresh writes generation+1 then swaps. */
    val activeGeneration: Int = 0,
)

@Entity(tableName = "epg_channel", primaryKeys = ["sourceId", "xmltvId"])
data class EpgChannelEntity(
    val sourceId: Long,
    val xmltvId: String,
    /** Display names joined with [NAME_SEPARATOR]. */
    val displayNames: String,
    val iconUrl: String?,
) {
    companion object { const val NAME_SEPARATOR = "\u001F" }
}

@Entity(
    tableName = "programme",
    indices = [
        Index(value = ["epgRef", "startMs"]),
        Index(value = ["epgRef", "stopMs"]),
        Index(value = ["sourceId", "generation"]),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val epgRef: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    val episode: String? = null,
    val generation: Int,
)

data class CategoryRow(@ColumnInfo(name = "name") val name: String?, @ColumnInfo(name = "count") val count: Int)

/** Lightweight projection used for EPG matching so large playlists don't load full rows. */
data class ChannelMatchRow(
    val id: Long,
    val tvgId: String?,
    val tvgName: String?,
    val name: String,
    val epgRef: String?,
    val logoFallback: String?,
)
