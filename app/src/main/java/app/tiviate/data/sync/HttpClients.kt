package app.tiviate.data.sync

import android.os.Build
import app.tiviate.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object HttpClients {

    val defaultUserAgent: String =
        "Tiviate/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.11.1"

    /**
     * One shared client (one connection pool) for playlists, guides, logos and streams.
     * Follows http↔https redirects, which IPTV providers and CDNs use heavily.
     */
    fun create(userAgent: String = defaultUserAgent): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", userAgent).build() else req)
        }
        .build()
}

/** A sync failure with a message suitable to show the user. */
class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Executes a GET and returns the open response; throws [SyncException] for non-2xx statuses. */
internal fun OkHttpClient.getOrThrow(url: String, userAgent: String?, what: String): Response {
    val request = Request.Builder().url(url).apply { if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent) }.build()
    val response = try {
        newCall(request).execute()
    } catch (e: IOException) {
        throw SyncException("$what download failed: ${e.message ?: e.javaClass.simpleName}", e)
    }
    if (!response.isSuccessful) {
        response.close()
        throw SyncException("$what download failed: HTTP ${response.code}")
    }
    return response
}
