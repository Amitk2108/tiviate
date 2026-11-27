package app.tiviate.ui.library

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.tiviate.core.xtream.XtreamDetails
import app.tiviate.data.AppSettings
import app.tiviate.data.LibraryRepository
import app.tiviate.data.MetaRepository
import app.tiviate.core.meta.Kind
import androidx.compose.runtime.produceState
import app.tiviate.data.db.MovieEntity
import app.tiviate.data.db.SeriesEntity
import app.tiviate.data.db.VodSort
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
import app.tiviate.ui.theme.ThemeState
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Movies & Series home: a large picture of one title at the top (it follows the highlighted poster; until then it
 * turns through recent titles), then rows: Continue watching, Recently added, My list, Favorites and one per group.
 * Typing a search shows the matching posters instead.
 */
@Composable
fun LibraryScreen() {
    val graph = LocalGraph.current
    val tv = LocalFormFactor.current == FormFactor.TV
    val phone = !tv && LocalFormFactor.current == FormFactor.PHONE
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    val pid = settings.activePlaylistId ?: return
    var seriesTab by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    var heroImage by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }

    Box(Modifier.fillMaxSize().testTag("library_screen")) {
        // The highlighted title's picture, behind the top of the screen, fading into the background.
        if (!phone && search.isEmpty()) {
            Crossfade(heroImage, animationSpec = if (ThemeState.animations) tween(450) else snap(), label = "hero") { image ->
                if (image != null) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
                        RemoteImage(image, Modifier.fillMaxSize().testTag("library_hero_image"))
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tokens.Background, 0.38f to Tokens.Background.copy(alpha = 0.85f), 1f to Tokens.Background.copy(alpha = 0.15f))))
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Tokens.Background.copy(alpha = 0f), 1f to Tokens.Background)))
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(horizontal = Tokens.SafeMargin).padding(top = Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!phone) Text(t("Movies & Series"), modifier = Modifier.padding(end = 12.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold))
                    Chip(t("Movies"), !seriesTab, "library_tab_movies", Modifier.focusRequester(first)) { seriesTab = false }
                    Chip(t("Series"), seriesTab, "library_tab_series") { seriesTab = true }
                    if (!phone) {
                        Box(Modifier.weight(1f))
                        Box(Modifier.width(320.dp)) { SearchField(query) { query = it } }
                    }
                }
                if (phone) SearchField(query) { query = it }
                if (search.isNotEmpty()) {
                    LibraryGrid(pid, seriesTab, all = true, category = null, search = search, sort = VodSort.PROVIDER, modifier = Modifier.weight(1f))
                } else {
                    HomeRows(pid, seriesTab, onHeroImage = { heroImage = it }, modifier = Modifier.weight(1f))
                }
            }
            if (phone) PhoneBottomBar(PhoneTab.LIBRARY)
        }
    }
}

