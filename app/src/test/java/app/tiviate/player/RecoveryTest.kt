package app.tiviate.player

import androidx.media3.common.MimeTypes
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

class RefusalTest {

    @Test
    fun accountBusyCodesAreRecognised() {
        // 458/407 are what single-session Xtream panels send; some (like the owner's provider) send a bare 400.
        listOf(400, 407, 429, 458, 503, 509).forEach { assertTrue("$it", Refusal.isAccountBusy(it)) }
        listOf(200, 401, 403, 404, 500).forEach { assertFalse("$it", Refusal.isAccountBusy(it)) }
    }

    @Test
    fun busyRetriesAreSlowerAndLastAboutNinetySeconds() {
        val p = RetryPolicy.accountBusy()
        val delays = generateSequence(0) { it + 1 }.map { p.nextDelay(it) }.takeWhile { it != null }.toList().filterNotNull()
        assertTrue(delays.sum() in 80_000L..120_000L)
        assertTrue(delays.first() >= 2_000L)
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
