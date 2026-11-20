package app.tiviate.ui.library

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.tiviate.data.VodPlay
import app.tiviate.data.db.RecordingEntity
import app.tiviate.data.db.RecordingStatus
import app.tiviate.data.record.Recordings
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.player.clock
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import java.io.File
import kotlinx.coroutines.launch

/** Recordings: the ones to come, the one running, and the finished ones to watch or delete. */
@Composable
fun RecordingsScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val recordings by remember { graph.db.recordings().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val first = remember { FocusRequester() }
    LaunchedEffect(recordings.isNotEmpty()) { focusSoon(first) }

    fun play(r: RecordingEntity) {
        val path = r.path ?: return
        nav.push(Screen.VodPlayer(VodPlay(r.playlistId, "recording:${r.id}", "${r.title} · ${r.channelName}", null, File(path).toURI().toString())))
    }

    Column(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("recordings_screen"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(t("Recordings"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
        if (recordings.isEmpty()) {
            Text(
                t("Nothing recorded yet. In the TV guide, long-press OK on a programme and pick Record; while watching, open the options and pick Record."),
                style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize),
            )
            ActionButton(t("Back"), { nav.back() }, Modifier.focusRequester(first))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(recordings, key = { _, r -> r.id }) { i, r ->
                Column(Modifier.fillMaxWidth().testTag("recording_$i"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                    val time = android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(r.startMs)) + " " +
                        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(r.startMs))
                    val size = if (r.bytes > 0) "  ·  " + android.text.format.Formatter.formatShortFileSize(context, r.bytes) else ""
                    Text("${r.channelName}  ·  $time  ·  ${clock(r.stopMs - r.startMs)}$size", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    Text(statusLabel(r), style = TextStyle(color = if (r.status == RecordingStatus.FAILED) Tokens.Danger else Tokens.Cyan, fontSize = Tokens.CaptionSize))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val firstModifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                        when (r.status) {
                            RecordingStatus.DONE -> ActionButton(t("Play"), { play(r) }, firstModifier, primary = true, tag = "recording_play_$i")
                            RecordingStatus.RECORDING -> ActionButton(t("Stop recording"), { graph.appScope.launch { Recordings.stop(context, r.id) } }, firstModifier, tag = "recording_stop_$i")
                            RecordingStatus.SCHEDULED -> ActionButton(t("Cancel"), { graph.appScope.launch { Recordings.stop(context, r.id) } }, firstModifier, tag = "recording_cancel_$i")
                        }
                        if (r.status != RecordingStatus.RECORDING) {
                            ActionButton(t("Delete"), { graph.appScope.launch { Recordings.delete(context, r.id) } }, if (r.status == RecordingStatus.DONE || r.status == RecordingStatus.SCHEDULED) Modifier else firstModifier, tag = "recording_delete_$i", danger = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(r: RecordingEntity): String = when (r.status) {
    RecordingStatus.SCHEDULED -> t("Scheduled")
    RecordingStatus.RECORDING -> t("● Recording")
    RecordingStatus.DONE -> t("Recorded")
    RecordingStatus.CANCELLED -> t("Cancelled")
    else -> r.error?.let { tf("Failed: %s", it) } ?: t("Failed")
}
