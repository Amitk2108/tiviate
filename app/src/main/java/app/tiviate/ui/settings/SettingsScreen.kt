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
import app.tiviate.data.ChannelSort
import app.tiviate.data.DisplayMode
import app.tiviate.data.OpenOn
import app.tiviate.data.GuideHighlight
import app.tiviate.AutoStart
import app.tiviate.data.UpDownMode
import app.tiviate.data.sync.SyncScheduler
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
    GENERAL("section_general", "General"),
    PLAYLISTS("section_playlists", "Playlists"),
    GUIDE("section_guide", "TV guide"),
    APPEARANCE("section_appearance", "Appearance"),
    PLAYER("section_player", "Playback"),
    REMOTE("section_remote", "Remote control"),
    OTHER("section_other", "Other"),
    ABOUT("section_about", "About"),
}

/** Two-pane settings: sections on the left drive the content on the right. */
@Composable
fun SettingsScreen() {
    var section by remember { mutableStateOf(Section.GENERAL) }
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
                Section.GENERAL -> GeneralSection()
                Section.PLAYLISTS -> PlaylistsSection()
                Section.GUIDE -> GuideSection()
                Section.APPEARANCE -> AppearanceSection()
                Section.PLAYER -> PlayerSection()
                Section.REMOTE -> RemoteSection()
                Section.OTHER -> OtherSection()
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

/** A setting showing its current value; OK moves to the next choice. */
@Composable
private fun ValueRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
            Text(value, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
        }
    }
}

private fun <T> List<T>.after(current: T): T = this[(indexOf(current).coerceAtLeast(0) + 1) % size]

@Composable
private fun GeneralSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var ua by remember(settings.defaultUserAgent) { mutableStateOf(settings.defaultUserAgent.orEmpty()) }
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    SectionColumn {
        Heading("General")
        ToggleRow("Open last channel fullscreen on start", settings.startFullscreen, { update { it.copy(startFullscreen = !it.startFullscreen) } }, tag = "setting_start_fullscreen")
        Caption("When off, Tiviate starts on the TV guide with the channel playing in the preview.")
        ToggleRow("Confirm exit with a second Back", settings.confirmExit, { update { it.copy(confirmExit = !it.confirmExit) } }, tag = "setting_confirm_exit")
        val context = androidx.compose.ui.platform.LocalContext.current
        ToggleRow("Start Tiviate when the TV turns on", settings.autoStartOnBoot, {
            val turningOn = !settings.autoStartOnBoot
            update { it.copy(autoStartOnBoot = turningOn) }
            if (turningOn) AutoStart.askPermissionIfNeeded(context)
        }, tag = "setting_autostart")
        Caption("On Android 10 and newer, allow \"Display over other apps\" for Tiviate when asked — the TV needs it to open Tiviate at start-up.")
        TvTextField("Default User-Agent (optional)", ua, { ua = it }, tag = "setting_ua", hint = "Leave empty to use Tiviate's")
        ActionButton("Save User-Agent", { update { it.copy(defaultUserAgent = ua.trim().ifEmpty { null }) } })
    }
}

