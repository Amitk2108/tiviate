package app.tiviate.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.ui.theme.Tokens
import java.util.Date
import kotlinx.coroutines.delay

/** Wall-clock time that ticks at [periodMs]; only the composables reading it recompose. */
@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs - now % periodMs)
        }
    }
    return now
}

@Composable
fun formatTime(ms: Long): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return format.format(Date(ms))
}

@Composable
fun Clock(style: TextStyle, modifier: Modifier = Modifier) {
    val now = rememberNow(15_000)
    val scale = when (app.tiviate.ui.theme.ThemeState.clockSize) {
        app.tiviate.data.ClockSize.SMALL -> 0.85f
        app.tiviate.data.ClockSize.MEDIUM -> 1f
        app.tiviate.data.ClockSize.LARGE -> 1.3f
    }
    val text = if (app.tiviate.ui.theme.ThemeState.clockShowDate) {
        java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(now)) + "  " + formatTime(now)
    } else {
        formatTime(now)
    }
    Text(text, style = style.copy(fontSize = style.fontSize * scale), modifier = modifier)
}

fun progressOf(startMs: Long, stopMs: Long, nowMs: Long): Float =
    if (stopMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(Tokens.SmallShape).background(Tokens.Outline)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Tokens.Cyan))
    }
}

/** A light spinner: one arc rotated on the render thread (graphicsLayer), no recomposition per frame. */
@Composable
fun Spinner(size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val stroke = 4.dp.toPx()
        drawArc(
            brush = Tokens.BrandGradient,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke),
        )
    }
}
