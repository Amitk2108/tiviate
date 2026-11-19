package app.tiviate.ui.phone

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.tiviate.data.AppSettings
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.components.Spinner
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.components.minutesLeft
import app.tiviate.ui.components.progressOf
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.guide.FollowCustomGroup
import app.tiviate.ui.guide.groupEntries
import app.tiviate.ui.organise.ChannelSheet
import app.tiviate.ui.organise.SheetTarget
import app.tiviate.ui.player.ChannelRow
import app.tiviate.ui.player.PlayerViewModel
import app.tiviate.ui.player.Toast
import app.tiviate.ui.player.VideoSlot
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The phone's Live screen (also tablets held upright): the video on top, what's on, the groups as chips
 * and the channel list below. Turning the phone sideways, or the fullscreen button, plays fullscreen.
 */
@Composable
fun PhoneHome() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(graph))
    val playback by graph.player.engine.state.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val customGroups by vm.customGroups.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    FollowCustomGroup(category, customGroups) { vm.selectCategory(it) }
    val channels = vm.channels.collectAsLazyPagingItems()
    val now = rememberNow()
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    // Nothing starts by itself (as on TV): the playlist and group load, a tap plays.
    LifecycleStartEffect(Unit) {
        vm.ensurePlaying(autoplay = false)
        onStopOrDispose { }
    }
    fun fullscreen() { (context as? Activity)?.let { Rotation.fullscreen(it, on = true) } }

    Column(Modifier.fillMaxSize().testTag("phone_home")) {
        // ---- Video ----
        var controls by remember { mutableStateOf(false) }
        // Each touch restarts the 3.5 s; no double-tap, so a single tap answers at once.
        var touchedAt by remember { mutableStateOf(0L) }
        LaunchedEffect(controls, touchedAt) { if (controls) { delay(3_500); controls = false } }
        fun touched() { touchedAt = android.os.SystemClock.uptimeMillis() }
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("phone_video")
                .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls; touched() }) },
            contentAlignment = Alignment.Center,
        ) {
            val key = current?.let { "${it.playlistId}:${it.stableKey}" }
            VideoSlot(settings.displayModeFor(key), settings.zoomPercent, Modifier.fillMaxSize())
            when {
                playback.channelKey == null -> FocusSurface(
                    onClick = { vm.ensurePlaying(autoplay = true) }, container = Color.Transparent, shape = Tokens.PillShape, scale = 1f,
                    modifier = Modifier.testTag("phone_play_last"),
                ) { Icon(Icons.Rounded.PlayCircle, contentDescription = t("Play"), tint = Tokens.Text, modifier = Modifier.size(64.dp)) }
                playback.issue != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t(playback.issue!!.title), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                    app.tiviate.ui.components.ActionButton(t("Try again"), { graph.player.engine.retryNow() })
                }
                playback.isBuffering -> Spinner(40.dp)
            }
            if (controls && playback.channelKey != null) {
                Row(
                    Modifier.fillMaxSize().background(Tokens.Scrim),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Rounded.SkipPrevious, t("Previous channel")) { touched(); vm.zap(forward = false) }
                    RoundButton(if (playback.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (playback.isPaused) t("Play") else t("Pause")) { touched(); vm.togglePause() }
                    RoundButton(Icons.Rounded.SkipNext, t("Next channel")) { touched(); vm.zap(forward = true) }
                    RoundButton(Icons.Rounded.Tune, t("Options")) {
                        // The full player options (audio, subtitles, display, zoom, sleep…) as on TV, in fullscreen.
                        vm.open(app.tiviate.ui.player.Overlay.Options)
                        fullscreen()
                    }
                    RoundButton(Icons.Rounded.Fullscreen, t("Fullscreen")) { fullscreen() }
                }
            }
        }

        // ---- What's on ----
        NowStrip(vm, now)

        // ---- Groups and channels ----
        GroupChips(
            entries = remember(groups, playlists, playlist?.id, customGroups, settings.showAllChannels, settings.showFavoritesGroup, settings.showRecentGroup) {
                groupEntries(groups, playlists, playlist?.id, customGroups, settings.showAllChannels, settings.showFavoritesGroup, settings.showRecentGroup)
            },
            selected = category,
            onPick = { vm.selectCategory(it) },
            onSwitch = { vm.switchPlaylist(it.id) },
        )
        val listState = rememberLazyListState()
        LaunchedEffect(listState, channels) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .debounce(120) // not a query per row while flinging
                .collect { indices -> vm.loadNowNext(indices.filter { it < channels.itemCount }.mapNotNull { channels.peek(it)?.epgRef }) }
        }
        LaunchedEffect(category) { listState.scrollToItem(0) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Tokens.Background)) {
            // Tablets held upright have room for what's on next beside each channel.
            val wide = maxWidth >= 600.dp
            if (channels.itemCount == 0) {
                Text(t("No channels here yet"), modifier = Modifier.align(Alignment.Center), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("phone_channel_list"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(count = channels.itemCount, key = channels.itemKey { it.id }, contentType = { "channel" }) { index ->
                    val ch = channels[index]
                    val info = ch?.epgRef?.let { vm.nowNext[it] }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelRow(
                            channel = ch,
                            isPlaying = ch != null && ch.stableKey == current?.stableKey,
                            isFavorite = ch != null && ch.stableKey in favorites,
                            info = info,
                            now = now,
                            showNumber = settings.showChannelNumbers,
                            modifier = Modifier.weight(1f).testTag("phone_channel_$index"),
                            onClick = {
                                ch ?: return@ChannelRow
                                // Tapping the channel already playing goes fullscreen.
                                if (ch.stableKey == current?.stableKey && playback.channelKey != null) fullscreen() else vm.tune(ch)
                            },
                            onLongClick = { ch?.let { sheet = SheetTarget(it.playlistId, it, category) } },
                        )
                        if (wide) {
                            Text(
                                info?.next?.let { tf("Next  %s  %s", formatTime(it.startMs), it.title) } ?: "",
                                modifier = Modifier.width(280.dp).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize),
                            )
                        }
                    }
                }
            }
        }
        PhoneBottomBar(PhoneTab.LIVE)
    }
    sheet?.let { s -> ChannelSheet(s, onClose = { sheet = null }, onMessage = vm::showToast) }
    toast?.let { Toast(it) }
}

/** The playing channel and its programme, with time left. */
@Composable
private fun NowStrip(vm: PlayerViewModel, now: Long) {
    val current by vm.current.collectAsStateWithLifecycle()
    val ch = current
    LaunchedEffect(ch?.epgRef) { ch?.epgRef?.let { vm.loadNowNext(listOf(it)) } }
    Column(Modifier.fillMaxWidth().background(Tokens.Background).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (ch == null) {
            Text(t("Tap a channel to start watching"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            return@Column
        }
        val p = ch.epgRef?.let { vm.nowNext[it] }?.now
        Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
        Text(p?.title ?: t("No programme information"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${formatTime(p.startMs)} – ${formatTime(p.stopMs)}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                ProgressLine(progressOf(p.startMs, p.stopMs, now), Modifier.weight(1f))
                Text(tf("%d min left", minutesLeft(p.stopMs, now)), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, container = Tokens.Scrim, shape = Tokens.PillShape, scale = 1f) {
        Icon(icon, contentDescription = label, tint = Tokens.Text, modifier = Modifier.padding(12.dp).size(30.dp))
    }
}
