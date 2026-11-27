package app.tiviate.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Text
import app.tiviate.core.xtream.XtreamDetails
import app.tiviate.data.AppSettings
import app.tiviate.data.db.MovieEntity
import app.tiviate.data.db.SeriesEntity
import app.tiviate.data.db.VodSort
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.LocalNavigator
import app.tiviate.ui.Screen
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.RemoteImage
import app.tiviate.ui.components.TvTextField
import app.tiviate.ui.phone.PhoneBottomBar
import app.tiviate.ui.phone.PhoneTab
import app.tiviate.ui.player.clock
import app.tiviate.ui.player.focusSoon
import app.tiviate.ui.t
import app.tiviate.ui.tf
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * "Show all" of a row: every film or series of a group (or all of them, or the most recently added films), as a
 * poster grid with group chips; on the TV, what the highlighted title is about beside it.
 */
@Composable
fun LibraryAllScreen(series: Boolean, category: String?, all: Boolean, newestFirst: Boolean = false) {
    val graph = LocalGraph.current
    val phone = LocalFormFactor.current == FormFactor.PHONE
    val settings by graph.settings.screenSettings.collectAsStateWithLifecycle(AppSettings())
    val pid = settings.activePlaylistId ?: return
    var chosen by rememberSaveable { mutableStateOf(category) }
    var everything by rememberSaveable { mutableStateOf(all) }
    var sort by rememberSaveable { mutableStateOf(if (newestFirst) VodSort.NEWEST else VodSort.PROVIDER) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val categories by remember(pid, series) { if (series) graph.db.vod().seriesCategories(pid) else graph.db.vod().movieCategories(pid) }
        .collectAsStateWithLifecycle(emptyList())
    // Series genres, most common first ("Drama, Crime" counts for both).
    val genreValues by remember(pid, series) { if (series) graph.db.vod().seriesGenreValues(pid) else kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val genres = remember(genreValues) {
        genreValues.flatMap { it.split(',', '/', '|') }.map { it.trim() }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(20).map { it.key }
    }
    val sortNames = listOf(t("As listed"), t("Newest"), t("A–Z"), t("Rating"))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    Column(Modifier.fillMaxSize().testTag("library_all_screen")) {
        Column(Modifier.weight(1f).padding(horizontal = Tokens.SafeMargin).padding(top = Tokens.SafeMargin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (everything) (if (series) t("Series") else t("Movies")) else chosen ?: t("Other"),
                style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "sort") { Chip(tf("Sort: %s", sortNames[sort]), sort != VodSort.PROVIDER, "library_sort") { sort = (sort + 1) % sortNames.size } }
                item(key = "all") {
                    Chip(t("All"), everything && sort != VodSort.NEWEST, "library_cat_all", Modifier.focusRequester(first)) {
                        everything = true; chosen = null; if (sort == VodSort.NEWEST) sort = VodSort.PROVIDER
                    }
                }
                if (!series) item(key = "recent") { Chip(t("Recently added"), everything && sort == VodSort.NEWEST, "library_cat_recent") { everything = true; chosen = null; sort = VodSort.NEWEST } }
                items(categories, key = { "c:" + it.name }) { c ->
                    Chip("${c.name ?: t("Other")}  ${c.count}", !everything && chosen == c.name, "library_cat_${c.name}") { everything = false; chosen = c.name }
                }
            }
            if (series && genres.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = "any") { Chip(t("Every genre"), genre == null, "library_genre_any") { genre = null } }
                    items(genres, key = { "g:$it" }) { g -> Chip(g, genre == g, "library_genre_$g") { genre = if (genre == g) null else g } }
                }
            }
            LibraryGrid(pid, series, everything, chosen, search = "", sort = sort, genre = genre, modifier = Modifier.weight(1f))
        }
        if (phone) PhoneBottomBar(PhoneTab.LIBRARY)
    }
}

