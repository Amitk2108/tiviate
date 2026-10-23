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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.tiviate.ui.t
import app.tiviate.ui.tf

/** Which overlay sits on top of the video. Only one at a time keeps focus handling simple. */
sealed interface Overlay {
    data object None : Overlay
    data object Banner : Overlay
    data class Panel(val categoriesFocused: Boolean) : Overlay
    /** Fullscreen options: info plus a row of picture/sound cards (Down, Menu or long OK). */
    data object Options : Overlay
    /** A choice list opened from an options card; closing it returns to the options. */
    data class Picker(val kind: PickerKind) : Overlay
    data object TouchControls : Overlay
}

enum class PickerKind { AUDIO, SUBTITLES, DISPLAY, ZOOM, QUALITY, SLEEP, AUDIO_SYNC }

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val graph: AppGraph) : ViewModel() {

    private val controller = graph.player

    private val _showStats = MutableStateFlow(false)
    /** The Stats card's overlay (stream numbers, top left). */
    val showStats: StateFlow<Boolean> = _showStats
    fun toggleStats() { _showStats.value = !_showStats.value }

    /** The options card used last; the panel reopens with it focused. */
    var lastOptionTag: String = "option_playpause"
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


    val current: StateFlow<ChannelEntity?> = controller.current

    /** Now/next per guide reference; filled lazily for the rows that are on screen. */
    val nowNext = mutableStateMapOf<String, NowNext>()

    val favoriteKeys: StateFlow<Set<String>> = _playlist.filterNotNull()
        .flatMapLatest { repo.favoriteKeys(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val customGroups: StateFlow<List<app.tiviate.data.db.CustomGroupEntity>> = _playlist.filterNotNull()
        .flatMapLatest { repo.customGroups(it.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            if (_category.value == Category.All) _category.value = Category.restore(settings.lastGroup)
            savedGroup = settings.lastGroup
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

    /** Previous channel ("last channel"): swaps back and forth between the last two. */
    fun previousChannel() {
        controller.previous.value?.let { tune(it) } ?: showToast(t("No previous channel yet"))
        showBanner()
    }

    private var peeking = false

    /** With the info bar up, Left/Right show the next/previous channel's info without switching; OK switches. */
    fun peek(forward: Boolean) {
        val pid = _playlist.value?.id ?: return
        val from = _pending.value ?: controller.current.value ?: return
        viewModelScope.launch {
            val next = repo.neighbor(pid, _category.value, from, forward) ?: return@launch
            tuneJob?.cancel()
            peeking = true
            _pending.value = next
            showBanner()
        }
    }

    /** OK on a peeked channel tunes it; returns false when nothing was being peeked. */
    fun commitPeek(): Boolean {
        val p = _pending.value
        if (!peeking || p == null) return false
        peeking = false
        tune(p)
        return true
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

    /** [save] = false while the viewer is only moving through the groups; [rememberGroup] saves it later. */
    fun selectCategory(category: Category, save: Boolean = true) {
        _category.value = category
        if (save) rememberGroup()
    }

    private var savedGroup: String? = null

    fun rememberGroup() {
        val g = Category.remember(_category.value)
        if (g == savedGroup) return
        savedGroup = g
        viewModelScope.launch { graph.settings.update { it.copy(lastGroup = g) } }
    }

    fun switchPlaylist(id: Long) {
        viewModelScope.launch {
            savedGroup = null
            graph.settings.update { it.copy(activePlaylistId = id, lastChannelKey = null, lastGroup = null) }
            _category.value = if (graph.settings.settings.first().openPlaylistOn == app.tiviate.data.OpenOn.FAVORITES) Category.Favorites else Category.All
            controller.stop()
            ensurePlaying()
        }
    }

    /** Loads now/next for [refs] that aren't cached yet or whose programme has since ended. */
    fun loadNowNext(refs: Collection<String>) {
        val now = System.currentTimeMillis()
        val missing = refs.filter { nowNext[it]?.isStaleAt(now) ?: true }
        if (missing.isNotEmpty()) reloadNowNext(missing)
    }

    /** Replaces cached entries in place (no blank flash while the new info loads). */
    private fun reloadNowNext(refs: Collection<String>) {
        if (refs.isEmpty()) return
        val wanted = refs.toList()
        viewModelScope.launch {
            val result = repo.nowNext(wanted)
            for (ref in wanted) nowNext[ref] = result[ref] ?: NowNext(null, null)
        }
    }

    init {
        // Programmes end while the banner or list is on screen; a guide refresh brings new data.
        viewModelScope.launch {
            while (true) {
                delay(NOW_NEXT_CHECK_MS)
                val now = System.currentTimeMillis()
                reloadNowNext(nowNext.filterValues { it.isStaleAt(now) }.keys)
            }
        }
        viewModelScope.launch { repo.guideVersion().drop(1).collect { reloadNowNext(nowNext.keys.toList()) } }
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            val added = repo.toggleFavorite(channel.playlistId, channel.stableKey)
            showToast(if (added) t("Added to Favorites") else t("Removed from Favorites"))
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
                showToast(tf("No channel %d", number))
            }
        }
    }

    /**
     * True while the viewer opened the info bar themselves (Info key) or is peeking: only then do
     * Left/Right look at other channels. The info bar that pops up after a switch leaves Left/Right
     * to open the groups and the guide.
     */
    var canPeek = false
        private set

    /** Info key: the info bar, with Left/Right peeking at the neighbouring channels. */
    fun showInfo() {
        showBanner()
        if (_overlay.value == Overlay.Banner) canPeek = true
    }

    fun showBanner() {
        if (_overlay.value != Overlay.None && _overlay.value != Overlay.Banner) return
        _overlay.value = Overlay.Banner
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            delay(graph.settings.settings.first().bannerTimeoutMs.toLong())
            if (_overlay.value == Overlay.Banner) _overlay.value = Overlay.None
            _pending.value = null
            peeking = false
            canPeek = false
        }
    }

    fun open(overlay: Overlay) {
        bannerJob?.cancel()
        canPeek = false
        _overlay.value = overlay
    }

    fun close() {
        if (peeking) { peeking = false; _pending.value = null }
        canPeek = false
        _overlay.value = Overlay.None
        rememberGroup() // a group browsed to in the channel list is kept
    }

    fun togglePause() = controller.engine.togglePause()

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
            runCatching { graph.playlists.refreshGuide(pid) } // cached info reloads on the guide version change
        }
        showToast(t("Updating the TV guide…"))
    }

    companion object {
        private const val NOW_NEXT_CHECK_MS = 30_000L

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory { initializer { PlayerViewModel(graph) } }
    }
}
