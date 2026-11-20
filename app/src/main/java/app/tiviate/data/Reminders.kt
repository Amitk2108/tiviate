package app.tiviate.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tiviate.HomeChannels
import app.tiviate.MainActivity
import app.tiviate.R
import app.tiviate.TiviateApp
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.t
import app.tiviate.ui.tf
import java.util.concurrent.TimeUnit

/** "Remind me" for a programme: shortly before it starts, Tiviate offers to switch to it. */
data class Reminder(val startMs: Long, val playlistId: Long, val stableKey: String, val title: String, val channelName: String) {
    val key: String get() = Reminders.key(playlistId, stableKey, startMs)
}

object Reminders {
    /** How long before the start the reminder comes. */
    const val AHEAD_MS = 60_000L
    private const val SEP = '\u001F' // never in titles or keys
    private const val CHANNEL_ID = "reminders"

    fun key(playlistId: Long, stableKey: String, startMs: Long) = "$playlistId:$stableKey@$startMs"

    fun encode(r: Reminder): String = listOf(r.startMs, r.playlistId, r.stableKey, r.channelName, r.title).joinToString(SEP.toString())

    fun decode(s: String): Reminder? {
        val p = s.split(SEP, limit = 5)
        if (p.size != 5) return null
        return Reminder(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2], p[4], p[3])
    }

    fun all(settings: AppSettings): List<Reminder> = settings.reminders.mapNotNull(::decode).sortedBy { it.startMs }

    fun keys(settings: AppSettings): Set<String> = all(settings).mapTo(HashSet()) { it.key }

    /** Sets the reminder, or removes it when it is already set. Returns true when it is now set. */
    suspend fun toggle(context: Context, store: SettingsStore, channel: ChannelEntity, title: String, startMs: Long): Boolean {
        val r = Reminder(startMs, channel.playlistId, channel.stableKey, title, channel.name)
        var set = false
        store.update { s ->
            val existing = s.reminders.firstOrNull { decode(it)?.key == r.key }
            set = existing == null
            // Ones whose programme already started are dropped on the way.
            val kept = s.reminders.filter { e -> decode(e)?.let { it.startMs > System.currentTimeMillis() - 3_600_000L } == true }.toSet()
            s.copy(reminders = if (existing != null) kept - existing else kept + encode(r))
        }
        val work = WorkManager.getInstance(context)
        if (set) {
            val delay = (startMs - AHEAD_MS - System.currentTimeMillis()).coerceAtLeast(0)
            work.enqueueUniqueWork(
                r.key, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(ReminderWorker.KEY to encode(r))).addTag(ReminderWorker.TAG).build(),
            )
        } else {
            work.cancelUniqueWork(r.key)
        }
        return set
    }

    /** The reminder is due: in the app when it's on screen, otherwise as a notification. */
    suspend fun fire(context: Context, r: Reminder) {
        val graph = (context.applicationContext as TiviateApp).graph
        graph.settings.update { s -> s.copy(reminders = s.reminders.filterTo(HashSet()) { decode(it)?.key != r.key }) }
        if (graph.player.foreground) {
            graph.reminderDue.value = r
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, t("Reminders"), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, r.key.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(HomeChannels.link(r.playlistId, r.stableKey))).setClass(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(r.title)
            .setContentText(tf("Starts now on %s", r.channelName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(r.key.hashCode(), note) } // no permission: nothing to show
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Reminders.decode(inputData.getString(KEY).orEmpty())?.let { Reminders.fire(applicationContext, it) }
        return Result.success()
    }

    companion object {
        const val KEY = "reminder"
        const val TAG = "tiviate-reminder"
    }
}
