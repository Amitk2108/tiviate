package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import app.tiviate.data.Category
import app.tiviate.data.NowNext
import app.tiviate.data.db.CategoryRow
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.ui.components.ChannelLogo
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.components.progressOf
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.components.RemoteImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce

sealed interface RailEntry {
    data object Search : RailEntry
    data class Pick(val category: Category, val label: String, val count: Int?) : RailEntry
    data class SwitchPlaylist(val playlist: PlaylistEntity) : RailEntry
    data object Settings : RailEntry
}

fun railEntries(groups: List<CategoryRow>, playlists: List<PlaylistEntity>, activeId: Long?): List<RailEntry> = buildList {
    add(RailEntry.Search)
    add(RailEntry.Pick(Category.All, "All channels", groups.sumOf { it.count }.takeIf { it > 0 }))
    add(RailEntry.Pick(Category.Favorites, "Favorites", null))
    add(RailEntry.Pick(Category.Recent, "Recent", null))
    groups.forEach { g -> g.name?.let { add(RailEntry.Pick(Category.Group(it), it, g.count)) } }
    playlists.filter { it.id != activeId }.forEach { add(RailEntry.SwitchPlaylist(it)) }
    add(RailEntry.Settings)
}

fun categoryLabel(c: Category) = when (c) {
    Category.All -> "All channels"
    Category.Favorites -> "Favorites"
    Category.Recent -> "Recent"
    is Category.Group -> c.name
    is Category.Custom -> c.name
}

/**
 * Channel list over the left of the video, with an optional categories rail and a details card
 * for the focused channel (programme image, times, description and what's next).
 */
