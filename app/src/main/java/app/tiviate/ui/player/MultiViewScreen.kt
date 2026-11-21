package app.tiviate.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.sync.HttpClients
import app.tiviate.player.StreamType
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

private const val TILES = 4

/**
 * Up to four channels at once (2 × 2). The selected tile has the sound and a yellow frame. Remote: the
 * D-pad picks a tile, OK chooses its channel, Back shows the selected one fullscreen. Touch: tap a tile to
 * select it, tap it again to choose its channel. Each tile is a small player of its own (at most 720p, so
 * low-end TVs keep up); each one uses one of the provider's connections.
 */
@Composable
fun MultiViewScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val tiles = remember { mutableStateListOf<ChannelEntity?>(*arrayOfNulls(TILES)) }
    var selected by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val root = remember { FocusRequester() }
    val playlistUa by produceState<String?>(null) {
        value = graph.settings.settings.first().activePlaylistId?.let { graph.playlists.get(it)?.userAgent }
    }

    // The channel on screen starts in the first tile; the main player rests meanwhile.
    LaunchedEffect(Unit) {
        if (tiles[0] == null) tiles[0] = graph.player.current.value
        graph.player.stop()
        focusSoon(root)
    }
    if (LocalFormFactor.current == FormFactor.PHONE) {
        DisposableEffect(Unit) {
            val activity = context as? Activity
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    // Back: the selected channel fullscreen (the main player takes over).
    fun leave() {
        val channel = tiles[selected] ?: tiles.firstOrNull { it != null }
        nav.back()
        channel?.let { graph.player.tune(it, playlistUa) }
    }
    BackHandler(enabled = !picking) { leave() }

    Column(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).testTag("multiview_screen")
            .focusRequester(root)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || picking) return@onKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { if (selected % 2 == 1) selected--; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (selected % 2 == 0) selected++; true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (selected >= 2) selected -= 2; true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (selected < 2) selected += 2; true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { picking = true; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    Tile(
                        channel = tiles[i], index = i, selected = i == selected, http = graph.http,
                        userAgent = tiles[i]?.userAgent ?: playlistUa ?: HttpClients.defaultUserAgent,
                        modifier = Modifier.weight(1f).fillMaxSize().pointerInput(i) {
                            detectTapGestures { if (selected == i) picking = true else selected = i }
                        },
                    )
                }
            }
        }
    }
    if (tiles.all { it == null } && !picking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(t("OK: choose a channel for the selected tile"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
        }
    }
    if (picking) {
        val channels by produceState(emptyList<ChannelEntity>()) {
            val pid = graph.settings.settings.first().activePlaylistId ?: return@produceState
            value = graph.db.channels().allInPlaylist(pid).filter { !it.hidden }
        }
        ChoiceDialog(
            tf("Tile %d", selected + 1),
            buildList {
                if (tiles[selected] != null) add(Choice(t("Empty"), false) { tiles[selected] = null; picking = false; root.requestFocusSafely() })
                channels.forEach { c -> add(Choice(c.name, c.stableKey == tiles[selected]?.stableKey) { tiles[selected] = c; picking = false; root.requestFocusSafely() }) }
            },
            empty = t("Loading channels…"),
            tag = "multiview_picker",
            onDismiss = { picking = false },
        )
        BackHandler { picking = false; root.requestFocusSafely() }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Tile(channel: ChannelEntity?, index: Int, selected: Boolean, http: OkHttpClient, userAgent: String, modifier: Modifier) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var failed by remember(channel?.stableKey) { mutableStateOf(false) }
    val player = remember(channel?.stableKey) { channel?.let { tilePlayer(context, http, userAgent, it) } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (isPlaying) failed = false }
            override fun onPlayerError(error: PlaybackException) { failed = true }
            // Live TV never "ends": the provider closed the connection, so open it again.
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) player?.let { it.seekToDefaultPosition(); it.prepare(); it.play() }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener); player?.release(); playing = false }
    }
    // A tile that failed tries again every few seconds (providers drop extra connections now and then).
    LaunchedEffect(player, failed) {
        if (player != null && failed) { delay(4_000); player.prepare(); player.play() }
    }
    LaunchedEffect(player, selected) { player?.volume = if (selected) 1f else 0f }
    Box(
        modifier.padding(2.dp)
            .then(if (selected) Modifier.border(3.dp, Tokens.Selector) else Modifier)
            .testTag("multiview_tile_$index")
            .semantics { stateDescription = if (playing) "playing" else "" },
    ) {
        if (player != null) {
            AndroidView(
                factory = { PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            when {
                channel == null -> t("Empty · OK to choose a channel")
                failed -> tf("%s · can't play (connection limit?)", channel.name)
                else -> channel.name
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(if (channel == null) Alignment.Center else Alignment.TopStart).padding(8.dp)
                .background(Tokens.Scrim, Tokens.SmallShape).padding(horizontal = 8.dp, vertical = 4.dp),
            style = TextStyle(color = if (selected) Tokens.Selector else Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium),
        )
    }
}

/** A light player for one tile: at most 720p, FFmpeg sound for AC3/DTS, starts at once. */
@OptIn(UnstableApi::class)
private fun tilePlayer(context: Context, http: OkHttpClient, userAgent: String, channel: ChannelEntity): ExoPlayer {
    val network = OkHttpDataSource.Factory(http).setUserAgent(userAgent)
        .setDefaultRequestProperties(buildMap { channel.referrer?.let { put("Referer", it) } })
    val selector = DefaultTrackSelector(context).apply { setParameters(buildUponParameters().setMaxVideoSize(1280, 720)) }
    val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network))
    app.tiviate.player.DrmSupport.of(channel)?.let { spec -> val manager = app.tiviate.player.DrmSupport.sessionManager(spec, network); sources.setDrmSessionManagerProvider { manager } }
    return ExoPlayer.Builder(context, DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setEnableDecoderFallback(true))
        .setMediaSourceFactory(sources)
        .setTrackSelector(selector)
        .build()
        .apply {
            val mime = app.tiviate.player.DrmSupport.mime(channel.manifestType) ?: StreamType.mimeFromUrl(channel.streamUrl)
            setMediaItem(MediaItem.Builder().setUri(channel.streamUrl).setMimeType(mime).build())
            volume = 0f
            playWhenReady = true
            prepare()
        }
}

private fun FocusRequester.requestFocusSafely() { runCatching { requestFocus() } }
