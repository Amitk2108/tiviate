package app.tiviate.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.tiviate.data.AppSettings
import app.tiviate.data.ChannelRepository
import app.tiviate.data.SettingsStore
import app.tiviate.data.db.ChannelEntity
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
    @Volatile var fullscreenVisible: Boolean = false

    /** Picture-in-picture is showing; the player screen hides its panels then. */
    val inPip = MutableStateFlow(false)

    val pipOnHome: Boolean get() = settings.pipOnHome

    private val _current = MutableStateFlow<ChannelEntity?>(null)
    val current: StateFlow<ChannelEntity?> = _current

    private var historyJob: Job? = null

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
            if (Looper.myLooper() == Looper.getMainLooper()) return Media3Engine(context, http, settings).also { engineOrNull = it }
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
        val already = _current.value?.stableKey == channel.stableKey && _current.value?.playlistId == channel.playlistId
        if (already && engine.state.value.error == null && engine.state.value.channelKey == channel.stableKey) return
        _current.value?.takeIf { it.stableKey != channel.stableKey }?.let { _previous.value = it }
        _current.value = channel
        val headers = buildMap { channel.referrer?.let { put("Referer", it) } }
        val ua = channel.userAgent ?: playlistUserAgent ?: settings.defaultUserAgent ?: HttpClients.defaultUserAgent
        engine.play(channel.streamUrl, headers, ua, channel.stableKey)
        scope.launch { settingsStore.update { it.copy(lastChannelKey = channel.stableKey) } }
        trackHistory(channel)
        followRow(channel)
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
        setSleepTimer(null)
        consumeSleepDue()
        stop()
    }

    fun stop() {
        historyJob?.cancel()
        rowJob?.cancel()
        engineOrNull?.stop()
        _current.value = null
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
