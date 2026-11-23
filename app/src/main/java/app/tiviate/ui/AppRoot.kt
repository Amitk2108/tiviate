package app.tiviate.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.tiviate.data.sync.SyncScheduler
import app.tiviate.ui.guide.GuideScreen
import app.tiviate.ui.player.PlayerScreen
import app.tiviate.ui.search.SearchScreen
import app.tiviate.ui.settings.SettingsScreen
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.welcome.AddPlaylistScreen
import app.tiviate.ui.welcome.SyncingScreen
import app.tiviate.ui.welcome.WelcomeScreen
import kotlinx.coroutines.flow.first

@Composable
fun AppRoot() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val nav = remember { Navigator(Screen.Loading) }
    // Which layouts to use: TV, or the touch layouts on phones and upright tablets.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val formFactor = forcedFormFactor ?: remember(config.smallestScreenWidthDp) { formFactorOf(isTvDevice(context), config.smallestScreenWidthDp) }
    val portrait = config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val phoneLayout = usesPhoneLayout(formFactor, portrait)
    // Fullscreen video on a phone or tablet (the Live screen held sideways): no system bars.
    val immersive = formFactor != FormFactor.TV &&
        ((nav.current == Screen.Player && !(phoneLayout && portrait)) || nav.current is Screen.VodPlayer || nav.current == Screen.MultiView)
    LaunchedEffect(immersive, formFactor) {
        if (formFactor == FormFactor.TV) return@LaunchedEffect
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
            if (immersive) hide(bars) else show(bars)
        }
    }

    LaunchedEffect(Unit) {
        val opening = graph.openChannel.value != null // started from a channel on the TV home screen
        val playlists = graph.db.playlists().all()
        if (playlists.isEmpty()) {
            nav.replaceAll(Screen.Welcome)
            return@LaunchedEffect
        }
        val settings = graph.settings.settings.first()
        val active = playlists.firstOrNull { it.id == settings.activePlaylistId } ?: playlists.first()
        if (settings.activePlaylistId != active.id) graph.settings.update { it.copy(activePlaylistId = active.id) }
        nav.replaceAll(Screen.Player)
        // TV: start on the guide (Back goes fullscreen). Phones open on their Live screen (video and list).
        if (!opening && !settings.startFullscreen && settings.guideScreen && !phoneLayout) nav.push(Screen.Guide)
        if (graph.playlists.needsStartSync(settings.playlistUpdate, settings.updateOnStart)) {
            // A playlist that never loaded goes now; a routine refresh waits until the viewer has settled in.
            SyncScheduler.syncNow(context, atStart = true, delaySec = if (playlists.any { it.lastSyncAt == null }) 0 else 45)
        }
    }

    // Once a day: is there a newer Tiviate on GitHub Releases?
    LaunchedEffect(Unit) {
        val s = graph.settings.settings.first()
        val now = System.currentTimeMillis()
        if (app.tiviate.data.update.UpdateChecker.dueForCheck(s.checkUpdates, s.lastUpdateCheck, now)) {
            graph.settings.update { it.copy(lastUpdateCheck = now) }
            val latest = app.tiviate.data.update.UpdateChecker.latest(graph.http)
            if (latest != null && app.tiviate.data.update.UpdateChecker.shouldOffer(latest.version, app.tiviate.BuildConfig.VERSION_NAME, s.skippedUpdate)) {
                app.tiviate.ui.components.UpdatePrompt.release = latest
            }
        }
    }

    // A channel picked on the TV home screen: play it fullscreen.
    LaunchedEffect(Unit) {
        graph.openChannel.collect { pick ->
            val (playlistId, key) = pick ?: return@collect
            graph.openChannel.value = null
            val channel = graph.db.channels().byKey(playlistId, key) ?: return@collect
            val playlist = graph.playlists.get(playlistId) ?: return@collect
            if (graph.settings.settings.first().activePlaylistId != playlistId) graph.settings.update { it.copy(activePlaylistId = playlistId) }
            // The player lives on the main thread; the lookups above may have resumed elsewhere.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                graph.player.tune(channel, playlist.userAgent)
                nav.replaceAll(Screen.Player)
            }
        }
    }

    BackHandler(enabled = nav.canGoBack) { nav.back() }
    LaunchedEffect(Unit) {
        graph.longBack.collect { if (nav.current != Screen.Player && nav.current !is Screen.Welcome && nav.current !is Screen.AddPlaylist) nav.replaceAll(Screen.Player) }
    }

    val videoLayout = remember { app.tiviate.ui.player.VideoLayout() }
    // Each screen fades in quickly; the video under it never moves layers, so this stays cheap.
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(nav.current) {
        if (app.tiviate.ui.theme.ThemeState.animations) {
            fade.snapTo(0.6f)
            fade.animateTo(1f, androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    CompositionLocalProvider(LocalNavigator provides nav, app.tiviate.ui.player.LocalVideoLayout provides videoLayout, LocalFormFactor provides formFactor) {
        Box(Modifier.fillMaxSize().background(Tokens.Background)) {
            app.tiviate.ui.player.VideoHost(graph.player.engine)
            val playing by graph.player.engine.state.collectAsState()
            app.tiviate.ui.player.KeepScreenOn((playing.channelKey != null && !playing.isPaused) || nav.current == Screen.MultiView)
            // Only the player, the guide and search let the video show through; every other screen is opaque.
            val opaque = nav.current != Screen.Player && nav.current != Screen.Guide && nav.current != Screen.Search && nav.current !is Screen.VodPlayer
            // Parental control: a locked channel waits for the PIN; locked Settings open only with it.
            val appSettings by graph.settings.settings.collectAsState(initial = app.tiviate.data.AppSettings())
            val pinRequest by graph.player.pinRequest.collectAsState()
            val unlocked by graph.player.unlocked.collectAsState()
            val settingsLocked = nav.current == Screen.Settings && appSettings.lockSettings && appSettings.pinHash != null && !unlocked
            val reminderDue by graph.reminderDue.collectAsState()
            val dialogOpen = app.tiviate.ui.components.UpdatePrompt.release != null || pinRequest != null || settingsLocked || reminderDue != null
            // The dialog kept the focus away from the screen; when it closes, hand the focus back
            // (otherwise nothing is focused and the first key press is lost).
            val screensFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            var wasOpen by remember { mutableStateOf(false) }
            LaunchedEffect(dialogOpen) {
                if (wasOpen && !dialogOpen) {
                    for (i in 0 until 10) {
                        if (runCatching { screensFocus.requestFocus() }.isSuccess) break
                        kotlinx.coroutines.delay(16)
                    }
                }
                wasOpen = dialogOpen
            }
            Box(
                Modifier.fillMaxSize().then(if (opaque) Modifier.background(Tokens.Background) else Modifier).graphicsLayer { alpha = fade.value }
                    // While the update dialog is open the screen behind it gets no focus and no keys.
                    .onPreviewKeyEvent { dialogOpen }
                    .focusProperties { onEnter = { if (dialogOpen) cancelFocusChange() } }
                    .focusRequester(screensFocus)
                    .focusGroup()
                    // Phones and tablets: keep clear of the status bar, navigation bar and camera cut-out.
                    .then(if (formFactor != FormFactor.TV && !immersive) Modifier.safeDrawingPadding() else Modifier),
            ) {
            when (val screen = nav.current) {
                Screen.Loading -> Unit
                Screen.Welcome -> WelcomeScreen()
                is Screen.AddPlaylist -> AddPlaylistScreen(screen.type, screen.editId)
                is Screen.Syncing -> SyncingScreen(screen.playlistId, screen.isEdit)
                Screen.Player -> if (phoneLayout && portrait) app.tiviate.ui.phone.PhoneHome() else PlayerScreen()
                Screen.Guide -> if (phoneLayout) app.tiviate.ui.phone.PhoneGuide() else GuideScreen()
                Screen.Search -> SearchScreen()
                is Screen.ManageChannels -> app.tiviate.ui.organise.ManageChannelsScreen(screen.playlistId, screen.category, screen.reorder)
                is Screen.ManageGroups -> app.tiviate.ui.organise.ManageGroupsScreen(screen.playlistId)
                Screen.OptionCards -> app.tiviate.ui.organise.OptionCardsScreen()
                Screen.Settings -> SettingsScreen()
                Screen.Library -> app.tiviate.ui.library.LibraryScreen()
                is Screen.MovieDetails -> app.tiviate.ui.library.MovieDetailsScreen(screen.playlistId, screen.streamId)
                is Screen.SeriesDetails -> app.tiviate.ui.library.SeriesDetailsScreen(screen.playlistId, screen.seriesId)
                is Screen.VodPlayer -> app.tiviate.ui.library.VodPlayerScreen(screen.item)
                Screen.Recordings -> app.tiviate.ui.library.RecordingsScreen()
                Screen.MultiView -> app.tiviate.ui.player.MultiViewScreen()
            }
            }
            app.tiviate.ui.components.UpdatePrompt.release?.let { r -> app.tiviate.ui.components.UpdateDialog(r) { app.tiviate.ui.components.UpdatePrompt.release = null } }
            fun unlock(pin: String): Boolean {
                if (!app.tiviate.data.Parental.matches(appSettings, pin)) return false
                graph.player.unlocked.value = true
                return true
            }
            when {
                pinRequest != null -> app.tiviate.ui.components.PinDialog(
                    t("This channel is locked. Enter the PIN"),
                    onPin = { pin -> unlock(pin).also { ok -> if (ok) { val retry = pinRequest; graph.player.pinRequest.value = null; retry?.invoke() } } },
                    onCancel = { graph.player.pinRequest.value = null },
                )
                settingsLocked -> app.tiviate.ui.components.PinDialog(t("Enter the PIN to open Settings"), onPin = ::unlock, onCancel = { nav.back() })
                reminderDue != null -> reminderDue?.let { r ->
                    app.tiviate.ui.components.ReminderPrompt(r, onWatch = {
                        graph.reminderDue.value = null
                        graph.openChannel.value = r.playlistId to r.stableKey // plays it fullscreen (locked channels ask for the PIN)
                    }, onDismiss = { graph.reminderDue.value = null })
                }
            }
        }
    }
}
