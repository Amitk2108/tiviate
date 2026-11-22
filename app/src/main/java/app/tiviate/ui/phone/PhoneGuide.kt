package app.tiviate.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import app.tiviate.data.AppSettings
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.guide.FocusBlock
import app.tiviate.ui.guide.FollowCustomGroup
import app.tiviate.ui.guide.GUIDE_ROW_HEIGHT
import app.tiviate.ui.guide.GuideHeader
import app.tiviate.ui.guide.GuideRow
import app.tiviate.ui.guide.GuideState
import app.tiviate.ui.guide.GuideViewModel
import app.tiviate.ui.guide.NowLine
import app.tiviate.ui.guide.dayLabel
import app.tiviate.ui.guide.groupEntries
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens

private val LOGO_COLUMN = 72.dp

/**
 * The TV guide on a phone: a narrow column of logos and a timeline that is dragged sideways; channels
 * scroll up and down. Tap a programme for its details, tap a logo to watch that channel.
 */
@Composable
fun PhoneGuide() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    val vm: GuideViewModel = viewModel(factory = GuideViewModel.factory(graph))
    val category by vm.category.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val customGroups by vm.customGroups.collectAsStateWithLifecycle()
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    val reminders = remember(settings.reminders) { app.tiviate.data.Reminders.keys(settings) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playing by graph.player.current.collectAsStateWithLifecycle()
    FollowCustomGroup(category, customGroups) { vm.selectCategory(it) }
    val items = vm.channels.collectAsLazyPagingItems()
    val now = rememberNow(30_000)
    LifecycleStartEffect(Unit) {
        vm.open()
        onStopOrDispose { }
    }
    var details by remember { mutableStateOf<Pair<ChannelEntity, FocusBlock>?>(null) }
    fun watch(ch: ChannelEntity) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        vm.tune(ch)
        nav.replaceAll(Screen.Player)
    }

    Column(Modifier.fillMaxSize().background(Tokens.Background).testTag("phone_guide")) {
        GroupChips(
            entries = remember(groups, playlists, playlist?.id, customGroups, settings.showAllChannels, settings.showFavoritesGroup, settings.showRecentGroup) {
                groupEntries(groups, playlists, playlist?.id, customGroups, settings.showAllChannels, settings.showFavoritesGroup, settings.showRecentGroup)
            },
            selected = category,
            onPick = { vm.selectCategory(it) },
            onSwitch = { vm.switchPlaylist(it.id) },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // About an hour and a half fits a phone held upright; wider screens show more.
            val windowMs = if (maxWidth < 600.dp) 90 * 60_000L else 3 * 60 * 60_000L
            val state = remember(windowMs) { GuideState(System.currentTimeMillis(), windowMs = windowMs) }
            val archiveDays by remember(playlist?.id) { playlist?.id?.let { graph.db.channels().archiveDays(it) } ?: kotlinx.coroutines.flow.flowOf(0) }
                .collectAsStateWithLifecycle(0)
            LaunchedEffect(state, settings.epgPastDays, archiveDays) { state.pastDays = maxOf(settings.epgPastDays, archiveDays) }
            var viewStart by remember(state) { mutableLongStateOf(state.viewStartMs) }
            val stripWidth = maxWidth - LOGO_COLUMN
            val stripPx = with(LocalDensity.current) { stripWidth.toPx() }
            val logoPx = with(LocalDensity.current) { LOGO_COLUMN.toPx() }
            val listState = rememberLazyListState()
            val first = listState.firstVisibleItemIndex
            val visibleRefs = (first until minOf(first + 14, items.itemCount)).mapNotNull { if (it < items.itemCount) items.peek(it)?.epgRef else null }
            val slot = state.floorToSlot(viewStart)
            LaunchedEffect(slot, visibleRefs) { vm.ensureProgrammes(visibleRefs, slot, slot + windowMs + state.slotMs) }
            LaunchedEffect(category) { listState.scrollToItem(0) }

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel(viewStart, now), modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                    if (viewStart != state.floorToSlot(now)) {
                        ActionButton(t("Now"), { state.backToNow(System.currentTimeMillis(), emptyList()); viewStart = state.viewStartMs })
                    }
                }
                GuideHeader(viewStart, windowMs, state.slotMs, stripWidth, "", channelWidth = LOGO_COLUMN)
                Box(
                    Modifier.fillMaxSize()
                        // Dragging sideways moves through time; up and down is the list's own scrolling.
                        .pointerInput(stripPx, windowMs) {
                            var pending = 0f
                            detectHorizontalDragGestures(onDragEnd = { pending = 0f }) { change, dx ->
                                change.consume()
                                // Redrawing every row on every drag frame is too slow on low-end phones:
                                // move in 2-minute steps instead (still smooth to the eye).
                                pending += dx
                                val ms = (-pending / stripPx * windowMs).toLong()
                                if (kotlin.math.abs(ms) >= 2 * 60_000L) {
                                    pending = 0f
                                    state.panBy(ms)
                                    viewStart = state.viewStartMs
                                }
                            }
                        },
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("phone_guide_rows")) {
                        items(count = items.itemCount, key = items.itemKey { it.id }, contentType = { "row" }) { i ->
                            val channel = items[i]
                            val rowList = channel?.epgRef?.let { vm.programmes[it] }.orEmpty()
                            val empty = remember(rowList, viewStart, windowMs) { state.emptyBlocks(rowList, viewStart, viewStart + windowMs) }
                            Box(
                                Modifier.fillMaxWidth().testTag("phone_guide_row_$i").pointerInput(channel?.id, viewStart, rowList) {
                                    detectTapGestures { pos ->
                                        val ch = channel ?: return@detectTapGestures
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                        if (pos.x < logoPx) { watch(ch); return@detectTapGestures }
                                        val at = viewStart + ((pos.x - logoPx) / stripPx * windowMs).toLong()
                                        details = ch to state.blockAt(rowList, at)
                                    }
                                },
                            ) {
                                GuideRow(
                                    channel = channel,
                                    programmes = rowList,
                                    emptyBlocks = empty,
                                    viewStartMs = viewStart,
                                    windowMs = windowMs,
                                    nowMs = now,
                                    stripWidth = stripWidth,
                                    isPlaying = channel != null && channel.stableKey == playing?.stableKey,
                                    showNumber = false,
                                    focused = null,
                                    highlight = settings.guideHighlight,
                                    channelWidth = LOGO_COLUMN,
                                    reminders = reminders,
                                )
                            }
                        }
                    }
                    NowLine(viewStart, windowMs, now, stripWidth, channelWidth = LOGO_COLUMN)
                    if (items.itemCount == 0) {
                        Text(t("No channels here yet"), modifier = Modifier.align(Alignment.Center), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
                    }
                }
            }
        }
        PhoneBottomBar(PhoneTab.GUIDE)
    }
    details?.let { (ch, block) ->
        ProgrammeSheet(ch, block, now, onWatch = {
            details = null
            // A programme that already aired plays from the archive when the channel has one.
            if (block.stopMs <= System.currentTimeMillis() && vm.playCatchup(ch, block)) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                nav.replaceAll(Screen.Player)
            } else {
                watch(ch)
            }
        }, onClose = { details = null }, reminded = block.programme?.let { app.tiviate.data.Reminders.key(ch.playlistId, ch.stableKey, it.startMs) in reminders },
            onRemind = { p ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch { app.tiviate.data.Reminders.toggle(context, graph.settings, ch, p.title, p.startMs) }
            },
            onRecord = { p ->
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                details = null
                scope.launch {
                    app.tiviate.data.record.Recordings.schedule(context, ch, p.title, maxOf(p.startMs, System.currentTimeMillis()), p.stopMs + app.tiviate.data.record.Recordings.LATE_MS)
                }
            })
    }
}

