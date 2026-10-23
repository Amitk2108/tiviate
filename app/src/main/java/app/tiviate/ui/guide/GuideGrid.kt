package app.tiviate.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.GuideHighlight
import app.tiviate.data.db.ProgrammeEntity
import app.tiviate.ui.components.ChannelLogo
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.theme.Tokens

val GUIDE_ROW_HEIGHT = 58.dp
val GUIDE_CHANNEL_WIDTH = 250.dp

/** Time ruler over the programme columns: one label per slot. */
@Composable
fun GuideHeader(viewStartMs: Long, windowMs: Long, slotMs: Long, stripWidth: Dp, dayLabel: String) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel, modifier = Modifier.width(GUIDE_CHANNEL_WIDTH).padding(start = 16.dp), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
        Box(Modifier.width(stripWidth).fillMaxHeight().clipToBounds()) {
            var t = viewStartMs
            while (t < viewStartMs + windowMs) {
                val x = stripWidth * ((t - viewStartMs).toFloat() / windowMs)
                Text(formatTime(t), modifier = Modifier.offset(x = x + 8.dp).align(Alignment.CenterStart), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
                t += slotMs
            }
        }
    }
}

/**
 * One guide row: channel cell plus the programmes intersecting the visible window. Only the focused
 * row receives a non-null [focused] block, so moving left/right recomposes a single row.
 */
@Composable
fun GuideRow(
    channel: ChannelEntity?,
    programmes: List<ProgrammeEntity>,
    viewStartMs: Long,
    windowMs: Long,
    nowMs: Long,
    stripWidth: Dp,
    isPlaying: Boolean,
    showNumber: Boolean,
    focused: FocusBlock?,
    highlight: GuideHighlight = GuideHighlight.ON_AIR,
) {
    Row(Modifier.fillMaxWidth().height(GUIDE_ROW_HEIGHT).padding(vertical = 2.dp)) {
        ChannelCell(channel, isPlaying, showNumber, rowFocused = focused != null)
        Box(Modifier.width(stripWidth).fillMaxHeight().clipToBounds()) {
            val viewEnd = viewStartMs + windowMs
            for (p in programmes) {
                if (p.stopMs <= viewStartMs || p.startMs >= viewEnd) continue
                val isFocused = focused?.programme?.id == p.id && focused.programme != null
                ProgrammeBlock(p.title, p.startMs, p.stopMs, viewStartMs, windowMs, stripWidth, nowMs, isFocused, highlight = highlight)
            }
            if (focused != null && focused.programme == null && focused.stopMs > viewStartMs && focused.startMs < viewEnd) {
                ProgrammeBlock("No information", focused.startMs, focused.stopMs, viewStartMs, windowMs, stripWidth, nowMs, focused = true, empty = true)
            }
        }
    }
}

@Composable
private fun ChannelCell(channel: ChannelEntity?, isPlaying: Boolean, showNumber: Boolean, rowFocused: Boolean) {
    Row(
        Modifier
            .width(GUIDE_CHANNEL_WIDTH)
            .fillMaxHeight()
            .padding(end = 4.dp)
            .background(if (rowFocused) Tokens.Raised else Tokens.Surface, Tokens.SmallShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (channel == null) return@Row
        if (showNumber) Text("${channel.number}", modifier = Modifier.width(34.dp), style = TextStyle(color = if (isPlaying) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
        ChannelLogo(channel, 40.dp)
        Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (isPlaying) Tokens.Cyan else Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    startMs: Long,
    stopMs: Long,
    viewStartMs: Long,
    windowMs: Long,
    stripWidth: Dp,
    nowMs: Long,
    focused: Boolean,
    empty: Boolean = false,
    highlight: GuideHighlight = GuideHighlight.ON_AIR,
) {
    val from = maxOf(startMs, viewStartMs)
    val to = minOf(stopMs, viewStartMs + windowMs)
    val x = stripWidth * ((from - viewStartMs).toFloat() / windowMs)
    val w = stripWidth * ((to - from).toFloat() / windowMs)
    val past = stopMs <= nowMs
    val live = startMs <= nowMs && nowMs < stopMs
    val bg = when {
        focused -> Tokens.Raised
        live && highlight == GuideHighlight.ON_AIR -> Color(0xFF0F1A2E)
        else -> Tokens.Surface
    }
    // "Progress only": the elapsed part of the programme on air is shaded instead.
    val elapsed = if (live && !focused && highlight == GuideHighlight.PROGRESS) {
        ((minOf(nowMs, to) - from).toFloat() / (to - from).coerceAtLeast(1)).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        Modifier
            .offset(x = x)
            .width(w)
            .fillMaxHeight()
            .padding(end = 3.dp)
            .clip(Tokens.SmallShape)
            .background(bg)
            .drawBehind { if (elapsed > 0f) drawRect(ELAPSED, size = size.copy(width = size.width * elapsed)) }
            .then(if (focused) Modifier.border(Tokens.FocusBorder, Tokens.Cyan, Tokens.SmallShape).testTag("guide_focused") else Modifier)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = when {
                    empty -> Tokens.TextSecondary
                    past && !focused -> Tokens.TextSecondary.copy(alpha = 0.7f)
                    else -> Tokens.Text
                },
                fontSize = Tokens.CaptionSize,
                fontWeight = if (focused || live) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** Vertical line marking the current time across the programme columns. */
@Composable
fun NowLine(viewStartMs: Long, windowMs: Long, nowMs: Long, stripWidth: Dp) {
    if (nowMs < viewStartMs || nowMs >= viewStartMs + windowMs) return
    val x = GUIDE_CHANNEL_WIDTH + stripWidth * ((nowMs - viewStartMs).toFloat() / windowMs)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(Tokens.Cyan))
    }
}

private val ELAPSED = Color(0xFF16263D)
