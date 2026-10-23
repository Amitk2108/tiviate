package app.tiviate.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import app.tiviate.data.AppSettings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/**
 * Media3 ExoPlayer tuned for live IPTV on TV boxes: hardware decoding with fallback, fast start,
 * live speed pinned at 1.0×, automatic reconnect with backoff and a stall watchdog.
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    private val context: Context,
    private val http: OkHttpClient,
    private var settings: AppSettings,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val watchdog: StallWatchdog = StallWatchdog(),
) : PlaybackEngine {

    private data class Request(
        val url: String,
        val headers: Map<String, String>,
        val userAgent: String,
        val channelKey: String,
        val forcedMime: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    private var request: Request? = null
    private var attempt = 0
    private var pausedAtMs: Long? = null
    private var boundView: PlayerView? = null

    var player: ExoPlayer = buildPlayer(settings)
        private set

    private val retryRunnable = Runnable { startCurrent() }
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (request != null && watchdog.onSample(SystemClock.elapsedRealtime(), p.isPlaying, p.playbackState == Player.STATE_BUFFERING, p.currentPosition)) {
                startCurrent()
            }
            main.postDelayed(this, 1_000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) attempt = 0
            _state.update { it.copy(isPlaying = isPlaying, error = if (isPlaying) null else it.error, retrying = if (isPlaying) 0 else it.retrying) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = tracks.selectedFormat(C.TRACK_TYPE_VIDEO)
            val audio = tracks.selectedFormat(C.TRACK_TYPE_AUDIO)
            _state.update {
                it.copy(frameRate = video?.frameRate?.takeIf { f -> f > 0 }, audioLabel = audio?.let(::audioBadge))
            }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    init {
        main.post(tick)
    }

    private fun buildPlayer(s: AppSettings): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (s.preferSoftwareDecoder) renderers.setMediaCodecSelector(SOFTWARE_FIRST)

        val preset = s.bufferPreset
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(preset.minMs, preset.maxMs, preset.startMs, preset.restartMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(s.tunneling))
        }
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
                playWhenReady = true
                addListener(listener)
            }
    }

    /** Rebuilds the player when buffer/decoder/tunneling settings change, resuming the current channel. */
    fun reconfigure(newSettings: AppSettings) {
        val needsRebuild = newSettings.bufferPreset != settings.bufferPreset ||
            newSettings.preferSoftwareDecoder != settings.preferSoftwareDecoder ||
            newSettings.tunneling != settings.tunneling
        settings = newSettings
        if (!needsRebuild) return
        player.removeListener(listener)
        player.release()
        player = buildPlayer(newSettings)
        boundView?.player = player
        if (request != null) startCurrent()
    }

    override fun play(url: String, headers: Map<String, String>, userAgent: String, channelKey: String) {
        main.removeCallbacks(retryRunnable)
        request = Request(url, headers, userAgent, channelKey)
        attempt = 0
        pausedAtMs = null
        _state.value = PlaybackState(channelKey = channelKey, isBuffering = true)
        startCurrent()
    }

    private fun startCurrent() {
        val r = request ?: return
        main.removeCallbacks(retryRunnable)
        watchdog.reset()
        val dataSource = OkHttpDataSource.Factory(http)
            .setUserAgent(r.userAgent)
            .setDefaultRequestProperties(r.headers)
        val item = MediaItem.Builder()
            .setUri(r.url)
            .setMimeType(r.forcedMime ?: StreamType.mimeFromUrl(r.url))
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
            .build()
        val source = DefaultMediaSourceFactory(dataSource, EXTRACTORS).createMediaSource(item)
        player.setMediaSource(source, /* resetPosition = */ true)
        player.prepare()
        player.play()
    }

    private fun handleError(error: PlaybackException) {
        val r = request ?: return
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && r.forcedMime == null -> {
                // URL without an extension that is actually HLS: retry once as HLS.
                request = r.copy(forcedMime = MimeTypes.APPLICATION_M3U8)
                startCurrent()
                return
            }
        }
        val delay = retryPolicy.nextDelay(attempt)
        if (delay == null) {
            _state.update { it.copy(isBuffering = false, isPlaying = false, error = describe(error), retrying = 0) }
            return
        }
        attempt++
        _state.update { it.copy(isBuffering = true, retrying = attempt) }
        main.postDelayed(retryRunnable, delay)
    }

    override fun togglePause() {
        if (request == null) return
        val paused = pausedAtMs
        if (paused == null) {
            player.pause()
            pausedAtMs = SystemClock.elapsedRealtime()
            _state.update { it.copy(isPaused = true) }
        } else {
            pausedAtMs = null
            _state.update { it.copy(isPaused = false) }
            // Long pauses on live streams: rejoin the live edge with a fresh connection.
            if (SystemClock.elapsedRealtime() - paused > LIVE_REJOIN_MS) startCurrent() else player.play()
        }
    }

    override fun retryNow() {
        attempt = 0
        _state.update { it.copy(error = null, isBuffering = true) }
        startCurrent()
    }

    override fun stop() {
        main.removeCallbacks(retryRunnable)
        request = null
        pausedAtMs = null
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
    }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.release()
    }

    override fun audioTracks(): List<TrackOption> = options(C.TRACK_TYPE_AUDIO)
    override fun textTracks(): List<TrackOption> = options(C.TRACK_TYPE_TEXT)

    override fun selectAudio(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    override fun selectText(option: TrackOption?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun bindView(view: PlayerView) {
        boundView = view
        view.player = player
    }

    private fun options(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        player.currentTracks.groups.forEachIndexed { gi, group ->
            if (group.type != type) return@forEachIndexed
            for (ti in 0 until group.length) {
                if (!group.isTrackSupported(ti)) continue
                val f = group.getTrackFormat(ti)
                val name = f.label ?: f.language?.let { Locale.forLanguageTag(it).displayLanguage.takeIf(String::isNotBlank) } ?: "Track ${out.size + 1}"
                val label = if (type == C.TRACK_TYPE_AUDIO) listOfNotNull(name, audioBadge(f)).joinToString(" · ") else name
                out += TrackOption(gi, ti, label, group.isTrackSelected(ti))
            }
        }
        return out
    }

    private fun Tracks.selectedFormat(type: Int): Format? {
        for (group in groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
        return null
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused this stream (HTTP error)."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the stream. Check the internet connection."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode this channel's format."
        else -> "Playback failed (${error.errorCodeName})."
    }

    companion object {
        private const val LIVE_REJOIN_MS = 30_000L

        private val EXTRACTORS = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setConstantBitrateSeekingEnabled(true)

        private val SOFTWARE_FIRST = MediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling).sortedBy { it.hardwareAccelerated }
        }

        fun audioBadge(f: Format): String? {
            val codec = when (f.sampleMimeType) {
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_MPEG_L2 -> "MP2"
                MimeTypes.AUDIO_OPUS -> "Opus"
                else -> null
            }
            val channels = when (f.channelCount) {
                1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> null; else -> "${f.channelCount}ch"
            }
            return listOfNotNull(codec, channels).joinToString(" ").ifEmpty { null }
        }
    }
}