/** The hero and the rows of the Movies or the Series tab. */
@Composable
private fun HomeRows(pid: Long, series: Boolean, onHeroImage: (String?) -> Unit, modifier: Modifier) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val phone = LocalFormFactor.current == FormFactor.PHONE
    val vod = graph.db.vod()
    val resume by remember(pid) { graph.library.continueWatching(pid) }.collectAsStateWithLifecycle(emptyList())
    val recent by remember(pid, series) { if (series) flowOf(emptyList()) else vod.recentMovies(pid, 20) }.collectAsStateWithLifecycle(emptyList())
    val myList by remember(pid, series) { if (series) vod.markedSeries(pid, LibraryRepository.MY_LIST) else vod.markedMovies(pid, LibraryRepository.MY_LIST) }.collectAsStateWithLifecycle(emptyList())
    val favorites by remember(pid, series) { if (series) vod.markedSeries(pid, LibraryRepository.FAVORITE) else vod.markedMovies(pid, LibraryRepository.FAVORITE) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember(pid, series) { if (series) vod.seriesCategories(pid) else vod.movieCategories(pid) }.collectAsStateWithLifecycle(emptyList())
    val heroSeries by remember(pid, series) { if (series) vod.heroSeries(pid, 6) else flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val newEpisodes by remember(pid) { vod.seriesWithNewEpisodes(pid) }.collectAsStateWithLifecycle(emptyList())
    val resumeHere = remember(resume, series) { resume.filter { (it.seriesId != null) == series } }
    // With a TMDB key: today's most watched that this playlist has, and more like the title watched last.
    val services by remember { graph.meta.services }.collectAsStateWithLifecycle(emptySet())
    val online = MetaRepository.Service.TMDB in services
    val kind = if (series) Kind.TV else Kind.MOVIE
    val top10 by produceState(emptyList<Any>(), pid, series, online) {
        value = if (online) graph.library.findInLibrary(pid, graph.meta.trending().filter { it.kind == kind }).take(10) else emptyList()
    }
    val lastWatched = resumeHere.firstOrNull()
    val because by produceState<Pair<String, List<Any>>?>(null, pid, series, online, lastWatched?.itemKey) {
        value = null
        val w = lastWatched ?: return@produceState
        if (!online) return@produceState
        val name = if (w.seriesId != null) vod.seriesItem(pid, w.seriesId)?.name else w.title
        val info = name?.let { graph.meta.titleInfo(kind, it, null) } ?: return@produceState
        val found = graph.library.findInLibrary(pid, info.recommendations)
        if (found.isNotEmpty()) value = name to found
    }

    // The hero: the highlighted title; until one is highlighted, recent titles in turn (every 8 s, not without animations).
    var highlighted by remember(series) { mutableStateOf<Any?>(null) }
    val pool: List<Any> = if (series) heroSeries else recent.take(6)
    var turn by remember(series) { mutableIntStateOf(0) }
    LaunchedEffect(pool.size, highlighted == null, ThemeState.animations) {
        if (highlighted != null || pool.size < 2 || !ThemeState.animations) return@LaunchedEffect
        while (true) { delay(8_000); turn++ }
    }
    val shown = highlighted ?: pool.getOrNull(turn % pool.size.coerceAtLeast(1))
    val filmDetails = remember(pid) { mutableStateMapOf<Long, XtreamDetails>() }
    val shownFilm = (shown as? MovieEntity)?.streamId
    LaunchedEffect(shownFilm) {
        val id = shownFilm ?: return@LaunchedEffect
        if (id in filmDetails) return@LaunchedEffect
        delay(300) // only the title the viewer stops on
        val playlist = graph.playlists.get(pid) ?: return@LaunchedEffect
        filmDetails[id] = graph.library.movieDetails(playlist, id)
    }
    val image = when (shown) {
        is MovieEntity -> filmDetails[shown.streamId]?.backdrop
        is SeriesEntity -> shown.backdrop
        else -> null
    }
    LaunchedEffect(image) { onHeroImage(image) }
    val highlight = { item: Any -> highlighted = item }

    BoxWithConstraints(modifier) {
        // Rows stay one poster row high at least; the hero takes what is left (and drops its description first).
        val card: Dp = if (phone) 104.dp else if (maxHeight < 380.dp) 84.dp else 100.dp
        val rowHeight = card * 1.5f + 64.dp
        val heroHeight = if (phone) 0.dp else (maxHeight - rowHeight - 12.dp).coerceIn(0.dp, 300.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!phone && shown != null && heroHeight >= 110.dp) Hero(pid, shown, filmDetails, Modifier.height(heroHeight), full = heroHeight >= 190.dp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library_rows"), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (phone && shown != null) item(key = "hero") { Hero(pid, shown, filmDetails, Modifier.fillMaxWidth(), full = true) }
                if (resumeHere.isNotEmpty()) item(key = "resume") { ResumeRow(pid, resumeHere, if (phone) 200.dp else card * 2.2f) }
                if (top10.isNotEmpty()) item(key = "top10") { TitleRow(t("Top 10 today"), "top10", top10, card, newEpisodes, highlight, ranked = true, showAll = null) }
                because?.let { (name, titles) ->
                    item(key = "because") { TitleRow(tf("Because you watched %s", name), "because", titles, card, newEpisodes, highlight, showAll = null) }
                }
                if (recent.isNotEmpty()) item(key = "recent") {
                    TitleRow(t("Recently added"), "recent", recent, card, newEpisodes, highlight) { nav.push(Screen.LibraryAll(series = false, category = null, all = true, newest = true)) }
                }
                if (myList.isNotEmpty()) item(key = "mylist") { TitleRow(t("My list"), "mylist", myList, card, newEpisodes, highlight, showAll = null) }
                if (favorites.isNotEmpty()) item(key = "favorites") { TitleRow(t("Favorites"), "favorites", favorites, card, newEpisodes, highlight, showAll = null) }
                items(categories, key = { "c:" + it.name }) { c ->
                    // Each group's row asks for its own first posters only when it comes into view.
                    val titles by remember(pid, series, c.name) { if (series) vod.seriesIn(pid, c.name, 20) else vod.moviesIn(pid, c.name, 20) }
                        .collectAsStateWithLifecycle(null)
                    when {
                        titles == null -> Box(Modifier.fillMaxWidth().height(card * 1.5f + 100.dp)) // loading: the row's room
                        titles!!.isNotEmpty() -> TitleRow(c.name ?: t("Other"), c.name ?: "other", titles!!, card, newEpisodes, highlight) {
                            nav.push(Screen.LibraryAll(series = series, category = c.name, all = false))
                        }
                    }
                }
            }
        }
    }
}

