package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.tiviate.player.PlaybackEngine
import app.tiviate.player.SeekInfo
import app.tiviate.ui.FormFactor
import app.tiviate.ui.LocalFormFactor
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.util.Locale

private const val STEP_MS = 10_000L

/**
 * Seek bar for streams that can be moved through (films, live channels with a rewind window). Shows
 * nothing for ordinary live channels. Touch: tap or drag the line, or the 10-second buttons. TV: with the
 * line focused, Left/Right move 10 seconds. [interactive] false only shows where playback is.
 */
@Composable
fun SeekBar(engine: PlaybackEngine, modifier: Modifier = Modifier, interactive: Boolean = true, onUse: () -> Unit = {}) {
    var info by remember { mutableStateOf(engine.seekInfo()) }
    var dragged by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(engine) {
        while (true) { info = engine.seekInfo(); delay(500) }
    }
    val i = info ?: return
    val haptics = LocalHapticFeedback.current
    val tv = LocalFormFactor.current == FormFactor.TV
    fun jump(deltaMs: Long) {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        onUse()
        engine.seekBy(deltaMs)
        info = engine.seekInfo()
    }
    fun seekTo(fraction: Float) {
        onUse()
        engine.seekTo((fraction.coerceIn(0f, 1f) * i.durationMs).toLong())
        info = engine.seekInfo()
    }
    val fraction = dragged ?: (i.positionMs.toFloat() / i.durationMs)
    val (left, right) = seekLabels(i, dragged?.let { (it * i.durationMs).toLong() })
    val label = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium)

    Row(modifier.fillMaxWidth().testTag("seek_bar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (interactive && !tv) SeekButton(Icons.Rounded.Replay10, t("Back 10 seconds"), "seek_back") { jump(-STEP_MS) }
        Text(left, style = label)
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        BoxWithConstraints(
            Modifier.weight(1f).height(36.dp)
                .then(
                    when {
                        !interactive -> Modifier
                        tv -> Modifier
                            .onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key) {
                                    Key.DirectionLeft -> { jump(-STEP_MS); true }
                                    Key.DirectionRight -> { jump(STEP_MS); true }
                                    else -> false
                                }
                            }
                            .focusable(interactionSource = interaction)
                        else -> Modifier
                            .pointerInput(i.durationMs) { detectTapGestures { seekTo(it.x / size.width) } }
                            .pointerInput(i.durationMs) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = (it.x / size.width).coerceIn(0f, 1f); onUse() },
                                    onDragEnd = { dragged?.let(::seekTo); dragged = null },
                                    onDragCancel = { dragged = null },
                                ) { change, _ ->
                                    change.consume()
                                    dragged = (change.position.x / size.width).coerceIn(0f, 1f)
                                    onUse()
                                }
                            }
                    },
                )
                .then(if (focused) Modifier.border(2.dp, Tokens.Selector, Tokens.SmallShape) else Modifier)
                .padding(horizontal = 8.dp)
                .testTag("seek_line"),
            contentAlignment = Alignment.CenterStart,
        ) {
            ProgressLine(fraction.coerceIn(0f, 1f), height = 4.dp)
            val thumb = if (dragged != null || focused) 18.dp else 14.dp
            Box(Modifier.offset(x = (maxWidth - thumb) * fraction.coerceIn(0f, 1f)).size(thumb).background(Tokens.Cyan, CircleShape))
        }
        // Live: a tap on "Live" goes back to the live edge.
        Text(
            right, style = label,
            modifier = if (i.live && interactive && !tv) Modifier.testTag("seek_live").pointerInput(Unit) { detectTapGestures { seekTo(1f) } } else Modifier,
        )
        if (interactive && !tv) SeekButton(Icons.Rounded.Forward10, t("Forward 10 seconds"), "seek_forward") { jump(STEP_MS) }
    }
}

@Composable
private fun SeekButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, container = Tokens.Scrim, shape = Tokens.PillShape, scale = 1f, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = label, tint = Tokens.Text, modifier = Modifier.padding(8.dp).size(26.dp))
    }
}

/**
 * The times beside the bar. Films: played and total. Live with a rewind window: how far behind live
 * (empty at the live edge) and "Live". [draggedMs] shows the drag target instead of the playing place.
 */
internal fun seekLabels(info: SeekInfo, draggedMs: Long? = null): Pair<String, String> {
    val at = draggedMs ?: info.positionMs
    if (!info.live) return clock(at) to clock(info.durationMs)
    val behind = info.durationMs - at
    return (if (behind < 10_000) "" else "−" + clock(behind)) to t("Live")
}

internal fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, s % 3600 / 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}
