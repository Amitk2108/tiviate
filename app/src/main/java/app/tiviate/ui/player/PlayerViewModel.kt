package app.tiviate.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tiviate.AppGraph
import app.tiviate.data.Category
import app.tiviate.data.db.PlaylistEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlayerViewModel(private val graph: AppGraph) : ViewModel() {

    private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
    val playlist: StateFlow<PlaylistEntity?> = _playlist

    /** Resumes the last watched channel (or the first one) unless something is already playing. */
    fun ensurePlaying() {
        viewModelScope.launch {
            val settings = graph.settings.settings.first()
            val pid = settings.activePlaylistId ?: return@launch
            val playlist = graph.playlists.get(pid) ?: return@launch
            _playlist.value = playlist
            val controller = graph.player
            if (controller.current.value != null && controller.engine.state.value.channelKey != null) return@launch
            val channel = settings.lastChannelKey?.let { graph.channels.byKey(pid, it) }
                ?: graph.channels.first(pid, Category.All)
                ?: return@launch
            controller.tune(channel, playlist.userAgent)
        }
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory { initializer { PlayerViewModel(graph) } }
    }
}
