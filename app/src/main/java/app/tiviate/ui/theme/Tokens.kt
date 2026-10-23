package app.tiviate.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tiviate's own visual language, derived from the app logo (deep navy, blue → cyan). */
object Tokens {
    val Background = Color(0xFF05080F)
    val Surface = Color(0xFF0B1220)
    val Raised = Color(0xFF121C30)
    val Outline = Color(0xFF1E2A44)
    val Text = Color(0xFFEAF2FF)
    val TextSecondary = Color(0xFF8DA2C0)
    val Blue = Color(0xFF2D6BFF)
    val Cyan = Color(0xFF16D9E3)
    val Danger = Color(0xFFFF5A6E)
    val Scrim = Color(0xE6050810)
    val ScrimSoft = Color(0x99050810)

    val BrandGradient = Brush.horizontalGradient(listOf(Blue, Cyan))

    val SafeMargin = 48.dp
    val Radius = 10.dp
    val Shape = RoundedCornerShape(Radius)
    val SmallShape = RoundedCornerShape(6.dp)

    const val FocusScale = 1.04f
    val FocusBorder = 2.dp

    /** All UI motion stays short and simple so TV GPUs never drop frames. */
    const val MotionMs = 140
    fun <T> motion() = tween<T>(durationMillis = MotionMs, easing = FastOutSlowInEasing)

    val TitleSize = 26.sp
    val HeadingSize = 20.sp
    val BodySize = 16.sp
    val CaptionSize = 13.sp
}
