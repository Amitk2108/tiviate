package app.tiviate.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.tiviate.data.AppSettings
import app.tiviate.data.ChannelRepository
import app.tiviate.data.SettingsStore
import app.tiviate.core.catchup.Catchup
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.sync.HttpClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * App-wide owner of the single player. The guide preview and fullscreen use the same engine, so
 * opening a channel from the preview never reconnects — important for providers that allow only
 * one connection.
 */
class PlayerController(
    private val context: Context,
    private val http: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val channels: ChannelRepository,
    private val scope: CoroutineScope,
) {
    private var engineOrNull: Media3Engine? = null
    private var settings = AppSettings()

    private val _previous = MutableStateFlow<ChannelEntity?>(null)
    /** The channel watched before the current one ("Previous channel"). */
    val previous: StateFlow<ChannelEntity?> = _previous

    /** True while the fullscreen player is on screen (picture-in-picture on Home only applies there). */
    /** The video is in view (fullscreen, or on top of the phone's Live screen): Home may switch to picture-in-picture. */
    @Volatile var fullscreenVisible: Boolean = false

    /** Picture-in-picture is showing; the player screen hides its panels then. */
    val inPip = MutableStateFlow(false)

    val pipOnHome: Boolean get() = settings.pipOnHome

    private val _current = MutableStateFlow<ChannelEntity?>(null)
    val current: StateFlow<ChannelEntity?> = _current

    private var historyJob: Job? = null

    /** A programme playing from the channel's archive (catch-up). */
    data class CatchupPlay(val title: String?, val startMs: Long, val stopMs: Long)
    private val _catchup = MutableStateFlow<CatchupPlay?>(null)
    /** The archived programme playing, or null while watching live. */
    val catchup: StateFlow<CatchupPlay?> = _catchup
    private var catchupUserAgent: String? = null

    /** A film or episode is playing (Movies & Series); live channels are off meanwhile. */
    @Volatile private var vodActive = false
    /** The film or episode reached its end. */
    val vodEnded = MutableStateFlow(false)

    /** Plays a film or episode from [startAtMs]; the live channel stops (it comes back on the live screens). */
    fun playVod(url: String, key: String, startAtMs: Long, userAgent: String?, subtitle: ExternalSubtitle? = null) {
        if (!foreground) return
        rowJob?.cancel()
        historyJob?.cancel()
        _current.value = null
        _catchup.value = null
        vodActive = true
        vodEnded.value = false
        engine.play(url, emptyMap(), userAgent ?: settings.defaultUserAgent ?: HttpClients.defaultUserAgent, "vod:$key", startAtMs, subtitle = subtitle)
    }

    /** Leaves a film or episode: the player stops, so the live screens start the channel again. */
    fun stopVod() {
        if (!vodActive) return
        vodActive = false
        engineOrNull?.stop()
    }

    /** The parental PIN was entered: locked channels play until the app leaves the screen. */
    val unlocked = MutableStateFlow(false)
    /** A locked channel is waiting for the PIN; runs once the PIN is right. */
    val pinRequest = MutableStateFlow<(() -> Unit)?>(null)

    /** True when [channel] needs the PIN first (the request is then shown and replayed after it). */
    private fun needsPin(channel: ChannelEntity, retry: () -> Unit): Boolean {
        if (unlocked.value || !app.tiviate.data.Parental.locked(settings, channel)) return false
        pinRequest.value = retry
        return true
    }

    /** Sleep timer: app-wide, so it keeps counting in the guide too. */
    val sleep = SleepTimer()
    private val _sleepMinutes = MutableStateFlow<Int?>(null)
    /** Minutes left (null = off), refreshed while the timer runs. */
    val sleepMinutes: StateFlow<Int?> = _sleepMinutes
    private val _sleepDue = MutableStateFlow(false)
    /** True once the timer fired: playback is stopped and the app should close. */
    val sleepDue: StateFlow<Boolean> = _sleepDue
    private var sleepJob: Job? = null
    private var rowJob: Job? = null

    /**
     * Created on first use so app start doesn't pay for codec/player setup. ExoPlayer must be
     * created on the main thread, so creation is always marshalled there.
     */
    val engine: Media3Engine
        get() {
            engineOrNull?.let { return it }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return Media3Engine(context, http, settings).also { engineOrNull = it; it.onEnded = ::archiveEnded }
            }
            val created = java.util.concurrent.CompletableFuture<Media3Engine>()
            Handler(Looper.getMainLooper()).post { created.complete(engine) }
            return created.get()
        }

    init {
        scope.launch {
            settingsStore.settings.collect { s ->
                settings = s
                engineOrNull?.reconfigure(s)
            }
        }
    }

    /**
     * False while the app is off screen (Home, TV standby). A zap, number entry or list autoplay that was
     * still pending then must not start a stream behind the TV's home screen (and hold the connection).
     */
    @Volatile var foreground = true

    /** Tunes [channel]. Re-tuning the channel that is already playing is a no-op (no reconnect). */
    fun tune(channel: ChannelEntity, playlistUserAgent: String?) {
        if (!foreground) return
        lastUserAgent = playlistUserAgent
        if (needsPin(channel) { tune(channel, playlistUserAgent) }) return
        val already = _current.value?.stableKey == channel.stableKey && _current.value?.playlistId == channel.playlistId
        if (already && engine.state.value.error == null && engine.state.value.channelKey == channel.stableKey) return
        _current.value?.takeIf { it.stableKey != channel.stableKey }?.let { _previous.value = it }
        _current.value = channel
        _catchup.value = null
        vodActive = false
        engine.play(
            channel.streamUrl, headers(channel), userAgent(channel, playlistUserAgent), channel.stableKey,
            drm = DrmSupport.of(channel), manifestMime = DrmSupport.mime(channel.manifestType),
        )
        scope.launch { settingsStore.update { it.copy(lastChannelKey = channel.stableKey) } }
        trackHistory(channel)
        followRow(channel)
    }

    private fun headers(channel: ChannelEntity) = buildMap { channel.referrer?.let { put("Referer", it) } }

    private fun userAgent(channel: ChannelEntity, playlistUserAgent: String?) =
        channel.userAgent ?: playlistUserAgent ?: settings.defaultUserAgent ?: HttpClients.defaultUserAgent

    /**
     * Plays a programme that already aired from the channel's archive. False when the channel has no
     * archive for it (the caller then plays the channel live).
     */
    fun playCatchup(channel: ChannelEntity, playlist: PlaylistEntity?, title: String?, startMs: Long, stopMs: Long): Boolean {
        if (!foreground) return false
        val now = System.currentTimeMillis()
        if (!Catchup.available(channel.catchupType, channel.catchupSource, channel.catchupDays, startMs, stopMs, now)) return false
        if (needsPin(channel) { playCatchup(channel, playlist, title, startMs, stopMs) }) return true
        val zone = playlist?.serverTimezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneOffset.UTC
        val urls = Catchup.urls(channel.catchupType, channel.catchupSource, channel.streamUrl, startMs, stopMs, now, zone).ifEmpty { return false }
        _current.value?.takeIf { it.stableKey != channel.stableKey }?.let { _previous.value = it }
        _current.value = channel
        _catchup.value = CatchupPlay(title, startMs, stopMs)
        vodActive = false
        catchupUserAgent = playlist?.userAgent
        engine.play(
            urls.first(), headers(channel), userAgent(channel, playlist?.userAgent), "${channel.stableKey}@$startMs",
            drm = DrmSupport.of(channel), alternatives = urls.drop(1),
        )
        followRow(channel)
        return true
    }

    /** An archived programme reached its end: carry on with the channel live. */
    private fun archiveEnded(): Boolean {
        if (vodActive) { vodEnded.value = true; return true }
        val channel = _current.value ?: return false
        if (_catchup.value == null) return false
        Handler(Looper.getMainLooper()).post { tune(channel, catchupUserAgent) }
        return true
    }

    /** Keeps [current] in step with the database: a resync or guide link updates the playing channel. */
    private fun followRow(channel: ChannelEntity) {
        rowJob?.cancel()
        rowJob = scope.launch {
            channels.observe(channel.playlistId, channel.stableKey).filterNotNull().collect { row ->
                if (_current.value?.stableKey != row.stableKey) return@collect
                _current.value = row
                engineOrNull?.updateUrl(row.stableKey, row.streamUrl)
            }
        }
    }

    /** Starts the sleep timer for [minutes], or turns it off when null. */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) {
            sleep.cancel()
            _sleepMinutes.value = null
            return
        }
        sleep.start(minutes)
        _sleepMinutes.value = sleep.minutesLeft()
        sleepJob = scope.launch {
            while (isActive) {
                delay(15_000)
                _sleepMinutes.value = sleep.minutesLeft()
                if (sleep.isDue()) {
                    sleep.cancel()
                    _sleepMinutes.value = null
                    stop()
                    _sleepDue.value = true
                    break
                }
            }
        }
    }

    fun consumeSleepDue() {
        _sleepDue.value = false
    }

    /** The app left the screen: playback stops, and so does the sleep timer (it would close the app next time). */
    fun onAppHidden() {
        foreground = false
        unlocked.value = false // locked channels ask again next time
        pinRequest.value = null
        setSleepTimer(null)
        consumeSleepDue()
        stop()
    }

    /** The live channel stopped by [pauseForScreen], with its playlist's User-Agent. */
    private var stoppedForScreen: Pair<ChannelEntity, String?>? = null
    private var lastUserAgent: String? = null

    /** A screen without the video opened (Settings, Movies & Series…): live TV stops instead of playing on behind it. */
    fun pauseForScreen() {
        val channel = _current.value ?: return
        if (vodActive || _catchup.value != null || engineOrNull?.state?.value?.channelKey == null) return
        stoppedForScreen = channel to lastUserAgent
        stop()
    }

    /** Back on a screen with the video: the channel [pauseForScreen] stopped plays again, live. */
    fun resumeAfterScreen() {
        val (channel, userAgent) = stoppedForScreen ?: return
        stoppedForScreen = null
        if (_current.value == null) tune(channel, userAgent)
    }

    fun stop() {
        historyJob?.cancel()
        rowJob?.cancel()
        engineOrNull?.stop()
        _current.value = null
        _catchup.value = null
    }

    fun release() {
        stop()
        engineOrNull?.release()
        engineOrNull = null
    }

    /** Recent records a channel after the chosen delay (5 s by default), then adds watch time every minute. */
    private fun trackHistory(channel: ChannelEntity) {
        historyJob?.cancel()
        historyJob = scope.launch {
            val delaySec = settings.recentDelaySec.coerceIn(1, 120)
            delay(delaySec * 1_000L)
            channels.recordWatch(channel.playlistId, channel.stableKey, delaySec, keep = settings.recentCount)
            while (isActive) {
                delay(60_000)
                channels.recordWatch(channel.playlistId, channel.stableKey, 60, keep = settings.recentCount)
            }
        }
    }

    suspend fun currentSettings(): AppSettings = settingsStore.settings.first()
}
