package app.tiviate.ui.player

import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.AvTimer
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.tiviate.data.NowNext
import app.tiviate.data.db.ChannelEntity
import app.tiviate.player.PlaybackState
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.theme.Tokens

/** One card in the options row: what it is, and its current value (e.g. "Audio" · "Hindi · Stereo"). */
data class OptionItem(val tag: String, val title: String, val value: String?, val onClick: () -> Unit)

/**
 * Fullscreen options (Down, Menu or long-press OK): what's on now, then a row of cards for the
 * picture and sound choices. Focus starts on [focusTag] — the card used last, so a picker that
 * closes hands focus back to the card that opened it.
 */
@Composable
fun OptionsPanel(
    channel: ChannelEntity?,
    info: NowNext?,
    playback: PlaybackState,
    showNumber: Boolean,
    requestInfo: (String) -> Unit,
    options: List<OptionItem>,
    focusTag: String,
) {
    val now = rememberNow()
    val focus = remember { FocusRequester() }
    val start = options.indexOfFirst { it.tag == focusTag }.coerceAtLeast(0)
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    LaunchedEffect(channel?.epgRef) { channel?.epgRef?.let(requestInfo) }
    LaunchedEffect(Unit) { focusSoon(focus) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Transparent, 1f to Tokens.Scrim))
            .testTag("options_panel"),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = Tokens.SafeMargin, end = Tokens.SafeMargin, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            channel?.let { ChannelInfo(it, info, playback, showNumber, now) }
            // Tiles are sized so a whole number fits the width: no tile is cut at the screen edge.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val perRow = 10
            val tileWidth = (maxWidth - gap * (perRow - 1) - 12.dp) / perRow
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(options, key = { it.tag }) { o ->
                    FocusSurface(
                        onClick = o.onClick,
                        modifier = (if (o.tag == options[start].tag) Modifier.focusRequester(focus) else Modifier).width(tileWidth).height(76.dp).testTag(o.tag),
                        container = Tokens.Raised.copy(alpha = 0.85f),
                        shape = Tokens.SmallShape,
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                        ) {
                            Icon(optionIcon(o.tag), contentDescription = null, tint = Tokens.Text, modifier = Modifier.size(22.dp))
                            Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
                            o.value?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.TextSecondary, fontSize = 11.sp)) }
                        }
                    }
                }
            }
            }
        }
    }
}

/** The icon for an options tile, by its tag. */
fun optionIcon(tag: String): ImageVector = when (tag) {
    "option_channels" -> Icons.Rounded.ViewList
    "option_guide" -> Icons.Rounded.CalendarMonth
    "option_playpause" -> Icons.Rounded.Pause
    "option_live" -> Icons.Rounded.LiveTv
    "option_previous" -> Icons.Rounded.SkipPrevious
    "option_audio" -> Icons.Rounded.Audiotrack
    "option_subtitles" -> Icons.Rounded.Subtitles
    "option_audio_sync" -> Icons.Rounded.AvTimer
    "option_pip" -> Icons.Rounded.PictureInPictureAlt
    "option_display" -> Icons.Rounded.AspectRatio
    "option_zoom" -> Icons.Rounded.ZoomIn
    "option_quality" -> Icons.Rounded.HighQuality
    "option_stats" -> Icons.Rounded.QueryStats
    "option_sleep" -> Icons.Rounded.Bedtime
    "option_favorite" -> Icons.Rounded.Star
    "option_external" -> Icons.Rounded.OpenInNew
    "option_channel" -> Icons.Rounded.Tune
    "option_search" -> Icons.Rounded.Search
    "option_settings" -> Icons.Rounded.Settings
    else -> Icons.Rounded.MoreHoriz
}
