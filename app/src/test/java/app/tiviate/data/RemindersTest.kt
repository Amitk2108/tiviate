package app.tiviate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemindersTest {
    @Test
    fun aReminderSurvivesBeingStored() {
        val r = Reminder(1_800_000_000_000L, 3, "tvg:bbc|one@x", "News | at: 10", "BBC One: HD")
        assertEquals(r, Reminders.decode(Reminders.encode(r)))
        assertEquals("3:tvg:bbc|one@x@1800000000000", r.key)
    }

    @Test
    fun damagedEntriesAreSkipped() {
        assertNull(Reminders.decode(""))
        assertNull(Reminders.decode("abc\u001F1\u001Fk\u001Fch\u001Ft"))
        val good = Reminders.encode(Reminder(2_000, 1, "k", "Late", "C"))
        val early = Reminders.encode(Reminder(1_000, 1, "k", "Early", "C"))
        assertEquals(listOf("Early", "Late"), Reminders.all(AppSettings(reminders = setOf(good, "junk", early))).map { it.title })
    }
}
