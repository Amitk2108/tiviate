package app.tiviate.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/** What kind of screen Tiviate is on; each screen picks its layout from it. */
enum class FormFactor { TV, TABLET, PHONE }

/** A TV by the device's mode; otherwise a tablet when the window's shorter side is at least 600 dp. */
fun formFactorOf(isTv: Boolean, smallestWidthDp: Int): FormFactor = when {
    isTv -> FormFactor.TV
    smallestWidthDp >= 600 -> FormFactor.TABLET
    else -> FormFactor.PHONE
}

/** Phones (both ways round) and tablets held upright use the touch layouts: video on top, list below. */
fun usesPhoneLayout(formFactor: FormFactor, portrait: Boolean): Boolean =
    formFactor == FormFactor.PHONE || (formFactor == FormFactor.TABLET && portrait)

fun isTvDevice(context: Context): Boolean {
    val mode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    return mode == Configuration.UI_MODE_TYPE_TELEVISION || context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** Set by instrumented tests to run the TV screens on a phone emulator; null in the app. */
@Volatile var forcedFormFactor: FormFactor? = null

val LocalFormFactor = staticCompositionLocalOf { FormFactor.TV }
