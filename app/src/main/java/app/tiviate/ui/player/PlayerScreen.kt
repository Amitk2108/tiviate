package app.tiviate.ui.player

import android.app.Activity
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.tiviate.data.AppSettings
import app.tiviate.data.Category
import app.tiviate.data.DisplayMode
import app.tiviate.data.UpDownMode
import app.tiviate.player.ExternalPlayer
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.KeyAction
import app.tiviate.ui.components.LongPressDetector
import app.tiviate.ui.theme.Tokens
import kotlin.math.abs

@Composable
fun PlayerScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(graph))
    val engine = graph.player.engine

    val playback by engine.state.collectAsStateWithLifecycle()
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val numberText by vm.numberText.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val showStats by vm.showStats.collectAsStateWithLifecycle()
    val sleepMinutes by graph.player.sleepMinutes.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())

    val rootFocus = remember { FocusRequester() }
    val okPress = remember { LongPressDetector() }
    var panelIndex by remember { mutableIntStateOf(0) }

    KeepScreenOn()
    MatchRefreshRate(enabled = settings.matchRefreshRate, frameRate = playback.frameRate)
    LifecycleStartEffect(Unit) {
        vm.ensurePlaying()
        onStopOrDispose { }
    }

    val inFullscreen = overlay == Overlay.None || overlay == Overlay.Banner
    LaunchedEffect(inFullscreen, playback.error) {
        if (inFullscreen && playback.error == null) focusSoon(rootFocus)
    }

    fun openPanel(categories: Boolean) {
        val ch = current
        if (ch == null) { vm.open(Overlay.Panel(categories)); return }
        graph.appScope.launch {
            panelIndex = vm.positionOf(category, ch)
            vm.open(Overlay.Panel(categories))
        }
    }

    fun handleKey(native: AndroidKeyEvent): Boolean {
        val down = native.action == AndroidKeyEvent.ACTION_DOWN
        // The error card's buttons sit in a row: Left/Right move between them (Up/Down still zap).
        if (playback.issue != null && (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)) return false
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (okPress.onKey(native)) {
                    KeyAction.Short -> openPanel(categories = false)
                    KeyAction.Long -> vm.open(Overlay.Options)
                    KeyAction.None -> Unit
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> {
                if (down && native.repeatCount % 3 == 0) vm.zap(forward = true)
                return true
            }
            // Down opens the options (owner's choice); with "Up/Down change channel" it zaps back instead.
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (settings.upDownMode == UpDownMode.ZAP) {
                    if (down && native.repeatCount % 3 == 0) vm.zap(forward = false)
                } else if (down && native.repeatCount == 0) {
                    vm.open(Overlay.Options)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down && native.repeatCount % 3 == 0) vm.zap(forward = false)
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) openPanel(categories = true); return true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_TV_INPUT -> {
                if (down) nav.push(Screen.Guide)
                return true
            }
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> { if (down) vm.open(Overlay.Options); return true }
            AndroidKeyEvent.KEYCODE_INFO -> { if (down) vm.showBanner(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) {
                    val paused = playback.isPaused
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        (native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PLAY && paused) ||
                        (native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE && !paused)
                    ) vm.togglePause()
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_SEARCH -> { if (down) nav.push(Screen.Search); return true }
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                if (down) vm.digit(native.keyCode - AndroidKeyEvent.KEYCODE_0)
                return true
            }
            in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
                if (down) vm.digit(native.keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                return true
            }
        }
        return false
    }

    // Back: close the top overlay, otherwise show the TV guide (the channel keeps playing in its preview).
    BackHandler {
        when {
            overlay is Overlay.Picker -> vm.open(Overlay.Options)
            !inFullscreen -> vm.close()
            overlay == Overlay.Banner -> vm.close()
            else -> nav.push(Screen.Guide)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("player_screen")
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { if (inFullscreen) handleKey(it.nativeKeyEvent) else false }
            // Phones/tablets: tap shows touch controls, vertical swipe zaps. TVs never send these.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (overlay == Overlay.TouchControls) vm.close() else vm.open(Overlay.TouchControls) })
            }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) > 120f) vm.zap(forward = total < 0) },
                ) { _, dy -> total += dy }
            },
    ) {
        VideoSurface(engine, settings.displayMode, settings.zoomPercent, playback.videoWidth, playback.videoHeight, Modifier.fillMaxSize())
        if (showStats) StatsOverlay(engine)

        if (playback.isBuffering && !playback.isPaused && playback.error == null) BufferingIndicator(playback.retrying, playback.notice)
        if (playback.isPaused) PausedBadge()

        val bannerChannel = pending ?: current
        when (val o = overlay) {
            Overlay.None -> Unit
            Overlay.Banner -> bannerChannel?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ZapBanner(it, it.epgRef?.let(vm.nowNext::get), playback, settings.showChannelNumbers, requestInfo = { ref -> vm.loadNowNext(listOf(ref)) })
                }
            }
            is Overlay.Panel -> {
                val items = vm.channels.collectAsLazyPagingItems()
                ChannelPanel(
                    channels = items,
                    category = category,
                    current = current,
                    categoriesFocused = o.categoriesFocused,
                    railEntries = railEntries(groups, playlists, playlist?.id),
                    favorites = favorites,
                    nowNext = vm.nowNext,
                    showNumbers = settings.showChannelNumbers,
                    initialIndex = panelIndex,
                    requestNowNext = vm::loadNowNext,
                    onTune = { ch -> if (ch.stableKey == current?.stableKey) vm.close() else vm.tune(ch) },
                    onRail = { entry ->
                        when (entry) {
                            RailEntry.Search -> { vm.close(); nav.push(Screen.Search) }
                            RailEntry.Settings -> { vm.close(); nav.push(Screen.Settings) }
                            is RailEntry.SwitchPlaylist -> { vm.close(); vm.switchPlaylist(entry.playlist.id) }
                            is RailEntry.Pick -> {
                                vm.selectCategory(entry.category)
                                panelIndex = 0
                                vm.open(Overlay.Panel(categoriesFocused = false))
                            }
                        }
                    },
                    onOpenRail = { vm.open(Overlay.Panel(categoriesFocused = true)) },
                    onCloseRail = { vm.open(Overlay.Panel(categoriesFocused = false)) },
                )
            }
            Overlay.Options -> {
                fun pick(tag: String, kind: PickerKind) { vm.lastOptionTag = tag; vm.open(Overlay.Picker(kind)) }
                val ch = current
                val subtitle = remember(playback.channelKey, overlay) { engine.textTracks().firstOrNull { it.selected }?.label ?: "Off" }
                OptionsPanel(
                    channel = ch,
                    info = ch?.epgRef?.let(vm.nowNext::get),
                    playback = playback,
                    showNumber = settings.showChannelNumbers,
                    requestInfo = { ref -> vm.loadNowNext(listOf(ref)) },
                    focusTag = vm.lastOptionTag,
                    options = listOfNotNull(
                        OptionItem("option_guide", "TV guide", null) { vm.close(); nav.push(Screen.Guide) },
                        OptionItem("option_audio", "Audio", playback.audioLabel ?: "Default") { pick("option_audio", PickerKind.AUDIO) },
                        OptionItem("option_subtitles", "Subtitles", subtitle) { pick("option_subtitles", PickerKind.SUBTITLES) },
                        OptionItem("option_display", "Display", settings.displayMode.label) { pick("option_display", PickerKind.DISPLAY) },
                        OptionItem("option_zoom", "Zoom", "${settings.zoomPercent}%") { pick("option_zoom", PickerKind.ZOOM) },
                        OptionItem(
                            "option_quality", "Quality",
                            if (playback.videoHeight > 0) "${playback.videoWidth} × ${playback.videoHeight}" else "Automatic",
                        ) { pick("option_quality", PickerKind.QUALITY) },
                        OptionItem("option_stats", "Stats", if (showStats) "On" else "Off") { vm.lastOptionTag = "option_stats"; vm.toggleStats() },
                        OptionItem("option_sleep", "Sleep timer", sleepMinutes?.let { "$it min left" } ?: "Off") { pick("option_sleep", PickerKind.SLEEP) },
                        ch?.let {
                            OptionItem("option_favorite", "Favorite", if (it.stableKey in favorites) "Added" else "Not added") {
                                vm.lastOptionTag = "option_favorite"
                                vm.toggleFavorite(it)
                            }
                        },
                        ch?.let {
                            OptionItem("option_external", "Other player", null) {
                                if (!ExternalPlayer.open(context, it.streamUrl, it.name)) vm.showToast("No other video player is installed")
                            }
                        },
                        OptionItem("option_search", "Search", null) { vm.close(); nav.push(Screen.Search) },
                        OptionItem("option_settings", "Settings", null) { vm.close(); nav.push(Screen.Settings) },
                    ),
                )
            }
            is Overlay.Picker -> {
                fun done() = vm.open(Overlay.Options)
                fun save(change: (AppSettings) -> AppSettings) { graph.appScope.launch { graph.settings.update(change) } }
                when (o.kind) {
                    PickerKind.AUDIO -> {
                        val tracks = remember(o, playback.channelKey) { engine.audioTracks() }
                        val forAll = settings.preferredAudioLanguage != null
                        ChoiceDialog(
                            title = "Audio",
                            choices = tracks.map { t ->
                                Choice(t.label, t.selected) {
                                    engine.selectAudio(t)
                                    if (forAll && t.language != null) save { it.copy(preferredAudioLanguage = t.language) }
                                    done()
                                }
                            },
                            toggle = ToggleChoice("Use this language for all channels", forAll) { on ->
                                val lang = tracks.firstOrNull { it.selected }?.language
                                when {
                                    !on -> save { it.copy(preferredAudioLanguage = null) }
                                    lang != null -> save { it.copy(preferredAudioLanguage = lang) }
                                    else -> vm.showToast("This track has no language set")
                                }
                            },
                            empty = "This channel has only one audio track.",
                            tag = "track_dialog",
                        )
                    }
                    PickerKind.SUBTITLES -> {
                        val tracks = remember(o, playback.channelKey) { engine.textTracks() }
                        ChoiceDialog(
                            title = "Subtitles",
                            choices = listOf(
                                Choice("Off", tracks.none { it.selected }) {
                                    engine.selectText(null)
                                    if (settings.subtitlesForAll) save { it.copy(subtitlesForAll = false) }
                                    done()
                                },
                            ) + tracks.map { t ->
                                Choice(t.label, t.selected) {
                                    engine.selectText(t)
                                    if (settings.subtitlesForAll) save { it.copy(preferredTextLanguage = t.language) }
                                    done()
                                }
                            },
                            toggle = ToggleChoice("Turn on for all channels", settings.subtitlesForAll) { on ->
                                val lang = tracks.firstOrNull { it.selected }?.language
                                save { it.copy(subtitlesForAll = on, preferredTextLanguage = if (on) lang ?: it.preferredTextLanguage else it.preferredTextLanguage) }
                            },
                            tag = "track_dialog",
                        )
                    }
                    PickerKind.DISPLAY -> ChoiceDialog(
                        title = "Display (all channels)",
                        choices = DisplayMode.entries.map { m -> Choice(m.label, m == settings.displayMode) { save { it.copy(displayMode = m) }; done() } },
                        tag = "display_dialog",
                    )
                    PickerKind.ZOOM -> ChoiceDialog(
                        title = "Zoom (all channels)",
                        choices = ZOOM_STEPS.map { z -> Choice("$z%", z == settings.zoomPercent) { save { it.copy(zoomPercent = z) }; done() } },
                        tag = "zoom_dialog",
                    )
                    PickerKind.QUALITY -> {
                        val tracks = remember(o, playback.channelKey) { engine.videoTracks() }
                        val auto = remember(o) { engine.isAutoQuality() }
                        ChoiceDialog(
                            title = "Quality",
                            choices = listOf(Choice("Automatic", auto) { engine.selectVideo(null); done() }) +
                                tracks.map { t -> Choice(t.label, !auto && t.selected) { engine.selectVideo(t); done() } },
                            tag = "quality_dialog",
                        )
                    }
                    PickerKind.SLEEP -> ChoiceDialog(
                        title = "Sleep timer",
                        choices = listOf(Choice("Off", sleepMinutes == null) { graph.player.setSleepTimer(null); done() }) +
                            SLEEP_STEPS.map { m -> Choice("$m min", false) { graph.player.setSleepTimer(m); vm.showToast("Tiviate will stop in $m min"); done() } },
                        tag = "sleep_dialog",
                    )
                }
            }
            Overlay.TouchControls -> TouchControls(
                onChannels = { openPanel(categories = false) },
                onGuide = { vm.close(); nav.push(Screen.Guide) },
                onMenu = { vm.open(Overlay.Options) },
                onPlayPause = { vm.togglePause() },
            )
        }

        playback.issue?.let { issue ->
            ErrorCard(
                issue = issue,
                detail = playback.issueDetail,
                channel = current,
                onRetry = { engine.retryNow() },
                onNext = { vm.zap(forward = true) },
                onExternal = { current?.let { if (!ExternalPlayer.open(context, it.streamUrl, it.name)) vm.showToast("No other video player is installed") } },
            )
        }
        if (numberText.isNotEmpty()) NumberBadge(numberText)
        toast?.let { Toast(it) }
    }
}

