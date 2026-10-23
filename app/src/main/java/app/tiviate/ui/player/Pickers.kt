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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.player.PlaybackEngine
import app.tiviate.player.PlaybackStats
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay

data class Choice(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** An on/off row under the choices, e.g. "Use this language for all channels". */
data class ToggleChoice(val label: String, val checked: Boolean, val onToggle: (Boolean) -> Unit)

/** A list of choices with the current one ticked, and an optional "for all channels" switch. */
@Composable
fun ChoiceDialog(title: String, choices: List<Choice>, toggle: ToggleChoice? = null, empty: String? = null, tag: String = "choice_dialog") {
    val first = remember { FocusRequester() }
    val start = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) { focusSoon(first) }
    Box(Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag(tag), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(Tokens.Surface, Tokens.Shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            if (choices.isEmpty() && empty != null) Text(empty, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(choices, key = { i, c -> "$i-${c.label}" }) { i, c ->
                    ChoiceRow(c.label, c.selected, (if (i == start) Modifier.focusRequester(first) else Modifier).testTag("choice_${c.label}"), c.onClick)
                }
            }
            toggle?.let { t ->
                var checked by remember(t.checked) { mutableStateOf(t.checked) }
                FocusSurface(
                    onClick = { checked = !checked; t.onToggle(checked) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("picker_toggle"),
                    container = Tokens.Raised,
                    scale = 1.02f,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                        Text(if (checked) "On" else "Off", style = TextStyle(color = if (checked) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), scale = 1.02f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
            if (selected) Text("✓", style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Bold))
        }
    }
}

/** Live numbers about the stream, top left; refreshed once a second while shown. */
@Composable
fun StatsOverlay(engine: PlaybackEngine) {
    var stats by remember { mutableStateOf(engine.stats()) }
    LaunchedEffect(engine) {
        while (true) {
            delay(1_000)
            stats = engine.stats()
        }
    }
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin), contentAlignment = Alignment.TopStart) {
        Column(Modifier.background(Tokens.Scrim, Tokens.Shape).padding(16.dp).testTag("stats_overlay"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            statsLines(stats).forEach { (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, modifier = Modifier.width(120.dp), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    Text(v, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

fun statsLines(s: PlaybackStats): List<Pair<String, String>> = buildList {
    add("Video" to listOfNotNull(
        s.videoCodec,
        "${s.width} × ${s.height}".takeIf { s.width > 0 },
        s.frameRate?.let { "%.2f fps".format(it) },
        s.videoBitrate?.let { "%.1f Mbps".format(it / 1_000_000f) },
    ).joinToString(" · ").ifEmpty { "—" })
    add("Audio" to listOfNotNull(
        s.audioCodec,
        when (s.audioChannels) { 0 -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${s.audioChannels} ch" },
        s.sampleRate.takeIf { it > 0 }?.let { "${it / 1000} kHz" },
    ).joinToString(" · ").ifEmpty { "—" })
    add("Buffer" to "%.1f s".format(s.bufferedMs / 1000f))
    add("Dropped" to "${s.droppedFrames} frames")
    add("Connection" to if (s.bandwidthKbps > 0) "%.1f Mbps".format(s.bandwidthKbps / 1000f) else "—")
}