@Composable
private fun RemoteSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    SectionColumn {
        Heading("Remote control")
        ValueRow(
            "Up / Down while watching",
            if (settings.upDownMode == UpDownMode.UP_NEXT_DOWN_OPTIONS) "Up: next channel · Down: options" else "Up and Down: change channel",
            "setting_updown",
        ) {
            scope.launch {
                graph.settings.update {
                    it.copy(upDownMode = if (it.upDownMode == UpDownMode.ZAP) UpDownMode.UP_NEXT_DOWN_OPTIONS else UpDownMode.ZAP)
                }
            }
        }
        Caption("Menu or a long press of OK always opens the options. CH+ and CH− always change channel.")
    }
}

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
        ValueRow("Sort channels", settings.channelSort.label, "setting_channel_sort") {
            scope.launch { graph.settings.update { it.copy(channelSort = ChannelSort.entries.after(it.channelSort)) } }
        }
        ValueRow("Open a playlist on", settings.openPlaylistOn.label, "setting_open_on") {
            scope.launch { graph.settings.update { it.copy(openPlaylistOn = OpenOn.entries.after(it.openPlaylistOn)) } }
        }
        playlists.forEachIndexed { i, p -> PlaylistCard(i, p, active = p.id == settings.activePlaylistId, onDelete = { confirm = p }) }
        if (playlists.size > 1) {
            var updatingAll by remember { mutableStateOf(false) }
            ActionButton(if (updatingAll) "Updating…" else "Update all playlists", {
                if (!updatingAll) {
                    updatingAll = true
                    graph.appScope.launch { graph.playlists.syncAll(); updatingAll = false }
                }
            }, tag = "playlist_update_all")
        }
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
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var channels by remember(p.id) { mutableStateOf<Int?>(null) }
    var account by remember(p.id) { mutableStateOf<XtreamAccount?>(null) }
    var syncing by remember(p.id) { mutableStateOf(false) }
    var namesEditor by remember(p.id) { mutableStateOf(false) }
    var prefixes by remember(p.id, p.namePrefixes) { mutableStateOf(p.namePrefixes.orEmpty()) }
    var suffixes by remember(p.id, p.nameSuffixes) { mutableStateOf(p.nameSuffixes.orEmpty()) }
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
        if (namesEditor) {
            Caption("Remove these from the start and end of channel names, separated by commas — for example \"UK:, IN |\" and \"| FHD, HEVC\".")
            TvTextField("Prefixes to remove", prefixes, { prefixes = it }, tag = "names_prefixes_$index")
            TvTextField("Suffixes to remove", suffixes, { suffixes = it }, tag = "names_suffixes_$index")
            ActionButton("Apply", {
                namesEditor = false
                scope.launch { graph.playlists.setNameEditor(p.id, prefixes, suffixes) }
            }, primary = true, tag = "names_apply_$index")
        }
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
            ActionButton("Edit", { nav.push(Screen.AddPlaylist(p.type, editId = p.id)) }, tag = "playlist_edit_$index")
            ActionButton("Names editor", { namesEditor = !namesEditor }, tag = "playlist_names_$index")
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
                var renaming by remember(s.id) { mutableStateOf(false) }
                var newName by remember(s.id, s.name) { mutableStateOf(s.name) }
                if (renaming) {
                    TvTextField("Source name", newName, { newName = it }, tag = "epg_name_${s.id}")
                    ActionButton("Save name", { renaming = false; scope.launch { graph.playlists.renameEpgSource(s.id, newName) } }, tag = "epg_name_save_${s.id}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!renaming) ActionButton("Rename", { renaming = true }, tag = "epg_rename_${s.id}")
                    if (s.origin == EpgOrigin.MANUAL) ActionButton("Remove", { scope.launch { graph.playlists.deleteEpgSource(s.id) } })
                }
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
        Heading("Updates")
        val context = androidx.compose.ui.platform.LocalContext.current
        ValueRow("Keep past programmes for", if (settings.epgPastDays == 1) "1 day" else "${settings.epgPastDays} days", "setting_epg_past_days") {
            scope.launch { graph.settings.update { it.copy(epgPastDays = listOf(1, 2, 3, 7).after(it.epgPastDays)) } }
        }
        ToggleRow("Update when Tiviate starts", settings.updateOnStart, { scope.launch { graph.settings.update { it.copy(updateOnStart = !it.updateOnStart) } } }, tag = "setting_update_on_start")
        ValueRow("Update playlists and guide every", "${settings.updateIntervalHours} hours", "setting_update_interval") {
            val next = listOf(6, 12, 24).after(settings.updateIntervalHours)
            scope.launch { graph.settings.update { it.copy(updateIntervalHours = next) } }
            graph.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { SyncScheduler.schedulePeriodic(context, next) }
        }
    }
}

