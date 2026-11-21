package app.tiviate.data.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tiviate.R
import app.tiviate.TiviateApp
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.RecordingEntity
import app.tiviate.data.db.RecordingStatus
import app.tiviate.data.sync.HttpClients
import app.tiviate.ui.t
import app.tiviate.ui.tf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Recording live channels to the device or a USB drive, now or at a programme's time. */
object Recordings {
    /** A scheduled recording starts this early, so the programme's start is in the file. */
    const val EARLY_MS = 60_000L
    /** …and runs this much longer than the guide says. */
    const val LATE_MS = 2 * 60_000L

    /** Where recordings can go: the device first, then any USB drive or card (no permission needed for these). */
    fun folders(context: Context): List<File> =
        context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull().ifEmpty { listOf(File(context.filesDir, "recordings")) }

    fun freeBytes(dir: File): Long = runCatching { dir.mkdirs(); StatFs(dir.path).availableBytes }.getOrDefault(0)

    fun folder(context: Context, chosen: String?): File =
        (folders(context).firstOrNull { it.path == chosen } ?: folders(context).first()).also { it.mkdirs() }

    /** Records [channel] from [startMs] (now, or later) to [stopMs]. */
    suspend fun schedule(context: Context, channel: ChannelEntity, title: String, startMs: Long, stopMs: Long): Long {
        val graph = (context.applicationContext as TiviateApp).graph
        val id = graph.db.recordings().insert(
            RecordingEntity(
                playlistId = channel.playlistId, stableKey = channel.stableKey, channelName = channel.name, title = title,
                startMs = startMs, stopMs = stopMs, status = RecordingStatus.SCHEDULED,
            ),
        )
        val delay = (startMs - EARLY_MS - System.currentTimeMillis()).coerceAtLeast(0)
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id), ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RecordingWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(RecordingWorker.KEY_ID to id)).addTag(RecordingWorker.TAG).build(),
        )
        return id
    }

    /** Stops a recording that is running (the file is kept) or calls off one still to come. */
    suspend fun stop(context: Context, id: Long) {
        val graph = (context.applicationContext as TiviateApp).graph
        val r = graph.db.recordings().get(id) ?: return
        if (r.status == RecordingStatus.SCHEDULED) graph.db.recordings().setStatus(id, RecordingStatus.CANCELLED)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    /** Removes a recording and its file. */
    suspend fun delete(context: Context, id: Long) {
        val graph = (context.applicationContext as TiviateApp).graph
        val r = graph.db.recordings().get(id) ?: return
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        r.path?.let { runCatching { File(it).delete() } }
        graph.db.recordings().delete(id)
    }

    fun workName(id: Long) = "record:$id"

    /** "News at Ten 2026-11-20 21-00" → a file name that every file system accepts. */
    internal fun fileName(r: RecordingEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date(r.startMs))
        return "${r.channelName} - ${r.title} - $date".replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").take(120).trim()
    }
}

/** Runs one recording in the foreground (a notification shows while it records). */
class RecordingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as TiviateApp).graph
        val dao = graph.db.recordings()
        val id = inputData.getLong(KEY_ID, -1)
        val r = dao.get(id) ?: return Result.success()
        if (r.status != RecordingStatus.SCHEDULED) return Result.success()
        if (System.currentTimeMillis() >= r.stopMs) { dao.setStatus(id, RecordingStatus.FAILED, t("The programme was over before the recording could start")); return Result.success() }
        val channel = graph.db.channels().byKey(r.playlistId, r.stableKey)
            ?: run { dao.setStatus(id, RecordingStatus.FAILED, t("The channel is no longer in the playlist")); return Result.success() }
        if (app.tiviate.player.DrmSupport.unrecordable(channel)) {
            dao.setStatus(id, RecordingStatus.FAILED, t("Protected (DRM) and DASH channels can't be recorded"))
            return Result.success()
        }
        val playlist = graph.playlists.get(r.playlistId)
        val settings = graph.settings.settings.first()
        runCatching { setForeground(foregroundInfo(r)) } // without it the system may stop long recordings
        dao.setStatus(id, RecordingStatus.RECORDING)
        val dir = Recordings.folder(applicationContext, settings.recordingFolder)
        var file: File? = null
        var bytes = 0L
        var lastSaved = 0L
        try {
            bytes = StreamRecorder(graph.http).record(
                url = channel.streamUrl,
                userAgent = channel.userAgent ?: playlist?.userAgent ?: settings.defaultUserAgent ?: HttpClients.defaultUserAgent,
                headers = buildMap { channel.referrer?.let { put("Referer", it) } },
                untilMs = r.stopMs,
                open = { ext -> File(dir, "${Recordings.fileName(r)}.$ext").also { file = it }.outputStream().buffered(256 * 1024) },
                onProgress = { total ->
                    bytes = total
                    val now = System.currentTimeMillis()
                    if (now - lastSaved > 5_000) { lastSaved = now; kotlinx.coroutines.runBlocking { dao.setFile(id, file?.path, total) } }
                },
            )
            dao.setFile(id, file?.path, bytes)
            dao.setStatus(id, if (bytes > 0) RecordingStatus.DONE else RecordingStatus.FAILED, if (bytes > 0) null else t("Nothing was received from the channel"))
        } catch (e: CancellationException) {
            // Stopped by the viewer: what was recorded stays.
            withContext(NonCancellable) {
                dao.setFile(id, file?.path, bytes)
                dao.setStatus(id, if (bytes > 0) RecordingStatus.DONE else RecordingStatus.CANCELLED)
            }
            throw e
        } catch (e: Exception) {
            dao.setFile(id, file?.path, bytes)
            dao.setStatus(id, if (bytes > 0) RecordingStatus.DONE else RecordingStatus.FAILED, e.message ?: t("Recording failed"))
        }
        return Result.success()
    }

    private fun foregroundInfo(r: RecordingEntity): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager?.createNotificationChannel(NotificationChannel(CHANNEL, t("Recordings"), NotificationManager.IMPORTANCE_LOW))
        val note = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(tf("Recording %s", r.title))
            .setContentText(r.channelName)
            .setOngoing(true)
            .build()
        val notificationId = 40_000 + (r.id % 10_000).toInt()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(notificationId, note)
    }

    companion object {
        const val KEY_ID = "recording_id"
        const val TAG = "tiviate-recording"
        private const val CHANNEL = "recordings"
    }
}
