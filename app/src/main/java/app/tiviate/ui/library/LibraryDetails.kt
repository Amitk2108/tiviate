package app.tiviate.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.tiviate.core.meta.Kind
import app.tiviate.core.meta.Ratings
import app.tiviate.core.meta.TmdbCast
import app.tiviate.core.meta.TmdbDetails
import app.tiviate.core.xtream.XtreamDetails
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import app.tiviate.core.xtream.XtreamEpisode
import app.tiviate.core.xtream.XtreamSeriesInfo
import app.tiviate.data.LibraryRepository
import app.tiviate.data.db.MovieEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.SeriesEntity
import app.tiviate.data.db.WatchProgressEntity
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.components.RemoteImage
import app.tiviate.ui.organise.TextInputDialog
import app.tiviate.ui.player.clock
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch

/** A film: backdrop, details, Play / Resume, Trailer, My list and Favorite. */
@Composable
fun MovieDetailsScreen(playlistId: Long, streamId: Long) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val playlist by produceState<PlaylistEntity?>(null, playlistId) { value = graph.playlists.get(playlistId) }
    val movie by produceState<MovieEntity?>(null, playlistId, streamId) { value = graph.db.vod().movie(playlistId, streamId) }
    val details by produceState<XtreamDetails?>(null, playlist) { playlist?.let { value = graph.library.movieDetails(it, streamId) } }
    val key = LibraryRepository.movieKey(streamId)
    val progress by remember(playlistId) { graph.library.progressFor(playlistId, listOf(key)) }.collectAsStateWithLifecycle(emptyList())
    val m = movie ?: return
    val p = playlist ?: return
    val resumeAt = progress.firstOrNull()?.takeIf { !it.finished && it.positionMs > 0 }?.positionMs
    val d = details ?: XtreamDetails()
    // Looked up once the provider's details (with the year) are in, so the right film is found.
    val (tmdb, ratings) = onlineInfo(Kind.MOVIE, m.name.takeIf { details != null }, d.year?.toIntOrNull())
    DetailsLayout(
        title = m.name,
        poster = m.poster,
        backdrop = d.backdrop ?: graph.meta.image(tmdb?.backdrop, "w780"),
        parts = filmParts(d),
        rating = d.rating ?: m.rating,
        badges = ratingBadges(ratings),
        plot = d.plot ?: tmdb?.overview,
        credits = credits(d.director, d.cast),
        cast = tmdb?.cast.orEmpty(),
        onPerson = { nav.push(Screen.Person(playlistId, it)) },
        actions = { first ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (resumeAt != null) {
                    ActionButton(tf("Resume from %s", clock(resumeAt)), { nav.push(Screen.VodPlayer(graph.library.moviePlay(p, m, resumeAt))) }, first, primary = true, tag = "movie_resume")
                    ActionButton(t("From the start"), { nav.push(Screen.VodPlayer(graph.library.moviePlay(p, m, 0))) }, tag = "movie_play")
                } else {
                    ActionButton(t("Play"), { nav.push(Screen.VodPlayer(graph.library.moviePlay(p, m, 0))) }, first, primary = true, tag = "movie_play")
                }
            }
            ExtraButtons(playlistId, key, d.trailer, m.name)
        },
    )
}

