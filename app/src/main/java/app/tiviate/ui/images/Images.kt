package app.tiviate.ui.images

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** Some logo/CDN hosts reject non-browser clients, so images are fetched with a browser-like User-Agent. */
val IMAGE_USER_AGENT = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/**
 * Shares the app's connection pool but always presents a browser User-Agent. Short timeouts so a
 * dead logo URL falls through to the next candidate quickly instead of leaving a tile empty. Its own
 * dispatcher keeps slow logo hosts from taking the per-host request slots the stream needs.
 */
fun imageHttpClient(http: OkHttpClient): OkHttpClient = http.newBuilder()
    .dispatcher(Dispatcher())
    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", IMAGE_USER_AGENT).build()) }
    .build()

fun buildImageLoader(context: Context, http: OkHttpClient): ImageLoader {
    val imageHttp = imageHttpClient(http)
    return ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
            add(SvgDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .apply { if (app.tiviate.BuildConfig.DEBUG) logger(coil3.util.DebugLogger()) }
        .build()
}
