package app.tiviate.player

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tiviate.data.AppSettings
import app.tiviate.data.BufferPreset
import app.tiviate.data.sync.HttpClients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class Media3EngineTest {

    @Test
    fun englishChannelsGetSubtitlesHindiChannelsDoNot() {
        assertTrue(autoSubtitles(audioLanguage = "en", enabled = true, forAll = false))
        assertTrue(autoSubtitles(audioLanguage = "eng", enabled = true, forAll = false))
        assertFalse(autoSubtitles(audioLanguage = "hi", enabled = true, forAll = false))
        assertFalse("unknown language: leave them off", autoSubtitles(audioLanguage = null, enabled = true, forAll = false))
        assertFalse("setting off", autoSubtitles(audioLanguage = "en", enabled = false, forAll = false))
        assertFalse("subtitles for all channels already decide", autoSubtitles(audioLanguage = "en", enabled = true, forAll = true))
        assertEquals(1, pickSubtitle(listOf("hi", "en", null)))
        assertEquals(2, pickSubtitle(listOf("hi", "ta", null)))
        assertEquals(null, pickSubtitle(listOf("hi", "ta")))
    }

    @Test
    fun audioSyncShiftsTheClockButNeverBelowZero() {
        assertEquals(1_250_000L, shiftAudioClock(1_000_000L, 250_000L))
        assertEquals(750_000L, shiftAudioClock(1_000_000L, -250_000L))
        assertEquals(0L, shiftAudioClock(100_000L, -500_000L))
        val unknown = androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
        assertEquals(unknown, shiftAudioClock(unknown, 250_000L))
    }

    @Test
    fun constructsWithIdleStateAndRebuildsOnSettingsChange() {
        val engine = Media3Engine(ApplicationProvider.getApplicationContext(), HttpClients.create(), AppSettings())
        assertFalse(engine.state.value.isPlaying)
        assertEquals(null, engine.state.value.channelKey)
        val before = engine.player
        engine.reconfigure(AppSettings(bufferPreset = BufferPreset.LARGE))
        assertFalse("buffer preset change must rebuild the player", before === engine.player)
        engine.release()
    }

    @Test
    fun audioLanguageForAllChannelsIsPreferred() {
        val engine = Media3Engine(ApplicationProvider.getApplicationContext(), HttpClients.create(), AppSettings())
        assertEquals(emptyList<String>(), engine.player.trackSelectionParameters.preferredAudioLanguages)
        val before = engine.player
        engine.reconfigure(AppSettings(preferredAudioLanguage = "hi"))
        assertEquals(listOf("hi"), engine.player.trackSelectionParameters.preferredAudioLanguages)
        assertTrue("a language change must not rebuild the player", before === engine.player)
        engine.reconfigure(AppSettings(preferredAudioLanguage = null))
        assertEquals(emptyList<String>(), engine.player.trackSelectionParameters.preferredAudioLanguages)
        engine.release()
    }

    @Test
    fun subtitlesForAllChannelsTurnTextOnWithTheLanguage() {
        val engine = Media3Engine(ApplicationProvider.getApplicationContext(), HttpClients.create(), AppSettings(subtitlesForAll = true, preferredTextLanguage = "en"))
        val p = engine.player.trackSelectionParameters
        assertFalse(androidx.media3.common.C.TRACK_TYPE_TEXT in p.disabledTrackTypes)
        assertEquals(listOf("en"), p.preferredTextLanguages)
        engine.reconfigure(AppSettings(subtitlesForAll = false))
        assertTrue(androidx.media3.common.C.TRACK_TYPE_TEXT in engine.player.trackSelectionParameters.disabledTrackTypes)
        engine.release()
    }
}
