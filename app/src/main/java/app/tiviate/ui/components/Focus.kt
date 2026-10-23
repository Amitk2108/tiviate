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
 * (cyan border, slight scale). tv-material animates these on the render thread.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = Tokens.Shape,
    container: Color = Tokens.Surface,
    focusedContainer: Color = Tokens.Focused,
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (app.tiviate.ui.theme.ThemeState.animations) scale else 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(Tokens.FocusBorder, Tokens.Cyan), shape = shape),
        ),
        // No glow: it is drawn as a shadow, which is expensive on low-end TV GPUs. Border + scale read clearly.
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        content = content,
    )
}
