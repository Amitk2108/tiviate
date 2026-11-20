package app.tiviate.player

import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.StateFlow

data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean, val language: String? = null)

/** What the Stats overlay shows; null/0 where the stream doesn't say. */
data class PlaybackStats(
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float? = null,
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val sampleRate: Int = 0,
    val bufferedMs: Long = 0,
    val droppedFrames: Int = 0,
    val bandwidthKbps: Long = 0,
)

data class PlaybackState(
    val channelKey: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isPaused: Boolean = false,
    /** Set when playback has given up; [error] is its title for simple checks. */
    val issue: PlaybackIssue? = null,
    val issueDetail: String? = null,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float? = null,
    val audioLabel: String? = null,
    val retrying: Int = 0,
    /** Short status shown under the spinner while recovering (e.g. provider busy). */
    val notice: String? = null,
)

/** Where playback is in a stream that can be moved through (films, or live with a rewind window). */
data class SeekInfo(val positionMs: Long, val durationMs: Long, val live: Boolean)

/**
 * The small surface the UI needs from a player. Media3 is the implementation today; another
 * engine (e.g. libVLC) can be added later without touching screens.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    /** [startAtMs]: where a film starts (resume); 0 = the start, or the live edge. */
    fun play(url: String, headers: Map<String, String>, userAgent: String, channelKey: String, startAtMs: Long = 0)
    fun togglePause()
    fun retryNow()
    fun stop()
    fun release()
    fun audioTracks(): List<TrackOption>
    fun textTracks(): List<TrackOption>
    fun videoTracks(): List<TrackOption>
    /** Null = automatic quality. */
    fun selectVideo(option: TrackOption?)
    fun stats(): PlaybackStats
    fun selectAudio(option: TrackOption)
    /** Null turns subtitles off. */
    fun selectText(option: TrackOption?)
    fun bindView(view: PlayerView)
    /** Null for ordinary live channels, which can't be moved through. */
    fun seekInfo(): SeekInfo?
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
}
