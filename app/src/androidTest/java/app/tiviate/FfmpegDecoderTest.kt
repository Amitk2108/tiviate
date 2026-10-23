package app.tiviate

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled FFmpeg audio decoder (AC3/E-AC3/DTS) loads and plugs into this Media3 version. */
@RunWith(AndroidJUnit4::class)
class FfmpegDecoderTest {

    @Test
    fun nativeLibraryLoadsAndDecodesSurroundFormats() {
        assertTrue("libffmpegJNI must load", FfmpegLibrary.isAvailable())
        for (mime in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_DTS)) {
            assertTrue("$mime", FfmpegLibrary.supportsFormat(mime))
        }
    }

    @Test
    fun playerBuildsTheFfmpegRendererAndItAcceptsAc3() {
        var support = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val renderers = DefaultRenderersFactory(ApplicationProvider.getApplicationContext())
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .createRenderers(Handler(Looper.getMainLooper()), object : VideoRendererEventListener {}, object : AudioRendererEventListener {}, {}, {})
            val ffmpeg = renderers.filterIsInstance<FfmpegAudioRenderer>()
            assertEquals(1, ffmpeg.size)
            val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(48_000).build()
            support = RendererCapabilities.getFormatSupport(ffmpeg.single().supportsFormat(ac3))
            renderers.forEach { it.release() }
        }
        assertEquals(C.FORMAT_HANDLED, support)
    }
}
