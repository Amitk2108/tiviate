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
import app.tiviate.TiviateApp
import java.util.concurrent.TimeUnit

/** Refreshes every enabled playlist and its guide in the background. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as TiviateApp).graph.playlists.syncAll()
        return Result.success()
    }
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

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
