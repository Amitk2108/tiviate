package app.tiviate.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.tv.material3.Text
import app.tiviate.data.Category
import app.tiviate.data.db.CategoryRow
import app.tiviate.data.db.CustomGroupEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.t
import app.tiviate.ui.tf

/** Where focus is on the guide screen: the grid, the groups column (Left), or the side menu (Left again). */
enum class SideNav { GRID, GROUPS, RAIL }

sealed interface GroupEntry {
    data class Pick(val category: Category, val label: String, val count: Int?) : GroupEntry
    /** Another playlist: OK switches to it. */
    data class SwitchPlaylist(val playlist: PlaylistEntity) : GroupEntry
}

/** Favorites, All channels, Recent, your own groups, the provider's groups, then the other playlists (if any). */
fun groupEntries(
    groups: List<CategoryRow>,
    playlists: List<PlaylistEntity>,
    activeId: Long?,
    custom: List<CustomGroupEntity> = emptyList(),
    showAll: Boolean = true,
    showFavorites: Boolean = true,
    showRecent: Boolean = true,
): List<GroupEntry> = buildList {
    if (showFavorites) add(GroupEntry.Pick(Category.Favorites, t("Favorites"), null))
    if (showAll) add(GroupEntry.Pick(Category.All, t("All channels"), groups.sumOf { it.count }.takeIf { it > 0 }))
    if (showRecent) add(GroupEntry.Pick(Category.Recent, t("Recent"), null))
    custom.forEach { add(GroupEntry.Pick(Category.Custom(it.id, it.name), it.name, null)) }
    groups.forEach { g -> g.name?.let { add(GroupEntry.Pick(Category.Group(it), g.shownName ?: it, g.count)) } }
    playlists.filter { it.id != activeId }.forEach { add(GroupEntry.SwitchPlaylist(it)) }
}

private fun Modifier.leftRight(onLeft: () -> Unit, onRight: () -> Unit) = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.DirectionLeft -> { onLeft(); true }
        Key.DirectionRight -> { onRight(); true }
        else -> false
    }
}

@Composable
fun GroupsColumn(
    playlistName: String?,
    entries: List<GroupEntry>,
    selected: Category,
    onPick: (Category) -> Unit,
    onSwitch: (PlaylistEntity) -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val selectedIndex = entries.indexOfFirst { it is GroupEntry.Pick && it.category == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 4).coerceAtLeast(0))
    LaunchedEffect(Unit) { focusSoon(first) }
    Column(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(Tokens.Surface)
            .padding(top = 28.dp, start = 16.dp, end = 12.dp)
            .leftRight(onLeft, onRight)
            .testTag("guide_groups"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        playlistName?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries.size) { i ->
                val e = entries[i]
                val (label, isSelected) = when (e) {
                    is GroupEntry.Pick -> (e.label + (e.count?.let { "  $it" } ?: "")) to (e.category == selected)
                    is GroupEntry.SwitchPlaylist -> tf("Playlist: %s", e.playlist.name) to false
                }
                FocusSurface(
                    onClick = { when (e) { is GroupEntry.Pick -> onPick(e.category); is GroupEntry.SwitchPlaylist -> onSwitch(e.playlist) } },
                    modifier = (if (i == selectedIndex) Modifier.focusRequester(first) else Modifier)
                        .fillMaxWidth()
                        .testTag("group_" + (if (e is GroupEntry.Pick) e.label else "playlist_${(e as GroupEntry.SwitchPlaylist).playlist.id}")),
                    container = Color.Transparent,
                    scale = 1.02f,
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (isSelected) Tokens.Cyan else Tokens.Text,
                            fontSize = Tokens.BodySize,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

/** The side menu: Search, TV (this screen) and Settings at the bottom. */
@Composable
fun NavRail(onSearch: () -> Unit, onTv: () -> Unit, onSettings: () -> Unit) {
    val search = remember { FocusRequester() }
    val tv = remember { FocusRequester() }
    val settings = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(tv) }
    Column(
        Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(Tokens.Background)
            .padding(vertical = 28.dp, horizontal = 16.dp)
            .leftRight(onLeft = {}, onRight = onTv)
            .testTag("guide_rail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t("Tiviate"), modifier = Modifier.padding(start = 12.dp, bottom = 24.dp), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
        // Up/Down stay inside the menu (the groups column beside it would otherwise be "closer").
        RailRow(t("Search"), "rail_search", onSearch, Modifier.focusRequester(search).focusProperties { up = FocusRequester.Cancel; down = tv })
        RailRow("TV", "rail_tv", onTv, Modifier.focusRequester(tv).focusProperties { up = search; down = settings }, selected = true)
        Spacer(Modifier.weight(1f))
        RailRow(t("Settings"), "rail_settings", onSettings, Modifier.focusRequester(settings).focusProperties { up = tv; down = FocusRequester.Cancel })
    }
}

@Composable
private fun RailRow(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().testTag(tag), container = if (selected) Tokens.Raised else Color.Transparent, scale = 1.02f) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
    }
}
