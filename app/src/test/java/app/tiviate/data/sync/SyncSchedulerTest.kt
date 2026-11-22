package app.tiviate.data.sync

import app.tiviate.data.PlaylistUpdate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {

    private val now = 1_792_756_800_000L
    private val hour = 3_600_000L

    @Test
    fun updatesOnStartOnlyWhenOnAndNotJustUpdated() {
        assertTrue("never updated", SyncScheduler.shouldSyncOnStart(enabled = true, lastSyncAt = null, nowMs = now))
        assertTrue(SyncScheduler.shouldSyncOnStart(enabled = true, lastSyncAt = now - 2 * hour, nowMs = now))
        assertFalse("updated minutes ago", SyncScheduler.shouldSyncOnStart(enabled = true, lastSyncAt = now - 10 * 60_000L, nowMs = now))
        assertFalse("turned off", SyncScheduler.shouldSyncOnStart(enabled = false, lastSyncAt = now - 48 * hour, nowMs = now))
        assertTrue("turned off, but nothing loaded yet", SyncScheduler.shouldSyncOnStart(enabled = false, lastSyncAt = null, nowMs = now))
    }

    /** New versions read new things from playlists (catch-up, DRM, films): loaded again once, whatever the schedule. */
    @Test
    fun anAppUpdateReloadsPlaylistsOnce() {
        val updated = now - hour
        assertTrue("last loaded by the old version", SyncScheduler.playlistDue(PlaylistUpdate.MONTHLY, now - 2 * hour, now, atStart = true, appUpdatedAt = updated))
        assertFalse("already loaded by this version", SyncScheduler.playlistDue(PlaylistUpdate.MONTHLY, now - hour / 2, now, atStart = true, appUpdatedAt = updated))
    }

    @Test
    fun playlistUpdatesFollowTheChosenSchedule() {
        val day = 24 * hour
        fun due(mode: PlaylistUpdate, ageMs: Long?, atStart: Boolean) =
            SyncScheduler.playlistDue(mode, ageMs?.let { now - it }, now, atStart, appUpdatedAt = 0)
        assertTrue("never loaded", due(PlaylistUpdate.MONTHLY, null, atStart = false))

        assertTrue(due(PlaylistUpdate.ON_START, 2 * hour, atStart = true))
        assertFalse("just updated", due(PlaylistUpdate.ON_START, 10 * 60_000L, atStart = true))
        assertFalse("only when Tiviate starts", due(PlaylistUpdate.ON_START, 3 * day, atStart = false))

        assertFalse(due(PlaylistUpdate.DAILY, 20 * hour, atStart = true))
        assertTrue(due(PlaylistUpdate.DAILY, day, atStart = false))
        assertFalse(due(PlaylistUpdate.WEEKLY, 6 * day, atStart = true))
        assertTrue(due(PlaylistUpdate.WEEKLY, 7 * day, atStart = true))
        assertFalse(due(PlaylistUpdate.MONTHLY, 29 * day, atStart = false))
        assertTrue(due(PlaylistUpdate.MONTHLY, 30 * day, atStart = false))
    }
}
