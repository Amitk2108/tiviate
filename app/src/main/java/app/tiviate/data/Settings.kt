package app.tiviate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Player buffering presets: minimum/maximum buffer and how much must be buffered to start / restart after a stall. */
enum class BufferPreset(val minMs: Int, val maxMs: Int, val startMs: Int, val restartMs: Int) {
    FAST(5_000, 20_000, 750, 2_000),
    NORMAL(15_000, 40_000, 1_500, 3_000),
    STABLE(30_000, 90_000, 2_500, 5_000),
}

data class AppSettings(
    val activePlaylistId: Long? = null,
    val bufferPreset: BufferPreset = BufferPreset.NORMAL,
    val preferSoftwareDecoder: Boolean = false,
    val tunneling: Boolean = false,
    val matchRefreshRate: Boolean = false,
    val defaultUserAgent: String? = null,
    val showChannelNumbers: Boolean = true,
    val showClock: Boolean = true,
    val bannerTimeoutMs: Int = 4_000,
    val lastChannelKey: String? = null,
    val lastGroup: String? = null,
)

class SettingsStore private constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map(::read)

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val current = read(p)
            val next = transform(current)
            p.setOrRemove(ACTIVE_PLAYLIST, next.activePlaylistId)
            p[BUFFER] = next.bufferPreset.name
            p[SOFTWARE_DECODER] = next.preferSoftwareDecoder
            p[TUNNELING] = next.tunneling
            p[MATCH_REFRESH] = next.matchRefreshRate
            p.setOrRemove(USER_AGENT, next.defaultUserAgent)
            p[SHOW_NUMBERS] = next.showChannelNumbers
            p[SHOW_CLOCK] = next.showClock
            p[BANNER_TIMEOUT] = next.bannerTimeoutMs
            p.setOrRemove(LAST_CHANNEL, next.lastChannelKey)
            p.setOrRemove(LAST_GROUP, next.lastGroup)
        }
    }

    private fun read(p: Preferences) = AppSettings(
        activePlaylistId = p[ACTIVE_PLAYLIST],
        bufferPreset = p[BUFFER]?.let { runCatching { BufferPreset.valueOf(it) }.getOrNull() } ?: BufferPreset.NORMAL,
        preferSoftwareDecoder = p[SOFTWARE_DECODER] ?: false,
        tunneling = p[TUNNELING] ?: false,
        matchRefreshRate = p[MATCH_REFRESH] ?: false,
        defaultUserAgent = p[USER_AGENT],
        showChannelNumbers = p[SHOW_NUMBERS] ?: true,
        showClock = p[SHOW_CLOCK] ?: true,
        bannerTimeoutMs = p[BANNER_TIMEOUT] ?: 4_000,
        lastChannelKey = p[LAST_CHANNEL],
        lastGroup = p[LAST_GROUP],
    )

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        private val ACTIVE_PLAYLIST = longPreferencesKey("active_playlist")
        private val BUFFER = stringPreferencesKey("buffer_preset")
        private val SOFTWARE_DECODER = booleanPreferencesKey("prefer_software_decoder")
        private val TUNNELING = booleanPreferencesKey("tunneling")
        private val MATCH_REFRESH = booleanPreferencesKey("match_refresh_rate")
        private val USER_AGENT = stringPreferencesKey("default_user_agent")
        private val SHOW_NUMBERS = booleanPreferencesKey("show_channel_numbers")
        private val SHOW_CLOCK = booleanPreferencesKey("show_clock")
        private val BANNER_TIMEOUT = intPreferencesKey("banner_timeout_ms")
        private val LAST_CHANNEL = stringPreferencesKey("last_channel_key")
        private val LAST_GROUP = stringPreferencesKey("last_group")

        fun create(context: Context): SettingsStore = forFile(context.preferencesDataStoreFile("settings"))

        fun forFile(file: File): SettingsStore = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