/** Posters of films or series (a group, all, a search), paged; on the TV with the highlighted title's details beside them. */
@Composable
internal fun LibraryGrid(
    pid: Long, series: Boolean, all: Boolean, category: String?, search: String, sort: Int, genre: String? = null, modifier: Modifier = Modifier,
) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val tv = LocalFormFactor.current == FormFactor.TV
    val phone = LocalFormFactor.current == FormFactor.PHONE
    val movies = remember(pid, all, category, search, sort) { Pager(PagingConfig(pageSize = 60)) { graph.db.vod().movies(pid, all, category, search, sort) }.flow }.collectAsLazyPagingItems()
    val shows = remember(pid, all, category, search, sort, genre) { Pager(PagingConfig(pageSize = 60)) { graph.db.vod().series(pid, all, category, search, sort, genre) }.flow }.collectAsLazyPagingItems()
    val newEpisodes by remember(pid) { graph.db.vod().seriesWithNewEpisodes(pid) }.collectAsStateWithLifecycle(emptyList())
    // The highlighted poster (a film or a series) and the films' details, fetched once each.
    var highlighted by remember { mutableStateOf<Any?>(null) }
    val filmDetails = remember(pid) { mutableStateMapOf<Long, XtreamDetails>() }
    val highlightedFilm = (highlighted as? MovieEntity)?.streamId
    LaunchedEffect(highlightedFilm) {
        val id = highlightedFilm ?: return@LaunchedEffect
        if (!tv || id in filmDetails) return@LaunchedEffect
        delay(300) // only the title the viewer stops on, not each one passed
        val playlist = graph.playlists.get(pid) ?: return@LaunchedEffect
        filmDetails[id] = graph.library.movieDetails(playlist, id)
    }
    fun focusOn(item: Any?) = { focused: Boolean -> if (focused) highlighted = item else if (highlighted == item) highlighted = null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if ((if (series) shows.itemCount else movies.itemCount) == 0) {
            val empty = if (search.isNotEmpty()) t("No titles match this search.") else t("Nothing here yet. Movies and series load after the channels; check again in a minute.")
            Text(empty, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LazyVerticalGrid(
                // Posters stay shorter than the grid, so a highlighted one never needs to scroll itself into view.
                columns = GridCells.Adaptive(if (phone) 104.dp else 130.dp),
                modifier = Modifier.weight(1f).testTag("library_grid"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (series) {
                    items(shows.itemCount, key = shows.itemKey { "s${it.seriesId}" }) { i ->
                        val s = shows[i]
                        PosterCard(s?.name.orEmpty(), s?.cover, "library_item_$i", focusOn(s), newTag = s?.seriesId?.takeIf { it in newEpisodes }?.let { "library_new_$it" }) {
                            s?.let { nav.push(Screen.SeriesDetails(pid, it.seriesId)) }
                        }
                    }
                } else {
                    items(movies.itemCount, key = movies.itemKey { "m${it.streamId}" }) { i ->
                        val m = movies[i]
                        PosterCard(m?.name.orEmpty(), m?.poster, "library_item_$i", focusOn(m)) { m?.let { nav.push(Screen.MovieDetails(pid, it.streamId)) } }
                    }
                }
            }
            if (tv) InfoPanel(highlighted, (highlighted as? MovieEntity)?.let { filmDetails[it.streamId] })
        }
    }
}

@Composable
internal fun SearchField(query: String, onChange: (String) -> Unit) =
    TvTextField("", query, onChange, tag = "library_search", hint = t("Film or series name…"), keyboardType = KeyboardType.Text)

/** What the highlighted film or series is about (TV): name, year, genre, rating and the description. */
@Composable
private fun InfoPanel(item: Any?, film: XtreamDetails?) {
    Column(Modifier.width(300.dp).fillMaxHeight().testTag("library_info"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (item) {
            is MovieEntity -> TitleInfo(item.name, filmParts(film ?: XtreamDetails()), film?.rating ?: item.rating, film?.plot, 12)
            is SeriesEntity -> TitleInfo(item.name, seriesParts(item), item.rating, item.plot, 12)
        }
    }
}

/** A title's name, its facts (year, genre, length), its rating and the start of its description. */
@Composable
internal fun TitleInfo(name: String, parts: List<String>, rating: String?, plot: String?, plotLines: Int, big: Boolean = false) {
    Text(
        name, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = Tokens.Text, fontSize = if (big) Tokens.TitleSize * 1.3f else Tokens.HeadingSize, fontWeight = FontWeight.SemiBold),
    )
    MetaLine(parts, rating)
    plot?.let { Text(it, maxLines = plotLines, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize)) }
}

