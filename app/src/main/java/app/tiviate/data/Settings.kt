package app.tiviate.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Enum names shown on screen, in the viewer's language. */
interface Labelled {
    val english: String
    val label: String get() = app.tiviate.ui.t(english)
}

/** Buffer size: minimum/maximum buffer and how much must be buffered to start / restart after a stall. */
enum class BufferPreset(override val english: String, val minMs: Int, val maxMs: Int, val startMs: Int, val restartMs: Int) : Labelled {
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

/** Order of channels inside a group. */
enum class ChannelSort(override val english: String) : Labelled {
    PLAYLIST("Playlist order"), NAME("Name"), NUMBER("Number"), NEWEST("Newest first"), MOST_WATCHED("Most watched"), MANUAL("My order"),
}

/** What a playlist opens on when you switch to it. */
enum class OpenOn(override val english: String) : Labelled { ALL("All channels"), FAVORITES("Favorites") }

enum class AccentColor(override val english: String, val argb: Long) : Labelled {
    CYAN("Cyan", 0xFF2EC5E8), BLUE("Blue", 0xFF3B8BFF), INDIGO("Indigo", 0xFF6C7BFF), PURPLE("Purple", 0xFFB06CFF),
    PINK("Pink", 0xFFFF5FA2), RED("Red", 0xFFFF5A5A), ORANGE("Orange", 0xFFFF9A3C), AMBER("Amber", 0xFFFFC23D),
    YELLOW("Yellow", 0xFFE8E04A), LIME("Lime", 0xFFA6E04A), GREEN("Green", 0xFF45D483), TEAL("Teal", 0xFF2BC2A8),
}

enum class BackgroundTheme(override val english: String) : Labelled { DARK("Dark"), BLACK("Black"), BLUE_GREY("Blue grey") }

enum class FontSize(override val english: String, val scale: Float) : Labelled { SMALL("Small", 0.9f), MEDIUM("Medium", 1.0f), LARGE("Large", 1.15f) }

enum class ClockSize(override val english: String) : Labelled { SMALL("Small"), MEDIUM("Medium"), LARGE("Large") }

/** A clock shown over the video in fullscreen. */
enum class ClockCorner(override val english: String) : Labelled { OFF("Off"), TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right") }

enum class LogoStyle(override val english: String) : Labelled { ROUNDED("Rounded"), SQUARE("Square"), CIRCLE("Circle") }

/** The fullscreen options cards, in their default order. */
val DEFAULT_OPTION_CARDS = listOf(
    "option_channels", "option_guide", "option_playpause", "option_previous", "option_audio", "option_subtitles", "option_display", "option_zoom",
    "option_quality", "option_audio_sync", "option_stats", "option_sleep", "option_favorite", "option_channel", "option_pip",
    "option_external", "option_search", "option_settings",
)

/** How the TV guide marks programmes on air. */
enum class GuideHighlight(override val english: String) : Labelled { ON_AIR("On-air programmes"), PROGRESS("Progress only"), NONE("None") }

/** What D-pad Up/Down do while a channel plays fullscreen. */
enum class UpDownMode { UP_NEXT_DOWN_OPTIONS, ZAP }

/** When playlists (channels and groups) update; "Update now" always works. The guide has its own interval. */
enum class PlaylistUpdate(override val english: String, val days: Int) : Labelled {
    ON_START("When Tiviate starts", 0),
    DAILY("Every day", 1),
    WEEKLY("Every week", 7),
    MONTHLY("Every month", 30),
}

/** How the picture fills the screen; applies to all channels. */
enum class DisplayMode(override val english: String) : Labelled {
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
    /** Last opened category (group, Favorites, Recent or a custom group), see [Category.remember]. */
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
    // --- B2: organising
    val channelSort: ChannelSort = ChannelSort.PLAYLIST,
    val playlistUpdate: PlaylistUpdate = PlaylistUpdate.DAILY,
    val openPlaylistOn: OpenOn = OpenOn.ALL,
    // --- B2: screens
    /** TV guide drawn over the full-screen video (instead of the preview window). */
    val guideOverlay: Boolean = false,
    /** Channel list with the video in a small window (instead of over the full-screen video). */
    val listPreview: Boolean = false,
    val stayOnList: Boolean = false,
    /** Tune the focused channel after this many seconds of browsing the list; 0 = off. */
    val autoplayDelaySec: Int = 0,
    // --- B2: player
    val optionCards: List<String> = DEFAULT_OPTION_CARDS,
    val hiddenOptionCards: Set<String> = emptySet(),
    /** Subtitles on by default when the channel's sound is English (Hindi and other channels stay off). */
    val englishSubtitles: Boolean = true,
    /** Look for a new Tiviate on GitHub Releases once a day. */
    val checkUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0,
    /** The version the viewer said "Later" to (not offered again; a newer one is). */
    val skippedUpdate: String? = null,
    val preferSurround: Boolean = false,
    /** Audio delay against the picture, ms (+ = sound later). */
    val audioOffsetMs: Int = 0,
    val pipOnHome: Boolean = false,
    val alwaysExternalPlayer: Boolean = false,
    val blackOnSwitch: Boolean = false,
    // --- B2: appearance
    val accent: AccentColor = AccentColor.CYAN,
    val backgroundTheme: BackgroundTheme = BackgroundTheme.DARK,
    val fontSize: FontSize = FontSize.MEDIUM,
    /** Panel transparency, 0 (solid) to 60 %. */
    val panelTransparency: Int = 0,
    val clockSize: ClockSize = ClockSize.MEDIUM,
    val clockShowDate: Boolean = false,
    val clockCorner: ClockCorner = ClockCorner.OFF,
    val logoStyle: LogoStyle = LogoStyle.ROUNDED,
    val twoLineNames: Boolean = false,
    val twoLineTitles: Boolean = false,
    val showPlaylistAndGroup: Boolean = false,
    val showAllChannels: Boolean = true,
    val showFavoritesGroup: Boolean = true,
    val showRecentGroup: Boolean = true,
    val animations: Boolean = true,
    /** UI language tag, null = the TV's language. */
    val language: String? = null,
) {
    fun displayModeFor(channelKey: String?): DisplayMode = channelKey?.let { channelDisplayModes[it] } ?: displayMode
}

class SettingsStore private constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map(::read)

