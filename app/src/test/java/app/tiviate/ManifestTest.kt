package app.tiviate

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun launcherHasLeanbackAndBanner() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(MainActivity::class.java.name, resolved.single().activityInfo.name)

        val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertNotEquals("application banner must be set for TV launchers", 0, appInfo.banner)
    }

    @Test
    fun leanbackAndTouchscreenAreOptional() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
        val features = assertNotNull(pkg.reqFeatures).let { pkg.reqFeatures!!.associateBy { it.name } }
        val leanback = features.getValue("android.software.leanback")
        val touch = features.getValue("android.hardware.touchscreen")
        assertEquals(0, leanback.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
        assertEquals(0, touch.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
    }
}
