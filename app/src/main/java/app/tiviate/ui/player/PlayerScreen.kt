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
import androidx.compose.runtime.mutableLongStateOf
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
    val aspect by vm.aspect.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())

    val rootFocus = remember { FocusRequester() }
    val okPress = remember { LongPressDetector() }
    var lastBackAt by remember { mutableLongStateOf(0L) }
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
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (okPress.onKey(native)) {
                    KeyAction.Short -> openPanel(categories = false)
                    KeyAction.Long -> vm.open(Overlay.QuickActions)
                    KeyAction.None -> Unit
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> {
                if (down && native.repeatCount % 3 == 0) vm.zap(forward = true)
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down && native.repeatCount % 3 == 0) vm.zap(forward = false)
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) openPanel(categories = true); return true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_TV_INPUT -> {
                if (down) nav.push(Screen.Guide)
                return true
            }
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> { if (down) vm.open(Overlay.QuickActions); return true }
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

    // Back: close the top overlay, otherwise ask for a second press to exit.
    BackHandler {
        when {
            !inFullscreen -> vm.close()
            overlay == Overlay.Banner -> vm.close()
            System.currentTimeMillis() - lastBackAt < 2_000 -> (context as? Activity)?.finish()
            else -> {
                lastBackAt = System.currentTimeMillis()
                vm.showToast("Press Back again to exit")
            }
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
        VideoSurface(engine, aspect, Modifier.fillMaxSize())

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
            Overlay.QuickActions -> QuickActionsBar(
                listOfNotNull(
                    QuickAction("action_audio", "Audio") { vm.open(Overlay.Tracks(audio = true)) },
                    QuickAction("action_subtitles", "Subtitles") { vm.open(Overlay.Tracks(audio = false)) },
                    QuickAction("action_aspect", "Picture: ${aspect.label}") { vm.cycleAspect() },
                    current?.let { ch ->
                        QuickAction("action_favorite", if (ch.stableKey in favorites) "Remove favorite" else "Add to favorites") { vm.toggleFavorite(ch) }
                    },
                    current?.let { ch ->
                        QuickAction("action_external", "Other player") {
                            if (!ExternalPlayer.open(context, ch.streamUrl, ch.name)) vm.showToast("No other video player is installed")
                        }
                    },
                    QuickAction("action_guide", "TV guide") { vm.close(); nav.push(Screen.Guide) },
                    QuickAction("action_refresh", "Update guide") { vm.close(); vm.refreshGuide() },
                ),
            )
            is Overlay.Tracks -> {
                val options = remember(o, playback.channelKey) { if (o.audio) engine.audioTracks() else engine.textTracks() }
                TrackDialog(if (o.audio) "Audio" else "Subtitles", options, allowOff = !o.audio) { choice ->
                    if (o.audio) choice?.let(engine::selectAudio) else engine.selectText(choice)
                    vm.close()
                }
            }
            Overlay.TouchControls -> TouchControls(
                onChannels = { openPanel(categories = false) },
                onGuide = { vm.close(); nav.push(Screen.Guide) },
                onMenu = { vm.open(Overlay.QuickActions) },
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
