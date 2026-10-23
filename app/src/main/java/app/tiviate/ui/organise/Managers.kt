package app.tiviate.ui.organise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import app.tiviate.data.Category
import app.tiviate.data.ChannelSort
import app.tiviate.data.db.GroupRow
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.player.categoryLabel
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch
import app.tiviate.ui.t
import app.tiviate.ui.tf

@Composable
private fun ManagerFrame(title: String, hint: String, tag: String, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val nav = LocalNavigator.current
    BackHandler { nav.back() }
    Column(Modifier.fillMaxSize().background(Tokens.Background).padding(Tokens.SafeMargin).testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
        Text(hint, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        content()
    }
}

/** Show or hide channels of a group ("Manage visibility"), or put them in your own order ("Reorder channels"). */
@Composable
fun ManageChannelsScreen(playlistId: Long, category: Category, reorder: Boolean) {
    if (reorder) ReorderChannels(playlistId, category) else ChannelVisibility(playlistId, category)
}

@Composable
private fun ChannelVisibility(playlistId: Long, category: Category) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val items = remember(playlistId, category) { graph.channels.page(playlistId, category, includeHidden = true) }.collectAsLazyPagingItems()
    val first = remember { FocusRequester() }
    LaunchedEffect(items.itemCount > 0) { if (items.itemCount > 0) focusSoon(first) }
    ManagerFrame(
        tf("Manage visibility · %s", categoryLabel(category)),
        t("OK shows or hides a channel. Hidden channels disappear from the lists, the guide and zapping."),
        "manage_visibility",
        actions = {
            ActionButton(t("Show all"), {
                scope.launch { graph.channels.setHidden(playlistId, graph.channels.list(playlistId, category, includeHidden = true).map { it.stableKey }, false) }
            }, tag = "visibility_show_all")
            ActionButton(t("Hide all"), {
                scope.launch { graph.channels.setHidden(playlistId, graph.channels.list(playlistId, category, includeHidden = true).map { it.stableKey }, true) }
            }, tag = "visibility_hide_all")
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                val ch = items[i]
                FocusSurface(
                    onClick = { ch?.let { c -> scope.launch { graph.channels.setHidden(playlistId, listOf(c.stableKey), !c.hidden) } } },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().testTag("visibility_$i"),
                    container = Color.Transparent,
                    scale = 1.01f,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${ch?.number ?: ""}", modifier = Modifier.padding(end = 16.dp), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
                        Text(ch?.name.orEmpty(), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (ch?.hidden == true) Tokens.TextSecondary else Tokens.Text, fontSize = Tokens.BodySize))
                        Text(if (ch?.hidden == true) t("Hidden") else t("Shown"), style = TextStyle(color = if (ch?.hidden == true) Tokens.TextSecondary else Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderChannels(playlistId: Long, category: Category) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(playlistId, category) {
        list.clear()
        list.addAll(graph.channels.list(playlistId, category).map { it.stableKey to "${it.number}  ${it.name}" })
    }
    ManagerFrame(
        if (category == Category.Favorites) t("Manage Favorites") else tf("Reorder channels · %s", categoryLabel(category)),
        t("OK picks a channel; Up/Down move it; Left/Right send it to the top or bottom; OK again puts it down."),
        "reorder_channels",
    ) {
        ReorderList(list, onDrop = { ordered ->
            scope.launch {
                graph.channels.reorder(playlistId, category, ordered.map { it.first })
                if (category == Category.All || category is Category.Group) graph.settings.update { it.copy(channelSort = ChannelSort.MANUAL) }
            }
        })
    }
}

/** Provider groups: show or hide them, give them your own order. */
@Composable
fun ManageGroupsScreen(playlistId: Long) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val groups by graph.channels.allGroups(playlistId).collectAsState(initial = emptyList<GroupRow>())
    var reorder by remember { mutableStateOf(false) }
    val list = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(reorder) { if (reorder) { list.clear(); list.addAll(groups.map { it.name to (it.label ?: it.name) }) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(groups.isNotEmpty(), reorder) { if (groups.isNotEmpty() && !reorder) focusSoon(first) }
    ManagerFrame(
        t("Manage groups"),
        if (reorder) t("OK picks a group; Up/Down move it; Left/Right send it to the top or bottom; OK again puts it down.") else t("OK shows or hides a group."),
        "manage_groups",
        actions = {
            ActionButton(if (reorder) t("Show or hide groups") else t("Reorder groups"), { reorder = !reorder }, tag = "groups_mode")
        },
    ) {
        if (reorder) {
            ReorderList(list, onDrop = { ordered -> scope.launch { graph.channels.reorderGroups(playlistId, ordered.map { it.first }) } })
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(groups, key = { _, g -> g.name }) { i, g ->
                    FocusSurface(
                        onClick = { scope.launch { graph.channels.setGroupHidden(playlistId, g.name, !g.hidden) } },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().testTag("group_row_$i"),
                        container = Color.Transparent,
                        scale = 1.01f,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((g.label ?: g.name) + "  ·  ${g.count}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (g.hidden) Tokens.TextSecondary else Tokens.Text, fontSize = Tokens.BodySize))
                            Text(if (g.hidden) t("Hidden") else t("Shown"), style = TextStyle(color = if (g.hidden) Tokens.TextSecondary else Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A list you put in order with the remote: OK picks a row, Up/Down move it, Left/Right send it to
 * the top/bottom, OK again drops it and [onDrop] gets the new order. Items are (key, label).
 */
@Composable
fun ReorderList(items: SnapshotStateList<Pair<String, String>>, onDrop: (List<Pair<String, String>>) -> Unit, tag: String = "reorder_list") {
    var picked by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(items.isNotEmpty()) { if (items.isNotEmpty()) focusSoon(first) }
    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices) return
        val item = items.removeAt(from)
        items.add(to.coerceIn(0, items.size), item)
        scope.launch { listState.scrollToItem((to - 3).coerceAtLeast(0)) }
    }
    LazyColumn(state = listState, modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(items, key = { _, it -> it.first }) { i, (key, label) ->
            val isPicked = picked == key
            FocusSurface(
                onClick = {
                    if (isPicked) { picked = null; onDrop(items.toList()) } else picked = key
                },
                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .fillMaxWidth()
                    .testTag("reorder_$i")
                    .onPreviewKeyEvent { e ->
                        if (!isPicked || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val at = items.indexOfFirst { it.first == key }
                        when (e.key) {
                            Key.DirectionUp -> { move(at, at - 1); true }
                            Key.DirectionDown -> { move(at, at + 1); true }
                            Key.DirectionLeft -> { move(at, 0); true }
                            Key.DirectionRight -> { move(at, items.lastIndex); true }
                            else -> false
                        }
                    },
                container = if (isPicked) Tokens.Raised else Color.Transparent,
                scale = 1.01f,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                    if (isPicked) Text(t("Moving ↕"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** Names of the fullscreen option cards, for the "Option cards" setting. */
val OPTION_CARD_TITLES = mapOf(
    "option_channels" to "Channels", "option_guide" to "TV guide", "option_playpause" to "Pause / Back to live", "option_previous" to "Previous channel",
    "option_audio" to "Audio", "option_subtitles" to "Subtitles", "option_display" to "Display", "option_zoom" to "Zoom",
    "option_quality" to "Quality", "option_audio_sync" to "Audio sync", "option_stats" to "Stats", "option_sleep" to "Sleep timer",
    "option_favorite" to "Favorite", "option_channel" to "Channel options", "option_pip" to "Picture-in-picture",
    "option_external" to "Other player", "option_search" to "Search", "option_settings" to "Settings",
)

/** Which option cards show in the fullscreen options, and in what order. */
@Composable
fun OptionCardsScreen() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState(initial = app.tiviate.data.AppSettings())
    var reorder by remember { mutableStateOf(false) }
    val list = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(reorder) { if (reorder) { list.clear(); list.addAll(settings.optionCards.map { it to t(OPTION_CARD_TITLES[it] ?: it) }) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(reorder) { if (!reorder) focusSoon(first) }
    ManagerFrame(
        t("Option cards"),
        if (reorder) t("OK picks a card; Up/Down move it; Left/Right send it to the start or end; OK again puts it down.") else t("OK shows or hides a card in the fullscreen options (Down / Menu)."),
        "option_cards",
        actions = { ActionButton(if (reorder) t("Show or hide cards") else t("Change the order"), { reorder = !reorder }, tag = "cards_mode") },
    ) {
        if (reorder) {
            ReorderList(list, onDrop = { ordered -> scope.launch { graph.settings.update { it.copy(optionCards = ordered.map { p -> p.first }) } } })
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(settings.optionCards, key = { _, t -> t }) { i, tag ->
                    val hidden = tag in settings.hiddenOptionCards
                    FocusSurface(
                        onClick = { scope.launch { graph.settings.update { it.copy(hiddenOptionCards = if (hidden) it.hiddenOptionCards - tag else it.hiddenOptionCards + tag) } } },
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().testTag("card_row_$tag"),
                        container = Color.Transparent,
                        scale = 1.01f,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t(OPTION_CARD_TITLES[tag] ?: tag), modifier = Modifier.weight(1f), style = TextStyle(color = if (hidden) Tokens.TextSecondary else Tokens.Text, fontSize = Tokens.BodySize))
                            Text(if (hidden) t("Hidden") else t("Shown"), style = TextStyle(color = if (hidden) Tokens.TextSecondary else Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}
