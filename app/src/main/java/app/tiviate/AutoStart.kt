package app.tiviate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * Android 10+ lets an app open itself at boot only with "Display over other apps". TVs that have no
 * screen for that permission can't start Tiviate automatically.
 */
object AutoStart {
    fun askPermissionIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)) return
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "This TV doesn't let apps start themselves at boot", Toast.LENGTH_LONG).show()
        }
    }
}
