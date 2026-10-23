package app.tiviate.ui.guide

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.PlayCircle
import app.tiviate.ui.player.backgroundWithHole
import app.tiviate.ui.player.VideoSlot
import android.text.format.DateUtils
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Text
import app.tiviate.data.AppSettings
import app.tiviate.data.Category
import app.tiviate.data.DisplayMode
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.LocalScreenActive
import app.tiviate.ui.Screen
import app.tiviate.ui.components.KeyAction
import app.tiviate.ui.organise.ChannelSheet
import app.tiviate.ui.organise.SheetTarget
import app.tiviate.ui.components.LongPressDetector
import app.tiviate.ui.components.Clock
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.components.progressOf
import app.tiviate.ui.components.minutesLeft
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.player.KeepScreenOn
import app.tiviate.ui.player.VideoSurface
import app.tiviate.ui.player.categoryLabel
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.components.RemoteImage
import kotlinx.coroutines.launch
import app.tiviate.ui.t
import app.tiviate.ui.tf

@Composable
fun GuideScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val vm: GuideViewModel = viewModel(factory = GuideViewModel.factory(graph))
    val scope = rememberCoroutineScope()

    val ready by vm.ready.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val current by graph.player.current.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val items = vm.channels.collectAsLazyPagingItems()

    val now = rememberNow(30_000)
    val state = remember { GuideState(System.currentTimeMillis()) }
    LaunchedEffect(settings.epgPastDays) { state.pastDays = settings.epgPastDays }
    var tick by remember { mutableIntStateOf(0) } // bumps after every GuideState change
    var initialised by remember(category) { mutableStateOf(false) }
    LaunchedEffect(ready) { if (!ready) initialised = false }
    var details by remember { mutableStateOf<FocusBlock?>(null) }
    var side by remember { mutableStateOf(SideNav.GRID) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val leftPress = remember { LongPressDetector() }
    val okPress = remember { LongPressDetector() }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    val customGroups by vm.customGroups.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    // Every start (first open, or back from the TV home screen) refreshes the guide and its preview.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val logoPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.roundToPx() }
    LaunchedEffect(playlist?.id) { playlist?.id?.let { vm.prefetchLogos(appContext, logoPx) } }
    LifecycleStartEffect(Unit) {
        vm.open()
        state.backToNow(System.currentTimeMillis(), (if (state.row < items.itemCount) items.peek(state.row) else null)?.epgRef?.let { vm.programmes[it] }.orEmpty())
        tick++
        onStopOrDispose { }
    }
    val visibleRows = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

    fun channelAt(row: Int) = if (row in 0 until items.itemCount) items.peek(row) else null
    fun rowProgrammes(row: Int) = channelAt(row)?.epgRef?.let { vm.programmes[it] }.orEmpty()

    fun keepRowVisible() {
        val first = listState.firstVisibleItemIndex
        // Fully visible rows only: the last one on screen is usually cut off.
        val fullyVisible = listState.layoutInfo.visibleItemsInfo.count { it.offset + it.size <= listState.layoutInfo.viewportEndOffset }.coerceAtLeast(1)
        val target = when {
            state.row < first -> state.row
            state.row >= first + fullyVisible -> state.row - fullyVisible + 1
            else -> return
        }
        scope.launch { listState.scrollToItem(target.coerceAtLeast(0)) }
    }

    // Open on the playing channel with the programme on air focused.
    LaunchedEffect(ready, items.itemCount, category) {
        if (!ready || items.itemCount == 0 || initialised) return@LaunchedEffect
        val row = vm.initialRow.coerceIn(0, items.itemCount - 1)
        state.focusRow(row, rowProgrammes(row))
        listState.scrollToItem((row - 2).coerceAtLeast(0))
        initialised = true
        tick++
        focusSoon(gridFocus)
    }

    // Load programmes for the rows on screen and the window being shown. Keyed on the rows' guide
    // links too: after login the guide opens before its data arrives, and channels get linked later.
    val firstRow = listState.firstVisibleItemIndex
    val visibleRefs = (firstRow until minOf(firstRow + 12, items.itemCount)).mapNotNull { channelAt(it)?.epgRef }
    LaunchedEffect(tick, visibleRefs) {
        vm.ensureProgrammes(visibleRefs, state.viewStartMs, state.viewStartMs + state.windowMs)
    }

    // When the focused row's programmes arrive after the focus was set, snap onto the real programme.
    val focusedRowProgrammes = channelAt(state.row)?.epgRef?.let { vm.programmes[it] }
    LaunchedEffect(focusedRowProgrammes) {
        if (focusedRowProgrammes != null && state.focused?.programme == null && initialised) {
            state.focusRow(state.row, focusedRowProgrammes)
            tick++
        }
    }

    // A guide left open keeps up with the clock (until the viewer moves in time).
    LaunchedEffect(now) {
        if (initialised && state.keepUpWith(System.currentTimeMillis(), rowProgrammes(state.row))) tick++
    }

    fun openSheet() {
        val pid = playlist?.id ?: return
        sheet = SheetTarget(pid, channelAt(state.row), category, state.focused?.programme?.title)
    }

    fun onOk() {
        val channel = channelAt(state.row) ?: return
        val block = state.focused
        val nowMs = System.currentTimeMillis()
        val live = block == null || (block.startMs <= nowMs && nowMs < block.stopMs)
        // Any programme of a channel plays that channel live (no catch-up); its description is in the long-OK menu.
        when {
            channel.stableKey == current?.stableKey -> nav.back() // already playing: go fullscreen, no reconnect
            settings.alwaysExternalPlayer -> app.tiviate.player.ExternalPlayer.open(activity ?: return, channel.streamUrl, channel.name)
            else -> vm.tune(channel) // preview it here; a second OK goes fullscreen
        }
        if (!live) {
            state.backToNow(nowMs, rowProgrammes(state.row))
            tick++
        }
    }

    fun handleKey(native: AndroidKeyEvent): Boolean {
        // Left on the programme on air opens the groups column; a long Left (or Left elsewhere) moves in time.
        if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
            if (!state.leftOpensGroups(System.currentTimeMillis())) {
                if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                    state.moveTime(-1, rowProgrammes(state.row))
                    keepRowVisible()
                    tick++
                }
                return true
            }
            when (leftPress.onKey(native)) {
                KeyAction.Short -> side = SideNav.GROUPS
                KeyAction.Long -> { state.moveTime(-1, rowProgrammes(state.row)); keepRowVisible(); tick++ }
                KeyAction.None -> Unit
            }
            return true
        }
        // OK: preview / fullscreen / details; long OK: the channel and programme menu.
        if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER) {
            when (okPress.onKey(native)) {
                KeyAction.Short -> onOk()
                KeyAction.Long -> openSheet()
                KeyAction.None -> Unit
            }
            return true
        }
        if (native.action != AndroidKeyEvent.ACTION_DOWN) return false
        val rows = items.itemCount
        state.keepUpWith(System.currentTimeMillis(), rowProgrammes(state.row)) // Up/Down land on what is on now
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> state.moveRow(-1, rows, rowProgrammes((state.row - 1).coerceAtLeast(0)))
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> state.moveRow(+1, rows, rowProgrammes((state.row + 1).coerceAtMost(rows - 1)))
            AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                state.moveRow(-visibleRows, rows, rowProgrammes((state.row - visibleRows).coerceAtLeast(0)))
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                state.moveRow(visibleRows, rows, rowProgrammes((state.row + visibleRows).coerceAtMost(rows - 1)))
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> state.moveTime(+1, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> state.jumpHours(2, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> state.jumpHours(-2, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_MENU -> { openSheet(); return true }
            AndroidKeyEvent.KEYCODE_GUIDE -> { nav.back(); return true }
            else -> return false
        }
        keepRowVisible()
        tick++
        return true
    }

    // Back closes what is open; on the bare guide it leaves the app (after a second press unless turned off).
    var lastBackAt by remember { mutableStateOf(0L) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2_000); toast = null } }
    BackHandler(enabled = LocalScreenActive.current) {
        when {
            sheet != null -> sheet = null
            details != null -> details = null
            side == SideNav.RAIL -> side = SideNav.GROUPS
            side == SideNav.GROUPS -> side = SideNav.GRID
            !settings.confirmExit || System.currentTimeMillis() - lastBackAt < 2_000 -> activity?.finish()
            else -> {
                lastBackAt = System.currentTimeMillis()
                toast = t("Press Back again to exit")
            }
        }
    }
    LaunchedEffect(details, side, sheet) { if (details == null && sheet == null && side == SideNav.GRID && initialised) focusSoon(gridFocus) }
    KeepScreenOn()

    // Overlay mode: the guide sits over the full-screen video instead of next to a preview window.
    if (settings.guideOverlay) {
        VideoSlot(settings.displayModeFor(current?.let { "${it.playlistId}:${it.stableKey}" }), settings.zoomPercent, Modifier.fillMaxSize())
    }
    var previewRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Column(
        Modifier.fillMaxSize()
            .then(
                if (settings.guideOverlay) Modifier.background(Tokens.Background.copy(alpha = 0.82f))
                else Modifier.backgroundWithHole(Tokens.Background, { previewRect }, cornerRadius = 10.dp),
            )
            .testTag("guide_screen"),
    ) {
        // Top: live preview of the playing channel and details of the focused programme.
        Row(Modifier.fillMaxWidth().weight(0.4f).padding(start = Tokens.SafeMargin, end = Tokens.SafeMargin, top = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (!settings.guideOverlay) Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                val playback by graph.player.engine.state.collectAsStateWithLifecycle()
                VideoSlot(DisplayMode.FIT, 100, Modifier.fillMaxSize(), onBounds = { previewRect = it })
                when {
                    // Nothing playing yet: a play sign until the viewer picks a channel.
                    playback.channelKey == null -> androidx.tv.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.PlayCircle, contentDescription = null,
                        tint = Tokens.TextSecondary, modifier = Modifier.size(56.dp),
                    )
                    playback.issue != null -> Text(t(playback.issue!!.title), modifier = Modifier.padding(16.dp), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    playback.isBuffering -> app.tiviate.ui.components.Spinner(32.dp)
                }
            }
            val focusedChannel = withTick(tick) { channelAt(state.row) }
            withTick(tick) {
                FocusDetails(state.focused, focusedChannel?.name, now, Modifier.weight(1f))
            }
            Clock(TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Medium))
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.6f).padding(horizontal = 12.dp)) {
            val stripWidth = maxWidth - GUIDE_CHANNEL_WIDTH
            val viewStart = withTick(tick) { state.viewStartMs }
            Column(Modifier.fillMaxSize()) {
                GuideHeader(viewStart, state.windowMs, state.slotMs, stripWidth, dayLabel(viewStart, now) + "  ·  " + categoryLabel(category))
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(gridFocus)
                            .focusable()
                            .onKeyEvent { handleKey(it.nativeKeyEvent) },
                    ) {
                        items(count = items.itemCount, key = { i -> channelAt(i)?.id ?: -i.toLong() - 1 }, contentType = { "row" }) { i ->
                            val channel = items[i]
                            val isFocusedRow = withTick(tick) { i == state.row }
                            val rowList = channel?.epgRef?.let { vm.programmes[it] }.orEmpty()
                            GuideRow(
                                channel = channel,
                                programmes = rowList,
                                emptyBlocks = state.emptyBlocks(rowList, viewStart, viewStart + state.windowMs),
                                viewStartMs = viewStart,
                                windowMs = state.windowMs,
                                nowMs = now,
                                stripWidth = stripWidth,
                                isPlaying = channel != null && channel.stableKey == current?.stableKey,
                                showNumber = settings.showChannelNumbers,
                                focused = if (isFocusedRow) withTick(tick) { state.focused } else null,
                                highlight = settings.guideHighlight,
                            )
                        }
                    }
                    NowLine(viewStart, state.windowMs, now, stripWidth)
                }
            }
        }
    }

    if (side != SideNav.GRID) {
        Row(Modifier.fillMaxSize().background(Tokens.ScrimSoft)) {
            if (side == SideNav.RAIL) {
                NavRail(
                    onSearch = { side = SideNav.GRID; nav.push(Screen.Search) },
                    onTv = { side = SideNav.GROUPS },
                    onSettings = { side = SideNav.GRID; nav.push(Screen.Settings) },
                )
            }
            GroupsColumn(
                playlistName = playlist?.name,
                entries = groupEntries(groups, playlists, playlist?.id, customGroups, settings.showAllChannels, settings.showFavoritesGroup, settings.showRecentGroup),
                selected = category,
                onPick = { picked ->
                    side = SideNav.GRID
                    if (picked != category) {
                        vm.selectCategory(picked)
                        state.focusRow(0, emptyList())
                        scope.launch { listState.scrollToItem(0) }
                        tick++
                    }
                },
                onSwitch = { p -> side = SideNav.GRID; vm.switchPlaylist(p.id) },
                onLeft = { side = SideNav.RAIL },
                onRight = { side = SideNav.GRID },
            )
        }
    }
    details?.let { ProgrammeDetailsDialog(it) { details = null } }
    sheet?.let { t ->
        ChannelSheet(t, onClose = { sheet = null }, onDescription = state.focused?.let { b -> { details = b } }, onMessage = { toast = it })
    }
    toast?.let { app.tiviate.ui.player.Toast(it) }
}