    /**
     * What screens draw from. The "where the viewer was" fields (saved on every tune and group change)
     * are left out, so saving them doesn't redraw every screen.
     */
    val screenSettings: Flow<AppSettings> = settings
        .map { it.copy(lastChannelKey = null, lastGroup = null, lastUpdateCheck = 0L) }
        .distinctUntilChanged()

    /** Every stored setting, by key name (for backups). */
    suspend fun exportRaw(): Map<String, Any> = store.data.first().asMap().mapKeys { it.key.name }

    /** Replaces all settings with [values] (a restored backup). */
    @Suppress("UNCHECKED_CAST")
    suspend fun importRaw(values: Map<String, Any>) {
        store.edit { p ->
            p.clear()
            for ((k, v) in values) {
                when (v) {
                    is Boolean -> p[androidx.datastore.preferences.core.booleanPreferencesKey(k)] = v
                    is Int -> p[androidx.datastore.preferences.core.intPreferencesKey(k)] = v
                    is Long -> p[androidx.datastore.preferences.core.longPreferencesKey(k)] = v
                    is Float -> p[androidx.datastore.preferences.core.floatPreferencesKey(k)] = v
                    is Double -> p[androidx.datastore.preferences.core.doublePreferencesKey(k)] = v
                    is String -> p[androidx.datastore.preferences.core.stringPreferencesKey(k)] = v
                    is Set<*> -> p[androidx.datastore.preferences.core.stringSetPreferencesKey(k)] = v as Set<String>
                }
            }
        }
    }

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
            p[CHANNEL_SORT] = next.channelSort.name
            p[PLAYLIST_UPDATE] = next.playlistUpdate.name
            p[OPEN_ON] = next.openPlaylistOn.name
            p[GUIDE_OVERLAY] = next.guideOverlay
            p[LIST_PREVIEW] = next.listPreview
            p[STAY_ON_LIST] = next.stayOnList
            p[AUTOPLAY] = next.autoplayDelaySec
            p[OPTION_CARDS] = next.optionCards.joinToString(",")
            p[HIDDEN_CARDS] = next.hiddenOptionCards
            p[PREFER_SURROUND] = next.preferSurround
            p[ENGLISH_SUBTITLES] = next.englishSubtitles
            p[CHECK_UPDATES] = next.checkUpdates
            p[LAST_UPDATE_CHECK] = next.lastUpdateCheck
            p.setOrRemove(SKIPPED_UPDATE, next.skippedUpdate)
            p[AUDIO_OFFSET] = next.audioOffsetMs
            p[PIP_ON_HOME] = next.pipOnHome
            p[ALWAYS_EXTERNAL] = next.alwaysExternalPlayer
            p[BLACK_ON_SWITCH] = next.blackOnSwitch
            p[ACCENT] = next.accent.name
            p[BACKGROUND] = next.backgroundTheme.name
            p[FONT_SIZE] = next.fontSize.name
            p[TRANSPARENCY] = next.panelTransparency
            p[CLOCK_SIZE] = next.clockSize.name
            p[CLOCK_DATE] = next.clockShowDate
            p[CLOCK_CORNER] = next.clockCorner.name
            p[LOGO_STYLE] = next.logoStyle.name
            p[TWO_LINE_NAMES] = next.twoLineNames
            p[TWO_LINE_TITLES] = next.twoLineTitles
            p[SHOW_PLAYLIST_GROUP] = next.showPlaylistAndGroup
            p[SHOW_ALL] = next.showAllChannels
            p[SHOW_FAVORITES] = next.showFavoritesGroup
            p[SHOW_RECENT] = next.showRecentGroup
            p[ANIMATIONS] = next.animations
            p.setOrRemove(LANGUAGE, next.language)
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
        channelSort = enumOr(p[CHANNEL_SORT], ChannelSort.PLAYLIST),
        playlistUpdate = enumOr(p[PLAYLIST_UPDATE], PlaylistUpdate.DAILY),
        openPlaylistOn = enumOr(p[OPEN_ON], OpenOn.ALL),
        guideOverlay = p[GUIDE_OVERLAY] ?: false,
        listPreview = p[LIST_PREVIEW] ?: false,
        stayOnList = p[STAY_ON_LIST] ?: false,
        autoplayDelaySec = p[AUTOPLAY] ?: 0,
        optionCards = p[OPTION_CARDS]?.split(',')?.filter { it.isNotBlank() }?.let { saved ->
            mergeCardOrder(saved, DEFAULT_OPTION_CARDS) // new cards appear where they sit by default
        } ?: DEFAULT_OPTION_CARDS,
        hiddenOptionCards = p[HIDDEN_CARDS] ?: emptySet(),
        preferSurround = p[PREFER_SURROUND] ?: false,
        englishSubtitles = p[ENGLISH_SUBTITLES] ?: true,
        checkUpdates = p[CHECK_UPDATES] ?: true,
        lastUpdateCheck = p[LAST_UPDATE_CHECK] ?: 0L,
        skippedUpdate = p[SKIPPED_UPDATE],
        audioOffsetMs = p[AUDIO_OFFSET] ?: 0,
        pipOnHome = p[PIP_ON_HOME] ?: false,
        alwaysExternalPlayer = p[ALWAYS_EXTERNAL] ?: false,
        blackOnSwitch = p[BLACK_ON_SWITCH] ?: false,
        accent = enumOr(p[ACCENT], AccentColor.CYAN),
        backgroundTheme = enumOr(p[BACKGROUND], BackgroundTheme.DARK),
        fontSize = enumOr(p[FONT_SIZE], FontSize.MEDIUM),
        panelTransparency = p[TRANSPARENCY] ?: 0,
        clockSize = enumOr(p[CLOCK_SIZE], ClockSize.MEDIUM),
        clockShowDate = p[CLOCK_DATE] ?: false,
        clockCorner = enumOr(p[CLOCK_CORNER], ClockCorner.OFF),
        logoStyle = enumOr(p[LOGO_STYLE], LogoStyle.ROUNDED),
        twoLineNames = p[TWO_LINE_NAMES] ?: false,
        twoLineTitles = p[TWO_LINE_TITLES] ?: false,
        showPlaylistAndGroup = p[SHOW_PLAYLIST_GROUP] ?: false,
        showAllChannels = p[SHOW_ALL] ?: true,
        showFavoritesGroup = p[SHOW_FAVORITES] ?: true,
        showRecentGroup = p[SHOW_RECENT] ?: true,
        animations = p[ANIMATIONS] ?: true,
        language = p[LANGUAGE],
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

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
        private val CHANNEL_SORT = stringPreferencesKey("channel_sort")
        private val PLAYLIST_UPDATE = stringPreferencesKey("playlist_update")
        private val OPEN_ON = stringPreferencesKey("open_playlist_on")
        private val GUIDE_OVERLAY = booleanPreferencesKey("guide_overlay")
        private val LIST_PREVIEW = booleanPreferencesKey("list_preview")
        private val STAY_ON_LIST = booleanPreferencesKey("stay_on_list")
        private val AUTOPLAY = intPreferencesKey("autoplay_delay_sec")
        private val OPTION_CARDS = stringPreferencesKey("option_cards")
        private val HIDDEN_CARDS = stringSetPreferencesKey("hidden_option_cards")
        private val PREFER_SURROUND = booleanPreferencesKey("prefer_surround")
        private val ENGLISH_SUBTITLES = booleanPreferencesKey("english_subtitles")
        private val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        private val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        private val SKIPPED_UPDATE = stringPreferencesKey("skipped_update")
        private val AUDIO_OFFSET = intPreferencesKey("audio_offset_ms")
        private val PIP_ON_HOME = booleanPreferencesKey("pip_on_home")
        private val ALWAYS_EXTERNAL = booleanPreferencesKey("always_external_player")
        private val BLACK_ON_SWITCH = booleanPreferencesKey("black_on_switch")
        private val ACCENT = stringPreferencesKey("accent")
        private val BACKGROUND = stringPreferencesKey("background_theme")
        private val FONT_SIZE = stringPreferencesKey("font_size")
        private val TRANSPARENCY = intPreferencesKey("panel_transparency")
        private val CLOCK_SIZE = stringPreferencesKey("clock_size")
        private val CLOCK_DATE = booleanPreferencesKey("clock_show_date")
        private val CLOCK_CORNER = stringPreferencesKey("clock_corner")
        private val LOGO_STYLE = stringPreferencesKey("logo_style")
        private val TWO_LINE_NAMES = booleanPreferencesKey("two_line_names")
        private val TWO_LINE_TITLES = booleanPreferencesKey("two_line_titles")
        private val SHOW_PLAYLIST_GROUP = booleanPreferencesKey("show_playlist_and_group")
        private val SHOW_ALL = booleanPreferencesKey("show_all_channels")
        private val SHOW_FAVORITES = booleanPreferencesKey("show_favorites_group")
        private val SHOW_RECENT = booleanPreferencesKey("show_recent_group")
        private val ANIMATIONS = booleanPreferencesKey("animations")
        private val LANGUAGE = stringPreferencesKey("language")

        fun create(context: Context): SettingsStore = forFile(context.preferencesDataStoreFile("settings"))

        fun forFile(file: File): SettingsStore = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
    }
}

/** The viewer's card order, with cards added in an update slotted in at their default position. */
fun mergeCardOrder(saved: List<String>, defaults: List<String>): List<String> {
    val out = saved.filter { it in defaults }.toMutableList()
    defaults.forEachIndexed { i, card -> if (card !in out) out.add(i.coerceAtMost(out.size), card) }
    return out
}