/** A series: backdrop, details, Trailer / My list / Favorite, then its seasons and episodes (with where the viewer stopped). */
@Composable
fun SeriesDetailsScreen(playlistId: Long, seriesId: Long) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val playlist by produceState<PlaylistEntity?>(null, playlistId) { value = graph.playlists.get(playlistId) }
    val series by produceState<SeriesEntity?>(null, playlistId, seriesId) { value = graph.db.vod().seriesItem(playlistId, seriesId) }
    var info by remember { mutableStateOf<XtreamSeriesInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(playlist, attempt) {
        val p = playlist ?: return@LaunchedEffect
        failed = false
        runCatching { graph.library.seriesInfo(p, seriesId) }.onSuccess { info = it }.onFailure { failed = true }
    }
    // Opening the page counts as having seen what's new ("New episodes" goes away).
    LaunchedEffect(seriesId) { graph.library.markSeen(playlistId, seriesId) }
    val episodes = info?.episodes.orEmpty()
    val keys = remember(episodes) { episodes.map { LibraryRepository.episodeKey(it.id) } }
    val progress by remember(playlistId, keys) { graph.library.progressFor(playlistId, keys) }.collectAsStateWithLifecycle(emptyList())
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    val s = series ?: return
    val p = playlist ?: return
    val d = info?.details
    val (tmdb, ratings) = onlineInfo(Kind.TV, s.name, (s.year ?: d?.year)?.toIntOrNull())
    DetailsLayout(
        title = s.name,
        poster = s.cover,
        backdrop = s.backdrop ?: d?.backdrop ?: graph.meta.image(tmdb?.backdrop, "w780"),
        badges = ratingBadges(ratings),
        cast = tmdb?.cast.orEmpty(),
        onPerson = { nav.push(Screen.Person(playlistId, it)) },
        parts = listOfNotNull(s.year ?: d?.year, s.genre ?: d?.genre, if (seasons.isNotEmpty()) tf("%d seasons", seasons.size) else null),
        rating = s.rating ?: d?.rating,
        plot = s.plot ?: d?.plot ?: tmdb?.overview,
        credits = credits(s.director ?: d?.director, s.castNames ?: d?.cast),
        actions = { first ->
            ExtraButtons(playlistId, LibraryRepository.seriesKey(seriesId), s.trailer ?: d?.trailer, s.name)
            when {
                failed -> {
                    Text(t("The episodes couldn't be loaded."), style = TextStyle(color = Tokens.Danger, fontSize = Tokens.BodySize))
                    ActionButton(t("Try again"), { attempt++ }, first, tag = "series_retry")
                }
                info == null -> Text(t("Loading episodes…"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            }
        },
        below = if (info == null) null else ({ first -> Episodes(p, s, episodes, seasons, progress, first) }),
    )
}

/** TMDB's page for the title and its ratings (OMDb); both null without keys. */
@Composable
private fun onlineInfo(kind: Kind, name: String?, year: Int?): Pair<TmdbDetails?, Ratings?> {
    val graph = LocalGraph.current
    val tmdb by produceState<TmdbDetails?>(null, kind, name, year) { if (name != null) value = graph.meta.titleInfo(kind, name, year) }
    val ratings by produceState<Ratings?>(null, tmdb?.imdbId) { tmdb?.imdbId?.let { value = graph.meta.ratings(it) } }
    return tmdb to ratings
}

private fun ratingBadges(r: Ratings?) = listOfNotNull(r?.imdb?.let { "details_imdb" to "IMDb $it" }, r?.rottenTomatoes?.let { "details_rt" to "Rotten Tomatoes $it" })

/** The cast with their photos; OK opens a person's page. */
@Composable
private fun CastRow(cast: List<TmdbCast>, onPerson: (Long) -> Unit) {
    val graph = LocalGraph.current
    Text(t("Cast"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(cast.take(15), key = { _, c -> c.id }) { i, c ->
            FocusSurface(onClick = { onPerson(c.id) }, modifier = Modifier.width(100.dp).testTag("details_cast_$i"), shape = Tokens.SmallShape, scale = 1.05f) {
                Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Tokens.Raised), contentAlignment = Alignment.Center) {
                        Text(c.name.take(1), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
                        RemoteImage(graph.meta.image(c.photo, "w185"), Modifier.fillMaxSize())
                    }
                    Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
                    c.character?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize)) }
                }
            }
        }
    }
}

private fun credits(director: String?, cast: String?) =
    listOfNotNull(director?.let { tf("Director: %s", it) }, cast?.let { tf("Cast: %s", it) }).joinToString("\n")

