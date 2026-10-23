package app.tiviate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.tiviate.data.db.ChannelEntity
import app.tiviate.ui.theme.Tokens
import coil3.compose.AsyncImage

/**
 * Logo URLs to try, in order: playlist logo, then the guide's channel icon. Plain-http URLs are tried
 * over https first, because some networks/ISP proxies stall plain http to image CDNs.
 */
fun logoCandidates(channel: ChannelEntity): List<String> = imageCandidates(channel.logoUrl, channel.logoFallback)

fun imageCandidates(vararg urls: String?): List<String> =
    urls.filterNotNull()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { url -> if (url.startsWith("http://")) listOf("https://" + url.removePrefix("http://"), url) else listOf(url) }
        .distinct()

/** An image that tries each candidate URL in turn and shows nothing (just its background) if all fail. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val candidates = remember(url) { imageCandidates(url) }
    var index by remember(candidates) { mutableIntStateOf(0) }
    val current = candidates.getOrNull(index) ?: return
    AsyncImage(model = current, contentDescription = null, contentScale = contentScale, modifier = modifier, onError = { index++ })
}

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
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.size(size).clip(Tokens.SmallShape).background(Tokens.Raised), contentAlignment = Alignment.Center) {
        // The monogram stays visible until a logo has actually loaded, so a tile is never blank.
        if (!loaded) Monogram(channel.name, size)
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                onSuccess = { loaded = true },
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
