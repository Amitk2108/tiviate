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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import app.tiviate.core.xtream.XtreamDetails
import app.tiviate.core.xtream.XtreamSeriesInfo
import app.tiviate.data.AppSettings
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
import app.tiviate.ui.phone.PhoneBottomBar
import app.tiviate.ui.phone.PhoneTab
import app.tiviate.ui.player.clock
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.launch

/** Movies & Series of the active (Xtream) playlist: Continue watching, groups and a poster grid. */
@Composable
fun LibraryScreen() {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val tv = LocalFormFactor.current == FormFactor.TV
    val phone = !tv && LocalFormFactor.current == FormFactor.PHONE
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    val pid = settings.activePlaylistId ?: return
    var seriesTab by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var all by rememberSaveable { mutableStateOf(true) }
    val categories by remember(pid, seriesTab) { if (seriesTab) graph.db.vod().seriesCategories(pid) else graph.db.vod().movieCategories(pid) }
        .collectAsStateWithLifecycle(emptyList())
    val resume by remember(pid) { graph.library.continueWatching(pid) }.collectAsStateWithLifecycle(emptyList())
    val movies = remember(pid, all, category) { Pager(PagingConfig(pageSize = 60)) { graph.db.vod().movies(pid, all, category, "") }.flow }.collectAsLazyPagingItems()
    val series = remember(pid, all, category) { Pager(PagingConfig(pageSize = 60)) { graph.db.vod().series(pid, all, category, "") }.flow }.collectAsLazyPagingItems()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    val scope = rememberCoroutineScope()

    fun openResume(p: WatchProgressEntity) {
        if (p.seriesId != null) { nav.push(Screen.SeriesDetails(pid, p.seriesId)); return }
        val streamId = p.itemKey.removePrefix("movie:").toLongOrNull() ?: return
        scope.launch {
            val playlist = graph.playlists.get(pid) ?: return@launch
            val movie = graph.db.vod().movie(pid, streamId) ?: return@launch
            nav.push(Screen.VodPlayer(graph.library.moviePlay(playlist, movie, p.positionMs)))
        }
    }

    Column(Modifier.fillMaxSize().testTag("library_screen")) {
        Column(Modifier.weight(1f).padding(horizontal = Tokens.SafeMargin).padding(top = Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!phone) Text(t("Movies & Series"), modifier = Modifier.padding(end = 12.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
                Chip(t("Movies"), !seriesTab, "library_tab_movies", Modifier.focusRequester(first)) { seriesTab = false; all = true; category = null }
                Chip(t("Series"), seriesTab, "library_tab_series") { seriesTab = true; all = true; category = null }
            }
            if (resume.isNotEmpty()) {
                Text(t("Continue watching"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(resume, key = { _, p -> p.itemKey }) { i, p ->
                        FocusSurface(onClick = { openResume(p) }, modifier = Modifier.width(if (phone) 200.dp else 260.dp).testTag("library_resume_$i"), shape = Tokens.SmallShape) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
                                ProgressLine((p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
                                Text(tf("%s left", clock(p.durationMs - p.positionMs)), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                            }
                        }
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") { Chip(t("All"), all, "library_cat_all") { all = true; category = null } }
                items(categories, key = { "c:" + it.name }) { c ->
                    Chip("${c.name ?: t("Other")}  ${c.count}", !all && category == c.name, "library_cat_${c.name}") { all = false; category = c.name }
                }
            }
            val count = if (seriesTab) series.itemCount else movies.itemCount
            if (count == 0) {
                Text(t("Nothing here yet. Movies and series load after the channels; check again in a minute."), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (phone) 104.dp else 150.dp),
                modifier = Modifier.weight(1f).testTag("library_grid"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (seriesTab) {
                    items(series.itemCount, key = series.itemKey { "s${it.seriesId}" }) { i ->
                        val s = series[i]
                        PosterCard(s?.name.orEmpty(), s?.cover, "library_item_$i") { s?.let { nav.push(Screen.SeriesDetails(pid, it.seriesId)) } }
                    }
                } else {
                    items(movies.itemCount, key = movies.itemKey { "m${it.streamId}" }) { i ->
                        val m = movies[i]
                        PosterCard(m?.name.orEmpty(), m?.poster, "library_item_$i") { m?.let { nav.push(Screen.MovieDetails(pid, it.streamId)) } }
                    }
                }
            }
        }
        if (phone) PhoneBottomBar(PhoneTab.LIBRARY)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, modifier = modifier.testTag(tag), shape = Tokens.PillShape, scale = 1.04f,
        container = if (selected) Tokens.Selected else Tokens.Surface,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), maxLines = 1, style = TextStyle(color = if (selected) Tokens.Cyan else Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun PosterCard(title: String, poster: String?, tag: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.testTag(tag), shape = Tokens.SmallShape, scale = 1.05f) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Tokens.Raised), contentAlignment = Alignment.Center) {
                Text(title.take(1), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.TitleSize))
                RemoteImage(poster, Modifier.fillMaxSize())
            }
            Text(title, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize))
        }
    }
}

/** A film: poster, details and Play / Resume. */
@Composable
fun MovieDetailsScreen(playlistId: Long, streamId: Long) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val playlist by produceState<PlaylistEntity?>(null, playlistId) { value = graph.playlists.get(playlistId) }
    val movie by produceState<MovieEntity?>(null, playlistId, streamId) { value = graph.db.vod().movie(playlistId, streamId) }
    val details by produceState(XtreamDetails(), playlist) { playlist?.let { value = graph.library.movieDetails(it, streamId) } }
    val key = LibraryRepository.movieKey(streamId)
    val progress by remember(playlistId) { graph.library.progressFor(playlistId, listOf(key)) }.collectAsStateWithLifecycle(emptyList())
    val m = movie ?: return
    val p = playlist ?: return
    val resumeAt = progress.firstOrNull()?.takeIf { !it.finished && it.positionMs > 0 }?.positionMs
    DetailsLayout(
        title = m.name,
        poster = m.poster,
        meta = listOfNotNull(details.year, details.genre, details.durationSec?.let { clock(it * 1000L) }, (details.rating ?: m.rating)?.let { "★ $it" }).joinToString("  ·  "),
        plot = details.plot,
        credits = listOfNotNull(details.director?.let { tf("Director: %s", it) }, details.cast?.let { tf("Cast: %s", it) }).joinToString("\n"),
    ) { first ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (resumeAt != null) {
                ActionButton(tf("Resume from %s", clock(resumeAt)), { nav.push(Screen.VodPlayer(graph.library.moviePlay(p, m, resumeAt))) }, first, primary = true, tag = "movie_resume")
                ActionButton(t("From the start"), { nav.push(Screen.VodPlayer(graph.library.moviePlay(p, m, 0))) }, tag = "movie_play")
            } else {
                ActionButton(t("Play"), { nav.push(Screen.VodPlayer(graph.library.moviePlay(p, m, 0))) }, first, primary = true, tag = "movie_play")
            }
        }
    }
}

