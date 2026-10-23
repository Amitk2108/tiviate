package app.tiviate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * "Start Tiviate when the TV turns on". Works on Fire TV and Android TV up to Android 9; newer
 * Android versions may block apps from opening themselves at boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? TiviateApp ?: return
        val enabled = runBlocking { app.graph.settings.settings.first().autoStartOnBoot } // a small local read
        if (!enabled) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
