package app.tiviate.ui

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

    LaunchedEffect(Unit) {
        val playlists = graph.db.playlists().all()
        if (playlists.isEmpty()) {
            nav.replaceAll(Screen.Welcome)
            return@LaunchedEffect
        }
        val settings = graph.settings.settings.first()
        val active = playlists.firstOrNull { it.id == settings.activePlaylistId } ?: playlists.first()
        if (settings.activePlaylistId != active.id) graph.settings.update { it.copy(activePlaylistId = active.id) }
        nav.replaceAll(Screen.Player)
        if (!settings.startFullscreen) nav.push(Screen.Guide) // start on the TV guide; Back goes fullscreen
        if (graph.playlists.needsStartSync(settings.playlistUpdate, settings.updateOnStart)) SyncScheduler.syncNow(context, atStart = true)
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
    CompositionLocalProvider(LocalNavigator provides nav, app.tiviate.ui.player.LocalVideoLayout provides videoLayout) {
        Box(Modifier.fillMaxSize().background(Tokens.Background)) {
            app.tiviate.ui.player.VideoHost(graph.player.engine)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
            when (val screen = nav.current) {
                Screen.Loading -> Unit
                Screen.Welcome -> WelcomeScreen()
                is Screen.AddPlaylist -> AddPlaylistScreen(screen.type, screen.editId)
                is Screen.Syncing -> SyncingScreen(screen.playlistId)
                Screen.Player -> PlayerScreen()
                Screen.Guide -> GuideScreen()
                Screen.Search -> SearchScreen()
                is Screen.ManageChannels -> app.tiviate.ui.organise.ManageChannelsScreen(screen.playlistId, screen.category, screen.reorder)
                is Screen.ManageGroups -> app.tiviate.ui.organise.ManageGroupsScreen(screen.playlistId)
                Screen.OptionCards -> app.tiviate.ui.organise.OptionCardsScreen()
                Screen.Settings -> SettingsScreen()
            }
            }
        }
    }
}