/** One title large: its name, facts, rating, description (when there is room) and Watch now / More info. */
@Composable
private fun Hero(pid: Long, shown: Any, filmDetails: Map<Long, XtreamDetails>, modifier: Modifier, full: Boolean) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val phone = LocalFormFactor.current == FormFactor.PHONE
    fun details() = when (shown) {
        is MovieEntity -> nav.push(Screen.MovieDetails(pid, shown.streamId))
        is SeriesEntity -> nav.push(Screen.SeriesDetails(pid, shown.seriesId))
        else -> Unit
    }
    fun watch() {
        if (shown is SeriesEntity) {
            scope.launch {
                val playlist = graph.playlists.get(pid) ?: return@launch
                val play = runCatching { graph.library.seriesPlay(playlist, shown) }.getOrNull()
                if (play != null) nav.push(Screen.VodPlayer(play)) else details()
            }
            return
        }
        val film = shown as? MovieEntity ?: return
        scope.launch {
            val playlist = graph.playlists.get(pid) ?: return@launch
            val at = graph.db.vod().progress(pid, LibraryRepository.movieKey(film.streamId))?.takeIf { !it.finished }?.positionMs ?: 0
            nav.push(Screen.VodPlayer(graph.library.moviePlay(playlist, film, at)))
        }
    }
    Column(modifier.fillMaxWidth(if (phone) 1f else 0.58f).testTag("library_hero"), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
        if (phone) {
            val image = when (shown) { is MovieEntity -> filmDetails[shown.streamId]?.backdrop; is SeriesEntity -> shown.backdrop; else -> null }
            if (image != null) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tokens.Raised, Tokens.SmallShape)) { RemoteImage(image, Modifier.fillMaxSize()) }
        }
        when (shown) {
            is MovieEntity -> {
                val d = filmDetails[shown.streamId] ?: XtreamDetails()
                TitleInfo(shown.name, filmParts(d), d.rating ?: shown.rating, if (full) d.plot else null, 2, big = true)
            }
            is SeriesEntity -> TitleInfo(shown.name, seriesParts(shown), shown.rating, if (full) shown.plot else null, 2, big = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(t("Watch now"), ::watch, primary = true, tag = "hero_play", icon = Icons.Rounded.PlayArrow)
            ActionButton(t("More info"), ::details, tag = "hero_info", icon = Icons.Rounded.Info)
        }
    }
}

/** A row of posters with its name; the last card opens all of them ([showAll], when there are more). */
@Composable
private fun TitleRow(
    name: String, key: String, titles: List<Any>, card: Dp, newEpisodes: List<Long>, onHighlight: (Any) -> Unit, ranked: Boolean = false,
    showAll: (() -> Unit)?,
) {
    val nav = LocalNavigator.current
    val settings by LocalGraph.current.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    val pid = settings.activePlaylistId ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
        LazyRow(Modifier.testTag("library_row_$key"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(titles, key = { _, it -> if (it is MovieEntity) "m${it.streamId}" else "s${(it as SeriesEntity).seriesId}" }) { i, item ->
                val tag = "library_row_${key}_item_$i"
                when (item) {
                    is MovieEntity -> PosterCard(item.name, item.poster, tag, { if (it) onHighlight(item) }, width = card, rank = if (ranked) i + 1 else null) {
                        nav.push(Screen.MovieDetails(pid, item.streamId))
                    }
                    is SeriesEntity -> PosterCard(
                        item.name, item.cover, tag, { if (it) onHighlight(item) },
                        newTag = item.seriesId.takeIf { it in newEpisodes }?.let { "library_new_$it" }, width = card, rank = if (ranked) i + 1 else null,
                    ) { nav.push(Screen.SeriesDetails(pid, item.seriesId)) }
                }
            }
            if (showAll != null && titles.size >= 20) item(key = "all") {
                FocusSurface(onClick = showAll, modifier = Modifier.width(card).aspectRatio(2f / 3f).testTag("library_all_$key"), shape = Tokens.SmallShape, scale = 1.05f) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(t("Show all"), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
        if (showAll != null && titles.size < 20) {
            // Few titles: the row shows them all, so "Show all" is only the group's grid (with its chips).
            FocusSurface(onClick = showAll, modifier = Modifier.testTag("library_all_$key"), shape = Tokens.PillShape, scale = 1.04f) {
                Text(t("Show all"), Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
            }
        }
    }
}

/** Continue watching: wide cards with the picture, the title and how much is left. */
@Composable
private fun ResumeRow(pid: Long, resume: List<WatchProgressEntity>, cardWidth: Dp) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val phone = LocalFormFactor.current == FormFactor.PHONE
    fun open(p: WatchProgressEntity) {
        if (p.seriesId != null) { nav.push(Screen.SeriesDetails(pid, p.seriesId)); return }
        val streamId = p.itemKey.removePrefix("movie:").toLongOrNull() ?: return
        scope.launch {
            val playlist = graph.playlists.get(pid) ?: return@launch
            val movie = graph.db.vod().movie(pid, streamId) ?: return@launch
            nav.push(Screen.VodPlayer(graph.library.moviePlay(playlist, movie, p.positionMs)))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("Continue watching"), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(resume, key = { _, p -> p.itemKey }) { i, p ->
                FocusSurface(onClick = { open(p) }, modifier = Modifier.width(cardWidth).testTag("library_resume_$i"), shape = Tokens.SmallShape) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tokens.Raised)) { RemoteImage(p.poster, Modifier.fillMaxSize()) }
                        ProgressLine((p.positionMs.toFloat() / p.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f))
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
                            Text(tf("%s left", clock(p.durationMs - p.positionMs)), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
                        }
                    }
                }
            }
        }
    }
}
