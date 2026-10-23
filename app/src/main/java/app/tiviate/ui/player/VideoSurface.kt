package app.tiviate.ui.player

import android.app.Activity
import android.graphics.Color
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import app.tiviate.data.DisplayMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tiviate.player.PlaybackEngine

/**
 * The video. A SurfaceView (PlayerView's default) is composited by the display hardware, which is
 * the smoothest path on TV chips. The last frame stays visible while switching channels.
 * Display mode and zoom size the view itself (see [videoFrame]); the video is stretched into it.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(engine: PlaybackEngine, mode: DisplayMode, zoomPercent: Int, videoWidth: Int, videoHeight: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val frame = videoFrame(mode, zoomPercent, constraints.maxWidth, constraints.maxHeight, videoWidth, videoHeight)
        val size = with(LocalDensity.current) { DpSize(frame.width.toDp(), frame.height.toDp()) }
        PlayerSurface(engine, Modifier.requiredSize(size))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(engine: PlaybackEngine, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable = false
                engine.bindView(this)
            }
        },
        update = { it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL },
        // Detach from the shared player when this view goes away (guide ⇄ fullscreen hand-over).
        onRelease = { it.player = null },
    )
}

/** Keeps the TV from dimming or sleeping while the player screen is visible. */
@Composable
fun KeepScreenOn() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