/** Trailer (when the provider has one), My list and Favorite. */
@Composable
private fun ExtraButtons(playlistId: Long, itemKey: String, trailer: String?, title: String) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onList by remember(playlistId, itemKey) { graph.library.isMarked(playlistId, itemKey, LibraryRepository.MY_LIST) }.collectAsStateWithLifecycle(false)
    val favorite by remember(playlistId, itemKey) { graph.library.isMarked(playlistId, itemKey, LibraryRepository.FAVORITE) }.collectAsStateWithLifecycle(false)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (trailer != null) item { ActionButton(t("Trailer"), { Trailer.open(context, trailer, title) }, tag = "details_trailer", icon = Icons.Rounded.Movie) }
        item {
            ActionButton(
                if (onList) t("On my list") else t("My list"), { scope.launch { graph.library.toggleMark(playlistId, itemKey, LibraryRepository.MY_LIST) } },
                tag = "details_mylist", icon = if (onList) Icons.Rounded.Check else Icons.Rounded.Add,
            )
        }
        item {
            ActionButton(
                if (favorite) t("In favorites") else t("Favorite"), { scope.launch { graph.library.toggleMark(playlistId, itemKey, LibraryRepository.FAVORITE) } },
                tag = "details_favorite", icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            )
        }
    }
}

/** Seasons (a column on the TV, chips on a phone) and the chosen season's episodes; Go to episode for long seasons. */
@Composable
private fun Episodes(
    p: PlaylistEntity, s: SeriesEntity, episodes: List<XtreamEpisode>, seasons: List<Int>, progress: List<WatchProgressEntity>, first: Modifier,
) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val tv = LocalFormFactor.current == FormFactor.TV
    val byKey = progress.associateBy { it.itemKey }
    var season by remember(seasons) {
        // Open on the season of the episode watched last, otherwise the first.
        mutableIntStateOf(progress.maxByOrNull { it.updatedAt }?.let { last -> episodes.firstOrNull { LibraryRepository.episodeKey(it.id) == last.itemKey }?.season } ?: seasons.firstOrNull() ?: 1)
    }
    val inSeason = remember(episodes, season) { episodes.filter { it.season == season } }
    val requesters = remember(inSeason) { List(inSeason.size) { FocusRequester() } }
    var asking by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(target) {
        val n = target ?: return@LaunchedEffect
        val index = inSeason.indexOfFirst { it.episode == n }.takeIf { it >= 0 } ?: (n - 1).coerceIn(0, inSeason.lastIndex)
        requesters.getOrNull(index)?.let { focusSoon(it) }
        target = null
    }
    val list = @Composable { modifier: Modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (inSeason.size > 30) ActionButton(t("Go to episode"), { asking = true }, tag = "series_goto", icon = Icons.Rounded.Search)
            inSeason.forEachIndexed { i, e ->
                val prog = byKey[LibraryRepository.episodeKey(e.id)]
                val at = prog?.takeIf { !it.finished && it.positionMs > 0 }?.positionMs ?: 0
                EpisodeRow(e, prog, at, (if (i == 0) first else Modifier).focusRequester(requesters[i]).testTag("series_episode_$i")) {
                    nav.push(Screen.VodPlayer(graph.library.episodePlay(p, s.seriesId, s.name, e, s.cover, at)))
                }
            }
        }
    }
    if (tv && seasons.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                seasons.forEach { n ->
                    FocusSurface(
                        onClick = { season = n },
                        // On the TV, moving through the seasons shows each one's episodes.
                        modifier = Modifier.fillMaxWidth().testTag("series_season_$n").onFocusChanged { if (it.hasFocus) season = n },
                        shape = Tokens.SmallShape, scale = 1.02f, container = if (n == season) Tokens.Selected else Tokens.Surface,
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(tf("Season %d", n), style = TextStyle(color = if (n == season) Tokens.Cyan else Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                            Text(tf("%d episodes", episodes.count { it.season == n }), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                        }
                    }
                }
            }
            list(Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (seasons.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons, key = { it }) { n -> Chip(tf("Season %d", n), n == season, "series_season_$n") { season = n } }
                }
            }
            list(Modifier.fillMaxWidth())
        }
    }
    if (asking) {
        TextInputDialog(
            t("Go to episode"), t("Episode number"), "",
            onSave = { v -> asking = false; target = v.trim().toIntOrNull() },
            onCancel = { asking = false }, tag = "series_goto_dialog",
        )
    }
}