@Composable
private fun PlayerSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    SectionColumn {
        Heading("Playback")
        val label = settings.bufferPreset.label
        FocusSurface(onClick = { update { it.copy(bufferPreset = nextPreset(it.bufferPreset)) } }, modifier = Modifier.fillMaxWidth().testTag("setting_buffer")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buffer size", modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                Text(label, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
        }
        Caption("A bigger buffer helps if channels often pause to load; a smaller one changes channels faster on a good connection.")
        ToggleRow("Prefer software video decoder", settings.preferSoftwareDecoder, { update { it.copy(preferSoftwareDecoder = !it.preferSoftwareDecoder) } }, tag = "setting_software")
        ToggleRow("Tunneled playback", settings.tunneling, { update { it.copy(tunneling = !it.tunneling) } }, tag = "setting_tunneling")
        ToggleRow("Match TV refresh rate to video", settings.matchRefreshRate, { update { it.copy(matchRefreshRate = !it.matchRefreshRate) } }, tag = "setting_afr")
        Caption("Refresh-rate matching removes judder on 25/50 fps channels; the screen may blink when it switches.")
        Heading("For all channels")
        ValueRow(
            "Audio language",
            settings.preferredAudioLanguage?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Not set",
            "setting_audio_lang",
        ) { if (settings.preferredAudioLanguage != null) update { it.copy(preferredAudioLanguage = null) } }
        Caption("Choose it while watching: Down → Audio → \"Use this language for all channels\". OK here clears it.")
        ToggleRow("Subtitles on", settings.subtitlesForAll, { update { it.copy(subtitlesForAll = !it.subtitlesForAll) } }, tag = "setting_subs_all")
        ValueRow("Display mode", settings.displayMode.label, "setting_display_mode") { update { it.copy(displayMode = DisplayMode.entries.after(it.displayMode)) } }
        ValueRow("Zoom", "${settings.zoomPercent}%", "setting_zoom") { update { it.copy(zoomPercent = listOf(100, 110, 120, 130, 140, 150).after(it.zoomPercent)) } }
    }
}

private fun nextPreset(p: BufferPreset) = BufferPreset.entries[(p.ordinal + 1) % BufferPreset.entries.size]

@Composable
private fun AppearanceSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    SectionColumn {
        Heading("Appearance")
        ToggleRow("Show what's on in the channel list", settings.listShowProgramme, { update { it.copy(listShowProgramme = !it.listShowProgramme) } }, tag = "setting_list_programme")
        ToggleRow("Show programme descriptions", settings.listShowDescription, { update { it.copy(listShowDescription = !it.listShowDescription) } }, tag = "setting_list_description")
        ValueRow("Highlight in the TV guide", settings.guideHighlight.label, "setting_guide_highlight") {
            update { it.copy(guideHighlight = GuideHighlight.entries.after(it.guideHighlight)) }
        }
        ValueRow(
            "Close the channel list and options after",
            if (settings.panelTimeoutSec == 0) "Never" else "${settings.panelTimeoutSec} s",
            "setting_panel_timeout",
        ) { update { it.copy(panelTimeoutSec = listOf(0, 10, 20, 30).after(it.panelTimeoutSec)) } }
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
private fun OtherSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var done by remember { mutableStateOf(false) }
    SectionColumn {
        Heading("Recent channels")
        ValueRow("Channels to keep", "${settings.recentCount}", "setting_recent_count") {
            scope.launch { graph.settings.update { it.copy(recentCount = listOf(20, 50, 100).after(it.recentCount)) } }
        }
        ValueRow("Add a channel after watching for", "${settings.recentDelaySec} s", "setting_recent_delay") {
            scope.launch { graph.settings.update { it.copy(recentDelaySec = listOf(3, 5, 10, 30).after(it.recentDelaySec)) } }
        }
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
        Text("Licence: GNU GPL v3.0", style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
        Caption(
            "Open-source components: AndroidX Media3 (ExoPlayer), Jetpack Compose & Compose for TV, Room, WorkManager, " +
                "DataStore, Paging (Apache-2.0); OkHttp & Okio, Moshi (Apache-2.0); Coil (Apache-2.0); XZ for Java (0BSD); Kotlin (Apache-2.0); " +
                "Jellyfin FFmpeg decoder and FFmpeg (GPL-3.0).",
        )
    }
}
