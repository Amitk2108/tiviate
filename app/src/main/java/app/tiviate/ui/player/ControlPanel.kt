package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.tiviate.data.NowNext
import app.tiviate.data.db.ChannelEntity
import app.tiviate.player.PlaybackState
import app.tiviate.ui.components.ChannelLogo
import app.tiviate.ui.components.FocusSurface
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens

/**
 * OK in fullscreen: what's on (logo, now/next, minutes left, stream badges), then shortcuts —
 * the channel list, the TV guide and the options — and the channels watched last (OK switches).
 */
@Composable
fun ControlPanel(
    channel: ChannelEntity?,
    info: NowNext?,
    playback: PlaybackState,
    showNumber: Boolean,
    requestInfo: (String) -> Unit,
    recent: List<ChannelEntity>,
    nowNext: Map<String, NowNext>,
    onChannels: () -> Unit,
    onGuide: () -> Unit,
    onOptions: () -> Unit,
    onRecent: (ChannelEntity) -> Unit,
) {
    val now = rememberNow()
    val first = remember { FocusRequester() }
    LaunchedEffect(channel?.epgRef) { channel?.epgRef?.let(requestInfo) }
    LaunchedEffect(recent.mapNotNull { it.epgRef }) { recent.mapNotNull { it.epgRef }.takeIf { it.isNotEmpty() }?.let { it.forEach(requestInfo) } }
    LaunchedEffect(Unit) { focusSoon(first) }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Transparent, 1f to Tokens.Scrim))
            .testTag("control_panel"),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = Tokens.SafeMargin, end = Tokens.SafeMargin, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            channel?.let { ChannelInfo(it, info, playback, showNumber, now) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 8.dp
                val perRow = 8
                val tileWidth = (maxWidth - gap * (perRow - 1) - 12.dp) / perRow
                LazyRow(horizontalArrangement = Arrangement.spacedBy(gap), contentPadding = PaddingValues(6.dp), modifier = Modifier.fillMaxWidth()) {
                    item(key = "channels") { ShortcutTile(Icons.Rounded.ViewList, t("Channels"), tileWidth, "control_channels", onChannels, Modifier.focusRequester(first)) }
                    item(key = "guide") { ShortcutTile(Icons.Rounded.CalendarMonth, t("TV guide"), tileWidth, "control_guide", onGuide) }
                    item(key = "options") { ShortcutTile(Icons.Rounded.Tune, t("Options"), tileWidth, "control_options", onOptions) }
                    if (recent.isNotEmpty()) item(key = "recent_label") { ShortcutLabel(Icons.Rounded.History, t("Recent"), tileWidth) }
                    recent.forEachIndexed { i, ch ->
                        item(key = "recent_${ch.id}") {
                            FocusSurface(
                                onClick = { onRecent(ch) },
                                modifier = Modifier.width(tileWidth).height(96.dp).testTag("control_recent_$i"),
                                container = Tokens.Raised.copy(alpha = 0.85f),
                                shape = Tokens.SmallShape,
                            ) {
                                Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                    ChannelLogo(ch, 40.dp)
                                    Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
                                    ch.epgRef?.let(nowNext::get)?.now?.title?.let {
                                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.TextSecondary, fontSize = 11.sp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(icon: ImageVector, label: String, width: androidx.compose.ui.unit.Dp, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(onClick = onClick, modifier = modifier.width(width).height(96.dp).testTag(tag), container = Tokens.Raised.copy(alpha = 0.85f), shape = Tokens.SmallShape) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            Icon(icon, contentDescription = null, tint = Tokens.Text, modifier = Modifier.size(28.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = TextStyle(color = Tokens.Text, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** "Recent": a quiet label before the recently watched channels (not focusable). */
@Composable
private fun ShortcutLabel(icon: ImageVector, label: String, width: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(width * 0.6f).height(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(icon, contentDescription = null, tint = Tokens.TextSecondary, modifier = Modifier.size(20.dp))
        Text(label, maxLines = 1, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize))
    }
}
