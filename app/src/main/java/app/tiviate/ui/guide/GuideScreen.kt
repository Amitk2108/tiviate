package app.tiviate.ui.guide

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
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.components.Clock
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.components.progressOf
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.player.AspectMode
import app.tiviate.ui.player.KeepScreenOn
import app.tiviate.ui.player.VideoSurface
import app.tiviate.ui.player.categoryLabel
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.components.RemoteImage
import kotlinx.coroutines.launch

@Composable
fun GuideScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
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
    var tick by remember { mutableIntStateOf(0) } // bumps after every GuideState change
    var initialised by remember(category) { mutableStateOf(false) }
    LaunchedEffect(ready) { if (!ready) initialised = false }
    var details by remember { mutableStateOf<FocusBlock?>(null) }
    var picker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    // Every start (first open, or back from the TV home screen) refreshes the guide and its preview.
    LifecycleStartEffect(Unit) {
        vm.open()
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

    // Load programmes for the rows on screen and the window being shown.
    LaunchedEffect(tick, listState.firstVisibleItemIndex, items.itemCount) {
        val first = listState.firstVisibleItemIndex
        val refs = (first until minOf(first + 12, items.itemCount)).mapNotNull { channelAt(it)?.epgRef }
        vm.ensureProgrammes(refs, state.viewStartMs, state.viewStartMs + state.windowMs)
    }

    // When the focused row's programmes arrive after the focus was set, snap onto the real programme.
    val focusedRowProgrammes = channelAt(state.row)?.epgRef?.let { vm.programmes[it] }
    LaunchedEffect(focusedRowProgrammes) {
        if (focusedRowProgrammes != null && state.focused?.programme == null && initialised) {
            state.focusRow(state.row, focusedRowProgrammes)
            tick++
        }
    }

    fun onOk() {
        val channel = channelAt(state.row) ?: return
        val block = state.focused
        val nowMs = System.currentTimeMillis()
        val live = block == null || (block.startMs <= nowMs && nowMs < block.stopMs)
        when {
            channel.stableKey == current?.stableKey && live -> nav.back() // already playing: go fullscreen, no reconnect
            live -> vm.tune(channel) // preview it here; a second OK goes fullscreen
            else -> details = block
        }
    }

    fun handleKey(native: AndroidKeyEvent): Boolean {
        if (native.action != AndroidKeyEvent.ACTION_DOWN) {
            return native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER
        }
        val rows = items.itemCount
        when (native.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> state.moveRow(-1, rows, rowProgrammes((state.row - 1).coerceAtLeast(0)))
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> state.moveRow(+1, rows, rowProgrammes((state.row + 1).coerceAtMost(rows - 1)))
            AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                state.moveRow(-visibleRows, rows, rowProgrammes((state.row - visibleRows).coerceAtLeast(0)))
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                state.moveRow(visibleRows, rows, rowProgrammes((state.row + visibleRows).coerceAtMost(rows - 1)))
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> state.moveTime(-1, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> state.moveTime(+1, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> state.jumpHours(2, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> state.jumpHours(-2, rowProgrammes(state.row))
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> { if (native.repeatCount == 0) onOk(); return true }
            AndroidKeyEvent.KEYCODE_MENU -> { picker = true; return true }
            AndroidKeyEvent.KEYCODE_GUIDE -> { nav.back(); return true }
            else -> return false
        }
        keepRowVisible()
        tick++
        return true
    }

    BackHandler(enabled = details != null || picker) {
        details = null
        picker = false
    }
    LaunchedEffect(details, picker) { if (details == null && !picker && initialised) focusSoon(gridFocus) }
    KeepScreenOn()

    Column(Modifier.fillMaxSize().background(Tokens.Background).testTag("guide_screen")) {
        // Top: live preview of the playing channel and details of the focused programme.
        Row(Modifier.fillMaxWidth().weight(0.4f).padding(start = Tokens.SafeMargin, end = Tokens.SafeMargin, top = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black, Tokens.SmallShape), contentAlignment = Alignment.Center) {
                VideoSurface(graph.player.engine, AspectMode.FIT, Modifier.fillMaxSize())
                val playback by graph.player.engine.state.collectAsStateWithLifecycle()
                when {
                    playback.issue != null -> Text(playback.issue!!.title, modifier = Modifier.padding(16.dp), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    playback.isBuffering -> app.tiviate.ui.components.Spinner(32.dp)
                }
            }
            val focusedChannel = withTick(tick) { channelAt(state.row) }
            withTick(tick) {
                FocusDetails(state.focused, focusedChannel?.name, now, Modifier.weight(1f))
            }
            Clock(TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Medium))
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.6f).padding(horizontal = 24.dp)) {
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
                            GuideRow(
                                channel = channel,
                                programmes = channel?.epgRef?.let { vm.programmes[it] }.orEmpty(),
                                viewStartMs = viewStart,
                                windowMs = state.windowMs,
                                nowMs = now,
                                stripWidth = stripWidth,
                                isPlaying = channel != null && channel.stableKey == current?.stableKey,
                                showNumber = settings.showChannelNumbers,
                                focused = if (isFocusedRow) withTick(tick) { state.focused } else null,
                            )
                        }
                    }
                    NowLine(viewStart, state.windowMs, now, stripWidth)
                }
            }
        }
    }

    details?.let { ProgrammeDetailsDialog(it) { details = null } }
    if (picker) {
        GroupPicker(
            entries = listOf(Category.All, Category.Favorites, Category.Recent) + groups.mapNotNull { g -> g.name?.let { Category.Group(it) } },
            selected = category,
            onPick = { picked ->
                picker = false
                vm.selectCategory(picked)
                state.focusRow(0, emptyList())
                scope.launch { listState.scrollToItem(0) }
                tick++
            },
        )
    }
}

