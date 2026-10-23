package app.tiviate.ui.guide

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tiviate.AppGraph
import app.tiviate.data.Category
import app.tiviate.data.db.CategoryRow
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import app.tiviate.data.db.ProgrammeEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(private val graph: AppGraph) : ViewModel() {

    private val repo = graph.channels

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist: StateFlow<PlaylistEntity?> = _playlist

    private val _category = MutableStateFlow<Category>(Category.All)
    val category: StateFlow<Category> = _category

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** Row of the playing channel when the guide opened. */
    var initialRow = 0
        private set

    val groups: StateFlow<List<CategoryRow>> = _playlist.filterNotNull()
        .flatMapLatest { repo.groups(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val channels: Flow<PagingData<ChannelEntity>> = combine(_playlist, _category) { p, c -> p to c }
        .flatMapLatest { (p, c) -> if (p == null) emptyFlow() else repo.page(p.id, c) }
        .cachedIn(viewModelScope)

    /** Programmes per guide reference, loaded for the rows on screen and the window around the view. */
    val programmes = mutableStateMapOf<String, List<ProgrammeEntity>>()
    private val loadedFrom = HashMap<String, Long>()
    private val loadedTo = HashMap<String, Long>()

    /** Called each time the guide opens: picks up the active playlist, group and playing channel. */
    fun open() {
        _ready.value = false
        viewModelScope.launch {
            val settings = graph.settings.settings.first()
            val pid = settings.activePlaylistId ?: return@launch
            val p = graph.playlists.get(pid) ?: return@launch
            _category.value = settings.lastGroup?.let { Category.Group(it) } ?: Category.All
            _playlist.value = p
            // Nothing playing yet (fresh start): put the last watched, or first, channel in the preview.
            if (graph.player.current.value == null || graph.player.engine.state.value.channelKey == null) {
                val channel = settings.lastChannelKey?.let { repo.byKey(pid, it) } ?: repo.first(pid, _category.value) ?: repo.first(pid, Category.All)
                channel?.let { graph.player.tune(it, p.userAgent) }
            }
            initialRow = graph.player.current.value?.let { repo.positionOf(pid, _category.value, it) } ?: 0
            _ready.value = true
        }
    }

    init {
        // A guide refresh swapped in new programmes: reload what is on screen, in place.
        viewModelScope.launch {
            repo.guideVersion().drop(1).collect {
                for ((window, refs) in loadedFrom.keys.groupBy { loadedFrom.getValue(it) to loadedTo.getValue(it) }) {
                    val loaded = repo.programmes(refs, window.first, window.second)
                    for (ref in refs) programmes[ref] = loaded[ref].orEmpty()
                }
            }
        }
    }

    /** Loads programmes for [refs] so that [fromMs, toMs] is covered, with a margin for scrolling. */
    fun ensureProgrammes(refs: Collection<String>, fromMs: Long, toMs: Long) {
        val need = refs.filter { ref -> (loadedFrom[ref] ?: Long.MAX_VALUE) > fromMs || (loadedTo[ref] ?: Long.MIN_VALUE) < toMs }
        if (need.isEmpty()) return
        val from = fromMs - 2 * HOUR
        val to = toMs + 4 * HOUR
        need.forEach { loadedFrom[it] = from; loadedTo[it] = to }
        viewModelScope.launch {
            val loaded = repo.programmes(need, from, to)
            for (ref in need) programmes[ref] = loaded[ref].orEmpty()
        }
    }

    fun selectCategory(c: Category) {
        _category.value = c
        initialRow = 0
        viewModelScope.launch { graph.settings.update { it.copy(lastGroup = (c as? Category.Group)?.name) } }
    }

    fun tune(channel: ChannelEntity) = graph.player.tune(channel, _playlist.value?.userAgent)

    companion object {
        private const val HOUR = 3_600_000L
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory { initializer { GuideViewModel(graph) } }
    }
}
