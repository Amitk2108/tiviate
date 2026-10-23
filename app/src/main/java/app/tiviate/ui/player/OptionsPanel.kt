package app.tiviate.ui.player

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
            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(options, key = { it.tag }) { o ->
                    FocusSurface(
                        onClick = o.onClick,
                        modifier = (if (o.tag == options[start].tag) Modifier.focusRequester(focus) else Modifier).width(176.dp).height(96.dp).testTag(o.tag),
                        container = Tokens.Raised,
                        focusedContainer = Tokens.Outline,
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.SemiBold))
                            o.value?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize)) }
                        }
                    }
                }
            }
        }
    }
}
