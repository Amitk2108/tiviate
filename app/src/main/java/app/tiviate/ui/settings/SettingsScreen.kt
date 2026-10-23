package app.tiviate.ui.settings

import androidx.compose.foundation.shape.CircleShape
import app.tiviate.ui.components.InfoChip
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.layout.size
import androidx.tv.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.Icons
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
import app.tiviate.ui.t
import app.tiviate.ui.tf

private enum class Section(val tag: String, val english: String, val icon: ImageVector) {
    GENERAL("section_general", "General", Icons.Rounded.Tune),
    PLAYLISTS("section_playlists", "Playlists", Icons.Rounded.PlaylistPlay),
    GUIDE("section_guide", "TV guide", Icons.Rounded.CalendarMonth),
    APPEARANCE("section_appearance", "Appearance", Icons.Rounded.Palette),
    PLAYER("section_player", "Playback", Icons.Rounded.PlayCircle),
    REMOTE("section_remote", "Remote control", Icons.Rounded.SettingsRemote),
    OTHER("section_other", "Other", Icons.Rounded.MoreHoriz),
    ABOUT("section_about", "About", Icons.Rounded.Info);

    /** Looked up each time, so a language change shows at once. */
    val label: String get() = t(english)
}

/** Two-pane settings: sections on the left drive the content on the right. */
@Composable
fun SettingsScreen() {
    var section by remember { mutableStateOf(Section.GENERAL) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }

    Row(Modifier.fillMaxSize().padding(horizontal = Tokens.SafeMargin, vertical = 20.dp).testTag("settings_screen"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t("Settings"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 12.dp, bottom = 14.dp))
            Section.entries.forEachIndexed { i, s ->
                val selected = s == section
                FocusSurface(
                    onClick = { section = s },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth().testTag(s.tag),
                    container = if (selected) Tokens.Selected else Color.Transparent,
                    shape = Tokens.SmallShape,
                    scale = 1.02f,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(s.icon, contentDescription = null, tint = if (selected) Tokens.Cyan else Tokens.TextSecondary, modifier = Modifier.size(20.dp))
                        Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (selected) Tokens.Cyan else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium))
                    }
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
    // Bottom room so the last row is never cut off by the screen edge.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun Caption(text: String) = Text(text, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))

/** A setting showing its current value; OK moves to the next choice. */
@Composable
private fun ValueRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag), shape = Tokens.SmallShape, scale = 1.01f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
    var pickLanguage by remember { mutableStateOf(false) }
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(transform) }
    if (pickLanguage) {
        app.tiviate.ui.player.ChoiceDialog(
            title = t("Language"),
            choices = listOf(app.tiviate.ui.player.Choice(t("TV's language"), settings.language == null) { pickLanguage = false; update { it.copy(language = null) } }) +
                app.tiviate.ui.I18n.LANGUAGES.map { (tag, name) ->
                    app.tiviate.ui.player.Choice(name, settings.language == tag) { pickLanguage = false; update { it.copy(language = tag) } }
                },
            tag = "language_dialog",
        )
        return
    }
    SectionColumn {
        Heading(t("General"))
        ValueRow(t("Language"), settings.language?.let { tag -> app.tiviate.ui.I18n.LANGUAGES.firstOrNull { it.first == tag }?.second } ?: t("TV's language"), "setting_language") { pickLanguage = true }
        ToggleRow(t("Open last channel fullscreen on start"), settings.startFullscreen, { update { it.copy(startFullscreen = !it.startFullscreen) } }, tag = "setting_start_fullscreen")
        Caption(t("When off, Tiviate starts on the TV guide with the channel playing in the preview."))
        ToggleRow(t("Confirm exit with a second Back"), settings.confirmExit, { update { it.copy(confirmExit = !it.confirmExit) } }, tag = "setting_confirm_exit")
        val context = androidx.compose.ui.platform.LocalContext.current
        ToggleRow(t("Start Tiviate when the TV turns on"), settings.autoStartOnBoot, {
            val turningOn = !settings.autoStartOnBoot
            update { it.copy(autoStartOnBoot = turningOn) }
            if (turningOn) AutoStart.askPermissionIfNeeded(context)
        }, tag = "setting_autostart")
        Caption(t("On Android 10 and newer, allow \"Display over other apps\" for Tiviate when asked — the TV needs it to open Tiviate at start-up."))
        TvTextField(t("Default User-Agent (optional)"), ua, { ua = it }, tag = "setting_ua", hint = t("Leave empty to use Tiviate's"))
        ActionButton(t("Save User-Agent"), { update { it.copy(defaultUserAgent = ua.trim().ifEmpty { null }) } })
    }
}

