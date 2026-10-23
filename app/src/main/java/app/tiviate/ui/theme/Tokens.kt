package app.tiviate.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tiviate.data.AppSettings
import app.tiviate.data.BackgroundTheme
import app.tiviate.data.ClockSize
import app.tiviate.data.LogoStyle

/**
 * Appearance chosen in Settings → Appearance. Compose state, so anything reading [Tokens] redraws
 * when the viewer changes a colour, transparency or style — no restart needed.
 */
object ThemeState {
    var accent by mutableStateOf(Color(0xFF16D9E3))
    var background by mutableStateOf(BackgroundTheme.DARK)
    /** 0 (solid) … 60 % see-through panels. */
    var transparency by mutableStateOf(0)
    var animations by mutableStateOf(true)
    var logoStyle by mutableStateOf(LogoStyle.ROUNDED)
    var twoLineNames by mutableStateOf(false)
    var twoLineTitles by mutableStateOf(false)
    var clockSize by mutableStateOf(ClockSize.MEDIUM)
    var clockShowDate by mutableStateOf(false)

    fun apply(s: AppSettings) {
        accent = Color(s.accent.argb)
        background = s.backgroundTheme
        transparency = s.panelTransparency.coerceIn(0, 60)
        animations = s.animations
        logoStyle = s.logoStyle
        twoLineNames = s.twoLineNames
        twoLineTitles = s.twoLineTitles
        clockSize = s.clockSize
        clockShowDate = s.clockShowDate
    }
}

/** Tiviate's own visual language, derived from the app logo (deep navy, blue → cyan). */
object Tokens {
    val Background: Color get() = when (ThemeState.background) {
        BackgroundTheme.DARK -> Color(0xFF05080F)
        BackgroundTheme.BLACK -> Color(0xFF000000)
        BackgroundTheme.BLUE_GREY -> Color(0xFF0F1720)
    }
    val Surface: Color get() = when (ThemeState.background) {
        BackgroundTheme.DARK -> Color(0xFF0B1220)
        BackgroundTheme.BLACK -> Color(0xFF0B0B0B)
        BackgroundTheme.BLUE_GREY -> Color(0xFF18232E)
    }
    val Raised: Color get() = when (ThemeState.background) {
        BackgroundTheme.DARK -> Color(0xFF121C30)
        BackgroundTheme.BLACK -> Color(0xFF171717)
        BackgroundTheme.BLUE_GREY -> Color(0xFF223140)
    }
    val Outline: Color get() = when (ThemeState.background) {
        BackgroundTheme.DARK -> Color(0xFF1E2A44)
        BackgroundTheme.BLACK -> Color(0xFF282828)
        BackgroundTheme.BLUE_GREY -> Color(0xFF2E3F50)
    }
    val Text = Color(0xFFEAF2FF)
    val TextSecondary = Color(0xFF8DA2C0)
    val Blue = Color(0xFF2D6BFF)

    /** The accent colour (named Cyan for its default). */
    val Cyan: Color get() = ThemeState.accent
    val Danger = Color(0xFFFF5A6E)

    /** Panel backgrounds over video; "UI transparency" makes them more see-through. */
    val Scrim: Color get() = Background.copy(alpha = 0.90f * (1f - ThemeState.transparency / 100f))
    val ScrimSoft: Color get() = Background.copy(alpha = 0.60f * (1f - ThemeState.transparency / 100f))

    val BrandGradient: Brush get() = Brush.horizontalGradient(listOf(Blue, Cyan))

    /** TVs draw Tiviate at 960 × 540 dp (2× on a 1080p panel), so margins stay small: 24 dp = 48 px. */
    val SafeMargin = 24.dp
    val Radius = 14.dp
    val Shape = RoundedCornerShape(Radius)
    val SmallShape = RoundedCornerShape(10.dp)
    val PillShape = RoundedCornerShape(50)

    /** Focused rows and cards: a glow of the accent over the raised surface (white text stays readable). */
    val Focused: Color get() = Cyan.copy(alpha = 0.38f).compositeOver(Raised)
    /** The selected (current) item when not focused: a faint accent tint. */
    val Selected: Color get() = Cyan.copy(alpha = 0.12f).compositeOver(Surface)

    val LogoShape: Shape get() = when (ThemeState.logoStyle) {
        LogoStyle.ROUNDED -> SmallShape
        LogoStyle.SQUARE -> RectangleShape
        LogoStyle.CIRCLE -> CircleShape
    }

    /** Focused items grow slightly; "Animations" off keeps them still. */
    val FocusScale: Float get() = if (ThemeState.animations) 1.03f else 1f
    val FocusBorder = 2.dp

    /** All UI motion stays short and simple so TV GPUs never drop frames. */
    const val MotionMs = 140
    fun <T> motion(): FiniteAnimationSpec<T> = if (ThemeState.animations) tween(durationMillis = MotionMs, easing = FastOutSlowInEasing) else snap()

    val TitleSize = 24.sp
    val HeadingSize = 18.sp
    val BodySize = 15.sp
    val CaptionSize = 12.sp
}
