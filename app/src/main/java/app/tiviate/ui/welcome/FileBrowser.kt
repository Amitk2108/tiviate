package app.tiviate.ui.welcome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Text
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.theme.Tokens
import java.io.File
import app.tiviate.ui.t

/**
 * Minimal file browser for devices without a system document picker (most Fire TV models).
 * Lists folders and .m3u/.m3u8 files on shared storage.
 */
@Composable
fun FileBrowser(onPicked: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(File(root, Environment.DIRECTORY_DOWNLOADS).takeIf { it.isDirectory } ?: root) }
    var granted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }

    val entries = remember(dir, granted) {
        dir.listFiles().orEmpty()
            .filter { it.isDirectory || it.extension.lowercase() in setOf("m3u", "m3u8") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    Column(Modifier.fillMaxSize().padding(Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t("Choose a playlist file"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
        Text(dir.absolutePath, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
        if (!granted) Text(t("Allow storage access to browse files."), style = TextStyle(color = Tokens.Danger, fontSize = Tokens.BodySize))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dir != root && dir.parentFile != null) {
                item(key = "..") { Entry("⬆  Up one folder") { dir = dir.parentFile!! } }
            }
            items(entries, key = { it.absolutePath }) { f ->
                Entry(if (f.isDirectory) "📁  ${f.name}" else "📄  ${f.name}") {
                    if (f.isDirectory) dir = f else onPicked(f.absolutePath)
                }
            }
        }
        ActionButton(t("Cancel"), onClick = onCancel)
    }
}

@Composable
private fun Entry(label: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), scale = 1.01f) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
    }
}
