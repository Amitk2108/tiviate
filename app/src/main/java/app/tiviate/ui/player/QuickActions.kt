package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.player.TrackOption
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay

/** Audio or subtitle choices. [allowOff] adds an "Off" entry (subtitles). */
@Composable
fun TrackDialog(title: String, options: List<TrackOption>, allowOff: Boolean, onSelect: (TrackOption?) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    val anySelected = options.any { it.selected }
    Box(Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag("track_dialog"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp).background(Tokens.Surface, Tokens.Shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            if (options.isEmpty() && !allowOff) Text("This channel has only one track.", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (allowOff) item(key = "off") { TrackRow("Off", selected = !anySelected, Modifier.focusRequester(first)) { onSelect(null) } }
                items(options, key = { "${it.groupIndex}-${it.trackIndex}" }) { o ->
                    val focus = if (!allowOff && o == options.first()) Modifier.focusRequester(first) else Modifier
                    TrackRow(o.label, o.selected, focus) { onSelect(o) }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), scale = 1.02f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
            if (selected) Text("✓", style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Bold))
        }
    }
}

/** Requests focus once the target is attached (it may take a frame after composition). */
suspend fun focusSoon(requester: FocusRequester) {
    repeat(10) {
        if (runCatching { requester.requestFocus() }.isSuccess) return
        delay(16)
    }
}
