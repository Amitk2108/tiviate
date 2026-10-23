package app.tiviate.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.tiviate.BuildConfig
import app.tiviate.core.xtream.XtreamAccount
import app.tiviate.core.xtream.XtreamUrls
import app.tiviate.data.AppSettings
import app.tiviate.data.BufferPreset
import app.tiviate.data.db.EpgOrigin
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.PlaylistType
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ToggleRow
import app.tiviate.ui.components.TvTextField
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.theme.Tokens
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private enum class Section(val tag: String, val label: String) {
    PLAYLISTS("section_playlists", "Playlists"),
    GUIDE("section_guide", "TV guide"),
    PLAYER("section_player", "Player"),
    INTERFACE("section_interface", "Interface"),
    HISTORY("section_history", "History"),
    ABOUT("section_about", "About"),
}

/** Two-pane settings: sections on the left drive the content on the right. */
@Composable
fun SettingsScreen() {
    var section by remember { mutableStateOf(Section.PLAYLISTS) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }

    Row(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("settings_screen"), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Settings", style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 16.dp))
            Section.entries.forEachIndexed { i, s ->
                FocusSurface(
                    onClick = { section = s },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().testTag(s.tag),
                    container = if (s == section) Tokens.Raised else Color.Transparent,
                    scale = 1.02f,
                ) {
                    Text(s.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = TextStyle(color = if (s == section) Tokens.Cyan else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (section) {
                Section.PLAYLISTS -> PlaylistsSection()
                Section.GUIDE -> GuideSection()
                Section.PLAYER -> PlayerSection()
                Section.INTERFACE -> InterfaceSection()
                Section.HISTORY -> HistorySection()
                Section.ABOUT -> AboutSection()
            }
        }
    }
}

@Composable
private fun SectionColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun Caption(text: String) = Text(text, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))

@Composable
private fun Heading(text: String) = Text(text, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))

@Composable
private fun PlaylistsSection() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var confirm by remember { mutableStateOf<PlaylistEntity?>(null) }

    val pending = confirm
    if (pending != null) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusSoon(first) }
        Column(Modifier.fillMaxWidth().background(Tokens.Surface, Tokens.Shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Delete “${pending.name}”?", style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            Caption("Its channels, favorites and history on this device are removed.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Delete", {
                    confirm = null
                    scope.launch {
                        graph.player.stop()
                        graph.playlists.delete(pending.id)
                        val remaining = graph.db.playlists().all()
                        if (remaining.isEmpty()) {
                            graph.settings.update { it.copy(activePlaylistId = null, lastChannelKey = null, lastGroup = null) }
                            nav.replaceAll(Screen.Welcome)
                        } else if (settings.activePlaylistId == pending.id) {
                            graph.settings.update { it.copy(activePlaylistId = remaining.first().id, lastChannelKey = null, lastGroup = null) }
                        }
                    }
                }, Modifier.focusRequester(first), primary = true, tag = "confirm_delete")
                ActionButton("Cancel", { confirm = null })
            }
        }
        return
    }

    SectionColumn {
        Heading("Playlists")
        playlists.forEachIndexed { i, p -> PlaylistCard(i, p, active = p.id == settings.activePlaylistId, onDelete = { confirm = p }) }
        Caption("Add another playlist")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("M3U link", { nav.push(Screen.AddPlaylist(PlaylistType.M3U_URL)) })
            ActionButton("Xtream Codes", { nav.push(Screen.AddPlaylist(PlaylistType.XTREAM)) })
            ActionButton("Local file", { nav.push(Screen.AddPlaylist(PlaylistType.M3U_FILE)) })
        }
    }
}