@Composable
private fun TouchControls(onChannels: () -> Unit, onGuide: () -> Unit, onMenu: () -> Unit, onPlayPause: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.background(Tokens.Scrim, Tokens.Shape).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Channels", onChannels)
            ActionButton("Guide", onGuide)
            ActionButton("⏯", onPlayPause)
            ActionButton("Menu", onMenu)
        }
    }
}

/** Optional: switch the TV's refresh rate to match the video (e.g. 50 Hz for 25 fps) to remove judder. */
@Composable
private fun MatchRefreshRate(enabled: Boolean, frameRate: Float?) {
    val activity = LocalContext.current as? Activity ?: return
    LaunchedEffect(enabled, frameRate) {
        if (!enabled || frameRate == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@LaunchedEffect
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val cur = display.mode.let { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = DisplayModeMatcher.pick(modes, cur, frameRate) ?: return@LaunchedEffect
        if (target.id != cur.id) {
            val attrs = activity.window.attributes
            attrs.preferredDisplayModeId = target.id
            activity.window.attributes = attrs
        }
    }
    DisposableEffect(activity) {
        onDispose {
            val attrs = activity.window.attributes
            if (attrs.preferredDisplayModeId != 0) {
                attrs.preferredDisplayModeId = 0
                activity.window.attributes = attrs
            }
        }
    }
}

private val ZOOM_STEPS = listOf(100, 110, 120, 130, 140, 150)
private val SLEEP_STEPS = listOf(15, 30, 60, 90, 120)
