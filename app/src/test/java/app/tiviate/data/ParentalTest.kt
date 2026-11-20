package app.tiviate.data

import app.tiviate.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalTest {
    private fun channel(key: String, group: String?) = ChannelEntity(
        playlistId = 3, stableKey = key, number = 1, name = key, groupName = group, logoUrl = null, streamUrl = "http://x/$key",
        tvgId = null, tvgName = null, userAgent = null, referrer = null, epgRef = null, logoFallback = null,
        catchupDays = null, sortIndex = 1, syncStamp = 1,
    )

    @Test
    fun thePinIsStoredAsAHashOnly() {
        val hash = Parental.hash("1234")
        assertEquals(64, hash.length)
        assertNotEquals("1234", hash)
        assertEquals("the same PIN always gives the same hash", hash, Parental.hash("1234"))
        val s = AppSettings(pinHash = hash)
        assertTrue(Parental.matches(s, "1234"))
        assertFalse(Parental.matches(s, "4321"))
        assertFalse("no PIN set: nothing matches", Parental.matches(AppSettings(), "1234"))
    }

    @Test
    fun aChannelIsLockedByItselfOrByItsGroup() {
        val kids = channel("k1", "Kids")
        val news = channel("n1", "News")
        val s = AppSettings(pinHash = Parental.hash("1234"), lockedChannels = setOf("3:n1"), lockedGroups = setOf("3|Kids"))
        assertTrue(Parental.locked(s, kids))
        assertTrue(Parental.locked(s, news))
        assertFalse(Parental.locked(s, channel("m1", "Movies")))
        assertFalse("the same group name in another playlist", Parental.locked(s, kids.copy(playlistId = 4)))
        assertFalse("no PIN: locks don't apply", Parental.locked(s.copy(pinHash = null), kids))
    }
}
