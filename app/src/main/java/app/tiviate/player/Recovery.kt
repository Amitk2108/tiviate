package app.tiviate.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException

/** Exponential backoff for reconnecting a failed stream: 1, 2, 4, 8, 16 s, then give up. */
class RetryPolicy(private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000)) {
    fun nextDelay(attempt: Int): Long? = delaysMs.getOrNull(attempt)

    companion object {
        /**
         * Slower, longer schedule (~83 s) for "account busy" refusals: the stream is fine, another
         * session holds the account's only connection and usually frees it within a minute.
         */
        fun accountBusy() = RetryPolicy(listOf(3_000, 5_000, 8_000, 10_000, 12_000, 15_000, 15_000, 15_000))
    }
}

/**
 * Why a channel isn't playing, in words a viewer understands. Each issue carries its own retry
 * schedule: an account busy elsewhere is worth waiting for; a channel that's down at the provider is not.
 */
enum class PlaybackIssue(val title: String, val body: String, val retry: RetryPolicy, val longRetry: Boolean = false) {
    CHANNEL_UNAVAILABLE(
        "This channel isn't available right now",
        "Your provider couldn't start this channel. It is probably down at the provider's end — other channels should still play.",
        RetryPolicy(listOf(1_000, 3_000)),
    ),
    ACCOUNT_BUSY(
        "Your account is in use elsewhere",
        "Your provider allows a limited number of connections and another device is using it. Close the other player, then try again.",
        RetryPolicy.accountBusy(),
        longRetry = true,
    ),
    ACCESS_DENIED(
        "Your provider denied access",
        "Your subscription may have expired, or this channel isn't part of your package.",
        RetryPolicy(listOf(2_000)),
    ),
    NO_CONNECTION(
        "Can't reach the stream",
        "Check that this device is connected to the internet. Tiviate keeps trying automatically.",
        RetryPolicy(),
    ),
    UNSUPPORTED_FORMAT(
        "This device can't play this channel",
        "The channel uses a format this TV can't decode. Try opening it in another player.",
        RetryPolicy(emptyList()),
    ),
    UNKNOWN(
        "Playback stopped",
        "Something went wrong while playing this channel.",
        RetryPolicy(),
    ),
    ;

    companion object {
        fun classify(httpStatus: Int?, errorCode: Int): PlaybackIssue = when {
            httpStatus == 458 || httpStatus == 407 || httpStatus == 429 || httpStatus == 509 -> ACCOUNT_BUSY
            httpStatus == 401 || httpStatus == 403 -> ACCESS_DENIED
            httpStatus != null && httpStatus >= 400 -> CHANNEL_UNAVAILABLE
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> NO_CONNECTION
            errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> UNSUPPORTED_FORMAT
            else -> UNKNOWN
        }
    }
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
