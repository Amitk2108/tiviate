package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.Spinner
import app.tiviate.ui.theme.Tokens

@Composable
fun BufferingIndicator(retrying: Int, notice: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spinner()
            if (notice != null) {
                Text(notice, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, textAlign = TextAlign.Center), modifier = Modifier.width(520.dp).background(Tokens.Scrim, Tokens.Shape).padding(12.dp))
            } else if (retrying > 0) {
                Text("Reconnecting… ($retrying)", style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize), modifier = Modifier.background(Tokens.Scrim, Tokens.Shape).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
fun PausedBadge() {
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("paused_badge"), contentAlignment = Alignment.TopStart) {
        Text("❚❚  Paused", style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold), modifier = Modifier.background(Tokens.Scrim, Tokens.Shape).padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
fun NumberBadge(text: String) {
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin), contentAlignment = Alignment.TopEnd) {
        Text(text, style = TextStyle(color = Tokens.Cyan, fontSize = 56.sp, fontWeight = FontWeight.Bold), modifier = Modifier.background(Tokens.Scrim, Tokens.Shape).padding(horizontal = 24.dp, vertical = 8.dp).testTag("number_entry"))
    }
}

@Composable
fun Toast(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
        Text(text, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize), modifier = Modifier.background(Tokens.Raised, Tokens.Shape).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
fun ErrorCard(message: String, onRetry: () -> Unit, onNext: () -> Unit, onExternal: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(message) { focusSoon(first) }
    Box(Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag("error_card"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(Tokens.Surface, Tokens.Shape).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("This channel isn't playing", style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
            Text(message, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize, textAlign = TextAlign.Center))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Try again", onRetry, Modifier.focusRequester(first), primary = true)
                ActionButton("Next channel", onNext)
                ActionButton("Other player", onExternal)
            }
        }
    }
}
