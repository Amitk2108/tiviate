package app.tiviate.ui.components

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.usesPhoneLayout
import app.tiviate.ui.theme.Tokens
import kotlin.math.roundToInt

/** Phones (and tablets held upright) show popups as bottom sheets; TVs and sideways tablets keep their dialogs. */
@Composable
fun usesBottomSheets(): Boolean =
    usesPhoneLayout(LocalFormFactor.current, LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT)

/**
 * A modal bottom sheet: slides up over a dimmed screen, at most 85% of its height. With [onDismiss], Back,
 * a tap above it or dragging the handle down closes it; without, its owner decides (e.g. its own Back).
 */
@Composable
fun BottomSheet(onDismiss: (() -> Unit)?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    if (onDismiss != null) BackHandler(onBack = onDismiss)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    var drag by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Tokens.ScrimSoft).testTag(tag)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val maxSheet = maxHeight * 0.85f
        AnimatedVisibility(shown, enter = slideInVertically { it } + fadeIn()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxSheet)
                    .offset { IntOffset(0, drag.roundToInt()) }
                    .background(Tokens.Surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .pointerInput(Unit) { detectTapGestures { } } // a tap on the sheet itself doesn't close it
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                // The handle: drag down to close.
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("${tag}_handle")
                        .pointerInput(onDismiss) {
                            detectVerticalDragGestures(
                                onDragEnd = { if (drag > 120f && onDismiss != null) onDismiss() else drag = 0f },
                                onDragCancel = { drag = 0f },
                            ) { change, dy -> change.consume(); drag = (drag + dy).coerceAtLeast(0f) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.padding(vertical = 8.dp).size(width = 40.dp, height = 4.dp).background(Tokens.Outline, CircleShape))
                }
                content()
            }
        }
    }
}
