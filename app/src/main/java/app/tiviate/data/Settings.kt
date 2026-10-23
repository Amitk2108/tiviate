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

/** What D-pad Up/Down do while a channel plays fullscreen. */
enum class UpDownMode { UP_NEXT_DOWN_OPTIONS, ZAP }

/** How the picture fills the screen; applies to all channels. */
enum class DisplayMode(val label: String) {
    FIT("Fit"),
    FILL("Stretch"),
    ZOOM("Fill screen"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
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
    /** Open straight into the last channel fullscreen; off = start on the TV guide with the preview. */
    val startFullscreen: Boolean = false,
    /** Back on the guide asks for a second press before leaving. */
    val confirmExit: Boolean = true,
    val upDownMode: UpDownMode = UpDownMode.UP_NEXT_DOWN_OPTIONS,
    /** Audio language picked "for all channels" (ISO 639 code from the stream). */
    val preferredAudioLanguage: String? = null,
    val subtitlesForAll: Boolean = false,
    val preferredTextLanguage: String? = null,
    val displayMode: DisplayMode = DisplayMode.FIT,
    val zoomPercent: Int = 100,
    val autoStartOnBoot: Boolean = false,
    val epgPastDays: Int = 1,
    val updateOnStart: Boolean = true,
    val updateIntervalHours: Int = 12,
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
            p[START_FULLSCREEN] = next.startFullscreen
            p[CONFIRM_EXIT] = next.confirmExit
            p[UP_DOWN] = next.upDownMode.name
            p.setOrRemove(AUDIO_LANG, next.preferredAudioLanguage)
            p[SUBS_FOR_ALL] = next.subtitlesForAll
            p.setOrRemove(TEXT_LANG, next.preferredTextLanguage)
            p[DISPLAY_MODE] = next.displayMode.name
            p[ZOOM] = next.zoomPercent
            p[AUTO_START] = next.autoStartOnBoot
            p[EPG_PAST_DAYS] = next.epgPastDays
            p[UPDATE_ON_START] = next.updateOnStart
            p[UPDATE_INTERVAL] = next.updateIntervalHours
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
        startFullscreen = p[START_FULLSCREEN] ?: false,
        confirmExit = p[CONFIRM_EXIT] ?: true,
        upDownMode = p[UP_DOWN]?.let { runCatching { UpDownMode.valueOf(it) }.getOrNull() } ?: UpDownMode.UP_NEXT_DOWN_OPTIONS,
        preferredAudioLanguage = p[AUDIO_LANG],
        subtitlesForAll = p[SUBS_FOR_ALL] ?: false,
        preferredTextLanguage = p[TEXT_LANG],
        displayMode = p[DISPLAY_MODE]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.FIT,
        zoomPercent = p[ZOOM] ?: 100,
        autoStartOnBoot = p[AUTO_START] ?: false,
        epgPastDays = p[EPG_PAST_DAYS] ?: 1,
        updateOnStart = p[UPDATE_ON_START] ?: true,
        updateIntervalHours = p[UPDATE_INTERVAL] ?: 12,
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
        private val START_FULLSCREEN = booleanPreferencesKey("start_fullscreen")
        private val CONFIRM_EXIT = booleanPreferencesKey("confirm_exit")
        private val UP_DOWN = stringPreferencesKey("up_down_mode")
        private val AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        private val SUBS_FOR_ALL = booleanPreferencesKey("subtitles_for_all")
        private val TEXT_LANG = stringPreferencesKey("preferred_text_language")
        private val DISPLAY_MODE = stringPreferencesKey("display_mode")
        private val ZOOM = intPreferencesKey("zoom_percent")
        private val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
        private val EPG_PAST_DAYS = intPreferencesKey("epg_past_days")
        private val UPDATE_ON_START = booleanPreferencesKey("update_on_start")
        private val UPDATE_INTERVAL = intPreferencesKey("update_interval_hours")

        fun create(context: Context): SettingsStore = forFile(context.preferencesDataStoreFile("settings"))

        fun forFile(file: File): SettingsStore = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
