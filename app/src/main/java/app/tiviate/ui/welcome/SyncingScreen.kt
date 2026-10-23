package app.tiviate.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.SyncProgress
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * Loads the channels, then opens the player straight away; the TV guide keeps loading in the
 * background so the user is watching within seconds.
 */
@Composable
fun SyncingScreen(playlistId: Long) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    var channels by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failure = null
        val opened = kotlinx.coroutines.CompletableDeferred<Boolean>()
        graph.appScope.launch {
            graph.playlists.sync(playlistId).collect { p ->
                when (p) {
                    is SyncProgress.Channels -> channels = p.count
                    is SyncProgress.Guide -> opened.complete(true)
                    is SyncProgress.Failed -> if (!opened.isCompleted) { failure = p.message; opened.complete(false) }
                    SyncProgress.Done -> opened.complete(true)
                }
            }
        }
        if (opened.await()) {
            graph.settings.update { it.copy(activePlaylistId = playlistId, lastChannelKey = null, lastGroup = null) }
            graph.player.stop()
            nav.replaceAll(Screen.Player)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("syncing_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val f = failure
        if (f == null) {
            Text("Loading your channels…", style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            Text(if (channels > 0) "$channels channels so far" else "Connecting to your provider", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize), modifier = Modifier.padding(top = 10.dp))
        } else {
            Text("Couldn't load the playlist", style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            Text(f, style = TextStyle(color = Tokens.Danger, fontSize = Tokens.BodySize), modifier = Modifier.padding(top = 10.dp, bottom = 24.dp).testTag("sync_error"))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton("Try again", onClick = { attempt++ }, primary = true)
                ActionButton("Change details", onClick = {
                    graph.appScope.launch { graph.playlists.delete(playlistId) }
                    nav.back()
                })
            }
        }
    }
}