/** A series: details, seasons and episodes (with where the viewer stopped). */
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
    val episodes = info?.episodes.orEmpty()
    val keys = remember(episodes) { episodes.map { LibraryRepository.episodeKey(it.id) } }
    val progress by remember(playlistId, keys) { graph.library.progressFor(playlistId, keys) }.collectAsStateWithLifecycle(emptyList())
    val byKey = progress.associateBy { it.itemKey }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var season by remember(seasons) {
        // Open on the season of the episode watched last, otherwise the first.
        mutableIntStateOf(progress.maxByOrNull { it.updatedAt }?.let { last -> episodes.firstOrNull { LibraryRepository.episodeKey(it.id) == last.itemKey }?.season } ?: seasons.firstOrNull() ?: 1)
    }
    val s = series ?: return
    val p = playlist ?: return
    val d = info?.details
    DetailsLayout(
        title = s.name,
        poster = s.cover,
        meta = listOfNotNull(s.year ?: d?.year, s.genre ?: d?.genre, (s.rating ?: d?.rating)?.let { "★ $it" }, if (seasons.isNotEmpty()) tf("%d seasons", seasons.size) else null).joinToString("  ·  "),
        plot = s.plot ?: d?.plot,
        credits = d?.cast?.let { tf("Cast: %s", it) }.orEmpty(),
    ) { first ->
        when {
            failed -> {
                Text(t("The episodes couldn't be loaded."), style = TextStyle(color = Tokens.Danger, fontSize = Tokens.BodySize))
                ActionButton(t("Try again"), { attempt++ }, first, tag = "series_retry")
            }
            info == null -> Text(t("Loading episodes…"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons, key = { it }) { n -> Chip(tf("Season %d", n), n == season, "series_season_$n") { season = n } }
                    }
                }
                episodes.filter { it.season == season }.forEachIndexed { i, e ->
                    val prog = byKey[LibraryRepository.episodeKey(e.id)]
                    val at = prog?.takeIf { !it.finished && it.positionMs > 0 }?.positionMs ?: 0
                    FocusSurface(
                        onClick = { nav.push(Screen.VodPlayer(graph.library.episodePlay(p, seriesId, s.name, e, s.cover, at))) },
                        modifier = (if (i == 0) first else Modifier).fillMaxWidth().testTag("series_episode_$i"),
                        shape = Tokens.SmallShape, scale = 1.02f,
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${e.episode}", modifier = Modifier.width(28.dp), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                                Text(e.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize))
                                val length = e.durationSec
                                when {
                                    prog?.finished == true -> Text(t("Watched"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                                    length != null -> Text(clock(length * 1000L), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                                }
                            }
                            if (at > 0 && prog != null) ProgressLine((at.toFloat() / prog.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }
}

/** Poster beside the details on TV and tablets; above them on phones. [actions] gets the requester for the first button. */
@Composable
private fun DetailsLayout(title: String, poster: String?, meta: String, plot: String?, credits: String, actions: @Composable (Modifier) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("library_details")) {
        val narrow = maxWidth < 600.dp
        val text = @Composable {
            Text(title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold))
            if (meta.isNotEmpty()) Text(meta, style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize))
            plot?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize)) }
            if (credits.isNotEmpty()) Text(credits, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            actions(Modifier.focusRequester(first))
        }
        if (narrow) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(150.dp).aspectRatio(2f / 3f).background(Tokens.Raised, Tokens.SmallShape)) { RemoteImage(poster, Modifier.fillMaxSize()) }
                text()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.width(220.dp).aspectRatio(2f / 3f).background(Tokens.Raised, Tokens.SmallShape)) { RemoteImage(poster, Modifier.fillMaxSize()) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { text() } } }
            }
        }
    }
}
