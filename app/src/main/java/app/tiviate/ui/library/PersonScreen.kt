package app.tiviate.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.core.meta.TmdbPerson
import app.tiviate.data.db.MovieEntity
import app.tiviate.data.db.SeriesEntity
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.RemoteImage
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens

/** Someone from a title's cast (TMDB): photo, name, a short biography and their films and series in the playlist. */
@Composable
fun PersonScreen(playlistId: Long, personId: Long) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val phone = LocalFormFactor.current == FormFactor.PHONE
    val person by produceState<TmdbPerson?>(null, personId) { value = graph.meta.person(personId) }
    val inPlaylist by produceState(emptyList<Any>(), person) { person?.let { value = graph.library.findInLibrary(playlistId, it.credits) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(inPlaylist.isNotEmpty()) { if (inPlaylist.isNotEmpty()) focusSoon(first) }
    val p = person
    Column(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("person_screen"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (p == null) {
            Text(t("Loading…"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.width(if (phone) 96.dp else 140.dp).aspectRatio(2f / 3f).background(Tokens.Raised, Tokens.SmallShape)) {
                RemoteImage(graph.meta.image(p.photo, "w185"), Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.name, style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
                p.biography?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize)) }
            }
        }
        Text(t("In your playlist"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
        if (inPlaylist.isEmpty()) {
            Text(t("None of their films or series are in this playlist."), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(inPlaylist) { i, item ->
                val modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                Box(modifier) {
                    when (item) {
                        is MovieEntity -> PosterCard(item.name, item.poster, "person_item_$i", width = 110.dp) { nav.push(Screen.MovieDetails(playlistId, item.streamId)) }
                        is SeriesEntity -> PosterCard(item.name, item.cover, "person_item_$i", width = 110.dp) { nav.push(Screen.SeriesDetails(playlistId, item.seriesId)) }
                    }
                }
            }
        }
    }
}
