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

    private val _current = MutableStateFlow<ChannelEntity?>(null)
    val current: StateFlow<ChannelEntity?> = _current

    private var historyJob: Job? = null
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

    /** Tunes [channel]. Re-tuning the channel that is already playing is a no-op (no reconnect). */
    fun tune(channel: ChannelEntity, playlistUserAgent: String?) {
        val already = _current.value?.stableKey == channel.stableKey && _current.value?.playlistId == channel.playlistId
        if (already && engine.state.value.error == null && engine.state.value.channelKey == channel.stableKey) return
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

    /** History records a channel after 5 s of watching, then adds watch time every minute. */
    private fun trackHistory(channel: ChannelEntity) {
        historyJob?.cancel()
        historyJob = scope.launch {
            delay(5_000)
            channels.recordWatch(channel.playlistId, channel.stableKey, 5)
            while (isActive) {
                delay(60_000)
                channels.recordWatch(channel.playlistId, channel.stableKey, 60)
            }
        }
    }

    suspend fun currentSettings(): AppSettings = settingsStore.settings.first()
}
