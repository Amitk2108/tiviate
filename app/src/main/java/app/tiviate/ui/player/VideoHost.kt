package app.tiviate.ui.player

import kotlin.math.abs
import app.tiviate.ui.theme.ThemeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tiviate.data.DisplayMode
import app.tiviate.player.PlaybackEngine
import kotlin.math.roundToInt

/** Where the one video surface is shown, and how (set by the screen on top through [VideoSlot]). */
data class VideoSpec(val bounds: Rect, val mode: DisplayMode, val zoomPercent: Int, val blackOnSwitch: Boolean, val owner: Any)

class VideoLayout {
    var spec by mutableStateOf<VideoSpec?>(null)
        private set

    fun place(next: VideoSpec) {
        if (spec != next) spec = next
    }

    /** A screen left: the video keeps its place (an opaque screen covers it) so the surface is never rebuilt. */
    fun release(owner: Any) = Unit
}

val LocalVideoLayout = staticCompositionLocalOf { VideoLayout() }

/**
 * Marks where the video appears on this screen. The surface itself lives in [VideoHost] under all
 * screens, so moving between fullscreen and the guide preview never tears it down — no black flash
 * and no stretched frames. Keep this area of the screen transparent (see [backgroundWithHole]).
 */
@Composable
fun VideoSlot(mode: DisplayMode, zoomPercent: Int, modifier: Modifier = Modifier, blackOnSwitch: Boolean = false, onBounds: (Rect) -> Unit = {}) {
    val layout = LocalVideoLayout.current
    val owner = remember { Any() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    SideEffect { bounds?.let { layout.place(VideoSpec(it, mode, zoomPercent, blackOnSwitch, owner)) } }
    DisposableEffect(owner) { onDispose { layout.release(owner) } }
    Box(
        modifier.onGloballyPositioned { c ->
            val b = c.boundsInRoot()
            if (b != bounds) {
                bounds = b
                onBounds(b)
                layout.place(VideoSpec(b, mode, zoomPercent, blackOnSwitch, owner))
            }
        },
    )
}

/** The single video surface, drawn under every screen at the place the current screen asked for. */
@Composable
fun VideoHost(engine: PlaybackEngine) {
    val spec = LocalVideoLayout.current.spec ?: return
    val playback by engine.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    // Guide preview ⇄ fullscreen: the picture glides between the two places (the surface itself never changes).
    val box = remember { Animatable(spec.bounds, Rect.VectorConverter) }
    LaunchedEffect(spec.bounds) {
        val from = box.value
        val moved = from != spec.bounds && from.width > 0f && abs(from.width - spec.bounds.width) > 8f
        if (moved && ThemeState.animations) box.animateTo(spec.bounds, tween(280, easing = FastOutSlowInEasing)) else box.snapTo(spec.bounds)
    }
    val b = box.value
    val target = spec.bounds
    // Black fades in behind the picture as it grows to fill its place.
    val fill = if (target.width <= 0f) 1f else (b.width / target.width).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Color.Black.copy(alpha = fill), topLeft = target.topLeft, size = target.size)
            drawRect(Color.Black, topLeft = b.topLeft, size = b.size)
        },
    ) {
        VideoSurface(
            engine, spec.mode, spec.zoomPercent, playback.videoWidth, playback.videoHeight,
            Modifier
                .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
                .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() }),
            blackOnSwitch = spec.blackOnSwitch,
        )
    }
}

/** A screen background with a (rounded) hole where the video shows through. [hole] is in this layout's coordinates. */
fun Modifier.backgroundWithHole(color: Color, hole: () -> Rect?, cornerRadius: androidx.compose.ui.unit.Dp = 0.dp): Modifier = drawBehind {
    val h = hole()
    if (h == null) {
        drawRect(color)
    } else {
        val r = cornerRadius.toPx()
        val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(h, CornerRadius(r, r))) }
        clipPath(path, ClipOp.Difference) { drawRect(color) }
    }
}
