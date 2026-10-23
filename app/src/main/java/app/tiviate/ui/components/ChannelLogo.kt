package app.tiviate.ui.components

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import androidx.compose.ui.graphics.FilterQuality
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
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
fun logoCandidates(channel: ChannelEntity): List<String> = LogoUrls.order(imageCandidates(channel.logoUrl, channel.logoFallback))

/**
 * Which logo links work, for this run of the app: the one that loaded is tried first next time and
 * the ones that failed are skipped, so scrolling back never waits on a dead https/http attempt again.
 */
object LogoUrls {
    private val good = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val bad = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var prefs: android.content.SharedPreferences? = null

    /** Loads the links that worked last time, so a restart does not retry dead ones first. */
    fun init(context: android.content.Context) {
        val p = context.getSharedPreferences("logo_urls", android.content.Context.MODE_PRIVATE)
        prefs = p
        good += p.getStringSet("good", emptySet()).orEmpty()
    }

    private var unsaved = 0

    fun worked(url: String) {
        bad -= url
        // Saved in batches: writing the whole set for every logo made the first start of a big playlist stutter.
        if (good.add(url) && ++unsaved >= 25) save()
    }

    fun save() {
        unsaved = 0
        prefs?.edit()?.putStringSet("good", HashSet(good))?.apply()
    }
    fun failed(url: String) { if (url !in good) bad += url }

    fun order(candidates: List<String>): List<String> {
        val known = candidates.filter { it in good }
        if (known.isNotEmpty()) return known
        return candidates.filter { it !in bad }.ifEmpty { candidates.takeLast(1) }
    }
}

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
    // The letters show only if no logo arrived soon (or none exists): a saved logo appears without a flash.
    var showMonogram by remember(url) { mutableStateOf(url == null) }
    LaunchedEffect(url) { if (url != null) { kotlinx.coroutines.delay(400); if (!loaded) showMonogram = true } }
    val px = with(LocalDensity.current) { size.roundToPx() }
    val context = LocalPlatformContext.current
    // Decoded at twice the tile size: the TV scales the whole picture up to 4K, so small logos stay crisp.
    val request = remember(url, px) { url?.let { ImageRequest.Builder(context).data(it).size(px * 2).build() } }
    Box(modifier.size(size).clip(Tokens.LogoShape).background(Tokens.Raised), contentAlignment = Alignment.Center) {
        if (showMonogram && !loaded) Monogram(channel.name, size)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().padding(2.dp),
                onSuccess = { loaded = true; LogoUrls.worked(url!!) },
                onError = { LogoUrls.failed(url!!); index++ },
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

/** The logo links still to download: one per channel, skipping those already saved on the device. */
fun logosToFetch(channels: List<ChannelEntity>, isSaved: (String) -> Boolean): List<String> =
    channels.mapNotNull { logoCandidates(it).firstOrNull() }.distinct().filterNot(isSaved)

/**
 * Downloads the logos that are not on the device yet, so the lists show them at once. Logos already
 * saved are left alone (every start after the first does nothing here). The rest load a few at a
 * time after the start has settled, straight to disk: decoding hundreds of logos into memory at
 * start-up made zapping and group changes stutter on low-end TVs.
 */
suspend fun prefetchLogos(context: android.content.Context, channels: List<ChannelEntity>, sizePx: Int) =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val loader = coil3.SingletonImageLoader.get(context)
        val disk = loader.diskCache
        val missing = logosToFetch(channels) { url -> disk?.openSnapshot(url)?.use { true } ?: false }
        if (missing.isEmpty()) return@withContext
        kotlinx.coroutines.delay(5_000)
        for (batch in missing.chunked(4)) {
            batch.map { url ->
                async {
                    val ok = loader.execute(
                        ImageRequest.Builder(context).data(url).size(sizePx)
                            .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                            .build(),
                    ) is coil3.request.SuccessResult
                    if (ok) LogoUrls.worked(url) else LogoUrls.failed(url)
                }
            }.awaitAll()
        }
        LogoUrls.save()
    }