/** Facts side by side with room between them, and the rating in a small pill (no dots or separators). */
@Composable
internal fun MetaLine(parts: List<String>, rating: String?, badges: List<Pair<String, String>> = emptyList()) {
    val shownRating = rating?.trim()?.takeIf { r -> r.isNotEmpty() && r.toDoubleOrNull()?.let { it > 0 } != false }
    if (parts.isEmpty() && shownRating == null && badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEach { Text(it, maxLines = 1, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium)) }
        shownRating?.let {
            Text(
                "★ $it", Modifier.background(Tokens.Raised, Tokens.PillShape).padding(horizontal = 10.dp, vertical = 3.dp),
                style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold),
            )
        }
        // IMDb / Rotten Tomatoes (with the viewer's keys).
        badges.forEach { (tag, text) ->
            Text(
                text, Modifier.testTag(tag).background(Tokens.Raised, Tokens.PillShape).padding(horizontal = 10.dp, vertical = 3.dp),
                style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

internal fun filmParts(d: XtreamDetails) = listOfNotNull(d.year, d.genre, d.durationSec?.let { clock(it * 1000L) })

internal fun seriesParts(s: SeriesEntity) = listOfNotNull(s.year, s.genre)

@Composable
internal fun Chip(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick, modifier = modifier.testTag(tag), shape = Tokens.PillShape, scale = 1.04f,
        container = if (selected) Tokens.Selected else Tokens.Surface,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), maxLines = 1, style = TextStyle(color = if (selected) Tokens.Cyan else Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
    }
}

/** A poster with its title under it; [newTag] marks a series with new episodes. [width] fixes its size in a row. */
@Composable
internal fun PosterCard(
    title: String, poster: String?, tag: String, onFocus: (Boolean) -> Unit = {}, newTag: String? = null, width: Dp? = null, rank: Int? = null,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick, modifier = (if (width != null) Modifier.width(width) else Modifier).testTag(tag).onFocusChanged { onFocus(it.hasFocus) },
        shape = Tokens.SmallShape, scale = 1.05f,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Tokens.Raised), contentAlignment = Alignment.Center) {
                Text(title.take(1), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.TitleSize))
                RemoteImage(poster, Modifier.fillMaxSize())
                // Top 10: its place, large, in the corner.
                if (rank != null) {
                    Text(
                        "$rank", Modifier.align(Alignment.BottomStart).padding(6.dp).background(Tokens.Background.copy(alpha = 0.85f), Tokens.SmallShape).padding(horizontal = 10.dp),
                        style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.TitleSize, fontWeight = FontWeight.Bold),
                    )
                }
                // A series with episodes added since the viewer last watched or opened it.
                if (newTag != null) {
                    Text(
                        t("New episodes"),
                        Modifier.align(Alignment.TopStart).padding(6.dp).background(Tokens.Cyan, Tokens.PillShape).padding(horizontal = 8.dp, vertical = 3.dp).testTag(newTag),
                        style = TextStyle(color = Tokens.Background, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
            Text(
                title, maxLines = if (width != null) 1 else 2, minLines = if (width != null) 1 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp), style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize),
            )
        }
    }
}
