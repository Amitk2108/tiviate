package app.tiviate.ui.phone

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener

/**
 * Fullscreen on a phone means landscape. The fullscreen button turns the screen and holds it there
 * until the phone is actually turned that way; then the sensor takes over again, as in video apps.
 */
object Rotation {
    private var listener: OrientationEventListener? = null

    /** The screen was turned sideways by Tiviate's own button (not by turning the device): Back turns it back. */
    @Volatile var buttonFullscreen = false
        private set

    fun fullscreen(activity: Activity, on: Boolean) {
        release()
        buttonFullscreen = on
        activity.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        // With auto-rotate off the viewer turns the screen only with this button, so keep the choice.
        val autoRotate = Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        if (!autoRotate) return
        listener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val sideways = degrees in 60..120 || degrees in 240..300
                val upright = degrees <= 30 || degrees >= 330 || degrees in 150..210
                if ((on && sideways) || (!on && upright)) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    release()
                    buttonFullscreen = false // the device itself is sideways now
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    fun release() {
        listener?.disable()
        listener = null
    }
}
