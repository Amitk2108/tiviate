package app.tiviate.data.record

import java.io.IOException
import java.io.OutputStream
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/** Just enough of HLS to record a live stream: pick the best variant, then follow its segments. */
internal object Hls {
    data class Segment(val sequence: Long, val uri: String)
    data class Media(val segments: List<Segment>, val targetDurationMs: Long, val ended: Boolean, val init: String?, val encrypted: Boolean) {
        /** fMP4 segments (with an init section) make an .mp4 file; MPEG-TS segments a .ts file. */
        val extension: String get() = if (init != null) "mp4" else "ts"
    }

    fun isPlaylist(text: String) = text.trimStart().startsWith("#EXTM3U")
    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    /** The variant with the highest BANDWIDTH (the first one when none says). */
    fun bestVariant(text: String, base: String): String? {
        var best: String? = null
        var bestRate = -1L
        var pending: Long? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> pending = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    if (pending > bestRate) { bestRate = pending; best = resolve(base, line) }
                    pending = null
                }
            }
        }
        return best
    }

    fun media(text: String, base: String): Media {
        var sequence = 0L
        var target = 6_000L
        var ended = false
        var init: String? = null
        var encrypted = false
        val segments = ArrayList<Segment>()
        var inSegment = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0) * 1000).toLong()
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-MAP:") -> init = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { resolve(base, it) }
                line.startsWith("#EXT-X-KEY:") -> encrypted = !line.contains("METHOD=NONE")
                line.startsWith("#EXTINF") -> inSegment = true
                line.isEmpty() || line.startsWith("#") -> Unit
                inSegment -> {
                    segments += Segment(sequence + segments.size, resolve(base, line))
                    inSegment = false
                }
            }
        }
        return Media(segments, target, ended, init, encrypted)
    }

    fun resolve(base: String, ref: String): String = runCatching { URI(base).resolve(ref.replace(" ", "%20")).toString() }.getOrDefault(ref)
}

/**
 * Saves a live stream to a file until [record]'s end time: a plain (MPEG-TS) stream as it arrives, or an
 * HLS playlist segment by segment. A dropped connection is reopened; the file then simply goes on.
 */
class StreamRecorder(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Records [url] until [untilMs]. [open] creates the file once the format is known ("ts" or "mp4");
     * [onProgress] gets the bytes written so far. Returns the bytes written.
     */
    suspend fun record(
        url: String,
        userAgent: String,
        headers: Map<String, String>,
        untilMs: Long,
        open: (extension: String) -> OutputStream,
        onProgress: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val first = get(url, userAgent, headers)
        val source = first.body.source()
        source.request(16)
        val head = source.buffer.snapshot(minOf(16L, source.buffer.size).toInt()).utf8()
        val hls = url.substringBefore('?').endsWith(".m3u8") || first.header("Content-Type").orEmpty().contains("mpegurl", ignoreCase = true) || Hls.isPlaylist(head)
        if (hls) {
            val text = first.use { it.body.string() }
            recordHls(url, text, userAgent, headers, untilMs, open, onProgress)
        } else {
            recordPlain(url, first, userAgent, headers, untilMs, open, onProgress)
        }
    }

    private suspend fun recordPlain(
        url: String, first: Response, userAgent: String, headers: Map<String, String>, untilMs: Long,
        open: (String) -> OutputStream, onProgress: (Long) -> Unit,
    ): Long {
        var out: OutputStream? = null
        var total = 0L
        var response: Response? = first
        var failures = 0
        val buffer = ByteArray(64 * 1024)
        try {
            while (clock() < untilMs) {
                coroutineContext.ensureActive()
                val r = response ?: runCatching { get(url, userAgent, headers) }.getOrNull()
                response = null
                var got = 0L
                if (r != null) {
                    try {
                        val input = r.body.byteStream()
                        while (clock() < untilMs) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            val o = out ?: open("ts").also { out = it }
                            o.write(buffer, 0, n)
                            total += n
                            got += n
                            onProgress(total)
                        }
                    } catch (e: IOException) {
                        // Connection dropped: reopened below.
                    } finally {
                        r.close()
                    }
                }
                if (clock() >= untilMs) break
                failures = if (got > RESET_AFTER_BYTES) 1 else failures + 1
                if (failures > MAX_FAILURES) throw IOException("The stream kept stopping")
                pause(RETRY_MS)
            }
        } finally {
            out?.close()
        }
        return total
    }

    private suspend fun recordHls(
        url: String, firstText: String, userAgent: String, headers: Map<String, String>, untilMs: Long,
        open: (String) -> OutputStream, onProgress: (Long) -> Unit,
    ): Long {
        var playlistUrl = url
        var text = firstText
        if (Hls.isMaster(text)) {
            playlistUrl = Hls.bestVariant(text, url) ?: throw IOException("The stream has no playable variant")
            text = getText(playlistUrl, userAgent, headers)
        }
        var out: OutputStream? = null
        var total = 0L
        var lastSequence = -1L
        var failures = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                val media = Hls.media(text, playlistUrl)
                if (media.encrypted) throw IOException("This stream is encrypted and can't be recorded")
                val o = out ?: open(media.extension).also { o -> out = o; media.init?.let { total += copy(it, userAgent, headers, o) } }
                for (segment in media.segments) {
                    if (segment.sequence <= lastSequence) continue
                    if (clock() >= untilMs) return total
                    total += runCatching { copy(segment.uri, userAgent, headers, o) }.getOrElse { 0L }
                    lastSequence = segment.sequence
                    onProgress(total)
                }
                if (media.ended || clock() >= untilMs) return total
                pause((media.targetDurationMs / 2).coerceIn(1_000, 6_000))
                text = try {
                    getText(playlistUrl, userAgent, headers).also { failures = 0 }
                } catch (e: IOException) {
                    if (++failures > MAX_FAILURES) throw e
                    text
                }
            }
        } finally {
            out?.close()
        }
    }

    private fun copy(url: String, userAgent: String, headers: Map<String, String>, out: OutputStream): Long =
        get(url, userAgent, headers).use { r -> r.body.byteStream().copyTo(out) }

    private fun getText(url: String, userAgent: String, headers: Map<String, String>): String = get(url, userAgent, headers).use { it.body.string() }

    private fun get(url: String, userAgent: String, headers: Map<String, String>): Response {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response
    }

    private companion object {
        const val MAX_FAILURES = 8
        const val RETRY_MS = 3_000L
        /** A connection that delivered this much counts as working (the failure count starts again). */
        const val RESET_AFTER_BYTES = 1_000_000L
    }
}
