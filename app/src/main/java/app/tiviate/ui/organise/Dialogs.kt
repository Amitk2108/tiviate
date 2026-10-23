package app.tiviate.ui.organise

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.GuideChoice
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.CustomGroupEntity
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.TvTextField
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DialogFrame(title: String, tag: String, width: Int = 620, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag(tag), contentAlignment = Alignment.Center) {
        Column(Modifier.width(width.dp).background(Tokens.Surface, Tokens.Shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            content()
        }
    }
}

/** A name to type (rename a channel or group, create a group). */
@Composable
fun TextInputDialog(title: String, label: String, initial: String, onSave: (String) -> Unit, onCancel: () -> Unit, tag: String = "text_dialog") {
    var value by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    DialogFrame(title, tag) {
        TvTextField(label, value, { value = it }, Modifier.focusRequester(first), tag = "${tag}_field")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Save", { onSave(value) }, primary = true, tag = "${tag}_save")
            ActionButton("Cancel", onCancel)
        }
    }
}

/** Pick the guide channel for [channel] by hand, or go back to automatic matching. */
@Composable
fun AssignGuideDialog(channel: ChannelEntity, onDone: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(channel.name) }
    var choices by remember { mutableStateOf<List<GuideChoice>>(emptyList()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(query) {
        delay(250)
        choices = graph.channels.guideChannels(channel.playlistId, query)
    }
    LaunchedEffect(Unit) { focusSoon(first) }
    DialogFrame("Guide for ${channel.name}", "assign_guide_dialog", width = 760) {
        TvTextField("Search the guide", query, { query = it }, Modifier.focusRequester(first), tag = "assign_guide_search")
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "auto") {
                Row("Automatic (match by name)", channel.epgOverride == null) {
                    scope.launch { graph.channels.assignGuide(channel, null); onDone() }
                }
            }
            items(choices, key = { it.ref }) { c ->
                Row("${c.name}  ·  ${c.xmltvId}  ·  ${c.sourceName}", c.ref == channel.epgRef) {
                    scope.launch { graph.channels.assignGuide(channel, c.ref); onDone() }
                }
            }
        }
        if (choices.isEmpty()) Text("Nothing in the guide matches.", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
    }
}

/** Add channels to one of the viewer's own groups, or make a new one. */
@Composable
fun AddToGroupDialog(playlistId: Long, keys: List<String>, onDone: (String?) -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val groups by graph.channels.customGroups(playlistId).collectAsState(initial = emptyList<CustomGroupEntity>())
    var creating by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    if (creating) {
        TextInputDialog("New group", "Group name", "", onSave = { name ->
            scope.launch {
                val id = graph.channels.createCustomGroup(playlistId, name)
                if (id != null) graph.channels.addToCustomGroup(id, keys)
                onDone(name.trim().takeIf { id != null })
            }
        }, onCancel = { creating = false }, tag = "new_group_dialog")
        return
    }
    DialogFrame("Add to group", "add_to_group_dialog") {
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "new") { Row("New group…", false, Modifier.focusRequester(first)) { creating = true } }
            items(groups, key = { it.id }) { g ->
                Row(g.name, false) { scope.launch { graph.channels.addToCustomGroup(g.id, keys); onDone(g.name) } }
            }
        }
    }
}

@Composable
private fun Row(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth().testTag("row_$label"), scale = 1.02f) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
            if (selected) Text("✓", style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Bold))
        }
    }
}
