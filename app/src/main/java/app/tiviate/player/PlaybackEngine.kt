package app.tiviate.player

import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.StateFlow

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

data class PlaybackState(
    val channelKey: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isPaused: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float? = null,
    val audioLabel: String? = null,
    val retrying: Int = 0,
    /** Short status shown under the spinner while recovering (e.g. provider busy). */
    val notice: String? = null,
)

/**
 * The small surface the UI needs from a player. Media3 is the implementation today; another
 * engine (e.g. libVLC) can be added later without touching screens.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    fun play(url: String, headers: Map<String, String>, userAgent: String, channelKey: String)
    fun togglePause()
    fun retryNow()
    fun stop()
    fun release()
    fun audioTracks(): List<TrackOption>
    fun textTracks(): List<TrackOption>
    fun selectAudio(option: TrackOption)
    /** Null turns subtitles off. */
    fun selectText(option: TrackOption?)
    fun bindView(view: PlayerView)
}
