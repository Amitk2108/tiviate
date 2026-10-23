package app.tiviate.ui.player

import androidx.compose.foundation.background
import app.tiviate.player.PlaybackIssue
import app.tiviate.data.db.ChannelEntity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.tiviate.ui.components.ActionButton
import app.tiviate.ui.components.Spinner
import app.tiviate.ui.theme.Tokens
import app.tiviate.ui.t
import app.tiviate.ui.tf

@Composable
fun BufferingIndicator(retrying: Int, notice: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spinner()
            if (notice != null) {
                Text(t(notice), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize, textAlign = TextAlign.Center), modifier = Modifier.width(520.dp).background(Tokens.Scrim, Tokens.Shape).padding(12.dp))
            } else if (retrying > 0) {
                Text(tf("Reconnecting… (%d)", retrying), style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize), modifier = Modifier.background(Tokens.Scrim, Tokens.Shape).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
fun PausedBadge() {
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin).testTag("paused_badge"), contentAlignment = Alignment.TopStart) {
        Text("❚❚  Paused", style = TextStyle(color = Tokens.Text, fontSize = Tokens.HeadingSize, fontWeight = FontWeight.SemiBold), modifier = Modifier.background(Tokens.Scrim, Tokens.Shape).padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
fun NumberBadge(text: String) {
    Box(Modifier.fillMaxSize().padding(Tokens.SafeMargin), contentAlignment = Alignment.TopEnd) {
        Text(text, style = TextStyle(color = Tokens.Cyan, fontSize = 56.sp, fontWeight = FontWeight.Bold), modifier = Modifier.background(Tokens.Scrim, Tokens.Shape).padding(horizontal = 24.dp, vertical = 8.dp).testTag("number_entry"))
    }
}

@Composable
fun Toast(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
        Text(text, style = TextStyle(color = Tokens.Text, fontSize = Tokens.BodySize), modifier = Modifier.background(Tokens.Raised, Tokens.Shape).padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

/** Friendly, explanatory error screen: what happened, why, and what the viewer can do. */
@Composable
fun ErrorCard(
    issue: PlaybackIssue,
    detail: String?,
    channel: ChannelEntity?,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onExternal: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(issue, channel?.stableKey) { focusSoon(first) }
    Box(Modifier.fillMaxSize().background(Tokens.Scrim).testTag("error_card"), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(640.dp)
                .background(Tokens.Surface, Tokens.Shape)
                .border(1.dp, Tokens.Outline, Tokens.Shape)
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IssueIcon(issue)
            channel?.let {
                Text("${it.number}  ·  ${it.name}", style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.Medium))
            }
            Text(t(issue.title), style = TextStyle(color = Tokens.Text, fontSize = Tokens.TitleSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
            Text(t(issue.body), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize, textAlign = TextAlign.Center))
            detail?.let {
                Text(
                    tf("Details: %s", it),
                    style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.CaptionSize),
                    modifier = Modifier.background(Tokens.Raised, Tokens.SmallShape).padding(horizontal = 10.dp, vertical = 4.dp).testTag("error_detail"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(t("Try again"), onRetry, Modifier.focusRequester(first), primary = true, tag = "error_retry")
                ActionButton(t("Next channel"), onNext, tag = "error_next")
                ActionButton(t("Other player"), onExternal, tag = "error_external")
            }
        }
    }
}

/** A small line icon per issue on a brand-gradient ring, drawn with Canvas (no image assets). */
@Composable
private fun IssueIcon(issue: PlaybackIssue) {
    Canvas(Modifier.size(84.dp)) {
        val w = size.minDimension
        val c = center
        val stroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(Tokens.Raised, radius = w / 2)
        drawCircle(Tokens.BrandGradient, radius = w / 2 - stroke.width / 2, style = Stroke(stroke.width))
        val ink = Tokens.Text
        val u = w / 10f
        when (issue) {
            PlaybackIssue.CHANNEL_UNAVAILABLE -> {
                drawRoundRect(ink, Offset(c.x - 2.6f * u, c.y - 1.8f * u), Size(5.2f * u, 3.4f * u), CornerRadius(0.5f * u), style = stroke)
                drawLine(ink, Offset(c.x - 1.2f * u, c.y + 2.4f * u), Offset(c.x + 1.2f * u, c.y + 2.4f * u), stroke.width, StrokeCap.Round)
                drawLine(Tokens.Danger, Offset(c.x - 3f * u, c.y - 3f * u), Offset(c.x + 3f * u, c.y + 3f * u), stroke.width, StrokeCap.Round)
            }
            PlaybackIssue.ACCOUNT_BUSY -> {
                drawCircle(ink, radius = 0.9f * u, center = Offset(c.x - 1.2f * u, c.y - 1.2f * u), style = stroke)
                drawArc(ink, 200f, 140f, false, Offset(c.x - 3f * u, c.y + 0.2f * u), Size(3.6f * u, 3.6f * u), style = stroke)
                drawCircle(Tokens.Cyan, radius = 0.9f * u, center = Offset(c.x + 1.4f * u, c.y - 0.8f * u), style = stroke)
                drawArc(Tokens.Cyan, 200f, 140f, false, Offset(c.x - 0.4f * u, c.y + 0.6f * u), Size(3.6f * u, 3.6f * u), style = stroke)
            }
            PlaybackIssue.ACCESS_DENIED -> {
                drawRoundRect(ink, Offset(c.x - 1.8f * u, c.y - 0.4f * u), Size(3.6f * u, 2.8f * u), CornerRadius(0.4f * u), style = stroke)
                drawArc(ink, 180f, 180f, false, Offset(c.x - 1.2f * u, c.y - 2.6f * u), Size(2.4f * u, 4.2f * u), style = stroke)
            }
            PlaybackIssue.NO_CONNECTION -> {
                for (k in 1..3) {
                    val r = k * 1.1f * u
                    drawArc(ink, 225f, 90f, false, Offset(c.x - r, c.y + 1.4f * u - r), Size(2 * r, 2 * r), style = stroke)
                }
                drawCircle(ink, radius = 0.35f * u, center = Offset(c.x, c.y + 1.4f * u))
                drawLine(Tokens.Danger, Offset(c.x - 3f * u, c.y - 3f * u), Offset(c.x + 3f * u, c.y + 3f * u), stroke.width, StrokeCap.Round)
            }
            PlaybackIssue.UNSUPPORTED_FORMAT -> {
                drawRoundRect(ink, Offset(c.x - 2.4f * u, c.y - 1.8f * u), Size(4.8f * u, 3.6f * u), CornerRadius(0.4f * u), style = stroke)
                val path = Path().apply {
                    moveTo(c.x - 0.7f * u, c.y - 1f * u); lineTo(c.x + 1.1f * u, c.y); lineTo(c.x - 0.7f * u, c.y + 1f * u); close()
                }
                drawPath(path, Tokens.Cyan)
            }
            PlaybackIssue.UNKNOWN -> {
                drawLine(ink, Offset(c.x, c.y - 2.2f * u), Offset(c.x, c.y + 0.6f * u), stroke.width, StrokeCap.Round)
                drawCircle(ink, radius = 0.4f * u, center = Offset(c.x, c.y + 2f * u))
            }
        }
    }
}
