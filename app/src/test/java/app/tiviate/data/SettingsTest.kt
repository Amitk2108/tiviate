package app.tiviate.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertFalse("TV-guide start is the default", defaults.startFullscreen)

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
    fun b1SettingsHaveDefaultsAndPersist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore.forFile(File(context.filesDir, "settings-b1-${System.nanoTime()}.preferences_pb"))

        val d = store.settings.first()
        assertTrue("exit asks for a second Back", d.confirmExit)
        assertEquals(UpDownMode.UP_NEXT_DOWN_OPTIONS, d.upDownMode)
        assertNull(d.preferredAudioLanguage)
        assertFalse(d.subtitlesForAll)
        assertNull(d.preferredTextLanguage)
        assertEquals(DisplayMode.FIT, d.displayMode)
        assertEquals(100, d.zoomPercent)
        assertFalse(d.autoStartOnBoot)
        assertEquals(1, d.epgPastDays)
        assertTrue(d.updateOnStart)
        assertEquals(12, d.updateIntervalHours)

        store.update {
            it.copy(
                confirmExit = false, upDownMode = UpDownMode.ZAP, preferredAudioLanguage = "hi", subtitlesForAll = true,
                preferredTextLanguage = "en", displayMode = DisplayMode.RATIO_16_9, zoomPercent = 120, autoStartOnBoot = true,
                epgPastDays = 3, updateOnStart = false, updateIntervalHours = 24,
            )
        }
        val s = store.settings.first()
        assertFalse(s.confirmExit)
        assertEquals(UpDownMode.ZAP, s.upDownMode)
        assertEquals("hi", s.preferredAudioLanguage)
        assertTrue(s.subtitlesForAll)
        assertEquals("en", s.preferredTextLanguage)
        assertEquals(DisplayMode.RATIO_16_9, s.displayMode)
        assertEquals(120, s.zoomPercent)
        assertTrue(s.autoStartOnBoot)
        assertEquals(3, s.epgPastDays)
        assertFalse(s.updateOnStart)
        assertEquals(24, s.updateIntervalHours)

        store.update { it.copy(preferredAudioLanguage = null) }
        assertNull("Reset clears the language", store.settings.first().preferredAudioLanguage)
    }

    @Test
    fun bufferPresetValuesMatchSpec() {
        assertEquals(listOf(5_000, 20_000, 750, 2_000), BufferPreset.FAST.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(15_000, 40_000, 1_500, 3_000), BufferPreset.NORMAL.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(30_000, 90_000, 2_500, 5_000), BufferPreset.STABLE.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
    }
}
