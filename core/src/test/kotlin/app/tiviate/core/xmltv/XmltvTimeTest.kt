package app.tiviate.core.xmltv

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvTimeTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun positiveOffset() = assertEquals(ms("2026-10-23T10:00:00Z"), XmltvTime.parse("20261023120000 +0200"))

    @Test
    fun noOffsetIsUtc() = assertEquals(ms("2026-10-23T12:00:00Z"), XmltvTime.parse("20261023120000"))

    @Test
    fun minutePrecisionNegativeOffset() = assertEquals(ms("2026-10-23T17:00:00Z"), XmltvTime.parse("202610231200 -0500"))

    @Test
    fun offsetWithoutSpace() = assertEquals(ms("2026-10-23T06:30:00Z"), XmltvTime.parse("20261023120000+0530"))

    @Test
    fun garbageIsNull() {
        assertNull(XmltvTime.parse("not a time"))
        assertNull(XmltvTime.parse(""))
    }
}
