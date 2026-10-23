package app.tiviate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.theme.Tokens
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Logo URLs to try, in order: playlist logo, then the guide's channel icon. */
fun logoCandidates(channel: ChannelEntity): List<String> =
    listOfNotNull(channel.logoUrl, channel.logoFallback).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun monogramInitials(name: String): String {
    val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

/**
 * Channel logo that never shows an empty tile: it tries each candidate URL and falls back to a
 * monogram. Images are decoded at display size to keep memory low on TV boxes.
 */
@Composable
fun ChannelLogo(channel: ChannelEntity, size: Dp, modifier: Modifier = Modifier) {
    val candidates = remember(channel.logoUrl, channel.logoFallback) { logoCandidates(channel) }
    var index by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(index)
    val px = with(LocalDensity.current) { size.roundToPx() }
    Box(modifier.size(size).clip(Tokens.SmallShape).background(Tokens.Raised), contentAlignment = Alignment.Center) {
        if (url == null) {
            Monogram(channel.name, size)
        } else {
            val context = LocalContext.current
            val request = remember(url, px) { ImageRequest.Builder(context).data(url).size(px).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { index++ },
            )
        }
    }
}

@Composable
fun Monogram(name: String, size: Dp) {
    Box(Modifier.fillMaxSize().background(Tokens.BrandGradient, alpha = 0.55f), contentAlignment = Alignment.Center) {
        Text(
            monogramInitials(name),
            color = Tokens.Text,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.34f).sp,
        )
    }
}