@Composable
private fun RemoteSection() {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(AppSettings())
    SectionColumn {
        Heading(t("Remote control"))
        ValueRow(
            t("Up / Down while watching"),
            if (settings.upDownMode == UpDownMode.UP_NEXT_DOWN_OPTIONS) t("Up: next channel · Down: options") else t("Up and Down: change channel"),
            "setting_updown",
        ) {
            scope.launch {
                graph.settings.update {
                    it.copy(upDownMode = if (it.upDownMode == UpDownMode.ZAP) UpDownMode.UP_NEXT_DOWN_OPTIONS else UpDownMode.ZAP)
                }
            }
        }
        Caption(t("Menu or a long press of OK always opens the options. CH+ and CH− always change channel."))
        val nav = LocalNavigator.current
        ActionButton(t("Option cards (show, hide, order)"), { nav.push(Screen.OptionCards) }, tag = "setting_option_cards")
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
            Text(tf("Delete “%s”?", pending.name), style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            Caption(t("Its channels, favorites and history on this device are removed."))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(t("Delete"), {
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
                ActionButton(t("Cancel"), { confirm = null })
            }
        }
        return
    }

    SectionColumn {
        Heading(t("Playlists"))
        ValueRow(t("Sort channels"), settings.channelSort.label, "setting_channel_sort") {
            scope.launch { graph.settings.update { it.copy(channelSort = ChannelSort.entries.after(it.channelSort)) } }
        }
        ValueRow(t("Open a playlist on"), settings.openPlaylistOn.label, "setting_open_on") {
            scope.launch { graph.settings.update { it.copy(openPlaylistOn = OpenOn.entries.after(it.openPlaylistOn)) } }
        }
        ValueRow(t("Update playlists"), settings.playlistUpdate.label, "setting_playlist_update") {
            scope.launch { graph.settings.update { it.copy(playlistUpdate = app.tiviate.data.PlaylistUpdate.entries.after(it.playlistUpdate)) } }
        }
        playlists.forEachIndexed { i, p -> PlaylistCard(i, p, active = p.id == settings.activePlaylistId, onDelete = { confirm = p }) }
        if (playlists.size > 1) {
            var updatingAll by remember { mutableStateOf(false) }
            ActionButton(if (updatingAll) t("Updating…") else t("Update all playlists"), {
                if (!updatingAll) {
                    updatingAll = true
                    graph.appScope.launch { graph.playlists.syncAll(); updatingAll = false }
                }
            }, tag = "playlist_update_all")
        }
        Caption(t("Add another playlist"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(t("M3U link"), { nav.push(Screen.AddPlaylist(PlaylistType.M3U_URL)) })
            ActionButton(t("Xtream Codes"), { nav.push(Screen.AddPlaylist(PlaylistType.XTREAM)) })
            ActionButton(t("Local file"), { nav.push(Screen.AddPlaylist(PlaylistType.M3U_FILE)) })
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
    val typeIcon = when (p.type) { PlaylistType.M3U_URL -> Icons.Rounded.Link; PlaylistType.M3U_FILE -> Icons.Rounded.FolderOpen; PlaylistType.XTREAM -> Icons.Rounded.Dns }
    Column(
        Modifier.fillMaxWidth()
            .background(if (active) Tokens.Selected else Tokens.Surface, Tokens.Shape)
            .then(if (active) Modifier.border(1.dp, Tokens.Cyan.copy(alpha = 0.45f), Tokens.Shape) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(Tokens.BrandGradient, CircleShape), contentAlignment = Alignment.Center) {
                Icon(typeIcon, contentDescription = null, tint = Tokens.Text, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
                    if (active) Text(t("Active"), style = TextStyle(color = Tokens.Background, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Bold), modifier = Modifier.background(Tokens.Cyan, Tokens.PillShape).padding(horizontal = 10.dp, vertical = 2.dp))
                }
                Text(
                    when (p.type) { PlaylistType.M3U_URL -> t("M3U link"); PlaylistType.M3U_FILE -> t("Local file"); PlaylistType.XTREAM -> t("Xtream Codes") },
                    style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            channels?.let { InfoChip(Icons.Rounded.LiveTv, "$it") }
            p.lastSyncAt?.let { InfoChip(Icons.Rounded.Sync, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
            account?.let { a ->
                a.status?.let { InfoChip(Icons.Rounded.VerifiedUser, it, if (it.equals("Active", ignoreCase = true)) Tokens.Cyan else Tokens.Danger) }
                a.expiresAtMs?.let { exp ->
                    val days = ((exp - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                    InfoChip(Icons.Rounded.Event, tf("Expires %s", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(exp))) + "  ·  $days d", if (days < 7) Tokens.Danger else Tokens.TextSecondary)
                }
                a.maxConnections?.let { InfoChip(Icons.Rounded.Devices, "${a.activeConnections ?: 0}/$it") }
            }
        }
        p.lastError?.let { Text(it, style = TextStyle(color = Tokens.Danger, fontSize = Tokens.CaptionSize)) }
        if (namesEditor) {
            Caption(t("Remove these from the start and end of channel names, separated by commas — for example \"UK:, IN |\" and \"| FHD, HEVC\"."))
            TvTextField(t("Prefixes to remove"), prefixes, { prefixes = it }, tag = "names_prefixes_$index")
            TvTextField(t("Suffixes to remove"), suffixes, { suffixes = it }, tag = "names_suffixes_$index")
            ActionButton(t("Apply"), {
                namesEditor = false
                scope.launch { graph.playlists.setNameEditor(p.id, prefixes, suffixes) }
            }, primary = true, tag = "names_apply_$index")
        }
        // Wraps onto a second line when the buttons do not fit (long translations, narrow screens).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (syncing) t("Updating…") else t("Update now"), icon = Icons.Rounded.Refresh, onClick = {
                if (!syncing) {
                    syncing = true
                    graph.appScope.launch {
                        graph.playlists.sync(p.id).collect { }
                        syncing = false
                    }
                }
            }, tag = "playlist_refresh_$index")
            ActionButton(t("Edit"), { nav.push(Screen.AddPlaylist(p.type, editId = p.id)) }, tag = "playlist_edit_$index", icon = Icons.Rounded.Edit)
            ActionButton(t("Names editor"), { namesEditor = !namesEditor }, tag = "playlist_names_$index", icon = Icons.Rounded.TextFields)
            ActionButton(t("Delete"), onDelete, tag = "playlist_delete_$index", icon = Icons.Rounded.Delete, danger = true)
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
        Heading(t("TV guide sources"))
        Caption(t("The guide from your playlist is added automatically. Add more XMLTV links (plain, .gz or .xz) to fill gaps."))
        sources.forEach { s ->
            Column(Modifier.fillMaxWidth().background(Tokens.Surface, Tokens.Shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.name + if (s.origin == EpgOrigin.AUTO) "  (from playlist)" else "", style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                Text(XtreamUrls.redact(s.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                Caption(s.lastSyncAt?.let { tf("Updated %s", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) } ?: t("Not updated yet"))
                s.lastError?.let { Text(it, style = TextStyle(color = Tokens.Danger, fontSize = Tokens.CaptionSize)) }
                var renaming by remember(s.id) { mutableStateOf(false) }
                var newName by remember(s.id, s.name) { mutableStateOf(s.name) }
                if (renaming) {
                    TvTextField(t("Source name"), newName, { newName = it }, tag = "epg_name_${s.id}")
                    ActionButton(t("Save name"), { renaming = false; scope.launch { graph.playlists.renameEpgSource(s.id, newName) } }, tag = "epg_name_save_${s.id}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!renaming) ActionButton(t("Rename"), { renaming = true }, tag = "epg_rename_${s.id}")
                    if (s.origin == EpgOrigin.MANUAL) ActionButton(t("Remove"), { scope.launch { graph.playlists.deleteEpgSource(s.id) } })
                }
            }
        }
        TvTextField(t("Add a guide link"), url, { url = it }, tag = "epg_url", hint = "http://…/guide.xml.gz")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(t("Add"), {
                if (url.startsWith("http")) scope.launch { graph.playlists.addEpgSource(url); url = "" }
            }, tag = "epg_add")
            ActionButton(if (updating) t("Updating…") else t("Update guide now"), {
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
        Heading(t("Updates"))
        val context = androidx.compose.ui.platform.LocalContext.current
        ValueRow(t("Keep past programmes for"), if (settings.epgPastDays == 1) "1 day" else "${settings.epgPastDays} days", "setting_epg_past_days") {
            scope.launch { graph.settings.update { it.copy(epgPastDays = listOf(1, 2, 3, 7).after(it.epgPastDays)) } }
        }
        ToggleRow(t("Update the guide when Tiviate starts"), settings.updateOnStart, { scope.launch { graph.settings.update { it.copy(updateOnStart = !it.updateOnStart) } } }, tag = "setting_update_on_start")
        ValueRow(t("Update the guide every"), tf("%d hours", settings.updateIntervalHours), "setting_update_interval") {
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
        Heading(t("Playback"))
        val label = settings.bufferPreset.label
        FocusSurface(onClick = { update { it.copy(bufferPreset = nextPreset(it.bufferPreset)) } }, modifier = Modifier.fillMaxWidth().testTag("setting_buffer")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Buffer size"), modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                Text(label, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
            }
        }
        Caption(t("A bigger buffer helps if channels often pause to load; a smaller one changes channels faster on a good connection."))
        ToggleRow(t("Prefer software video decoder"), settings.preferSoftwareDecoder, { update { it.copy(preferSoftwareDecoder = !it.preferSoftwareDecoder) } }, tag = "setting_software")
        ToggleRow(t("Tunneled playback"), settings.tunneling, { update { it.copy(tunneling = !it.tunneling) } }, tag = "setting_tunneling")
        ToggleRow(t("Match TV refresh rate to video"), settings.matchRefreshRate, { update { it.copy(matchRefreshRate = !it.matchRefreshRate) } }, tag = "setting_afr")
        Caption(t("Refresh-rate matching removes judder on 25/50 fps channels; the screen may blink when it switches."))
        ToggleRow(t("Prefer surround sound (5.1/7.1) when a channel has it"), settings.preferSurround, { update { it.copy(preferSurround = !it.preferSurround) } }, tag = "setting_surround")
        ValueRow(t("Audio sync"), app.tiviate.ui.player.audioSyncLabel(settings.audioOffsetMs), "setting_audio_sync") {
            update { it.copy(audioOffsetMs = listOf(0, 50, 100, 150, 200, 300, 400, 500, -500, -400, -300, -200, -150, -100, -50).after(it.audioOffsetMs)) }
        }
        ToggleRow(t("Black screen while switching channels"), settings.blackOnSwitch, { update { it.copy(blackOnSwitch = !it.blackOnSwitch) } }, tag = "setting_black")
        ToggleRow(t("Always open channels in another player"), settings.alwaysExternalPlayer, { update { it.copy(alwaysExternalPlayer = !it.alwaysExternalPlayer) } }, tag = "setting_external")
        ToggleRow(t("Picture-in-picture when Home is pressed"), settings.pipOnHome, { update { it.copy(pipOnHome = !it.pipOnHome) } }, tag = "setting_pip_home")
        val captionContext = androidx.compose.ui.platform.LocalContext.current
        ActionButton(t("Subtitle style (TV settings)"), {
            runCatching { captionContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_CAPTIONING_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, tag = "setting_caption_style")
        Caption(t("Subtitles use the size, colour and background chosen in the TV's caption settings."))
        Heading(t("For all channels"))
        ValueRow(
            t("Audio language"),
            settings.preferredAudioLanguage?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: t("Not set"),
            "setting_audio_lang",
        ) { if (settings.preferredAudioLanguage != null) update { it.copy(preferredAudioLanguage = null) } }
        Caption(t("Choose it while watching: Down → Audio → \"Use this language for all channels\". OK here clears it."))
        ToggleRow(t("Subtitles on"), settings.subtitlesForAll, { update { it.copy(subtitlesForAll = !it.subtitlesForAll) } }, tag = "setting_subs_all")
        ValueRow(t("Display mode"), settings.displayMode.label, "setting_display_mode") { update { it.copy(displayMode = DisplayMode.entries.after(it.displayMode)) } }
        ValueRow(t("Zoom"), "${settings.zoomPercent}%", "setting_zoom") { update { it.copy(zoomPercent = listOf(100, 110, 120, 130, 140, 150).after(it.zoomPercent)) } }
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
        Heading(t("Appearance"))
        ValueRow(t("Accent colour"), settings.accent.label, "setting_accent") { update { it.copy(accent = app.tiviate.data.AccentColor.entries.after(it.accent)) } }
        ValueRow(t("Background"), settings.backgroundTheme.label, "setting_background") { update { it.copy(backgroundTheme = app.tiviate.data.BackgroundTheme.entries.after(it.backgroundTheme)) } }
        ValueRow(t("Font size"), settings.fontSize.label, "setting_font_size") { update { it.copy(fontSize = app.tiviate.data.FontSize.entries.after(it.fontSize)) } }
        ValueRow(t("Panel transparency"), "${settings.panelTransparency} %", "setting_transparency") { update { it.copy(panelTransparency = listOf(0, 20, 40, 60).after(it.panelTransparency)) } }
        ValueRow(t("Logo style"), settings.logoStyle.label, "setting_logo_style") { update { it.copy(logoStyle = app.tiviate.data.LogoStyle.entries.after(it.logoStyle)) } }
        ToggleRow(t("Two-line channel names"), settings.twoLineNames, { update { it.copy(twoLineNames = !it.twoLineNames) } }, tag = "setting_two_line_names")
        ToggleRow(t("Two-line programme titles in the guide"), settings.twoLineTitles, { update { it.copy(twoLineTitles = !it.twoLineTitles) } }, tag = "setting_two_line_titles")
        ToggleRow(t("Show playlist and group name in the info bar"), settings.showPlaylistAndGroup, { update { it.copy(showPlaylistAndGroup = !it.showPlaylistAndGroup) } }, tag = "setting_show_source")
        ToggleRow(t("Animations"), settings.animations, { update { it.copy(animations = !it.animations) } }, tag = "setting_animations")
        Heading(t("Clock"))
        ValueRow(t("Clock size"), settings.clockSize.label, "setting_clock_size") { update { it.copy(clockSize = app.tiviate.data.ClockSize.entries.after(it.clockSize)) } }
        ToggleRow(t("Show the date"), settings.clockShowDate, { update { it.copy(clockShowDate = !it.clockShowDate) } }, tag = "setting_clock_date")
        ValueRow(t("Clock on the video"), settings.clockCorner.label, "setting_clock_corner") { update { it.copy(clockCorner = app.tiviate.data.ClockCorner.entries.after(it.clockCorner)) } }
        Heading(t("Groups list"))
        ToggleRow(t("Show \"All channels\""), settings.showAllChannels, { update { it.copy(showAllChannels = !it.showAllChannels) } }, tag = "setting_show_all")
        ToggleRow(t("Show \"Favorites\""), settings.showFavoritesGroup, { update { it.copy(showFavoritesGroup = !it.showFavoritesGroup) } }, tag = "setting_show_favorites")
        ToggleRow(t("Show \"Recent\""), settings.showRecentGroup, { update { it.copy(showRecentGroup = !it.showRecentGroup) } }, tag = "setting_show_recent")
        Heading(t("Screens"))
        ToggleRow(t("TV guide over the full-screen video"), settings.guideOverlay, { update { it.copy(guideOverlay = !it.guideOverlay) } }, tag = "setting_guide_overlay")
        ToggleRow(t("Channel list with a small video window"), settings.listPreview, { update { it.copy(listPreview = !it.listPreview) } }, tag = "setting_list_preview")
        ToggleRow(t("Stay on the channel list after switching"), settings.stayOnList, { update { it.copy(stayOnList = !it.stayOnList) } }, tag = "setting_stay_on_list")
        ValueRow(t("Play the channel you stop on in the list"), if (settings.autoplayDelaySec == 0) t("Off") else "after ${settings.autoplayDelaySec} s", "setting_autoplay") {
            update { it.copy(autoplayDelaySec = listOf(0, 1, 2, 3).after(it.autoplayDelaySec)) }
        }
        ToggleRow(t("Show what's on in the channel list"), settings.listShowProgramme, { update { it.copy(listShowProgramme = !it.listShowProgramme) } }, tag = "setting_list_programme")
        ToggleRow(t("Show programme descriptions"), settings.listShowDescription, { update { it.copy(listShowDescription = !it.listShowDescription) } }, tag = "setting_list_description")
        ValueRow(t("Highlight in the TV guide"), settings.guideHighlight.label, "setting_guide_highlight") {
            update { it.copy(guideHighlight = GuideHighlight.entries.after(it.guideHighlight)) }
        }
        ValueRow(
            t("Close the channel list and options after"),
            if (settings.panelTimeoutSec == 0) t("Never") else "${settings.panelTimeoutSec} s",
            "setting_panel_timeout",
        ) { update { it.copy(panelTimeoutSec = listOf(0, 10, 20, 30).after(it.panelTimeoutSec)) } }
        ToggleRow(t("Show channel numbers"), settings.showChannelNumbers, { update { it.copy(showChannelNumbers = !it.showChannelNumbers) } })
        ToggleRow(t("Show clock"), settings.showClock, { update { it.copy(showClock = !it.showClock) } })
        val seconds = settings.bannerTimeoutMs / 1000
        FocusSurface(onClick = { update { it.copy(bannerTimeoutMs = nextBanner(it.bannerTimeoutMs)) } }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(t("Info bar stays for"), modifier = Modifier.weight(1f), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
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
        Heading(t("Recent channels"))
        ValueRow(t("Channels to keep"), "${settings.recentCount}", "setting_recent_count") {
            scope.launch { graph.settings.update { it.copy(recentCount = listOf(20, 50, 100).after(it.recentCount)) } }
        }
        ValueRow(t("Add a channel after watching for"), "${settings.recentDelaySec} s", "setting_recent_delay") {
            scope.launch { graph.settings.update { it.copy(recentDelaySec = listOf(3, 5, 10, 30).after(it.recentDelaySec)) } }
        }
        ActionButton(if (done) t("History cleared") else t("Clear history"), {
            settings.activePlaylistId?.let { pid -> scope.launch { graph.playlists.clearHistory(pid); done = true } }
        }, tag = "clear_history")
    }
}

@Composable
private fun AboutSection() {
    SectionColumn {
        Heading("Tiviate ${BuildConfig.VERSION_NAME}")
        Caption(t("A player for your own IPTV playlists. Tiviate doesn't provide any channels or content."))
        Text(t("Licence: GNU GPL v3.0"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
        Caption(
            t("Open-source components: AndroidX Media3 (ExoPlayer), Jetpack Compose & Compose for TV, Room, WorkManager, ") +
                t("DataStore, Paging (Apache-2.0); OkHttp & Okio, Moshi (Apache-2.0); Coil (Apache-2.0); XZ for Java (0BSD); Kotlin (Apache-2.0); ") +
                t("Jellyfin FFmpeg decoder and FFmpeg (GPL-3.0)."),
        )
    }
}