/** Reads a value while depending on [tick], so the composable refreshes after GuideState changes. */
@Composable
private inline fun <T> withTick(@Suppress("UNUSED_PARAMETER") tick: Int, block: () -> T): T = block()

private fun dayLabel(ms: Long, now: Long): String = when {
    DateUtils.isToday(ms) -> t("Today")
    DateUtils.isToday(ms - DateUtils.DAY_IN_MILLIS) -> t("Tomorrow")
    DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> t("Yesterday")
    else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
}

@Composable
private fun FocusDetails(block: FocusBlock?, channelName: String?, now: Long, modifier: Modifier) {
    val p = block?.programme
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            channelName?.let { Text(it, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold)) }
            Text(p?.title ?: t("No programme information"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            if (block != null) {
                Text("${formatTime(block.startMs)} – ${formatTime(block.stopMs)}" + (p?.episode?.let { "  ·  $it" } ?: ""), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
                if (block.startMs <= now && now < block.stopMs) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(progressOf(block.startMs, block.stopMs, now), Modifier.weight(1f))
                        Text(tf("%d min left", minutesLeft(block.stopMs, now)), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    }
                }
            }
            p?.description?.let { Text(it, maxLines = 4, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize)) }
        }
        p?.iconUrl?.let { url ->
            RemoteImage(url, contentScale = ContentScale.Crop, modifier = Modifier.width(240.dp).aspectRatio(16f / 9f).background(Tokens.Raised, Tokens.SmallShape))
        }
    }
}

@Composable
private fun ProgrammeDetailsDialog(block: FocusBlock, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    Box(Modifier.fillMaxSize().background(Tokens.Scrim).testTag("programme_details"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(Tokens.Surface, Tokens.Shape).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val p = block.programme
            p?.iconUrl?.let { RemoteImage(it, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(240.dp).background(Tokens.Raised, Tokens.SmallShape)) }
            Text(p?.title ?: t("No programme information"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            Text("${formatTime(block.startMs)} – ${formatTime(block.stopMs)}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            p?.description?.let { Text(it, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize)) }
            FocusSurface(onClick = onClose, modifier = Modifier.focusRequester(first), container = Tokens.Raised) {
                Text(t("Close"), modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
