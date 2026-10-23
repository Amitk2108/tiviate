package app.tiviate.data.sync

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
}
