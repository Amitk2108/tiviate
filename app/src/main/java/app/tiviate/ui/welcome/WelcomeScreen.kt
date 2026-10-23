package app.tiviate.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.R
import app.tiviate.data.db.PlaylistType
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.theme.Tokens

@Composable
fun WelcomeScreen() {
    val nav = LocalNavigator.current
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("welcome_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.mipmap.ic_launcher), contentDescription = null, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        Text("Welcome to Tiviate", style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        Text(
            "Add the playlist from your TV provider to start watching. Tiviate is a player only and includes no channels.",
            style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize, textAlign = TextAlign.Center),
            modifier = Modifier.width(560.dp),
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SourceCard("M3U link", "Paste a playlist web address", Modifier.focusRequester(first)) { nav.push(Screen.AddPlaylist(PlaylistType.M3U_URL)) }
            SourceCard("Xtream Codes", "Server, username and password") { nav.push(Screen.AddPlaylist(PlaylistType.XTREAM)) }
            SourceCard("Local file", "An .m3u file on this device") { nav.push(Screen.AddPlaylist(PlaylistType.M3U_FILE)) }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun SourceCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.width(220.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
        }
    }
}
