package app.tiviate.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun backoffThenGivesUp() {
        val p = RetryPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), (0..4).map { p.nextDelay(it) })
        assertNull(p.nextDelay(5))
    }
}

class PlaybackIssueTest {

    @Test
    fun classifiesHttpStatuses() {
        assertEquals(PlaybackIssue.ACCOUNT_BUSY, PlaybackIssue.classify(httpStatus = 458, errorCode = 0))
        assertEquals(PlaybackIssue.ACCOUNT_BUSY, PlaybackIssue.classify(httpStatus = 407, errorCode = 0))
        assertEquals(PlaybackIssue.ACCOUNT_BUSY, PlaybackIssue.classify(httpStatus = 429, errorCode = 0))
        // The owner's provider answers 400 for channels its upstream can't open (other channels play).
        assertEquals(PlaybackIssue.CHANNEL_UNAVAILABLE, PlaybackIssue.classify(httpStatus = 400, errorCode = 0))
        assertEquals(PlaybackIssue.CHANNEL_UNAVAILABLE, PlaybackIssue.classify(httpStatus = 404, errorCode = 0))
        assertEquals(PlaybackIssue.CHANNEL_UNAVAILABLE, PlaybackIssue.classify(httpStatus = 502, errorCode = 0))
        assertEquals(PlaybackIssue.ACCESS_DENIED, PlaybackIssue.classify(httpStatus = 401, errorCode = 0))
        assertEquals(PlaybackIssue.ACCESS_DENIED, PlaybackIssue.classify(httpStatus = 403, errorCode = 0))
    }