/** An episode: its picture, number and title, air date, length (or Watched), the start of its description. */
@Composable
private fun EpisodeRow(e: XtreamEpisode, prog: WatchProgressEntity?, at: Long, modifier: Modifier, onClick: () -> Unit) {
    val phone = LocalFormFactor.current == FormFactor.PHONE
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = Tokens.SmallShape, scale = 1.02f) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            e.image?.let {
                Box(Modifier.width(if (phone) 112.dp else 160.dp).aspectRatio(16f / 9f).background(Tokens.Raised, Tokens.SmallShape)) { RemoteImage(it, Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.episode}. ${e.title}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium))
                    val length = e.durationSec
                    when {
                        prog?.finished == true -> Text(t("Watched"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                        length != null -> Text(clock(length * 1000L), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                    }
                }
                e.airDate?.let { Text(it, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize)) }
                e.plot?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize)) }
                if (at > 0 && prog != null) ProgressLine((at.toFloat() / prog.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
            }
        }
    }
}

/**
 * A details page. With a backdrop: the picture fills the screen behind the text (TV and tablets) or heads the page
 * (phones); without one, the poster sits beside (or above) the text. [actions] get the requester for the first
 * button, [below] (episodes) too when it is there.
 */
@Composable
private fun DetailsLayout(
    title: String, poster: String?, backdrop: String?, parts: List<String>, rating: String?, plot: String?, credits: String,
    actions: @Composable (Modifier) -> Unit,
    below: (@Composable (Modifier) -> Unit)? = null,
    badges: List<Pair<String, String>> = emptyList(),
    cast: List<TmdbCast> = emptyList(),
    onPerson: (Long) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    // Again once the episodes arrive: they take the first focus.
    LaunchedEffect(below == null) { focusSoon(first) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("library_details")) {
        val narrow = maxWidth < 600.dp
        // The first focus goes to the episodes when there are some, otherwise to the first button.
        val firstAction = if (below == null) Modifier.focusRequester(first) else Modifier
        val firstBelow = Modifier.focusRequester(first)
        val text = @Composable {
            Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            MetaLine(parts, if (badges.any { it.first == "details_imdb" }) null else rating, badges)
            plot?.let { Text(it, maxLines = if (backdrop != null && !narrow) 4 else 6, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize)) }
            if (credits.isNotEmpty()) Text(credits, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            actions(firstAction)
            if (cast.isNotEmpty()) CastRow(cast, onPerson)
        }
        when {
            narrow -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (backdrop != null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tokens.Raised, Tokens.SmallShape).testTag("details_backdrop")) { RemoteImage(backdrop, Modifier.fillMaxSize()) }
                } else {
                    Box(Modifier.width(150.dp).aspectRatio(2f / 3f).background(Tokens.Raised, Tokens.SmallShape)) { RemoteImage(poster, Modifier.fillMaxSize()) }
                }
                text()
                below?.invoke(firstBelow)
            }
            backdrop != null -> {
                RemoteImage(backdrop, Modifier.fillMaxSize().testTag("details_backdrop"))
                // Dark on the left (where the text is) and at the bottom, so the text stays readable on any picture.
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tokens.Background.copy(alpha = 0.96f), 0.55f to Tokens.Background.copy(alpha = 0.75f), 1f to Tokens.Background.copy(alpha = 0.2f))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Tokens.Background.copy(alpha = 0f), 1f to Tokens.Background.copy(alpha = 0.9f))))
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Column(Modifier.fillMaxWidth(0.62f).padding(top = Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(10.dp)) { text() } }
                    below?.let { item { Box(Modifier.padding(bottom = Tokens.SafeMargin)) { it(firstBelow) } } }
                }
            }
            else -> Row(Modifier.padding(Tokens.SafeMargin), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.width(220.dp).aspectRatio(2f / 3f).background(Tokens.Raised, Tokens.SmallShape)) { RemoteImage(poster, Modifier.fillMaxSize()) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { text() } }
                    below?.let { item { it(firstBelow) } }
                }
            }
        }
    }
}
