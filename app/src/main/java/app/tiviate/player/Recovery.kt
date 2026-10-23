package app.tiviate.player

import androidx.media3.common.MimeTypes

/** Exponential backoff for reconnecting a failed stream: 1, 2, 4, 8, 16 s, then give up. */
class RetryPolicy(private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000)) {
    fun nextDelay(attempt: Int): Long? = delaysMs.getOrNull(attempt)
}

/**
 * Detects streams that are stuck: buffering for longer than [bufferingLimitMs], or "playing" while the
 * position hasn't moved for [frozenLimitMs] (a frozen picture). Fed once per second by the engine.
 */
class StallWatchdog(private val bufferingLimitMs: Long = 20_000, private val frozenLimitMs: Long = 10_000) {
    private var bufferingSince: Long? = null
    private var lastPosition: Long = Long.MIN_VALUE
    private var lastAdvanceAt: Long? = null

    /** Returns true when the stream should be re-prepared. */
    fun onSample(nowMs: Long, isPlaying: Boolean, isBuffering: Boolean, positionMs: Long): Boolean {
        if (isBuffering) {
            val since = bufferingSince ?: nowMs.also { bufferingSince = it }
            if (nowMs - since >= bufferingLimitMs) return trigger()
        } else {
            bufferingSince = null
        }

        if (isPlaying) {
            if (positionMs != lastPosition || lastAdvanceAt == null) {
                lastPosition = positionMs
                lastAdvanceAt = nowMs
            } else if (nowMs - lastAdvanceAt!! >= frozenLimitMs) {
                return trigger()
            }
        } else if (!isBuffering) {
            lastAdvanceAt = null // paused: nothing to watch
        }
        return false
    }

    fun reset() {
        bufferingSince = null
        lastPosition = Long.MIN_VALUE
        lastAdvanceAt = null
    }

    private fun trigger(): Boolean {
        reset()
        return true
    }
}

object StreamType {
    /** Picks the media type from the URL path; null lets ExoPlayer detect progressive formats (e.g. MPEG-TS). */
    fun mimeFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    /** True when the first bytes of a response are an HLS playlist (used when a URL has no extension). */
    fun looksLikeHls(firstBytes: ByteArray): Boolean =
        firstBytes.toString(Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")
}
