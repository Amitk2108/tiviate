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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.tiviate.ui.theme.Tokens

/**
 * The one focusable building block: a tv-material Surface with Tiviate's focus look
 * (cyan border, slight scale). tv-material animates these on the render thread.
 * The TV Surface only reacts to the remote's OK key, so taps and long-presses are handled here too
 * (phones and tablets); while the screen is being touched, the focus look stays off.
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
    val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { haptics.performHapticFeedback(HapticFeedbackType.VirtualKey); click() },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick?.invoke() ?: click()
                },
            )
        },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = if (touch) container else focusedContainer,
            pressedContainerColor = focusedContainer,
            contentColor = Tokens.Text,
            focusedContentColor = Tokens.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (app.tiviate.ui.theme.ThemeState.animations && !touch) scale else 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (touch) Border.None else Border(BorderStroke(Tokens.FocusBorder, Tokens.Cyan), shape = shape),
        ),
        // No glow: it is drawn as a shadow, which is expensive on low-end TV GPUs. Border + scale read clearly.
        glow = ClickableSurfaceDefaults.glow(glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None),
        content = content,
    )
}
