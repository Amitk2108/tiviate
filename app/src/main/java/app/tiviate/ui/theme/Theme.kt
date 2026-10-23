package app.tiviate.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import app.tiviate.data.AppSettings

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold, color = Tokens.Text),
    titleMedium = TextStyle(fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold, color = Tokens.Text),
    bodyLarge = TextStyle(fontSize = Tokens.BodySize, color = Tokens.Text),
    bodyMedium = TextStyle(fontSize = Tokens.BodySize, color = Tokens.Text),
    bodySmall = TextStyle(fontSize = Tokens.CaptionSize, color = Tokens.TextSecondary),
    labelMedium = TextStyle(fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium, color = Tokens.TextSecondary),
)

/** Applies the Appearance settings: colours and styles through [ThemeState], font size through the text scale. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TiviateTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    SideEffect { ThemeState.apply(settings) }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(settings.language) { app.tiviate.ui.I18n.use(context, settings.language) }
    val colors = darkColorScheme(
        primary = Tokens.Cyan,
        onPrimary = Tokens.Background,
        secondary = Tokens.Blue,
        background = Tokens.Background,
        onBackground = Tokens.Text,
        surface = Tokens.Surface,
        onSurface = Tokens.Text,
        surfaceVariant = Tokens.Raised,
        onSurfaceVariant = Tokens.TextSecondary,
        border = Tokens.Outline,
        error = Tokens.Danger,
    )
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * settings.fontSize.scale),
        LocalBringIntoViewSpec provides ComfortableScroll,
    ) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

/**
 * How far a list scrolls to show the focused item. The item keeps a fifth of the list's height (or
 * width) clear on either side, so the rows around it stay readable and the heading above the first
 * row comes back into view on the way up. Items already inside that zone do not scroll.
 */
fun comfortScroll(offset: Float, size: Float, containerSize: Float): Float {
    val margin = minOf(containerSize * 0.2f, ((containerSize - size) / 2f).coerceAtLeast(0f))
    val leading = offset
    val trailing = offset + size
    return when {
        leading < margin -> leading - margin
        trailing > containerSize - margin -> trailing - (containerSize - margin)
        else -> 0f
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val ComfortableScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = comfortScroll(offset, size, containerSize)
}
