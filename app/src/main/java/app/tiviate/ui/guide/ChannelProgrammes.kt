package app.tiviate.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.tiviate.core.catchup.Catchup
import app.tiviate.data.db.ChannelEntity
import app.tiviate.data.db.ProgrammeEntity
import app.tiviate.ui.LocalGraph
import app.tiviate.ui.components.formatTime
import app.tiviate.ui.t
import app.tiviate.ui.theme.Tokens

private const val DAY = 86_400_000L
private val ON_AIR = Color(0xFF0F1A2E)

/**
 * [channel]'s programmes, from as far back as its archive (or [pastDays] of guide) reaches to two days ahead.
 * Reloaded every five minutes of [now].
 */
@Composable
fun rememberChannelProgrammes(channel: ChannelEntity?, pastDays: Int, now: Long): List<ProgrammeEntity> {
    val graph = LocalGraph.current
    val ref = channel?.epgRef
    val archive = channel != null && Catchup.supported(channel.catchupType, channel.catchupSource)
    val back = maxOf(pastDays, if (archive) channel?.catchupDays?.takeIf { it > 0 } ?: Catchup.DEFAULT_DAYS else 0)
    val list by produceState(emptyList<ProgrammeEntity>(), ref, back, now / 300_000) {
        value = if (ref == null) emptyList() else graph.db.epg().programmesFor(listOf(ref), now - back * DAY, now + 2 * DAY)
    }
    return list
}

/** Where a channel's list starts: the programme on air, else the next one, else the last. */
fun onAirIndex(programmes: List<ProgrammeEntity>, now: Long): Int =
    programmes.indexOfFirst { it.stopMs > now }.let { if (it < 0) programmes.lastIndex else it }

/**
 * One channel's programmes as a list (GitHub issue #5): day, time, title, and ↺ on what can be watched again
 * from the archive. [selected]: the remote's place in it (TV); [onClick]: a tap (touch screens).
 */
@Composable
fun ChannelProgrammes(
    channel: ChannelEntity?,
    programmes: List<ProgrammeEntity>,
    now: Long,
    selected: Int?,
    state: LazyListState,
    modifier: Modifier = Modifier,
    onClick: ((ProgrammeEntity) -> Unit)? = null,
) {
    Box(modifier.testTag("guide_channel_programmes")) {
        if (channel == null || programmes.isEmpty()) {
            Text(t("No programme information"), modifier = Modifier.align(Alignment.Center), style = TextStyle(color = Tokens.TextSecondary, fontSize = Tokens.BodySize))
            return@Box
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), userScrollEnabled = onClick != null, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(programmes, key = { _, p -> p.id }) { i, p ->
                Column {
                    val day = dayLabel(p.startMs, now)
                    if (i == 0 || dayLabel(programmes[i - 1].startMs, now) != day) {
                        Text(day, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp), style = TextStyle(color = Tokens.Cyan, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold))
                    }
                    ProgrammeLine(channel, p, now, i == selected, if (onClick == null) Modifier else Modifier.clickable { onClick(p) })
                }
            }
        }
    }
}

@Composable
private fun ProgrammeLine(channel: ChannelEntity, p: ProgrammeEntity, now: Long, selected: Boolean, modifier: Modifier) {
    val live = p.startMs <= now && now < p.stopMs
    val replay = Catchup.available(channel.catchupType, channel.catchupSource, channel.catchupDays, p.startMs, p.stopMs, now)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            .background(if (selected) Tokens.Raised else if (live) ON_AIR else Color.Transparent, Tokens.SmallShape)
            .then(if (selected) Modifier.border(1.dp, Tokens.Selector.copy(alpha = 0.45f), Tokens.SmallShape).testTag("guide_programme_selected") else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            formatTime(p.startMs), modifier = Modifier.width(56.dp),
            style = TextStyle(color = if (live) Tokens.Cyan else Tokens.TextSecondary, fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold),
        )
        if (replay) Icon(Icons.Rounded.History, contentDescription = t("Catch-up"), tint = Tokens.Cyan, modifier = Modifier.size(14.dp))
        Text(
            p.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (p.stopMs <= now && !selected) Tokens.TextSecondary else Tokens.Text,
                fontSize = Tokens.BodySize, fontWeight = if (live || selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

/** "TV guide · This channel" above the TV guide: Up from the top row reaches them, Left and Right switch. */
@Composable
internal fun GuideTabs(listView: Boolean, focused: Boolean) {
    Row(
        Modifier.padding(start = 4.dp, bottom = 6.dp).then(if (focused) Modifier.testTag("guide_tabs_focused") else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, on) in listOf(t("TV guide") to !listView, t("This channel") to listView)) {
            Text(
                label,
                modifier = Modifier
                    .background(if (on && focused) Tokens.Selector else if (on) Tokens.Raised else Color.Transparent, Tokens.PillShape)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                style = TextStyle(
                    color = if (on && focused) Tokens.Background else if (on) Tokens.Text else Tokens.TextSecondary,
                    fontSize = Tokens.CaptionSize, fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}
