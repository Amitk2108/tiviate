package app.tiviate.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.SearchResult
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.components.ChannelLogo
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.TvTextField
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import app.tiviate.ui.t
import app.tiviate.ui.tf

/** Channels by name and what's on now/next by title. Results update as the viewer types. */
@Composable
fun SearchScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(SearchResult(emptyList(), emptyList())) }
    var userAgent by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }

    LaunchedEffect(Unit) { focusSoon(field) }
    LaunchedEffect(text) {
        delay(300) // debounce typing
        val pid = graph.settings.settings.first().activePlaylistId ?: return@LaunchedEffect
        userAgent = graph.playlists.get(pid)?.userAgent
        result = if (text.isBlank()) SearchResult(emptyList(), emptyList()) else graph.channels.search(pid, text)
    }

    fun play(channel: ChannelEntity) {
        graph.player.tune(channel, userAgent)
        nav.back()
    }

    fun playArchive(hit: app.tiviate.data.ProgrammeHit) {
        val p = hit.programme
        graph.appScope.launch {
            val playlist = graph.playlists.get(hit.channel.playlistId)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (graph.player.playCatchup(hit.channel, playlist, p.title, p.startMs, p.stopMs)) nav.back() else play(hit.channel)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(Tokens.SafeMargin).testTag("search_screen"),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(t("Search"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
        TvTextField(t("Channel or programme"), text, { text = it }, Modifier.focusRequester(field), tag = "search_field", hint = t("Type a name…"), keyboardType = KeyboardType.Text)

        if (text.isNotBlank() && result.channels.isEmpty() && result.programmes.isEmpty() && result.later.isEmpty() && result.archive.isEmpty()) {
            Text(tf("Nothing found for “%s”", text), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
        }
        if (result.channels.isNotEmpty()) {
            Section(t("Channels")) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(result.channels, key = { _, c -> c.id }) { i, c ->
                        FocusSurface(onClick = { play(c) }, modifier = Modifier.width(220.dp).testTag("search_channel_$i")) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                ChannelLogo(c, 44.dp)
                                Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                }
            }
        }
        ProgrammeSection(t("On now & next"), result.programmes, "search_programme", withDay = false) { play(it.channel) }
        // Later programmes: a press sets (or cancels) a reminder instead of switching now.
        ProgrammeSection(t("Later this week"), result.later, "search_later", withDay = true) { hit ->
            graph.appScope.launch {
                val set = app.tiviate.data.Reminders.toggle(context, graph.settings, hit.channel, hit.programme.title, hit.programme.startMs)
                toast = if (set) tf("Reminder set for %s", app.tiviate.ui.guide.dayLabel(hit.programme.startMs, System.currentTimeMillis()) + " " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(hit.programme.startMs))) else t("Reminder cancelled")
            }
        }
        ProgrammeSection(t("Catch-up"), result.archive, "search_archive", withDay = true, onClick = ::playArchive)
        toast?.let { Text(it, modifier = Modifier.testTag("search_toast"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium)) }
    }
}

/** A row of programme cards: channel, title and time (with the day for other days). */
@Composable
private fun ProgrammeSection(title: String, hits: List<app.tiviate.data.ProgrammeHit>, tag: String, withDay: Boolean, onClick: (app.tiviate.data.ProgrammeHit) -> Unit) {
    if (hits.isEmpty()) return
    val now = System.currentTimeMillis()
    Section(title) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(hits, key = { _, h -> h.programme.id }) { i, hit ->
                FocusSurface(onClick = { onClick(hit) }, modifier = Modifier.width(300.dp).testTag("${tag}_$i")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelLogo(hit.channel, 32.dp)
                            Text(hit.channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                        }
                        Text(hit.programme.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                        val time = "${formatTime(hit.programme.startMs)} – ${formatTime(hit.programme.stopMs)}"
                        Text(
                            if (withDay) app.tiviate.ui.guide.dayLabel(hit.programme.startMs, now) + " · " + time else time,
                            style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
        content()
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
    }
}
