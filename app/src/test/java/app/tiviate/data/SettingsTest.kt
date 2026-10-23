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
        assertEquals(BufferPreset.MEDIUM, defaults.bufferPreset)
        assertFalse(defaults.tunneling)
        assertEquals(4_000, defaults.bannerTimeoutMs)
        assertFalse("TV-guide start is the default", defaults.startFullscreen)

        store.update { it.copy(activePlaylistId = 7, bufferPreset = BufferPreset.LARGE, lastChannelKey = "x:6775", lastGroup = "Sports") }
        val updated = store.settings.first()
        assertEquals(7L, updated.activePlaylistId)
        assertEquals(BufferPreset.LARGE, updated.bufferPreset)
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
    fun bufferSizesSmallToVeryLarge() {
        assertEquals(listOf(5_000, 20_000, 750, 2_000), BufferPreset.SMALL.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(15_000, 40_000, 1_500, 3_000), BufferPreset.MEDIUM.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(30_000, 90_000, 2_500, 5_000), BufferPreset.LARGE.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals(listOf(60_000, 180_000, 4_000, 8_000), BufferPreset.VERY_LARGE.let { listOf(it.minMs, it.maxMs, it.startMs, it.restartMs) })
        assertEquals("older installs keep their choice", BufferPreset.LARGE, BufferPreset.fromStored("STABLE"))
        assertEquals(BufferPreset.SMALL, BufferPreset.fromStored("FAST"))
        assertEquals(BufferPreset.MEDIUM, BufferPreset.fromStored("NORMAL"))
        assertEquals(BufferPreset.MEDIUM, BufferPreset.fromStored(null))
    }

    @Test
    fun partlyDoneSettingsHaveDefaultsAndPersist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore.forFile(File(context.filesDir, "settings-pd-${System.nanoTime()}.preferences_pb"))
        val d = store.settings.first()
        assertEquals(50, d.recentCount)
        assertEquals(5, d.recentDelaySec)
        assertTrue(d.listShowProgramme)
        assertTrue(d.listShowDescription)
        assertEquals(GuideHighlight.ON_AIR, d.guideHighlight)
        assertEquals("panels stay until closed", 0, d.panelTimeoutSec)
        assertEquals(emptyMap<String, DisplayMode>(), d.channelDisplayModes)

        store.update {
            it.copy(
                recentCount = 100, recentDelaySec = 10, listShowProgramme = false, listShowDescription = false,
                guideHighlight = GuideHighlight.PROGRESS, panelTimeoutSec = 20,
                channelDisplayModes = mapOf("1:x:9" to DisplayMode.RATIO_4_3, "1:t:abc|news" to DisplayMode.ZOOM),
            )
        }
        val s = store.settings.first()
        assertEquals(100, s.recentCount)
        assertEquals(10, s.recentDelaySec)
        assertFalse(s.listShowProgramme)
        assertFalse(s.listShowDescription)
        assertEquals(GuideHighlight.PROGRESS, s.guideHighlight)
        assertEquals(20, s.panelTimeoutSec)
        assertEquals(mapOf("1:x:9" to DisplayMode.RATIO_4_3, "1:t:abc|news" to DisplayMode.ZOOM), s.channelDisplayModes)
        assertEquals("a channel's own mode wins", DisplayMode.RATIO_4_3, s.displayModeFor("1:x:9"))
        assertEquals("others use the all-channels mode", DisplayMode.FIT, s.displayModeFor("1:x:10"))
    }

    @Test
    fun cardsAddedInAnUpdateKeepTheirDefaultPlace() {
        val defaults = listOf("channels", "guide", "pause", "audio")
        assertEquals(listOf("channels", "pause", "guide", "audio"), mergeCardOrder(listOf("pause", "guide", "audio"), defaults))
        assertEquals("unknown cards are dropped", listOf("channels", "guide", "pause", "audio"), mergeCardOrder(listOf("audio", "old"), defaults))
    }
}
