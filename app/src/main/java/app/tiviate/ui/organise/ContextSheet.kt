package app.tiviate.ui.organise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.AppSettings
import app.tiviate.data.Category
import app.tiviate.data.ChannelSort
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.player.categoryLabel
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch

/** What the long-OK menu is about: a channel (and its programme) seen inside [category]. */
data class SheetTarget(
    val playlistId: Long,
    val channel: ChannelEntity?,
    val category: Category,
    val programmeTitle: String? = null,
)

private sealed interface SheetDialog {
    data object RenameChannel : SheetDialog
    data object AssignGuide : SheetDialog
    data object AddToGroup : SheetDialog
    data object RenameGroup : SheetDialog
    data object CreateGroup : SheetDialog
}

/**
 * The long-OK menu (also Menu in the guide): what you can do with this programme, this channel
 * and the group you are in — favourites, hiding, renaming, guide link, own groups and managers.
 */
@Composable
fun ChannelSheet(target: SheetTarget, onClose: () -> Unit, onDescription: (() -> Unit)? = null, onMessage: (String) -> Unit = {}) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState(initial = AppSettings())
    val favorites by graph.channels.favoriteKeys(target.playlistId).collectAsState(initial = emptySet())
    var dialog by remember { mutableStateOf<SheetDialog?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(dialog) { if (dialog == null) focusSoon(first) }
    val ch = target.channel
    val cat = target.category

    when (dialog) {
        SheetDialog.RenameChannel -> if (ch != null) {
            TextInputDialog("Rename channel", "Channel name", ch.name, onSave = { name ->
                scope.launch { graph.channels.renameChannel(ch, name); onMessage("Channel renamed"); onClose() }
            }, onCancel = { dialog = null }, tag = "rename_channel_dialog")
            return
        }
        SheetDialog.AssignGuide -> if (ch != null) {
            AssignGuideDialog(ch) { onMessage("Guide updated"); onClose() }
            return
        }
        SheetDialog.AddToGroup -> if (ch != null) {
            AddToGroupDialog(target.playlistId, listOf(ch.stableKey)) { name -> name?.let { onMessage("Added to $it") }; onClose() }
            return
        }
        SheetDialog.RenameGroup -> {
            val current = when (cat) { is Category.Group -> cat.name; is Category.Custom -> cat.name; else -> "" }
            TextInputDialog("Rename group", "Group name", current, onSave = { name ->
                scope.launch {
                    when (cat) {
                        is Category.Group -> graph.channels.renameGroup(target.playlistId, cat.name, name)
                        is Category.Custom -> graph.channels.renameCustomGroup(cat.id, name)
                        else -> Unit
                    }
                    onClose()
                }
            }, onCancel = { dialog = null }, tag = "rename_group_dialog")
            return
        }
        SheetDialog.CreateGroup -> {
            TextInputDialog("New group", "Group name", "", onSave = { name ->
                scope.launch {
                    graph.channels.createCustomGroup(target.playlistId, name)?.let { id ->
                        ch?.let { graph.channels.addToCustomGroup(id, listOf(it.stableKey)) }
                    }
                    onMessage("Group created")
                    onClose()
                }
            }, onCancel = { dialog = null }, tag = "new_group_dialog")
            return
        }
        null -> Unit
    }

    val rows = buildList<SheetItem> {
        add(SheetItem.Row("sheet_search", "Search") { onClose(); nav.push(Screen.Search) })
        add(SheetItem.Row("sheet_settings", "Settings") { onClose(); nav.push(Screen.Settings) })
        if (target.programmeTitle != null && onDescription != null) {
            add(SheetItem.Header(target.programmeTitle))
            add(SheetItem.Row("sheet_description", "Programme description") { onClose(); onDescription() })
        }
        if (ch != null) {
            add(SheetItem.Header(ch.name))
            val fav = ch.stableKey in favorites
            add(SheetItem.Row("sheet_favorite", if (fav) "Remove from Favorites" else "Add to Favorites") {
                scope.launch { val added = graph.channels.toggleFavorite(target.playlistId, ch.stableKey); onMessage(if (added) "Added to Favorites" else "Removed from Favorites"); onClose() }
            })
            add(SheetItem.Row("sheet_hide", "Hide channel") {
                scope.launch { graph.channels.setHidden(target.playlistId, listOf(ch.stableKey), true); onMessage("Hidden — show it again in Manage visibility"); onClose() }
            })
            add(SheetItem.Row("sheet_rename", "Rename channel") { dialog = SheetDialog.RenameChannel })
            if (ch.renamed) add(SheetItem.Row("sheet_restore_name", "Restore channel name") { scope.launch { graph.channels.restoreChannelName(ch); onClose() } })
            add(SheetItem.Row("sheet_assign_guide", "Assign guide") { dialog = SheetDialog.AssignGuide })
            add(SheetItem.Row("sheet_add_to_group", "Add to group") { dialog = SheetDialog.AddToGroup })
            if (cat is Category.Custom) {
                add(SheetItem.Row("sheet_remove_from_group", "Remove from ${cat.name}") {
                    scope.launch { graph.channels.removeFromCustomGroup(cat.id, listOf(ch.stableKey)); onClose() }
                })
            }
        }
        add(SheetItem.Header(categoryLabel(cat)))
        if (cat == Category.Favorites) add(SheetItem.Row("sheet_manage_favorites", "Manage Favorites") { onClose(); nav.push(Screen.ManageChannels(target.playlistId, cat, reorder = true)) })
        add(SheetItem.Row("sheet_visibility", "Manage visibility") { onClose(); nav.push(Screen.ManageChannels(target.playlistId, cat, reorder = false)) })
        if (cat != Category.Recent && cat != Category.Favorites) {
            add(SheetItem.Row("sheet_reorder", "Reorder channels") { onClose(); nav.push(Screen.ManageChannels(target.playlistId, cat, reorder = true)) })
        }
        if (cat == Category.All || cat is Category.Group) {
            add(SheetItem.Row("sheet_sort", "Sort channels: ${settings.channelSort.label}") {
                scope.launch { graph.settings.update { it.copy(channelSort = ChannelSort.entries[(it.channelSort.ordinal + 1) % ChannelSort.entries.size]) } }
            })
        }
        if (cat is Category.Group || cat is Category.Custom) add(SheetItem.Row("sheet_rename_group", "Rename group") { dialog = SheetDialog.RenameGroup })
        if (cat is Category.Group) {
            add(SheetItem.Row("sheet_hide_group", "Hide group") {
                scope.launch { graph.channels.setGroupHidden(target.playlistId, cat.name, true); onMessage("Group hidden — show it again in Manage groups"); onClose() }
            })
        }
        if (cat is Category.Custom) {
            add(SheetItem.Row("sheet_delete_group", "Delete group") { scope.launch { graph.channels.deleteCustomGroup(cat.id); onMessage("Group deleted"); onClose() } })
        }
        add(SheetItem.Row("sheet_create_group", "Create group") { dialog = SheetDialog.CreateGroup })
        add(SheetItem.Row("sheet_manage_groups", "Manage groups") { onClose(); nav.push(Screen.ManageGroups(target.playlistId)) })
    }

    Box(Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag("channel_sheet"), contentAlignment = Alignment.CenterEnd) {
        LazyColumn(
            Modifier.width(520.dp).fillMaxHeight().background(Tokens.Surface).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val firstRow = rows.indexOfFirst { it is SheetItem.Row && (ch == null || it.tag == "sheet_favorite") }.coerceAtLeast(0)
            items(rows.size) { i ->
                when (val item = rows[i]) {
                    is SheetItem.Header -> Text(
                        item.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
                        style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold),
                    )
                    is SheetItem.Row -> FocusSurface(
                        onClick = item.onClick,
                        modifier = (if (i == firstRow) Modifier.focusRequester(first) else Modifier).fillMaxWidth().testTag(item.tag),
                        container = Color.Transparent,
                        scale = 1.02f,
                    ) {
                        Text(item.label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                    }
                }
            }
        }
    }
}

private sealed interface SheetItem {
    data class Header(val title: String) : SheetItem
    data class Row(val tag: String, val label: String, val onClick: () -> Unit) : SheetItem
}
