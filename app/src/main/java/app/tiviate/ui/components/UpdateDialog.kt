package app.tiviate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.tiviate.data.update.Release
import app.tiviate.data.update.UpdateChecker
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch

/** The update being offered, drawn by the app root over whatever screen is open. */
object UpdatePrompt {
    var release by mutableStateOf<Release?>(null)
}

/** "Version X is available": Install downloads the APK and opens the installer; Later skips this version. */
@Composable
fun UpdateDialog(release: Release, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val install = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(install) }
    Box(Modifier.fillMaxSize().background(Tokens.Scrim).testTag("update_dialog"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(620.dp).background(Tokens.Surface, Tokens.Shape).padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = Tokens.Cyan)
                Text(tf("Version %s is available", release.version), style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Bold))
            }
            if (release.notes.isNotBlank()) {
                Text(plainNotes(release.notes), maxLines = 8, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            }
            progress?.let { p ->
                Text(tf("Downloading… %d%%", p), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                ProgressLine(p / 100f, Modifier.fillMaxWidth())
            }
            message?.let { Text(it, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(t("Install"), {
                    if (progress != null && progress!! < 100) return@ActionButton
                    scope.launch {
                        runCatching {
                            progress = 0
                            val apk = UpdateChecker.download(context, graph.http, release) { progress = it }
                            if (!UpdateChecker.install(context, apk)) {
                                message = t("Allow Tiviate to install apps, then press Install again.") +
                                    "\n" + t("Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → Tiviate.")
                            }
                        }.onFailure { progress = null; message = t("Couldn't check for updates") }
                    }
                }, modifier = Modifier.focusRequester(install), primary = true, tag = "update_install")
                ActionButton(t("Later"), {
                    scope.launch { graph.settings.update { it.copy(skippedUpdate = release.version) } }
                    onClose()
                }, tag = "update_later")
            }
        }
    }
}

/** Release notes as plain text: links show their label, and Markdown marks (#, *, >, `) go. */
internal fun plainNotes(markdown: String): String = markdown
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .lines().joinToString("\n") { it.trimStart('>', '#', ' ').replace(Regex("[*`]"), "").trimEnd() }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
