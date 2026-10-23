package app.tiviate

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {

    private val app = ApplicationProvider.getApplicationContext<TiviateApp>()

    @Test
    fun opensTiviateOnBootOnlyWhenTurnedOn() {
        runBlocking { app.graph.settings.update { it.copy(autoStartOnBoot = false) } }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull("off: stays closed", shadowOf(app).nextStartedActivity)

        runBlocking { app.graph.settings.update { it.copy(autoStartOnBoot = true) } }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(MainActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
    }
}
