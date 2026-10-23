package app.tiviate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.compose.ui.unit.dp
import app.tiviate.ui.theme.Tokens

/**
 * The one focusable building block: a tv-material Surface with Tiviate's focus look
 * (cyan border, soft glow, slight scale). tv-material animates these on the render thread.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = Tokens.Shape,
    container: Color = Tokens.Surface,
    focusedContainer: Color = Tokens.Raised,
    scale: Float = Tokens.FocusScale,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = focusedContainer,
            pressedContainerColor = focusedContainer,
            contentColor = Tokens.Text,
            focusedContentColor = Tokens.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(Tokens.FocusBorder, Tokens.Cyan), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = Tokens.Cyan.copy(alpha = 0.35f), elevation = 8.dp)),
        content = content,
    )
}
