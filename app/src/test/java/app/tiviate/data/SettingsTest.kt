package app.tiviate.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest {

    @Test
    fun defaultsThenUpdatePersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "settings-test-${System.nanoTime()}.preferences_pb")
        val store = SettingsStore.forFile(file)

        val defaults = store.settings.first()
        assertNull(defaults.activePlaylistId)
        assertEquals(BufferPreset.NORMAL, defaults.bufferPreset)
        assertFalse(defaults.tunneling)
        assertEquals(4_000, defaults.bannerTimeoutMs)

        store.update { it.copy(activePlaylistId = 7, bufferPreset = BufferPreset.STABLE, lastChannelKey = "x:6775", lastGroup = "Sports") }
        val updated = store.settings.first()
        assertEquals(7L, updated.activePlaylistId)
        assertEquals(BufferPreset.STABLE, updated.bufferPreset)
        assertEquals("x:6775", updated.lastChannelKey)
        assertEquals("Sports", updated.lastGroup)

        store.update { it.copy(lastGroup = null) }
        assertNull(store.settings.first().lastGroup)
    }

    @Test
    fun bufferPresetValuesMatchSpec() {
        assertEquals(listOf(5_000, 20_000, 750, 2_000), BufferPreset.FAST.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(15_000, 40_000, 1_500, 3_000), BufferPreset.NORMAL.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(30_000, 90_000, 2_500, 5_000), BufferPreset.STABLE.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
    }
}