/** A programme's details over the bottom of the guide, with Watch. */
@Composable
private fun ProgrammeSheet(
    channel: ChannelEntity, block: FocusBlock, now: Long, onWatch: () -> Unit, onClose: () -> Unit,
    reminded: Boolean? = null, onRemind: (app.tiviate.data.db.ProgrammeEntity) -> Unit = {},
    onRecord: (app.tiviate.data.db.ProgrammeEntity) -> Unit = {},
) {
    app.tiviate.ui.components.BottomSheet(onClose, "phone_programme_scrim") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("phone_programme_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val p = block.programme
            Text(channel.name, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
            Text(p?.title ?: t("No programme information"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            Text("${formatTime(block.startMs)} – ${formatTime(block.stopMs)}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            p?.description?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(t("Watch"), onWatch, primary = true, tag = "phone_watch")
                // Still to come: a reminder shortly before it starts.
                if (p != null && p.startMs > System.currentTimeMillis() && reminded != null) {
                    ActionButton(if (reminded) t("Cancel reminder") else t("Remind me"), { onRemind(p) }, tag = "phone_remind")
                }
                if (p != null && p.stopMs > System.currentTimeMillis()) ActionButton(t("Record"), { onRecord(p) }, tag = "phone_record")
                ActionButton(t("Close"), onClose)
            }
        }
    }
}
