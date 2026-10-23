package app.tiviate.ui.player

import android.app.Activity
import android.graphics.Color
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tiviate.player.PlaybackEngine

enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

/**
 * The video. A SurfaceView (PlayerView's default) is composited by the display hardware, which is
 * the smoothest path on TV chips. The last frame stays visible while switching channels.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(engine: PlaybackEngine, aspect: AspectMode, modifier: Modifier = Modifier) {
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
        update = { it.resizeMode = aspect.resizeMode },
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
