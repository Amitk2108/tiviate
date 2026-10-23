package app.tiviate.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.tiviate.ui.components.ChannelLogo
import app.tiviate.ui.components.Clock
import app.tiviate.ui.components.ProgressLine
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.components.progressOf
import app.tiviate.ui.components.rememberNow
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.t
import app.tiviate.ui.tf

/** Bottom info bar shown while zapping: channel, now/next, progress and stream badges. */
@Composable
fun ZapBanner(
    channel: ChannelEntity,
    info: NowNext?,
    playback: PlaybackState,
    showNumber: Boolean,
    requestInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
    source: String? = null,
) {
    LaunchedEffect(channel.epgRef) { channel.epgRef?.let(requestInfo) }
    val now = rememberNow()
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Tokens.Scrim)))
            .padding(start = Tokens.SafeMargin, end = Tokens.SafeMargin, top = 64.dp, bottom = 36.dp)
            .testTag("zap_banner"),
    ) {
        ChannelInfo(channel, info, playback, showNumber, now, source)
    }
}

/** Logo, number and name, stream badges, programme on now with progress, next programme and the clock. */
@Composable
fun ChannelInfo(channel: ChannelEntity, info: NowNext?, playback: PlaybackState, showNumber: Boolean, now: Long, source: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        ChannelLogo(channel, 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // "Show playlist and group name"
            source?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showNumber) Text("${channel.number}", style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Bold))
                Text(channel.name, style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Badges(playback)
            }
            val current = info?.now
            if (current != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatTime(current.startMs)} – ${formatTime(current.stopMs)}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
                    Text(current.title, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProgressLine(progressOf(current.startMs, current.stopMs, now), Modifier.width(520.dp))
                info.next?.let { next ->
                    Text(tf("Next: %s  %s", formatTime(next.startMs), next.title), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(t("No programme information"), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            }
        }
        Clock(TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun Badges(p: PlaybackState) {
    val labels = buildList {
        if (p.videoHeight > 0) add(resolutionLabel(p.videoHeight))
        p.frameRate?.let { add("${"%.0f".format(it)} fps") }
        p.audioLabel?.let { add(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach {
            Text(
                it,
                style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium),
                modifier = Modifier.background(Tokens.Raised, Tokens.SmallShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

fun resolutionLabel(height: Int) = when {
    height >= 2000 -> "4K"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    height >= 540 -> "576p"
    else -> "${height}p"
}
