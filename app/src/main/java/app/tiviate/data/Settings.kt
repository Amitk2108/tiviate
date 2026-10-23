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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Buffer size: minimum/maximum buffer and how much must be buffered to start / restart after a stall. */
enum class BufferPreset(val label: String, val minMs: Int, val maxMs: Int, val startMs: Int, val restartMs: Int) {
    SMALL("Small (fastest start)", 5_000, 20_000, 750, 2_000),
    MEDIUM("Medium", 15_000, 40_000, 1_500, 3_000),
    LARGE("Large", 30_000, 90_000, 2_500, 5_000),
    VERY_LARGE("Very large (weak connections)", 60_000, 180_000, 4_000, 8_000),
    ;

    companion object {
        /** Reads a stored name, including the names older versions saved. */
        fun fromStored(name: String?): BufferPreset = when (name) {
            null -> MEDIUM
            "FAST" -> SMALL
            "NORMAL" -> MEDIUM
            "STABLE" -> LARGE
            else -> runCatching { valueOf(name) }.getOrDefault(MEDIUM)
        }
    }
}

/** How the TV guide marks programmes on air. */
enum class GuideHighlight(val label: String) { ON_AIR("On-air programmes"), PROGRESS("Progress only"), NONE("None") }

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
    val bufferPreset: BufferPreset = BufferPreset.MEDIUM,
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
    /** How many channels the Recent list keeps, and how long a channel must play to be added. */
    val recentCount: Int = 50,
    val recentDelaySec: Int = 5,
    /** Channel list: the programme on now under each name; the description in the details card. */
    val listShowProgramme: Boolean = true,
    val listShowDescription: Boolean = true,
    val guideHighlight: GuideHighlight = GuideHighlight.ON_AIR,
    /** Channel list and options close after this many idle seconds; 0 = stay until closed. */
    val panelTimeoutSec: Int = 0,
    /** Display mode chosen for single channels ("<playlistId>:<stableKey>"), overriding [displayMode]. */
    val channelDisplayModes: Map<String, DisplayMode> = emptyMap(),
) {
    fun displayModeFor(channelKey: String?): DisplayMode = channelKey?.let { channelDisplayModes[it] } ?: displayMode
}

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
            p[RECENT_COUNT] = next.recentCount
            p[RECENT_DELAY] = next.recentDelaySec
            p[LIST_SHOW_PROGRAMME] = next.listShowProgramme
            p[LIST_SHOW_DESCRIPTION] = next.listShowDescription
            p[GUIDE_HIGHLIGHT] = next.guideHighlight.name
            p[PANEL_TIMEOUT] = next.panelTimeoutSec
            p[CHANNEL_DISPLAY] = next.channelDisplayModes.map { (k, v) -> "${v.name}=$k" }.toSet()
        }
    }

    private fun read(p: Preferences) = AppSettings(
        activePlaylistId = p[ACTIVE_PLAYLIST],
        bufferPreset = BufferPreset.fromStored(p[BUFFER]),
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
        recentCount = p[RECENT_COUNT] ?: 50,
        recentDelaySec = p[RECENT_DELAY] ?: 5,
        listShowProgramme = p[LIST_SHOW_PROGRAMME] ?: true,
        listShowDescription = p[LIST_SHOW_DESCRIPTION] ?: true,
        guideHighlight = p[GUIDE_HIGHLIGHT]?.let { runCatching { GuideHighlight.valueOf(it) }.getOrNull() } ?: GuideHighlight.ON_AIR,
        panelTimeoutSec = p[PANEL_TIMEOUT] ?: 0,
        channelDisplayModes = p[CHANNEL_DISPLAY].orEmpty().mapNotNull { entry ->
            val mode = runCatching { DisplayMode.valueOf(entry.substringBefore('=')) }.getOrNull() ?: return@mapNotNull null
            entry.substringAfter('=') to mode
        }.toMap(),
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
        private val RECENT_COUNT = intPreferencesKey("recent_count")
        private val RECENT_DELAY = intPreferencesKey("recent_delay_sec")
        private val LIST_SHOW_PROGRAMME = booleanPreferencesKey("list_show_programme")
        private val LIST_SHOW_DESCRIPTION = booleanPreferencesKey("list_show_description")
        private val GUIDE_HIGHLIGHT = stringPreferencesKey("guide_highlight")
        private val PANEL_TIMEOUT = intPreferencesKey("panel_timeout_sec")
        private val CHANNEL_DISPLAY = stringSetPreferencesKey("channel_display_modes")

        fun create(context: Context): SettingsStore = forFile(context.preferencesDataStoreFile("settings"))

        fun forFile(file: File): SettingsStore = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}