@Composable
private fun PlaylistCard(index: Int, p: PlaylistEntity, active: Boolean, onDelete: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var channels by remember(p.id) { mutableStateOf<Int?>(null) }
    var account by remember(p.id) { mutableStateOf<XtreamAccount?>(null) }
    var syncing by remember(p.id) { mutableStateOf(false) }
    LaunchedEffect(p.id, p.lastSyncAt) {
        channels = graph.playlists.channelCount(p.id)
        if (p.type == PlaylistType.XTREAM) account = graph.playlists.xtreamAccount(p.id)
    }
    Column(Modifier.fillMaxWidth().background(Tokens.Surface, Tokens.Shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(p.name, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            if (active) Text("Active", style = TextStyle(color = Tokens.Background, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Bold), modifier = Modifier.background(Tokens.Cyan, Tokens.SmallShape).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Caption(
            listOfNotNull(
                when (p.type) { PlaylistType.M3U_URL -> "M3U link"; PlaylistType.M3U_FILE -> "Local file"; PlaylistType.XTREAM -> "Xtream Codes" },
                channels?.let { "$it channels" },
                p.lastSyncAt?.let { "updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" },
            ).joinToString("  ·  "),
        )
        account?.let { a ->
            Caption(
                listOfNotNull(
                    a.status?.let { "Account: $it" },
                    a.expiresAtMs?.let { "Expires ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}" },
                    a.maxConnections?.let { "Connections: ${a.activeConnections ?: 0}/$it" },
                ).joinToString("  ·  "),
            )
        }
        p.lastError?.let { Text(it, style = TextStyle(color = Tokens.Danger, fontSize = Tokens.CaptionSize)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            ActionButton(if (syncing) "Updating…" else "Update now", {
                if (!syncing) {
                    syncing = true
                    graph.appScope.launch {
                        graph.playlists.sync(p.id).collect { }
                        syncing = false
                    }
                }
            }, tag = "playlist_refresh_$index")
            ActionButton("Delete", onDelete, tag = "playlist_delete_$index")
        }
    }
}

@Composable
private fun GuideSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val sources by graph.playlists.epgSources.collectAsStateWithLifecycle(emptyList())
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var url by remember { mutableStateOf("") }
    var updating by remember { mutableStateOf(false) }
    SectionColumn {
        Heading("TV guide sources")
        Caption("The guide from your playlist is added automatically. Add more XMLTV links (plain, .gz or .xz) to fill gaps.")
        sources.forEach { s ->
            Column(Modifier.fillMaxWidth().background(Tokens.Surface, Tokens.Shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.name + if (s.origin == EpgOrigin.AUTO) "  (from playlist)" else "", style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                Text(XtreamUrls.redact(s.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                Caption(s.lastSyncAt?.let { "Updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}" } ?: "Not updated yet")
                s.lastError?.let { Text(it, style = TextStyle(color = Tokens.Danger, fontSize = Tokens.CaptionSize)) }
                if (s.origin == EpgOrigin.MANUAL) ActionButton("Remove", { scope.launch { graph.playlists.deleteEpgSource(s.id) } })
            }
        }
        TvTextField("Add a guide link", url, { url = it }, tag = "epg_url", hint = "http://…/guide.xml.gz")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Add", {
                if (url.startsWith("http")) scope.launch { graph.playlists.addEpgSource(url); url = "" }
            }, tag = "epg_add")
            ActionButton(if (updating) "Updating…" else "Update guide now", {
                val pid = settings.activePlaylistId ?: return@ActionButton
                if (!updating) {
                    updating = true
                    graph.appScope.launch {
                        runCatching { graph.playlists.refreshGuide(pid) }
                        updating = false
                    }
                }
            }, primary = true, tag = "epg_refresh")
        }
    }
}

@Composable
private fun PlayerSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var ua by remember(settings.defaultUserAgent) { mutableStateOf(settings.defaultUserAgent.orEmpty()) }
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    SectionColumn {
        Heading("Player")
        val label = when (settings.bufferPreset) {
            BufferPreset.FAST -> "Fast start (smaller buffer)"
            BufferPreset.NORMAL -> "Balanced"
            BufferPreset.STABLE -> "Stable (bigger buffer)"
        }
        FocusSurface(onClick = { update { it.copy(bufferPreset = nextPreset(it.bufferPreset)) } }, modifier = Modifier.fillMaxWidth().testTag("setting_buffer")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buffer", modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                Text(label, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
        }
        Caption("Choose Stable if channels often pause to buffer; Fast start for quicker channel changes on a good connection.")
        ToggleRow("Prefer software video decoder", settings.preferSoftwareDecoder, { update { it.copy(preferSoftwareDecoder = !it.preferSoftwareDecoder) } }, tag = "setting_software")
        ToggleRow("Tunneled playback", settings.tunneling, { update { it.copy(tunneling = !it.tunneling) } }, tag = "setting_tunneling")
        ToggleRow("Match TV refresh rate to video", settings.matchRefreshRate, { update { it.copy(matchRefreshRate = !it.matchRefreshRate) } }, tag = "setting_afr")
        Caption("Refresh-rate matching removes judder on 25/50 fps channels; the screen may blink when it switches.")
        TvTextField("Default User-Agent (optional)", ua, { ua = it }, tag = "setting_ua", hint = "Leave empty to use Tiviate's")
        ActionButton("Save User-Agent", { update { it.copy(defaultUserAgent = ua.trim().ifEmpty { null }) } })
    }
}

private fun nextPreset(p: BufferPreset) = BufferPreset.entries[(p.ordinal + 1) % BufferPreset.entries.size]

@Composable
private fun InterfaceSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    SectionColumn {
        Heading("Interface")
        ToggleRow("Open last channel fullscreen on start", settings.startFullscreen, { update { it.copy(startFullscreen = !it.startFullscreen) } }, tag = "setting_start_fullscreen")
        Caption("When off, Tiviate starts on the TV guide with the channel playing in the preview.")
        ToggleRow("Show channel numbers", settings.showChannelNumbers, { update { it.copy(showChannelNumbers = !it.showChannelNumbers) } })
        ToggleRow("Show clock", settings.showClock, { update { it.copy(showClock = !it.showClock) } })
        val seconds = settings.bannerTimeoutMs / 1000
        FocusSurface(onClick = { update { it.copy(bannerTimeoutMs = nextBanner(it.bannerTimeoutMs)) } }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Info bar stays for", modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                Text("$seconds s", style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

private fun nextBanner(ms: Int): Int = listOf(3_000, 4_000, 6_000, 8_000).let { list -> list[(list.indexOf(ms).coerceAtLeast(0) + 1) % list.size] }

@Composable
private fun HistorySection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var done by remember { mutableStateOf(false) }
    SectionColumn {
        Heading("History")
        Caption("Tiviate remembers the last 50 channels you watched for the Recent list.")
        ActionButton(if (done) "History cleared" else "Clear history", {
            settings.activePlaylistId?.let { pid -> scope.launch { graph.playlists.clearHistory(pid); done = true } }
        }, tag = "clear_history")
    }
}

@Composable
private fun AboutSection() {
    SectionColumn {
        Heading("Tiviate ${BuildConfig.VERSION_NAME}")
        Caption("A player for your own IPTV playlists. Tiviate doesn't provide any channels or content.")
        Text("Licence: Apache License 2.0", style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
        Caption(
            "Open-source components: AndroidX Media3 (ExoPlayer), Jetpack Compose & Compose for TV, Room, WorkManager, " +
                "DataStore, Paging (Apache-2.0); OkHttp & Okio, Moshi (Apache-2.0); Coil (Apache-2.0); XZ for Java (0BSD); Kotlin (Apache-2.0).",
        )
    }
}