@OptIn(FlowPreview::class)
@Composable
fun ChannelPanel(
    channels: LazyPagingItems<ChannelEntity>,
    category: Category,
    current: ChannelEntity?,
    categoriesFocused: Boolean,
    railEntries: List<RailEntry>,
    favorites: Set<String>,
    nowNext: Map<String, NowNext>,
    showNumbers: Boolean,
    showProgramme: Boolean,
    showDescription: Boolean,
    initialIndex: Int,
    requestNowNext: (Collection<String>) -> Unit,
    onTune: (ChannelEntity) -> Unit,
    onRail: (RailEntry) -> Unit,
    onOpenRail: () -> Unit,
    onCloseRail: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialIndex - 3).coerceAtLeast(0))
    val rowFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf<ChannelEntity?>(current) }
    val now = rememberNow()
    val focusManager = LocalFocusManager.current
    fun pageRows() = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)

    LaunchedEffect(listState, channels) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .debounce(120)
            // The list may have shrunk (category switch) since these indices were measured.
            .collect { indices -> requestNowNext(indices.filter { it < channels.itemCount }.mapNotNull { i -> channels.peek(i)?.epgRef }) }
    }
    // Deep in a long category the target row is a placeholder at first: focus it once it has loaded.
    val targetLoaded by remember(channels, initialIndex) {
        derivedStateOf { initialIndex < channels.itemCount && channels.peek(initialIndex) != null }
    }
    LaunchedEffect(categoriesFocused, channels.itemCount, targetLoaded) {
        if (!categoriesFocused && channels.itemCount > 0) {
            repeat(10) {
                if (runCatching { rowFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(16)
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (categoriesFocused) CategoriesRail(railEntries, category, onRail, onCloseRail)
        Column(
            Modifier
                .width(470.dp)
                .fillMaxHeight()
                .background(Tokens.Scrim)
                .padding(top = 28.dp, start = 20.dp, end = 12.dp)
                .testTag("channel_panel"),
        ) {
            Text(categoryLabel(category), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            if (channels.itemCount == 0) {
                Text("No channels here yet", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize), modifier = Modifier.padding(8.dp))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { onOpenRail(); true }
                            // A page at a time: CH±, Pg± and RW/FF move the focus by one screen of rows.
                            Key.PageDown, Key.ChannelDown, Key.MediaFastForward -> { repeat(pageRows()) { focusManager.moveFocus(FocusDirection.Down) }; true }
                            Key.PageUp, Key.ChannelUp, Key.MediaRewind -> { repeat(pageRows()) { focusManager.moveFocus(FocusDirection.Up) }; true }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
            ) {
                items(count = channels.itemCount, key = channels.itemKey { it.id }, contentType = { "channel" }) { index ->
                    val channel = channels[index]
                    val isTarget = if (current != null) channel?.stableKey == current.stableKey else index == 0
                    ChannelRow(
                        channel = channel,
                        isPlaying = channel != null && channel.stableKey == current?.stableKey,
                        isFavorite = channel != null && channel.stableKey in favorites,
                        info = if (showProgramme) channel?.epgRef?.let(nowNext::get) else null,
                        now = now,
                        showNumber = showNumbers,
                        modifier = (if (isTarget || (index == initialIndex && current == null)) Modifier.focusRequester(rowFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused && channel != null) focused = channel },
                        onClick = { channel?.let(onTune) },
                    )
                }
            }
        }
        if (!categoriesFocused) focused?.let { DetailsCard(it, it.epgRef?.let(nowNext::get), now, showDescription, Modifier.weight(1f)) }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity?,
    isPlaying: Boolean,
    isFavorite: Boolean,
    info: NowNext?,
    now: Long,
    showNumber: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp), container = androidx.compose.ui.graphics.Color.Transparent, scale = 1.02f) {
        if (channel == null) return@FocusSurface
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showNumber) {
                Text("${channel.number}", modifier = Modifier.width(40.dp), style = TextStyle(color = if (isPlaying) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
            ChannelLogo(channel, 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (isFavorite) "★ " else "") + channel.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = if (isPlaying) Tokens.Cyan else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium),
                )
                val p = info?.now
                if (p != null) {
                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    ProgressLine(progressOf(p.startMs, p.stopMs, now), height = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(channel: ChannelEntity, info: NowNext?, now: Long, showDescription: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxHeight().padding(Tokens.SafeMargin), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier.width(440.dp).background(Tokens.Scrim, Tokens.Shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val p = info?.now
            p?.iconUrl?.let { url ->
                RemoteImage(url, modifier = Modifier.fillMaxWidth().height(200.dp).background(Tokens.Raised, Tokens.SmallShape))
            }
            Text(channel.name, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            if (p != null) {
                Text(p.title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(p.startMs)} – ${formatTime(p.stopMs)}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                ProgressLine(progressOf(p.startMs, p.stopMs, now))
                if (showDescription) p.description?.let { Text(it, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize), maxLines = 4, overflow = TextOverflow.Ellipsis) }
                info.next?.let { Text("Next  ${formatTime(it.startMs)}  ${it.title}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else {
                Text("No programme information", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            }
        }
    }
}

@Composable
private fun CategoriesRail(entries: List<RailEntry>, selected: Category, onRail: (RailEntry) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val selectedIndex = entries.indexOfFirst { it is RailEntry.Pick && it.category == selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0))
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    LazyColumn(
        state = state,
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(Tokens.Background.copy(alpha = 0.96f))
            .padding(top = 28.dp, start = 16.dp, end = 12.dp)
            .testTag("categories_rail")
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onClose(); true } else false },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(entries, key = { i, e -> "$i-${e.hashCode()}" }) { i, entry ->
            val (label, count, highlight) = when (entry) {
                RailEntry.Search -> Triple("🔍  Search", null, false)
                RailEntry.Settings -> Triple("⚙  Settings", null, false)
                is RailEntry.SwitchPlaylist -> Triple("⇄  ${entry.playlist.name}", null, false)
                is RailEntry.Pick -> Triple(entry.label, entry.count, entry.category == selected)
            }
            FocusSurface(
                onClick = { onRail(entry) },
                modifier = (if (i == selectedIndex) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                container = androidx.compose.ui.graphics.Color.Transparent,
                scale = 1.02f,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (highlight) Tokens.Cyan else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal))
                    count?.let { Text("$it", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize)) }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
