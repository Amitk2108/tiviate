package app.tiviate.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tiviate.TiviateApp
import app.tiviate.data.PlaylistUpdate
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background updates: the guide every run, playlists when their schedule says so
 * (Settings → Playlists → Update playlists). [KEY_AT_START] marks the run made when Tiviate starts.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as TiviateApp).graph
        val settings = graph.settings.settings.first()
        graph.playlists.scheduledUpdate(settings.playlistUpdate, inputData.getBoolean(KEY_AT_START, false), settings.updateOnStart)
        return Result.success()
    }

    companion object { const val KEY_AT_START = "at_start" }
}

object SyncScheduler {
    private const val PERIODIC = "tiviate-sync-periodic"
    private const val NOW = "tiviate-sync-now"
    const val STALE_MS = 12 * 3_600_000L

    /**
     * "Update when Tiviate starts": refresh unless it was updated within the last hour. A playlist
     * that never loaded is always fetched.
     */
    fun shouldSyncOnStart(enabled: Boolean, lastSyncAt: Long?, nowMs: Long): Boolean =
        lastSyncAt == null || (enabled && nowMs - lastSyncAt >= 3_600_000L)

    /** Is a playlist last updated at [lastSyncAt] due under [mode]? A playlist that never loaded always is. */
    fun playlistDue(mode: PlaylistUpdate, lastSyncAt: Long?, nowMs: Long, atStart: Boolean): Boolean = when {
        lastSyncAt == null -> true
        mode == PlaylistUpdate.ON_START -> atStart && nowMs - lastSyncAt >= 3_600_000L
        else -> nowMs - lastSyncAt >= mode.days * 24 * 3_600_000L
    }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Background refresh every [hours] (setting "Update every"); changing it reschedules the job. */
    fun schedulePeriodic(context: Context, hours: Int = 12) {
        val h = hours.coerceIn(1, 72).toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(h, TimeUnit.HOURS)
            .setConstraints(network)
            .setInitialDelay(h, TimeUnit.HOURS)
            .addTag("every-${h}h")
            .build()
        val wm = WorkManager.getInstance(context)
        val current = wm.getWorkInfosForUniqueWork(PERIODIC).get().firstOrNull { !it.state.isFinished }
        val policy = if (current != null && "every-${h}h" !in current.tags) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(PERIODIC, policy, request)
    }

    /** [delaySec]: the start-up refresh waits a little, so downloading the guide doesn't slow the first zaps. */
    fun syncNow(context: Context, atStart: Boolean = false, delaySec: Long = 0) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_AT_START to atStart)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