/** Reads a value while depending on [tick], so the composable refreshes after GuideState changes. */
@Composable
private inline fun <T> withTick(@Suppress("UNUSED_PARAMETER") tick: Int, block: () -> T): T = block()

private fun dayLabel(ms: Long, now: Long): String = when {
    DateUtils.isToday(ms) -> "Today"
    DateUtils.isToday(ms - DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
    DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
}

@Composable
private fun FocusDetails(block: FocusBlock?, channelName: String?, now: Long, modifier: Modifier) {
    val p = block?.programme
    Row(modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            channelName?.let { Text(it, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold)) }
            Text(p?.title ?: "No programme information", maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            if (block != null) {
                Text("${formatTime(block.startMs)} – ${formatTime(block.stopMs)}" + (p?.episode?.let { "  ·  $it" } ?: ""), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
                if (block.startMs <= now && now < block.stopMs) ProgressLine(progressOf(block.startMs, block.stopMs, now), Modifier.width(320.dp))
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
            Text(p?.title ?: "No programme information", style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            Text("${formatTime(block.startMs)} – ${formatTime(block.stopMs)}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            p?.description?.let { Text(it, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize)) }
            FocusSurface(onClick = onClose, modifier = Modifier.focusRequester(first), container = Tokens.Raised) {
                Text("Close", modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
private fun GroupPicker(entries: List<Category>, selected: Category, onPick: (Category) -> Unit) {
    val first = remember { FocusRequester() }
    val selectedIndex = entries.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) { focusSoon(first) }
    Box(Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag("guide_groups"), contentAlignment = Alignment.CenterStart) {
        LazyColumn(
            state = listState,
            modifier = Modifier.width(340.dp).fillMaxHeight().background(Tokens.Background).padding(top = 28.dp, start = 16.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(entries.size) { i ->
                val c = entries[i]
                FocusSurface(
                    onClick = { onPick(c) },
                    modifier = (if (i == selectedIndex) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    container = Color.Transparent,
                    scale = 1.02f,
                ) {
                    Text(
                        categoryLabel(c),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = if (c == selected) Tokens.Cyan else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = if (c == selected) FontWeight.SemiBold else FontWeight.Normal),
                    )
                }
            }
        }
    }
}
