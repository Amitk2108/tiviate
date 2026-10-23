package app.tiviate.ui.player

import android.os.SystemClock
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
import app.tiviate.data.NowNext
import app.tiviate.data.db.CategoryRow
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.PlaylistEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which overlay sits on top of the video. Only one at a time keeps focus handling simple. */
sealed interface Overlay {
    data object None : Overlay
    data object Banner : Overlay
    data class Panel(val categoriesFocused: Boolean) : Overlay
    data object QuickActions : Overlay
    data class Tracks(val audio: Boolean) : Overlay
    data object TouchControls : Overlay
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val graph: AppGraph) : ViewModel() {

    private val controller = graph.player
    private val repo = graph.channels

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist: StateFlow<PlaylistEntity?> = _playlist

    val playlists: StateFlow<List<PlaylistEntity>> = graph.playlists.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _category = MutableStateFlow<Category>(Category.All)
    val category: StateFlow<Category> = _category

    private val _overlay = MutableStateFlow<Overlay>(Overlay.None)
    val overlay: StateFlow<Overlay> = _overlay

    /** Channel shown in the banner while zapping; tuning follows after a short pause. */
    private val _pending = MutableStateFlow<ChannelEntity?>(null)
    val pending: StateFlow<ChannelEntity?> = _pending

    private val _numberText = MutableStateFlow("")
    val numberText: StateFlow<String> = _numberText

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _aspect = MutableStateFlow(AspectMode.FIT)
    val aspect: StateFlow<AspectMode> = _aspect

    val current: StateFlow<ChannelEntity?> = controller.current

    /** Now/next per guide reference; filled lazily for the rows that are on screen. */
    val nowNext = mutableStateMapOf<String, NowNext>()

    val favoriteKeys: StateFlow<Set<String>> = _playlist.filterNotNull()
        .flatMapLatest { repo.favoriteKeys(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val groups: StateFlow<List<CategoryRow>> = _playlist.filterNotNull()
        .flatMapLatest { repo.groups(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val channels: Flow<PagingData<ChannelEntity>> = combine(_playlist, _category) { p, c -> p to c }
        .flatMapLatest { (p, c) -> if (p == null) emptyFlow() else repo.page(p.id, c) }
        .cachedIn(viewModelScope)

    private var bannerJob: Job? = null
    private var tuneJob: Job? = null
    private var numberJob: Job? = null
    private var toastJob: Job? = null
    private val numberEntry = NumberEntry()

    /** Resumes the last watched channel (or the first one) unless something is already playing. */
    fun ensurePlaying() {
        viewModelScope.launch {
            val settings = graph.settings.settings.first()
            val pid = settings.activePlaylistId ?: return@launch
            val playlist = graph.playlists.get(pid) ?: return@launch
            _playlist.value = playlist
            settings.lastGroup?.let { if (_category.value == Category.All) _category.value = Category.Group(it) }
            if (controller.current.value != null && controller.engine.state.value.channelKey != null) return@launch
            val channel = settings.lastChannelKey?.let { repo.byKey(pid, it) }
                ?: repo.first(pid, Category.All)
                ?: return@launch
            controller.tune(channel, playlist.userAgent)
            showBanner()
        }
    }

    fun tune(channel: ChannelEntity) {
        tuneJob?.cancel()
        _pending.value = null
        controller.tune(channel, _playlist.value?.userAgent)
    }

    /** Zaps within the current category. Rapid presses only move the banner; the stream follows 250 ms after the last press. */
    fun zap(forward: Boolean) {
        val pid = _playlist.value?.id ?: return
        val from = _pending.value ?: controller.current.value ?: return
        viewModelScope.launch {
            val next = repo.neighbor(pid, _category.value, from, forward) ?: return@launch
            _pending.value = next
            showBanner()
            tuneJob?.cancel()
            tuneJob = viewModelScope.launch {
                delay(250)
                tune(next)
            }
        }
    }

    fun selectCategory(category: Category) {
        _category.value = category
        viewModelScope.launch { graph.settings.update { it.copy(lastGroup = (category as? Category.Group)?.name) } }
    }

    fun switchPlaylist(id: Long) {
        viewModelScope.launch {
            graph.settings.update { it.copy(activePlaylistId = id, lastChannelKey = null, lastGroup = null) }
            _category.value = Category.All
            controller.stop()
            ensurePlaying()
        }
    }

    fun loadNowNext(refs: Collection<String>) {
        val missing = refs.filter { it !in nowNext }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val result = repo.nowNext(missing)
            for (ref in missing) nowNext[ref] = result[ref] ?: NowNext(null, null)
        }
    }

    /** Drops cached programme info so it refreshes as programmes change. */
    fun refreshNowNext() = nowNext.clear()

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            val added = repo.toggleFavorite(channel.playlistId, channel.stableKey)
            showToast(if (added) "Added to Favorites" else "Removed from Favorites")
        }
    }

    fun digit(d: Int) {
        numberEntry.append(d, SystemClock.elapsedRealtime())
        _numberText.value = numberEntry.text
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(1_550)
            val number = numberEntry.commitIfDue(SystemClock.elapsedRealtime())
            _numberText.value = numberEntry.text
            val pid = _playlist.value?.id ?: return@launch
            val channel = number?.let { repo.byNumber(pid, it) }
            if (channel != null) {
                tune(channel)
                showBanner()
            } else if (number != null) {
                showToast("No channel $number")
            }
        }
    }

    fun showBanner() {
        if (_overlay.value != Overlay.None && _overlay.value != Overlay.Banner) return
        _overlay.value = Overlay.Banner
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(graph.settings.settings.first().bannerTimeoutMs.toLong())
            if (_overlay.value == Overlay.Banner) _overlay.value = Overlay.None
            _pending.value = null
        }
    }

    fun open(overlay: Overlay) {
        bannerJob?.cancel()
        _overlay.value = overlay
    }

    fun close() {
        _overlay.value = Overlay.None
    }

    fun togglePause() = controller.engine.togglePause()

    fun cycleAspect() {
        val values = AspectMode.entries
        _aspect.value = values[(values.indexOf(_aspect.value) + 1) % values.size]
    }

    fun showToast(text: String) {
        _toast.value = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2_000)
            _toast.value = null
        }
    }

    suspend fun positionOf(category: Category, channel: ChannelEntity): Int {
        val pid = _playlist.value?.id ?: return 0
        return repo.positionOf(pid, category, channel)
    }

    fun refreshGuide() {
        val pid = _playlist.value?.id ?: return
        graph.appScope.launch {
            runCatching { graph.playlists.refreshGuide(pid) }
            nowNext.clear()
        }
        showToast("Updating the TV guide…")
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory { initializer { PlayerViewModel(graph) } }
    }
}
