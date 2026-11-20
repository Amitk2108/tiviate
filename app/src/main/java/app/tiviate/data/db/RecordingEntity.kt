package app.tiviate.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A recording, scheduled or made. [status] is one of [RecordingStatus]. */
@Entity(tableName = "recording")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val stableKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val status: String,
    val path: String? = null,
    val bytes: Long = 0,
    val error: String? = null,
)

object RecordingStatus {
    const val SCHEDULED = "scheduled"
    const val RECORDING = "recording"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

@Dao
interface RecordingDao {
    @Insert suspend fun insert(r: RecordingEntity): Long
    @Query("SELECT * FROM recording WHERE id = :id") suspend fun get(id: Long): RecordingEntity?
    @Query("SELECT * FROM recording ORDER BY startMs DESC") fun observeAll(): Flow<List<RecordingEntity>>
    @Query("SELECT * FROM recording WHERE status IN ('scheduled', 'recording') ORDER BY startMs") fun observeActive(): Flow<List<RecordingEntity>>
    @Query("UPDATE recording SET status = :status, error = :error WHERE id = :id") suspend fun setStatus(id: Long, status: String, error: String? = null)
    @Query("UPDATE recording SET path = :path, bytes = :bytes WHERE id = :id") suspend fun setFile(id: Long, path: String?, bytes: Long)
    @Query("DELETE FROM recording WHERE id = :id") suspend fun delete(id: Long)
}
