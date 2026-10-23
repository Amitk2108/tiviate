package app.tiviate.ui.welcome

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.core.xtream.XtreamCredentials
import app.tiviate.data.db.PlaylistType
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.ToggleRow
import app.tiviate.ui.components.TvTextField
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch
import app.tiviate.ui.t

@Composable
fun AddPlaylistScreen(type: PlaylistType, editId: Long? = null) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<String?>(null) }
    var preferHls by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    // Down from the last field always lands on "Add playlist" (never on Cancel).
    val toSave = Modifier.focusProperties { down = saveButton }

    // Editing: start from the saved details (the password field stays masked).
    LaunchedEffect(editId) {
        val p = editId?.let { graph.playlists.get(it) } ?: return@LaunchedEffect
        name = p.name
        epgUrl = p.epgUrl.orEmpty()
        userAgent = p.userAgent.orEmpty()
        server = p.xtreamServer.orEmpty()
        username = p.xtreamUser.orEmpty()
        password = p.xtreamPass.orEmpty()
        preferHls = p.preferHls
        if (type == PlaylistType.M3U_FILE) fileUri = p.url else url = p.url.orEmpty()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            fileUri = uri.toString()
        }
    }

    if (showBrowser) {
        FileBrowser(onPicked = { fileUri = it; showBrowser = false }, onCancel = { showBrowser = false })
        return
    }

    fun save() {
        error = validate(type, url, server, username, password, fileUri)
        if (error != null) return
        scope.launch {
            val repo = graph.playlists
            if (editId != null) {
                when (type) {
                    PlaylistType.M3U_URL -> repo.updateM3uUrl(editId, name, url, epgUrl, userAgent)
                    PlaylistType.M3U_FILE -> repo.updateM3uFile(editId, name, fileUri!!, epgUrl)
                    PlaylistType.XTREAM -> repo.updateXtream(editId, name, XtreamCredentials(server, username, password), preferHls, userAgent)
                }
                nav.push(Screen.Syncing(editId)) // reload with the new details
                return@launch
            }
            val id = when (type) {
                PlaylistType.M3U_URL -> repo.addM3uUrl(name.ifBlank { defaultName(url) }, url, epgUrl, userAgent)
                PlaylistType.M3U_FILE -> repo.addM3uFile(name.ifBlank { t("Local playlist") }, fileUri!!, epgUrl)
                PlaylistType.XTREAM -> repo.addXtream(name.ifBlank { defaultName(server) }, XtreamCredentials(server, username, password), preferHls, userAgent)
            }
            nav.push(Screen.Syncing(id))
        }
    }

    Row(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("add_playlist_screen"), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (editId != null) t("Edit playlist") else title(type), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            Text(help(type), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (type) {
                PlaylistType.M3U_URL -> {
                    TvTextField(t("Playlist address"), url, { url = it }, Modifier.focusRequester(firstField), tag = "field_url", hint = "http://…/playlist.m3u")
                    TvTextField(t("Name (optional)"), name, { name = it }, tag = "field_name", keyboardType = KeyboardType.Text)
                    TvTextField(t("TV guide address (optional)"), epgUrl, { epgUrl = it }, tag = "field_epg", hint = t("Detected from the playlist when empty"))
                    TvTextField(t("User-Agent (optional)"), userAgent, { userAgent = it }, toSave, tag = "field_ua", keyboardType = KeyboardType.Text)
                }
                PlaylistType.XTREAM -> {
                    TvTextField(t("Server"), server, { server = it }, Modifier.focusRequester(firstField), tag = "field_server", hint = "http://provider.example:8080")
                    TvTextField(t("Username"), username, { username = it }, tag = "field_username", keyboardType = KeyboardType.Text)
                    TvTextField(t("Password"), password, { password = it }, tag = "field_password", password = true)
                    TvTextField(t("Name (optional)"), name, { name = it }, tag = "field_name", keyboardType = KeyboardType.Text)
                    TvTextField(t("User-Agent (optional)"), userAgent, { userAgent = it }, tag = "field_ua", keyboardType = KeyboardType.Text)
                    ToggleRow(t("Prefer HLS (.m3u8) streams"), preferHls, { preferHls = !preferHls }, toSave, tag = "field_hls")
                }
                PlaylistType.M3U_FILE -> {
                    ActionButton(
                        text = fileUri?.let { t("File: ") + Uri.decode(it).substringAfterLast('/') } ?: t("Choose a playlist file"),
                        onClick = {
                            try {
                                picker.launch(arrayOf("*/*"))
                            } catch (e: ActivityNotFoundException) {
                                showBrowser = true // Fire TV usually has no system file picker
                            }
                        },
                        modifier = Modifier.focusRequester(firstField),
                        tag = "choose_file",
                    )
                    TvTextField(t("Name (optional)"), name, { name = it }, tag = "field_name", keyboardType = KeyboardType.Text)
                    TvTextField(t("TV guide address (optional)"), epgUrl, { epgUrl = it }, toSave, tag = "field_epg")
                }
            }
            error?.let { Text(it, style = TextStyle(color = Tokens.Danger, fontSize = Tokens.BodySize)) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton(if (editId != null) t("Save changes") else t("Add playlist"), onClick = ::save, modifier = Modifier.focusRequester(saveButton), primary = true, tag = "save_playlist")
                ActionButton(t("Cancel"), onClick = { nav.back() })
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
}

private fun title(type: PlaylistType) = when (type) {
    PlaylistType.M3U_URL -> t("M3U link")
    PlaylistType.XTREAM -> t("Xtream Codes")
    PlaylistType.M3U_FILE -> t("Local file")
}

private fun help(type: PlaylistType) = when (type) {
    PlaylistType.M3U_URL -> t("Enter the playlist address your provider gave you. The TV guide is picked up automatically when the playlist includes one.")
    PlaylistType.XTREAM -> t("Enter the login details from your provider. Channels, groups and the TV guide are loaded automatically.")
    PlaylistType.M3U_FILE -> t("Pick an .m3u or .m3u8 file stored on this device or on a USB drive.")
}

private fun validate(type: PlaylistType, url: String, server: String, user: String, pass: String, file: String?): String? = when (type) {
    PlaylistType.M3U_URL -> if (!url.trim().startsWith("http://") && !url.trim().startsWith("https://")) "Enter an address starting with http:// or https://" else null
    PlaylistType.XTREAM -> if (server.isBlank() || user.isBlank() || pass.isBlank()) t("Server, username and password are all required") else null
    PlaylistType.M3U_FILE -> if (file == null) t("Choose a playlist file first") else null
}

private fun defaultName(address: String): String =
    address.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { t("My playlist") }
