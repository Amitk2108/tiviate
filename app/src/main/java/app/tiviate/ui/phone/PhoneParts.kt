package app.tiviate.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.tiviate.data.Category
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.guide.GroupEntry
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens

enum class PhoneTab { LIVE, GUIDE, LIBRARY }

/** What the Live screen shows below the video (GitHub issue #5: watching while browsing). */
enum class PhoneView { CHANNELS, PROGRAMMES, GUIDE }

object PhoneHomeView {
    var current by mutableStateOf(PhoneView.CHANNELS)
}

/** Live, Guide, Search and Settings, at the bottom of the phone screens. [onHome]: on the Live screen, Live and TV guide only switch what is below the video. */
@Composable
fun PhoneBottomBar(selected: PhoneTab, onHome: Boolean = false) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().background(Tokens.Surface).padding(horizontal = 8.dp, vertical = 6.dp).testTag("phone_bottom_bar"),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        BarItem(Icons.Rounded.LiveTv, t("Live"), selected == PhoneTab.LIVE, "tab_live") {
            PhoneHomeView.current = PhoneView.CHANNELS
            if (!onHome) nav.replaceAll(Screen.Player)
        }
        // The guide opens below the video, which keeps playing.
        BarItem(Icons.Rounded.CalendarMonth, t("TV guide"), selected == PhoneTab.GUIDE, "tab_guide") {
            PhoneHomeView.current = PhoneView.GUIDE
            if (!onHome) nav.replaceAll(Screen.Player)
        }
        // Movies & Series: Xtream accounts that have them.
        val graph = app.tiviate.ui.LocalGraph.current
        val pid by graph.settings.screenSettings.map { it.activePlaylistId }.collectAsState(initial = null)
        val library by remember(pid) { pid?.let { graph.library.count(it) } ?: kotlinx.coroutines.flow.flowOf(0) }.collectAsState(initial = 0)
        if (library > 0 || selected == PhoneTab.LIBRARY) {
            BarItem(Icons.Rounded.Movie, t("Movies"), selected == PhoneTab.LIBRARY, "tab_library") {
                if (selected != PhoneTab.LIBRARY) { nav.replaceAll(Screen.Player); nav.push(Screen.Library) }
            }
        }
        BarItem(Icons.Rounded.Search, t("Search"), false, "tab_search") { nav.push(Screen.Search) }
        BarItem(Icons.Rounded.Settings, t("Settings"), false, "tab_settings") { nav.push(Screen.Settings) }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.testTag(tag), container = Color.Transparent, shape = Tokens.SmallShape, scale = 1f) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (selected) Tokens.Cyan else Tokens.TextSecondary, modifier = Modifier.size(24.dp))
            Text(label, maxLines = 1, style = TextStyle(color = if (selected) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** The groups as a row of chips to swipe through; the selected one is filled. */
@Composable
fun GroupChips(entries: List<GroupEntry>, selected: Category, onPick: (Category) -> Unit, onSwitch: (PlaylistEntity) -> Unit) {
    val state = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it is GroupEntry.Pick && it.category == selected }
    LaunchedEffect(selectedIndex) { if (selectedIndex > 0) state.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().background(Tokens.Background).testTag("phone_group_chips"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(entries) { i, e ->
            val isSelected = i == selectedIndex
            val label = when (e) {
                is GroupEntry.Pick -> e.label + (e.count?.let { "  $it" } ?: "")
                is GroupEntry.SwitchPlaylist -> tf("Playlist: %s", e.playlist.name)
            }
            Pill(label, isSelected) { when (e) { is GroupEntry.Pick -> onPick(e.category); is GroupEntry.SwitchPlaylist -> onSwitch(e.playlist) } }
        }
    }
}

/** A chip; the selected one is filled. */
@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(48.dp).then(if (selected) Modifier else Modifier.border(1.dp, Tokens.Raised, Tokens.PillShape)), // 48 dp: the touch-target minimum
        container = if (selected) Tokens.Cyan else Color.Transparent,
        shape = Tokens.PillShape,
        scale = 1f,
    ) {
        Text(
            label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            style = TextStyle(color = if (selected) Tokens.Background else Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Channels · This channel · TV guide: what the Live screen shows below the video. */
@Composable
fun ViewChips(selected: PhoneView) {
    Row(Modifier.fillMaxWidth().background(Tokens.Background).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((view, label, tag) in listOf(
            Triple(PhoneView.CHANNELS, t("Channels"), "phone_view_channels"),
            Triple(PhoneView.PROGRAMMES, t("This channel"), "phone_view_programmes"),
            Triple(PhoneView.GUIDE, t("TV guide"), "phone_view_guide"),
        )) {
            Pill(label, view == selected, Modifier.testTag(tag)) { PhoneHomeView.current = view }
        }
    }
}
