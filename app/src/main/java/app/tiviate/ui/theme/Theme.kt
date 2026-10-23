package app.tiviate.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

private val colors = darkColorScheme(
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

private val typography = Typography(
    headlineSmall = TextStyle(fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold, color = Tokens.Text),
    titleMedium = TextStyle(fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold, color = Tokens.Text),
    bodyLarge = TextStyle(fontSize = Tokens.BodySize, color = Tokens.Text),
    bodyMedium = TextStyle(fontSize = Tokens.BodySize, color = Tokens.Text),
    bodySmall = TextStyle(fontSize = Tokens.CaptionSize, color = Tokens.TextSecondary),
    labelMedium = TextStyle(fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium, color = Tokens.TextSecondary),
)

@Composable
fun TiviateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
