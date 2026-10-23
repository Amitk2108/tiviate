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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.os.SystemClock
import kotlinx.coroutines.delay
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
import app.tiviate.ui.organise.ChannelSheet
import app.tiviate.ui.organise.SheetTarget
import app.tiviate.player.ExternalPlayer
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.LocalScreenActive
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.KeyAction
import app.tiviate.ui.components.LongPressDetector
import app.tiviate.ui.theme.Tokens
import kotlin.math.abs
import app.tiviate.ui.t
import app.tiviate.ui.tf

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
    val customGroups by vm.customGroups.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    val numberText by vm.numberText.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val showStats by vm.showStats.collectAsStateWithLifecycle()
    val sleepMinutes by graph.player.sleepMinutes.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())

    val rootFocus = remember { FocusRequester() }
    val okPress = remember { LongPressDetector() }
    var panelIndex by remember { mutableIntStateOf(0) }
    // In the channel list, moving through the groups shows that group's channels after a short pause.
    var browsing by remember { mutableStateOf<Category?>(null) }
    LaunchedEffect(browsing) {
        val c = browsing ?: return@LaunchedEffect
        delay(300)
        if (c != category) { vm.selectCategory(c); panelIndex = 0 }
    }

    KeepScreenOn()
    MatchRefreshRate(enabled = settings.matchRefreshRate, frameRate = playback.frameRate)
    LaunchedEffect(Unit) { graph.longBack.collect { vm.close() } }
    val inPip by graph.player.inPip.collectAsStateWithLifecycle()
    LaunchedEffect(inPip) { if (inPip) vm.close() }
    LifecycleStartEffect(Unit) {
        vm.ensurePlaying()
        onStopOrDispose { }
    }

    val inFullscreen = overlay == Overlay.None || overlay == Overlay.Banner
    var videoRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    DisposableEffect(inFullscreen) {
        graph.player.fullscreenVisible = inFullscreen
        onDispose { graph.player.fullscreenVisible = false }
    }
    var lastInputAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    // "Close the channel list and options after N s": an idle panel closes by itself.
    LaunchedEffect(overlay, settings.panelTimeoutSec, lastInputAt) {
        val seconds = settings.panelTimeoutSec
        if (seconds > 0 && (overlay is Overlay.Panel || overlay == Overlay.Options)) {
            delay(seconds * 1_000L)
            vm.close()
        }
    }
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
                    KeyAction.Short -> if (!vm.commitPeek()) vm.open(Overlay.Options)
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
            // Info bar opened with Info: Left/Right look at the previous/next channel's programme; OK switches to it.
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) { if (overlay == Overlay.Banner && vm.canPeek) vm.peek(forward = false) else openPanel(categories = true) }; return true }
            AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { if (down) vm.previousChannel(); return true }
            AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { if (down) { graph.player.stop(); nav.push(Screen.Guide) }; return true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_TV_INPUT -> {
                if (down) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && overlay == Overlay.Banner && vm.canPeek) vm.peek(forward = true) else nav.push(Screen.Guide)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> { if (down) vm.open(Overlay.Options); return true }
            AndroidKeyEvent.KEYCODE_INFO -> { if (down) vm.showInfo(); return true }
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
    BackHandler(enabled = LocalScreenActive.current) {
        when {
            sheet != null -> sheet = null
            overlay is Overlay.Picker -> vm.open(Overlay.Options)
            !inFullscreen -> vm.close()
            overlay == Overlay.Banner -> vm.close()
            else -> nav.push(Screen.Guide)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .backgroundWithHole(Color.Black, { videoRect })
            .testTag("player_screen")
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { lastInputAt = SystemClock.uptimeMillis(); false }
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
        val channelKey = current?.let { "${it.playlistId}:${it.stableKey}" }
        // Channel list in preview mode: the video moves into a window at the top right.
        val preview = settings.listPreview && overlay is Overlay.Panel
        val videoModifier = if (preview) {
            Modifier.align(Alignment.TopEnd).padding(Tokens.SafeMargin).fillMaxWidth(0.42f).aspectRatio(16f / 9f)
        } else {
            Modifier.fillMaxSize()
        }
        VideoSlot(settings.displayModeFor(channelKey), settings.zoomPercent, videoModifier, blackOnSwitch = settings.blackOnSwitch, onBounds = { videoRect = it })
        if (showStats) StatsOverlay(engine)

        if (playback.isBuffering && !playback.isPaused && playback.error == null) BufferingIndicator(playback.retrying, playback.notice)
        if (playback.isPaused) PausedBadge()

        val bannerChannel = pending ?: current
        when (val o = overlay) {
            Overlay.None -> Unit
            Overlay.Banner -> bannerChannel?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ZapBanner(
                        it, it.epgRef?.let(vm.nowNext::get), playback, settings.showChannelNumbers, requestInfo = { ref -> vm.loadNowNext(listOf(ref)) },
                        source = if (settings.showPlaylistAndGroup) listOfNotNull(playlist?.name, it.groupName).joinToString("  ·  ") else null,
                    )
                }
            }
            is Overlay.Panel -> {
                val items = vm.channels.collectAsLazyPagingItems()
                ChannelPanel(
                    channels = items,
                    category = category,
                    current = current,
                    categoriesFocused = o.categoriesFocused,
                    railEntries = railEntries(
                        groups, playlists, playlist?.id, customGroups,
                        settings.showAllChannels, settings.showFavoritesGroup, settings.showRecentGroup,
                    ),
                    onRowMenu = { ch -> sheet = SheetTarget(ch.playlistId, ch, category) },
                    favorites = favorites,
                    nowNext = vm.nowNext,
                    showNumbers = settings.showChannelNumbers,
                    showProgramme = settings.listShowProgramme,
                    showDescription = settings.listShowDescription,
                    initialIndex = panelIndex,
                    requestNowNext = vm::loadNowNext,
                    onTune = { ch ->
                        when {
                            ch.stableKey == current?.stableKey -> vm.close()
                            settings.alwaysExternalPlayer -> { if (!ExternalPlayer.open(context, ch.streamUrl, ch.name)) vm.showToast(t("No other video player is installed")) }
                            else -> { vm.tune(ch); if (!settings.stayOnList) vm.close() }
                        }
                    },
                    autoplayDelaySec = settings.autoplayDelaySec,
                    onAutoplay = { ch -> if (ch.stableKey != current?.stableKey) vm.tune(ch) },
                    onRail = { entry ->
                        when (entry) {
                            RailEntry.Search -> { vm.close(); nav.push(Screen.Search) }
                            RailEntry.Settings -> { vm.close(); nav.push(Screen.Settings) }
                            is RailEntry.SwitchPlaylist -> { vm.close(); vm.switchPlaylist(entry.playlist.id) }
                            is RailEntry.Pick -> {
                                browsing = null
                                vm.selectCategory(entry.category)
                                panelIndex = 0
                                vm.open(Overlay.Panel(categoriesFocused = false))
                            }
                        }
                    },
                    onOpenRail = { vm.open(Overlay.Panel(categoriesFocused = true)) },
                    onCloseRail = { browsing = null; vm.open(Overlay.Panel(categoriesFocused = false)) },
                    onBrowse = { browsing = it },
                )
            }
            Overlay.Options -> {
                fun pick(tag: String, kind: PickerKind) { vm.lastOptionTag = tag; vm.open(Overlay.Picker(kind)) }
                val ch = current
                val subtitle = remember(playback.channelKey, overlay) { engine.textTracks().firstOrNull { it.selected }?.label ?: t("Off") }
                OptionsPanel(
                    channel = ch,
                    info = ch?.epgRef?.let(vm.nowNext::get),
                    playback = playback,
                    showNumber = settings.showChannelNumbers,
                    requestInfo = { ref -> vm.loadNowNext(listOf(ref)) },
                    focusTag = vm.lastOptionTag,
                    options = listOfNotNull(
                        OptionItem("option_channels", t("Channels"), null) { vm.lastOptionTag = "option_channels"; openPanel(categories = false) },
                        OptionItem("option_guide", t("TV guide"), null) { vm.close(); nav.push(Screen.Guide) },
                        OptionItem("option_playpause", if (playback.isPaused) t("Play") else t("Pause"), if (playback.isPaused) t("Paused") else t("Live")) {
                            vm.lastOptionTag = "option_playpause"
                            vm.togglePause()
                        },
                        if (playback.isPaused) OptionItem("option_live", t("Back to live"), null) { vm.lastOptionTag = "option_playpause"; engine.goLive() } else null,
                        OptionItem("option_audio", t("Audio"), playback.audioLabel ?: t("Default")) { pick("option_audio", PickerKind.AUDIO) },
                        graph.player.previous.value?.let { prev ->
                            OptionItem("option_previous", t("Previous channel"), prev.name) { vm.lastOptionTag = "option_previous"; vm.close(); vm.previousChannel() }
                        },
                        OptionItem("option_subtitles", t("Subtitles"), subtitle) { pick("option_subtitles", PickerKind.SUBTITLES) },
                        OptionItem("option_audio_sync", t("Audio sync"), audioSyncLabel(settings.audioOffsetMs)) { pick("option_audio_sync", PickerKind.AUDIO_SYNC) },
                        OptionItem("option_pip", t("Picture-in-picture"), null) {
                            vm.lastOptionTag = "option_pip"
                            vm.close()
                            if ((context as? app.tiviate.MainActivity)?.enterPip() != true) vm.showToast(t("This TV doesn't support picture-in-picture"))
                        },
                        OptionItem("option_display", t("Display"), settings.displayModeFor(ch?.let { "${it.playlistId}:${it.stableKey}" }).label) { pick("option_display", PickerKind.DISPLAY) },
                        OptionItem("option_zoom", t("Zoom"), "${settings.zoomPercent}%") { pick("option_zoom", PickerKind.ZOOM) },
                        OptionItem(
                            "option_quality", t("Quality"),
                            if (playback.videoHeight > 0) "${playback.videoWidth} × ${playback.videoHeight}" else t("Automatic"),
                        ) { pick("option_quality", PickerKind.QUALITY) },
                        OptionItem("option_stats", t("Stats"), if (showStats) t("On") else t("Off")) { vm.lastOptionTag = "option_stats"; vm.toggleStats() },
                        OptionItem("option_sleep", t("Sleep timer"), sleepMinutes?.let { tf("%d min left", it) } ?: t("Off")) { pick("option_sleep", PickerKind.SLEEP) },
                        ch?.let {
                            OptionItem("option_favorite", t("Favorite"), if (it.stableKey in favorites) t("Added") else t("Not added")) {
                                vm.lastOptionTag = "option_favorite"
                                vm.toggleFavorite(it)
                            }
                        },
                        ch?.let {
                            OptionItem("option_external", t("Other player"), null) {
                                if (!ExternalPlayer.open(context, it.streamUrl, it.name)) vm.showToast(t("No other video player is installed"))
                            }
                        },
                        ch?.let { OptionItem("option_channel", t("Channel options"), null) { vm.lastOptionTag = "option_channel"; vm.close(); sheet = SheetTarget(it.playlistId, it, category, vm.nowNext[it.epgRef]?.now?.title) } },
                        OptionItem("option_search", t("Search"), null) { vm.close(); nav.push(Screen.Search) },
                        OptionItem("option_settings", t("Settings"), null) { vm.close(); nav.push(Screen.Settings) },
                    ).filter { it.tag !in settings.hiddenOptionCards }
                        .sortedBy { optionOrder(settings.optionCards, it.tag) },
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
                            title = t("Audio"),
                            choices = tracks.map { t ->
                                Choice(t.label, t.selected) {
                                    engine.selectAudio(t)
                                    if (forAll && t.language != null) save { it.copy(preferredAudioLanguage = t.language) }
                                    done()
                                }
                            },
                            toggle = ToggleChoice(t("Use this language for all channels"), forAll) { on ->
                                val lang = tracks.firstOrNull { it.selected }?.language
                                when {
                                    !on -> save { it.copy(preferredAudioLanguage = null) }
                                    lang != null -> save { it.copy(preferredAudioLanguage = lang) }
                                    else -> vm.showToast(t("This track has no language set"))
                                }
                            },
                            empty = t("This channel has only one audio track."),
                            tag = "track_dialog",
                        )
                    }
                    PickerKind.SUBTITLES -> {
                        val tracks = remember(o, playback.channelKey) { engine.textTracks() }
                        ChoiceDialog(
                            title = t("Subtitles"),
                            choices = listOf(
                                Choice(t("Off"), tracks.none { it.selected }) {
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
                            toggle = ToggleChoice(t("Turn on for all channels"), settings.subtitlesForAll) { on ->
                                val lang = tracks.firstOrNull { it.selected }?.language
                                save { it.copy(subtitlesForAll = on, preferredTextLanguage = if (on) lang ?: it.preferredTextLanguage else it.preferredTextLanguage) }
                            },
                            tag = "track_dialog",
                        )
                    }
                    PickerKind.DISPLAY -> {
                        val key = current?.let { "${it.playlistId}:${it.stableKey}" }
                        var applyAll by remember(o) { mutableStateOf(key == null || key !in settings.channelDisplayModes) }
                        val effective = settings.displayModeFor(key)
                        ChoiceDialog(
                            title = t("Display"),
                            choices = DisplayMode.entries.map { m ->
                                Choice(m.label, m == effective) {
                                    save { s ->
                                        if (applyAll || key == null) s.copy(displayMode = m, channelDisplayModes = s.channelDisplayModes - (key ?: ""))
                                        else s.copy(channelDisplayModes = s.channelDisplayModes + (key to m))
                                    }
                                    done()
                                }
                            },
                            toggle = key?.let {
                                ToggleChoice(t("Apply to all channels"), applyAll) { on ->
                                    applyAll = on
                                    if (on) save { s -> s.copy(channelDisplayModes = s.channelDisplayModes - it) }
                                }
                            },
                            tag = "display_dialog",
                        )
                    }
                    PickerKind.ZOOM -> ChoiceDialog(
                        title = t("Zoom (all channels)"),
                        choices = ZOOM_STEPS.map { z -> Choice("$z%", z == settings.zoomPercent) { save { it.copy(zoomPercent = z) }; done() } },
                        tag = "zoom_dialog",
                    )
                    PickerKind.QUALITY -> {
                        val tracks = remember(o, playback.channelKey) { engine.videoTracks() }
                        val auto = remember(o) { engine.isAutoQuality() }
                        ChoiceDialog(
                            title = t("Quality"),
                            choices = listOf(Choice(t("Automatic"), auto) { engine.selectVideo(null); done() }) +
                                tracks.map { t -> Choice(t.label, !auto && t.selected) { engine.selectVideo(t); done() } },
                            tag = "quality_dialog",
                        )
                    }
                    PickerKind.AUDIO_SYNC -> ChoiceDialog(
                        title = t("Audio sync (all channels)"),
                        choices = AUDIO_SYNC_STEPS.map { ms -> Choice(audioSyncLabel(ms), ms == settings.audioOffsetMs) { save { it.copy(audioOffsetMs = ms) }; done() } },
                        tag = "audio_sync_dialog",
                    )
                    PickerKind.SLEEP -> ChoiceDialog(
                        title = t("Sleep timer"),
                        choices = listOf(Choice(t("Off"), sleepMinutes == null) { graph.player.setSleepTimer(null); done() }) +
                            SLEEP_STEPS.map { m -> Choice(tf("%d min", m), false) { graph.player.setSleepTimer(m); vm.showToast(tf("Tiviate will stop in %d min", m)); done() } },
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
                onExternal = { current?.let { if (!ExternalPlayer.open(context, it.streamUrl, it.name)) vm.showToast(t("No other video player is installed")) } },
            )
        }
        sheet?.let { t -> ChannelSheet(t, onClose = { sheet = null }, onMessage = vm::showToast) }
        // "Clock on the video": always visible in fullscreen at the chosen corner.
        if (inFullscreen && settings.clockCorner != app.tiviate.data.ClockCorner.OFF && !inPip) {
            val align = when (settings.clockCorner) {
                app.tiviate.data.ClockCorner.TOP_LEFT -> Alignment.TopStart
                app.tiviate.data.ClockCorner.TOP_RIGHT -> Alignment.TopEnd
                app.tiviate.data.ClockCorner.BOTTOM_LEFT -> Alignment.BottomStart
                else -> Alignment.BottomEnd
            }
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = align) {
                app.tiviate.ui.components.Clock(
                    androidx.compose.ui.text.TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    Modifier.background(Tokens.Scrim, Tokens.SmallShape).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (numberText.isNotEmpty()) NumberBadge(numberText)
        toast?.let { Toast(it) }
    }
}

@Composable
private fun TouchControls(onChannels: () -> Unit, onGuide: () -> Unit, onMenu: () -> Unit, onPlayPause: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.background(Tokens.Scrim, Tokens.Shape).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(t("Channels"), onChannels)
            ActionButton(t("Guide"), onGuide)
            ActionButton("⏯", onPlayPause)
            ActionButton(t("Menu"), onMenu)
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
private val AUDIO_SYNC_STEPS = listOf(-500, -400, -300, -200, -150, -100, -50, 0, 50, 100, 150, 200, 300, 400, 500)

/** "0 ms", "Sound 100 ms later", "Sound 200 ms earlier". */
fun audioSyncLabel(ms: Int): String = when {
    ms == 0 -> "0 ms"
    ms > 0 -> tf("Sound %d ms later", ms)
    else -> tf("Sound %d ms earlier", -ms)
}

/** Position of an option card: the viewer's order; "Back to live" sits right after Pause; unknown cards go last. */
internal fun optionOrder(order: List<String>, tag: String): Double {
    val i = order.indexOf(if (tag == "option_live") "option_playpause" else tag)
    return if (i < 0) Double.MAX_VALUE else i + if (tag == "option_live") 0.5 else 0.0
}