    @Test
    fun classifiesPlayerErrorCodes() {
        assertEquals(PlaybackIssue.NO_CONNECTION, PlaybackIssue.classify(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlaybackIssue.NO_CONNECTION, PlaybackIssue.classify(null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(PlaybackIssue.UNSUPPORTED_FORMAT, PlaybackIssue.classify(null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackIssue.UNSUPPORTED_FORMAT, PlaybackIssue.classify(null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackIssue.UNKNOWN, PlaybackIssue.classify(null, PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun onlyAccountBusyUsesTheLongWait() {
        assertTrue(PlaybackIssue.ACCOUNT_BUSY.longRetry)
        assertFalse(PlaybackIssue.CHANNEL_UNAVAILABLE.longRetry)
        val p = RetryPolicy.accountBusy()
        val delays = generateSequence(0) { it + 1 }.map { p.nextDelay(it) }.takeWhile { it != null }.toList().filterNotNull()
        assertTrue(delays.sum() in 80_000L..120_000L)
    }

    @Test
    fun everyIssueHasFriendlyCopy() {
        PlaybackIssue.entries.forEach { assertTrue(it.title.isNotBlank() && it.body.isNotBlank()) }
    }
}

class StallWatchdogTest {

    @Test
    fun bufferingOver20sTriggers() {
        val w = StallWatchdog()
        assertFalse(w.onSample(0, isPlaying = false, isBuffering = true, positionMs = 0))
        assertFalse(w.onSample(19_999, isPlaying = false, isBuffering = true, positionMs = 0))
        assertTrue(w.onSample(20_000, isPlaying = false, isBuffering = true, positionMs = 0))
    }

    @Test
    fun frozenPositionOver10sWhilePlayingTriggers() {
        val w = StallWatchdog()
        assertFalse(w.onSample(0, isPlaying = true, isBuffering = false, positionMs = 5_000))
        assertFalse(w.onSample(9_000, isPlaying = true, isBuffering = false, positionMs = 5_000))
        assertTrue(w.onSample(10_000, isPlaying = true, isBuffering = false, positionMs = 5_000))
    }

    @Test
    fun advancingPositionNeverTriggers() {
        val w = StallWatchdog()
        for (t in 0..60_000 step 1_000) assertFalse(w.onSample(t.toLong(), isPlaying = true, isBuffering = false, positionMs = t.toLong()))
    }

    @Test
    fun pausedNeverTriggers() {
        val w = StallWatchdog()
        for (t in 0..60_000 step 1_000) assertFalse(w.onSample(t.toLong(), isPlaying = false, isBuffering = false, positionMs = 42))
    }

    @Test
    fun resetClearsTimers() {
        val w = StallWatchdog()
        w.onSample(0, isPlaying = false, isBuffering = true, positionMs = 0)
        w.reset()
        assertFalse(w.onSample(20_000, isPlaying = false, isBuffering = true, positionMs = 0))
    }

    @Test
    fun triggeringRestartsTheClock() {
        val w = StallWatchdog()
        w.onSample(0, isPlaying = false, isBuffering = true, positionMs = 0)
        assertTrue(w.onSample(20_000, isPlaying = false, isBuffering = true, positionMs = 0))
        assertFalse(w.onSample(21_000, isPlaying = false, isBuffering = true, positionMs = 0))
    }
}

class StreamTypeTest {

    @Test
    fun mimeFromUrlExtension() {
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamType.mimeFromUrl("https://h/live/u/p/6775.m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamType.mimeFromUrl("https://h/index.M3U8?token=abc"))
        assertEquals(MimeTypes.APPLICATION_MPD, StreamType.mimeFromUrl("https://h/manifest.mpd"))
        assertNull(StreamType.mimeFromUrl("http://h/live/u/p/1.ts"))
        assertNull(StreamType.mimeFromUrl("http://h/stream"))
    }

    @Test
    fun looksLikeHlsChecksPlaylistHeader() {
        assertTrue(StreamType.looksLikeHls("﻿#EXTM3U\n#EXT-X-VERSION:4".toByteArray()))
        assertTrue(StreamType.looksLikeHls("  #EXTM3U".toByteArray()))
        assertFalse(StreamType.looksLikeHls(byteArrayOf(0x47, 0x40, 0x11, 0x10)))
    }
}

class ReconnectorTest {

    private val scheduled = mutableListOf<Long>()
    private var gaveUp: PlaybackIssue? = null
    private val r = Reconnector(
        watchdog = StallWatchdog(bufferingLimitMs = 20_000),
        schedule = { delay, _ -> scheduled += delay },
        giveUp = { issue, _ -> gaveUp = issue },
    )

    /** Feeds one buffering sample per second, firing each scheduled reconnect as the engine would. */
    private fun bufferFor(seconds: Int, startMs: Long = 0) {
        var pendingUntil: Long? = null
        for (s in 0..seconds) {
            val now = startMs + s * 1_000L
            if (pendingUntil != null && now >= pendingUntil) {
                pendingUntil = null
                r.onStarted()
            }
            val before = scheduled.size
            r.onSample(now, isPlaying = false, isBuffering = true, positionMs = 0)
            if (scheduled.size > before) pendingUntil = now + scheduled.last()
        }
    }

    @Test
    fun aStreamThatNeverSendsDataEndsInTheErrorCard() {
        bufferFor(seconds = 300)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), scheduled)
        assertEquals(PlaybackIssue.NO_CONNECTION, gaveUp)
    }

    @Test
    fun errorsAndStallsShareOneBudget() {
        repeat(4) { r.onIssue(PlaybackIssue.NO_CONNECTION, null); r.onStarted() }
        bufferFor(seconds = 25) // one stall: the fifth and last reconnect
        assertNull(gaveUp)
        r.onIssue(PlaybackIssue.NO_CONNECTION, null)
        assertEquals(PlaybackIssue.NO_CONNECTION, gaveUp)
    }

    @Test
    fun playingAgainRestoresTheFullBudget() {
        repeat(5) { r.onIssue(PlaybackIssue.NO_CONNECTION, null); r.onStarted() }
        r.onPlaying()
        r.onIssue(PlaybackIssue.NO_CONNECTION, null)
        assertNull(gaveUp)
        assertEquals(1_000L, scheduled.last())
    }
}

class FormatFallbackTest {

    @Test
    fun tsAndHlsLinksSwapKeepingTheQuery() {
        assertEquals("http://h/live/u/p/42.m3u8", StreamFallback.otherFormat("http://h/live/u/p/42.ts"))
        assertEquals("http://h/live/u/p/42.ts", StreamFallback.otherFormat("http://h/live/u/p/42.m3u8"))
        assertEquals("http://h/a/42.m3u8?token=x", StreamFallback.otherFormat("http://h/a/42.ts?token=x"))
        assertNull("no known extension", StreamFallback.otherFormat("http://h/play/42"))
        assertNull(StreamFallback.otherFormat("http://h/movie.mp4"))
    }

    @Test
    fun onlyFormatProblemsTryTheOtherFormatAndOnlyOnce() {
        assertTrue(StreamFallback.worthTrying(PlaybackIssue.CHANNEL_UNAVAILABLE, alreadyTried = false))
        assertTrue(StreamFallback.worthTrying(PlaybackIssue.UNSUPPORTED_FORMAT, alreadyTried = false))
        assertTrue(StreamFallback.worthTrying(PlaybackIssue.UNKNOWN, alreadyTried = false))
        assertFalse("once per channel", StreamFallback.worthTrying(PlaybackIssue.CHANNEL_UNAVAILABLE, alreadyTried = true))
        assertFalse(StreamFallback.worthTrying(PlaybackIssue.ACCOUNT_BUSY, alreadyTried = false))
        assertFalse(StreamFallback.worthTrying(PlaybackIssue.ACCESS_DENIED, alreadyTried = false))
        assertFalse(StreamFallback.worthTrying(PlaybackIssue.NO_CONNECTION, alreadyTried = false))
    }
}
